# MineSMP

Deutscher Minecraft-Survival-Server (Java 26.3, Paper) - **minesmp.de**.
Eigenes Kern-Plugin **MineSMPCore** + bewaehrte Plugins (LuckPerms, WorldGuard, CoreProtect, GrimAC, GSit ...).
Farbschema: Tuerkis, Blau, Weiss, Dunkelgrau.

> Schnellstart: `./scripts/install.sh /opt/minesmp` -> [docs/INSTALL.md](docs/INSTALL.md)

## Funktionen (MineSMPCore)
- **Fantasy-NPCs und kolossale Bosse**: 9 NPC-Figuren (Runenmagier, Waldhaendler, Moosgoblin, Eisenork, Skelettkrieger, Eisgolem, Glutdaemon, Koenigsritter, Hochkoenig) und 5 Bosse (Vierkoepfiger Weltenbrand, Malvoria, Granitfuerst, Nachtmahr, Glutweberin). Mit dem Plugin BetterModel als animierte 3D-Modelle, ohne als Fantasy-Skins bzw. vergroesserte Monster. Das Boss-Event (Samstag 20 Uhr) nimmt reihum einen der fuenf Bosse - jeder mit eigenen Faehigkeiten (Feuer-/Gift-/Eis-/Schattenatem, Fluch, Totengeister, Erdstoss, Felswurf, Finsternis, Tentakelgriff, Lavanetz, Spinnenbrut ...) und Raserei unter 30 % Leben. Der Gruftwaechter in `/gruft` traegt das Steintitan-Modell. ([NPCs](media/npc-3d-vorschau.jpg), [Bosse](media/bosse-vorschau.jpg))
- **Drachen-Ausruestung "Obsidian & Sturm"**: 4 Ruestungsteile (gehoernter 3D-Helm, Panzer, Beinschutz, Stiefel) und 6 Waffen/Werkzeuge mit eigenen 3D-Modellen im MineSMP-Ressourcenpaket - Donnerklinge (Blitz), Frostbogen (Langsamkeit), Titan-Spitzhacke (3x3 + Schmelzen), Sturmaxt (Baumfaellen), Terra-Schaufel (3x3), Lebenssichel (Neusaat); volles Set = Feuerresistenz + Resistenz I. Kaufen in der **Drachen-Schmiede** (`/schmiede`, MineCoins), gewinnen beim **Boss-Event** (Platz 1 sicher ein Teil), oder bauen aus Drachenkern + Netherit-Teil + Drachenatem. Fuer Bedrock (Geyser) liegen Zuordnung und Bedrock-Paket bei. ([Vorschau](media/drachen-vorschau.jpg))
- **Report-System**: `/report` oeffnet ein Kisten-Menue - Spieler melden (Spieler auswaehlen, Grund anklicken) oder Bug melden (Kategorie anklicken, kurz im Chat beschreiben). Das Team bekommt eine Chat-Nachricht mit den Knoepfen **[Annehmen]** (teleportiert zum Spieler, auf Wunsch unsichtbar) und **[Ablehnen]**; der Melder erfaehrt, was mit seinem Report passiert. Teammitglieder sehen im selben Menue zusaetzlich alle offenen Reports (Linksklick annehmen, Rechtsklick ablehnen). Gruende und Kategorien: `config.yml -> reports`
- **Anti-Cheat-Waechter ("KI-System")**: erkennt Cheater automatisch und bannt sie permanent - X-Ray (statistischer Test der Fundrate versteckter Diamant-/Schrott-Adern, Hoehlenfunde zaehlen nicht), Zugriffe durch Waende (Freecam/Ghost-Hand: Behaelter oeffnen, obwohl ein Block davor ist) und Hack-Clients (Alarme von GrimAC, wird automatisch angebunden). Warnung ans Team ab 60 %, Bann ab 95 % Sicherheit, jeder Fall mit Beweisen gespeichert (`/guard cases`). Optional prueft ein echtes KI-Modell (Claude) jeden Bann gegen. Paper-Anti-Xray ist zusaetzlich aktiv. Reines Zuschauen mit Freecam kann kein Server erkennen. Details: [INSTALL.md](docs/INSTALL.md#11-anti-cheat-waechter-ki-system)
- **Winter am Spawn**: Schneedecke auf Boden und Daechern, Schneefall, Abenddaemmerung, Weihnachtsbaum mit Lichtern und Geschenken, Weihnachtsmann (verteilt die taegliche Belohnung) und leise Weihnachtslieder aus Glockentoenen (Jingle Bells, O Tannenbaum, We Wish You a Merry Christmas, Stille Nacht, Deck the Halls). An/aus mit `/minesmp winter on|off` - "off" entfernt Schnee, Baum und Wetter wieder komplett. Jeder Spieler kann die Musik in `/settings` abschalten. Einstellungen: `config.yml -> winter` ([Vorschau](media/winter-vorschau.jpg))
- **Spawn-Schutz**: normale Spieler koennen am Spawn nichts abbauen oder veraendern (Bloecke sind sofort wieder da; auch Falltueren, Blumentoepfe, Schilder, Rahmen, Werkzeuge, Wachstum, Feuer ...); Tueren und Knoepfe bleiben benutzbar. **Bau-Modus** fuer Builder: Kreativ und WorldEdit nur am Spawn, eigenes Inventar wird gesichert
- **Team-Rechte**: Owner, Admin und Entwickler duerfen alles; Moderator (Strafen, Vanish, Invsee, Events), Supporter (Chat-Moderation, Reports) und Builder (Bauen am Spawn) haben abgestufte Rechte - Tabelle in `docs/PERMISSIONS.md`
- **Navigator-Kompass**: liegt nur am Spawn im letzten Hotbar-Platz (Rechtsklick = Reise-Menue zu Markt, Kisten, Arena, Kolosseum, Gruft ...), verschwindet beim Verlassen; eigenes Item auf dem Platz wird verschoben und zurueckgelegt; abschaltbar in /settings
- **Spawn-Karte aus dem Internet**: mit WorldEdit laedt der Server beim ersten Start die Burg-Insel "High Crown Medieval Spawn" (von OrioniX, [SchemsFactory](https://schemsfactory.com/schematics/high-crown-medieval-spawn/)) und richtet darin alle Stationen ein (NPCs, Kisten, Portal, Rangliste). Die Datei wird nicht mitgeliefert, weil die Weitergabe nicht erlaubt ist. Clan-Kolosseum, PvP-Arena, Sumo, Duell-Inseln und Gruft kommen weiter aus dem eigenen Bauplan. Ohne WorldEdit oder ohne Download: der eingebaute Spawn (`spawn-build.map: builtin`)
- **Eingebauter Spawn**: baut sich selbst (und aktualisiert sich bei neuem Bauplan) - Kristall-Monument ueber dem Brunnen, schwebender MINESMP-Schriftzug, kleine Inseln und Wasserfaelle, Clan-Kolosseum mit Bruecke, PvP-Platz, Sumo-Plattformen, Tor zur Kristallgruft, Plaza, Kistenhalle, Markt, Fortschritts-Tempel, Clanhalle, Event-Buehne, Portale, PvP-Arena, Duell-Inseln; klickbare NPCs fuer Shop, Battlepass, Quests, Kits ...; `/navi`
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
- **Clan-Liga** (/clanduell): gewertete Clan-Duelle 3 gegen 3 im Clan-Kolosseum, Wertung (Elo), 11 Raenge mit eigenen Symbolen, Platzierungsmatches, Aufstellung, Match-Suche, Rangliste (aktuelle + vorherige Saison), Aufstieg/Abstieg, TOP-100-Tafel am Kolosseum
- **PvP-Platz**: Warteschlange fuer Duelle und **Sumo** (/queue, /sumo), **Kit-Editor** (/kiteditor: eigene Anordnung der Kit-Items), **Kronen** nach Siegen (/kronen) mit persoenlicher Tafel, K/D + Ping im Namensschild
- **PvE "Die Kristallgruft"** (/gruft): allein oder zu viert 3 Wellen + Boss - Kreaturen besiegen, Laternenmasten entzuenden (Lichtfresser stoeren), "Lichter aus", Zweite Chance, Belohnungen mit Tageslimit
- **PvP**: Combat-Tag (Logout = Tod), Killstreaks, Kill-Belohnung mit Farm-Schutz, Kopfgelder, Duelle mit Kits, FFA-Arena, optional Lifesteal
- **Crates**: Vote/Daily/Rare/Epic/Legendary, Roll-Animation, Vorschau mit Chancen, virtuelle + physische Schluessel, Hologramme, Broadcast bei seltenen Gewinnen
- **Fortschritt**: Spielerlevel + 5 Skills, Level-Belohnungen, taegliche/woechentliche Quests, Erfolge, Battlepass (Free/Premium)
- **Events**: KOTH, Supply Drop, Schatzsuche, Mining-/Angel-/Ernte-/Holz-Wettbewerb, Boss, PvP-Turnier, Community-Challenge, saisonale Events, Zeitplan, Rangliste
- **Ränge**: eckige Abzeichen (OWNER, ADMIN, ENTWICKLER, MOD, SUPPORT, BUILDER, PREMIUM, LEGENDE, MVP, VIP) als Grafik in Namensschild, Tab-Liste, Chat und Scoreboard; Namensschild über dem Kopf (auch selbst in F5 sichtbar) im Format `◆ [✔] ADMIN | Name [Clan]` mit zweiter Zeile `Coins: … , Gems: …`; Tab-Liste im selben Format; Rang-Partikel (Ring, Heiligenschein, Spirale, Flammenkrone, Funkeln) - alles in `ranks.yml`
- **Anzeige**: animiertes Scoreboard, Info-Leiste oben (wechselnde, ablaufende Bossbar), Info-Zeile ueber der Hotbar (Geld, Koordinaten, Richtung, Welt, Uhrzeit), animierte Tablist, Rang-Prefixe (LuckPerms), Nametags, Bossbars, Actionbars, alle Texte in `messages.yml`
- **Komfort**: /trash /craft /ec /repair /msg /r /ignore /profile /stats /playtime /daily /rewards /quests /settings /discord /rules /help /report /staff /vote, AFK-System, Custom Items, eigene Rezepte, Partikel-Spuren, Haustiere
- **Sicherheit/Team**: Ban/Tempban/Mute/Warn (mit Eskalation)/Kick + Verlauf, Reports-GUI, Teamchat, /vanish (komplett unsichtbar, auch der eigene Skin) und /vanishultra (eigener Skin sichtbar) - das Team sieht sich gegenseitig immer, Invsee, Audit-Log, Discord-Webhooks, Anti-Spam/Links, Anti-Bot, Schutz vor Namespace-/Plugin-Befehlen, Dupe-Schutz in allen GUIs
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
