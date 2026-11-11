# Plugins & Versionen

Stand: 05.11.2026. Zielversion **Minecraft Java 1.21.11** mit **Paper** (Build 132).
Alle Versionen unten wurden zusammen auf einem Testserver gestartet (Java 25, siehe [TESTS.md](TESTS.md)).

| Plugin | Version (getestet) | Aufgabe | Quelle |
|---|---|---|---|
| Paper | 1.21.11-132 | Server-Software (Purpur ist moeglich, wurde aber nicht getestet) | papermc.io |
| **MineSMPCore** | 1.0.0 | Eigenes Plugin: Economy (MineCoins, Gems, Sterne), Shop, Gem-/Sterne-Shop, AH, Kaufauftraege, Spawner, Grundstuecke mit Raid-Zeit, eigene Verzauberungen, Spezial-Items, Keyall, Meteoriten/Kampfzonen, Mitspieler-Suche, Handel, Spieler-Shops, Clans, PvP, Duelle, Kopfgelder, Kits, Crates, Skills, Quests, Erfolge, Battlepass, Events, Scoreboard/Tab/Chat, Homes/TPA/RTP, Strafen, Reports, Audit-Log, Webhooks | `core/` |
| LuckPerms | 5.5.71 | Raenge & Rechte | Modrinth |
| Vault | 1.7.3 | Economy-/Chat-Schnittstelle (MineCoins fuer andere Plugins, Prefixe) | GitHub |
| PlaceholderAPI | 2.12.3 | Platzhalter `%minesmp_...%` fuer andere Plugins | Modrinth |
| WorldEdit | 7.4.6 | Bauen, Regionen markieren; fuegt die Spawn-Karte (Schematic) ein | Modrinth |
| WorldGuard | 7.0.17 | Spawn-Schutz, PvP-Zonen/Safezones | Modrinth |
| CoreProtect CE | 24.1 | Block-Logging & Rollbacks | Modrinth |
| GrimAC | 2.3.75 | Anti-Cheat; meldet Alarme an den Waechter von MineSMPCore (Eintrag in `punishments.yml` erfolgt automatisch, getestet mit 2.3.75) | Modrinth |
| GSit | 3.8.0 | /sit, /lay, /crawl | Modrinth |
| Chunky | 1.4.40 | Welt vorgenerieren | Modrinth |
| BetterModel | 3.6.0 (paper) | Optional: zeigt die 3D-Modelle der NPCs und Bosse (Blockbench) ueber Display-Entities, baut dafuer ein eigenes Ressourcenpaket. MineSMPCore spricht die Schnittstelle von 3.6.0 an (gegen die Klassendateien geprueft), **auf dem Testserver nicht gestartet** - siehe TESTS.md. Braucht Java 25 | Modrinth |
| Simple Voice Chat | 2.6.27 (bukkit) | Optional: Sprachchat in der Naehe. Spieler brauchen die Client-Mod, Server braucht UDP-Port 24454. Auf Modrinth fuer Paper 1.21.11 gelistet, mit MineSMP **nicht** zusammen getestet | Modrinth |
| Geyser + Floodgate | 2.12.1 / 2.2.5 | Optional: Bedrock-Spieler | geysermc.org |
| NuVotifier | (manuell) | Optional: Vote-Belohnungen | GitHub |

## Wichtig: Java-Version
**WorldEdit 7.4.6 und WorldGuard 7.0.17 benoetigen Java 25** (im Test mit Java 21 nicht ladbar:
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
