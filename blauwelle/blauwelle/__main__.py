"""Kommandozeile.

    python -m blauwelle simulate [szenario|alle]   Simulation ohne Hardware
    python -m blauwelle run -c config.json         Echter Betrieb an der Kreuzung
    python -m blauwelle check -c config.json       Konfiguration prüfen
"""

from __future__ import annotations

import argparse
import logging
import signal
import sys
import time

from . import config as config_mod
from . import simulate


def cmd_simulate(args) -> int:
    names = list(simulate.SCENARIOS) if args.scenario == "alle" else [args.scenario]
    failed = 0
    for name in names:
        print(f"\n=== Szenario: {name} ===")
        res = simulate.run(name, verbose=True)
        for e in res.events:
            if e["kind"] in ("request", "green", "release", "fault", "conflict", "warning"):
                d = {k: v for k, v in e["detail"].items() if k != "state_before"}
                print(f"  {e['ts']:6.1f}s  {e['kind']:<9} {e['approach'] or '':<6} "
                      f"konf={e['confidence']:.2f} {d or ''}")
        want = simulate.SCENARIOS[name].expected_requests
        got = [a for _, a in res.requests]
        status = "OK" if res.ok else "ABWEICHUNG"
        failed += not res.ok
        print(f"  Erwartet: {want or 'keine Anforderung'} · Ergebnis: {got or 'keine'} → {status}")
    return 1 if failed else 0


def cmd_check(args) -> int:
    cfg = config_mod.load(args.config)
    print(f"OK: {cfg.intersection_id} mit {len(cfg.approaches)} Zufahrten, Steuergerät: {cfg.controller.type}")
    return 0


def cmd_run(args) -> int:  # pragma: no cover - braucht Hardware
    from .api import serve
    from .box import Box
    from .eventlog import EventLog
    from .interfaces import make_controller
    from .sources import Camera, MicArray

    cfg = config_mod.load(args.config)
    controller = make_controller(cfg)
    box = Box(cfg, controller, EventLog(cfg.database))
    mics = MicArray(cfg) if cfg.audio.enabled else None
    cams = {a.id: Camera(a.camera, fps=cfg.vision.fps) for a in cfg.approaches
            if cfg.vision.enabled and a.camera is not None}
    if mics:
        mics.start()
    for c in cams.values():
        c.start()
    srv = serve(box) if cfg.api.enabled else None

    stop = {"flag": False}

    def _stop(*_):
        stop["flag"] = True

    signal.signal(signal.SIGTERM, _stop)
    signal.signal(signal.SIGINT, _stop)
    logging.info("BlauWelle läuft (%s)", cfg.intersection_id)
    try:
        while not stop["flag"]:
            t0 = time.monotonic()
            audio = mics.read() if mics else None
            frames = {aid: c.read() for aid, c in cams.items()}
            box.process(t0, audio, frames)
            time.sleep(max(0.0, cfg.tick_s - (time.monotonic() - t0)))
    finally:
        box.shutdown(time.monotonic())
        if mics:
            mics.close()
        for c in cams.values():
            c.close()
        if srv:
            srv.shutdown()
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="blauwelle", description="BlauWelle – Vorrangschaltung zum Nachrüsten")
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("simulate", help="Simulation ohne Hardware")
    s.add_argument("scenario", nargs="?", default="alle", choices=[*simulate.SCENARIOS, "alle"])
    s.set_defaults(func=cmd_simulate)
    r = sub.add_parser("run", help="Echter Betrieb")
    r.add_argument("-c", "--config", required=True)
    r.set_defaults(func=cmd_run)
    c = sub.add_parser("check", help="Konfiguration prüfen")
    c.add_argument("-c", "--config", required=True)
    c.set_defaults(func=cmd_check)
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO if args.verbose else logging.WARNING,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
