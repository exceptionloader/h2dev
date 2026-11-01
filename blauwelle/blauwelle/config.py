"""Konfiguration der BlauWelle-Box.

Alles, was sich pro Kreuzung unterscheidet, steht in einer JSON-Datei
(siehe config/beispiel-kreuzung.json). Fehlende Werte nehmen die Defaults
aus den Dataclasses unten.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field, fields, is_dataclass
from pathlib import Path
from typing import Any, Optional


@dataclass
class Roi:
    """Bildausschnitt in relativen Koordinaten (0..1)."""

    x0: float = 0.0
    y0: float = 0.0
    x1: float = 1.0
    y1: float = 1.0


@dataclass
class ApproachConfig:
    """Eine Zufahrt der Kreuzung (z. B. 'nord')."""

    id: str
    name: str = ""
    # Richtung, aus der Fahrzeuge dieser Zufahrt kommen, gesehen von der Box.
    # 0 = Norden, 90 = Osten (im Uhrzeigersinn).
    bearing_deg: float = 0.0
    bearing_tolerance_deg: float = 45.0
    # Kamera, die diese Zufahrt beobachtet (Index oder URL für OpenCV).
    camera: Any = None
    roi: Roi = field(default_factory=Roi)
    # Ausgang am Steuergerät (z. B. GPIO-Pin des Relais).
    output_pin: Optional[int] = None
    # Rückmeldung "Grün für diese Zufahrt" vom Steuergerät (optional).
    feedback_pin: Optional[int] = None
    # Höher = wird bei gleichzeitigen Anforderungen bevorzugt.
    priority: int = 0


@dataclass
class AudioConfig:
    enabled: bool = True
    sample_rate: int = 16000
    device: Any = None
    # Mikrofonpositionen in Metern relativ zur Box (x = Osten, y = Norden).
    mic_positions: list = field(
        default_factory=lambda: [[-0.05, -0.05], [0.05, -0.05], [0.05, 0.05], [-0.05, 0.05]]
    )
    band_hz: list = field(default_factory=lambda: [300.0, 2000.0])
    frame_size: int = 1024
    window_s: float = 5.0
    tonality_threshold: float = 0.30
    min_switches: int = 2
    # Pegel unterhalb davon wird ignoriert (dBFS, RMS).
    min_level_dbfs: float = -60.0
    speed_of_sound: float = 343.0


@dataclass
class VisionConfig:
    enabled: bool = True
    fps: float = 15.0
    window_s: float = 2.0
    # Anteil blauer Pixel im ROI, ab dem überhaupt etwas zählt.
    min_blue_fraction: float = 0.0015
    flash_hz: list = field(default_factory=lambda: [0.8, 8.0])
    # Fenster und Schwelle für "Fahrzeug entfernt sich" (Blitz wird kleiner)
    trend_window_s: float = 4.0
    receding_ratio: float = 0.6
    # Blau-Erkennung (RGB, 0..255)
    blue_min: int = 140
    blue_over_red: int = 60
    blue_over_green: int = 30


@dataclass
class FusionConfig:
    # §38 StVO: Wegerecht nur mit Blaulicht UND Martinshorn.
    require_both: bool = True
    allow_unknown_bearing: bool = True
    trigger_threshold: float = 0.55
    release_threshold: float = 0.35
    confirm_s: float = 1.0
    lost_s: float = 3.0


@dataclass
class SafetyConfig:
    # Längste erlaubte Vorrang-Anforderung, danach wird zwangsweise freigegeben.
    max_hold_s: float = 60.0
    # Pause nach jeder Freigabe, bevor eine neue Anforderung möglich ist.
    cooldown_s: float = 5.0
    # Sperre einer Zufahrt, nachdem sie max_hold_s erreicht hat.
    lockout_s: float = 120.0
    # Wie lange auf "Grün" vom Steuergerät gewartet wird (nur mit Rückmeldung).
    ack_timeout_s: float = 15.0
    # Läuft die Hauptschleife länger nicht, wird alles freigegeben.
    watchdog_s: float = 2.0
    # Nach einem Fehler automatisch wieder starten (0 = nur manuell).
    auto_recover_s: float = 300.0


@dataclass
class ControllerConfig:
    # "simulated" | "gpio" | "ocit"
    type: str = "simulated"
    active_high: bool = True
    heartbeat_pin: Optional[int] = None
    # Für den Simulator: Zwischenzeit bis Grün.
    intergreen_s: float = 4.0


@dataclass
class ApiConfig:
    enabled: bool = True
    host: str = "127.0.0.1"
    port: int = 8080
    # Token für schreibende Aufrufe (Fehlalarm markieren, Reset).
    token: str = ""


@dataclass
class BoxConfig:
    intersection_id: str = "kreuzung-1"
    name: str = ""
    approaches: list = field(default_factory=list)
    audio: AudioConfig = field(default_factory=AudioConfig)
    vision: VisionConfig = field(default_factory=VisionConfig)
    fusion: FusionConfig = field(default_factory=FusionConfig)
    safety: SafetyConfig = field(default_factory=SafetyConfig)
    controller: ControllerConfig = field(default_factory=ControllerConfig)
    api: ApiConfig = field(default_factory=ApiConfig)
    database: str = "blauwelle.db"
    tick_s: float = 0.1

    def approach(self, approach_id: str) -> ApproachConfig:
        for a in self.approaches:
            if a.id == approach_id:
                return a
        raise KeyError(approach_id)

    def to_dict(self) -> dict:
        return asdict(self)


_NESTED = {
    "audio": AudioConfig,
    "vision": VisionConfig,
    "fusion": FusionConfig,
    "safety": SafetyConfig,
    "controller": ControllerConfig,
    "api": ApiConfig,
}


def _build(cls, data: dict):
    known = {f.name for f in fields(cls)}
    unknown = set(data) - known
    if unknown:
        raise ValueError(f"Unbekannte Felder in {cls.__name__}: {sorted(unknown)}")
    return cls(**data)


def from_dict(data: dict) -> BoxConfig:
    data = dict(data)
    kwargs: dict = {}
    for key, cls in _NESTED.items():
        if key in data:
            kwargs[key] = _build(cls, data.pop(key))
    approaches = []
    for a in data.pop("approaches", []):
        a = dict(a)
        if "roi" in a:
            a["roi"] = _build(Roi, a["roi"])
        approaches.append(_build(ApproachConfig, a))
    kwargs["approaches"] = approaches
    cfg = _build(BoxConfig, {**data, **kwargs})
    validate(cfg)
    return cfg


def load(path: str | Path) -> BoxConfig:
    with open(path, encoding="utf-8") as f:
        return from_dict(json.load(f))


def validate(cfg: BoxConfig) -> None:
    if not cfg.approaches:
        raise ValueError("Mindestens eine Zufahrt (approaches) nötig.")
    ids = [a.id for a in cfg.approaches]
    if len(ids) != len(set(ids)):
        raise ValueError("Zufahrt-IDs müssen eindeutig sein.")
    if cfg.fusion.release_threshold >= cfg.fusion.trigger_threshold:
        raise ValueError("release_threshold muss kleiner als trigger_threshold sein.")
    if cfg.safety.max_hold_s <= 0:
        raise ValueError("max_hold_s muss > 0 sein.")
    if len(cfg.audio.mic_positions) < 2 and cfg.audio.enabled:
        raise ValueError("Für die Richtungserkennung sind mindestens 2 Mikrofone nötig.")
    if cfg.controller.type not in ("simulated", "gpio", "ocit"):
        raise ValueError(f"Unbekannter controller.type: {cfg.controller.type}")
    if cfg.controller.type == "gpio":
        for a in cfg.approaches:
            if a.output_pin is None:
                raise ValueError(f"Zufahrt {a.id}: output_pin fehlt für GPIO-Anschluss.")


def is_config(obj: Any) -> bool:
    return is_dataclass(obj)
