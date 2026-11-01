import json
import unittest
import urllib.error
import urllib.request

from blauwelle import simulate
from blauwelle.api import serve
from blauwelle.box import Box
from blauwelle.config import from_dict
from blauwelle.interfaces import SimulatedController


class ScenarioTests(unittest.TestCase):
    def test_all_scenarios(self):
        for name, sc in simulate.SCENARIOS.items():
            with self.subTest(name):
                res = simulate.run(name)
                self.assertEqual([a for _, a in res.requests], sc.expected_requests)
                # Nie zwei gleichzeitige Anforderungen, immer freigegeben am Ende
                if res.controller_history:
                    self.assertEqual(res.controller_history[-1][1], "release")

    def test_einsatz_timing(self):
        res = simulate.run("einsatz")
        kinds = {e["kind"]: e for e in res.events}
        v = simulate.SCENARIOS["einsatz"].vehicles[0]
        # Grün muss vor der Ankunft an der Kreuzung da sein
        self.assertLess(kinds["green"]["ts"], v.t_pass)
        # und spätestens 6 s nach der Durchfahrt wieder freigegeben werden
        self.assertLess(kinds["release"]["ts"], v.t_pass + 6.0)

    def test_watchdog_releases(self):
        cfg = simulate.default_config()
        res_cfg = cfg
        clock = {"t": 0.0}
        ctl = SimulatedController([a.id for a in cfg.approaches], 4.0, clock=lambda: clock["t"])
        box = Box(res_cfg, ctl)
        box.preemption.step(1.0, {"nord": 0.9})
        self.assertEqual(ctl.status().requested, "nord")
        box.process(1.0)
        clock["t"] = 5.0
        box.process(5.0)  # 4 s Pause > watchdog_s
        self.assertIsNone(ctl.status().requested)


class ApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cfg = from_dict({"approaches": [{"id": "nord"}],
                         "api": {"port": 0, "token": "geheim"}})
        cls.box = Box(cfg, SimulatedController(["nord"]))
        cls.srv = serve(cls.box)
        cls.base = f"http://127.0.0.1:{cls.srv.server_address[1]}"
        cls.eid = cls.box.events.add("request", "nord", 0.9, {})

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def get(self, path):
        with urllib.request.urlopen(self.base + path) as r:
            return r.status, r.read()

    def post(self, path, body, token=None):
        req = urllib.request.Request(self.base + path, data=json.dumps(body).encode(), method="POST")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_read_endpoints(self):
        code, body = self.get("/api/status")
        self.assertEqual(code, 200)
        self.assertEqual(json.loads(body)["state"], "idle")
        self.assertEqual(self.get("/api/stats")[0], 200)
        self.assertEqual(len(json.loads(self.get("/api/events")[1])), 1)
        code, html = self.get("/")
        self.assertIn(b"BlauWelle", html)

    def test_write_needs_token(self):
        self.assertEqual(self.post(f"/api/events/{self.eid}/feedback", {"value": "korrekt"})[0], 401)
        self.assertEqual(self.post(f"/api/events/{self.eid}/feedback", {"value": "korrekt"}, "falsch")[0], 401)
        code, body = self.post(f"/api/events/{self.eid}/feedback", {"value": "korrekt"}, "geheim")
        self.assertEqual((code, body["ok"]), (200, True))
        self.assertEqual(self.post("/api/events/1/feedback", {"value": "x"}, "geheim")[0], 400)


if __name__ == "__main__":
    unittest.main()
