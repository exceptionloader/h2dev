import unittest

from blauwelle.config import from_dict
from blauwelle.eventlog import EventLog
from blauwelle.interfaces import SimulatedController
from blauwelle.preemption import Preemption, State


def make(**safety):
    cfg = from_dict({
        "approaches": [{"id": "nord", "priority": 1}, {"id": "ost"}, {"id": "west", "priority": 2}],
        "safety": {"cooldown_s": 5.0, "max_hold_s": 30.0, "lockout_s": 60.0,
                   "auto_recover_s": 0, **safety},
        "controller": {"intergreen_s": 4.0},
    })
    clock = {"t": 0.0}
    ctl = SimulatedController(["nord", "ost", "west"], 4.0, clock=lambda: clock["t"])
    log = EventLog(wall_clock=lambda: clock["t"])
    p = Preemption(cfg, ctl, emit=lambda k, a, c, d: log.add(k, a, c, d))

    def step(t, confirmed):
        clock["t"] = t
        p.step(t, confirmed)

    return p, ctl, log, step


def kinds(log):
    return [e["kind"] for e in reversed(log.recent(100))]


class PreemptionTests(unittest.TestCase):
    def test_normal_cycle(self):
        p, ctl, log, step = make()
        step(1.0, {"nord": 0.9})
        self.assertEqual(p.state, State.REQUESTED)
        self.assertEqual(ctl.status().requested, "nord")
        step(5.0, {"nord": 0.9})
        self.assertEqual(p.state, State.ACTIVE)
        step(10.0, {})
        self.assertEqual(p.state, State.COOLDOWN)
        self.assertIsNone(ctl.status().requested)
        step(12.0, {"nord": 0.9})  # noch in der Pause
        self.assertEqual(p.state, State.COOLDOWN)
        step(15.1, {})
        self.assertEqual(p.state, State.IDLE)
        self.assertEqual(kinds(log), ["request", "green", "release"])

    def test_priority_and_conflict(self):
        p, ctl, log, step = make()
        step(1.0, {"ost": 0.99, "west": 0.6})
        self.assertEqual(p.approach, "west")
        step(1.1, {"ost": 0.99, "west": 0.6})
        step(1.2, {"ost": 0.99, "west": 0.6})
        self.assertEqual(kinds(log).count("conflict"), 1)

    def test_max_hold_forces_release_and_lockout(self):
        p, ctl, log, step = make()
        t = 0.0
        while t < 31.0:
            t += 0.5
            step(t, {"nord": 0.9})
        self.assertIn(p.state, (State.COOLDOWN,))
        self.assertIsNone(ctl.status().requested)
        # Nach der Pause bleibt die Zufahrt gesperrt
        step(40.0, {"nord": 0.9})
        self.assertEqual(p.state, State.IDLE)
        self.assertIsNone(ctl.status().requested)
        # Andere Zufahrt geht weiterhin
        step(40.1, {"nord": 0.9, "ost": 0.9})
        self.assertEqual(p.approach, "ost")

    def test_controller_failure_goes_to_fault_and_releases(self):
        p, ctl, log, step = make()
        step(1.0, {"nord": 0.9})
        ctl.fail = True
        step(1.1, {"nord": 0.9})
        self.assertEqual(p.state, State.FAULT)
        ctl.fail = False
        self.assertIsNone(ctl.status().requested)
        step(2.0, {"nord": 0.9})
        self.assertEqual(p.state, State.FAULT)  # bleibt, bis jemand zurücksetzt
        self.assertTrue(p.reset(3.0))
        step(3.1, {"nord": 0.9})
        self.assertEqual(p.state, State.REQUESTED)

    def test_auto_recover(self):
        p, ctl, log, step = make(auto_recover_s=10.0)
        ctl.fail = True
        step(1.0, {"nord": 0.9})
        self.assertEqual(p.state, State.FAULT)
        ctl.fail = False
        step(11.5, {})
        self.assertEqual(p.state, State.IDLE)

    def test_force_release(self):
        p, ctl, log, step = make()
        step(1.0, {"nord": 0.9})
        p.force_release(1.5, "watchdog")
        self.assertIsNone(ctl.status().requested)
        self.assertEqual(p.state, State.COOLDOWN)


class EventLogTests(unittest.TestCase):
    def test_feedback_and_stats(self):
        log = EventLog()
        rid = log.add("request", "nord", 0.9, {})
        log.add("green", "nord", 0.9, {"time_to_green_s": 4.0})
        log.add("release", "nord", 0.9, {"duration_s": 12.0})
        self.assertTrue(log.set_feedback(rid, "fehlalarm"))
        with self.assertRaises(ValueError):
            log.set_feedback(rid, "quatsch")
        s = log.stats()
        self.assertEqual(s["anforderungen"], 1)
        self.assertEqual(s["fehlalarme"], 1)
        self.assertEqual(s["zeit_bis_gruen_s_mittel"], 4.0)


class ConfigTests(unittest.TestCase):
    def test_rejects_bad_config(self):
        with self.assertRaises(ValueError):
            from_dict({"approaches": []})
        with self.assertRaises(ValueError):
            from_dict({"approaches": [{"id": "a"}, {"id": "a"}]})
        with self.assertRaises(ValueError):
            from_dict({"approaches": [{"id": "a"}], "controller": {"type": "gpio"}})
        with self.assertRaises(ValueError):
            from_dict({"approaches": [{"id": "a"}], "unbekannt": 1})

    def test_example_config_loads(self):
        from pathlib import Path
        from blauwelle.config import load
        cfg = load(Path(__file__).parent.parent / "config" / "beispiel-kreuzung.json")
        self.assertEqual(len(cfg.approaches), 4)


if __name__ == "__main__":
    unittest.main()
