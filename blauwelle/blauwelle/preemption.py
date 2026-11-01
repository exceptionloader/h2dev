"""Zustandsautomat für die Vorrangschaltung (die Sicherheitslogik der Box).

    IDLE ──bestätigt──▶ REQUESTED ──Grün gemeldet──▶ ACTIVE
      ▲                     │                          │
      │            Fahrzeug weg / max_hold_s           │
      └──── COOLDOWN ◀──────┴──────────────────────────┘
    Jeder Fehler am Steuergerät ──▶ FAULT (alles freigegeben)

Grundregel: Im Zweifel wird freigegeben. Eine fehlende Anforderung kostet
Sekunden, eine hängende Anforderung blockiert die Kreuzung.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Optional

from .config import BoxConfig
from .interfaces import ControllerInterface

log = logging.getLogger(__name__)


class State(str, Enum):
    IDLE = "idle"
    REQUESTED = "requested"
    ACTIVE = "active"
    COOLDOWN = "cooldown"
    FAULT = "fault"


EventSink = Callable[[str, Optional[str], float, dict], None]


def _no_sink(kind, approach, conf, detail):
    return None


@dataclass
class Preemption:
    cfg: BoxConfig
    controller: ControllerInterface
    emit: EventSink = _no_sink
    state: State = State.IDLE
    approach: Optional[str] = None
    since: float = 0.0  # Zeitpunkt des letzten Zustandswechsels
    request_start: float = 0.0  # Beginn der aktuellen Anforderung
    cooldown_until: float = 0.0
    fault_reason: str = ""
    lockouts: dict = field(default_factory=dict)  # Zufahrt -> gesperrt bis
    _conflicts: set = field(default_factory=set)
    _ack_warned: bool = False
    _peak_conf: float = 0.0

    # -- Hilfen ----------------------------------------------------------
    def _set(self, state: State, now: float, approach: Optional[str] = None) -> None:
        log.info("Zustand %s -> %s (%s)", self.state.value, state.value, approach)
        self.state = state
        self.approach = approach
        self.since = now

    def _release(self, now: float, reason: str) -> None:
        aid = self.approach
        try:
            self.controller.release()
        except Exception as e:
            self._fault(now, f"Freigabe fehlgeschlagen: {e}")
            return
        self.emit("release", aid, self._peak_conf,
                  {"reason": reason, "duration_s": round(now - self.request_start, 2)})
        self.cooldown_until = now + self.cfg.safety.cooldown_s
        self._conflicts.clear()
        self._set(State.COOLDOWN, now)

    def _fault(self, now: float, reason: str) -> None:
        try:
            self.controller.release()
        except Exception:
            pass
        self.fault_reason = reason
        self.emit("fault", self.approach, 0.0, {"reason": reason})
        log.error("FEHLER: %s", reason)
        self._set(State.FAULT, now)

    # -- Von außen -------------------------------------------------------
    def reset(self, now: float) -> bool:
        if self.state != State.FAULT:
            return False
        self.emit("reset", None, 0.0, {"after": self.fault_reason})
        self.fault_reason = ""
        self._set(State.IDLE, now)
        return True

    def force_release(self, now: float, reason: str) -> None:
        """Für Watchdog und Herunterfahren."""
        if self.state in (State.REQUESTED, State.ACTIVE):
            self._release(now, reason)
        else:
            try:
                self.controller.release()
            except Exception:
                pass

    def step(self, now: float, confirmed: dict) -> None:
        """confirmed: {approach_id: konfidenz} der bestätigten Zufahrten."""
        s = self.cfg.safety
        try:
            self.controller.heartbeat()
        except Exception as e:
            self._fault(now, f"Heartbeat fehlgeschlagen: {e}")
            return

        if self.state == State.FAULT:
            if s.auto_recover_s > 0 and now - self.since >= s.auto_recover_s:
                self.reset(now)
            return

        if self.state == State.COOLDOWN:
            if now < self.cooldown_until:
                return
            self._set(State.IDLE, now)

        if self.state == State.IDLE:
            self._maybe_request(now, confirmed)
            return

        self._supervise(now, confirmed)

    # -- Zustände --------------------------------------------------------
    def _maybe_request(self, now: float, confirmed: dict) -> None:
        candidates = [a for a in confirmed if self.lockouts.get(a, 0.0) <= now]
        if not candidates:
            return
        aid = max(candidates, key=lambda a: (self.cfg.approach(a).priority, confirmed[a]))
        try:
            self.controller.request(aid)
        except Exception as e:
            self._fault(now, f"Anforderung fehlgeschlagen: {e}")
            return
        self._peak_conf = confirmed[aid]
        self._ack_warned = False
        self.request_start = now
        self.emit("request", aid, confirmed[aid], {})
        self._set(State.REQUESTED, now, aid)

    def _supervise(self, now: float, confirmed: dict) -> None:
        s = self.cfg.safety
        aid = self.approach
        assert aid is not None
        if aid in confirmed:
            self._peak_conf = max(self._peak_conf, confirmed[aid])

        try:
            st = self.controller.status()
        except Exception as e:
            self._fault(now, f"Status nicht lesbar: {e}")
            return
        if not st.healthy:
            self._fault(now, "Steuergerät meldet Störung")
            return

        if self.state == State.REQUESTED:
            if not st.supports_feedback:
                self._set(State.ACTIVE, now, aid)
            elif st.green == aid:
                self.emit("green", aid, self._peak_conf,
                          {"time_to_green_s": round(now - self.request_start, 2)})
                self._set(State.ACTIVE, now, aid)
            elif now - self.request_start >= s.ack_timeout_s and not self._ack_warned:
                self._ack_warned = True
                self.emit("warning", aid, self._peak_conf, {"reason": "keine Grün-Rückmeldung"})

        if now - self.request_start >= s.max_hold_s:
            self.lockouts[aid] = now + s.lockout_s
            self.emit("warning", aid, self._peak_conf,
                      {"reason": "max_hold_s erreicht", "lockout_s": s.lockout_s})
            self._release(now, "max_hold")
            return

        if aid not in confirmed:
            self._release(now, "fahrzeug_weg")
            return

        for other in confirmed:
            if other != aid and other not in self._conflicts:
                self._conflicts.add(other)
                self.emit("conflict", other, confirmed[other], {"active": aid})
