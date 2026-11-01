"""Anschluss an das Ampelsteuergerät.

Die Box schaltet NIE selbst Lampen. Sie gibt dem Steuergerät nur eine
Vorrang-Anforderung für eine Zufahrt. Das Steuergerät behält Zwischenzeiten,
Mindestgrün und den Schutz vor feindlichem Grün. Fällt die Box aus oder hat
sie keinen Strom, sind alle Anforderungen weg und die Ampel läuft normal.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass
from typing import Optional

from .config import BoxConfig

log = logging.getLogger(__name__)


class ControllerError(RuntimeError):
    pass


@dataclass
class ControllerStatus:
    healthy: bool = True
    requested: Optional[str] = None
    green: Optional[str] = None  # Zufahrt, die laut Rückmeldung Grün hat
    supports_feedback: bool = False


class ControllerInterface:
    def request(self, approach_id: str) -> None:
        raise NotImplementedError

    def release(self) -> None:
        raise NotImplementedError

    def status(self) -> ControllerStatus:
        raise NotImplementedError

    def heartbeat(self) -> None:
        """Wird jeden Takt aufgerufen (für Hardware-Watchdog)."""

    def close(self) -> None:
        self.release()


class SimulatedController(ControllerInterface):
    """Modell einer Ampel: Grün für die angeforderte Zufahrt nach der Zwischenzeit."""

    def __init__(self, approach_ids, intergreen_s: float = 4.0, clock=time.monotonic):
        self.approach_ids = list(approach_ids)
        self.intergreen_s = intergreen_s
        self.clock = clock
        self._requested: Optional[str] = None
        self._since = 0.0
        self.history: list = []
        self.fail = False  # für Tests: Ausfall simulieren

    def request(self, approach_id: str) -> None:
        if self.fail:
            raise ControllerError("Simulierter Ausfall")
        if approach_id not in self.approach_ids:
            raise ControllerError(f"Unbekannte Zufahrt {approach_id}")
        if self._requested != approach_id:
            self._requested = approach_id
            self._since = self.clock()
            self.history.append((self._since, "request", approach_id))

    def release(self) -> None:
        if self._requested is not None:
            self.history.append((self.clock(), "release", self._requested))
        self._requested = None

    def status(self) -> ControllerStatus:
        if self.fail:
            raise ControllerError("Simulierter Ausfall")
        green = None
        if self._requested and self.clock() - self._since >= self.intergreen_s:
            green = self._requested
        return ControllerStatus(True, self._requested, green, supports_feedback=True)


class GpioRelayController(ControllerInterface):
    """Ein Relais (potentialfreier Kontakt) pro Zufahrt am Vorrang-Eingang des Steuergeräts.

    Verdrahtung: Relais als Schließer (NO). Stromlos = keine Anforderung.
    Optional: Rückmelde-Eingänge "Grün" je Zufahrt und ein Heartbeat-Ausgang
    für einen externen Hardware-Watchdog, der bei fehlendem Takt die
    Relais-Versorgung trennt.
    """

    def __init__(self, cfg: BoxConfig):
        try:
            from gpiozero import DigitalInputDevice, OutputDevice  # type: ignore
        except ImportError as e:  # pragma: no cover - nur auf Hardware
            raise ControllerError("gpiozero fehlt: pip install gpiozero") from e
        self._lock = threading.Lock()
        ah = cfg.controller.active_high
        self.outputs = {a.id: OutputDevice(a.output_pin, active_high=ah, initial_value=False)
                        for a in cfg.approaches}
        self.feedback = {a.id: DigitalInputDevice(a.feedback_pin)
                         for a in cfg.approaches if a.feedback_pin is not None}
        self.heartbeat_out = (OutputDevice(cfg.controller.heartbeat_pin, initial_value=False)
                              if cfg.controller.heartbeat_pin is not None else None)
        self._requested: Optional[str] = None

    def request(self, approach_id: str) -> None:
        with self._lock:
            for aid, out in self.outputs.items():
                if aid != approach_id:
                    out.off()
            self.outputs[approach_id].on()
            self._requested = approach_id

    def release(self) -> None:
        with self._lock:
            for out in self.outputs.values():
                out.off()
            self._requested = None

    def status(self) -> ControllerStatus:
        green = None
        for aid, inp in self.feedback.items():
            if inp.is_active:
                green = aid
        return ControllerStatus(True, self._requested, green, supports_feedback=bool(self.feedback))

    def heartbeat(self) -> None:
        if self.heartbeat_out is not None:
            self.heartbeat_out.toggle()

    def close(self) -> None:
        self.release()
        for dev in [*self.outputs.values(), *self.feedback.values()]:
            dev.close()
        if self.heartbeat_out is not None:
            self.heartbeat_out.off()
            self.heartbeat_out.close()


class OcitController(ControllerInterface):  # pragma: no cover - Platzhalter
    """OCIT-Outstations (deutscher Standard für Steuergeräte).

    Die konkrete Umsetzung hängt vom Hersteller (Yunex, Swarco, …) und dessen
    Dokumentation ab und gehört in eine Partnerschaft. Bis dahin: GPIO-Relais
    am Vorrang-Eingang verwenden.
    """

    def __init__(self, cfg: BoxConfig):
        raise ControllerError("OCIT-Anbindung ist noch nicht umgesetzt – bitte controller.type='gpio' nutzen.")


def make_controller(cfg: BoxConfig, clock=time.monotonic) -> ControllerInterface:
    t = cfg.controller.type
    if t == "simulated":
        return SimulatedController([a.id for a in cfg.approaches], cfg.controller.intergreen_s, clock)
    if t == "gpio":
        return GpioRelayController(cfg)
    if t == "ocit":
        return OcitController(cfg)
    raise ControllerError(f"Unbekannter Typ {t}")
