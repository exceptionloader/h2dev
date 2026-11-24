# 🎄 Christmas Shop für Roblox (Luau)

Komplett animierter Weihnachts-Shop: Shop-Button mit Schlitten-Flug, Shop-Fenster mit drei Bereichen, „Santa's Sack"-Gacha mit Tipp-Minispiel, zwei Gamepasses, drei Cash-Pakete und Geschenke an Freunde. Die Server-Seite ist autoritativ und gegen Exploits abgesichert. Die gesamte GUI wird per Code gebaut, in Studio musst du nichts von Hand zusammenklicken.

---

## 1. Dateien und genaue Explorer-Orte

| Datei im Repo | Objekt in Studio | Typ |
|---|---|---|
| `src/ReplicatedStorage/ShopConfig.lua` | `ReplicatedStorage > ShopConfig` | ModuleScript |
| `src/ReplicatedStorage/UIAnimations.lua` | `ReplicatedStorage > UIAnimations` | ModuleScript |
| `src/ServerScriptService/ShopService/init.server.lua` | `ServerScriptService > ShopService` | **Script** |
| `src/ServerScriptService/ShopService/PlayerData.lua` | `ServerScriptService > ShopService > PlayerData` | ModuleScript |
| `src/ServerScriptService/ShopService/GiftInbox.lua` | `ServerScriptService > ShopService > GiftInbox` | ModuleScript |
| `src/ServerScriptService/ShopService/RateLimiter.lua` | `ServerScriptService > ShopService > RateLimiter` | ModuleScript |
| `src/StarterPlayerScripts/ShopController/init.client.lua` | `StarterPlayer > StarterPlayerScripts > ShopController` | **LocalScript** |
| `src/StarterPlayerScripts/ShopController/UIKit.lua` | `… > ShopController > UIKit` | ModuleScript |
| `src/StarterPlayerScripts/ShopController/Toasts.lua` | `… > ShopController > Toasts` | ModuleScript |
| `src/StarterPlayerScripts/ShopController/FriendPicker.lua` | `… > ShopController > FriendPicker` | ModuleScript |
| `src/StarterPlayerScripts/ShopController/SackMinigame.lua` | `… > ShopController > SackMinigame` | ModuleScript |

So sieht das im Explorer aus:

```
ReplicatedStorage
├─ ShopConfig            (ModuleScript)
├─ UIAnimations          (ModuleScript)
└─ ChristmasShopRemotes  (Folder, wird vom Server automatisch erstellt)
ServerScriptService
└─ ShopService           (Script)
   ├─ PlayerData         (ModuleScript)
   ├─ GiftInbox          (ModuleScript)
   └─ RateLimiter        (ModuleScript)
StarterPlayer
└─ StarterPlayerScripts
   └─ ShopController     (LocalScript)
      ├─ UIKit           (ModuleScript)
      ├─ Toasts          (ModuleScript)
      ├─ FriendPicker    (ModuleScript)
      └─ SackMinigame    (ModuleScript)
```

### Installation

**Mit Rojo:** Im Ordner `christmas-shop` `rojo serve` ausführen und in Studio mit dem Rojo-Plugin verbinden. `default.project.json` legt alles an die richtigen Stellen.

**Ohne Rojo (von Hand):** Lege die Objekte genau wie in der Tabelle oben an. Achte auf die Typen: `ShopService` ist ein *Script*, `ShopController` ein *LocalScript*, alles andere sind *ModuleScripts*. Dann kopierst du den Inhalt der jeweiligen Datei hinein. Die Module müssen **Kinder** von `ShopService` bzw. `ShopController` sein.

---

## 2. IDs, die du eintragen musst (alle in `ShopConfig`)

Lege sie unter *Creator Dashboard → dein Spiel → Monetization* an. Solange eine ID `0` ist, wird der Kauf in Studio simuliert. Auf Live-Servern erscheint dann „isn't available yet".

**Developer Products** (je ein normales Produkt + ein Geschenk-Produkt zum gleichen Preis):

| Key | Feld | Name-Vorschlag | Preis |
|---|---|---|---|
| `Sack1` | `Id` / `GiftId` | Santa's Sack x1 / Gift: Santa's Sack x1 | 99 R$ |
| `Sack5` | `Id` / `GiftId` | Santa's Sack x5 / Gift: … | 449 R$ |
| `Sack10` | `Id` / `GiftId` | Santa's Sack x10 / Gift: … | 799 R$ |
| `Cash25K` | `Id` / `GiftId` | +25K Cash / Gift: … | 99 R$ |
| `Cash100K` | `Id` / `GiftId` | +100K Cash / Gift: … | 249 R$ |
| `Cash500K` | `Id` / `GiftId` | +500K Cash / Gift: … | 799 R$ |

**Gamepasses:**

| Key | Feld | Was | Preis |
|---|---|---|---|
| `Speed` | `Id` | Gamepass „2x Speed" | 199 R$ |
| `Speed` | `GiftId` | **Developer Product** „Gift: 2x Speed" | 199 R$ |
| `Luck` | `Id` | Gamepass „2x Luck" | 249 R$ |
| `Luck` | `GiftId` | **Developer Product** „Gift: 2x Luck" | 249 R$ |

Gamepasses kann man in Roblox nicht direkt verschenken. Deshalb läuft das Verschenken über ein Developer Product, und der Beschenkte bekommt den Vorteil dauerhaft in seinen Spielstand gespeichert.

Insgesamt sind das **14 Developer Products + 2 Gamepasses = 16 IDs**. Jede ID darf nur einmal vorkommen, sonst warnt der Server im Output.

> ⚠️ `ShopService` setzt `MarketplaceService.ProcessReceipt`. Das darf es pro Spiel nur **einmal** geben. Wenn dein Spiel schon andere Developer Products verkauft, häng sie in `ProcessReceipt` an der Stelle mit dem Kommentar „Not one of ours" an.

---

## 3. Bilder (`ShopConfig.Icons`)

Lade die Bilder als *Image/Decal* hoch und trag die **Image**-ID als `"rbxassetid://123…"` ein. Solange eine ID `rbxassetid://0` ist, zeigt der Shop ein Emoji als Ersatz. Lichtstrahlen und Schneewolken werden ohne Bild prozedural gezeichnet. Alles funktioniert also sofort.

| Key | Bild-Prompt |
|---|---|
| `SantaSleigh` | Cartoon Santa Claus waving from a red and gold sleigh full of presents, thick black outline, glossy mobile game icon, transparent background |
| `SantaSack` | Big red Santa sack overflowing with colorful wrapped presents, tied with a gold rope, thick black outline, glossy cartoon game icon, transparent background |
| `CandyCane` | Red and white striped candy cane, glossy highlight, thick black outline, cartoon mobile game icon, transparent background |
| `Present` | Green gift box with a big shiny red ribbon bow, isometric 3/4 view, thick black outline, glossy cartoon game icon, transparent background |
| `Stocking` | Red Christmas stocking with white fluffy cuff stuffed with green cash bills, thick black outline, glossy cartoon game icon, transparent background |
| `GoldenSnowflake` | Chunky golden snowflake with glossy shine, thick dark outline, cartoon mobile game icon, transparent background |
| `Skates` | Pair of red ice skates with white angel wings, motion lines, thick black outline, glossy cartoon game icon, transparent background |
| `Clover` | Shiny golden four-leaf clover with a small holly sprig and red berries, thick black outline, glossy cartoon game icon, transparent background |
| `CashSmall` | Small stack of green cash bills tied with a red ribbon, thick black outline, glossy cartoon game icon, transparent background |
| `CashMedium` | Open red gift box overflowing with green cash bills and gold coins, thick black outline, glossy cartoon game icon, transparent background |
| `CashLarge` | Huge red sack bursting with green cash bills and gold coins, thick black outline, glossy cartoon game icon, transparent background |
| `Snowman` | Cute snowman waving with one stick arm, red scarf and red bobble hat, thick black outline, cartoon game sticker, transparent background |
| `ChristmasTree` | Small Christmas tree with red baubles, golden star and snow on the branches, thick black outline, cartoon game sticker, transparent background |
| `JingleBells` | Two golden jingle bells with a red bow and holly leaves hanging from a short garland, thick black outline, cartoon sticker, transparent background |
| `HollyCorner` | Holly leaves with three red berries, corner decoration, thick black outline, cartoon sticker, transparent background |
| `Gift` | Small red gift box icon with a white ribbon, flat glossy cartoon UI icon, thick black outline, transparent background |
| `LightRays` *(optional)* | Radial golden sunburst light rays, soft edges fading out, centered, transparent background, game UI effect |
| `Snowflake` | Simple white snowflake particle, soft glow, transparent background, game UI particle |
| `Sparkle` | White four-point sparkle star with soft glow, transparent background, game UI particle |
| `SnowCloud` *(optional)* | Puffy white cartoon snow cloud with light blue shading, thick soft outline, transparent background, game UI sticker |

Tipp: Generiere die Bilder quadratisch (z. B. 512×512) mit transparentem Hintergrund. Die Icons werden mit `ScaleType.Fit` eingepasst.

---

## 4. In Studio testen

1. **Ohne IDs (sofort):** Einfach *Play* drücken. Shop-Button links oder Taste **B** öffnet den Shop. Jeder Kauf wird simuliert (`StudioSimulatePurchases = true`), also ohne Robux und ohne Dialog, und durchläuft trotzdem den kompletten Server-Pfad inklusive Sack-Minispiel.
2. **Speichern testen:** *Game Settings → Security → Enable Studio Access to API Services* einschalten. Ohne diese Einstellung läuft der Shop mit einem Offline-Profil, nichts wird gespeichert, und im Output steht eine Warnung.
3. **Mit echten IDs:** Kaufdialoge in Studio sind Testkäufe und kosten keine Robux. `ProcessReceipt` läuft dabei ganz normal.
4. **Geschenke testen:** *Test → Clients and Servers → 2 Players → Start*. Die Testspieler sind keine echten Freunde, also für den Test `ShopConfig.RequireFriendshipForGifts = false` setzen und danach wieder auf `true`.
5. **Reduced Motion:** Setz `ShopConfig.ReducedMotion = true` oder gib dem Player das Attribut `ReducedMotion = true`. Die Roblox-Einstellung „Reduce Motion" wird ebenfalls beachtet.

### Offline-Tests der Server-Logik

`tests/server_harness.lua` simuliert die Roblox-Dienste und testet 59 Regeln rund ums Kaufen:

- Käufe werden nie doppelt gutgeschrieben.
- Bei einem DataStore-Ausfall kommt `NotProcessedYet`, beim nächsten Versuch die Gutschrift.
- Sack-Ziehungen werden verdeckt und nach dem Minispiel bzw. nach einem Timeout aufgedeckt.
- Geschenke kommen auch über Server hinweg an.
- Ungültige Eingaben werden abgelehnt, das Rate-Limit greift.
- Session-Lock und Shutdown funktionieren.

```bash
LUAU=/pfad/zu/luau ./tests/run.sh   # Luau-CLI: github.com/luau-lang/luau/releases
```

---

## 5. Einstellungen und Anpassungen (`ShopConfig`)

- `ToggleKey` (Standard **B**) und `GamepadToggleKey` (**Y**) öffnen bzw. schließen den Shop.
- **Belohnungen** (`Loot[*].Cash`): Platzhalter sind Candy Cane 1.000 / Present 10.000 / Stocking 50.000 / Golden Snowflake 250.000 plus Chat-Ankündigung.
- **2x Luck** (`LuckMultiplier`) verschiebt die Chancen von 45/30/20/5 % auf etwa 33/28/30/9 %. Spieler mit dem Pass sehen die neuen Prozente direkt im Shop (in Gold).
- `SackTapsRequired` (7), `DailyResetHourUTC` (Countdown-Ziel), `BaseWalkSpeed` / `SpeedMultiplier`, `CurrencyName` und `StartingCash` lassen sich frei einstellen.
- **Eigenes Währungs-/Datensystem:** `PlayerData` hat eine kleine API (`load`, `get`, `waitFor`, `release`, `profile:Save()`, `profile:HasReceipt/AddReceipt`). Wenn du die beibehältst und intern auf dein System umleitest, funktioniert der Rest unverändert.

## 6. So funktioniert's (kurz)

- Der Client **fragt** nur (`PurchaseRequest`). Der Server prüft alles, öffnet den Kaufdialog und vergibt in `ProcessReceipt` die Belohnung. `PurchaseGranted` kommt erst, nachdem gespeichert wurde.
- Sack-Ergebnisse würfelt der Server (`Random.new()`) und schickt sie **vor** dem Minispiel an den Client. Das Minispiel ist reine Optik. Das Cash ist sofort gespeichert, erscheint aber erst im Leaderboard, wenn das Minispiel fertig ist (spätestens nach 25 s).
- Geschenke landen in einer DataStore-„Inbox" des Empfängers. Der Server des Empfängers holt sie ab, speichert sie erst ins Profil und löscht sie dann aus der Inbox. Es geht nichts verloren, und nichts wird doppelt vergeben.
- Jedes Remote hat ein Rate-Limit und prüft seine Eingaben.
