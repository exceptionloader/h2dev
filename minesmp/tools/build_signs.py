#!/usr/bin/env python3
"""Grosse Schilder fuer den Spawn: MINESMP-Logo und Bereichs-Schilder (MARKT, HANDEL, KISTEN ...) als Pixel-Grafiken.
Sie liegen als Schriftzeichen in der eigenen Schrift minesmp:signs und werden im Spiel von Text-Displays gross gezeigt
(SpawnService, Liste "signs" im Spawn-Layout). Ein Bild wird in Streifen von hoechstens 240 Pixeln Breite zerlegt
(Minecraft legt Schriftzeichen auf 256er-Seiten ab); zwischen den Streifen schliesst ein Abstand von -1 die Luecke.
Aufruf ueber tools/build_pack.py. Ergebnis: assets/minesmp/font/signs.json + Bilder, core/src/main/resources/signs.yml."""
import json
from PIL import Image, ImageDraw
import build_pack as B

T = 4            # Bildpunkte je Schrift-Pixel (alle Masse sind Vielfache davon)
FIRST = 0xE200   # erstes Zeichen der Schild-Schrift
SPACER = 0xE2FF  # Abstand -1

# Fehlende Zeichen fuer die 5x7-Pixelschrift
B.F.setdefault('-', ["000", "000", "000", "111", "000", "000", "000"])
B.F.setdefault('·', ["0", "0", "0", "1", "0", "0", "0"])
B.F.setdefault('&', ["01100", "10010", "10100", "01000", "10101", "10010", "01101"])

def rgb(h, a=255):
    return B.hexc(h, a)

def mix(a, b, t):
    a, b = rgb(a), rgb(b)
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)

def text_w(t, L):
    return B.text_width(t, L)

def chamfer_rect(d, box, r, fill):
    """Rechteck mit treppenfoermig abgeschnittenen Ecken (Pixel-Look). r = Stufen, Stufe = T*2 Pixel."""
    x0, y0, x1, y1 = box
    s = T * 2
    for i in range(r + 1):
        inset = (r - i) * s
        d.rectangle([x0 + inset, y0 + i * s, x1 - inset, y1 - i * s], fill=fill)

def plaque(text, c_light, c_dark, icon=None, L=8):
    """Bereichs-Schild: Rahmen, Farbverlauf, Glanzkante, Symbol links und rechts, Schrift mit Schatten."""
    ic = 9 * L                         # Symbol 9x9
    tw = text_w(text, L)
    pad_x, pad_y = 4 * L, 2 * L
    inner_w = tw + (2 * (ic + 3 * L) if icon else 0)
    W = inner_w + 2 * pad_x
    H = 7 * L + 2 * pad_y
    W += (-W) % T; H += (-H) % T
    O = T * 2                          # Umriss
    img = Image.new('RGBA', (W + 2 * O, H + 2 * O + 2 * T), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Schatten unter dem Schild, Umriss, Flaeche
    chamfer_rect(d, (O, O + 2 * T, W + O - 1, H + O + 2 * T - 1), 2, (10, 14, 20, 110))
    chamfer_rect(d, (0, 0, W + 2 * O - 1, H + 2 * O - 1), 2, rgb('#0B141C'))
    band = Image.new('RGBA', (W, H))
    bd = ImageDraw.Draw(band)
    steps = H // T
    for i in range(steps):
        bd.rectangle([0, i * T, W, (i + 1) * T - 1], fill=mix(c_light, c_dark, i / max(1, steps - 1)))
    mask = Image.new('L', (W, H), 0)
    chamfer_rect(ImageDraw.Draw(mask), (0, 0, W - 1, H - 1), 2, 255)
    img.paste(band, (O, O), mask)
    # Glanzkante oben, dunkle Kante unten, innerer heller Rahmen
    d.rectangle([O + 3 * T, O + T, W + O - 3 * T - 1, O + 2 * T - 1], fill=(255, 255, 255, 90))
    d.rectangle([O + 3 * T, H + O - 2 * T, W + O - 3 * T - 1, H + O - T - 1], fill=(0, 0, 0, 70))
    x = O + pad_x
    if icon:
        im = B.icon(B.ICONS[icon]).resize((ic, ic), Image.NEAREST)
        img.alpha_composite(im, (x, O + (H - ic) // 2))
        img.alpha_composite(im, (O + W - pad_x - ic, O + (H - ic) // 2))
        x += ic + 3 * L
    ty = O + pad_y
    shadow = mix(c_dark, '#000000', 0.55)
    B.draw_text(d, text, x + L // 2, ty + L // 2, L, shadow)
    B.draw_text(d, text, x, ty, L, rgb('#FFFFFF'))
    return img

def logo_big(tagline='SURVIVAL · CLANS · EVENTS'):
    """Grosses MINESMP-Logo: zwei Pillen wie das Server-Logo, darunter ein dunkles Band mit Untertitel."""
    L = 10
    def pill(text, fill, fg):
        tw = text_w(text, L)
        w, h = tw + 4 * L, 7 * L + 4 * L
        w += (-w) % T
        im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.rounded_rectangle([0, 0, w - 1, h - 1], radius=h // 2 - T, fill=rgb(fill))
        d.rounded_rectangle([3 * T, 2 * T, w - 3 * T - 1, h // 2 - T], radius=h // 4, fill=(255, 255, 255, 60))
        B.draw_text(d, text, 2 * L + L // 2, 2 * L + L // 2, L, mix(fill, '#000000', 0.45) if fg == '#FFFFFF' else (0, 0, 0, 0))
        B.draw_text(d, text, 2 * L, 2 * L, L, rgb(fg))
        return im
    a, b = pill('MINE', '#22C3D6', '#FFFFFF'), pill('SMP', '#FFFFFF', '#1B2733')
    gap = 4 * T
    O = 3 * T
    top_w = a.width + gap + b.width
    sub_L = 4
    sw = text_w(tagline, sub_L) + 8 * sub_L
    sh = 7 * sub_L + 6 * sub_L
    W = max(top_w, sw) + 2 * O
    W += (-W) % T
    H = a.height + 2 * O + 2 * T + sh + 2 * O + 2 * T
    H += (-H) % T
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    x0 = (W - top_w) // 2
    x0 -= x0 % T
    # Umriss und Schatten der Pillen
    for dx, dy, col in ((0, 2 * T, (10, 14, 20, 120)), (0, 0, rgb('#0B141C'))):
        d.rounded_rectangle([x0 - O + dx, O - O + dy, x0 + a.width + O - 1 + dx, O + a.height + O - 1 + dy], radius=a.height // 2, fill=col)
        d.rounded_rectangle([x0 + a.width + gap - O + dx, dy, x0 + top_w + O - 1 + dx, O + a.height + O - 1 + dy], radius=a.height // 2, fill=col)
    img.alpha_composite(a, (x0, O))
    img.alpha_composite(b, (x0 + a.width + gap, O))
    # Untertitel-Band
    by = O + a.height + O + 2 * T
    bx = (W - sw) // 2
    bx -= bx % T
    d.rounded_rectangle([bx - T * 2, by, bx + sw + T * 2 - 1, by + sh + 2 * T - 1], radius=sh // 2, fill=rgb('#0B141C'))
    d.rounded_rectangle([bx, by + T, bx + sw - 1, by + sh], radius=sh // 2 - T, fill=rgb('#16303D'))
    B.draw_text(d, tagline, bx + 4 * sub_L, by + T + 3 * sub_L, sub_L, rgb('#9FE9F3'))
    return img

def signs():
    out = {'logo': logo_big()}
    for sid, text, light, dark, ic in [
        ('markt', 'MARKT', '#7CFF8A', '#1E9E4E', 'coin'),
        ('handel', 'HANDEL', '#FFD86B', '#D9731A', 'dollar'),
        ('kisten', 'KISTEN', '#FFE08A', '#C98A0E', 'key'),
        ('fortschritt', 'FORTSCHRITT', '#E59BFF', '#6E44D8', 'star'),
        ('kristallgruft', 'KRISTALLGRUFT', '#C084FC', '#5B2BB5', 'gem'),
        ('clans', 'CLANS', '#9DB8FF', '#3459D9', 'shield'),
        ('events', 'EVENTS', '#FF9CF2', '#8E3FD6', 'bolt'),
        ('portale', 'PORTALE', '#7FF3FF', '#1785A8', 'arrow'),
        ('pvp', 'PVP-ARENA', '#FFB36B', '#C9302F', 'swords'),
        ('kolosseum', 'KOLOSSEUM', '#FF9CF2', '#8E3FD6', 'crown'),
        ('survival', 'SURVIVAL', '#7FF3FF', '#1785A8', 'arrow'),
    ]:
        out[sid] = plaque(text, light, dark, ic)
    return out

def write(OUT, ROOT):
    font_dir = OUT / 'assets/minesmp/font'
    tex_dir = OUT / 'assets/minesmp/textures/font/signs'
    tex_dir.mkdir(parents=True, exist_ok=True)
    font_dir.mkdir(parents=True, exist_ok=True)
    providers, table = [], {}
    cp = FIRST
    for sid, img in signs().items():
        w0, h0 = img.size
        img = img.crop((0, 0, w0 + (-w0) % T, h0 + (-h0) % T)) if (w0 % T or h0 % T) else img
        W, H = img.size
        height = H // T                      # Hoehe in Schrift-Pixeln
        pieces, chars = [], ''
        n = -(-W // 240)
        step = -(-W // n)
        step += (-step) % T
        for i, x in enumerate(range(0, W, step)):
            part = img.crop((x, 0, min(W, x + step), H))
            name = f'{sid}_{i}.png'
            part.save(tex_dir / name)
            providers.append({"type": "bitmap", "file": f"minesmp:font/signs/{name}", "ascent": height - 2, "height": height, "chars": [chr(cp)]})
            chars += (chr(SPACER) if chars else '') + chr(cp)
            cp += 1
        table[sid] = {'text': chars, 'width': W // T, 'height': height}
        if sid == 'logo':
            img.save(ROOT / 'media' / 'schild-logo.png')
    providers.append({"type": "space", "advances": {chr(SPACER): -1}})
    (font_dir / 'signs.json').write_text(json.dumps({"providers": providers}, ensure_ascii=True, indent=1))
    lines = ["# Automatisch erzeugt von tools/build_signs.py - grosse Spawn-Schilder in der Schrift minesmp:signs",
             "# width/height in Schrift-Pixeln (40 = 1 Block bei Groesse 1)"]
    for sid, t in table.items():
        esc = ''.join(f'\\u{ord(c):04X}' for c in t['text'])
        lines.append(f'{sid}: {{text: "{esc}", width: {t["width"]}, height: {t["height"]}}}')
    (ROOT / 'core/src/main/resources/signs.yml').write_text('\n'.join(lines) + '\n')
    # Vorschau aller Schilder
    sheet_imgs = list(signs().items())
    sw = max(i.width for _, i in sheet_imgs) + 40
    sh = sum(i.height + 24 for _, i in sheet_imgs) + 20
    sheet = Image.new('RGBA', (sw, sh), (135, 190, 235, 255))
    y = 20
    for _, i in sheet_imgs:
        sheet.alpha_composite(i, ((sw - i.width) // 2, y)); y += i.height + 24
    sheet.convert('RGB').save(ROOT / 'media' / 'schilder-vorschau.png')
    print(len(table), 'Schilder,', len(providers) - 1, 'Schriftzeichen')
