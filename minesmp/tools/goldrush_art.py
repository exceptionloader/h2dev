#!/usr/bin/env python3
"""Pixel-Grafiken fuer den Goldrausch: Items (16x16), Menue-Symbole und kleine Zeichen fuer Chat/Leiste.
Eigene Entwuerfe in den Serverfarben (Tuerkis/Blau) mit Gold. Genutzt von tools/build_goldrush.py."""
from PIL import Image

def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)

def sprite(rows, pal, size=16):
    assert len(rows) == size, (len(rows), rows)
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        assert len(row) == size, (y, len(row), row)
        for x, ch in enumerate(row):
            if ch in pal:
                img.putpixel((x, y), hexc(pal[ch]))
            else:
                assert ch == '.', (ch, y, x)
    return img

# ---------------------------------------------------------------- Farben
GOLD = {'o': '#6B3F00', 'y': '#FFC83D', 'h': '#FFF3B0', 'd': '#D98E04', 'e': '#A86400'}
CYAN = {'c': '#22C3D6', 'C': '#B6F7FF', 'k': '#0E7490', 'K': '#07424F'}
WOOD = {'s': '#8B5A2B', 'w': '#B9824A', 'b': '#4A2E14'}
STEEL = {'m': '#8FA3B3', 'M': '#DCE6EE', 'n': '#4B5B6A', 'N': '#1F2A35'}
RED = {'r': '#E5484D', 'R': '#FF8A8A', 'q': '#9B1C24', 'Q': '#5A0E14'}

def pal(*ps, **extra):
    out = {}
    for p in ps:
        out.update(p)
    out.update(extra)
    return out

# ---------------------------------------------------------------- Items
PICKE = sprite([
    "................",
    ".....oooooo.....",
    "....ohhyyyyoo...",
    "....oooodyyyyo..",
    ".......KKodyyyo.",
    "......KcCKodyyo.",
    ".....bsKKb.odyo.",
    "....bswb...oyyo.",
    "...bswb....oydo.",
    "..bswb.....oydo.",
    ".bswb......oyo..",
    "bswb.......oo...",
    "bsb.............",
    ".b..............",
    "................",
    "................"], pal(GOLD, CYAN, WOOD))

HELM = sprite([
    "................",
    "................",
    ".....oooooo.....",
    "...oohhhyyyoo...",
    "..ohhyyyyyyydo..",
    ".ohyyyNNNNyyydo.",
    ".oyyyNCCccNyydo.",
    "oyyyyNCcckNyyydo",
    "oyyyyyNNNNyyyydo",
    "oyyyyyyyyyyyyydo",
    "okkkkkkkkkkkkkko",
    "oooooooooooooooo",
    "ohyyyyyyyyyyyydo",
    ".oooooooooooooo.",
    "................",
    "................"], pal(GOLD, CYAN, STEEL))

DYNAMIT = sprite([
    "................",
    "........h.......",
    ".......hyh......",
    "........h.......",
    "........b.......",
    ".......b........",
    "..QQQ.QbQ.QQQ...",
    ".QRrqQRrqQRrqQ..",
    ".QRrqQRrqQRrqQ..",
    ".QwwwwwwwwwwwQ..",
    ".QsssssssssssQ..",
    ".QRrqQRrqQRrqQ..",
    ".QRrqQRrqQRrqQ..",
    ".QRrqQRrqQRrqQ..",
    ".QrqqQrqqQrqqQ..",
    "..QQQ.QQQ.QQQ..."], pal(RED, WOOD, GOLD))

PFANNE = sprite([
    "................",
    "................",
    "................",
    "....NNNNNNNN....",
    "..NNmmmmmmmmNN..",
    ".NmmNNNNNNNNmmN.",
    "NmMNkccccckkNmnN",
    "NmMNcCcyhcckNmnN",
    "NmmNcchyyyckNmnN",
    "NnmmNcydyckNmnnN",
    ".NnmmNNNNNNmnnN.",
    "..NNnnmmmmnnNN..",
    "....NNNNNNNN....",
    "................",
    "................",
    "................"], pal(STEEL, CYAN, GOLD))

RUTE = sprite([
    ".KK..........KK.",
    "KCcK........KcCK",
    "KccK........KccK",
    ".KbsK......Ksb..",
    "..bswb....bwsb..",
    "...bswb..bwsb...",
    "....bswbbwsb....",
    ".....bswwsb.....",
    "......bswb......",
    "......bswb......",
    "......bswb......",
    "......bswb......",
    "......bswb......",
    "......bssb......",
    ".......bb.......",
    "................"], pal(WOOD, CYAN))

BARREN = sprite([
    "................",
    "................",
    "................",
    "....oooooooooo..",
    "...ohhhhhhhhyo..",
    "..ohhyyyyyyyydo.",
    ".ohyyyyyyyyyyydo",
    "oooooooooooooodo",
    "oyyyyyyyyyyyyodo",
    "oyhyyKccKyyydodo",
    "oyyyyKCcKyyydoo.",
    "oyddddddddddoo..",
    "oooooooooooo....",
    "................",
    "................",
    "................"], pal(GOLD, CYAN))

NUGGET = sprite([
    "................",
    "................",
    "......ooo.......",
    ".....ohhyoo.....",
    "....ohhyyyyo....",
    "...ohyyyyydyoo..",
    "..ohyyyhyyyyydo.",
    "..oyyyhhyyydydo.",
    ".oyyyyyyyyyyddo.",
    ".oydyyyyyhyydeo.",
    ".oyyyydyyyyddeo.",
    "..oddyyyyddeeo..",
    "...ooddddeeoo...",
    ".....oooooo.....",
    "................",
    "................"], pal(GOLD))

ELIXIER = sprite([
    "................",
    "......bbbb......",
    "......bswb......",
    ".....NNNNNN.....",
    "......NMmN......",
    "......NMmN......",
    ".....NMmmmN.....",
    "....NMmmmmmN....",
    "...NMhhyyyymN...",
    "..NMhyyyyyydmN..",
    "..NhyyhyyyyydN..",
    "..NyyhyyyyyddN..",
    "..NyyyyyyydddN..",
    "...NyyyyydddN...",
    "....NNNNNNNN....",
    "................"], pal(STEEL, GOLD, WOOD))

HUFEISEN = sprite([
    "................",
    "....oooooooo....",
    "..oohhhyyyyyoo..",
    ".ohhyyyyyyyyydo.",
    ".ohyyoooooooydo.",
    "ohyKo......oKydo",
    "ohyyo......oyydo",
    "oyyyo......oyydo",
    "oyKyo......oyKdo",
    "oyyyo......oyydo",
    "oyydo......oyddo",
    "oyKdo......oyKdo",
    "oyddo......odddo",
    "ooooo......ooooo",
    "................",
    "................"], pal(GOLD, CYAN))

def pokal(metal):
    return sprite([
        "................",
        "..oooooooooooo..",
        ".oohhyyyyyyydoo.",
        "oyoohyyyyyydooyo",
        "oyo.oyyKKyydo.yo",
        "oyo.oyKCcKydo.yo",
        ".oyooyyKKydoodo.",
        "..oooyyyyydooo..",
        "....oyyyyydo....",
        ".....oyyydo.....",
        "......oydo......",
        "......oydo......",
        "....ooyyddoo....",
        "...ohyyyyyddo...",
        "...oooooooooo...",
        "................"], pal(metal, CYAN))

def ticket(p):
    """Ticket mit Stern, Kerben an den Seiten und Abrisskante."""
    return sprite([
        "................",
        "................",
        "................",
        ".oooooooooooooo.",
        "ohhhhhhhhhhhohho",
        "oyyyyyWyyyyydyyo",
        "oyyyyWWWyyyyoydo",
        ".oyyWWWWWyyydyo.",
        ".oyyyWWWyyyyoyo.",
        "oyyyyWyWyyyydydo",
        "oyyyyyyyyyyyoydo",
        "oddddddddddddddo",
        ".oooooooooooooo.",
        "................",
        "................",
        "................"], p)

TICKET = ticket(pal(GOLD, W='#FFFFFF'))
GUTSCHEIN = ticket({'o': '#07424F', 'y': '#22C3D6', 'h': '#B6F7FF', 'd': '#0E7490', 'W': '#FFF3B0'})

STAUB = sprite([
    "................",
    "................",
    ".....h..........",
    "....hyh....h....",
    ".....h....hyh...",
    "...........h....",
    "......oo........",
    ".....oyyo..oo...",
    "....oyhyyooyyo..",
    "...oyhyyyyyhyyo.",
    "..oyyyyhyyyyyydo",
    ".oyhyyyyyyhyyydo",
    ".oyyyydyyyyyyddo",
    "oyydyyyyydyydddo",
    "oooooooooooooooo",
    "................"], pal(GOLD))

SILBER = {'o': '#3A4652', 'y': '#C9D4DD', 'h': '#FFFFFF', 'd': '#8C9AA7', 'e': '#5E6C79'}
BRONZE = {'o': '#4A2408', 'y': '#D08A4C', 'h': '#F6C89A', 'd': '#9C5A26', 'e': '#6E3C14'}

ITEMS = {
    'gr_picke': PICKE, 'gr_helm': HELM, 'gr_dynamit': DYNAMIT, 'gr_pfanne': PFANNE, 'gr_rute': RUTE,
    'gr_barren': BARREN, 'gr_nugget': NUGGET, 'gr_elixier': ELIXIER, 'gr_hufeisen': HUFEISEN,
    'gr_pokal_gold': pokal(GOLD), 'gr_pokal_silber': pokal(SILBER), 'gr_pokal_bronze': pokal(BRONZE),
    'gr_ticket': TICKET, 'gr_gutschein': GUTSCHEIN, 'gr_staub': STAUB,
}
HANDHELD = {'gr_picke', 'gr_rute'}

# ---------------------------------------------------------------- Menue-Symbole (16x16)
IC_ZEIT = sprite([
    "................",
    "..KKKKKKKKKKKK..",
    "..KCCCCCCCCCCK..",
    "...KcccccccCK...",
    "...KcyyyyyycK...",
    "....KcyyyycK....",
    ".....KcyycK.....",
    "......KyyK......",
    "......KyyK......",
    ".....Kc..cK.....",
    "....Kc.yy.cK....",
    "...Kc.yyyy.cK...",
    "...KcyyyyyycK...",
    "..KCCCCCCCCCCK..",
    "..KKKKKKKKKKKK..",
    "................"], pal(CYAN, GOLD))

IC_BONUS = sprite([
    "................",
    ".........ooo....",
    "........ohyo....",
    ".......ohyyo....",
    "......ohyyo.....",
    ".....ohyyyo.....",
    "....ohyyyyoooo..",
    "...ohyyyyyyyyo..",
    "...oooooyyyydo..",
    ".......oyyydo...",
    "......oyyydo....",
    "......oyydo.....",
    ".....oyydo......",
    ".....oydo.......",
    ".....ooo........",
    "................"], pal(GOLD))

IC_GELD = sprite([
    "................",
    "................",
    ".....oooooo.....",
    "...oohhhyyyoo...",
    "..ohhyyyyyyydo..",
    "..oyyyoyyoyydo..",
    "..oyyooooooydo..",
    "..oyyyoyyoyydo..",
    "..oyyooooooydo..",
    "..oyyyoyyoyydo..",
    "..oydyyyyyyddo..",
    "...oodddddeoo...",
    ".....oooooo.....",
    "................",
    "................",
    "................"], pal(GOLD))

IC_ERZE = sprite([
    "................",
    ".NNNNNNNNNNNNNN.",
    "NMmmmmmmmmmmmmnN",
    "NmmmoommmmmmmmnN",
    "NmmohyommmoommnN",
    "NmmoyyommohyomnN",
    "NmmmoommmoydomnN",
    "NmmmmmmmmmoomnnN",
    "NmmmmmoommmmmmnN",
    "NmoommohyommmnnN",
    "NmohyomoydommmnN",
    "NmoydommoommmnnN",
    "NmmoommmmmmmnnnN",
    "NmnnnnnnnnnnnnnN",
    ".NNNNNNNNNNNNNN.",
    "................"], pal(STEEL, GOLD))

IC_PLATZ = sprite([
    "................",
    ".KKKK......KKKK.",
    ".KCcK......KcCK.",
    "..KccK....KccK..",
    "..KcckK..KcckK..",
    "...KccKooKccK...",
    "...KKKohhoKKK...",
    ".....ohyyydo....",
    "....ohyyoyydo...",
    "....oyyooyydo...",
    "....oyyyoyydo...",
    "....oyyyoyydo...",
    "....oyyooodeo...",
    ".....oydddeo....",
    "......ooooo.....",
    "................"], pal(CYAN, GOLD))

IC_SERVER = sprite([
    "................",
    ".....KKKKKK.....",
    "...KKCCcccKKK...",
    "..KCCcKcccKccK..",
    ".KCccKcccccKccK.",
    ".KccKKKKKKKKKkK.",
    "KCccKcccccccKckK",
    "KcccKcccccccKckK",
    "KKKKKKKKKKKKKKKK",
    "KcccKcccccccKckK",
    ".KccKKKKKKKKKkK.",
    ".KcckKcccccKkkK.",
    "..KckkKcccKkkK..",
    "...KKkkkkkkKK...",
    ".....KKKKKK.....",
    "................"], pal(CYAN))

IC_SHOP = sprite([
    "................",
    "......bbbb......",
    ".....bswwsb.....",
    "....bsb..bsb....",
    "...oooooooooo...",
    "..ohhyyyyyyydo..",
    "..oyyyyyyyyydo..",
    ".ohyyyKKKKyyydo.",
    ".oyyyKCccKyyydo.",
    ".oyyyKcckKyyydo.",
    ".oyyyyKKKKyyddo.",
    ".oyyyyyyyyyyddo.",
    ".oydyyyyyyydddo.",
    "..oddddddddddo..",
    "...oooooooooo...",
    "................"], pal(GOLD, CYAN, WOOD))

IC_ZIEL = sprite([
    "................",
    "..bb............",
    "..bsKKKKKKKKK...",
    "..bsKCCccccckK..",
    "..bsKCccccckK...",
    "..bsKcccccckK...",
    "..bsKccccckkK...",
    "..bsKccccckkkK..",
    "..bsKKKKKKKKKK..",
    "..bs............",
    "..bs............",
    "..bs............",
    "..bs............",
    ".obsoo..........",
    "oyyyydo.........",
    "ooooooo........."], pal(CYAN, WOOD, GOLD))

IC_TOP = sprite([
    "................",
    "................",
    ".o....oo....o...",
    "oyo..oyyo..oyo..",
    "oyyo.oyyo.oyyo..",
    "oyyyoyyyyoyyyo..",
    "ohyyyyyyyyyydo..",
    "ohyKyyKCyyKydo..",
    "oyyyyyKcyyyydo..",
    "oyyyyyyyyyyddo..",
    "oooooooooooooo..",
    "oyhyyyyyyyyydo..",
    "oooooooooooooo..",
    "................",
    "................",
    "................"], pal(GOLD, CYAN))

IC_X = sprite([
    "................",
    "................",
    "..QQ........QQ..",
    ".QRrQ......QRrQ.",
    ".QrrrQ....QrrrQ.",
    "..QrrrQ..QrrrQ..",
    "...QrrrQQrrrQ...",
    "....QrrrrrrQ....",
    ".....QrrrrQ.....",
    "....QrrrrrrQ....",
    "...QrrrQQrrqQ...",
    "..QrrrQ..QrqqQ..",
    ".QrrrQ....QqqqQ.",
    ".QrqQ......QqqQ.",
    "..QQ........QQ..",
    "................"], pal(RED))

IC_INFO = sprite([
    "................",
    ".....KKKKKK.....",
    "...KKCCcccKKK...",
    "..KCCccccccckK..",
    ".KCcccMMMcccckK.",
    ".KccccMMMcccckK.",
    "KCcccccccccccckK",
    "KccccMMMMccccckK",
    "KcccccMMMccccckK",
    "KcccccMMMccccckK",
    ".KccccMMMcccckK.",
    ".KcccMMMMMccckK.",
    "..KcccccccccKK..",
    "...KKkkkkkkKK...",
    ".....KKKKKK.....",
    "................"], pal(CYAN, STEEL))

IC_HAKEN = sprite([
    "................",
    "................",
    "............GG..",
    "...........GgLG.",
    "..........GgLgG.",
    ".........GgLgG..",
    ".GG.....GgLgG...",
    "GgLG...GgLgG....",
    "GgLgG.GgLgG.....",
    ".GgLgGgLgG......",
    "..GgLgLgG.......",
    "...GgLgG........",
    "....GgG.........",
    ".....G..........",
    "................",
    "................"], {'G': '#14532D', 'g': '#22C55E', 'L': '#86EFAC'})

IC_SCHLOSS = sprite([
    "................",
    ".....NNNNNN.....",
    "....NmmmmmmN....",
    "...NmmNNNNmmN...",
    "...NmN....NmN...",
    "...NmN....NmN...",
    "..NNNNNNNNNNNN..",
    ".NMmmmmmmmmmmnN.",
    ".NmmmmNNNmmmmnN.",
    ".NmmmmNNNmmmmnN.",
    ".NmmmmmNmmmmmnN.",
    ".NmmmmmNmmmmnnN.",
    ".NmnnnnnnnnnnnN.",
    "..NNNNNNNNNNNN..",
    "................",
    "................"], pal(STEEL))

IC_FIEBER = sprite([
    "................",
    ".......q........",
    "......qrq.......",
    "......qrrq......",
    ".....qrrrq..q...",
    "....qrryrrqqrq..",
    "...qrryyrrrrrq..",
    "...qryyyyrryrq..",
    "..qrryhyyyyyrrq.",
    "..qryyhhyyhyyrq.",
    "..qryhhhhhhyyrq.",
    "..qrryhhhhyyrrq.",
    "...qrryhhyyrrq..",
    "....qqryyrrqq...",
    "......qqqqq.....",
    "................"], pal(RED, GOLD))

GREEN = {'G': '#14532D', 'g': '#22C55E', 'L': '#86EFAC', 'D': '#15803D'}
IC_GLUECK = sprite([
    "................",
    "...GGG...GGG....",
    "..GLggG.GggLG...",
    "..GgggGGGgggG...",
    "..GggggGggggG...",
    "...GGggGggGG....",
    "..GGGGGgGGGGG...",
    ".GLggGgggGggLG..",
    ".GgggggGgggggG..",
    ".GggDGGGGGDggG..",
    "..GGG.GgG.GGG...",
    "......GgG.......",
    ".......GgG......",
    "........GgG.....",
    ".........GG.....",
    "................"], GREEN)

IC_HEUTE = sprite([
    "................",
    ".......o........",
    "..o....y....o...",
    "...y.......y....",
    ".....ooooo......",
    "....ohhyyyo.....",
    "...ohyyyyydo....",
    "oy.oyyyyyydo.yo.",
    "...oyyyyyydo....",
    "...oyyyyyddo....",
    "....oyydddo.....",
    ".....ooooo......",
    "...y.......y....",
    "..o....y....o...",
    ".......o........",
    "................"], pal(GOLD))

ICONS = {
    'gr_ic_zeit': IC_ZEIT, 'gr_ic_bonus': IC_BONUS, 'gr_ic_geld': IC_GELD, 'gr_ic_erze': IC_ERZE, 'gr_ic_platz': IC_PLATZ,
    'gr_ic_server': IC_SERVER, 'gr_ic_shop': IC_SHOP, 'gr_ic_ziel': IC_ZIEL, 'gr_ic_top': IC_TOP, 'gr_ic_x': IC_X,
    'gr_ic_info': IC_INFO, 'gr_ic_haken': IC_HAKEN, 'gr_ic_schloss': IC_SCHLOSS, 'gr_ic_fieber': IC_FIEBER,
    'gr_ic_glueck': IC_GLUECK, 'gr_ic_heute': IC_HEUTE,
}

def sheet(scale=12, cols=7):
    """Uebersichtsbild aller Grafiken (zum Pruefen)."""
    names = list(ITEMS) + list(ICONS)
    rows = (len(names) + cols - 1) // cols
    cell = 16 * scale + 16
    img = Image.new('RGBA', (cols * cell, rows * cell), hexc('#0B1620'))
    for i, n in enumerate(names):
        im = (ITEMS.get(n) or ICONS.get(n)).resize((16 * scale, 16 * scale), Image.NEAREST)
        img.alpha_composite(im, ((i % cols) * cell + 8, (i // cols) * cell + 8))
    return img

if __name__ == '__main__':
    import sys
    sheet().save(sys.argv[1])
