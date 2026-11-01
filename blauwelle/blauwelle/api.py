"""Kleine HTTP-API und Dashboard (nur Standardbibliothek).

GET  /                         Dashboard
GET  /api/status               aktueller Zustand
GET  /api/events?limit=50      letzte Ereignisse
GET  /api/stats                Kennzahlen
POST /api/events/<id>/feedback {"value": "korrekt"|"fehlalarm"|"verpasst"}   (Token)
POST /api/reset                Fehlerzustand zurücksetzen                     (Token)

Schreibende Aufrufe brauchen den Header  Authorization: Bearer <api.token>.
Standardmäßig nur auf 127.0.0.1 – für Fernzugriff ein VPN nutzen, nicht das
Internet.
"""

from __future__ import annotations

import hmac
import json
import logging
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import resources
from urllib.parse import parse_qs, urlparse

from .box import Box

log = logging.getLogger(__name__)


def make_handler(box: Box):
    token = box.cfg.api.token

    class Handler(BaseHTTPRequestHandler):
        server_version = "BlauWelle/0.1"

        def log_message(self, fmt, *args):
            log.debug("api: " + fmt, *args)

        def _send(self, code: int, body, ctype="application/json"):
            data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype + "; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def _authorized(self) -> bool:
            if not token:
                return False  # ohne Token keine schreibenden Aufrufe
            got = self.headers.get("Authorization", "")
            return hmac.compare_digest(got, f"Bearer {token}")

        def do_GET(self):
            url = urlparse(self.path)
            if url.path == "/":
                html = resources.files("blauwelle").joinpath("dashboard.html").read_bytes()
                return self._send(200, html, "text/html")
            if url.path == "/api/status":
                return self._send(200, box.status())
            if url.path == "/api/events":
                q = parse_qs(url.query)
                limit = min(500, max(1, int(q.get("limit", ["50"])[0])))
                return self._send(200, box.events.recent(limit))
            if url.path == "/api/stats":
                return self._send(200, box.events.stats())
            return self._send(404, {"error": "nicht gefunden"})

        def do_POST(self):
            if not self._authorized():
                return self._send(401, {"error": "Token fehlt oder falsch"})
            length = min(int(self.headers.get("Content-Length", "0") or 0), 10_000)
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "ungültiges JSON"})
            m = re.fullmatch(r"/api/events/(\d+)/feedback", self.path)
            if m:
                try:
                    ok = box.events.set_feedback(int(m.group(1)), str(body.get("value", "")))
                except ValueError as e:
                    return self._send(400, {"error": str(e)})
                return self._send(200 if ok else 404, {"ok": ok})
            if self.path == "/api/reset":
                return self._send(200, {"ok": box.reset()})
            return self._send(404, {"error": "nicht gefunden"})

    return Handler


def serve(box: Box) -> ThreadingHTTPServer:
    srv = ThreadingHTTPServer((box.cfg.api.host, box.cfg.api.port), make_handler(box))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    log.info("Dashboard: http://%s:%d/", box.cfg.api.host, box.cfg.api.port)
    return srv
