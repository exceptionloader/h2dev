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
 'gem': ({'o':"#6B21A8", 'p':"#C084FC", 'h':"#F5D0FE", 'd':"#9333EA"}, [
   ".........", "..ooooo..", ".ohpppdo.", "ohppppddo", "ooooooooo", ".oppppdo.", "..oppdo..", "...opo...", "....o...."]),
 'key': ({'o':"#92400E", 'y':"#FBBF24", 'h':"#FFF3A3"}, [
   "...ooo...", "..oyhyo..", "..oy.yo..", "..oyyyo..", "...oyo...", "...oyo...", "...oyyo..", "...oyo...", "...oyyo.."]),
 'check': ({'g':"#4ADE5A", 'd':"#15803D"}, [
   ".........", "........g", ".......gg", "......ggd", "g....ggd.", "gg..ggd..", "dggggd...", ".dggd....", "..dd....."]),
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

# ---------------------------------------------------------------- Scoreboard-Rahmen mit Logo
# Passt genau auf die Vanilla-Sidebar mit 15 Zeilen: Rahmen beginnt mitten in der Titelzeile und endet 2px unter der letzten Zeile.
BOARD_W, BOARD_LH, BOARD_F = 132, 16, 15 * 9 + 7


def big_pill(text, fill, fg, px=2, pad=5, h=20):
    H, P = h * S, px * S
    W = text_width(text, P) + pad * 2 * S
    img = Image.new('RGBA', (W, H + S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, S, W - 1, H + S - 1], radius=H // 2 - 2, fill=(0, 0, 0, 110))
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=H // 2 - 2, fill=hexc(fill))
    gl = Image.new('RGBA', img.size, (0, 0, 0, 0))
    ImageDraw.Draw(gl).rounded_rectangle([3 * S, S, W - 1 - 3 * S, H // 2 - S], radius=H // 4, fill=(255, 255, 255, 50))
    img.alpha_composite(gl)
    draw_text(ImageDraw.Draw(img), text, pad * S, (H - 7 * P) // 2, P, hexc(fg))
    return img


def gem(d, cx, cy, col, r=2):
    for k in range(-r, r + 1):
        w = r - abs(k)
        d.rectangle([(cx - w) * S, (cy + k) * S, (cx + w + 1) * S - 1, (cy + k + 1) * S - 1], fill=hexc(col))


def board():
    W, Lh, F = BOARD_W, BOARD_LH, BOARD_F
    img = Image.new('RGBA', (W * S, (Lh + F + 3) * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    top, bot = Lh, Lh + F - 1
    R = lambda x1, y1, x2, y2, c: d.rectangle([x1 * S, y1 * S, (x2 + 1) * S - 1, (y2 + 1) * S - 1], fill=c)
    R(1, top + 1, W - 2, bot - 1, (8, 24, 32, 85))                       # leicht getoente Flaeche
    R(2, top, W - 3, top, hexc('#0B2A33')); R(2, bot, W - 3, bot, hexc('#0B2A33'))
    R(0, top + 2, 0, bot - 2, hexc('#0B2A33')); R(W - 1, top + 2, W - 1, bot - 2, hexc('#0B2A33'))
    R(1, top + 1, 1, top + 1, hexc('#0B2A33')); R(W - 2, top + 1, W - 2, top + 1, hexc('#0B2A33'))
    R(1, bot - 1, 1, bot - 1, hexc('#0B2A33')); R(W - 2, bot - 1, W - 2, bot - 1, hexc('#0B2A33'))
    R(2, top + 1, W - 3, top + 1, hexc('#22C3D6')); R(2, bot - 1, W - 3, bot - 1, hexc('#22C3D6'))   # tuerkiser Rand
    R(1, top + 2, 1, bot - 2, hexc('#22C3D6')); R(W - 2, top + 2, W - 2, bot - 2, hexc('#22C3D6'))
    R(3, top + 2, W - 4, top + 2, hexc('#4F7CFF')); R(3, bot - 2, W - 4, bot - 2, hexc('#4F7CFF'))   # blaue Innenlinie
    R(2, top + 3, 2, bot - 3, hexc('#4F7CFF')); R(W - 3, top + 3, W - 3, bot - 3, hexc('#4F7CFF'))
    for x in (4, W - 5):                                                    # Eck-Edelsteine
        gem(d, x, top + 1, '#B6F7FF', 1); gem(d, x, bot - 1, '#B6F7FF', 1)
    a, b = big_pill("MINE", "#22C3D6", "#FFFFFF"), big_pill("SMP", "#FFFFFF", "#1B2733")
    lw = a.width + b.width + 2 * S
    x0 = (W * S - lw) // 2
    y0 = (top - 14) * S
    for gx in (x0 // S - 7, (x0 + lw) // S + 6):                           # Funkeln neben dem Logo
        gem(d, gx, top - 4, '#55FFFF', 2); gem(d, gx + (3 if gx > W // 2 else -3), top - 9, '#FFFFFF', 1)
    img.alpha_composite(a, (x0, y0)); img.alpha_composite(b, (x0 + a.width + 2 * S, y0))
    for i, x in enumerate(range(10, W - 9, 9)):                             # Deko unten
        gem(d, x, bot + 1, '#22C3D6' if i % 2 else '#B6F7FF', 1 if i % 3 else 2)
    return img


add('board', board(), BOARD_LH + BOARD_F + 3, BOARD_LH + 3)

# Rang-Abzeichen (Namensschild, Tab, Chat, Scoreboard): eckiges, flaches Kaestchen mit weisser Schrift
def mix(h, other, t):
    a, b = hexc(h), hexc(other)
    return tuple(round(a[i] * (1 - t) + b[i] * t) for i in range(3)) + (255,)

def tag(text, fill, fg='#FFFFFF', h=9, pad=2):
    H = h * S
    W = text_width(text, S) + pad * 2 * S
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W - 1, H - 1], fill=hexc(fill))
    d.rectangle([0, 0, W - 1, S // 2 - 1 if S > 1 else 0], fill=mix(fill, '#FFFFFF', 0.25))   # feine helle Oberkante
    d.rectangle([0, H - S, W - 1, H - 1], fill=mix(fill, '#000000', 0.28))                    # dunkle Unterkante
    y = (H - S - 7 * S) // 2 + S // 2
    draw_text(d, text, pad * S + S // 2, y + S // 2, S, mix(fill, '#000000', 0.45))            # Schatten
    draw_text(d, text, pad * S, y, S, hexc(fg))
    return img

GEM = ["....o....", "...ohо...".replace('о', 'o'), "..ohhpo..", ".ohhpppo.", "ohhpppddo", ".oppdddo.", "..opddo..", "...odo...", "....o...."]

RANKS = [('owner', 'OWNER', '#B91C1C'), ('admin', 'ADMIN', '#E02B2B'), ('dev', 'DEV', '#9333EA'), ('mod', 'MOD', '#EA580C'),
         ('support', 'SUPPORT', '#CA8A04'), ('builder', 'BUILDER', '#16A34A'), ('premium', 'PREMIUM', '#D99A00'),
         ('legende', 'LEGENDE', '#3B5BDB'), ('mvp', 'MVP', '#0E9FB3'), ('vip', 'VIP', '#2F9BE0'), ('spieler', 'SPIELER', '#5B6472')]
for rid, text, fill in RANKS:
    add('rank_' + rid, tag(text, fill), 9, 8)
# Kleiner Edelstein in Rangfarbe (steht vor dem Abzeichen)
for rid, text, fill in RANKS:
    pal = {'o': '#%02X%02X%02X' % mix(fill, '#000000', 0.5)[:3], 'p': fill, 'h': '#%02X%02X%02X' % mix(fill, '#FFFFFF', 0.6)[:3],
           'd': '#%02X%02X%02X' % mix(fill, '#000000', 0.22)[:3]}
    add('icon_' + rid, icon((pal, GEM)), 9, 8)

def build():
    import shutil
    if OUT.exists(): shutil.rmtree(OUT)
    TEX.mkdir(parents=True)
    providers = []
    for name, c, img, h, asc in GLYPHS:
        img.save(TEX / f"{name}.png")
        providers.append({"type": "bitmap", "file": f"minesmp:font/{name}.png", "ascent": asc, "height": h, "chars": [chr(c)]})
    providers.append({"type": "space", "advances": {"\ue0f0": -1, "\ue0f1": 1, "\ue0f2": 3, "\ue0f3": 6, "\ue0f4": 8, "\ue0f5": 2}})
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
        "".join(f'{n}: "\\u{c:04X}"\n' for n, c, *_ in GLYPHS)
        + 'sp-1: "\\uE0F0"\nsp1: "\\uE0F1"\nsp2: "\\uE0F5"\nsp3: "\\uE0F2"\nsp6: "\\uE0F3"\nsp8: "\\uE0F4"\n')
    print(len(GLYPHS), "Zeichen,", z.stat().st_size, "Bytes")

if __name__ == "__main__":
    build()
