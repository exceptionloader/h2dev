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
Das Skript laedt Paper 1.21.11, alle Plugins (passend zu 1.21.11 von Modrinth), baut `MineSMPCore`
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
