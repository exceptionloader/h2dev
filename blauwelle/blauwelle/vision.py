"""Blaulicht-Erkennung im Kamerabild.

Pro Zufahrt wird nur ein Bildausschnitt (ROI) ausgewertet. Gezählt wird der
Anteil kräftig blauer Pixel. Ein Blaulicht blinkt: Der Anteil springt mit
ca. 1–8 Hz zwischen "an" und "aus". Ein dauerhaft blaues Schild oder Auto
blinkt nicht und ergibt keinen Treffer. Bilder werden nirgends gespeichert.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass

import numpy as np

from .config import Roi, VisionConfig


@dataclass
class VisionResult:
    score: float  # 0..1
    blue_fraction: float
    flash_hz: float
    approaching: bool


def _clamp(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, x))


def crop(frame: np.ndarray, roi: Roi) -> np.ndarray:
    h, w = frame.shape[:2]
    y0, y1 = int(roi.y0 * h), max(int(roi.y1 * h), int(roi.y0 * h) + 1)
    x0, x1 = int(roi.x0 * w), max(int(roi.x1 * w), int(roi.x0 * w) + 1)
    return frame[y0:y1, x0:x1]


def blue_fraction(rgb: np.ndarray, cfg: VisionConfig) -> float:
    """Anteil kräftig blauer Pixel in einem RGB-Bild (uint8)."""
    img = rgb.astype(np.int16)
    r, g, b = img[..., 0], img[..., 1], img[..., 2]
    mask = (b >= cfg.blue_min) & (b - r >= cfg.blue_over_red) & (b - g >= cfg.blue_over_green)
    return float(mask.mean())


class BlueLightDetector:
    def __init__(self, cfg: VisionConfig, roi: Roi):
        self.cfg = cfg
        self.roi = roi
        self.history: deque = deque()  # (t, fraction)
        self.trend: deque = deque()  # längeres Fenster für "nähert sich / entfernt sich"
        self.last = VisionResult(0.0, 0.0, 0.0, False)

    def process(self, now: float, frame_rgb: np.ndarray) -> VisionResult:
        frac = blue_fraction(crop(frame_rgb, self.roi), self.cfg)
        self.history.append((now, frac))
        self.trend.append((now, frac))
        while self.history and self.history[0][0] < now - self.cfg.window_s:
            self.history.popleft()
        while self.trend and self.trend[0][0] < now - self.cfg.trend_window_s:
            self.trend.popleft()
        self.last = self._score()
        return self.last

    def _approaching(self) -> bool:
        """Hüllkurve der Blitzgröße: Fährt das Fahrzeug weg, wird sie deutlich kleiner."""
        if len(self.trend) < 8:
            return True
        t = np.array([h[0] for h in self.trend])
        x = np.array([h[1] for h in self.trend])
        bins = np.floor((t - t[0]) / 0.5).astype(int)
        env = np.array([x[bins == b].max() for b in np.unique(bins)])
        env = env[env > 0]
        if len(env) < 3:
            return True
        recent = env[-2:].max()
        return recent >= self.cfg.receding_ratio * env.max()

    def _score(self) -> VisionResult:
        if len(self.history) < max(6, int(self.cfg.fps * self.cfg.window_s * 0.6)):
            return VisionResult(0.0, self.history[-1][1] if self.history else 0.0, 0.0, False)
        t = np.array([h[0] for h in self.history])
        x = np.array([h[1] for h in self.history])
        peak = float(x.max())
        if peak < self.cfg.min_blue_fraction:
            return VisionResult(0.0, float(x[-1]), 0.0, False)

        # Kontrast: Blinken heißt, es gibt deutlich dunklere Momente
        low = float(np.percentile(x, 15))
        contrast = (peak - low) / peak
        contrast_score = _clamp((contrast - 0.4) / 0.4)

        # Blinkfrequenz aus Zustandswechseln
        on = x > (low + 0.5 * (peak - low))
        changes = int(np.count_nonzero(on[1:] != on[:-1]))
        duration = max(t[-1] - t[0], 1e-3)
        flash_hz = changes / 2.0 / duration
        lo, hi = self.cfg.flash_hz
        rate_score = 1.0 if lo <= flash_hz <= hi else 0.0
        duty = float(on.mean())
        duty_score = 1.0 if 0.1 <= duty <= 0.9 else 0.0

        size_score = _clamp(peak / (self.cfg.min_blue_fraction * 3))

        approaching = self._approaching()
        # Ein wegfahrendes Fahrzeug (von hinten gesehen) braucht keinen Vorrang.
        trend_score = 1.0 if approaching else 0.0

        score = contrast_score * rate_score * duty_score * size_score * trend_score
        return VisionResult(score, float(x[-1]), flash_hz, approaching)
