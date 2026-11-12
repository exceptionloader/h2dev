# Plugins & Versionen

Stand: 11.11.2026. Zielversion **Minecraft Java 26.3** mit **Paper** (Build 249).
Die Versionen unten wurden zusammen auf einem Paper-26.3-Testserver gestartet (Java 25, siehe [TESTS.md](TESTS.md)), sofern nicht anders vermerkt.

| Plugin | Version (getestet) | Aufgabe | Quelle |
|---|---|---|---|
| Paper | 26.3-249 | Server-Software (Purpur ist moeglich, wurde aber nicht getestet). Beim ersten Start nach 1.21.11 baut Paper die Speicherstruktur der Welten um (`world/dimensions/...`) - vorher sichern (das Windows-Setup macht das selbst) | papermc.io |
| **MineSMPCore** | 1.0.0 | Eigenes Plugin: Economy (MineCoins, Gems, Sterne), Shop, Gem-/Sterne-Shop, AH, Kaufauftraege, Spawner, Grundstuecke mit Raid-Zeit, eigene Verzauberungen, Spezial-Items, Keyall, Meteoriten/Kampfzonen, Mitspieler-Suche, Handel, Spieler-Shops, Clans, PvP, Duelle, Kopfgelder, Kits, Crates, Skills, Quests, Erfolge, Battlepass, Events, Scoreboard/Tab/Chat, Homes/TPA/RTP, Strafen, Reports, Audit-Log, Webhooks | `core/` |
| LuckPerms | 5.5.71 | Raenge & Rechte | Modrinth |
| Vault | 1.7.3 | Economy-/Chat-Schnittstelle (MineCoins fuer andere Plugins, Prefixe) | GitHub |
| PlaceholderAPI | 2.12.3 | Platzhalter `%minesmp_...%` fuer andere Plugins | Modrinth |
| WorldEdit | 7.4.6 | Bauen, Regionen markieren; fuegt die Spawn-Karte (Schematic) ein. Auf 26.3 setzt es Betten aus Schematics nicht (Meldung `Unsupported key: minecraft:bed`); MineSMPCore setzt die 31 Betten der Burg danach selbst | Modrinth |
| WorldGuard | 7.0.21 | Spawn-Schutz, PvP-Zonen/Safezones | Modrinth |
| CoreProtect CE | - | Block-Logging & Rollbacks. **Fuer 26.3 gibt es noch keine Version**: 24.1 meldet "Minecraft 26.3 is not supported" und schaltet sich ab. Das Setup laedt CoreProtect automatisch, sobald es eine 26.3-Version auf Modrinth gibt (einfach neu starten) | Modrinth |
| GrimAC | 2.3.74 | Anti-Cheat; meldet Alarme an den Waechter von MineSMPCore (Eintrag in `punishments.yml` erfolgt automatisch). Auf 26.3 gestartet; das Zusammenspiel mit dem Waechter wurde unter 1.21.11 getestet | Modrinth |
| GSit | 3.8.0 | /sit, /lay, /crawl | Modrinth |
| Chunky | 1.5.66 | Welt vorgenerieren | Modrinth |
| BetterModel | 3.6.0 (paper) | Zeigt die 3D-Modelle der NPCs und Bosse (Blockbench) ueber Display-Entities und baut dafuer ein eigenes Ressourcenpaket (MineSMPCore schickt es als zweites Paket mit). Auf Paper 26.3 getestet (Protokoll-Ebene, siehe TESTS.md). Braucht Java 25 | Modrinth |
| Simple Voice Chat | 2.6.25 (bukkit) | Optional: Sprachchat in der Naehe. Spieler brauchen die Client-Mod, Server braucht UDP-Port 24454. Auf Modrinth fuer Paper 26.3 gelistet, mit MineSMP **nicht** zusammen getestet | Modrinth |
| Geyser + Floodgate | 2.12.1 (Build 1299) | Optional: Bedrock-Spieler (Drachen-Items: `custom_mappings/drachen.json` + `packs/DrachenSMP-Bedrock.mcpack` liegen bei). Auf 26.3 **nicht** getestet | geysermc.org |
| NuVotifier | (manuell) | Optional: Vote-Belohnungen | GitHub |

## Wichtig: Java-Version
**Minecraft 26.3, WorldEdit 7.4.6 und WorldGuard 7.0.21 benoetigen Java 25** (im Test mit Java 21 nicht ladbar:
`UnsupportedClassVersionError`, Klassenversion 69). Deshalb: **Java 25 verwenden** (z.B. Eclipse Temurin 25).
MineSMPCore selbst ist fuer Java 21 kompiliert und laeuft auf Java 21 und 25.

## Bewusst NICHT verwendet (vermeidet doppelte Funktionen)
- **EssentialsX**: Homes, TPA, Spawn, Back, Msg, AFK, Kits, Economy etc. sind in MineSMPCore enthalten.
  EssentialsX wuerde Befehle (/money, /home, /msg ...) doppelt belegen.
- **Skript**: Der Auftrag nannte Skript fuer einfache Funktionen. Alles ist stattdessen in einem Java-Plugin umgesetzt:
  eine Codebasis, keine doppelten Datenhaltungen, asynchrone Datenbank, keine zusaetzliche Abhaengigkeit.
  Die im Auftrag skizzierte `plugins/Skript/scripts/*.sk`-Struktur entfaellt daher.
- **TAB / Scoreboard-Plugins / DecentHolograms**: Scoreboard, Tablist, Nametags und Crate-Hologramme (TextDisplay) macht MineSMPCore.
- **LiteBans/AdvancedBan**: Ban/Mute/Warn/Kick mit Verlauf ist eingebaut.

## Weitere Abhaengigkeiten von MineSMPCore
Werden beim ersten Start automatisch von Maven Central geladen (Paper-Library-Loader, siehe `plugin.yml`):
HikariCP 6.2.1, sqlite-jdbc 3.47.1.0, mariadb-java-client 3.5.1.
