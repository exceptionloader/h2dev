#!/usr/bin/env python3
"""Erzeugt das MineSMP-Ressourcenpaket (Logo, Abschnitts-Kaestchen, Icons) als Schriftzeichen.
Ausgabe: resourcepack/ (Quellordner) und core/src/main/resources/minesmp-pack.zip
Zeichen liegen im privaten Unicode-Bereich (U+E000...) der Standard-Schrift und werden in der
Konfiguration ueber Kuerzel wie :logo: :coin: eingesetzt (siehe GLYPHS unten / Placeholders.java)."""
import json, os, pathlib, zipfile
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resourcepack"
TEX = OUT / "assets/minesmp/textures/font"
S = 2  # Bilder in doppelter Aufloesung, im Spiel halb so hoch -> feinere Kanten

# ---------------------------------------------------------------- 5x7-Pixelschrift
F = {
 'A':["01110","10001","10001","11111","10001","10001","10001"], 'B':["11110","10001","11110","10001","10001","10001","11110"],
 'C':["01111","10000","10000","10000","10000","10000","01111"], 'D':["11110","10001","10001","10001","10001","10001","11110"],
 'E':["11111","10000","11110","10000","10000","10000","11111"], 'F':["11111","10000","11110","10000","10000","10000","10000"],
 'G':["01111","10000","10000","10011","10001","10001","01111"], 'I':["111","010","010","010","010","010","111"],
 'K':["10001","10010","11100","10010","10001","10001","10001"], 'L':["10000","10000","10000","10000","10000","10000","11111"],
 'M':["10001","11011","10101","10001","10001","10001","10001"], 'N':["10001","11001","10101","10011","10001","10001","10001"],
 'O':["01110","10001","10001","10001","10001","10001","01110"], 'P':["11110","10001","10001","11110","10000","10000","10000"],
 'R':["11110","10001","10001","11110","10010","10001","10001"], 'S':["01111","10000","10000","01110","00001","00001","11110"],
 'T':["11111","00100","00100","00100","00100","00100","00100"], 'V':["10001","10001","10001","10001","10001","01010","00100"],
 'Y':["10001","10001","01010","00100","00100","00100","00100"],
 'W':["10001","10001","10001","10101","10101","11011","10001"], 'H':["10001","10001","10001","11111","10001","10001","10001"], 'U':["10001","10001","10001","10001","10001","10001","01110"],
 '.':["0","0","0","0","0","0","1"], ' ':["00","00","00","00","00","00","00"],
}

def text_width(t, px):
    return sum((len(F[c][0]) + 1) * px for c in t) - px

def draw_text(d, t, x, y, px, color):
    for c in t:
        g = F[c]
        for r, row in enumerate(g):
            for k, v in enumerate(row):
                if v == '1':
                    d.rectangle([x + k * px, y + r * px, x + (k + 1) * px - 1, y + (r + 1) * px - 1], fill=color)
        x += (len(g[0]) + 1) * px

def hexc(h, a=255):
    h = h.lstrip('#'); return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)

def pill(text, fill, fg, h=9, pad=4, shadow=True):
    """Abgerundetes Kaestchen mit Text. h = Hoehe im Spiel (Pixel)."""
    H = h * S
    tw = text_width(text, 1 * S)
    W = tw + pad * 2 * S
    img = Image.new('RGBA', (W, H + (S if shadow else 0)), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if shadow:
        d.rounded_rectangle([0, S, W - 1, H + S - 1], radius=H // 2 - 1, fill=(0, 0, 0, 90))
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=H // 2 - 1, fill=hexc(fill))
    # leichter Glanz oben (eigene Ebene, damit die Transparenz gemischt wird)
    gl = Image.new('RGBA', img.size, (0, 0, 0, 0))
    ImageDraw.Draw(gl).rounded_rectangle([2 * S, S, W - 1 - 2 * S, H // 2 - S], radius=H // 4, fill=(255, 255, 255, 45))
    img.alpha_composite(gl)
    d = ImageDraw.Draw(img)
    draw_text(d, text, pad * S, (H - 7 * S) // 2, 1 * S, hexc(fg))
    return img

def logo():
    a, b = pill("MINE", "#22C3D6", "#FFFFFF", h=11, pad=5), pill("SMP", "#FFFFFF", "#1B2733", h=11, pad=5)
    img = Image.new('RGBA', (a.width + b.width + 2 * S, max(a.height, b.height)), (0, 0, 0, 0))
    img.alpha_composite(a, (0, 0)); img.alpha_composite(b, (a.width + 2 * S, 0))
    return img

# ---------------------------------------------------------------- 9x9-Icons (Zeichen -> Farbe)
ICONS = {
 'coin': ({'o':"#2E8B3A", 'g':"#4ADE5A", 'h':"#B9F7A8"}, [
   "..ooooo..", ".oggggho.", "oggoooggo", "oggoggggo", "oggoooggo", "oggggogo.", "oggoooggo", ".oggggggo", "..ooooo.."]),
 'star': ({'o':"#B7791F", 'y':"#FACC15", 'h':"#FFF3A3"}, [
   "....o....", "...oyo...", "...oho...", "oooyyyooo", "oyyyhyyyo", ".ooyyyoo.", ".oyyoyyo.", "oyyo.oyyo", "ooo...ooo"]),
 'sword': ({'o':"#3F3F46", 's':"#E5E7EB", 'h':"#FFFFFF", 'b':"#8B5A2B", 'r':"#EF4444"}, [
   ".......oo", "......ohs", ".....ohso", "....ohso.", "o..ohso..", "oroohso..", ".orrso...", ".obro....", "obo.o...."]),
 'skull': ({'o':"#3F3F46", 'w':"#F4F4F5", 'g':"#A1A1AA"}, [
   "..ooooo..", ".owwwwwo.", "owwwwwwwo", "owoowoowo", "owoowoowo", "owwwgwwwo", ".owwwwwo.", ".owowowo.", "..ooooo.."]),
 'shield': ({'o':"#1E3A8A", 'b':"#4F7CFF", 'c':"#55FFFF", 'h':"#BFD3FF"}, [
   "ooooooooo", "obbbcbbbo", "obhbcbbbo", "obbbcbbbo", "occcccccо", "obbbcbbbo", ".obbcbbo.", "..obcbo..", "...ooo..."]),
 'people': ({'o':"#155E75", 'c':"#22C3D6", 'h':"#A5F3FC"}, [
   "...ooo...", "..ohcco..", "..occco..", "...ooo...", ".ooooooo.", "ohcccccco", "occcccccо", "occcccccо", "ooooooooo"]),
 'crown': ({'o':"#92400E", 'y':"#FBBF24", 'r':"#EF4444", 'h':"#FFF3A3"}, [
   ".........", "o...o...o", "oo.ooo.oo", "oyoyyyoyo", "oyhyyyyyo", "oyyryyryo", "oyyyyyyyo", "ooooooooo", "........."]),
 'pin': ({'o':"#7F1D1D", 'r':"#EF4444", 'h':"#FCA5A5", 'w':"#FFFFFF"}, [
   "..ooooo..", ".orrrrro.", "orhrwrrro", "orrwwwrro", "orrrwrrro", ".orrrrro.", "..orrro..", "...oro...", "....o...."]),
 'clock': ({'o':"#334155", 'w':"#F8FAFC", 'b':"#4F7CFF"}, [
   "..ooooo..", ".owwwwwo.", "owwwbwwwo", "owwwbwwwo", "owwwbbbwo", "owwwwwwwo", "owwwwwwwo", ".owwwwwo.", "..ooooo.."]),
 'heart': ({'o':"#7F1D1D", 'r':"#EF4444", 'h':"#FCA5A5"}, [
   ".........", ".oo...oo.", "orho.orro", "orrroorro", "orrrrrrro", ".orrrrro.", "..orrro..", "...oro...", "....o...."]),
 'bolt': ({'o':"#854D0E", 'y':"#FDE047"}, [
   ".....ooo.", "....oyyo.", "...oyyo..", "..oyyyooo", ".oyyyyyyo", "ooooyyyo.", "...oyyo..", "..oyyo...", "..ooo...."]),
 'arrow': ({'o':"#0E7490", 'c':"#22C3D6"}, [
   "....o....", "...oco...", "..occco..", ".occccco.", "ooocccooo", "...oco...", "...oco...", "...oco...", "...ooo..."]),
 'dot': ({'g':"#4ADE5A", 'o':"#166534"}, [
   ".........", ".........", "...ooo...", "..oggo...", "..oggo...", "...ooo...", ".........", ".........", "........."]),
}

def icon(spec):
    pal, rows = spec
    img = Image.new('RGBA', (9 * S, 9 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row[:9]):
            ch = 'o' if ch == 'о' else ch  # kyrillisches o abfangen
            if ch in pal:
                d.rectangle([x * S, y * S, (x + 1) * S - 1, (y + 1) * S - 1], fill=hexc(pal[ch]))
    return img

# ---------------------------------------------------------------- Zeichen-Tabelle
GLYPHS = []  # (kuerzel, codepoint, Bild, Hoehe im Spiel, ascent)
cp = 0xE000
def add(name, img, height, ascent):
    global cp
    GLYPHS.append((name, cp, img, height, ascent)); cp += 1

add('logo', logo(), 12, 10)
for name, text, fill, fg in [('profil', 'PROFIL', '#22C3D6', '#FFFFFF'), ('kampf', 'KAMPF', '#E5484D', '#FFFFFF'),
                             ('clan', 'CLAN', '#4F7CFF', '#FFFFFF'), ('server', 'SERVER', '#FFFFFF', '#1B2733'),
                             ('event', 'EVENT', '#A855F7', '#FFFFFF'), ('daily', 'DAILY', '#22C55E', '#FFFFFF'),
                             ('vote', 'VOTE', '#FACC15', '#1B2733'), ('combat', 'IM KAMPF', '#E5484D', '#FFFFFF'),
                             ('web', 'MINESMP.DE', '#1E3A5F', '#55FFFF')]:
    add(name, pill(text, fill, fg), 10, 8)
for name in ICONS:
    add(name, icon(ICONS[name]), 9, 8)

# Rang-Abzeichen (Namensschild, Chat, Scoreboard)
RANKS = [('rank_owner', 'OWNER', '#B3261E', '#FFFFFF'), ('rank_admin', 'ADMIN', '#E5484D', '#FFFFFF'),
         ('rank_dev', 'DEV', '#A855F7', '#FFFFFF'), ('rank_mod', 'MOD', '#F59E0B', '#FFFFFF'),
         ('rank_support', 'SUPPORT', '#FACC15', '#1B2733'), ('rank_builder', 'BUILDER', '#22C55E', '#FFFFFF'),
         ('rank_legende', 'LEGENDE', '#4F7CFF', '#FFFFFF'), ('rank_mvp', 'MVP', '#22C3D6', '#FFFFFF'),
         ('rank_vip', 'VIP', '#8FF3FF', '#0B3B44'), ('rank_spieler', 'SPIELER', '#64748B', '#FFFFFF')]
for name, text, fill, fg in RANKS:
    add(name, pill(text, fill, fg), 10, 8)

def build():
    import shutil
    if OUT.exists(): shutil.rmtree(OUT)
    TEX.mkdir(parents=True)
    providers = []
    for name, c, img, h, asc in GLYPHS:
        img.save(TEX / f"{name}.png")
        providers.append({"type": "bitmap", "file": f"minesmp:font/{name}.png", "ascent": asc, "height": h, "chars": [chr(c)]})
    providers.append({"type": "space", "advances": {"\ue0f0": -1, "\ue0f1": 1, "\ue0f2": 3, "\ue0f3": 6}})
    (OUT / "assets/minecraft/font").mkdir(parents=True)
    (OUT / "assets/minecraft/font/default.json").write_text(json.dumps({"providers": providers}, ensure_ascii=True, indent=1))
    (OUT / "pack.mcmeta").write_text(json.dumps({"pack": {"description": "§b§lMINE§f§lSMP §7Ressourcenpaket",
        "pack_format": 75, "min_format": [75, 0], "max_format": [75, 0]}}, ensure_ascii=False, indent=1))
    # Vorschaubild fuer den Paket-Bildschirm
    logo().resize((logo().width * 2, logo().height * 2), Image.NEAREST).save(OUT / "pack.png")
    z = ROOT / "core/src/main/resources/minesmp-pack.zip"
    with zipfile.ZipFile(z, 'w', zipfile.ZIP_DEFLATED) as zf:
        for p in sorted(OUT.rglob('*')):
            if p.is_file():
                zi = zipfile.ZipInfo(str(p.relative_to(OUT)), date_time=(2026, 1, 1, 0, 0, 0))
                zi.compress_type = zipfile.ZIP_DEFLATED
                zf.writestr(zi, p.read_bytes())
    # Kuerzel-Tabelle fuer das Plugin
    (ROOT / "core/src/main/resources/glyphs.yml").write_text(
        "# Automatisch erzeugt von tools/build_pack.py - Kuerzel :name: -> Zeichen im Ressourcenpaket\n" +
        "".join(f'{n}: "\\u{c:04X}"\n' for n, c, *_ in GLYPHS))
    print(len(GLYPHS), "Zeichen,", z.stat().st_size, "Bytes")

if __name__ == "__main__":
    build()
