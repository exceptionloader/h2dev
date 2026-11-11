#!/usr/bin/env python3
"""
Bereitet die Blockbench-Modelle (NPCs und Bosse) fuer BetterModel auf dem Java-Server vor.

Quelle:  tools/models-src/npc/*.bbmodel, tools/models-src/bosse/*.bbmodel  (Bedrock-Format aus den Paketen)
Ziel:    core/src/main/resources/models/<name>.bbmodel                    (wird ins Plugin gepackt)

Was passiert:
- Format-Kennung "bedrock" -> "free" (Allgemeines Modell); die Geometrie bleibt unveraendert.
- Animationsnamen werden kurz: idle, walk, attack ("move" der Bosse heisst jetzt "walk").
  BetterModel spielt idle und walk selbst ab, attack loest MineSMPCore aus.
- Wuerfel mit Drehungen, die Java-Modelle nicht kennen (mehrere Achsen oder nicht 0/22,5/45 Grad),
  bekommen eine eigene Gruppe mit genau dieser Drehung; der Wuerfel selbst ist danach ungedreht. Optisch gleich.
- Gruppennamen, die BetterModel als Sonderfunktion liest (z.B. "cape_..." = Spieler-Umhang), werden umbenannt.
  Der Kopf der NPCs heisst "h_head", damit er dem Blick des NPCs folgt.
- Texturen bekommen ihre Breite/Hoehe eingetragen.
"""
import base64
import copy
import io
import json
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "tools" / "models-src"
OUT = ROOT / "core" / "src" / "main" / "resources" / "models"
JAVA_ANGLES = (-45.0, -22.5, 22.5, 45.0)


def java_ok(rot):
    nz = [r for r in rot if abs(r) > 1e-6]
    return not nz or (len(nz) == 1 and any(abs(nz[0] - a) < 1e-3 for a in JAVA_ANGLES))


def png_size(data_uri):
    try:
        raw = base64.b64decode(data_uri.split(",", 1)[1])
        if raw[:8] == b"\x89PNG\r\n\x1a\n":
            return int.from_bytes(raw[16:20], "big"), int.from_bytes(raw[20:24], "big")
    except Exception:
        pass
    return None


def convert(path, kind):
    j = json.loads(path.read_text(encoding="utf-8"))
    name = path.stem
    j["meta"]["model_format"] = "free"
    j["name"] = name
    groups = {g["uuid"]: g for g in j["groups"]}
    renames = {}
    for g in j["groups"]:
        n = g["name"]
        if n == "cape" or n.startswith("cape_"):
            g["name"] = "mantel" + n[4:]
        elif kind == "npc" and n == "head":
            g["name"] = "h_head"
        if g["name"] != n:
            renames[g["uuid"]] = g["name"]
    for a in j.get("animations", []):
        short = a["name"].split(".")[-1]
        a["name"] = "walk" if short == "move" else short
        for key, an in a.get("animators", {}).items():
            if key in renames:
                an["name"] = renames[key]
    for t in j.get("textures", []):
        size = png_size(t.get("source", ""))
        if size:
            t["width"], t["height"] = size
            t.setdefault("uv_width", size[0])
            t.setdefault("uv_height", size[1])

    cubes = {e["uuid"]: e for e in j["elements"] if e.get("type", "cube") == "cube"}
    wrapped = 0
    template = copy.deepcopy(j["groups"][0])

    def walk(children):
        nonlocal wrapped
        for i, c in enumerate(children):
            if isinstance(c, dict):
                walk(c.get("children", []))
                continue
            e = cubes.get(c)
            if e is None or java_ok(e.get("rotation", [0, 0, 0])):
                continue
            wrapped += 1
            gid = str(uuid.uuid5(uuid.NAMESPACE_URL, name + "/" + c))
            g = copy.deepcopy(template)
            g.update({"name": "dreh_%d" % wrapped, "uuid": gid, "origin": list(e.get("origin", [0, 0, 0])),
                      "rotation": list(e["rotation"]), "isOpen": False})
            j["groups"].append(g)
            e["rotation"] = [0, 0, 0]
            children[i] = {"uuid": gid, "isOpen": False, "children": [c]}

    walk(j["outliner"])
    bad = [e for e in cubes.values() if not java_ok(e.get("rotation", [0, 0, 0]))]
    assert not bad, (name, len(bad))
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / (name + ".bbmodel")).write_text(json.dumps(j, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    anims = [a["name"] for a in j.get("animations", [])]
    print(f"{name:16s} {kind:5s} Wuerfel {len(cubes):3d}  neue Drehgruppen {wrapped:3d}  Gruppen gesamt {len(j['groups']):3d}  Animationen {anims}"
          + (f"  umbenannt {sorted(set(renames.values()))}" if renames else ""))


def main():
    n = 0
    for kind, folder in (("npc", "npc"), ("boss", "bosse")):
        for p in sorted((SRC / folder).glob("*.bbmodel")):
            convert(p, kind)
            n += 1
    print(n, "Modelle ->", OUT.relative_to(ROOT))
    return 0 if n else 1


if __name__ == "__main__":
    sys.exit(main())
