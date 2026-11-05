# MineSMP

Deutscher Minecraft-Survival-Server (Java 1.21.11, Paper) - **minesmp.de**.
Eigenes Kern-Plugin **MineSMPCore** + bewaehrte Plugins (LuckPerms, WorldGuard, CoreProtect, GrimAC, GSit ...).
Farbschema: Tuerkis, Blau, Weiss, Dunkelgrau.

> Schnellstart: `./scripts/install.sh /opt/minesmp` -> [docs/INSTALL.md](docs/INSTALL.md)

## Funktionen (MineSMPCore)
- **Survival**: Spawn-Welt (Void, geschuetzt), Survival-Welt, Farmwelt mit automatischem Wochen-Reset, /rtp, Homes, TPA, /back, /spawn, Spieler-Warps, Schlafsystem (30 %)
- **Economy (MineCoins)**: /money /pay /baltop, Vault-Provider, Verdienst durch Mining/Farming/Holz/Fischen/Kampf/Quests, Transaktions-Log
- **Handel**: Server-Shop-GUI mit Suche, /sell /sellhand /sellall, Auktionshaus mit Gebuehr/Steuer/Abholfach, sicherer /trade, Spieler-Shops per Schild, Verkaufsstab (Verkaufskisten)
- **Clans**: Raenge, Einladungen, Bank, Home, Level/XP, Allianzen, Rivalen, Kriege mit Einsatz, Clanchat, Rangliste, GUI, Tags in Chat/Tab/Nametag
- **PvP**: Combat-Tag (Logout = Tod), Killstreaks, Kill-Belohnung mit Farm-Schutz, Kopfgelder, Duelle mit Kits, FFA-Arena, optional Lifesteal
- **Crates**: Vote/Daily/Rare/Epic/Legendary, Roll-Animation, Vorschau mit Chancen, virtuelle + physische Schluessel, Hologramme, Broadcast bei seltenen Gewinnen
- **Fortschritt**: Spielerlevel + 5 Skills, Level-Belohnungen, taegliche/woechentliche Quests, Erfolge, Battlepass (Free/Premium)
- **Events**: KOTH, Supply Drop, Schatzsuche, Mining-/Angel-/Ernte-/Holz-Wettbewerb, Boss, PvP-Turnier, Community-Challenge, saisonale Events, Zeitplan, Rangliste
- **Anzeige**: animiertes Scoreboard, Info-Leiste oben (wechselnde, ablaufende Bossbar), Info-Zeile ueber der Hotbar (Geld, Koordinaten, Richtung, Welt, Uhrzeit), animierte Tablist, Rang-Prefixe (LuckPerms), Nametags, Bossbars, Actionbars, alle Texte in `messages.yml`
- **Komfort**: /trash /craft /ec /repair /msg /r /ignore /profile /stats /playtime /daily /rewards /quests /settings /discord /rules /help /report /staff /vote, AFK-System, Custom Items, eigene Rezepte, Partikel-Spuren, Haustiere
- **Sicherheit/Team**: Ban/Tempban/Mute/Warn (mit Eskalation)/Kick + Verlauf, Reports-GUI, Teamchat, Vanish, Invsee, Audit-Log, Discord-Webhooks, Anti-Spam/Links, Anti-Bot, Schutz vor Namespace-/Plugin-Befehlen, Dupe-Schutz in allen GUIs
- **Technik**: SQLite oder MariaDB (HikariCP), alle DB-Zugriffe asynchron, PlaceholderAPI-Erweiterung `%minesmp_...%`

## Projektstruktur
```
minesmp/
├── core/                 MineSMPCore (Maven, Java 21) - Quellcode + Standard-Configs (src/main/resources)
├── server/               server.properties, bukkit.yml, spigot.yml, config/paper-*.yml (Optimierung, Anti-Xray)
├── scripts/              install.sh, start.sh, backup.sh, luckperms-setup.txt, worldguard-setup.txt
├── tests/                Automatisierte Bot-Smoke-Tests (mineflayer + RCON)
├── tools/                gen_commands.py (erzeugt docs/COMMANDS.md, prueft Befehls-Kollisionen)
├── docs/                 INSTALL, PLUGINS, COMMANDS, PERMISSIONS, TESTS
├── database/             (leer - SQLite liegt zur Laufzeit in plugins/MineSMPCore/minesmp.db)
└── backups/              (Ziel fuer backup.sh)
```
Die Skript-Dateien (`*.sk`) aus dem urspruenglichen Konzept entfallen bewusst - siehe [docs/PLUGINS.md](docs/PLUGINS.md).

## Dokumentation
- [Installation](docs/INSTALL.md) · [Plugins & Versionen](docs/PLUGINS.md) · [Befehle](docs/COMMANDS.md) · [Berechtigungen](docs/PERMISSIONS.md) · [Tests](docs/TESTS.md)

## Wichtige Hinweise
- **Java 25** wird fuer WorldEdit 7.4.6 / WorldGuard 7.0.17 benoetigt.
- Eigene Designs/Texte - keine Inhalte anderer Server uebernommen.
- `minesmp.de` ist als Domain nur geplant; DNS (A-Record + ggf. SRV `_minecraft._tcp`) selbst einrichten.
