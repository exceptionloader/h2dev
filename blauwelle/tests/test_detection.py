import math
import unittest

import numpy as np

from blauwelle.audio import SirenDetector
from blauwelle.config import Roi
from blauwelle.simulate import Scenario, Vehicle, default_config, synth_audio
from blauwelle.vision import BlueLightDetector

FS = 16000
STEP = 1600


def run_audio(cfg, audio):
    det = SirenDetector(cfg.audio)
    res = None
    for i in range(len(audio) // STEP):
        res = det.process((i + 1) * 0.1, audio[i * STEP:(i + 1) * STEP])
    return res


class SirenTests(unittest.TestCase):
    def setUp(self):
        self.cfg = default_config()

    def test_two_tone_detected_with_direction(self):
        for ap, bearing in [("nord", 0), ("ost", 90), ("sued", 180), ("west", 270)]:
            sc = Scenario("t", 10, [Vehicle(ap, None, t_pass=12)])
            r = run_audio(self.cfg, synth_audio(self.cfg, sc))
            self.assertGreater(r.score, 0.8, ap)
            self.assertEqual(r.pattern, "two_tone")
            diff = abs((r.bearing_deg - bearing + 180) % 360 - 180)
            self.assertLess(diff, 15, f"{ap}: {r.bearing_deg}")

    def test_sweep_detected(self):
        sc = Scenario("t", 10, [Vehicle("ost", None, t_pass=12, siren="sweep")])
        r = run_audio(self.cfg, synth_audio(self.cfg, sc))
        self.assertGreater(r.score, 0.8)
        self.assertEqual(r.pattern, "sweep")

    def test_constant_horn_and_noise_ignored(self):
        sc = Scenario("t", 10, [], horn=True)
        r = run_audio(self.cfg, synth_audio(self.cfg, sc))
        self.assertLess(r.score, 0.1)
        sc = Scenario("t", 10, [])
        r = run_audio(self.cfg, synth_audio(self.cfg, sc))
        self.assertLess(r.score, 0.1)

    def test_silence_below_level_gate(self):
        r = run_audio(self.cfg, np.zeros((FS * 6, 4)))
        self.assertEqual(r.score, 0.0)


def frames(fn, seconds=4.0, fps=10.0):
    det = BlueLightDetector(default_config().vision, Roi())
    rng = np.random.default_rng(0)
    res = None
    n = int(seconds * fps)
    for i in range(n):
        t = (i + 1) / fps
        img = rng.normal(90, 8, (48, 64, 3)).clip(0, 255)
        fn(img, t)
        res = det.process(t, img.astype(np.uint8))
    return res


class VisionTests(unittest.TestCase):
    def test_flashing_blue_detected(self):
        def draw(img, t):
            if t % 0.4 < 0.2:
                img[20:28, 28:36] = (30, 80, 250)
        r = frames(draw)
        self.assertGreater(r.score, 0.8)
        self.assertTrue(1.5 < r.flash_hz < 3.5)

    def test_steady_blue_ignored(self):
        def draw(img, t):
            img[20:28, 28:36] = (30, 80, 250)
        self.assertEqual(frames(draw).score, 0.0)

    def test_flashing_red_ignored(self):
        def draw(img, t):
            if t % 0.4 < 0.2:
                img[20:28, 28:36] = (250, 40, 40)
        self.assertEqual(frames(draw).score, 0.0)

    def test_receding_vehicle_ignored(self):
        def draw(img, t):
            r = max(1, int(12 - 3 * t))
            if t % 0.4 < 0.2:
                img[24 - r:24 + r, 32 - r:32 + r] = (30, 80, 250)
        r = frames(draw)
        self.assertFalse(r.approaching)
        self.assertEqual(r.score, 0.0)

    def test_roi_excludes_other_lane(self):
        det = BlueLightDetector(default_config().vision, Roi(0.0, 0.0, 0.5, 1.0))
        rng = np.random.default_rng(0)
        for i in range(40):
            t = (i + 1) / 10
            img = rng.normal(90, 8, (48, 64, 3)).clip(0, 255)
            if t % 0.4 < 0.2:
                img[20:28, 50:58] = (30, 80, 250)  # rechte Bildhälfte
            r = det.process(t, img.astype(np.uint8))
        self.assertEqual(r.score, 0.0)


if __name__ == "__main__":
    unittest.main()
