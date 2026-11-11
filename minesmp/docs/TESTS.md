# Tests

Alle Tests liefen am 05.11.2026 auf einem echten Paper-1.21.11-Server (Build 132) mit Bot-Spielern
(mineflayer 4.39) und RCON. Skripte: `tests/smoke-*.js` (`MC_PORT`, `RCON_PORT`, `RCON_PASSWORD` setzen).

## Ergebnis: bestanden
| Bereich | Test | Ergebnis |
|---|---|---|
| Build | `mvn clean package` (Java 21, 127 Klassen, ~9.500 Zeilen) | ✅ ohne Fehler |
| Start | Paper + LuckPerms + Vault + PlaceholderAPI + MineSMPCore (Java 21) | ✅ keine Exceptions |
| Start | Alle 12 Plugins zusammen (Java 25) | ✅ alle aktiviert, keine Exceptions |
| Start | Java 21 + WorldEdit 7.4.6 / WorldGuard 7.0.17 | ❌ erwartet: brauchen Java 25 (dokumentiert) |
| Install | `scripts/install.sh` laedt Paper + 11 Plugins, baut Plugin, kopiert Configs | ✅ |
| Configs | Teil-Configs `paper-global.yml`, `paper-world-defaults.yml`, `spigot.yml`, `bukkit.yml` werden von Paper ergaenzt | ✅ |
| Setup | Alle Befehle aus `luckperms-setup.txt` + `worldguard-setup.txt` per RCON | ✅ (ein ungueltiges WG-Flag `hunger` gefunden und entfernt) |
| Economy | /money, /bal, /pay, /baltop, /eco give (RCON) | ✅ |
| Shop | /shop GUI oeffnen, Kategorie, Kauf per Klick (Geld abgezogen) | ✅ |
| Verkauf | /sellhand | ✅ |
| Auktionshaus | /ah sell, Kauf durch 2. Spieler mit Bestaetigungs-GUI, Status SOLD in DB | ✅ |
| Homes/TPA | /sethome (in Spawn-Welt korrekt gesperrt, in Survival ok), /home, /tpa + /tpaccept, /spawn | ✅ |
| Clans | create, invite, join, info, bank deposit, /cc; Friendly-Fire-Schutz (kein Schaden unter Clanmitgliedern) | ✅ |
| PvP | Kill mit Todesnachricht, Combat-Tag-Nachricht, /spawn im Kampf gesperrt, Kampf-Logout = Tod + Broadcast | ✅ |
| Anti-Farm | Kill zwischen gleicher IP gibt keine Belohnung | ✅ |
| Duelle | Anfrage, Annahme, Kit, Sieger/Verlierer-Statistik | ✅ |
| Kopfgeld | /bounty set mit Bestaetigung, Broadcast | ✅ |
| Crates | /crate givekey (RCON), /crate open vote mit Animation, Belohnung gutgeschrieben | ✅ |
| Belohnungen | /daily (+ Sperre beim 2. Mal), /quests GUI, /skills GUI | ✅ |
| Events | /eventadmin start mining, /event, /eventadmin stop | ✅ |
| Moderation | /tempmute (RCON) blockiert Chat mit Grund, /report mit Team-Benachrichtigung, /history | ✅ |
| Sicherheit | /pl und /minecraft:op fuer Spieler blockiert; Link-Filter; Chat-Tempo-Limit | ✅ |
| Raenge | LuckPerms-VIP: Prefix im Tab ("VIP \| Name"), /kit vip nur fuer VIP | ✅ |
| Chat | Format `{prefix}{clan}Name » Nachricht` mit Hover/Klick (Paket geprueft) | ✅ |
| Aliase | /tell, /w, /help werden von MineSMP uebernommen | ✅ |
| Anti-Cheat | GrimAC erkennt die Bot-Bewegung (TickTimer) | ✅ (zeigt: Grim aktiv) |
| Speicherung | Nach `stop`: Spieler, Clan, 12 Transaktionen, Mute, Auktion in SQLite vorhanden | ✅ |

## Noch NICHT getestet (ehrlich)
- **Echte Spieler mit echtem Client** (nur Bots) - insbesondere Optik von Scoreboard, Bossbars, GUIs, Hologrammen, Partikeln.
- **MariaDB-Betrieb** (nur SQLite getestet).
- **Last/Performance** mit vielen Spielern (nur 2-3 Bots).
- **Bedrock-Spieler** ueber Geyser/Floodgate (Plugins starten, Verbindung nicht getestet).
- **NuVotifier**-Votes (nur `/fakevote`-Pfad im Code, nicht ausgefuehrt).
- **KOTH, Supply Drop, Schatzsuche, Boss, Turnier** komplett durchgespielt (nur Mining-Wettbewerb gestartet/gestoppt).
- **Farmwelt-Reset** (Erstellung getestet, Reset-Ablauf nicht).
- **Clan-Kriege, Allianzen**, Clan-Level-Up, Clan-Aufloesung.
- **Handel (/trade)**, Spieler-Shops (Schilder), Verkaufsstab, Mine-Hammer, Holzfaeller-Axt, eigene Rezepte im Spiel.
- **Lifesteal** (standardmaessig aus), Battlepass-Abholung, Erfolge-Freischaltung, Haustiere, Spuren.
- **Discord-Webhooks** (kein Webhook konfiguriert).
- **Absturzschutz Duell-Inventar** (Restore nach Crash), Anti-Bot-Sperre bei Join-Flut.
- **Purpur** statt Paper.

## Manuelle Testliste (vor dem Release mit 2 echten Accounts)
1. `/trade <Spieler>`: Items auf beide Seiten, Geld +1000, beide bestaetigen -> Tausch korrekt; Abbruch per ESC -> jeder bekommt seins zurueck.
2. Truhe + Schild `[Shop]` / `1` / `K 10 : V 5` mit Item in der Hand -> Kauf/Verkauf, Fremde koennen Truhe nicht oeffnen, Trichter ziehen nichts.
3. `/clan war <Clan> 1000`, `/clan war accept`, Kills zaehlen in Bossbar, Pot geht an Sieger.
4. `/eventadmin setkoth 1|2`, `/eventadmin start koth`, im Bereich stehen -> Sieg nach 180 s.
5. `/eventadmin start supply` -> Koordinaten im Chat, Kiste oeffnen -> Gewinner.
6. `/eventadmin setboss`, `/eventadmin start boss` -> Bossbar, Top-3-Belohnung.
7. `/eventadmin start turnier` mit 4 Spielern -> K.-o.-Runden ueber Duell-Arena.
8. `/farmwelt reset` -> Spieler zum Spawn, Welt neu.
9. Erz platzieren und abbauen -> keine Skill-Belohnung; natuerliches Erz -> Belohnung (nach 30 s Actionbar).
10. `/msitem <Spieler> hammer` -> 3x3 Abbau, respektiert WorldGuard am Spawn.
11. Server hart beenden (kill -9) waehrend Duell -> beim naechsten Join kommt Inventar zurueck.
12. `/ban`, `/tempban 1h`, `/unban`, `/warn` 3x -> automatischer Mute.

## Update 07.11.2026: Gems, Sterne, Spawner, Auftraege, Grundstuecke, Verzauberungen, Spezial-Items, Keyall, Meteoriten
Testserver wie oben (Paper 1.21.11, Java 25, alle Plugins), zwei Bot-Spieler + RCON.

| Bereich | Test | Ergebnis |
|---|---|---|
| Build/Start | `mvn package`, Server-Start mit allen Plugins | ✅ keine Exceptions |
| Gems/Sterne | `/gems give`, `/sterne give` (RCON), `/gems` | ✅ |
| Menues | `/gemshop`, `/sterneshop`, `/spawner`, `/ce`, `/order`, `/order mine` oeffnen | ✅ |
| Verzauberung | Buch "Vampir I" im Inventar auf ein Diamantschwert gezogen | ✅ "Verzaubert: Vampir I" |
| Grundstueck | `/claim`, `/claim info`, `/claim unclaim`; fremder Spieler baut im Grundstueck ab | ✅ Block bleibt |
| Spawner | Platzieren, mit Schleichen stapeln (12x), fremder Zugriff gesperrt, Drops nach 30 s im Speicher, "Alles verkaufen", fremdes Abbauen gesperrt, Besitzer baut ab und bekommt 12 Spawner zurueck | ✅ |
| Auftraege | `/order create 64 5 dirt` + Bestaetigung, zweiter Spieler liefert (Geld erhalten), Abholen | ✅ |
| Keyall | `/keyall`, `/keyall now` -> Vote-Schluessel + Stern fuer beide Spieler | ✅ (Fehler gefunden und behoben: neue Config-Abschnitte wurden bei alten Installationen nicht ganz gelesen) |
| Mitspieler | `/mitspieler <Text>` -> Nachricht mit [MITMACHEN] beim anderen Spieler | ✅ |
| Meteorit | `/poi start meteor` -> Ort gefunden, Nachricht mit Koordinaten, `/poi`, `/poi stop` | ✅ |

**Nicht getestet** (nur gebaut): Kampfzone erobern, Meteoriten-Kiste pluendern, Staubsauger, Enterhaken,
Wirkung der Verzauberungen im Kampf/beim Abbauen, Raid-Zeit (TNT), Premium auf vollem Server, Kauf im Gem-/Sterne-Shop,
die neuen NPCs am Spawn, die neuen Icons im Ressourcenpaket, Simple Voice Chat. Hinweis: GrimAC sperrt Bot-Aktionen,
die Bots brauchten `grim.exempt`.

## Update 10.11.2026: neue Rang-Abzeichen, Namensschild und Tab-Liste
| Bereich | Test | Ergebnis |
|---|---|---|
| Migration | Start mit alter `ranks.yml`/`scoreboard.yml` -> als `*-alt.yml` gesichert, neue angelegt | ✅ |
| Namensschild | Text des Namensschilds per Bot gelesen: mit Paket `<Edelstein> [<Haken>] <Abzeichen> \| Name` + Zeile `Coins: ..., Gems: ...`; ohne Paket `[✔] ADMIN \| Name` | ✅ |
| Tab-Liste | Team-Praefix pro Betrachter (mit/ohne Ressourcenpaket), Spieler ohne Rang ohne Haken | ✅ |

**Nicht getestet:** Aussehen im echten Minecraft-Client (nur die uebertragenen Texte und Grafiken wurden geprueft).

## Update 10.11.2026: /vanish und /vanishultra
Drei Bot-Spieler (Admin, Supporter, Spieler ohne Rang).

| Test | Ergebnis |
|---|---|
| `/vanish`: Spieler ohne Rang sieht den Admin nicht mehr (weder Figur noch Tab-Liste) und liest `[-] Name` | ✅ |
| `/vanish`: Supporter sieht den Admin weiter; Namensschild und Tab-Eintrag unveraendert (`[✔] ADMIN \| Name`, kein Vanish-Hinweis), Team-Nachricht | ✅ |
| `/vanish`: Unsichtbar-Kennzeichen am Admin gesetzt (eigener Skin weg); beim Supporter steckt der Admin im selben Team (Voraussetzung fuer die durchsichtige Figur) | ✅ |
| `/vanishultra`: Spieler sieht den Admin nicht, Unsichtbar-Kennzeichen aus (eigener Skin sichtbar), Supporter sieht ihn normal | ✅ |
| Ausschalten: Spieler sieht den Admin wieder und liest `[+] Name`; `/vanish list` | ✅ |

**Nicht getestet:** Aussehen im echten Client (durchsichtige Figur beim Team, eigener Skin in F5), Vanish nach Neu-Einloggen,
Haustiere/Spuren im Vanish, Mobs ignorieren Spieler im Vanish.

## Update 10.11.2026: Clan-Liga, PvP-Platz (Sumo, Kit-Editor, Kronen), Kristallgruft (PvE), neue Spawn-Map
Zwei Bot-Spieler + RCON auf dem Testserver. Fuer den Test war die Clan-Liga auf 1 gegen 1 und 1 Platzierungsmatch gestellt.

| Bereich | Test | Ergebnis |
|---|---|---|
| Spawn | Alter Spawn (Bauplan 1) wird beim Start automatisch durch Bauplan 2 ersetzt (398.747 Bloecke in 4 s) | ✅ |
| Clan-Liga | Zwei Clans: Aufstellung per Menue, Match-Suche, Teleport in die Arena mit Kit, Sieg -> Wertung +30/-30, "PLATZIERT" mit Rang, Rangliste zeigt beide | ✅ |
| Clan-Liga | Nach dem Match: beide Spieler wieder am alten Ort, eigenes Inventar unveraendert | ✅ |
| Sumo | `/sumo` fuer beide -> Plattform, Inventar leer; ein Spieler faellt -> der andere gewinnt, beide zurueck mit Inventar | ✅ |
| Kit-Editor | Kit waehlen, zwei Plaetze tauschen, speichern | ✅ |
| Kristallgruft | Kompletter Lauf: 3 Wellen (inkl. "Lichter aus"), Laternenmasten, Boss, Belohnung, Rueckkehr zum Tor | ✅ |
| Kristallgruft | Spieler stirbt zweimal -> "Zweite Chance", danach "Gescheitert in Welle 1 von 4", Rueckkehr | ✅ |

**Nicht getestet:** echtes 3 gegen 3 mit sechs Spielern, Match-Suche mit mehr als zwei Clans, Zeitlimit/Unentschieden, Kronen-Aufstieg,
Duell-Warteschlange mit Kit, Gruppe in der Gruft (Einladen/Annehmen), Schwierigkeit der Gruft mit echten Spielern,
Aussehen von Map, Menues und Liga-Symbolen im echten Client (die Map wurde nur in einem vereinfachten 3D-Betrachter geprueft).

## Update 10.11.2026 (2): Scoreboard im neuen Aufbau, Spawn-Karte "High Crown" aus dem Internet
| Bereich | Test | Ergebnis |
|---|---|---|
| Karte | Schematic (1.16-Format, 190x196x263) wird mit WorldEdit eingefuegt, danach der Zusatz-Bauplan (Kolosseum, Arena, Sumo, Gruft) | ✅ 7 s |
| Karte | Umstieg vom eingebauten Spawn: alter Spawn wird geloescht, neue Karte gebaut | ✅ |
| Stationen | `/spawn` fuehrt auf den Goldblock am Portal; 12 NPCs in Sichtweite; Klick auf NPC oeffnet das richtige Menue | ✅ |
| Kisten | Linksklick auf die Kiste im Pavillon oeffnet "Vorschau: Vote-Crate" | ✅ |
| Portal | Ins Portal laufen -> Zufalls-Teleport in die Survival-Welt; Portal-Bloecke bleiben auch nach Block-Updates stehen | ✅ |
| Aufraeumen | Platzhalter-Schilder des Erbauers und die sechste, unbenutzte Kiste sind entfernt | ✅ |

**Nicht getestet:** der automatische Download der Karten-Datei durch das Plugin auf einem frischen Server (im Test lag die Datei schon im
Ordner; der Download-Link selbst wurde geprueft), jeder einzelne der 26 NPC-Plaetze im echten Client, das neue Scoreboard im echten Client.

## Update 10.11.2026 (3): Navigator-Kompass am Spawn
| Test | Ergebnis |
|---|---|
| Survival-Welt: kein Kompass; eigene Spitzhacke auf Hotbar-Platz 9 bleibt | ✅ |
| Am Spawn: Kompass liegt auf Platz 9, die Spitzhacke wurde ins Inventar verschoben | ✅ |
| Rechtsklick oeffnet den Navigator mit 7 Zielen | ✅ |
| Wegwerfen und Verschieben des Kompasses werden verhindert | ✅ |
| In der PvP-Arena und zurueck in der Survival-Welt: Kompass weg, Spitzhacke wieder auf Platz 9 | ✅ |

## Update 11.11.2026: Spawn-Schutz, Bau-Modus, neue Team-Rechte
| Bereich | Test | Ergebnis |
|---|---|---|
| Spawn-Schutz | Normaler Spieler baut den Goldblock am Spawn ab -> Block ist wieder da, Hinweis in der Leiste | ✅ |
| Spawn-Schutz | Falltuer, Blumentopf, Hebel: Rechtsklick aendert nichts; Hacke auf Gras: bleibt Gras; Schild laesst sich nicht bearbeiten | ✅ |
| Spawn-Schutz | Tuer laesst sich weiter oeffnen | ✅ |
| Bau-Modus | Builder: `/gamemode creative` am Spawn -> eigenes Inventar gesichert, kann abbauen; `/gamemode survival` -> Inventar wie vorher | ✅ |
| Bau-Modus | Builder in der Survival-Welt: `/gamemode creative` nicht erlaubt | ✅ |
| Rechte | Neue Rechte per LuckPerms-Befehlen gesetzt und exportiert (Admin/Entwickler alles, Moderator/Supporter/Builder abgestuft) | ✅ |

**Nicht getestet:** jedes einzelne gesperrte Objekt (Rahmen, Ruestungsstaender, Betten, Kerzen ...), WorldEdit als Builder,
der automatische Rechte-Abgleich durch das Windows-Startskript auf einem bestehenden Server.

## Update 11.11.2026 (2): Winter am Spawn
| Bereich | Test | Ergebnis |
|---|---|---|
| Schnee | Burg-Karte und eingebauter Spawn: Schneedecke auf Boden, Daechern und Baeumen; Wege, Treppen und Wasser bleiben frei | ✅ |
| Schneefall | Wetter in der Spawn-Welt steht auf Niederschlag, Biom ist verschneit (Bot empfaengt Regenstaerke 1) | ✅ |
| Baum | Weihnachtsbaum mit Lichtern, Schnee und 6 Geschenken steht am vorgesehenen Platz (beide Karten) | ✅ |
| Weihnachtsmann | Steht neben dem Baum in roter Kleidung; Klick holt die taegliche Belohnung | ✅ |
| Musik | Glockentoene kommen beim Spieler an: Lautstaerke 0.25 (Zweitstimme 0.10), Kanal "Schallplatten/Jukebox" | ✅ |
| Musik | In `/settings` abgeschaltet -> 40 Sekunden lang kein Ton mehr | ✅ |
| Aus | `/minesmp winter off`: kein Schnee, kein Baum, kein Weihnachtsmann, klares Wetter, keine Musik | ✅ |
| An | `/minesmp winter on` danach: alles wieder da | ✅ |
| Neubau | `/minesmp buildspawn confirm` bei aktivem Winter: Winter wird nach dem Bau automatisch neu eingerichtet | ✅ |

**Nicht getestet:** wie Schneefall, Daemmerung und Musik im echten Minecraft-Client wirken (die Vorschau-Bilder zeigen weder fallenden Schnee
noch die Abendstimmung), ob die Melodien sauber klingen (nur die Tonfolgen wurden geprueft, niemand hat sie angehoert), Bedrock-Spieler,
Serverlast beim Einrichten mit vielen Spielern am Spawn.
