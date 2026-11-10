# MineSMP

Deutscher Minecraft-Survival-Server (Java 1.21.11, Paper) - **minesmp.de**.
Eigenes Kern-Plugin **MineSMPCore** + bewaehrte Plugins (LuckPerms, WorldGuard, CoreProtect, GrimAC, GSit ...).
Farbschema: Tuerkis, Blau, Weiss, Dunkelgrau.

> Schnellstart: `./scripts/install.sh /opt/minesmp` -> [docs/INSTALL.md](docs/INSTALL.md)

## Funktionen (MineSMPCore)
- **Fertiger Spawn**: baut sich selbst - Plaza, Kistenhalle, Markt, Fortschritts-Tempel, Clanhalle, Event-Buehne, Portale, PvP-Arena, Duell-Inseln; klickbare NPCs fuer Shop, Battlepass, Quests, Kits ...; `/navi`
- **Survival**: Spawn-Welt (Void, geschuetzt), Survival-Welt, Farmwelt mit automatischem Wochen-Reset, /rtp, Homes, TPA, /back, /spawn, Spieler-Warps, Schlafsystem (30 %)
- **Economy (MineCoins)**: /money /pay /baltop, Vault-Provider, Verdienst durch Mining/Farming/Holz/Fischen/Kampf/Quests, Transaktions-Log
- **Gems & Sterne**: zweite und dritte Waehrung (Gems aus Store/Votes/Events, Sterne fuer aktive Spielzeit), Gem-Shop (Premium, Raenge, Spawner, Waffen) und Sterne-Shop (Sternen-Spitzhacke, Enterhaken ...)
- **Keyall**: jede volle Stunde Schluessel fuer alle Online-Spieler, Countdown im Scoreboard
- **Spawner**: Spawner kaufen, stapeln (bis 64), Drops + XP landen im Speicher, Alles-verkaufen-Knopf, natuerliche Spawner mit der Spawner-Spitzhacke
- **Kaufauftraege** (/order): Geld hinterlegen, andere liefern aus dem Inventar und werden sofort bezahlt
- **Grundstuecke** (/claim): Chunks sichern, Spieler vertrauen, Clan-Mitglieder automatisch; Raid-Zeit am Wochenende (TNT + Kisten erlaubt)
- **Eigene Verzauberungen** (/ce): Vampir, Donner, Frost, Gift, Henker, Telekinese, Autoschmelze, Ader, Bohrer, Tempo, Nachtsicht, Panzer ... ueber Buecher und Mine-Kristalle
- **Spezial-Items**: Staubsauger, Sternen-Spitzhacke, Spawner-Spitzhacke, Phoenix-Fluegel (unzerstoerbare Elytra), Enterhaken, Donnerklinge, Frostbogen, Blutaxt, Windlaeufer-Stiefel, Mine-Kristalle
- **Meteoriten & Kampfzonen** (/poi): erscheinen stuendlich in der Survival-Welt - Beutekiste mit Waechtern oder Zone halten (PvP)
- **Premium-Rang**: mehr Homes, groessere Clans, Platz auf vollem Server, eigenes Abzeichen; **Mitspieler-Suche** (/mitspieler)
- **Handel**: Server-Shop-GUI mit Suche, /sell /sellhand /sellall, Auktionshaus mit Gebuehr/Steuer/Abholfach, sicherer /trade, Spieler-Shops per Schild, Verkaufsstab (Verkaufskisten)
- **Clans**: Clan-Saison mit Rangliste am Spawn (TOP 100, Rang-Stufen, Siege/Unentschieden/Niederlagen, Countdown, eigener Clan), Raenge, Einladungen, Bank, Home, Level/XP, Allianzen, Rivalen, Kriege mit Einsatz, Clanchat, Rangliste, GUI, Tags in Chat/Tab/Nametag
- **PvP**: Combat-Tag (Logout = Tod), Killstreaks, Kill-Belohnung mit Farm-Schutz, Kopfgelder, Duelle mit Kits, FFA-Arena, optional Lifesteal
- **Crates**: Vote/Daily/Rare/Epic/Legendary, Roll-Animation, Vorschau mit Chancen, virtuelle + physische Schluessel, Hologramme, Broadcast bei seltenen Gewinnen
- **Fortschritt**: Spielerlevel + 5 Skills, Level-Belohnungen, taegliche/woechentliche Quests, Erfolge, Battlepass (Free/Premium)
- **Events**: KOTH, Supply Drop, Schatzsuche, Mining-/Angel-/Ernte-/Holz-Wettbewerb, Boss, PvP-Turnier, Community-Challenge, saisonale Events, Zeitplan, Rangliste
- **Ränge**: eckige Abzeichen (OWNER, ADMIN, ENTWICKLER, MOD, SUPPORT, BUILDER, PREMIUM, LEGENDE, MVP, VIP) als Grafik in Namensschild, Tab-Liste, Chat und Scoreboard; Namensschild über dem Kopf (auch selbst in F5 sichtbar) im Format `◆ [✔] ADMIN | Name [Clan]` mit zweiter Zeile `Coins: … , Gems: …`; Tab-Liste im selben Format; Rang-Partikel (Ring, Heiligenschein, Spirale, Flammenkrone, Funkeln) - alles in `ranks.yml`
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
