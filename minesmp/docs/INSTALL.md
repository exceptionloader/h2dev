# Installation

## Voraussetzungen
- Linux-Server (Root oder eigener Benutzer), mindestens 8 GB RAM fuer 50-100 Spieler
- **Java 25** (z.B. `sudo apt install temurin-25-jdk` aus dem Adoptium-Repo) - noetig fuer WorldEdit/WorldGuard
- Maven 3.9+ (nur zum Bauen von MineSMPCore), `curl`, `python3`
- Optional: MariaDB 10.6+ fuer grosse Server

## 1. Dateien holen und installieren
```bash
git clone <dieses Repository>
cd h2dev/minesmp
./scripts/install.sh /opt/minesmp        # Zielordner frei waehlbar; BEDROCK=0 ohne Geyser/Floodgate
```
Das Skript laedt Paper 26.3, alle Plugins (passend zu 26.3 von Modrinth), baut `MineSMPCore`
und kopiert `server.properties`, `bukkit.yml`, `spigot.yml` und `config/paper-*.yml` (ueberschreibt nichts).

## 2. Erster Start
```bash
cd /opt/minesmp
nano eula.txt            # eula=true (nach Lesen der Minecraft-EULA)
MEMORY=8G ./start.sh     # am besten in screen/tmux oder als systemd-Dienst
```
Beim ersten Start entstehen: Welt `world` (+Nether/End), die leere Spawn-Welt `spawn` (Plattform bei 0/100/0)
und die Farmwelt `farmwelt`. MineSMPCore legt `plugins/MineSMPCore/*.yml` und die SQLite-Datenbank an.

## 3. Raenge & Schutz einrichten (Server-Konsole)
1. Befehle aus `scripts/luckperms-setup.txt` ausfuehren (Zeile fuer Zeile, ohne Kommentare).
2. Dich selbst zum Owner machen: `lp user DeinName parent set owner`
3. Befehle aus `scripts/worldguard-setup.txt` ausfuehren (Spawn-Welt geschuetzt, PvP aus).

## 4. Spawn (baut sich automatisch)
Beim ersten Start baut MineSMPCore den fertigen Spawn in die Spawn-Welt (ca. 280.000 Bloecke, unter 1 Sekunde):
Plaza mit Leuchtfeuer-Brunnen, Kistenhalle, Markt, Fortschritts-Tempel, Clanhalle, Event-Buehne, vier Portale
(Survival, Farmwelt, Nether, PvP-Arena), Arena-Insel und zwei Duell-Inseln. Spawn-Punkt, Kisten, Duell-Arenen und
Arena-Spawn werden automatisch eingetragen. NPCs (Haendler, Pass-Meister, Questgeberin, ...) oeffnen die Menues per Klick.
Am Spawn sind Abbauen, PvP (ausser in der Arena), Fall- und Hungerschaden aus; wer runterfaellt, landet wieder am Spawn.

| Was | Befehl |
|---|---|
| Spawn neu bauen (z.B. nach Update) | `/minesmp buildspawn confirm` |
| Navigator (alle Bereiche) | `/navi` oder NPC "Navigator" |
| Winter am Spawn an/aus (Schnee, Schneefall, Baum, Musik) | `/minesmp winter on` / `/minesmp winter off` |
| Am Spawn bauen duerfen | Recht `minesmp.spawn.build` (Admins haben es) |
| KOTH-Huegel | Survival-Welt, zwei Ecken: `/eventadmin setkoth 1` und `/eventadmin setkoth 2` |
| Boss-Spawn | `/eventadmin setboss` |
| Welt vorgenerieren | `chunky world world` `chunky radius 5000` `chunky start` (wiederholen fuer farmwelt) |

Der Bauplan wird mit `tools/build_spawn.py` erzeugt (Python + Pillow). Enthaelt die Spawn-Welt schon eigene Bauten
(mehr als 5.000 Bloecke), wird nicht automatisch gebaut.

**Spawn-Karte:** Standard ist `spawn-build.map: auto`. Ist WorldEdit installiert, laedt der Server beim ersten Start die Burg-Insel
"High Crown Medieval Spawn" von OrioniX direkt von [SchemsFactory](https://schemsfactory.com/schematics/high-crown-medieval-spawn/)
(frei nutzbar auf eigenen Servern, die Datei darf aber nicht weitergegeben werden - deshalb liegt sie nicht im Paket) und setzt
NPCs, Kisten, Portal und Rangliste an die vom Erbauer vorgesehenen Plaetze. Klappt der Download nicht, die Datei von der Seite laden,
nach `plugins/MineSMPCore/schematics/high-crown-medieval-spawn.schem` legen und neu starten. Mit `map: builtin` bleibt der eingebaute Spawn.
Eigene Karten: Stationen in `plugins/MineSMPCore/spawn-layout.yml` eintragen (Vorlage: `spawn/maps/high-crown.yml` im Plugin).

**Neuer Bauplan nach einem Update:** Hat das Plugin den Spawn selbst gebaut, baut es bei einem neuen Bauplan automatisch neu
(`spawn-build.auto-update: true`). Dabei wird der alte Spawn-Bereich ueberschrieben - eigene Umbauten am Spawn gehen verloren.
Wer das nicht moechte, setzt `auto-update: false` und baut bei Bedarf mit `/minesmp buildspawn confirm`.

## 5. Optionale Dienste
- **Discord-Webhooks**: URLs in `plugins/MineSMPCore/config.yml` -> `discord.webhooks` eintragen.
- **Votes**: NuVotifier installieren, Vote-Seiten-Links in `config.yml -> vote.links`. Test: `/fakevote <Spieler>`.
- **MariaDB**: In `config.yml` `database.type: mariadb` + Zugangsdaten setzen, Datenbank vorher anlegen:
  `CREATE DATABASE minesmp CHARACTER SET utf8mb4; CREATE USER 'minesmp'@'localhost' IDENTIFIED BY '...'; GRANT ALL ON minesmp.* TO 'minesmp'@'localhost';`
  Hinweis: Es gibt keinen automatischen Umzug der Daten von SQLite nach MariaDB - vor dem Start mit Spielern entscheiden.
- **Bedrock**: Geyser/Floodgate sind installiert (Port 19132/UDP freigeben). Ohne: `BEDROCK=0 ./scripts/install.sh`.

## 6. Backups
`backup.sh` sichert Welten, Plugin-Daten (inkl. SQLite) und optional einen MariaDB-Dump; behaelt 12 Stueck.
```
crontab -e
0 */6 * * * /opt/minesmp/backup.sh >> /opt/minesmp/backups/backup.log 2>&1
```
Fuer MariaDB: `MARIADB_DB=minesmp MARIADB_USER=minesmp MARIADB_PASSWORD=... ./backup.sh`.
Wiederherstellen: Server stoppen, Archiv im Serverordner entpacken (`tar xzf backups/minesmp_DATUM.tar.gz`), starten.
Block-Rollbacks fuer Griefing: CoreProtect (`/co inspect`, `/co rollback u:Spieler t:1h r:20`).

## 7. Raenge vergeben
`lp user Spielername parent set mvp` (Gruppen: owner, admin, developer, moderator, supporter, builder, legende, mvp, vip, default).
Abzeichen, Namensfarben, Namensschild und Partikel stehen in `plugins/MineSMPCore/ranks.yml` (`/minesmp reload`).
Spieler koennen in `/settings` ihr eigenes Namensschild (F5), ihre Partikel und die Partikel anderer ausschalten.
Hinweis: Plugin-Teleports in andere Welten funktionieren mit dem Namensschild, weil MineSMPCore es dafuer kurz abnimmt.
Fremde Plugins, die Spieler per API in andere Welten teleportieren, koennten daran scheitern - dann `nametag.enabled: false`.

## 8. Anpassen
Alle Texte: `plugins/MineSMPCore/messages.yml` (fehlende Schluessel werden automatisch ergaenzt).
Preise: `shop.yml`, Kisten: `crates.yml`, Quests: `quests.yml`, Events/Zeiten: `events.yml`,
Battlepass: `battlepass.yml`, Skills/Belohnungen: `skills.yml`, Scoreboard/Tab: `scoreboard.yml`.
Neu laden: `/minesmp reload` (Nachrichten, Shop, Crates, Kits, Events, Scoreboard). Alles andere: Neustart.

## 9. Update von MineSMPCore
```bash
cd h2dev/minesmp/core && mvn -B clean package
cp target/MineSMPCore-*.jar /opt/minesmp/plugins/   # alte Version vorher loeschen
```
Neue Funktionen aus Updates bringen ihre Standardwerte selbst mit (config.yml, events.yml, custom-items.yml).
Neue Dateien (`token-shops.yml`, `spawners.yml`, `enchants.yml`) werden beim Start angelegt.
Bei einem neuen Design werden `ranks.yml` und `scoreboard.yml` automatisch ersetzt; die alten Dateien bleiben als
`ranks-alt.yml` / `scoreboard-alt.yml` erhalten (eigene Aenderungen von dort uebernehmen).
Fuer den **Premium-Rang** die Rang-Datei neu importieren (`lp import minesmp-raenge`, das Windows-Paket macht das automatisch).

## 10. Sprachchat (optional)
Simple Voice Chat von Modrinth in `plugins/` legen (das Windows-Paket laedt es automatisch). Der Server braucht zusaetzlich
**UDP-Port 24454**, jeder Spieler die Simple-Voice-Chat-Mod im Client (Fabric/NeoForge/Forge).

## 11. Anti-Cheat-Waechter ("KI-System")
Laeuft ohne Einrichtung. Drei Ebenen arbeiten zusammen:

| Ebene | Was sie tut |
|---|---|
| Paper Anti-Xray (`paper-world-defaults.yml`, im Windows-Paket schon aktiv) | versteckte Erze werden gar nicht erst an den Client geschickt - X-Ray zeigt nur Attrappen |
| GrimAC | blockt Fly, Speed, Killaura, Reichweite usw. sofort und meldet sie an den Waechter |
| Waechter (MineSMPCore, `config.yml -> guard`) | sammelt alle Hinweise pro Spieler, warnt das Team und bannt automatisch permanent |

**Was der Waechter misst**
- **X-Ray:** Wie viele *versteckte* Diamant-Adern (Oberwelt, bis Y 16) bzw. Antiker Schrott (Nether) ein Spieler pro abgebautem Block findet.
  Adern, die an eine Hoehle, Wasser oder Lava grenzen, zaehlen nicht. Ehrliches Minen liegt geschaetzt bei 2-9 Adern pro 1000 Bloecke
  (aus den Welt-Generator-Werten gerechnet, nicht auf einem echten Server gemessen), die eingestellte Obergrenze bei 20 (Schrott: 12).
  Erst ab 10 Adern wird bewertet; gebannt wird erst, wenn so viele Funde selbst bei dieser Obergrenze seltener als 1:50.000 Zufall waeren.
- **Zugriffe durch Waende (Freecam/Ghost-Hand):** Kisten, Faesser, Oefen usw. werden geoeffnet, obwohl zwischen Auge und Klickpunkt ein voller
  Block liegt. 4 verschiedene Behaelter in 15 Minuten = Warnung, 10 = Bann. Nicht am Spawn, nicht fuer Bedrock-Spieler.
- **Hack-Clients:** GrimAC-Alarme nach Kategorie. Eine Kategorie allein (z.B. nur Bewegung) reicht nie fuer einen Bann - das Team wird gewarnt.
  Erst zwei unabhaengige Kategorien mit hohen Zaehlern (z.B. Reichweite + Fliegen) fuehren zum Bann.

**Grenzen:** Wer mit Freecam nur *zuschaut*, sendet nichts an den Server - das kann kein Plugin erkennen. Ein vorsichtiger X-Ray-Nutzer mit
unauffaelliger Fundrate faellt statistisch nicht auf; gegen ihn hilft das Paper-Anti-Xray.

| Was | Befehl |
|---|---|
| Uebersicht | `/guard` (auch `/ki`) |
| Werte eines Spielers ansehen | `/guard check <Spieler>` |
| Letzte Faelle / ein Fall mit Beweisen | `/guard cases [Spieler]`, `/guard case <Nr>` |
| Falsch gebannt? | `/unban <Spieler>` - die Zaehler beginnen danach bei null |
| Zaehler eines Spielers leeren | `/guard reset <Spieler>` |
| Nur warnen statt bannen | `config.yml -> guard.action: alert` |
| Jemanden ausnehmen | Recht `minesmp.guard.bypass` (Owner/Admin/Entwickler haben es) |

**GrimAC-Anbindung:** Beim ersten Start traegt MineSMPCore in `plugins/GrimAC/punishments.yml` je Gruppe eine Zeile
`guard grim %player% %check_name% %vl%` ein (Sicherung: `punishments.yml.minesmp-backup`) und laedt GrimAC neu. Abschalten: `guard.grim.auto-setup: false`.

**Echte KI-Gegenpruefung (optional, kostenpflichtig):** Mit einem API-Schluessel von Anthropic (console.anthropic.com) prueft das Modell
Claude jeden Fall, bevor gebannt wird - gebannt wird dann nur, wenn Statistik *und* KI zustimmen.
1. `config.yml -> guard.ai.enabled: true` und `api-key: 'sk-ant-...'` eintragen, dann `/minesmp reload`.
2. `/guard aitest` zeigt, ob die Verbindung steht.

Uebertragen werden nur Messwerte (keine Namen, UUIDs oder IPs). Standard-Modell ist `claude-opus-5-5`; lehnt das Modell eine Anfrage ab,
uebernimmt automatisch ein Ausweichmodell von Anthropic (`fallbacks`). Ist die KI nicht erreichbar, wird nicht gebannt, sondern das Team
gewarnt (`guard.ai.on-error`). Die KI-Bibliothek (ca. 45 MB) laedt der Server beim ersten Start selbst aus Maven Central.

## 12. Report-System
| Wer | Was |
|---|---|
| Spieler | `/report` (auch `/melden`, `/bugreport`) -> Menue: **Spieler melden** oder **Bug melden**. Offline-Spieler: `/report <Name>` |
| Team (Recht `minesmp.staff.reports`, ab Supporter) | Chat-Nachricht bei jedem neuen Report mit **[Annehmen]** und **[Ablehnen]**; `/report` zeigt zusaetzlich alle offenen Reports; Hinweis beim Einloggen |

- **Annehmen** teleportiert zum gemeldeten Spieler (bei Bugs zum Melder). Ist er offline, landet man am Ort des Reports.
  Wer `/vanish` darf, wird dabei unsichtbar (`reports.vanish-on-accept`).
- **Ablehnen** schliesst den Report. Der Melder bekommt in beiden Faellen eine kurze Nachricht.
- Ein Spieler kann hoechstens 3 offene Reports haben (`reports.max-open`) und denselben Spieler nicht doppelt melden; Wartezeit zwischen zwei Reports: `cooldowns.report`.
- Gruende, Bug-Kategorien und Symbole stehen in `config.yml -> reports`. Mit `text: true` schreibt der Spieler den Grund danach in den Chat (die Nachricht ist fuer andere nicht sichtbar).

## 13. 3D-NPCs und Bosse
Das Windows-Paket laedt **BetterModel 3.6.0** mit (kostenlos, MIT-Lizenz). Beim Start legt MineSMPCore die 14 Modelle nach
`plugins/BetterModel/models`, BetterModel baut daraus ein Ressourcenpaket (`plugins/BetterModel/build.zip`) und MineSMPCore
schickt es zusammen mit dem eigenen Paket an jeden Spieler (zwei Pakete, das unterstuetzt Minecraft seit 1.20.3).

| Was | Wo |
|---|---|
| Welcher NPC welche Figur traegt | `config.yml -> models.npcs` (z.B. `shop: haendler`) |
| Bosse: Leben, Schaden, Groesse, Faehigkeiten | `plugins/MineSMPCore/bosses.yml` (danach `/minesmp reload`) |
| Reihenfolge im Boss-Event | `events.yml -> events.boss.bosses` und `boss-order: rotation` oder `random` |
| Bestimmten Boss sofort starten | `/eventadmin start boss granitfuerst` (Boss-Spawn vorher mit `/eventadmin setboss` setzen) |
| Gruftwaechter-Modell | `config.yml -> pve.boss.model` |
| Eine Boss-Faehigkeit sofort ausloesen (Test/Vorfuehren) | `/eventadmin cast <faehigkeit>` waehrend des Boss-Events |
| 3D-Modelle ganz aus | `config.yml -> models.enabled: false` oder BetterModel entfernen |

Ohne BetterModel funktioniert alles weiter: NPCs tragen die Fantasy-Skins (aus dem MineSMP-Ressourcenpaket), Bosse erscheinen
als vergroessertes Monster (Verwuester, Hexe, Eisengolem, Spinne) mit denselben Faehigkeiten.

**Sichtlinie:** BetterModel schickt Animationen nur an Spieler, die das Modell sehen koennen (`plugins/BetterModel/config.yml -> sight-trace`).
Hinter Waenden steht ein Modell still und bewegt sich wieder, sobald man es sieht - das ist so gewollt und spart Leistung.

**Hinweise:** Die Modelle sind fuer Java gebaut; Bedrock-Spieler (Geyser) sehen wahrscheinlich nur das Grundwesen oder nichts -
das ist nicht getestet. Wer das Ressourcenpaket ablehnt, sieht statt der Fantasy-Skins ein Fehlermuster. Die Bosse sind auf
etwa ein Drittel der Originalgroesse gesetzt (8-11 Bloecke hoch); `model-scale` in `bosses.yml` aendert das.
Die Original-Pakete fuer Bedrock (`.mcaddon`) gehoeren nicht zum Server - Bedrock-Welten koennen sie direkt importieren.

## 14. Update von Minecraft 1.21.11 auf 26.3
Das Windows-Paket erledigt das beim naechsten Start von "MineSMP starten.bat" selbst:
1. Es sichert die Welten (`world`, `world_nether`, `world_the_end`, `spawn`, `farmwelt`) nach `backups\vor-26.3-<Datum>`.
2. Es laedt Paper 26.3 und die Plugins in der passenden Version neu (LuckPerms, PlaceholderAPI, WorldEdit, WorldGuard, GSit, Voice Chat).
3. Paper baut beim ersten Start die Speicherstruktur der Welten um (alles liegt danach unter `world/dimensions/minecraft/...`)
   und wartet dabei 30 Sekunden - das ist normal. Der gebaute Spawn, Grundstuecke, Clans, Raenge und Geld bleiben erhalten.

Spieler brauchen danach **Minecraft Java 26.3**. Aeltere Versionen koennen nur mit dem Plugin ViaBackwards beitreten
(nicht im Paket enthalten). CoreProtect fehlt, bis es eine Version fuer 26.3 gibt (siehe PLUGINS.md).
Zurueck auf 1.21.11 geht nur mit der Sicherung - eine umgebaute Welt laesst sich nicht mehr mit 1.21.11 oeffnen.

## 15. Drachen-Ausruestung ("Obsidian & Sturm")
10 Teile mit eigenen 3D-Modellen (stecken im MineSMP-Ressourcenpaket, kein Zusatz-Plugin noetig):

| Teil | Faehigkeit |
|---|---|
| Donnerklinge | Treffer: Blitz-Effekt + 4 Zusatzschaden, 8 s Abklingzeit |
| Frostbogen | Pfeiltreffer: Langsamkeit II fuer 3 s, 4 s Abklingzeit |
| Titan-Spitzhacke | 3x3 Stein/Erze, Eisen/Gold/Kupfer sofort geschmolzen |
| Sturmaxt | Schleichen + Abbauen: bis 12 Stammbloecke (in Reichweite) |
| Terra-Schaufel | 3x3 Erde/Sand/Kies |
| Lebenssichel | reife Feldfruechte werden neu gesaet |
| Helm / Panzer / Beinschutz / Stiefel | Nachtsicht / Dornen / Flinkes Schleichen / Tempo I - alle 4: Feuerresistenz + Resistenz I |

**So kommt man dran**
- **Kaufen:** `/schmiede` oeffnet die Drachen-Schmiede (MineCoins). Preise in `token-shops.yml -> shops.drachen`.
- **Gewinnen:** Boss-Event - Platz 1 bekommt sicher ein zufaelliges Teil + 2 Drachenkerne, Platz 2 zu 50 %, Platz 3 zu 25 % (+1 Kern),
  alle anderen Teilnehmer zu 5 %. Einstellen in `events.yml -> events.boss.rewards` (`drachen-loot: 0.5` = 50 %), welche Teile wie oft
  in `config.yml -> drachen.loot-pool`. Gewinne werden im Chat angesagt.
- **Bauen:** Drachenkern + passendes Netherit-Teil (Bogen: normaler Bogen) + Drachenatem in der Werkbank. Den Kern gibt es beim
  Boss-Event, im Shop, vom Enderdrachen oder aus 4 Amethyst + 4 Echoscherben + 1 Netheritbruchstueck (`/schmiede rezepte`).

Bestehende Server bekommen den Shop und die Boss-Beute beim ersten Start automatisch eingetragen (eigene Einstellungen bleiben).
Faehigkeiten, Abklingzeiten und Effekte: `config.yml -> drachen` (z.B. `affect-players: false` = Blitz/Frost nur gegen Monster).
Spieler: `/schmiede modus` (3x3 an/aus), `/schmiede schmelzen` (Autoschmelze an/aus), `/schmiede info`. Admins: `/msitem <Spieler> drachen_helm`.
3x3-Abbau und Baumfaellen laufen ueber normale Abbau-Ereignisse - Grundstuecke, Spawnschutz und WorldGuard greifen fuer jeden Block.

**Bedrock:** Mit Geyser kopiert das Setup `drachen.json` nach `plugins/Geyser-Spigot/custom_mappings` und `DrachenSMP-Bedrock.mcpack`
nach `plugins/Geyser-Spigot/packs`. In der Geyser-Konfiguration muessen eigene Inhalte erlaubt sein (`enable-custom-content: true`).
Bedrock ist nicht getestet.

## 16. Grosse Schilder am Spawn
Ueber dem Vorhof schwebt ein grosses MINESMP-Logo (32 Bloecke breit, schwebt langsam auf und ab), ueber den Bereichen haengen
grosse Schilder: MARKT, HANDEL, KISTEN, FORTSCHRITT, KRISTALLGRUFT. Es sind Pixel-Grafiken aus dem MineSMP-Ressourcenpaket
(eigene Schrift `minesmp:signs`), angezeigt mit Text-Displays - ohne Zusatz-Plugin, ohne Bloecke. Sie erscheinen von selbst,
der Spawn muss nicht neu gebaut werden.

| Was | Wo |
|---|---|
| Ein/aus, Schweben, Sichtweite | `config.yml -> spawn-signs` (`enabled`, `bob`, `view-range`) |
| Position, Groesse, Ausrichtung | Spawn-Layout, Liste `signs`: `pos` = Unterkante Mitte (+ Blickrichtung), `width` in Bloecken, `billboard: fixed` (fest) oder `vertical` (dreht sich zum Spieler) |
| Eigene Texte/Farben | `tools/build_signs.py` aendern, dann `python3 tools/build_pack.py` und Plugin neu bauen |

Unter den grossen Schildern zeigen die Hologramme nur noch die Beschreibung (z.B. "Shop · Verkauf · Gems ..."), der Titel
steht ja schon gross darueber. Wer das Ressourcenpaket ablehnt, sieht statt der Schilder Kaestchen.

## 17. Schatzkisten und Schatz-Token
Im **Nether** und in der **Farmwelt** erscheinen Schatzkisten in der Naehe von Spielern (24-110 Bloecke entfernt, an der
Oberflaeche und in Hoehlen). Sie glitzern leicht, wenn man naeher als 24 Bloecke ist, und zerfallen nach 45 Minuten.
Rechtsklick oder Abbauen oeffnet sie: 2-4 normale Sachen und mit Glueck ein Bonus (selten 35 %, episch 10 %, legendaer 2 %).
Inhalt: `plugins/MineSMPCore/treasure.yml`, Menge und Abstand: `config.yml -> treasure`.

**Schatz-Token:** extrem selten in einer Schatzkiste. Wer ihn findet, bekommt sofort
- **7 Tage alle Crates gratis** - ohne Schluessel, so oft man will (`/keys` oder die Kisten am Spawn),
- **7 Tage MVP** (`lp user <Name> parent addtemp mvp 7d accumulate`, ein hoeherer Rang bleibt),
- eine Andenken-Muenze und Feuerwerk. Ein zweiter Token verlaengert um weitere 7 Tage.

**Der Fund-Moment (wie in Fortnite):** Beim Finder leuchtet die Kiste golden und wackelt 3 Sekunden immer staerker (Toene
steigen), dann eine Gold-Explosion: die Token-Muenze fliegt gross ins Bild, der goldene Schriftzug **SCHATZ-TOKEN** springt
auf, darunter "DU HAST IHN GEFUNDEN!", ueber der Hotbar die Vorteile. Danach oeffnet sich das Kisten-Menue mit der Beute.
**Alle Spieler** sehen oben in der Mitte 15 Sekunden lang eine goldene Leiste "<Name> hat den SCHATZ-TOKEN gefunden!"
(Schriftzug schimmert, Balken laeuft ab, Dauer: `treasure.token.announce-seconds`). `/schatz admin token <Spieler>`
spielt denselben Moment vor dem Spieler ab. ([Muenze](../media/token-muenze.jpg), [Schriftzug](../media/token-titel.jpg), [Leiste](../media/token-leiste.jpg))

**So selten ist er** (n = Kisten seit dem letzten eigenen Token, Einstellungen `config.yml -> treasure.token`):

| Kisten | Chance je Kiste | Schon gefunden (Summe) |
|---|---|---|
| 1-149 | 0 % | 0 % |
| 150-599 | 0,06 % | 300: 8,7 % · 500: 19 % |
| 600-1499 | steigt bis 2 % | 800: 37 % · 1000: 64 % · 1200: 91 % |
| 1500 | sicher | 100 % |

Im Schnitt nach etwa **840 Kisten**. Serverweit gibt es hoechstens **2 Tokens in 7 Tagen**; ist die Grenze erreicht, kommt
keiner, der eigene Zaehler laeuft aber weiter. Mit `/schatz admin chance` rechnet der Server die Tabelle mit den aktuellen
Einstellungen aus.

| Befehl | Zweck |
|---|---|
| `/schatz` | eigene Zahlen (Kisten, seit dem letzten Token, Tokens), Token-Restzeit |
| `/schatz admin spawn [n]` | Kisten in deiner Naehe setzen |
| `/schatz admin list` / `clear` | aktive Kisten anzeigen / entfernen |
| `/schatz admin token <Spieler>` | Token vergeben (z.B. Gewinnspiel) |
| `/schatz admin reset <Spieler>` | Token-Zeit und Zaehler zuruecksetzen |
| `/schatz admin chance` | Fundwahrscheinlichkeit mit den aktuellen Einstellungen |

Schatzkisten erscheinen nicht auf Grundstuecken, nicht in Lava oder Wasser und nicht ueber dem Nether-Dach; Explosionen
zerstoeren sie nicht.

## 18. Raenge Media und Famous
Zwei neue Raenge fuer Creator: **Media** (lila) und **Famous** (gold). Beide glaenzen: ueber das Abzeichen wandert ein
Lichtstreifen und der Name schimmert auf dem Namensschild und in der Tab-Liste; dazu Funkel-Partikel. Rechte wie MVP.
Vergeben: `lp user <Name> parent add media` bzw. `famous`. Reihenfolge: ueber Premium, unter Builder.
Bestehende Server: Das Windows-Paket laedt die Gruppen beim naechsten Start einmalig nach, und `ranks.yml` bekommt die
beiden Raenge automatisch (eigene Aenderungen bleiben). Linux: Befehle aus `scripts/luckperms-setup.txt`.
