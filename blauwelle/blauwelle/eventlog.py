"""Ereignisprotokoll (SQLite). Speichert nur Zeit, Art, Zufahrt, Konfidenz –
keine Bilder, keine Tonaufnahmen, keine Kennzeichen (DSGVO)."""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from typing import Optional

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts REAL NOT NULL,
    kind TEXT NOT NULL,
    approach TEXT,
    confidence REAL,
    detail TEXT,
    feedback TEXT
);
CREATE INDEX IF NOT EXISTS events_ts ON events(ts);
"""

FEEDBACK_VALUES = ("korrekt", "fehlalarm", "verpasst")


class EventLog:
    def __init__(self, path: str = ":memory:", wall_clock=time.time):
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.executescript(SCHEMA)
        self._lock = threading.Lock()
        self.clock = wall_clock

    def add(self, kind: str, approach: Optional[str], confidence: float, detail: dict,
            ts: Optional[float] = None) -> int:
        with self._lock:
            cur = self._db.execute(
                "INSERT INTO events (ts, kind, approach, confidence, detail) VALUES (?,?,?,?,?)",
                (ts if ts is not None else self.clock(), kind, approach,
                 round(float(confidence), 3), json.dumps(detail, ensure_ascii=False)),
            )
            self._db.commit()
            return int(cur.lastrowid)

    def recent(self, limit: int = 50) -> list:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM events ORDER BY id DESC LIMIT ?", (int(limit),)
            ).fetchall()
        return [{**dict(r), "detail": json.loads(r["detail"] or "{}")} for r in rows]

    def set_feedback(self, event_id: int, value: str) -> bool:
        if value not in FEEDBACK_VALUES:
            raise ValueError(f"feedback muss eines von {FEEDBACK_VALUES} sein")
        with self._lock:
            cur = self._db.execute("UPDATE events SET feedback=? WHERE id=?", (value, int(event_id)))
            self._db.commit()
            return cur.rowcount == 1

    def stats(self, since_ts: float = 0.0) -> dict:
        with self._lock:
            rows = self._db.execute(
                "SELECT kind, feedback, detail FROM events WHERE ts >= ?", (since_ts,)
            ).fetchall()
        out = {"anforderungen": 0, "freigaben": 0, "fehler": 0, "konflikte": 0,
               "fehlalarme": 0, "zeit_bis_gruen_s_mittel": None, "dauer_s_mittel": None}
        ttg, dur = [], []
        for r in rows:
            d = json.loads(r["detail"] or "{}")
            k = r["kind"]
            if k == "request":
                out["anforderungen"] += 1
                if r["feedback"] == "fehlalarm":
                    out["fehlalarme"] += 1
            elif k == "release":
                out["freigaben"] += 1
                if "duration_s" in d:
                    dur.append(d["duration_s"])
            elif k == "fault":
                out["fehler"] += 1
            elif k == "conflict":
                out["konflikte"] += 1
            elif k == "green" and "time_to_green_s" in d:
                ttg.append(d["time_to_green_s"])
        if ttg:
            out["zeit_bis_gruen_s_mittel"] = round(sum(ttg) / len(ttg), 2)
        if dur:
            out["dauer_s_mittel"] = round(sum(dur) / len(dur), 2)
        return out

    def close(self) -> None:
        with self._lock:
            self._db.close()
