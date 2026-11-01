"""Simulator: erzeugt Ton (Mikrofon-Array) und Kamerabilder für typische
Situationen und lässt die echte Box-Logik darauf laufen. So lässt sich das
System ohne Kreuzung, Kamera und Feuerwehr testen.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Optional

import numpy as np

from .box import Box
from .config import ApproachConfig, BoxConfig, from_dict
from .eventlog import EventLog
from .interfaces import SimulatedController


@dataclass
class Vehicle:
    approach: str  # Zufahrt, aus der es kommt
    exit: Optional[str]  # Zufahrt, in die es abfährt
    t_pass: float  # Zeitpunkt in der Kreuzungsmitte
    speed: float = 15.0  # m/s (54 km/h)
    siren: str = "two_tone"  # "two_tone" | "sweep" | "none"
    blue_light: bool = True
    start_distance: float = 300.0
    visible_distance: float = 150.0


@dataclass
class Scenario:
    name: str
    duration: float
    vehicles: list = field(default_factory=list)
    blue_sign: Optional[str] = None  # Zufahrt mit dauerhaft blauem Schild
    horn: bool = False  # konstanter Hupton (Störgeräusch)
    expected_requests: list = field(default_factory=list)


SCENARIOS = {
    "einsatz": Scenario(
        "einsatz", 32.0,
        [Vehicle("nord", "sued", t_pass=20.0)],
        expected_requests=["nord"],
    ),
    "blaues_schild": Scenario(
        "blaues_schild", 20.0, [], blue_sign="ost", horn=True, expected_requests=[],
    ),
    "nur_sirene": Scenario(
        "nur_sirene", 25.0,
        [Vehicle("west", None, t_pass=18.0, blue_light=False)],
        expected_requests=[],
    ),
    "heulton": Scenario(
        "heulton", 30.0,
        [Vehicle("ost", "west", t_pass=19.0, siren="sweep")],
        expected_requests=["ost"],
    ),
}


def default_config() -> BoxConfig:
    approaches = [
        {"id": "nord", "name": "Hauptstraße Nord", "bearing_deg": 0, "priority": 1},
        {"id": "ost", "name": "Bahnhofstraße Ost", "bearing_deg": 90},
        {"id": "sued", "name": "Hauptstraße Süd", "bearing_deg": 180, "priority": 1},
        {"id": "west", "name": "Feuerwache West", "bearing_deg": 270, "priority": 2},
    ]
    return from_dict({
        "intersection_id": "sim-1",
        "name": "Simulierte Kreuzung",
        "approaches": approaches,
        "vision": {"fps": 10.0},
        "controller": {"type": "simulated", "intergreen_s": 4.0},
        "database": ":memory:",
    })


# -- Ton --------------------------------------------------------------------
def _siren_freq(kind: str, t: np.ndarray) -> np.ndarray:
    if kind == "two_tone":
        # Deutsches Martinshorn: zwei Töne im Quartabstand, abwechselnd
        return np.where((t % 2.0) < 1.0, 440.0, 585.0)
    if kind == "sweep":
        # Heulton: langsam auf- und abschwellend
        return 900.0 + 400.0 * np.sin(2 * math.pi * t / 4.0)
    return np.zeros_like(t)


def _frac_delay(x: np.ndarray, delay_s: float, fs: int) -> np.ndarray:
    n = len(x)
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(n, 1.0 / fs)
    return np.fft.irfft(X * np.exp(-2j * math.pi * f * delay_s), n=n)


def _bearing(cfg: BoxConfig, approach: str) -> float:
    return cfg.approach(approach).bearing_deg


def _distance(v: Vehicle, t: np.ndarray) -> np.ndarray:
    return np.abs(v.t_pass - t) * v.speed + 5.0


def synth_audio(cfg: BoxConfig, sc: Scenario, seed: int = 1) -> np.ndarray:
    fs = cfg.audio.sample_rate
    n = int(sc.duration * fs)
    t = np.arange(n) / fs
    mics = np.asarray(cfg.audio.mic_positions)
    rng = np.random.default_rng(seed)
    out = np.zeros((n, len(mics)))

    for v in sc.vehicles:
        if v.siren == "none":
            continue
        f = _siren_freq(v.siren, t)
        phase = 2 * math.pi * np.cumsum(f) / fs
        tone = np.sin(phase) + 0.5 * np.sin(2 * phase) + 0.3 * np.sin(3 * phase)
        d = _distance(v, t)
        active = (v.t_pass - t) * v.speed <= v.start_distance
        amp = np.where(active, np.minimum(0.4, 3.0 / d), 0.0)
        src = tone * amp
        # vor der Kreuzung aus der Zufahrt, danach aus der Ausfahrt
        segments = [(t < v.t_pass, _bearing(cfg, v.approach))]
        if v.exit:
            segments.append((t >= v.t_pass, _bearing(cfg, v.exit)))
        for mask, bearing in segments:
            part = np.where(mask, src, 0.0)
            u = np.array([math.sin(math.radians(bearing)), math.cos(math.radians(bearing))])
            for m, p in enumerate(mics):
                out[:, m] += _frac_delay(part, -float(p @ u) / cfg.audio.speed_of_sound, fs)

    if sc.horn:
        out += 0.05 * np.sin(2 * math.pi * 410.0 * t)[:, None]
    # Verkehrslärm: gemeinsames tieffrequentes Rauschen + Eigenrauschen pro Mikrofon
    common = np.cumsum(rng.normal(0, 1, n))
    common -= np.convolve(common, np.ones(400) / 400, mode="same")
    out += 0.004 * common[:, None] / (np.std(common) + 1e-9)
    out += rng.normal(0, 0.006, out.shape)
    return out


# -- Bild -------------------------------------------------------------------
H, W = 48, 64


def synth_frame(cfg: BoxConfig, sc: Scenario, approach: ApproachConfig, now: float,
                rng: np.random.Generator) -> np.ndarray:
    img = rng.normal(90, 8, (H, W, 3)).clip(0, 255)
    if sc.blue_sign == approach.id:
        img[5:12, 50:60] = (20, 60, 220)
    for v in sc.vehicles:
        if not v.blue_light:
            continue
        coming = v.approach == approach.id and now < v.t_pass
        going = v.exit == approach.id and now >= v.t_pass
        if not (coming or going):
            continue
        d = abs(v.t_pass - now) * v.speed + 5.0
        if d > v.visible_distance:
            continue
        # Doppelblitz-artiges Muster, ~2,5 Hz
        if (now % 0.4) >= 0.2:
            continue
        r = int(np.clip(300.0 / d, 1, 12))
        cy, cx = H // 2, W // 2
        yy, xx = np.ogrid[:H, :W]
        blob = (yy - cy) ** 2 + (xx - cx) ** 2 <= r * r
        img[blob] = (30, 80, 250)
    return img.astype(np.uint8)


# -- Ablauf -----------------------------------------------------------------
@dataclass
class SimResult:
    scenario: str
    requests: list
    events: list
    timeline: list
    controller_history: list

    @property
    def ok(self) -> bool:
        return [r for _, r in self.requests] == SCENARIOS[self.scenario].expected_requests \
            if self.scenario in SCENARIOS else True


def run(scenario: str | Scenario, cfg: Optional[BoxConfig] = None, seed: int = 1,
        verbose: bool = False) -> SimResult:
    sc = SCENARIOS[scenario] if isinstance(scenario, str) else scenario
    cfg = cfg or default_config()
    clock = {"t": 0.0}
    controller = SimulatedController([a.id for a in cfg.approaches], cfg.controller.intergreen_s,
                                     clock=lambda: clock["t"])
    events = EventLog(":memory:", wall_clock=lambda: clock["t"])
    box = Box(cfg, controller, events)

    audio = synth_audio(cfg, sc, seed) if cfg.audio.enabled else None
    rng = np.random.default_rng(seed + 7)
    fs = cfg.audio.sample_rate
    step = int(round(cfg.tick_s * fs))
    timeline = []
    last_state = None
    t = 0.0
    i = 0
    while t < sc.duration - 1e-9:
        t = round((i + 1) * cfg.tick_s, 6)
        clock["t"] = t
        block = audio[i * step:(i + 1) * step] if audio is not None else None
        frames = {a.id: synth_frame(cfg, sc, a, t, rng) for a in cfg.approaches}
        box.process(t, block, frames)
        st = box.status()
        if st["state"] != last_state:
            timeline.append((t, st["state"], st["approach"]))
            if verbose:
                print(f"{t:6.1f}s  {st['state']:<10} {st['approach'] or ''}")
            last_state = st["state"]
        i += 1
    box.shutdown(t)

    evs = list(reversed(events.recent(1000)))
    events.close()
    requests = [(e["ts"], e["approach"]) for e in evs if e["kind"] == "request"]
    return SimResult(sc.name, requests, evs, timeline, controller.history)
