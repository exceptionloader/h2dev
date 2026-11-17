#!/usr/bin/env python3
"""Goldrausch im Ressourcenpaket: Items, Menue-Symbole und das eigene Menue-Fenster (/goldrausch) in den Serverfarben.

Das Menue ist eine normale 6-Reihen-Kiste. Ihr Fenster wird durch ein Bild in der Titelzeile komplett ueberdeckt:
  Schrift minesmp:gui, ein Zeichen je Seite (176 x 222 Pixel, ascent 13 = buendig mit der Fensteroberkante),
  davor ein Abstand von -8 (der Titel beginnt bei x = 8).
Texte, die sich aendern (Zeit, Betraege, Preise), stehen ebenfalls in der Titelzeile: Abstands-Zeichen schieben den
Schreibkopf an die Stelle, die Schriften minesmp:gr1..gr4 sind die normale Minecraft-Schrift, nach unten versetzt auf
die Kisten-Reihen 1-4. Die Masse dazu stehen in core/src/main/resources/goldrush-gui.yml (vom Plugin gelesen).
Aufruf ueber tools/build_pack.py."""
import json
from PIL import Image, ImageDraw
import build_pack as B
import goldrush_art as A

W, H = 176, 222
NAVY0, NAVY1, NAVY2, NAVY3 = '#050B12', '#0B1620', '#102230', '#17303F'
LINE, LINE_L, SLOT = '#1D3547', '#2A5468', '#081019'
CYAN, CYAN_L, CYAN_D, BLUE = '#22C3D6', '#B6F7FF', '#0E7490', '#4F7CFF'
GOLD, GOLD_L, GOLD_D, GOLD_O = '#FFC83D', '#FFF3B0', '#D98E04', '#6B3F00'
WHITE, DIM = '#FFFFFF', '#6F8797'

# Fehlende Zeichen der 5x7-Pixelschrift (feste Beschriftungen im Bild)
for ch, rows in {
    '0': ["01110", "10001", "10011", "10101", "11001", "10001", "01110"], '1': ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    '2': ["01110", "10001", "00001", "00010", "00100", "01000", "11111"], '3': ["11110", "00001", "00001", "01110", "00001", "00001", "11110"],
    '4': ["00010", "00110", "01010", "10010", "11111", "00010", "00010"], '5': ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    '6': ["00110", "01000", "10000", "11110", "10001", "10001", "01110"], '7': ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    '8': ["01110", "10001", "10001", "01110", "10001", "10001", "01110"], '9': ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    'J': ["00111", "00010", "00010", "00010", "00010", "10010", "01100"], 'Q': ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    'X': ["10001", "10001", "01010", "00100", "01010", "10001", "10001"], 'Z': ["11111", "00001", "00010", "00100", "01000", "10000", "11111"],
    'Ä': ["01010", "00000", "01110", "10001", "11111", "10001", "10001"], 'Ö': ["01010", "00000", "01110", "10001", "10001", "10001", "01110"],
    'Ü': ["01010", "00000", "10001", "10001", "10001", "10001", "01110"],
    '-': ["000", "000", "000", "111", "000", "000", "000"], ':': ["0", "0", "1", "0", "0", "1", "0"], '!': ["1", "1", "1", "1", "1", "0", "1"],
    '+': ["000", "000", "010", "111", "010", "000", "000"], '=': ["000", "000", "111", "000", "111", "000", "000"],
    '/': ["001", "001", "010", "010", "010", "100", "100"], '%': ["11001", "11010", "00010", "00100", "01000", "01011", "10011"],
    '&': ["01100", "10010", "10100", "01000", "10101", "10010", "01101"], '·': ["0", "0", "0", "1", "0", "0", "0"],
    '#': ["01010", "01010", "11111", "01010", "11111", "01010", "01010"], ',': ["00", "00", "00", "00", "00", "01", "10"], '?': ["01110", "10001", "00001", "00010", "00100", "00000", "00100"],
}.items():
    B.F.setdefault(ch, rows)

def c(h, a=255):
    return B.hexc(h, a)

def mixc(a, b, t):
    a, b = c(a), c(b)
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)

class Page:
    """Ein Menue-Bild (176 x 222, Massstab 1:1 wie das Kistenfenster)."""
    def __init__(self):
        self.img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.img)

    def R(self, x0, y0, x1, y1, col):
        self.d.rectangle([x0, y0, x1, y1], fill=c(col) if isinstance(col, str) else col)

    def text(self, s, x, y, col, px=1, shadow=None):
        if shadow:
            B.draw_text(self.d, s, x + px, y + px, px, c(shadow))
        B.draw_text(self.d, s, x, y, px, c(col))

    def text_c(self, s, cx, y, col, px=1, shadow=None):
        self.text(s, cx - B.text_width(s, px) // 2, y, col, px, shadow)

    def paste(self, im, x, y):
        self.img.alpha_composite(im, (x, y))
        self.d = ImageDraw.Draw(self.img)

    def frame(self, x0, y0, x1, y1, fill=NAVY2, edge=LINE, light=LINE_L):
        """Kachel: Flaeche, Rand, feine helle Oberkante."""
        self.R(x0, y0, x1, y1, edge)
        self.R(x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill)
        self.R(x0 + 1, y0 + 1, x1 - 1, y0 + 1, light)

    def slot(self, x, y, edge_dark=NAVY0, edge_light=LINE_L, fill=SLOT):
        """Item-Feld 18x18 mit eingelassenem Rand (x, y = linke obere Ecke der Zelle)."""
        self.R(x, y, x + 17, y + 17, edge_light)
        self.R(x, y, x + 16, y + 16, edge_dark)
        self.R(x + 1, y + 1, x + 16, y + 16, fill)

    def cell(self, col, row):
        return 7 + 18 * col, 17 + 18 * row

    def cslot(self, col, row, **kw):
        x, y = self.cell(col, row)
        self.slot(x, y, **kw)

    def gem(self, cx, cy, col, r=1):
        for k in range(-r, r + 1):
            w = r - abs(k)
            self.R(cx - w, cy + k, cx + w, cy + k, col)

    def finish(self):
        """Ecken wie das Vanilla-Fenster (damit nichts Graues hervorschaut); die letzte Zeile zeichnet Vanilla nicht."""
        px = self.img.load()
        clear = [(0, 0), (1, 0), (0, 1), (173, 0), (174, 0), (175, 0), (174, 1), (175, 1), (175, 2),
                 (0, 218), (0, 219), (1, 219), (0, 220), (1, 220), (2, 220), (175, 219), (174, 220), (175, 220)]
        for x, y in clear:
            px[x, y] = (0, 0, 0, 0)
        for x in range(W):
            px[x, 221] = (0, 0, 0, 0)
        return self.img

MINI = {   # 7x7-Symbole fuer das Feld oben rechts
    'zeit': (["ccccccc", ".cyyyc.", "..cyc..", "...c...", "..c.c..", ".cyyyc.", "ccccccc"], {'c': CYAN, 'y': GOLD}),
    'glueck': ([".gg.gg.", "gggggg.", "ggggggg", ".ggggg.", "ggggggg", "gg.ggg.", "....d.."], {'g': '#22C55E', 'd': '#15803D'}),
    'staub': (["...h...", "..hyh..", "...h...", "..ooo..", ".oyhyo.", "oyyyydo", "ooooooo"], {'o': GOLD_O, 'y': GOLD, 'h': GOLD_L, 'd': GOLD_D}),
    'erz': (["nnnnnnn", "nmmymmn", "nmyymmn", "nmmmmyn", "nymmyyn", "nmmmmmn", "nnnnnnn"], {'n': '#4B5B6A', 'm': '#8FA3B3', 'y': GOLD}),
    'platz': (["c.....c", ".c...c.", "..cyc..", ".oyyyo.", ".oyhyo.", ".oyyyo.", "..ooo.."], {'c': CYAN, 'y': GOLD, 'h': GOLD_L, 'o': GOLD_O}),
    'ticket': ([".......", "ooooooo", "oyyhyyo", ".yhhhy.", "oyyhyyo", "ooooooo", "......."], {'o': GOLD_O, 'y': GOLD, 'h': '#FFFFFF'}),
}

def mini(name):
    rows, pal = MINI[name]
    img = Image.new('RGBA', (7, 7), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in pal:
                img.putpixel((x, y), c(pal[ch]))
    return img

TABS = ['ticket', 'erze', 'funde', 'start', 'shop', 'ziele', 'top']   # Reihenfolge wie im HugoSMP-Menue: Hauptgewinn zuerst
PILL_X = 111       # x der Texte im Feld oben rechts (Titelzeile, y = 6)

def chrome(active, strip_text, strip_col, pill_icon):
    """Rahmen, Kopfleiste, Leiste mit den Seiten-Knoepfen, Spieler-Inventar."""
    p = Page()
    p.R(0, 0, W - 1, 220, NAVY0)
    p.R(1, 1, W - 2, 219, CYAN)
    p.R(2, 2, W - 3, 218, NAVY1)
    p.R(3, 3, W - 4, 3, '#12303D'); p.R(3, 217, W - 4, 217, '#12303D')
    for x, y in ((4, 4), (W - 5, 4), (4, 216), (W - 5, 216)):
        p.gem(x, y, CYAN_L, 1)
    # Kopfleiste (Titelzeile): links fester Schriftzug, rechts ein Feld fuer den wechselnden Text
    p.R(3, 4, W - 4, 15, NAVY2)
    p.R(3, 16, W - 4, 16, CYAN_D)
    p.text(strip_text, 9, 6, strip_col, 1, shadow=NAVY0)
    p.R(98, 4, 170, 14, LINE); p.R(99, 5, 169, 13, NAVY0)
    p.paste(mini(pill_icon), 101, 6)
    # Knopfleiste (Kisten-Reihe 5)
    p.R(3, 106, W - 4, 106, LINE)
    for i in range(9):
        x, y = p.cell(i, 5)
        if i < len(TABS) or i == 8:
            on = i == active
            p.R(x, y, x + 17, y + 17, CYAN if on else LINE)
            p.R(x + 1, y + 1, x + 16, y + 16, CYAN_D if on else NAVY3)
            p.R(x + 1, y + 1, x + 16, y + 1, CYAN_L if on else LINE_L)
            p.R(x + 1, y + 16, x + 16, y + 16, '#07424F' if on else NAVY0)
            if on:                                       # kleiner Zeiger zur Seite hin
                p.R(x + 7, y - 2, x + 10, y - 2, CYAN); p.R(x + 8, y - 3, x + 9, y - 3, CYAN)
    p.gem(142, 116, '#1E4254', 1)
    # Spieler-Inventar
    p.R(3, 125, W - 4, 125, CYAN_D)
    p.R(5, 127, 60, 136, '#7E94A4'); p.R(6, 126, 59, 137, '#7E94A4'); p.R(6, 127, 59, 127, '#A9BCC9')
    for r in range(3):
        for col in range(9):
            p.slot(7 + 18 * col, 138 + 18 * r)
    p.R(7, 193, 168, 193, LINE)
    for col in range(9):
        p.slot(7 + 18 * col, 196, edge_light=CYAN_D)
    return p

def title_band(p, text):
    """Kisten-Reihe 0: grosser Seitentitel."""
    p.R(7, 18, 168, 33, NAVY2); p.R(7, 34, 168, 34, LINE)
    w = B.text_width(text, 2)
    x = 88 - w // 2
    p.text(text, x, 19, WHITE, 2, shadow=CYAN_D)
    for gx, s in ((x - 9, 1), (x + w + 8, -1)):
        p.gem(gx, 26, CYAN, 2); p.gem(gx - 6 * s, 26, CYAN_D, 1)

def gold_text(p, text, x, y, px=2, gap=3):
    """Schriftzug mit Goldverlauf und dunklem Schatten (Buchstabenabstand gap Pixel). Gibt die Breite zurueck."""
    img = gold_word(text, px, gap)
    p.paste(img, x, y)
    return img.width - 3

def gold_word(text, px=2, gap=3, shadow='#3A2200'):
    w = sum(len(B.F[ch][0]) * px + gap for ch in text) - gap
    h = 7 * px
    m = Image.new('L', (w, h), 0)
    md = ImageDraw.Draw(m)
    cx = 0
    for ch in text:
        g = B.F[ch]
        for r, row in enumerate(g):
            for k, v in enumerate(row):
                if v == '1':
                    md.rectangle([cx + k * px, r * px, cx + (k + 1) * px - 1, (r + 1) * px - 1], fill=255)
        cx += len(g[0]) * px + gap
    sh = max(1, px // 2)
    out = Image.new('RGBA', (w + 3 * sh, h + 3 * sh), (0, 0, 0, 0))
    mp, op = m.load(), out.load()
    for yy in range(h):
        for xx in range(w):
            if mp[xx, yy]:
                for dx in range(0, 2 * sh + 1):
                    for dy in range(0, 2 * sh + 1):
                        if dx or dy:
                            op[xx + dx, yy + dy] = c(shadow)
    for yy in range(h):
        for xx in range(w):
            if mp[xx, yy]:
                t = yy / max(1, h - 1)
                op[xx, yy] = mixc(GOLD_L, GOLD, min(1, t * 2.4)) if t < 0.42 else mixc(GOLD, GOLD_D, min(1, (t - 0.42) * 1.8))
    return out

def plate(p, cx, row, half=16):
    """Dunkles Schild fuer einen Wert unter einem Item (Mitte cx, Kisten-Reihe row)."""
    y = 17 + 18 * row
    p.R(cx - half, y + 2, cx + half - 1, y + 15, NAVY2)
    p.R(cx - half, y + 2, cx + half - 1, y + 2, LINE)

def ccx(col):
    return 16 + 18 * col

def page_start():
    p = chrome(3, "DEIN GOLDRAUSCH", CYAN, 'zeit')
    # Banner: Farbverlauf Tuerkis -> Blau, Schriftzug GOLDRAUSCH, Spitzhacke
    for x in range(7, 169):
        p.R(x, 18, x, 33, mixc('#0C5C6E', '#2B4FB5', (x - 7) / 161))
    p.R(7, 18, 168, 18, mixc(CYAN, BLUE, 0.3)); p.R(7, 34, 168, 34, NAVY0)
    for gx, gy in ((164, 21), (160, 30), (166, 28)):
        p.gem(gx, gy, CYAN_L, 1)
    gw = gold_text(p, "GOLDRAUSCH", 11, 19, 2)
    p.paste(A.PICKE, 11 + gw + 7, 18)
    # Sechs Kacheln: Beschriftung oben, darunter Symbol (Item) und Wert
    labels = [("GEWONNEN", GOLD), ("ERZE", CYAN_L), ("FUNDE", GOLD), ("GLÜCK", CYAN_L), ("PLATZ", GOLD), ("HEUTE", CYAN_L)]
    for i, (lab, col) in enumerate(labels):
        x0, y0 = 7 + 54 * (i % 3), 35 + 36 * (i // 3)
        p.frame(x0, y0, x0 + 53, y0 + 35)
        p.R(x0 + 1, y0 + 2, x0 + 52, y0 + 12, NAVY3); p.R(x0 + 1, y0 + 13, x0 + 52, y0 + 13, LINE)
        p.text_c(lab, x0 + 27, y0 + 4, col, 1, shadow=NAVY0)
        p.R(x0 + 1, y0 + 19, x0 + 16, y0 + 34, SLOT)
    return p.finish()

RED_BG, RED_LINE, RED_TXT = '#3A1420', '#E5484D', '#FF9A9A'

FIND_ROWS = [("GOLDNUGGET", "GELD AUFS KONTO"), ("GOLDBARREN", "SEHR VIEL GELD"), ("STAR-TOOL", "NUR FÜR DICH"),
             ("SPAWNER", "ZUFÄLLIGE ART"), ("GOLDTICKET", "DER HAUPTGEWINN")]

def page_funde():
    """Liste "Was du finden kannst": je Reihe Symbol, Name, kurze Erklaerung; rechts steht die Chance (wechselnder Text)."""
    p = chrome(2, "DAS KANNST DU FINDEN", GOLD, 'glueck')
    p.R(98, 4, 170, 14, NAVY2)                      # hier kein Feld oben rechts: der Schriftzug braucht den Platz
    p.R(3, 4, W - 4, 15, NAVY2); p.text("DAS KANNST DU FINDEN", 9, 6, GOLD, 1, shadow=NAVY0)
    for r, (name, desc) in enumerate(FIND_ROWS):
        y0 = 17 + 18 * r
        last = r == len(FIND_ROWS) - 1
        p.cslot(0, r, edge_light=GOLD if last else GOLD_D)
        p.R(25, y0, 168, y0 + 17, GOLD_D if last else LINE)
        p.R(26, y0 + 1, 167, y0 + 16, '#1B2A2E' if last else NAVY2)
        p.R(26, y0 + 1, 167, y0 + 1, GOLD if last else LINE_L)
        p.text(name, 29, y0 + 2, GOLD_L if last else GOLD, 1, shadow=NAVY0)
        p.text(desc, 29, y0 + 10, '#8FA3B3', 1)
        p.R(128, y0 + 3, 165, y0 + 14, NAVY0)       # Feld fuer die Chance
    return p.finish()

def page_erze():
    p = chrome(1, "GOLDRAUSCH", GOLD, 'glueck')
    title_band(p, "ERZE")
    for col in range(9):
        p.cslot(col, 1)
    p.R(7, 54, 168, 68, NAVY2); p.R(7, 54, 168, 54, LINE)
    p.text_c("TIEFENSCHIEFER ZÄHLT GENAUSO", 88, 58, CYAN_L, 1, shadow=NAVY0)
    p.frame(7, 71, 78, 88)
    p.text("DEIN GLÜCK", 11, 76, GOLD, 1, shadow=NAVY0)
    for col in range(4, 9):
        p.cslot(col, 3, edge_light=CYAN_D)
    # Regeln (rot wie eine Warnung)
    p.R(7, 89, 150, 106, RED_LINE); p.R(8, 90, 149, 105, RED_BG)
    p.text("EVENT WIRD MODERIERT", 12, 91, RED_TXT, 1)
    p.text("CHEATS = BANN", 12, 99, '#D0707A', 1)
    p.cslot(8, 4, edge_light=RED_LINE)
    return p.finish()

def page_ticket():
    """Hauptgewinn wie im HugoSMP-Menue aufgebaut: Banner, Gratis-Schluessel je Kiste, Premium und Goldener Name."""
    import math
    p = chrome(0, "MINESMP EVENT", CYAN, 'zeit')
    # Banner ueber zwei Reihen: Strahlen in Tuerkis, goldenes Ticket, Schriftzug
    for y in range(18, 53):
        for x in range(7, 169):
            ang = math.atan2(y - 35, (x - 26) * 0.55)
            ray = int((ang + math.pi) / (math.pi / 9)) % 2
            dist = min(1.0, math.hypot((x - 26) * 0.55, y - 35) / 70)
            p.img.putpixel((x, y), mixc('#0C4656', '#0B1620', dist) if ray else mixc('#0E5A6E', '#0D2030', dist))
    p.d = ImageDraw.Draw(p.img)
    p.R(7, 18, 168, 18, CYAN_D); p.R(7, 53, 168, 53, GOLD_D)
    p.paste(A.TICKET.resize((32, 32), Image.NEAREST), 10, 19)
    for gx, gy in ((9, 23), (43, 22), (45, 47), (10, 47)):
        p.gem(gx, gy, GOLD_L, 1)
    p.text("HAUPTGEWINN", 48, 21, CYAN_L, 1, shadow=NAVY0)
    gold_text(p, "GOLDTICKET", 47, 32, 2, gap=2)
    # Reihe 2+3: Karten "jeden Tag gratis" - eine je Kiste (Name steht im Feld darunter, vom Plugin)
    for i, col in enumerate((1, 3, 5, 7)):
        cx = ccx(col)
        p.R(cx - 17, 54, cx + 16, 88, LINE)
        p.R(cx - 16, 55, cx + 15, 87, NAVY2)
        p.R(cx - 16, 55, cx + 15, 55, GOLD_D)
        p.cslot(col, 2, edge_light=GOLD_D)
        p.R(cx - 15, 74, cx + 14, 86, NAVY0)
    # Reihe 4: zwei Kaesten wie bei HugoSMP - Premium und Goldener Name; ganz rechts dein Ticket
    def box(x0, x1, col, title, sub, edge):
        p.R(x0, 89, x1, 106, edge); p.R(x0 + 1, 90, x1 - 1, 105, NAVY2)
        p.cslot(col, 4, edge_light=edge)
        p.R(x0, 89, x1, 89, edge); p.R(x0, 89, x0, 106, edge); p.R(x1, 89, x1, 106, edge)
        tx = 7 + 18 * col + 20
        p.text(title, tx, 91, edge, 1, shadow=NAVY0)
        p.text(sub, tx, 99, '#8FA3B3', 1)
    box(7, 78, 0, "PREMIUM", "30 TAGE", CYAN)
    box(79, 150, 4, "GOLDNAME", "+ AURA", GOLD)
    p.cslot(8, 4, edge_light=GOLD)
    return p.finish()

def page_shop():
    p = chrome(4, "GOLDRAUSCH", GOLD, 'staub')
    title_band(p, "STAUB-SHOP")
    for col in (1, 3, 5, 7):
        p.cslot(col, 1, edge_light=GOLD_D)
        plate(p, ccx(col), 2, 17)
    for col in (2, 4, 6):
        p.cslot(col, 3, edge_light=GOLD_D)
        plate(p, ccx(col), 4, 17)
    return p.finish()

def page_ziele():
    p = chrome(5, "GOLDRAUSCH", GOLD, 'erz')
    title_band(p, "MEILENSTEINE")
    p.R(25, 43, 150, 45, LINE); p.R(25, 44, 150, 44, CYAN_D)       # Weg zwischen den Stufen
    for i, col in enumerate(range(1, 8)):
        p.cslot(col, 1, edge_light=CYAN_D)
        plate(p, ccx(col), 2, 8)
        p.text_c(str(i + 1), ccx(col), 17 + 18 * 2 + 5, CYAN_L, 1, shadow=NAVY0)
    p.frame(7, 71, 168, 105)
    p.text_c("DEIN FORTSCHRITT", 88, 75, CYAN_L, 1, shadow=NAVY0)
    p.R(11, 85, 164, 91, NAVY0)                                     # Bett fuer den Balken
    return p.finish()

def page_top():
    p = chrome(6, "GOLDRAUSCH", GOLD, 'platz')
    title_band(p, "RANGLISTE")
    # Siegertreppe: 2 - 1 - 3. Der Betrag steht jeweils im dunklen Feld (Kisten-Reihe 3).
    def pedestal(cx, y0, light, mid, dark):
        p.R(cx - 25, y0, cx + 24, 88, dark); p.R(cx - 24, y0 + 1, cx + 23, 88, mid)
        p.R(cx - 24, y0 + 1, cx + 23, y0 + 1, light); p.R(cx - 24, y0 + 2, cx - 24, 88, light)
        p.R(cx - 20, 73, cx + 19, 86, dark); p.R(cx - 19, 74, cx + 18, 85, NAVY0)
    pedestal(88, 53, '#FFF3B0', '#D9A520', '#6B3F00')
    pedestal(34, 71, '#FFFFFF', '#9FAEBB', '#3A4652')
    pedestal(142, 71, '#F6C89A', '#B8733A', '#4A2408')
    p.text_c("1", 89, 57, GOLD_O, 2); p.text_c("1", 88, 56, GOLD_L, 2)
    p.text_c("2", 35, 38, '#3A4652', 2); p.text_c("2", 34, 37, '#DCE6EE', 2)
    p.text_c("3", 143, 38, '#4A2408', 2); p.text_c("3", 142, 37, '#E0A06A', 2)
    for col, row in ((4, 1), (1, 2), (7, 2)):
        p.cslot(col, row, edge_light=GOLD_D)
    p.gem(88 - 16, 43, GOLD, 2); p.gem(88 + 15, 43, GOLD, 2)
    for col in range(1, 8):
        p.cslot(col, 4)
    return p.finish()

PAGES = {'start': page_start, 'funde': page_funde, 'erze': page_erze, 'ticket': page_ticket, 'shop': page_shop, 'ziele': page_ziele, 'top': page_top}

# ---------------------------------------------------------------- Balken (Meilensteine)
BAR_Y, BAR_X, BAR_N = 86, 12, 38       # 38 Stuecke je 3 Pixel + 1 Pixel Luecke = 152 Pixel

def bar_piece(on):
    img = Image.new('RGBA', (3, 5), c(GOLD if on else '#16303F'))
    if on:
        for x in range(3):
            img.putpixel((x, 0), c(GOLD_L)); img.putpixel((x, 4), c(GOLD_D))
    return img

# ---------------------------------------------------------------- Helm (getragen)
def helmet_layer():
    """64x32 Ruestungs-Bild: nur der Kopf - gelber Grubenhelm mit Lampe und tuerkisem Band."""
    img = Image.new('RGBA', (64, 32), (0, 0, 0, 0))
    px = img.load()
    y_, h_, d_, o_ = c(GOLD), c(GOLD_L), c(GOLD_D), c(GOLD_O)
    def face(x0, top_rows=5):
        for x in range(8):
            for y in range(top_rows):
                px[x0 + x, 8 + y] = y_ if y < top_rows - 1 else c(CYAN_D)
            px[x0 + x, 8] = h_ if x < 5 else y_
    for x0 in (0, 8, 16, 24):
        face(x0)
    for x in range(8, 16):                      # Oberseite
        for y in range(8):
            px[x, y] = y_
    for y in range(8):
        px[11, y] = h_; px[12, y] = h_
    for x in range(8, 16):
        px[x, 0] = d_; px[x, 7] = d_
    for y in range(8):
        px[8, y] = d_; px[15, y] = d_
    for x in range(10, 14):                     # Lampe vorne
        for y in range(9, 12):
            px[x, y] = c('#1F2A35')
    for x, y, col in ((11, 10, CYAN_L), (12, 10, CYAN), (11, 9, CYAN), (12, 9, CYAN_L)):
        px[x, y] = c(col)
    for x in range(8, 16):                      # Schirm vorne
        px[x, 13] = o_
    return img

# ---------------------------------------------------------------- Schrift-Dateien
ASCII_ROWS = ["\u0000" * 16, "\u0000" * 16, "\u0000!\"#$%&'()*+,-./", "0123456789:;<=>?", "@ABCDEFGHIJKLMNO", "PQRSTUVWXYZ[\\]^_",
              "`abcdefghijklmno", "pqrstuvwxyz{|}~\u0000"] + ["\u0000" * 16] * 8
ROW_Y = {0: 22, 1: 40, 2: 58, 3: 76, 4: 94}   # Oberkante der Schrift in den Kisten-Reihen 0-4 (Fenster-Koordinaten)
TITLE_Y = 6                                 # Oberkante der Titelzeile
SHIFTS = [1, 2, 4, 8, 16, 32, 64, 128]
CP_PAGE, CP_BAR, CP_SHIFT = 0xE300, 0xE310, 0xE320

def write(out, root):
    """Schreibt alle Goldrausch-Dateien ins Ressourcenpaket (out) und die Masse fuers Plugin."""
    tex_item = out / "assets/minesmp/textures/item"; tex_font = out / "assets/minesmp/textures/font"
    for d in (tex_item, tex_font, out / "assets/minesmp/models/item", out / "assets/minesmp/items", out / "assets/minesmp/font",
              out / "assets/minesmp/equipment", out / "assets/minesmp/textures/entity/equipment/humanoid"):
        d.mkdir(parents=True, exist_ok=True)
    sprites = dict(A.ITEMS); sprites.update(A.ICONS)
    sprites['gr_leer'] = Image.new('RGBA', (16, 16), (0, 0, 0, 0))            # unsichtbares Feld (nur Hinweistext)
    for name, img in sprites.items():
        img.save(tex_item / f"{name}.png")
        parent = "minecraft:item/handheld" if name in A.HANDHELD else "minecraft:item/generated"
        (out / f"assets/minesmp/models/item/{name}.json").write_text(json.dumps({"parent": parent, "textures": {"layer0": f"minesmp:item/{name}"}}, indent=1))
        (out / f"assets/minesmp/items/{name}.json").write_text(json.dumps({"model": {"type": "minecraft:model", "model": f"minesmp:item/{name}"}}, indent=1))
    helmet_layer().save(out / "assets/minesmp/textures/entity/equipment/humanoid/grubenhelm.png")
    (out / "assets/minesmp/equipment/grubenhelm.json").write_text(json.dumps({"layers": {"humanoid": [{"texture": "minesmp:grubenhelm"}]}}, indent=1))

    providers, pages = [], {}
    for i, (name, fn) in enumerate(PAGES.items()):
        fn().save(tex_font / f"gui_gr_{name}.png")
        pages[name] = CP_PAGE + i
        providers.append({"type": "bitmap", "file": f"minesmp:font/gui_gr_{name}.png", "ascent": 13, "height": H, "chars": [chr(CP_PAGE + i)]})
    for i, on in enumerate((True, False)):
        bar_piece(on).save(tex_font / f"gui_gr_bar{i}.png")
        providers.append({"type": "bitmap", "file": f"minesmp:font/gui_gr_bar{i}.png", "ascent": 13 - BAR_Y, "height": 5, "chars": [chr(CP_BAR + i)]})
    adv = {}
    for i, n in enumerate(SHIFTS):
        adv[chr(CP_SHIFT + i)] = -n
        adv[chr(CP_SHIFT + 16 + i)] = n
    providers.append({"type": "space", "advances": adv})
    (out / "assets/minesmp/font/gui.json").write_text(json.dumps({"providers": providers}, ensure_ascii=True, indent=1))
    for row, y in ROW_Y.items():
        (out / f"assets/minesmp/font/gr{row}.json").write_text(json.dumps({"providers": [
            {"type": "space", "advances": {" ": 4}},
            {"type": "bitmap", "file": "minecraft:font/ascii.png", "ascent": 7 - (y - TITLE_Y), "chars": ASCII_ROWS}]}, ensure_ascii=True, indent=1))

    def esc(cp):
        return '"\\u%04X"' % cp
    y = ["# Automatisch erzeugt von tools/build_goldrush.py - Masse des Goldrausch-Menues (nicht aendern)",
         "font: minesmp:gui", "width: %d" % W, "pill-x: %d" % PILL_X, "pages:"]
    y += [f"  {n}: {esc(cp)}" for n, cp in pages.items()]
    y += ["bar: {on: %s, off: %s, x: %d, pieces: %d, step: 4}" % (esc(CP_BAR), esc(CP_BAR + 1), BAR_X, BAR_N), "shift-back:"]
    y += [f"  {n}: {esc(CP_SHIFT + i)}" for i, n in enumerate(SHIFTS)]
    y += ["shift-forward:"] + [f"  {n}: {esc(CP_SHIFT + 16 + i)}" for i, n in enumerate(SHIFTS)]
    y += ["row-fonts: {" + ", ".join(f"{r}: 'minesmp:gr{r}'" for r in ROW_Y) + "}"]
    (root / "core/src/main/resources/goldrush-gui.yml").write_text("\n".join(y) + "\n")
    return sprites
