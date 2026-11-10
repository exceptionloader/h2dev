#!/usr/bin/env python3
"""Erzeugt den MineSMP-Spawn als Bauplan fuer das Plugin (eigenes Design).

Ausgabe:
  core/src/main/resources/spawn/blueprint.txt.gz  Bloecke (Palette + Zeilen "id y z x1 x2")
  core/src/main/resources/spawn/layout.yml        NPCs, Hologramme, Portale, Kisten, Arenen, Navigator
  resourcepack/../media/spawn-plan.png            Draufsicht zur Kontrolle

Koordinaten: Welt "spawn", Mitte (0, 100, 0). Boden-Oberkante y=100, Spieler stehen auf y=101.
  Norden (z-): Kistenhalle      Osten (x+): Markt            Westen (x-): Fortschritt (Battlepass, Quests ...)
  Nordost:     Clanhalle        Nordwest:  Event-Platz       Sueden (z+): Portale
  Arena-Insel bei z=150 (PvP, davor der PvP-Platz), Duell-Inseln bei x=+-64, z=150, Sumo-Plattformen bei x=+-34, z=92
  Clan-Kolosseum (Clan-Liga) auf eigener Insel im Osten (x=112), Bruecke vom Markt
  Kristallgruft (PvE): Tor im Suedwesten, die Grueften selbst liegen weit westlich (x=-260) unter y=100
  Ueber dem Brunnen: Kristall-Monument, im Norden schwebt der Schriftzug MINESMP, rundherum kleine Inseln
"""
import gzip, json, math, pathlib, random

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "core/src/main/resources/spawn"
VERSION = 3
rng = random.Random(1907)

W = {}  # (x,y,z) -> Blockdaten


def put(x, y, z, b):
    W[(x, y, z)] = b


def get(x, y, z):
    return W.get((x, y, z))


def box(x1, y1, z1, x2, y2, z2, b):
    for x in range(min(x1, x2), max(x1, x2) + 1):
        for y in range(min(y1, y2), max(y1, y2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                put(x, y, z, b)


def clear(x1, y1, z1, x2, y2, z2):
    for x in range(min(x1, x2), max(x1, x2) + 1):
        for y in range(min(y1, y2), max(y1, y2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                W.pop((x, y, z), None)


def disc(cx, cz, r, y, b):
    ri = int(math.ceil(r))
    for x in range(cx - ri, cx + ri + 1):
        for z in range(cz - ri, cz + ri + 1):
            if (x - cx) ** 2 + (z - cz) ** 2 <= r * r:
                put(x, y, z, b)


# ---------------------------------------------------------------- Rauschen
class Noise2:
    def __init__(self, seed, scale):
        self.r = random.Random(seed); self.s = scale; self.c = {}

    def v(self, ix, iz):
        k = (ix, iz)
        if k not in self.c:
            self.c[k] = random.Random(hash((ix, iz, self.r.random())) & 0xffffffff).random() * 2 - 1
        return self.c[k]

    def __call__(self, x, z):
        fx, fz = x / self.s, z / self.s
        ix, iz = math.floor(fx), math.floor(fz)
        tx, tz = fx - ix, fz - iz
        tx, tz = tx * tx * (3 - 2 * tx), tz * tz * (3 - 2 * tz)
        a = self.v(ix, iz) * (1 - tx) + self.v(ix + 1, iz) * tx
        b = self.v(ix, iz + 1) * (1 - tx) + self.v(ix + 1, iz + 1) * tx
        return a * (1 - tz) + b * tz


edge_n = Noise2(11, 9.0)
depth_n = Noise2(12, 7.0)
rock_n = Noise2(13, 6.0)
flower_n = Noise2(14, 5.0)


def island(cx, cz, R, maxdepth, top=100, wobble=4.0):
    """Schwebende Insel: Gras oben, Erde, Stein, tropfsteinartige Unterseite. Gibt Menge der Oberflaechen-Spalten zurueck."""
    cols = set()
    for x in range(cx - int(R) - 6, cx + int(R) + 7):
        for z in range(cz - int(R) - 6, cz + int(R) + 7):
            dx, dz = x - cx, z - cz
            d = math.hypot(dx, dz)
            rr = R + wobble * edge_n(x * 1.0, z * 1.0)
            if d > rr:
                continue
            t = d / rr
            depth = 3 + maxdepth * (1 - t ** 1.7) ** 1.25 + 3 * depth_n(x, z)
            depth = max(2, int(depth))
            bottom = top - depth
            for y in range(bottom, top + 1):
                if y == top:
                    b = "grass_block"
                elif y >= top - 3:
                    b = "dirt"
                else:
                    n = rock_n(x + y * 0.7, z - y * 0.5)
                    b = "andesite" if n > 0.45 else "tuff" if n < -0.5 else "stone"
                    if y <= bottom + 1 and rng.random() < 0.15:
                        b = "moss_block"
                put(x, y, z, b)
            if depth > 6 and rng.random() < 0.05:
                put(x, bottom - 1, z, "pointed_dripstone[vertical_direction=down,thickness=tip]")
            cols.add((x, z))
    return cols


# ================================================================ Hauptinsel
main_cols = island(0, 0, 60, 32)

# ---------------------------------------------------------------- Plaza (r<=15)
for x in range(-16, 17):
    for z in range(-16, 17):
        d = math.hypot(x, z)
        if d > 15.5:
            continue
        ang = (math.degrees(math.atan2(z, x)) + 360) % 360
        b = "polished_andesite"
        if 14.5 < d <= 15.5:
            b = "polished_deepslate"
        elif 13.5 < d <= 14.5:
            b = "smooth_quartz"
        elif 9.0 < d <= 10.0:
            b = "cyan_terracotta"
        elif 6.0 < d <= 7.0:
            b = "smooth_quartz"
        elif d > 7 and min(abs(((ang - a + 180) % 360) - 180) for a in range(0, 360, 45)) * math.pi / 180 * d < 0.7:
            b = "light_blue_terracotta"
        put(x, 100, z, b)
        put(x, 99, z, "stone")
# eingelassene Lichter
for i in range(8):
    a = math.radians(22.5 + i * 45)
    put(round(math.cos(a) * 11.5), 100, round(math.sin(a) * 11.5), "sea_lantern")

# ---------------------------------------------------------------- Brunnen mit Leuchtfeuer
disc(0, 0, 5.5, 100, "dark_prismarine")
box(-1, 99, -1, 1, 99, 1, "iron_block")
put(0, 100, 0, "beacon")
for x in range(-6, 7):
    for z in range(-6, 7):
        d = math.hypot(x, z)
        if 4.6 < d <= 5.7:
            put(x, 101, z, "smooth_quartz"); put(x, 102, z, "smooth_quartz_slab[type=bottom]")
        elif 1.6 < d <= 4.6:
            put(x, 101, z, "water")
box(-1, 101, -1, 1, 101, 1, "prismarine_bricks")
put(0, 101, 0, "cyan_stained_glass")
for x, z in [(-1, -1), (1, -1), (-1, 1), (1, 1)]:
    put(x, 102, z, "prismarine_wall"); put(x, 103, z, "sea_lantern")
for x, z in [(0, -4), (4, 0), (0, 4), (-4, 0)]:  # Wasser-Fontaenen-Saeulen (nicht ueberlaufend)
    put(x, 101, z, "prismarine_bricks"); put(x, 102, z, "prismarine_wall"); put(x, 103, z, "soul_lantern[hanging=false]")

# Baenke (Lehne nach aussen) und Laternen am Plaza-Rand
def bench(cx, cz, facing, length=3):
    dx, dz = (1, 0) if facing in ("north", "south") else (0, 1)
    for i in range(-(length // 2), length // 2 + 1):
        put(cx + dx * i, 101, cz + dz * i, f"quartz_stairs[facing={facing},half=bottom]")


for i in range(4):
    a = math.radians(45 + i * 90)
    cx, cz = round(math.cos(a) * 12.2), round(math.sin(a) * 12.2)
    if abs(cx) >= abs(cz):
        bench(cx, cz, "east" if cx > 0 else "west")
    else:
        bench(cx, cz, "south" if cz > 0 else "north")


def lamp(x, z, y=101, h=3, soul=False):
    for k in range(h):
        put(x, y + k, z, "polished_deepslate_wall")
    put(x, y + h, z, "soul_lantern[hanging=false]" if soul else "lantern[hanging=false]")


for i in range(8):
    a = math.radians(i * 45 + 22.5)
    lamp(round(math.cos(a) * 16.5), round(math.sin(a) * 16.5))


# ---------------------------------------------------------------- Wege
def path_tile(x, z, edge=False):
    if edge:
        return "polished_andesite"
    r = rng.random()
    return "mossy_stone_bricks" if r < 0.12 else "cracked_stone_bricks" if r < 0.18 else "stone_bricks"


for k in range(15, 24):
    for w in range(-3, 4):
        e = abs(w) == 3
        put(w, 100, -k, path_tile(w, -k, e)); put(w, 100, k, path_tile(w, k, e))
        put(k, 100, w, path_tile(k, w, e)); put(-k, 100, w, path_tile(-k, w, e))
for k in (18, 22):
    lamp(-4, -k); lamp(4, -k); lamp(-4, k); lamp(4, k)
    lamp(k, -4); lamp(k, 4); lamp(-k, -4); lamp(-k, 4)

# Diagonale Gartenwege (Trampelpfad) zu den Eck-Bereichen
for sx, sz in [(1, 1), (-1, 1), (1, -1), (-1, -1)]:
    for t in range(15, 30):
        for o in (-1, 0, 1):
            x = round(sx * t * 0.7071 + o * 0.7071 * sx)
            z = round(sz * t * 0.7071 - o * 0.7071 * sz)
            if get(x, 100, z) == "grass_block":
                put(x, 100, z, "dirt_path")


# ================================================================ Norden: Kistenhalle
def checker(x1, z1, x2, z2, a, b, y=100, size=2):
    for x in range(x1, x2 + 1):
        for z in range(z1, z2 + 1):
            put(x, y, z, a if ((x // size) + (z // size)) % 2 == 0 else b)


checker(-18, -42, 18, -24, "smooth_quartz", "cyan_terracotta")
box(-19, 100, -43, 19, 100, -43, "polished_deepslate"); box(-19, 100, -43, -19, 100, -24, "polished_deepslate")
box(19, 100, -43, 19, 100, -24, "polished_deepslate")
# Rueckwand mit Fenstern
box(-19, 101, -44, 19, 111, -44, "quartz_bricks")
for x in range(-16, 17, 4):
    box(x - 1, 104, -44, x + 1, 109, -44, "light_blue_stained_glass")
    box(x, 101, -43, x, 111, -43, "quartz_pillar[axis=y]")
box(-19, 112, -44, 19, 112, -44, "prismarine_bricks")
# Saeulengaenge an den Seiten + Pergola-Dach
for z in range(-42, -23, 3):
    for x in (-19, 19):
        box(x, 101, z, x, 109, z, "quartz_pillar[axis=y]")
        put(x, 110, z, "chiseled_quartz_block")
for z in range(-43, -23, 2):
    box(-19, 110, z, 19, 110, z, "prismarine_brick_slab[type=bottom]")
box(-19, 111, -43, -19, 111, -24, "dark_prismarine"); box(19, 111, -43, 19, 111, -24, "dark_prismarine")
for x in range(-15, 16, 6):
    put(x, 109, -30, "lantern[hanging=true]"); put(x, 109, -38, "lantern[hanging=true]")

CRATES = [("vote", "lime"), ("daily", "yellow"), ("rare", "blue"), ("epic", "purple"), ("legendary", "orange")]
crate_pos = {}
for i, (cid, col) in enumerate(CRATES):
    cx = -12 + i * 6
    cz = -32 - abs(cx) // 4
    box(cx - 2, 100, cz - 2, cx + 2, 100, cz + 2, f"{col}_concrete")
    box(cx - 1, 101, cz - 1, cx + 1, 101, cz + 1, "smooth_quartz_slab[type=bottom]")
    put(cx, 101, cz, f"{col}_glazed_terracotta[facing=south]")
    put(cx, 102, cz, "chest[facing=south]")
    for px, pz in [(cx - 2, cz - 2), (cx + 2, cz - 2), (cx - 2, cz + 2), (cx + 2, cz + 2)]:
        box(px, 101, pz, px, 104, pz, "quartz_pillar[axis=y]"); put(px, 105, pz, "end_rod[facing=up]")
    box(cx - 2, 106, cz - 2, cx + 2, 106, cz + 2, f"{col}_stained_glass")
    for px, pz in [(cx - 2, cz - 2), (cx + 2, cz - 2), (cx - 2, cz + 2), (cx + 2, cz + 2)]:
        put(px, 105, pz, "quartz_pillar[axis=y]")
    put(cx, 105, cz, "lantern[hanging=true]")
    crate_pos[cid] = [cx, 102, cz]

# ================================================================ Osten: Markt
for x in range(22, 46):
    for z in range(-17, 18):
        r = rng.random()
        put(x, 100, z, "andesite" if r < 0.3 else "cobblestone" if r < 0.4 else "polished_andesite")
box(22, 100, -1, 45, 100, 1, "stone_bricks")


def stall(x1, z1, front):  # 7 breit (x), 5 tief (z); front = "south" oder "north"
    x2, z2 = x1 + 6, z1 + 4
    box(x1, 100, z1, x2, 100, z2, "spruce_planks")
    for px in (x1, x2):
        for pz in (z1, z2):
            box(px, 101, pz, px, 104, pz, "spruce_log[axis=y]")
    for x in range(x1 - 1, x2 + 2):
        for z in range(z1 - 1, z2 + 2):
            put(x, 105, z, "cyan_wool" if (x - x1) % 2 == 0 else "white_wool")
    back = z1 if front == "south" else z2
    cnt = z2 if front == "south" else z1
    for x in range(x1 + 1, x2):
        put(x, 101, back, "barrel[facing=up]" if x % 2 else "spruce_planks")
        put(x, 102, back, "spruce_slab[type=bottom]")
        put(x, 101, cnt, "spruce_planks" if x != x1 + 3 else "air")
        if x != x1 + 3:
            put(x, 102, cnt, rng.choice(["hay_block[axis=y]", "melon", "pumpkin", "barrel[facing=up]", "spruce_slab[type=bottom]"]))
    put(x1 + 1, 104, (z1 + z2) // 2, "lantern[hanging=true]"); put(x2 - 1, 104, (z1 + z2) // 2, "lantern[hanging=true]")
    return x1 + 3, (z1 + z2) // 2


st_shop = stall(26, -15, "south")
st_sell = stall(36, -15, "south")
st_ah = stall(26, 11, "north")
st_warp = stall(36, 11, "north")
# Markt-Mitte: kleiner Kirschbaum-Platz
disc(41, 0, 2.6, 100, "moss_block")


def cherry_tree(x, z, y=101, h=5):
    for k in range(h):
        put(x, y + k, z, "cherry_log[axis=y]")
    top = y + h
    for dx in range(-3, 4):
        for dz in range(-3, 4):
            for dy in range(-1, 2):
                d = math.hypot(dx, dz) + abs(dy) * 1.4
                if d <= 3.2 and rng.random() > 0.12:
                    if get(x + dx, top + dy, z + dz) is None:
                        put(x + dx, top + dy, z + dz, "cherry_leaves[persistent=true]")
    for dx, dz in [(-2, 0), (2, 0), (0, -2), (0, 2)]:
        if rng.random() < 0.7:
            put(x + dx, top - 2, z + dz, "cherry_leaves[persistent=true]")


def oak_tree(x, z, y=101, h=None):
    h = h or rng.randint(5, 6)
    for k in range(h):
        put(x, y + k, z, "oak_log[axis=y]")
    top = y + h
    for dy, rad in [(-2, 2.5), (-1, 2.6), (0, 1.8), (1, 1.2)]:
        for dx in range(-3, 4):
            for dz in range(-3, 4):
                if math.hypot(dx, dz) <= rad and (dx or dz or dy > 0) and rng.random() > 0.08:
                    if get(x + dx, top + dy, z + dz) is None:
                        put(x + dx, top + dy, z + dz, "oak_leaves[persistent=true]")


cherry_tree(41, 0)

# ================================================================ Westen: Fortschritt (Tempel)
checker(-45, -15, -22, 15, "smooth_quartz", "light_blue_terracotta", size=3)
box(-46, 100, -16, -46, 100, 16, "polished_deepslate"); box(-46, 100, -16, -22, 100, -16, "polished_deepslate")
box(-46, 100, 16, -22, 100, 16, "polished_deepslate")
for x in range(-44, -22, 4):
    for z in (-15, 15):
        box(x, 101, z, x, 107, z, "quartz_pillar[axis=y]")
for z in range(-15, 16, 5):
    box(-45, 101, z, -45, 107, z, "quartz_pillar[axis=y]")
box(-46, 101, -14, -46, 107, 14, "quartz_bricks")
for z in range(-12, 13, 6):
    box(-46, 103, z - 1, -46, 106, z + 1, "cyan_stained_glass")
box(-46, 108, -16, -22, 108, 16, "prismarine_bricks")
box(-45, 108, -15, -23, 108, 15, "smooth_quartz")
for x in range(-43, -23, 4):
    for z in range(-12, 13, 6):
        put(x, 108, z, "sea_lantern")
box(-46, 109, -16, -22, 109, -16, "dark_prismarine"); box(-46, 109, 16, -22, 109, 16, "dark_prismarine")
box(-46, 109, -16, -46, 109, 16, "dark_prismarine"); box(-22, 109, -16, -22, 109, 16, "dark_prismarine")
# Podeste fuer die NPCs
PROG = [(-38, 0), (-35, -7), (-35, 7), (-31, -12), (-31, 12)]
for x, z in PROG:
    disc(x, z, 1.2, 100, "chiseled_quartz_block")

# ================================================================ Nordost: Clanhalle
cx0, cz0 = 33, -33
box(cx0 - 9, 100, cz0 - 9, cx0 + 9, 100, cz0 + 9, "polished_andesite")
checker(cx0 - 7, cz0 - 7, cx0 + 7, cz0 + 7, "stone_bricks", "polished_andesite", size=1)
for x in range(cx0 - 9, cx0 + 10):
    for z in range(cz0 - 9, cz0 + 10):
        edge = x in (cx0 - 9, cx0 + 9) or z in (cz0 - 9, cz0 + 9)
        back = z == cz0 - 9 or x == cx0 + 9
        if edge and back:
            for y in range(101, 108):
                put(x, y, z, "polished_deepslate" if (x in (cx0 - 9, cx0 + 9) and z in (cz0 - 9, cz0 + 9)) else "stone_bricks")
for x in range(cx0 - 6, cx0 + 7, 4):
    box(x, 103, cz0 - 9, x, 106, cz0 - 9, "blue_stained_glass")
for z in range(cz0 - 6, cz0 + 7, 4):
    box(cx0 + 9, 103, z, cx0 + 9, 106, z, "blue_stained_glass")
box(cx0 - 9, 101, cz0 + 9, cx0 - 9, 107, cz0 + 9, "polished_deepslate")
box(cx0 - 9, 108, cz0 - 9, cx0 + 9, 108, cz0 + 9, "deepslate_tiles")
box(cx0 - 8, 108, cz0 - 8, cx0 + 8, 108, cz0 + 8, "dark_oak_planks")
for x in range(cx0 - 6, cx0 + 7, 4):
    for z in range(cz0 - 6, cz0 + 7, 4):
        put(x, 107, z, "lantern[hanging=true]")
for x in range(cx0 - 7, cx0 + 8, 3):
    put(x, 105, cz0 - 8, "blue_wall_banner[facing=south]")
for z in range(cz0 - 7, cz0 + 8, 3):
    put(cx0 + 8, 105, z, "light_blue_wall_banner[facing=west]")
box(cx0 - 2, 101, cz0 - 1, cx0 + 2, 101, cz0 + 1, "dark_oak_planks")  # Tisch
for x in range(cx0 - 2, cx0 + 3):
    put(x, 101, cz0 - 2, "dark_oak_stairs[facing=south,half=bottom]")  # Stuehle
    put(x, 101, cz0 + 2, "dark_oak_stairs[facing=north,half=bottom]")
put(cx0, 102, cz0, "lantern[hanging=false]")

# ================================================================ Nordwest: Event-Platz mit Buehne
ex, ez = -33, -33
box(ex - 9, 100, ez - 9, ex + 9, 100, ez + 9, "polished_blackstone_bricks")
checker(ex - 7, ez - 7, ex + 7, ez + 7, "polished_blackstone", "polished_blackstone_bricks", size=1)
box(ex - 6, 101, ez - 9, ex + 6, 101, ez - 4, "polished_blackstone")
for x in range(ex - 6, ex + 7):
    put(x, 101, ez - 3, "polished_blackstone_stairs[facing=north,half=bottom]")
for x in (ex - 6, ex + 6):
    box(x, 102, ez - 9, x, 108, ez - 9, "polished_blackstone_bricks")
    put(x, 109, ez - 9, "end_rod[facing=up]")
box(ex - 6, 108, ez - 9, ex + 6, 108, ez - 9, "purple_stained_glass")
box(ex - 5, 102, ez - 9, ex + 5, 107, ez - 9, "black_stained_glass")
put(ex, 102, ez - 6, "bell[attachment=floor,facing=south]")
for x in (ex - 9, ex + 9):
    for z in (ez - 9, ez + 9):
        lamp(x, z, h=4, soul=True)

# ================================================================ Sueden: Portale
checker(-27, 22, 27, 37, "smooth_quartz", "cyan_terracotta", size=2)
box(-28, 100, 38, 28, 100, 38, "polished_deepslate")
PORTALS = [  # (x-Mitte, Name, Aktion, Akzentfarbe)
    (-15, "Farmwelt", "farmwelt", "lime"),
    (-5, "Survival", "rtp:world", "light_blue"),
    (5, "Nether", "rtp:world_nether", "red"),
    (15, "PvP-Arena", "warp:arena", "orange"),
]
PZ = 31
portal_defs = []
for cx, name, action, col in PORTALS:
    box(cx - 2, 100, PZ, cx + 2, 105, PZ, "obsidian")
    box(cx - 1, 101, PZ, cx + 1, 104, PZ, "nether_portal[axis=x]")
    box(cx - 3, 101, PZ, cx - 3, 106, PZ, "quartz_pillar[axis=y]"); box(cx + 3, 101, PZ, cx + 3, 106, PZ, "quartz_pillar[axis=y]")
    box(cx - 3, 107, PZ, cx + 3, 107, PZ, "prismarine_bricks")
    put(cx, 108, PZ, "sea_lantern")
    box(cx - 2, 100, PZ - 3, cx + 2, 100, PZ - 1, f"{col}_concrete")
    portal_defs.append({"name": name, "action": action, "box": [cx - 1, 101, PZ, cx + 1, 104, PZ],
                        "back": [cx + 0.5, 101, PZ - 3.5, 180, 0]})

# ================================================================ Gaerten (Suedwest / Suedost) + Baeume
def pond(cx, cz, rx, rz):
    for x in range(cx - rx - 1, cx + rx + 2):
        for z in range(cz - rz - 1, cz + rz + 2):
            v = ((x - cx) / rx) ** 2 + ((z - cz) / rz) ** 2
            if v <= 1:
                put(x, 100, z, "water"); put(x, 99, z, "water" if v < 0.5 else "clay"); put(x, 98, z, "clay")
                if v < 0.5:
                    put(x, 97, z, "clay")
                if rng.random() < 0.08:
                    put(x, 101, z, "lily_pad")
            elif v <= 1.6 and get(x, 100, z) == "grass_block" and rng.random() < 0.3:
                put(x, 101, z, "short_grass")


pond(-34, 32, 6, 4)
pond(34, 31, 5, 4)
for x, z in [(-44, 24), (-24, 44), (-40, 42), (-20, 30), (44, 22), (24, 44), (40, 42), (-52, -6), (52, 22), (-12, -52), (12, -52),
             (0, -54), (-50, 14), (-26, -48), (26, -48), (50, -8)]:
    if get(x, 100, z) == "grass_block":
        (cherry_tree if rng.random() < 0.45 else oak_tree)(x, z)
for i, (x, z) in enumerate([(-28, 26), (28, 26), (-24, 36), (24, 36)]):
    bench(x, z, "south" if i % 2 else "north")

# ================================================================ Arena-Insel (PvP) bei z=150
AZ = 150
arena_cols = island(0, AZ, 39, 26)
for x in range(-36, 37):
    for z in range(AZ - 36, AZ + 37):
        d = math.hypot(x, z - AZ)
        if d <= 18.5:
            put(x, 100, z, "cut_red_sandstone" if 11.5 < d <= 12.5 or 5.5 < d <= 6.5 else "smooth_sandstone")
        elif d <= 26.5:
            tier = int(d - 18.5)
            base = 101 + tier
            gap = abs(x) <= 2 and z < AZ
            for y in range(100, base + 1):
                if gap and y > 100:
                    continue
                put(x, y, z, "stone_bricks" if (tier + y) % 2 else "polished_andesite")
            if not gap:
                put(x, base + 1, z, "stone_brick_slab[type=bottom]" if tier < 7 else "stone_bricks")
            if tier == 7 and not gap:
                for y in range(base + 2, base + 4):
                    put(x, y, z, "stone_brick_wall" if y == base + 2 else "air")
for i in range(12):
    a = math.radians(i * 30 + 15)
    x, z = round(math.cos(a) * 27), AZ + round(math.sin(a) * 27)
    if not (abs(x) <= 3 and z < AZ):
        lamp(x, z, y=max(k[1] for k in W if k[0] == x and k[2] == z and W[k] not in ("air",)) + 1, h=2)
# Eingang (Norden) mit Rueckkehr-Portal
checker(-8, AZ - 36, 8, AZ - 27, "smooth_quartz", "cyan_terracotta", size=2)
RZ = AZ - 33
box(-2, 100, RZ, 2, 105, RZ, "obsidian")
box(-1, 101, RZ, 1, 104, RZ, "nether_portal[axis=x]")
box(-3, 101, RZ, -3, 106, RZ, "quartz_pillar[axis=y]"); box(3, 101, RZ, 3, 106, RZ, "quartz_pillar[axis=y]")
box(-3, 107, RZ, 3, 107, RZ, "prismarine_bricks"); put(0, 108, RZ, "sea_lantern")
portal_defs.append({"name": "Spawn", "action": "warp:spawn", "box": [-1, 101, RZ, 1, 104, RZ], "back": [0.5, 101, RZ + 3.5, 0, 0]})
arena_arrival = [0.5, 101, RZ + 4.5, 0, 0]

# ================================================================ Duell-Inseln
duels = {}
for name, dcx in [("duell1", -64), ("duell2", 64)]:
    island(dcx, AZ, 15, 14, wobble=2)
    checker(dcx - 11, AZ - 11, dcx + 11, AZ + 11, "smooth_quartz", "light_blue_concrete", size=3)
    for x in range(dcx - 12, dcx + 13):
        for z in range(AZ - 12, AZ + 13):
            if x in (dcx - 12, dcx + 12) or z in (AZ - 12, AZ + 12):
                put(x, 100, z, "polished_deepslate")
                for y in range(101, 105):
                    put(x, y, z, "light_blue_stained_glass")
    for x, z in [(dcx, AZ - 10), (dcx, AZ + 10)]:
        put(x, 100, z, "sea_lantern")
    duels[name] = {"pos1": [dcx + 0.5, 101, AZ - 9.5, 0, 0], "pos2": [dcx + 0.5, 101, AZ + 10.5, 180, 0]}

# ================================================================ Kristall-Monument ueber dem Brunnen
GLASS = ["light_blue_stained_glass", "cyan_stained_glass", "light_blue_stained_glass", "white_stained_glass"]


def crystal(cx, cy, cz, r, h, core="sea_lantern"):
    """Hohle Doppelpyramide aus Glas mit leuchtendem Kern; die Mittelachse bleibt frei (Leuchtfeuer-Strahl)."""
    for dy in range(-h, h + 1):
        rad = r * (1 - abs(dy) / (h + 0.5))
        ri = int(math.ceil(rad))
        for dx in range(-ri, ri + 1):
            for dz in range(-ri, ri + 1):
                m = abs(dx) + abs(dz)
                if m > rad:
                    continue
                if m > rad - 1.0:
                    put(cx + dx, cy + dy, cz + dz, "cyan_stained_glass" if dx == 0 or dz == 0 else GLASS[(dx * 3 + dz * 5 + dy) % len(GLASS)])
                elif (dx or dz) and m <= rad - 2.2 and abs(dy) < h * 0.55 and (dx + dz + dy) % 2 == 0:
                    put(cx + dx, cy + dy, cz + dz, core)


crystal(0, 125, 0, 6, 11)
for i in range(8):  # acht Boegen tragen den Kristall
    a = math.radians(22.5 + i * 45)
    mat = "quartz_bricks" if i % 2 == 0 else "prismarine_bricks"
    for k in range(0, 181):
        phi = math.radians(k / 2)
        r, y = 5 + 8 * math.cos(phi), 101 + 12 * math.sin(phi)
        put(round(math.cos(a) * r), round(y), round(math.sin(a) * r), mat)
    put(round(math.cos(a) * 13), 101, round(math.sin(a) * 13), "chiseled_quartz_block")
    put(round(math.cos(a) * 13), 102, round(math.sin(a) * 13), "sea_lantern")
for x in range(-6, 7):  # Krone unter dem Kristall
    for z in range(-6, 7):
        d = math.hypot(x, z)
        if 4.4 < d <= 5.6:
            put(x, 113, z, "smooth_quartz" if (x + z) % 2 else "sea_lantern")
for i in range(6):  # kleine Splitter umkreisen das Monument
    a = math.radians(i * 60 + 10)
    crystal(round(math.cos(a) * 11), 119 + (i % 3) * 4, round(math.sin(a) * 11), 2, 3, core="amethyst_block")

# ================================================================ Schwebender Schriftzug MINESMP (Norden)
LETTERS = {
    'M': ["10001", "11011", "10101", "10001", "10001", "10001", "10001"], 'I': ["111", "010", "010", "010", "010", "010", "111"],
    'N': ["10001", "11001", "10101", "10011", "10001", "10001", "10001"], 'E': ["11111", "10000", "11110", "10000", "10000", "10000", "11111"],
    'S': ["01111", "10000", "10000", "01110", "00001", "00001", "11110"], 'P': ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
}


def block_text(text, x0, y0, z, scale, colors, depth=2, back="sea_lantern"):
    """Schrift aus Bloecken in der x/y-Ebene (Blick von Sueden). colors: Liste je Buchstabe."""
    x = x0
    for i, ch in enumerate(text):
        g = LETTERS[ch]
        for r, row in enumerate(g):
            for c, v in enumerate(row):
                if v != '1':
                    continue
                for sx in range(scale):
                    for sy in range(scale):
                        put(x + c * scale + sx, y0 + (6 - r) * scale + sy, z, colors[i])
                        for dz in range(1, depth):
                            put(x + c * scale + sx, y0 + (6 - r) * scale + sy, z - dz, back)
        x += (len(g[0]) + 1) * scale
    return x - scale - x0


LOGO_W = sum((len(LETTERS[c][0]) + 1) * 2 for c in "MINESMP") - 2
block_text("MINESMP", -LOGO_W // 2, 126, -62, 2, ["cyan_concrete"] * 4 + ["white_concrete"] * 3)
for x in range(-LOGO_W // 2 - 2, LOGO_W // 2 + 3):  # leuchtende Leiste darunter
    put(x, 123, -62, "prismarine_bricks" if x % 4 else "sea_lantern")
    put(x, 124, -62, "smooth_quartz_slab[type=bottom]")


# ================================================================ Kristall-Spitzen am Inselrand, kleine Inseln, Wasserfaelle
def spike(x, z, h, y0=101):
    for k in range(h):
        rad = (1 - k / h) * (1.7 if h > 9 else 1.15)
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                if math.hypot(dx, dz) <= rad + 0.25:
                    core = dx == 0 and dz == 0 and k % 3 == 1 and k < h - 2
                    put(x + dx, y0 + k, z + dz, "sea_lantern" if core else "amethyst_block" if k == 0 else GLASS[(dx + dz + k) % len(GLASS)])
    put(x, y0 + h, z, "end_rod[facing=up]")


for i in range(14):
    a = math.radians(i * 360 / 14 + 9)
    x, z = round(math.cos(a) * 52), round(math.sin(a) * 52)
    if get(x, 100, z) == "grass_block" and get(x, 101, z) is None and not (abs(z) < 6 and x > 0) and math.hypot(x + 47, z - 34) > 16:
        spike(x, z, rng.randint(6, 13))


def waterfall(x, z, top, length):
    for y in range(top - length, top + 1):
        put(x, y, z, "water")


ISLETS = [(-88, -34, 109, 7), (-80, 44, 95, 6), (72, -66, 113, 7), (58, 76, 104, 6), (-46, -86, 117, 6), (22, -90, 101, 5), (-98, 6, 121, 6), (86, 50, 111, 6)]
for i, (ix, iz, top, rad) in enumerate(ISLETS):
    island(ix, iz, rad, 9, top=top, wobble=1.5)
    if i % 3 == 0:
        spike(ix, iz, rng.randint(7, 11), y0=top + 1)
    elif i % 3 == 1:
        cherry_tree(ix, iz, y=top + 1)
    else:
        oak_tree(ix, iz, y=top + 1)
    if i % 2 == 0:  # stehender Wasserfall vom Rand in die Tiefe
        put(ix + rad - 2, top, iz, "water")
        waterfall(ix + rad - 1, iz, top, 22 + i * 2)
for x, z in [(-57, -12), (-56, 18), (57, -22), (12, 58)]:  # Wasserfaelle an der Hauptinsel
    if get(x, 100, z):
        put(x, 100, z, "water")
        sx = 1 if x > 0 else -1 if abs(x) > abs(z) else 0
        sz = 0 if sx else (1 if z > 0 else -1)
        for k in range(1, 5):
            if get(x + sx * k, 100, z + sz * k) is None:
                waterfall(x + sx * k, z + sz * k, 100, 30)
                break
            put(x + sx * k, 100, z + sz * k, "water")

# ================================================================ Clan-Kolosseum (Clan-Liga) im Osten
KX, KZ = 112, 0
island(KX, KZ, 37, 26)
for x in range(KX - 34, KX + 35):
    for z in range(KZ - 34, KZ + 35):
        d = math.hypot(x - KX, z - KZ)
        if d <= 14.5:  # Kampfflaeche
            b = "smooth_sandstone"
            if 9.5 < d <= 10.5 or d <= 1.5:
                b = "cut_sandstone"
            if abs(z - KZ) <= 3 and x < KX - 6:
                b = "light_blue_concrete"   # Startfeld Team 1
            if abs(z - KZ) <= 3 and x > KX + 6:
                b = "red_concrete"          # Startfeld Team 2
            put(x, 100, z, b)
            put(x, 99, z, "sandstone")
        elif d <= 16.5:  # Ringmauer, darueber unsichtbare Sperre
            for y in range(100, 104):
                put(x, y, z, "polished_blackstone_bricks" if y < 103 else "polished_blackstone_brick_wall" if (x + z) % 2 else "polished_blackstone_bricks")
            if d <= 15.5:
                for y in range(104, 112):
                    put(x, y, z, "barrier")
        elif d <= 26.5:  # Tribuene: unten ein Rundgang, darueber Stufen (je 1 Block, begehbar)
            tier = int(d - 16.5)
            base = 103 if tier <= 1 else 102 + tier
            gate = abs(z - KZ) <= 2 and x < KX and tier >= 2
            side = "light_blue" if x < KX else "red"
            for y in range(100, base + 1):
                if gate and 100 < y < 106:
                    continue
                b = "quartz_bricks" if (tier + y) % 3 == 0 else "smooth_quartz" if tier % 2 else "stone_bricks"
                if tier == 9 and 104 <= y <= 108 and int(math.degrees(math.atan2(z - KZ, x - KX)) // 6) % 2 == 0:
                    b = f"{side}_stained_glass"   # Fensterband in der Aussenmauer
                put(x, y, z, b)
            if gate:  # Treppe vom Eingang hinauf zum Rundgang
                step = int((KX - x) - 16.5)
                if step in (2, 3, 4):
                    for y in range(101, 101 + (4 - step)):
                        put(x, y, z, "quartz_bricks")
                    put(x, 101 + (4 - step), z, "quartz_stairs[facing=east,half=bottom]")
            elif tier in (3, 6):
                put(x, base + 1, z, f"{side}_carpet")
            if tier == 9:
                put(x, base + 1, z, "quartz_bricks"); put(x, base + 2, z, "polished_blackstone_brick_wall" if (x + z) % 3 else "quartz_pillar[axis=y]")
for i in range(12):  # Feuerschalen-Tuerme auf der Tribuene
    a = math.radians(i * 30 + 15)
    x, z = KX + round(math.cos(a) * 26), KZ + round(math.sin(a) * 26)
    top = max(k[1] for k in W if k[0] == x and k[2] == z)
    box(x, top + 1, z, x, top + 5, z, "quartz_pillar[axis=y]")
    put(x, top + 6, z, "polished_blackstone"); put(x, top + 7, z, "soul_campfire[lit=true]" if i % 2 else "campfire[lit=true]")
    put(x, top + 4, z + (1 if z < KZ else -1), ("light_blue" if x < KX else "red") + "_wall_banner[facing=" + ("south" if z < KZ else "north") + "]")
# Vorplatz im Westen der Insel + Bruecke zum Markt
checker(KX - 36, KZ - 9, KX - 27, KZ + 9, "smooth_quartz", "light_blue_terracotta", size=2)
box(KX - 26, 100, KZ - 2, KX - 17, 100, KZ + 2, "polished_andesite")
for x in range(46, KX - 35):
    for z in range(-3, 4):
        e = abs(z) == 3
        put(x, 100, z, "polished_deepslate" if e else path_tile(x, z))
        put(x, 99, z, "stone_bricks")
        if e and x % 2 == 0:
            put(x, 101, z, "stone_brick_wall")
        if not e and x % 6 == 0:
            for k in range(1, 4 - abs(z)):
                put(x, 99 - k, z, "stone_bricks")
    if x % 8 == 4:
        lamp(x, -3, y=102, h=2); lamp(x, 3, y=102, h=2)
for z in (KZ - 9, KZ + 9):
    box(KX - 36, 101, z, KX - 36, 106, z, "quartz_pillar[axis=y]"); put(KX - 36, 107, z, "sea_lantern")
    box(KX - 27, 101, z, KX - 27, 106, z, "quartz_pillar[axis=y]"); put(KX - 27, 107, z, "sea_lantern")
clan_arena = {"main": {"pos1": [KX - 9.5, 101, KZ + 0.5, -90, 0], "pos2": [KX + 10.5, 101, KZ + 0.5, 90, 0]}}
clan_board = [KX - 31.5, 102.8, KZ - 6.5]

# ================================================================ PvP-Platz (vor der Arena) und Sumo-Plattformen
checker(-13, AZ - 37, 13, AZ - 27, "smooth_quartz", "red_terracotta", size=2)
for x in (-13, 13):
    for z in (AZ - 37, AZ - 27):
        box(x, 101, z, x, 105, z, "polished_blackstone_bricks"); put(x, 106, z, "campfire[lit=true]")
for x in (-9, 9):
    for z in (AZ - 30, AZ - 35):
        disc(x, z, 1.3, 100, "chiseled_polished_blackstone")
SUMO = {}
for name, sx in [("a", -34), ("b", 34)]:
    SZ = 92
    for x in range(sx - 9, sx + 10):
        for z in range(SZ - 9, SZ + 10):
            d = math.hypot(x - sx, z - SZ)
            if d <= 6.5:
                put(x, 100, z, "red_concrete" if 5.5 < d else "white_concrete" if d > 1.5 else "red_concrete")
                for k in range(1, int(7 - d) + 1):
                    put(x, 100 - k, z, "smooth_quartz" if k == 1 else "stone")
            elif d <= 8.5 and (x + z) % 3 == 0:
                put(x, 96, z, "sea_lantern")  # leuchtender Ring unter der Plattform
    put(sx - 2, 100, SZ, "black_concrete"); put(sx + 2, 100, SZ, "black_concrete")
    SUMO[name] = {"pos1": [sx - 2.5, 101, SZ + 0.5, -90, 0], "pos2": [sx + 3.5, 101, SZ + 0.5, 90, 0], "min-y": 98.5}

# ================================================================ Tor zur Kristallgruft (Suedwesten)
TX, TZ = -47, 34
box(TX - 3, 92, TZ - 5, TX + 5, 99, TZ + 5, "stone")
box(TX - 3, 100, TZ - 5, TX + 5, 100, TZ + 5, "deepslate_tiles")
for z in range(TZ - 4, TZ + 5):
    for y in range(101, 110):
        arch = abs(z - TZ) <= 2 and y <= 106 - (1 if abs(z - TZ) == 2 else 0)
        put(TX - 2, y, z, "black_stained_glass" if arch else "deepslate_bricks" if (y + z) % 3 else "cracked_deepslate_bricks")
        if not arch and abs(z - TZ) in (3, 4):
            put(TX - 1, y, z, "polished_deepslate")
    put(TX - 2, 110, z, "deepslate_tile_slab[type=bottom]")
box(TX - 3, 101, TZ - 4, TX - 3, 109, TZ + 4, "deepslate")
for z in (TZ - 3, TZ + 3):
    put(TX, 101, z, "amethyst_block"); put(TX, 102, z, "amethyst_cluster[facing=up]")
    put(TX - 1, 110, z, "soul_lantern[hanging=false]")
for z in (TZ - 2, TZ + 2):
    put(TX - 1, 107, z, "crying_obsidian")
put(TX - 1, 108, TZ, "amethyst_block"); put(TX - 1, 109, TZ, "amethyst_cluster[facing=up]")
for t in range(0, 31):  # Weg vom Garten zum Tor
    x, z = round(-21 + (TX + 5 + 21) * t / 30), round(21 + (TZ - 21) * t / 30)
    for o in (-1, 0, 1):
        if get(x, 100, z + o) in ("grass_block", "dirt_path"):
            put(x, 100, z + o, "deepslate_tiles" if rng.random() < 0.6 else "cobbled_deepslate")
            W.pop((x, 101, z + o), None)
pve_lobby = [TX + 3.5, 101, TZ + 0.5, 90, 0]


# ================================================================ Die Grueften (PvE-Arenen), weit weg vom Spawn
def crypt(cx, cz, y0=80, R=26):
    lanterns, mobs = [], []
    for x in range(cx - R - 3, cx + R + 4):
        for z in range(cz - R - 3, cz + R + 4):
            d = math.hypot(x - cx, z - cz)
            if d > R + 2.6:
                continue
            put(x, y0 - 1, z, "deepslate")
            if d >= R - 0.5:  # Wand
                for y in range(y0, y0 + 13):
                    put(x, y, z, "deepslate" if rock_n(x + y, z - y) > -0.3 else "cobbled_deepslate")
                continue
            n = rock_n(x * 1.7, z * 1.7)
            floor = "deepslate_tiles" if n > 0.35 else "cobbled_deepslate" if n > -0.1 else "deepslate" if n > -0.55 else "moss_block"
            if 12.5 < d <= 13.5 or d <= 2.5:
                floor = "polished_deepslate"
            if abs(d - 7.5) < 0.5 and (x + z) % 4 == 0:
                floor = "amethyst_block"
            put(x, y0, z, floor)
            ceil = y0 + 5 + int(14 * math.sqrt(max(0.0, 1 - (d / R) ** 2)))
            for y in range(ceil, ceil + 3):
                put(x, y, z, "deepslate" if (x * 7 + z * 13 + y) % 11 else "amethyst_block")
            if (x * 31 + z * 17) % 97 == 0 and d < R - 3:
                put(x, ceil - 1, z, "ochre_froglight[axis=y]" if (x + z) % 2 else "sea_lantern")
            if floor == "moss_block" and rng.random() < 0.25:
                put(x, y0 + 1, z, "moss_carpet")
    for i in range(4):  # Laternenmasten
        a = math.radians(i * 90)
        x, z = cx + round(math.cos(a) * 13), cz + round(math.sin(a) * 13)
        put(x, y0, z, "chiseled_deepslate")
        for k in range(1, 4):
            put(x, y0 + k, z, "deepslate_brick_wall")
        put(x, y0 + 4, z, "waxed_copper_bulb[lit=false,powered=false]")
        put(x, y0 + 5, z, "deepslate_tile_slab[type=bottom]")
        lanterns.append([x, y0 + 4, z])
    for i in range(8):  # Kristall-Stalagmiten und Stellen, an denen Kreaturen erscheinen
        a = math.radians(i * 45 + 22.5)
        x, z = cx + round(math.cos(a) * 21), cz + round(math.sin(a) * 21)
        mobs.append([x, y0 + 1, z])
        sx, sz = cx + round(math.cos(a) * 17), cz + round(math.sin(a) * 17)
        for k in range(1, 4 + i % 3):
            put(sx, y0 + k, sz, "amethyst_block" if k < 2 + i % 3 else "amethyst_cluster[facing=up]")
        put(cx + round(math.cos(a) * 24), y0 + 2, cz + round(math.sin(a) * 24), "soul_lantern[hanging=false]")
        put(cx + round(math.cos(a) * 24), y0 + 1, cz + round(math.sin(a) * 24), "polished_deepslate_wall")
    for i in range(6):  # Saeulen
        a = math.radians(i * 60 + 30)
        x, z = cx + round(math.cos(a) * 9), cz + round(math.sin(a) * 9)
        ceil = y0 + 5 + int(14 * math.sqrt(1 - (9 / R) ** 2))
        for y in range(y0 + 1, ceil):
            put(x, y, z, "deepslate_bricks" if y % 4 else "chiseled_deepslate")
    return {"spawn": [cx + 0.5, y0 + 1, cz + 21.5, 180, 0], "center": [cx + 0.5, y0 + 1, cz + 0.5], "radius": R + 4,
            "mobs": mobs, "lanterns": lanterns, "boss": [cx, y0 + 1, cz]}


GX = -260
pve_arenas = {"a": crypt(GX, 0), "b": crypt(GX, 110)}

# ================================================================ Blumen & Gras auf freien Grasflaechen
FLOWERS = ["poppy", "dandelion", "cornflower", "azure_bluet", "oxeye_daisy", "allium", "lily_of_the_valley"]
for (x, y, z), b in list(W.items()):
    if b != "grass_block" or get(x, y + 1, z) is not None:
        continue
    r = rng.random()
    if r < 0.16:
        put(x, y + 1, z, "short_grass")
    elif r < 0.20 + 0.04 * flower_n(x, z):
        put(x, y + 1, z, rng.choice(FLOWERS))
    elif r < 0.215:
        put(x, y + 1, z, f"pink_petals[flower_amount={rng.randint(2, 4)},facing=north]")

# ================================================================ Layout (NPCs, Hologramme, ...)
SKINS = ["wide/steve", "slim/alex", "wide/kai", "slim/zuri", "wide/noor", "slim/sunny", "wide/ari", "slim/efe", "wide/makena"]


def npc(i, name, desc, item, pos, action, skin=None):
    return {"id": i, "name": name, "desc": desc, "item": item, "pos": pos, "action": action, "skin": skin or SKINS[hash(i) % len(SKINS)]}


npcs = [
    npc("shop", "&a&lHändler", "&7Server-Shop", "EMERALD", [st_shop[0] + 0.5, 101, st_shop[1] + 0.5, 0, 0], "cmd:shop", "wide/kai"),
    npc("sell", "&e&lAnkäufer", "&7Items verkaufen", "GOLD_INGOT", [st_sell[0] + 0.5, 101, st_sell[1] + 0.5, 0, 0], "cmd:sell", "slim/sunny"),
    npc("ah", "&6&lAuktionator", "&7Auktionshaus", "GOLDEN_HELMET", [st_ah[0] + 0.5, 101, st_ah[1] + 0.5, 180, 0], "cmd:ah", "wide/ari"),
    npc("pwarp", "&b&lReiseführer", "&7Spieler-Warps", "ENDER_PEARL", [st_warp[0] + 0.5, 101, st_warp[1] + 0.5, 180, 0], "cmd:pwarp", "slim/zuri"),
    npc("battlepass", "&d&lPass-Meister", "&7Battlepass", "NETHER_STAR", [PROG[0][0] + 0.5, 101, PROG[0][1] + 0.5, -90, 0], "cmd:battlepass", "wide/noor"),
    npc("quests", "&b&lQuestgeberin", "&7Tägliche & wöchentliche Quests", "WRITABLE_BOOK", [PROG[1][0] + 0.5, 101, PROG[1][1] + 0.5, -90, 0], "cmd:quests", "slim/alex"),
    npc("rewards", "&a&lBelohnungen", "&7Daily, Vote & mehr", "SUNFLOWER", [PROG[2][0] + 0.5, 101, PROG[2][1] + 0.5, -90, 0], "cmd:rewards", "slim/efe"),
    npc("skills", "&e&lLehrmeister", "&7Skills & Level", "EXPERIENCE_BOTTLE", [PROG[3][0] + 0.5, 101, PROG[3][1] + 0.5, -90, 0], "cmd:skills", "wide/makena"),
    npc("achievements", "&6&lChronist", "&7Erfolge", "GOLDEN_APPLE", [PROG[4][0] + 0.5, 101, PROG[4][1] + 0.5, -90, 0], "cmd:achievements", "wide/steve"),
    npc("clan", "&9&lClanmeister", "&7Clans gründen & verwalten", "BLUE_BANNER", [cx0 + 0.5, 101, cz0 - 4.5, 0, 0], "cmd:clan", "wide/kai"),
    npc("bounty", "&c&lKopfgeldjäger", "&7Kopfgelder", "IRON_SWORD", [cx0 - 4.5, 101, cz0 + 4.5, -135, 0], "cmd:bounty", "wide/ari"),
    npc("kits", "&7&lWaffenmeister", "&7Kits", "IRON_CHESTPLATE", [cx0 + 4.5, 101, cz0 + 4.5, 135, 0], "cmd:kit", "wide/noor"),
    npc("event", "&d&lHerold", "&7Events & Zeitplan", "BELL", [ex + 0.5, 102, ez - 6.5, 0, 0], "cmd:event", "slim/sunny"),
    npc("vote", "&a&lWahlhelfer", "&7Voten & Schlüssel", "PAPER", [ex - 4.5, 101, ez + 4.5, -135, 0], "cmd:vote", "slim/zuri"),
    npc("cosmetics", "&d&lStylistin", "&7Spuren & Haustiere", "BLAZE_POWDER", [ex + 5.5, 101, ez + 4.5, 135, 0], "cmd:cosmetics", "slim/alex"),
    npc("keys", "&e&lKistenwärter", "&7Deine Schlüssel", "TRIPWIRE_HOOK", [-16.5, 101, -26.5, -135, 0], "cmd:keys", "wide/makena"),
    npc("guide", "&b&lNavigator", "&7Alle Bereiche & Hilfe", "COMPASS", [3.5, 101, 12.5, 135, 0], "navi", "wide/steve"),
    npc("arena", "&c&lDuellmeister", "&7Warteschlange: Duell & Sumo", "DIAMOND_SWORD", [-8.5, 101, AZ - 29.5, 0, 0], "cmd:queue", "wide/kai"),
    npc("sumo", "&e&lSumo-Meister", "&7Sumo-Warteschlange", "SLIME_BALL", [9.5, 101, AZ - 29.5, 0, 0], "cmd:sumo", "wide/noor"),
    npc("kiteditor", "&b&lKit-Schmied", "&7Kit-Editor: Items anordnen", "CRAFTING_TABLE", [-8.5, 101, AZ - 34.5, 0, 0], "cmd:kiteditor", "slim/sunny"),
    npc("liga", "&d&lLiga-Meisterin", "&7Clan-Duell: 3 gegen 3, gewertet", "NETHERITE_SWORD", [KX - 31.5, 101, KZ + 6.5, 90, 0], "cmd:clanduell", "slim/zuri"),
    npc("gruft", "&5&lGrubenmeister Torin", "&7PvE: Die Kristallgruft", "AMETHYST_CLUSTER", [TX + 0.5, 101, TZ + 0.5, -90, 0], "cmd:gruft", "wide/ari"),
    npc("settings", "&7&lEinstellungen", "&7Scoreboard, TPA & mehr", "COMPARATOR", [-3.5, 101, 12.5, -135, 0], "cmd:settings", "slim/efe"),
    # Markt, zweite Reihe am Weg
    npc("gemshop", "&d&lGem-Händlerin", "&7Gem-Shop: Ränge, Spawner, Items", "AMETHYST_CLUSTER", [27.5, 101, -6.5, 0, 0], "cmd:gemshop", "slim/zuri"),
    npc("sterneshop", "&e&lSternenhändler", "&7Sterne-Shop", "NETHER_STAR", [33.5, 101, -6.5, 0, 0], "cmd:sterneshop", "wide/noor"),
    npc("ce", "&5&lVerzauberer", "&7Eigene Verzauberungen", "ENCHANTED_BOOK", [39.5, 101, -6.5, 0, 0], "cmd:ce", "wide/makena"),
    npc("spawner", "&b&lSpawner-Händler", "&7Spawner kaufen", "SPAWNER", [27.5, 101, 7.5, 180, 0], "cmd:spawner", "wide/ari"),
    npc("orders", "&a&lAuftragsbrett", "&7Kaufaufträge erstellen & liefern", "WRITABLE_BOOK", [33.5, 101, 7.5, 180, 0], "cmd:order", "slim/efe"),
    npc("claim", "&3&lLandvermesser", "&7Grundstücke & Raid-Zeit", "GRASS_BLOCK", [39.5, 101, 7.5, 180, 0], "cmd:claim info", "wide/steve"),
]

holos = [
    {"pos": [0.5, 107.5, 0.5], "scale": 2.4, "text": ["<g:#55FFFF:#3FA9FF:#FFFFFF>&lMINESMP</g>"]},
    {"pos": [0.5, 105.6, 0.5], "scale": 1.0, "text": ["&7Willkommen auf dem Server!", "&b/help &8· &b/discord &8· &b/rules"]},
    {"pos": [0.5, 111.5, -30.5], "scale": 2.0, "text": ["<g:#FFD86B:#FF9E2C>&lKISTEN</g>", "&7Rechtsklick öffnen · Linksklick Vorschau"]},
    {"pos": [33.5, 108.0, 0.5], "scale": 2.0, "text": ["<g:#7CFF8A:#2BD46A>&lMARKT</g>", "&7Shop · Gems · Sterne · Spawner · Aufträge"]},
    {"pos": [-24.0, 110.0, 0.5], "scale": 2.0, "text": ["<g:#E59BFF:#9B6BFF>&lFORTSCHRITT</g>", "&7Battlepass · Quests · Skills · Erfolge"]},
    {"pos": [33.5, 111.0, -33.5], "scale": 2.0, "text": ["<g:#7FA6FF:#4F7CFF>&lCLANS</g>", "&7Clans · Kopfgelder · Kits"]},
    {"pos": [-33.5, 111.0, -33.5], "scale": 2.0, "text": ["<g:#FF8AF0:#B45BFF>&lEVENTS</g>", "&7KOTH · Boss · Turniere · Vote"]},
    {"pos": [0.5, 112.0, 31.5], "scale": 2.0, "text": ["<g:#55FFFF:#4F7CFF>&lPORTALE</g>", "&7Lauf einfach hinein"]},
    {"pos": [0.5, 116.0, AZ + 0.5], "scale": 3.0, "text": ["<g:#FF6B6B:#FFB347>&lPVP-ARENA</g>", "&7Jeder gegen jeden · Combat-Tag aktiv"]},
]
holos += [
    {"pos": [KX - 31.5, 109.5, KZ + 0.5], "scale": 2.2, "text": ["<g:#FF8AF0:#B45BFF>&lCLAN-KOLOSSEUM</g>", "&7Gewertete Clan-Duelle · 3 gegen 3 · &d/clanduell"]},
    {"pos": [KX + 0.5, 122.0, KZ + 0.5], "scale": 3.0, "text": ["<g:#55FFFF:#4F7CFF>&lCLAN-LIGA</g>", "&711 Ränge · Saison-Rangliste"]},
    {"pos": [0.5, 108.5, AZ - 31.5], "scale": 2.0, "text": ["<g:#FF6B6B:#FFB347>&lPVP-PLATZ</g>", "&7Duell · Sumo · Kit-Editor · Kronen"]},
    {"pos": [-33.5, 106.0, 92.5], "scale": 1.6, "text": ["&e&lSUMO", "&7/sumo"]},
    {"pos": [34.5, 106.0, 92.5], "scale": 1.6, "text": ["&e&lSUMO", "&7/sumo"]},
    {"pos": [TX + 1.5, 112.5, TZ + 0.5], "scale": 2.0, "text": ["<g:#C084FC:#55FFFF>&lDIE KRISTALLGRUFT</g>", "&7Überlebe 4 Wellen und besiege den Gruftwächter",
                                                                    "&7allein oder mit bis zu 3 Freunden · &d/gruft"]},
]
for cx, name, action, col in PORTALS:
    holos.append({"pos": [cx + 0.5, 109.6, PZ + 0.5], "scale": 1.3, "text": [{"Farmwelt": "&a&l", "Survival": "&b&l", "Nether": "&c&l", "PvP-Arena": "&6&l"}[name] + name,
                  {"Farmwelt": "&7Ressourcen abbauen", "Survival": "&7Zufälliger Ort", "Nether": "&7Zufälliger Ort", "PvP-Arena": "&7Kämpfen"}[name]]})
holos.append({"pos": [0.5, 109.6, RZ + 0.5], "scale": 1.3, "text": ["&b&lZurück zum Spawn"]})
for cid, (x, y, z) in crate_pos.items():
    pass  # Kisten-Hologramme macht der CrateService

areas = [
    {"name": "&b&lSpawn", "icon": "BEACON", "desc": "&7Plaza mit Brunnen", "pos": [0.5, 101, 12.5, 180, 0]},
    {"name": "&6&lKisten", "icon": "CHEST", "desc": "&7Vote, Daily, Rare, Epic, Legendary", "pos": [0.5, 101, -24.5, 180, 0]},
    {"name": "&a&lMarkt", "icon": "EMERALD", "desc": "&7Shop, Gems, Sterne, Spawner, Aufträge", "pos": [24.5, 101, 0.5, -90, 0]},
    {"name": "&d&lFortschritt", "icon": "NETHER_STAR", "desc": "&7Battlepass, Quests, Skills, Erfolge", "pos": [-23.5, 101, 0.5, 90, 0]},
    {"name": "&9&lClanhalle", "icon": "BLUE_BANNER", "desc": "&7Clans, Kopfgelder, Kits", "pos": [27.5, 101, -27.5, -135, 0]},
    {"name": "&5&lEvent-Platz", "icon": "BELL", "desc": "&7Events, Vote, Kosmetik", "pos": [-27.5, 101, -27.5, 135, 0]},
    {"name": "&3&lPortale", "icon": "OBSIDIAN", "desc": "&7Survival, Farmwelt, Nether, Arena", "pos": [0.5, 101, 24.5, 0, 0]},
    {"name": "&c&lPvP-Arena", "icon": "DIAMOND_SWORD", "desc": "&7Jeder gegen jeden · Duell · Sumo · Kit-Editor", "pos": arena_arrival},
    {"name": "&d&lClan-Kolosseum", "icon": "NETHERITE_SWORD", "desc": "&7Clan-Liga, Rangliste TOP 100", "pos": [KX - 33.5, 101, KZ + 0.5, -90, 0]},
    {"name": "&5&lKristallgruft", "icon": "AMETHYST_CLUSTER", "desc": "&7PvE: Wellen & Boss", "pos": pve_lobby},
]

layout = {
    "version": VERSION,
    "world": "spawn",
    "spawn": [0.5, 101, 12.5, 180, 0],
    "arena-arrival": arena_arrival,
    "void-y": 55,
    "pvp-zones": [{"center": [0.5, AZ + 0.5], "radius": 18.5, "y": [99, 120]}],
    "clear-boxes": [[-112, 60, -112, 152, 150, 108], [-86, 60, AZ - 42, 86, 140, AZ + 42], [GX - 32, 74, -32, GX + 32, 104, 142]],
    "npcs": npcs,
    "holograms": holos,
    "portals": portal_defs,
    "crates": crate_pos,
    "duels": duels,
    "areas": areas,
    "clan-board": clan_board,
    "clan-arenas": clan_arena,
    "sumo": SUMO,
    "points": {"crown-board": [9.5, 103.0, AZ - 34.5], "pve-lobby": pve_lobby},
    "pve": {"arenas": pve_arenas},
}

# ================================================================ Ausgabe
OUT.mkdir(parents=True, exist_ok=True)
W2 = {k: v for k, v in W.items() if v != "air"}


def write_blueprint(blocks, path):
    palette, ids, rows = [], {}, {}
    for (x, y, z), b in blocks.items():
        if b not in ids:
            ids[b] = len(palette); palette.append(b)
        rows.setdefault((y, z), []).append((x, ids[b]))
    lines = [f"#VERSION {VERSION}"]
    lines += [f"#P {i} minecraft:{b}" for i, b in enumerate(palette)]
    count = 0
    for (y, z) in sorted(rows):  # von unten nach oben (Stuetzbloecke zuerst)
        run = None
        for x, pid in sorted(rows[(y, z)]) + [(None, None)]:
            if run and x == run[1] + 1 and pid == run[2]:
                run[1] = x; continue
            if run:
                lines.append(f"{run[2]} {y} {z} {run[0]} {run[1]}"); count += 1
            run = [x, x, pid] if x is not None else None
    data = ("\n".join(lines) + "\n").encode()
    path.write_bytes(gzip.compress(data, 9))
    print(f"{path.name}: {len(blocks)} Bloecke, {len(palette)} Blocktypen, {count} Zeilen, {len(gzip.compress(data, 9)) // 1024} KB")


write_blueprint(W2, OUT / "blueprint.txt.gz")
(OUT / "layout.yml").write_text("# Automatisch erzeugt von tools/build_spawn.py\n" + json.dumps(layout, ensure_ascii=False, indent=1), encoding="utf-8")


# ---------------------------------------------------------------- Karten aus dem Internet (tools/maps/<id>.json)
# Die Hauptinsel kommt dann aus einer Schematic-Datei (wird vom Server von der Seite des Erbauers geladen, nicht mitgeliefert).
# Aus dem eigenen Bauplan bleiben nur die Zusatz-Bauten: Clan-Kolosseum, PvP-Arena mit Platz, Duell-Inseln, Sumo, Grueften.
def extra(x, z):
    if x <= -200:
        return True
    if 74 <= x <= 152 and -42 <= z <= 42:
        return True
    return z >= 83 and math.hypot(x - 58, z - 76) > 10


write_blueprint({k: v for k, v in W2.items() if extra(k[0], k[2])}, OUT / "blueprint-extras.txt.gz")
MAPS = ROOT / "tools/maps"
(OUT / "maps").mkdir(exist_ok=True)
for mf in sorted(MAPS.glob("*.json")) if MAPS.exists() else []:
    m = json.loads(mf.read_text(encoding="utf-8"))
    ox, oy, oz = m["schematic"]["origin"]

    def shift(p):  # Positionen in der Karten-Datei sind relativ zum Ursprung der Schematic angegeben
        return [p[0] + ox, p[1] + oy, p[2] + oz] + list(p[3:])

    lay = {k: v for k, v in layout.items()}
    lay["blueprint"] = "spawn/blueprint-extras.txt.gz"
    lay["schematic"] = m["schematic"]
    lay["spawn"] = shift(m["spawn"])
    lay["npcs"] = [n for n in npcs if extra(n["pos"][0], n["pos"][2])]
    done = {n["id"] for n in lay["npcs"]}
    by_id = {n["id"]: n for n in npcs}
    for nid, pos in m["npcs"].items():
        if nid in done:
            continue
        n = dict(by_id[nid]) if nid in by_id else {"id": nid, "name": pos[5], "desc": pos[6], "item": pos[7], "action": pos[8], "skin": pos[9]}
        n["pos"] = shift(pos[:5])
        lay["npcs"].append(n)
    lay["holograms"] = [h for h in holos if extra(h["pos"][0], h["pos"][2])] + [dict(h, pos=shift(h["pos"])) for h in m.get("holograms", [])]
    lay["portals"] = [p for p in portal_defs if extra(p["box"][0], p["box"][2])] + [
        {"name": p["name"], "action": p["action"], "box": shift(p["box"][:3]) + shift(p["box"][3:6]), "back": shift(p["back"])} for p in m.get("portals", [])]
    lay["crates"] = {k: shift(v) for k, v in m.get("crates", {}).items()}
    lay["areas"] = [dict(a, pos=shift(a["pos"])) for a in m.get("areas", [])] + [a for a in areas if extra(a["pos"][0], a["pos"][2])]
    lay["clan-board"] = shift(m["clan-board"]) if "clan-board" in m else clan_board
    pts = dict(layout["points"])
    for k, v in m.get("points", {}).items():
        pts[k] = shift(v)
    lay["points"] = pts
    lay["clear-boxes"] = layout["clear-boxes"] + [shift(m["box"][:3]) + shift(m["box"][3:6])]
    lay["blocks"] = [shift(b[:3]) + [b[3]] for b in m.get("blocks", [])]   # nach dem Einfuegen setzen (z.B. Platzhalter-Schilder entfernen)
    (OUT / "maps" / (mf.stem + ".yml")).write_text("# Automatisch erzeugt von tools/build_spawn.py aus tools/maps/" + mf.name + "\n" + json.dumps(lay, ensure_ascii=False, indent=1), encoding="utf-8")
    print("Karte", mf.stem, "-", len(lay["npcs"]), "NPCs,", len(lay["holograms"]), "Hologramme")

# Draufsicht
try:
    from PIL import Image
    COL = {"grass_block": (95, 160, 70), "water": (60, 120, 220), "smooth_quartz": (235, 230, 225), "cyan_terracotta": (85, 95, 95),
           "light_blue_terracotta": (115, 110, 140), "polished_andesite": (135, 140, 140), "stone_bricks": (120, 120, 120),
           "dirt_path": (150, 120, 70), "polished_deepslate": (60, 60, 65), "obsidian": (30, 20, 45), "nether_portal[axis=x]": (140, 60, 220)}
    tops = {}
    for (x, y, z), b in W2.items():
        if (x, z) not in tops or y > tops[(x, z)][0]:
            tops[(x, z)] = (y, b)
    xs = [k[0] for k in tops]; zs = [k[1] for k in tops]
    img = Image.new("RGB", (max(xs) - min(xs) + 1, max(zs) - min(zs) + 1), (20, 24, 30))
    for (x, z), (y, b) in tops.items():
        base = b.split("[")[0]
        c = COL.get(b) or COL.get(base)
        if not c:
            h = abs(hash(base)) % 200
            c = (60 + h % 140, 60 + (h * 7) % 140, 60 + (h * 13) % 140)
        shade = 0.75 + (y - 100) * 0.03
        img.putpixel((x - min(xs), z - min(zs)), tuple(max(0, min(255, int(v * shade))) for v in c))
    img = img.resize((img.width * 3, img.height * 3), Image.NEAREST)
    (ROOT / "media").mkdir(exist_ok=True)
    img.save(ROOT / "media/spawn-plan.png")
except ImportError:
    pass
