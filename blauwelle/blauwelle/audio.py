"""Sirenen-Erkennung und Richtungsbestimmung aus einem Mikrofon-Array.

Erkennung: Eine Sirene ist ein lauter, tonaler Klang, dessen Tonhöhe sich
typisch ändert – entweder als Zweiklang (deutsches Martinshorn, Quart-Abstand
ca. 4:3) oder als Heulton/Yelp (gleitende Frequenz). Ein konstanter Ton
(Maschine, Hupe) oder Rauschen ergibt keinen Treffer.

Richtung: GCC-PHAT zwischen allen Mikrofonpaaren liefert Laufzeitunterschiede.
Daraus wird per Rastersuche der Winkel bestimmt, der am besten passt.
"""

from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass
from itertools import combinations
from typing import Optional

import numpy as np

from .config import AudioConfig


@dataclass
class AudioResult:
    score: float  # 0..1, wie sicher eine Sirene zu hören ist
    bearing_deg: Optional[float]  # Richtung der Quelle, None = unbekannt
    bearing_quality: float  # 0..1
    level_dbfs: float
    pattern: str  # "two_tone" | "sweep" | "none"


def _clamp(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, x))


def gcc_phat(sig: np.ndarray, ref: np.ndarray, fs: int, max_tau: float,
             band: tuple = (300.0, 2000.0), interp: int = 16) -> tuple:
    """Verzögerung von `sig` gegenüber `ref` in Sekunden (positiv = sig später).

    Gibt (tau, peak_schaerfe) zurück.
    """
    n = len(sig) + len(ref)
    SIG = np.fft.rfft(sig, n=n)
    REF = np.fft.rfft(ref, n=n)
    R = SIG * np.conj(REF)
    R /= np.abs(R) + 1e-12
    freqs = np.fft.rfftfreq(n, 1.0 / fs)
    R[(freqs < band[0]) | (freqs > band[1])] = 0
    cc = np.fft.irfft(R, n=interp * n)
    max_shift = max(1, int(interp * fs * max_tau))
    cc = np.concatenate((cc[-max_shift:], cc[: max_shift + 1]))
    idx = int(np.argmax(np.abs(cc)))
    peak = float(np.abs(cc[idx]))
    sharp = peak / (float(np.mean(np.abs(cc))) + 1e-12)
    return (idx - max_shift) / float(interp * fs), sharp


class DirectionFinder:
    """Bestimmt die Richtung einer Schallquelle (Fernfeld-Annahme)."""

    def __init__(self, mic_positions, fs: int, c: float = 343.0, band=(300.0, 2000.0)):
        self.p = np.asarray(mic_positions, dtype=float)
        self.fs = fs
        self.c = c
        self.band = tuple(band)
        self.pairs = list(combinations(range(len(self.p)), 2))
        self.grid = np.deg2rad(np.arange(0, 360, 1.0))
        # Einheitsvektor zur Quelle: x = Osten (sin), y = Norden (cos)
        u = np.stack([np.sin(self.grid), np.cos(self.grid)], axis=1)
        # Erwartete Verzögerung von Mikro j gegenüber i: -(p_j - p_i)·u / c
        self.expected = np.stack(
            [-(u @ (self.p[j] - self.p[i])) / c for i, j in self.pairs], axis=1
        )
        dists = [np.linalg.norm(self.p[j] - self.p[i]) for i, j in self.pairs]
        self.max_tau = max(dists) / c * 1.1

    def estimate(self, block: np.ndarray) -> tuple:
        """block: (samples, mics). Gibt (winkel_grad, qualitaet 0..1) zurück."""
        taus = []
        for i, j in self.pairs:
            tau, _ = gcc_phat(block[:, j], block[:, i], self.fs, self.max_tau, self.band)
            taus.append(tau)
        taus = np.asarray(taus)
        err = np.sum((self.expected - taus) ** 2, axis=1)
        k = int(np.argmin(err))
        # Qualität: Restfehler im Verhältnis zur typischen Laufzeit
        rms = math.sqrt(err[k] / len(self.pairs))
        quality = _clamp(1.0 - rms / (self.max_tau * 0.5))
        return float(np.rad2deg(self.grid[k])), quality


class SirenDetector:
    def __init__(self, cfg: AudioConfig):
        self.cfg = cfg
        self.fs = cfg.sample_rate
        self.n = cfg.frame_size
        self.window = np.hanning(self.n)
        self.freqs = np.fft.rfftfreq(self.n, 1.0 / self.fs)
        lo, hi = cfg.band_hz
        self.band = (self.freqs >= lo) & (self.freqs <= hi)
        self.band_idx = np.nonzero(self.band)[0]
        self.history: deque = deque()  # (t, freq or None)
        self.direction = DirectionFinder(cfg.mic_positions, self.fs, cfg.speed_of_sound, cfg.band_hz)
        self._buf = np.zeros((0, len(cfg.mic_positions)))
        self._bearings: deque = deque(maxlen=8)
        self.last = AudioResult(0.0, None, 0.0, -120.0, "none")

    # -- Einzelrahmen --------------------------------------------------
    def _analyse_frame(self, mono: np.ndarray) -> Optional[float]:
        spec = np.abs(np.fft.rfft(mono * self.window)) ** 2
        band = spec[self.band]
        total = float(band.sum()) + 1e-20
        k = int(np.argmax(band))
        peak = float(band[max(0, k - 2): k + 3].sum())
        if peak / total < self.cfg.tonality_threshold:
            return None
        # Parabolische Interpolation für genauere Frequenz
        b = self.band_idx[k]
        if 0 < b < len(spec) - 1:
            a, m, c = np.log(spec[b - 1] + 1e-20), np.log(spec[b] + 1e-20), np.log(spec[b + 1] + 1e-20)
            denom = a - 2 * m + c
            off = 0.5 * (a - c) / denom if denom != 0 else 0.0
        else:
            off = 0.0
        return float((b + off) * self.fs / self.n)

    # -- Muster ----------------------------------------------------------
    def _pattern(self) -> tuple:
        frames = list(self.history)
        if not frames:
            return 0.0, "none"
        tonal = [f for _, f in frames if f is not None]
        tonal_frac = len(tonal) / len(frames)
        tonal_score = _clamp((tonal_frac - 0.3) / 0.4)
        if len(tonal) < 6:
            return 0.0, "none"
        f = np.asarray(tonal)
        p10, p25, p75, p90 = np.percentile(f, [10, 25, 75, 90])

        # Zweiklang: zwei Gruppen im Verhältnis ~1.2..1.5, mehrfacher Wechsel
        two_tone = 0.0
        ratio = p75 / max(p25, 1.0)
        if 1.18 <= ratio <= 1.55:
            mid = math.sqrt(p25 * p75)
            low_ok = f[f < mid]
            high_ok = f[f >= mid]
            spread_ok = (np.std(low_ok) < 0.04 * mid) and (np.std(high_ok) < 0.04 * mid) if len(low_ok) and len(high_ok) else False
            states = []
            for _, fr in frames:
                if fr is None:
                    continue
                s = 0 if fr < mid else 1
                if not states or states[-1] != s:
                    states.append(s)
            switches = len(states) - 1
            if spread_ok and switches >= self.cfg.min_switches:
                two_tone = 1.0

        # Heulton / Yelp: großer Frequenzbereich, kontinuierliche Bewegung
        sweep = 0.0
        rng = p90 - p10
        if rng > 200.0:
            steps = np.abs(np.diff(f)) / f[:-1]
            if np.median(steps) < 0.12 and np.median(steps) > 0.002:
                sweep = 1.0

        if two_tone >= sweep:
            return tonal_score * two_tone, "two_tone" if two_tone else "none"
        return tonal_score * sweep, "sweep"

    # -- Öffentliche API ------------------------------------------------
    def process(self, now: float, block: np.ndarray) -> AudioResult:
        """block: (samples, mics) float-Samples (-1..1), beliebige Länge."""
        if block.ndim == 1:
            block = block[:, None]
        self._buf = np.concatenate([self._buf[:, : block.shape[1]], block]) if len(self._buf) else block
        level = -120.0
        n_frames = len(self._buf) // self.n
        dt = self.n / self.fs
        t0 = now - n_frames * dt
        for i in range(n_frames):
            mono = self._buf[i * self.n:(i + 1) * self.n, 0]
            rms = float(np.sqrt(np.mean(mono ** 2)) + 1e-12)
            level = max(level, 20 * math.log10(rms))
            freq = self._analyse_frame(mono) if level >= self.cfg.min_level_dbfs else None
            self.history.append((t0 + (i + 1) * dt, freq))
        used = n_frames * self.n
        recent = self._buf[max(0, used - 4 * self.n): used]
        self._buf = self._buf[used:]
        while self.history and self.history[0][0] < now - self.cfg.window_s:
            self.history.popleft()

        score, pattern = self._pattern()
        bearing, quality = None, 0.0
        if score > 0.2 and recent.shape[1] >= 2 and len(recent) >= self.n:
            b, q = self.direction.estimate(recent)
            if q > 0.3:
                self._bearings.append(b)
        elif score <= 0.05:
            self._bearings.clear()
        if self._bearings:
            # Zirkulärer Mittelwert der letzten Schätzungen
            rad = np.deg2rad(np.asarray(self._bearings))
            s, c = np.mean(np.sin(rad)), np.mean(np.cos(rad))
            bearing = float(np.rad2deg(math.atan2(s, c)) % 360)
            quality = float(math.hypot(s, c))
        self.last = AudioResult(score, bearing, quality, level if n_frames else self.last.level_dbfs, pattern)
        return self.last
