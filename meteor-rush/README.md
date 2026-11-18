# ☄️ METEOR RUSH – Roblox Game

Meteore krachen auf eine Insel. Du schnappst dir den leuchtenden Kern, trägst ihn in deine Basis und er produziert jede Sekunde Geld. Und die anderen Spieler können ihn dir **klauen**.

Das Spiel mischt die Mechaniken, die auf Roblox gerade am besten funktionieren, zu einem eigenen Konzept:

| Mechanik | Warum Spieler bleiben |
|---|---|
| 🎲 **Rarity-System** (7 Stufen von Common bis Cosmic + Mutationen Golden/Rainbow/Void) | „Nur noch ein Meteor, vielleicht kommt ein Cosmic!“ |
| 🦹 **Stehlen & Verteidigen** (Base-Lock, Star Bonker) | Drama und Rache sorgen für Freunde-Einladungen und Clips |
| 💰 **Idle-Einkommen + Offline-Einnahmen** | Spieler kommen zurück, um ihr Geld abzuholen |
| ☄️ **Server-Events** (Meteor Shower, Lucky Skies, Golden Hour, Cosmic Storm) | FOMO: man bleibt im Server, bis das Event kommt |
| 🌌 **Rebirth, Upgrades, Index, Daily Streak, Codes** | Langzeit-Ziele |
| 🛒 **Monetarisierung** (4 Gamepasses + 4 Dev-Products, z. B. eine *server-weite Meteor Shower mit deinem Namen*) | Käufe, die alle im Server sehen |

---

## 🚀 Installation (5 Minuten)

### Variante A: Datei öffnen (am einfachsten)
1. **Roblox Studio** öffnen → *File → Open from File* → `MeteorRush.rbxlx` auswählen.
2. Auf **Play** drücken. Die Insel wird beim Start automatisch generiert (Terrain, Basen, Deko, Licht).

### Variante B: Mit Rojo (für Entwickler)
```bash
rojo serve        # dann im Studio-Plugin "Connect" klicken
# oder
rojo build -o MeteorRush.rbxlx
```
Wenn du Code in `src/` änderst, kannst du die Datei auch ohne Rojo neu bauen:
```bash
python3 tools/build_place.py
```

### ⚙️ Pflicht-Einstellungen in Studio
1. **File → Publish to Roblox** (das Spiel muss veröffentlicht sein, damit es speichern kann)
2. **Game Settings → Security → „Enable Studio Access to API Services“** einschalten (sonst wird nichts gespeichert)
3. **Game Settings → Places → Max Players = 8** (es gibt 8 Basen pro Server)
4. **Game Settings → Avatar → R15**
5. Prüfen: `Lighting.Technology = Future` (ist in der .rbxlx schon gesetzt) und `Workspace.StreamingEnabled = false`
6. Optional für schönes Gras: `Workspace → Terrain → Decoration = true`

---

## 💸 Geld verdienen: Gamepasses & Produkte einrichten

1. Im **Creator Hub** → dein Spiel → *Monetization*:
   - **Passes** erstellen: `VIP`, `Auto Collect`, `Super Luck`, `Rocket Boots`
   - **Developer Products** erstellen: `Meteor Shower`, `Lucky Meteor`, `Cash Pouch`, `Cash Vault`
2. Die IDs in `src/ReplicatedStorage/Shared/Config.luau` eintragen (bei `Config.GamePasses` / `Config.Products` → `Id = 0` ersetzen).
3. Preise legst du im Creator Hub fest; der Shop zeigt automatisch den echten Preis.

Empfohlene Startpreise: VIP 249 R$, Auto Collect 149 R$, Super Luck 299 R$, Rocket Boots 99 R$, Meteor Shower 99 R$, Lucky Meteor 149 R$.

> **Testen in Studio:** Solange eine ID `0` ist, zeigt der Shop in Studio „TEST (Studio)“. Damit kannst du jeden Pass bzw. jedes Produkt gratis ausprobieren. Im Live-Spiel funktioniert das nicht.

---

## 🎮 Steuerung

| Taste | Aktion |
|---|---|
| **E** (halten) | Core greifen / klauen / eigenen Core aufheben / verkaufen |
| **Klick** mit Star Bonker | Spieler umhauen, damit er seinen Core fallen lässt |
| **M** | Shop · **U** Upgrades · **Esc** Menü schließen |
| 🔒 rotes Pad in der Basis | Basis 60 s sperren (Schutzkuppel) |
| 💰 grünes Pad | Geld einsammeln |

---

## 🎨 Grafik

Alles wird prozedural erzeugt, es werden **keine Asset-IDs** gebraucht:

- **Future Lighting** mit Atmosphere, Bloom, SunRays, Color Grading, Depth of Field, Wolken und einem Tag/Nacht-Zyklus (Nacht mit Glühwürmchen)
- **Smooth-Terrain-Insel** mit Hügeln, Bergen und Schneegipfeln, Stränden, Ozean, Kopfsteinpflaster-Wegen und Lava-Kratern
- **Schwebende Inseln**, ein rotierender **Asteroidengürtel** und ein **Meteor-Monument** mit Orbit-Ringen im Zentrum
- Bäume, Palmen, leuchtende Kristallbäume, Felsen und Straßenlaternen
- Meteore mit Feuer, Rauch, Funken, Trail, Warnkreis, Schockwelle, Trümmern und Kamera-Shake
- Cores als Kristall-Cluster mit Glow, Partikeln, Orbit-Ringen (ab Epic) und Lichtsäulen (ab Legendary)
- Eine komplette UI mit Gradients, Animationen, Toasts, Event-Banner und Ladebildschirm

**Noch mehr Wow (optional):** Musik- und Sound-IDs aus der Toolbox in `Config.Sounds` eintragen, eine eigene Skybox einsetzen und ein starkes **Thumbnail und Icon** machen. Das Thumbnail ist der wichtigste Faktor für die Klickrate!

---

## 📈 Tipps für viele Spieler

Ehrlich gesagt: Kein Code garantiert Spielerzahlen. Die Mechaniken oben sind bewährt, aber auf Roblox entscheidet vor allem, ob Leute das Spiel überhaupt anklicken. Was wirklich hilft:

1. **Thumbnail und Icon**: grell, ein riesiger leuchtender Meteor, ein Spieler, der klaut, große Zahlen („$1,000,000/s“)
2. **Titel mit Hook**, z. B. `☄️ Meteor Rush [STEAL!]`. Bei Updates die Tags `[UPDATE]` oder `[EVENT]` hinzufügen
3. **Regelmäßige Updates** (alle 1–2 Wochen): neue Cores, neue Events, neue Codes. Das hält dich im Algorithmus
4. **Sponsored Ads / Ads Manager** am Anfang mit kleinem Budget, damit der Algorithmus genug Daten bekommt
5. **TikTok und Shorts**: Clips von Diebstählen und Cosmic-Drops verbreiten sich gut
6. **Roblox-Gruppe und Discord** für Codes und Update-Ankündigungen
7. Werte in `Config.luau` balancen: sind die Upgrades zu teuer, springen Leute ab, sind sie zu billig, ist das Spiel schnell durchgespielt

---

## 📁 Struktur

```
src/
├─ ReplicatedStorage/Shared/
│  ├─ Config.luau        ← ALLES einstellbar: Cores, Preise, Events, IDs, Codes
│  ├─ CoreBuilder.luau   ← 3D-Modell der Cores
│  └─ Util.luau
├─ ServerScriptService/Server/
│  ├─ init.server.luau   ← startet alle Services
│  ├─ WorldBuilder       ← Terrain, Licht, Plaza, Deko
│  ├─ PlotService        ← Basen, Sockel, Lock, Collect
│  ├─ CoreService        ← Greifen, Tragen, Klauen, Platzieren, Verkaufen
│  ├─ MeteorService      ← Meteor-Spawns & Rarity-Würfel
│  ├─ EconomyService     ← Geld, Upgrades, Rebirth, Daily, Codes
│  ├─ EventService       ← Server-Events
│  ├─ CombatService      ← Star Bonker
│  ├─ MonetizationService← Gamepasses & Dev Products (sicheres ProcessReceipt)
│  ├─ DataService        ← Speichern/Laden mit Retries & Autosave
│  └─ LeaderboardService ← globales Top-10-Board
└─ StarterPlayer/StarterPlayerScripts/Client/
   ├─ init.client.luau
   ├─ Interface.luau     ← komplettes HUD & Menüs
   └─ Effects.luau       ← Meteore, Explosionen, Animationen
```

Aktuelle Codes: `LAUNCH`, `METEOR`, `STARFALL` (in `Config.Codes` änderbar)
