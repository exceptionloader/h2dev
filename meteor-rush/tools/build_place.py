#!/usr/bin/env python3
"""
Baut aus dem src/-Ordner eine Roblox-Place-Datei (MeteorRush.rbxlx),
die man direkt in Roblox Studio öffnen kann – ganz ohne Rojo.

Konventionen (wie bei Rojo):
  ordner/init.server.luau  -> Script   (Name = Ordnername, restliche Dateien = Kinder)
  ordner/init.client.luau  -> LocalScript
  name.server.luau         -> Script
  name.client.luau         -> LocalScript
  name.luau                -> ModuleScript
  ordner                   -> Folder

Aufruf:  python3 tools/build_place.py
"""
import os
import sys
from xml.sax.saxutils import escape

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")
OUT = os.path.join(ROOT, "MeteorRush.rbxlx")

_ref = [0]


def ref():
    _ref[0] += 1
    return "RBX%08X" % _ref[0]


def cdata(text):
    if "]]>" in text:
        raise ValueError("Quelltext enthält ']]>' – bitte umschreiben")
    return "<![CDATA[" + text + "]]>"


def script_item(cls, name, source, children_xml=""):
    return (
        '<Item class="%s" referent="%s"><Properties>'
        '<string name="Name">%s</string>'
        '<ProtectedString name="Source">%s</ProtectedString>'
        "</Properties>%s</Item>"
    ) % (cls, ref(), escape(name), cdata(source), children_xml)


def folder_item(name, children_xml):
    return '<Item class="Folder" referent="%s"><Properties><string name="Name">%s</string></Properties>%s</Item>' % (
        ref(),
        escape(name),
        children_xml,
    )


def classify(filename):
    if filename.endswith(".server.luau"):
        return "Script", filename[: -len(".server.luau")]
    if filename.endswith(".client.luau"):
        return "LocalScript", filename[: -len(".client.luau")]
    if filename.endswith(".luau"):
        return "ModuleScript", filename[: -len(".luau")]
    return None, None


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def build_dir(path):
    """Gibt XML für alle Kinder eines Ordners zurück."""
    out = []
    for entry in sorted(os.listdir(path)):
        full = os.path.join(path, entry)
        if os.path.isdir(full):
            out.append(build_node(full))
        else:
            if entry.startswith("init."):
                continue
            cls, name = classify(entry)
            if cls:
                out.append(script_item(cls, name, read(full)))
    return "".join(out)


def build_node(path):
    """Ein Ordner wird zu Folder – oder zu Script/LocalScript/Module, wenn er eine init-Datei hat."""
    name = os.path.basename(path)
    for init, cls in (("init.server.luau", "Script"), ("init.client.luau", "LocalScript"), ("init.luau", "ModuleScript")):
        init_path = os.path.join(path, init)
        if os.path.exists(init_path):
            return script_item(cls, name, read(init_path), build_dir(path))
    return folder_item(name, build_dir(path))


def service(cls, children_xml="", props=""):
    return '<Item class="%s" referent="%s"><Properties><string name="Name">%s</string>%s</Properties>%s</Item>' % (
        cls,
        ref(),
        cls,
        props,
        children_xml,
    )


def main():
    parts = []
    parts.append(service("Workspace", props='<bool name="StreamingEnabled">false</bool>'))
    parts.append(
        service(
            "Lighting",
            props=(
                '<token name="Technology">4</token>'  # Future
                '<bool name="GlobalShadows">true</bool>'
                '<float name="ClockTime">14.2</float>'
                '<float name="Brightness">2.6</float>'
            ),
        )
    )
    parts.append(service("ReplicatedStorage", build_dir(os.path.join(SRC, "ReplicatedStorage"))))
    parts.append(service("ServerScriptService", build_dir(os.path.join(SRC, "ServerScriptService"))))
    spc = build_dir(os.path.join(SRC, "StarterPlayer", "StarterPlayerScripts"))
    starter_player_scripts = '<Item class="StarterPlayerScripts" referent="%s"><Properties><string name="Name">StarterPlayerScripts</string></Properties>%s</Item>' % (ref(), spc)
    parts.append(service("StarterPlayer", starter_player_scripts))

    xml = (
        '<roblox xmlns:xmime="http://www.w3.org/2005/05/xmlmime" '
        'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
        'xsi:noNamespaceSchemaLocation="http://www.roblox.com/roblox.xsd" version="4">'
        "<External>null</External><External>nil</External>" + "".join(parts) + "</roblox>"
    )
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(xml)
    print("OK ->", os.path.relpath(OUT, os.getcwd()), "(%d KB)" % (len(xml) // 1024))


if __name__ == "__main__":
    sys.exit(main())
