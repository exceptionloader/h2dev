#!/usr/bin/env python3
"""Erzeugt docs/COMMANDS.md aus dem Quellcode (alle new SimpleCommand(...)-Aufrufe)."""
import re, pathlib, sys
root = pathlib.Path(__file__).resolve().parent.parent
rows = []
for f in sorted((root / "core/src/main/java").rglob("*.java")):
    src = f.read_text(encoding="utf-8")
    for m in re.finditer(r"new SimpleCommand\(", src):
        i, depth = m.end(), 1
        while depth and i < len(src):
            depth += {"(": 1, ")": -1}.get(src[i], 0); i += 1
        call = src[m.end():i - 1]
        strs = re.findall(r'"((?:[^"\\]|\\.)*)"', call)
        if len(strs) < 3: continue
        # Top-Level-Argumente trennen; Aliase = abschliessende reine String-Literale
        args, d, cur, q = [], 0, "", False
        for ch in call:
            if ch == '"': q = not q
            if not q:
                if ch in "([{": d += 1
                elif ch in ")]}": d -= 1
                elif ch == "," and d == 0: args.append(cur.strip()); cur = ""; continue
            cur += ch
        args.append(cur.strip())
        aliases = []
        for a in reversed(args[3:]):
            if re.fullmatch(r'"[^"]*"', a): aliases.insert(0, a.strip('"'))
            else: break
        rows.append((strs[0], strs[1], strs[2], aliases, f.stem))
out = ["# MineSMP - Befehle\n", "Automatisch erzeugt mit `tools/gen_commands.py`.\n",
       "| Befehl | Aliase | Berechtigung | Beschreibung | Modul |", "|---|---|---|---|---|"]
for n, p, d, a, mod in sorted(rows):
    out.append(f"| /{n} | {', '.join('/'+x for x in a) or '-'} | `{p}` | {d} | {mod} |")
(root / "docs/COMMANDS.md").write_text("\n".join(out) + "\n", encoding="utf-8")
print(len(rows), "Befehle")
names = [r[0] for r in rows] + [x for r in rows for x in r[3]]
dups = {x for x in names if names.count(x) > 1}
print("Kollisionen:", dups or "keine"); sys.exit(1 if dups else 0)
