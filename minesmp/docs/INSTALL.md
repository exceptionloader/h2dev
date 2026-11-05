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

## 4. Spawn bauen und Punkte setzen (ingame als Owner)
| Was | Befehl |
|---|---|
| Spawn-Punkt | `/setspawn` (in der Spawn-Welt) |
| Crate-Kisten | Auf Block schauen: `/crate setblock vote` (daily, rare, epic, legendary) |
| PvP-Arena (FFA) | Region mit `pvp allow` (siehe worldguard-setup.txt), dann `/duel setffa` |
| Duell-Arena(en) | Region mit `pvp allow`, dann `/duel setarena duell1 1` und `/duel setarena duell1 2` |
| KOTH-Huegel | Survival-Welt, zwei Ecken: `/eventadmin setkoth 1` und `/eventadmin setkoth 2` |
| Boss-Spawn | `/eventadmin setboss` |
| Welt vorgenerieren | `chunky world world` `chunky radius 5000` `chunky start` (wiederholen fuer farmwelt) |

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

## 7. Anpassen
Alle Texte: `plugins/MineSMPCore/messages.yml` (fehlende Schluessel werden automatisch ergaenzt).
Preise: `shop.yml`, Kisten: `crates.yml`, Quests: `quests.yml`, Events/Zeiten: `events.yml`,
Battlepass: `battlepass.yml`, Skills/Belohnungen: `skills.yml`, Scoreboard/Tab: `scoreboard.yml`.
Neu laden: `/minesmp reload` (Nachrichten, Shop, Crates, Kits, Events, Scoreboard). Alles andere: Neustart.

## 8. Update von MineSMPCore
```bash
cd h2dev/minesmp/core && mvn -B clean package
cp target/MineSMPCore-*.jar /opt/minesmp/plugins/   # alte Version vorher loeschen
```
