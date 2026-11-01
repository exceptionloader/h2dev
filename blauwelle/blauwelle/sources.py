"""Echte Hardware-Quellen: Mikrofon-Array (sounddevice) und Kameras (OpenCV).

Beide Bibliotheken sind optional und werden erst hier geladen, damit
Simulator und Tests ohne sie laufen.
"""

from __future__ import annotations

import logging
import queue
import threading
import time
from typing import Optional

import numpy as np

from .config import BoxConfig

log = logging.getLogger(__name__)


class MicArray:
    def __init__(self, cfg: BoxConfig):
        try:
            import sounddevice as sd  # type: ignore
        except ImportError as e:
            raise RuntimeError("sounddevice fehlt: pip install sounddevice") from e
        a = cfg.audio
        self.channels = len(a.mic_positions)
        self._q: queue.Queue = queue.Queue(maxsize=200)
        self._stream = sd.InputStream(
            samplerate=a.sample_rate, channels=self.channels, device=a.device,
            dtype="float32", blocksize=int(a.sample_rate * cfg.tick_s), callback=self._cb,
        )
        self.overflows = 0

    def _cb(self, data, frames, t, status):
        if status:
            self.overflows += 1
        try:
            self._q.put_nowait(data.copy())
        except queue.Full:
            self.overflows += 1

    def start(self):
        self._stream.start()

    def read(self) -> Optional[np.ndarray]:
        blocks = []
        while True:
            try:
                blocks.append(self._q.get_nowait())
            except queue.Empty:
                break
        return np.concatenate(blocks).astype(np.float64) if blocks else None

    def close(self):
        self._stream.stop()
        self._stream.close()


class Camera:
    """Liest in einem eigenen Thread, liefert immer nur das neueste Bild (RGB, verkleinert)."""

    def __init__(self, source, width: int = 320, fps: float = 15.0):
        try:
            import cv2  # type: ignore
        except ImportError as e:
            raise RuntimeError("opencv fehlt: pip install opencv-python-headless") from e
        self.cv2 = cv2
        self.cap = cv2.VideoCapture(source)
        self.cap.set(cv2.CAP_PROP_FPS, fps)
        self.width = width
        self._frame: Optional[np.ndarray] = None
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self.last_ok = 0.0
        self._thread = threading.Thread(target=self._loop, daemon=True)

    def start(self):
        self._thread.start()

    def _loop(self):
        while not self._stop.is_set():
            ok, bgr = self.cap.read()
            if not ok:
                time.sleep(0.2)
                continue
            h, w = bgr.shape[:2]
            if w > self.width:
                bgr = self.cv2.resize(bgr, (self.width, int(h * self.width / w)))
            rgb = self.cv2.cvtColor(bgr, self.cv2.COLOR_BGR2RGB)
            with self._lock:
                self._frame = rgb
                self.last_ok = time.monotonic()

    def read(self) -> Optional[np.ndarray]:
        with self._lock:
            f, self._frame = self._frame, None
        return f

    def close(self):
        self._stop.set()
        self._thread.join(timeout=2)
        self.cap.release()
