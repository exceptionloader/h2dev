"""Simple in-process sliding-window rate limiter.

Good enough for a single backend process (the private beta). When we run more than one
process this must move to Redis, otherwise each process has its own counters.
"""

import time
from collections import defaultdict, deque
from threading import Lock

from app.core.errors import RateLimited


class RateLimiter:
    def __init__(self) -> None:
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = Lock()

    def hit(self, key: str, rule: str) -> None:
        limit, window = (int(x) for x in rule.split("/"))
        now = time.monotonic()
        with self._lock:
            hits = self._hits[key]
            while hits and hits[0] <= now - window:
                hits.popleft()
            if len(hits) >= limit:
                raise RateLimited(retry_after=max(1, int(hits[0] + window - now) + 1))
            hits.append(now)

    def reset(self) -> None:
        with self._lock:
            self._hits.clear()


limiter = RateLimiter()
