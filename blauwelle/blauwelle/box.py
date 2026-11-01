"""Die Box: verbindet Erkennung, Fusion, Zustandsautomat, Protokoll."""

from __future__ import annotations

import logging
import threading
import time
from typing import Optional

import numpy as np

from .audio import AudioResult, SirenDetector
from .config import BoxConfig
from .eventlog import EventLog
from .fusion import Fusion
from .interfaces import ControllerInterface
from .preemption import Preemption, State
from .vision import BlueLightDetector

log = logging.getLogger(__name__)


class Box:
    def __init__(self, cfg: BoxConfig, controller: ControllerInterface,
                 events: Optional[EventLog] = None):
        self.cfg = cfg
        self.controller = controller
        self.events = events or EventLog(":memory:")
        self.siren = SirenDetector(cfg.audio) if cfg.audio.enabled else None
        self.lights = {a.id: BlueLightDetector(cfg.vision, a.roi) for a in cfg.approaches} \
            if cfg.vision.enabled else {}
        self.fusion = Fusion(cfg.fusion, cfg.approaches)
        self.preemption = Preemption(cfg, controller, emit=self._emit)
        self._lock = threading.Lock()
        self._last_tick: Optional[float] = None
        self._audio = AudioResult(0.0, None, 0.0, -120.0, "none")
        self._vision: dict = {}
        self._confirmed: dict = {}

    def _emit(self, kind, approach, conf, detail):
        detail = {**detail, "state_before": self.preemption.state.value}
        self.events.add(kind, approach, conf, detail)

    def process(self, now: float, audio: Optional[np.ndarray] = None,
                frames: Optional[dict] = None) -> None:
        """Ein Takt. audio: (samples, mics), frames: {zufahrt: RGB-Bild}."""
        with self._lock:
            if self._last_tick is not None and now - self._last_tick > self.cfg.safety.watchdog_s:
                log.warning("Watchdog: %.1fs ohne Takt", now - self._last_tick)
                self.preemption.force_release(now, "watchdog")
            self._last_tick = now

            if self.siren is not None and audio is not None and len(audio):
                self._audio = self.siren.process(now, audio)
            for aid, frame in (frames or {}).items():
                if aid in self.lights and frame is not None:
                    self._vision[aid] = self.lights[aid].process(now, frame)

            self._confirmed = self.fusion.update(
                now, self._audio, self._vision,
                audio_enabled=self.siren is not None, vision_enabled=bool(self.lights))
            self.preemption.step(now, self._confirmed)

    def reset(self, now: Optional[float] = None) -> bool:
        with self._lock:
            return self.preemption.reset(now if now is not None else time.monotonic())

    def shutdown(self, now: Optional[float] = None) -> None:
        with self._lock:
            self.preemption.force_release(now if now is not None else time.monotonic(), "shutdown")
            try:
                self.controller.close()
            except Exception:
                log.exception("Fehler beim Schließen des Steuergeräts")

    def status(self) -> dict:
        with self._lock:
            p = self.preemption
            return {
                "intersection": self.cfg.intersection_id,
                "name": self.cfg.name,
                "state": p.state.value,
                "approach": p.approach,
                "fault": p.fault_reason or None,
                "audio": {
                    "score": round(self._audio.score, 3),
                    "bearing_deg": None if self._audio.bearing_deg is None else round(self._audio.bearing_deg),
                    "bearing_quality": round(self._audio.bearing_quality, 2),
                    "level_dbfs": round(self._audio.level_dbfs, 1),
                    "pattern": self._audio.pattern,
                },
                "approaches": [
                    {
                        "id": a.id,
                        "name": a.name or a.id,
                        "vision": round(self.fusion.states[a.id].vision, 3),
                        "audio": round(self.fusion.states[a.id].audio, 3),
                        "confidence": round(self.fusion.states[a.id].confidence, 3),
                        "confirmed": self.fusion.states[a.id].confirmed,
                        "locked": p.lockouts.get(a.id, 0.0) > (self._last_tick or 0.0),
                    }
                    for a in self.cfg.approaches
                ],
            }

    @property
    def state(self) -> State:
        return self.preemption.state
