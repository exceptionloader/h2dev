"""Zusammenführung von Kamera und Mikrofon pro Zufahrt.

Ergebnis pro Zufahrt: eine Konfidenz 0..1 und der Zustand "bestätigt" mit
Hysterese (muss confirm_s lang über der Schwelle liegen, wird erst nach
lost_s unter der Freigabe-Schwelle wieder verworfen).
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Optional

from .audio import AudioResult
from .config import ApproachConfig, FusionConfig
from .vision import VisionResult


def angle_diff(a: float, b: float) -> float:
    return abs((a - b + 180.0) % 360.0 - 180.0)


@dataclass
class ApproachState:
    confidence: float = 0.0
    vision: float = 0.0
    audio: float = 0.0
    confirmed: bool = False
    above_since: Optional[float] = None
    below_since: Optional[float] = None
    confirmed_at: Optional[float] = None


@dataclass
class Fusion:
    cfg: FusionConfig
    approaches: list  # list[ApproachConfig]
    states: dict = field(default_factory=dict)

    def __post_init__(self):
        self.states = {a.id: ApproachState() for a in self.approaches}

    def _audio_for(self, a: ApproachConfig, audio: AudioResult) -> float:
        if audio.score <= 0:
            return 0.0
        if audio.bearing_deg is None or audio.bearing_quality < 0.5:
            return audio.score if self.cfg.allow_unknown_bearing else 0.0
        if angle_diff(audio.bearing_deg, a.bearing_deg) <= a.bearing_tolerance_deg:
            return audio.score
        return audio.score * 0.3

    def combine(self, v: float, a: float, audio_enabled: bool, vision_enabled: bool) -> float:
        if not audio_enabled:
            return v
        if not vision_enabled:
            return a
        if self.cfg.require_both:
            return math.sqrt(max(v, 0.0) * max(a, 0.0))
        return 1.0 - (1.0 - v) * (1.0 - a)

    def update(self, now: float, audio: AudioResult, vision: dict,
               audio_enabled: bool = True, vision_enabled: bool = True) -> dict:
        """vision: {approach_id: VisionResult}. Gibt {id: konfidenz} der bestätigten Zufahrten zurück."""
        confirmed = {}
        for a in self.approaches:
            st = self.states[a.id]
            vr: VisionResult = vision.get(a.id) or VisionResult(0.0, 0.0, 0.0, False)
            st.vision = vr.score
            st.audio = self._audio_for(a, audio)
            st.confidence = self.combine(st.vision, st.audio, audio_enabled, vision_enabled)

            if st.confidence >= self.cfg.trigger_threshold:
                st.below_since = None
                if st.above_since is None:
                    st.above_since = now
                if not st.confirmed and now - st.above_since >= self.cfg.confirm_s:
                    st.confirmed = True
                    st.confirmed_at = now
            elif st.confidence < self.cfg.release_threshold:
                st.above_since = None
                if st.below_since is None:
                    st.below_since = now
                if st.confirmed and now - st.below_since >= self.cfg.lost_s:
                    st.confirmed = False
                    st.confirmed_at = None
            else:
                # Zwischen den Schwellen: Zustand halten
                st.above_since = st.above_since if st.confirmed else None
                st.below_since = None
            if st.confirmed:
                confirmed[a.id] = st.confidence
        return confirmed
