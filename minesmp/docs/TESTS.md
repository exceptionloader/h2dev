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

## Update 11.11.2026 (3): Anti-Cheat-Waechter ("KI-System")
Alle Tests mit Bots (mineflayer) auf dem Testserver mit echtem GrimAC 2.3.75 - kein echter Hack-Client, kein echter Minecraft-Client.

| Bereich | Test | Ergebnis |
|---|---|---|
| X-Ray | Bot graebt gezielt von Diamant-Ader zu Ader (11 Bloecke Abstand): Warnung ans Team bei Ader 14, permanenter Bann bei Ader 15 | ✅ |
| X-Ray | Ehrlicher Bot: 684 Bloecke Tunnel, 12 Erze in 4 Adern -> keine Warnung, kein Bann | ✅ |
| X-Ray | Rechenkern (Poisson-Test) gegen exakte Rechnung geprueft, 10 Wertepaare identisch | ✅ |
| Durch Waende | 12 frei stehende Faesser geoeffnet -> 0 Treffer; Kisten hinter einer Steinwand: Warnung bei 4, Bann bei 10 | ✅ |
| GrimAC | Meldebefehl wird in 9 Gruppen eingetragen (mit Sicherung), GrimAC laedt neu; echte Grim-Alarme kommen beim Waechter an | ✅ |
| GrimAC | Bot mit Angriffen ausser Reichweite + Fliegen: 65 % -> 90 % -> Bann bei 95 %; Bewegung allein blieb bei einer Warnung | ✅ |
| Bann | Kick mit Grund und Fallnummer, Chat-Meldung "von KI-System gebannt", Eintrag im Strafverlauf, Wiedereinloggen abgewiesen | ✅ |
| Entbannen | `/unban` -> Spieler kann wieder rein, Zaehler stehen auf null (kein sofortiger neuer Bann) | ✅ |
| Team | `/guard`, `/guard check`, `/guard cases`, `/guard case <Nr>` zeigen Werte und gespeicherte Beweise; Zaehler ueberleben den Logout | ✅ |
| KI-Gegenpruefung | Gegen einen nachgebauten KI-Dienst: Anfrage enthaelt Modell, JSON-Schema, Ausweichmodell-Schalter und nur anonyme Messwerte | ✅ |
| KI-Gegenpruefung | KI widerspricht -> kein Bann, Fall gespeichert, Team gewarnt; KI bestaetigt -> Bann; falscher Schluessel / Fehler 500 / Ablehnung -> klare Meldung, kein Bann | ✅ |

**Nicht getestet:** echte Cheat-Clients (X-Ray-Mods, Freecam, Killaura) und echte Spieler - die Schwellen beruhen auf Rechnung und Bot-Laeufen,
Fehlalarme im Alltag sind nicht ausgeschlossen; die KI-Gegenpruefung gegen den echten Anthropic-Dienst (kein Schluessel vorhanden);
Antiker Schrott im Nether (gleicher Code wie Diamanten, aber kein eigener Lauf); Bedrock-Spieler; `/guard reset`.

## Update 11.11.2026 (4): Report-System mit Menue
| Test | Ergebnis |
|---|---|
| `/report` als Spieler: Menue mit "Spieler melden" und "Bug melden"; Spieler auswaehlen -> Grund anklicken -> Report angelegt | ✅ |
| Team bekommt Chat-Nachricht mit den anklickbaren Knoepfen `[Annehmen]` und `[Ablehnen]` (Befehle dahinter ausgelesen und ausgefuehrt) | ✅ |
| Annehmen: Teammitglied wird zum gemeldeten Spieler teleportiert und unsichtbar; Melder bekommt eine Nachricht | ✅ |
| Bug melden: Kategorie -> Beschreibung im Chat; die Beschreibung erscheint bei anderen Spielern nicht im Chat | ✅ |
| `/report` als Teammitglied: gleiches Menue plus Bereich mit allen offenen Reports; Rechtsklick lehnt ab (Report verschwindet), Linksklick nimmt an | ✅ |
| Bereits bearbeiteter Report: zweites Annehmen meldet "wurde schon bearbeitet" | ✅ |
| "Sonstiges" mit eigenem Text, "abbrechen", doppelter Report, Limit von 3 offenen Reports, Hinweis beim Team-Login | ✅ |

**Nicht getestet:** das Aussehen der Menues und das Anklicken der Chat-Knoepfe im echten Client (die Bots haben die Befehle hinter den
Knoepfen ausgefuehrt), mehrere Seiten bei sehr vielen Reports oder Spielern, Bedrock-Spieler, der Discord-Webhook.

## Update 11.11.2026 (5): Fantasy-NPCs und kolossale Bosse
| Bereich | Test | Ergebnis |
|---|---|---|
| Modelle | 14 Blockbench-Modelle umgewandelt (Format, Animationsnamen, 383 schraeg gedrehte Wuerfel in eigene Drehgruppen); Gegenprobe: alle Wuerfel-Eckpunkte vorher/nachher gleich (Abweichung 0) | ✅ |
| BetterModel-Anbindung | Alle 12 genutzten Aufrufe gegen die Klassendateien von BetterModel 3.6.0 geprueft (Namen, Parameter, Rueckgabetypen) | ✅ |
| Ohne BetterModel | Server startet, Meldung "BetterModel ist nicht installiert", Modell-Paket-Adresse antwortet 404, keine Fehler | ✅ |
| NPC-Skins | Ressourcenpaket enthaelt die 9 Skins; zugeordnete NPCs tragen sie (z.B. Navigator = Hochkoenig, Auktionator = Goblin), nicht zugeordnete behalten ihren Skin | ✅ |
| Boss-Event | Alle 5 Bosse starten per `/eventadmin start boss <name>` mit richtigem Grundwesen, Groesse und Bossbar; `events.yml` bestehender Server bekommt die Boss-Liste automatisch | ✅ |
| Faehigkeiten | Alle 15 einzeln ausgeloest (`/eventadmin cast`) und am Testspieler gemessen: Feuer/Frost/Wither/Gift/Finsternis/Schweben/Verlangsamung, Schaden, Totengeister (3), Spinnen (4), Felsbrocken, Feuerbaelle, Giftwolke, Spinnennetze | ✅ |
| Aufraeumen | Nach Ende oder Sieg: keine Diener, Geschosse oder Spinnennetze mehr uebrig | ✅ |
| Sieg | Boss besiegt -> Top-Schaden mit echtem Schaden, Belohnung mit Boss-Namen | ✅ |
| Gefundene Fehler (behoben) | Mehr als 1024 Leben lehnt Minecraft ab (betraf auch den alten Boss mit 1500) -> Leben auf 1024, Schaden passend verkleinert; Boss verschwand, wenn beim Start niemand in der Naehe war -> Gebiet bleibt geladen; Nahkampf des Steintitans zu stark -> eigener Nahkampfwert | ✅ |

**Nicht getestet:** BetterModel selbst - das Starten des heruntergeladenen Plugins war in dieser Arbeitsumgebung nicht erlaubt.
Ob die 3D-Modelle im Spiel richtig aussehen, sich bewegen, wie die Angriffs-Animation wirkt und ob das zweite Ressourcenpaket
bei Spielern ankommt, ist daher offen. Ebenso offen: echte Spieler im Bosskampf (Balance), Bedrock-Spieler, der Gruftwaechter mit Modell,
Leistung bei vielen Spielern.

## Update 11.11.2026 (6): Umstellung auf Minecraft 26.3
Kopie des Testservers (Welten von 1.21.11) mit Paper 26.3 Build 249 und den 26.3-Plugin-Versionen gestartet.
Die Test-Bots koennen nur bis 26.1 und kamen ueber ViaVersion/ViaBackwards 5.12.2 auf den Server (nur auf dem Testserver).

| Bereich | Test | Ergebnis |
|---|---|---|
| Bauen | MineSMPCore gegen die Paper-26.3-Schnittstelle uebersetzt (`mvn -P paper26 compile`): 0 Fehler, nur Hinweise auf veraltete, noch vorhandene Funktionen | ✅ |
| Start | Paper 26.3, LuckPerms, PlaceholderAPI, WorldEdit, WorldGuard 7.0.21, GrimAC 2.3.74, GSit, Chunky, Vault und MineSMPCore starten ohne Fehler | ✅ |
| Welten | Umbau der Speicherstruktur: Oberwelt, Nether, End, Spawn- und Farmwelt uebernommen, gebauter Spawn unveraendert; Spielregeln der Spawn-Welt wirken | ✅ |
| Menues | Alle 27 Menues oeffnen sich (Navigator, Report, Shop, Clan, Battlepass, Spawner ...) | ✅ |
| Bosse | Alle 5 Bosse, alle 15 Faehigkeiten wirken (Feuer, Frost, Gift, Wither, Finsternis, Totengeister, Felswurf, Spinnennetze, Spinnenbrut ...) | ✅ |
| Report | Report per Befehl -> Team-Nachricht mit [Annehmen]/[Ablehnen] | ✅ |
| Spawn | Winter (Schnee, Baum) und NPC-Skins nach dem Umbau vorhanden | ✅ |
| Neubau | Eigener Spawn (133 Blocksorten): 0 unbekannte Bloecke. Burg-Karte: WorldEdit setzt die 31 Betten nicht -> jetzt von MineSMPCore gesetzt, danach alle 3 Stichproben-Betten vorhanden | ✅ |
| Paket | Ressourcenpaket gilt fuer Format 75 bis 97.1 (1.21.11 bis 26.3) | ✅ |
| CoreProtect | 24.1 schaltet sich auf 26.3 ab ("not supported") | ❌ (wartet auf Update) |

**Nicht getestet:** ein echter 26.3-Client (Aussehen von Menues, Scoreboard-Glyphen, Skins), der Anti-Cheat-Waechter mit Bots auf 26.3
(die Bots wurden ueber die Protokoll-Uebersetzung staendig getrennt; gleiche Paper-Funktionen wie unter 1.21.11),
BetterModel, Geyser/Floodgate und Simple Voice Chat auf 26.3, das Windows-Setup selbst (kein Windows vorhanden).

## Update 11.11.2026 (7): BetterModel 3.6.0 auf Paper 26.3
Gleicher 26.3-Testserver, BetterModel 3.6.0 (Modrinth, Pruefsumme geprueft) dazu. Gemessen mit einem Beobachter-Bot, der die
Pakete des Servers mitliest (kein echter Minecraft-Client).

| Bereich | Test | Ergebnis |
|---|---|---|
| Start | MineSMPCore legt die 14 Modelle vor dem Start von BetterModel ab; BetterModel startet (NMS V26_R3), MineSMPCore erkennt es; 0 Fehler im Log | ✅ |
| Paket | BetterModel baut `plugins/BetterModel/build.zip` (Format 97, 3.449 Dateien); MineSMPCore liefert es unveraendert aus (gleiche Pruefsumme) und bietet es jedem Spieler als zweites Paket an | ✅ |
| NPCs | Modellteile haengen an den zugeordneten NPCs (z.B. Navigator/Hochkoenig 11 Teile, Kosmetik/Runenmagier 10), nicht zugeordnete NPCs ohne Modell; NPC-Koerper auf 1,3 vergroessert; Ruhe-Animation laeuft (113 von 264 Teilen bewegt in 4 s) | ✅ |
| Bosse | Grundwesen unsichtbar, Modell dran: Granitfuerst 35, Glutweberin 50, Malvoria 71, Nachtmahr 96, Weltenbrand 118 Teile | ✅ |
| Animation | Ruhe-Animation und Angriffs-Animation beim Ausloesen einer Faehigkeit (z.B. Erdstoss, Feueratem, Fluch, Lavanetz) senden laufend Bewegungsdaten - bei freier Sicht | ✅ |
| Aufraeumen | Nach dem Ende des Boss-Events sind alle Modellteile weg | ✅ |
| Sichtlinie | Ohne freie Sicht (Boss hinter Gelaende) kommen keine Animationsdaten - Absicht von BetterModel (`sight-trace`) | ℹ️ |

**Nicht getestet:** wie die Modelle im echten Client aussehen (Texturen, Groesse, ob alle gedrehten Teile richtig sitzen) - die Bots
koennen die Modelle nicht darstellen. Bedrock-Spieler (Geyser) sehen die Modelle voraussichtlich nicht.

## Update 12.11.2026: Drachen-Ausruestung "Obsidian & Sturm"
26.3-Testserver (Paper Build 249) mit bestehender Welt und alten Einstellungen. Test-Bots (mineflayer ueber ViaVersion), Pruefung per RCON.

| Bereich | Test | Ergebnis |
|---|---|---|
| Update | Bestehender Server: Shop `drachen` in token-shops.yml und Boss-Beute in events.yml automatisch ergaenzt, 11 neue Items ohne eigene Aenderungen zu verlieren; 0 Fehler im Log | ✅ |
| Ressourcenpaket | Ausgeliefertes Paket enthaelt 11 Item-Definitionen `drachen:*`, alle Modelle/Texturen vorhanden, Ruestungs-Aussehen `drachen:obsidian` (humanoid + humanoid_leggings) | ✅ |
| Drachen-Schmiede | `/schmiede` oeffnet das Menue, 11 Teile mit 3D-Modell-Kennung, Kauf mit Bestaetigung zieht 250.000 MC ab und gibt die Donnerklinge | ✅ |
| Items | Alle 11 per `/msitem`, jeweils mit `item_model drachen:<name>`; Panzer/Beinschutz/Stiefel tragen sich in Brust/Beine/Fuesse mit `drachen:obsidian`, Helm auf dem Kopf mit 3D-Modell | ✅ |
| Ruestung | Nur Helm: Nachtsicht; 4/4: Nachtsicht, Tempo, Feuerresistenz, Resistenz; Helm ab: Nachtsicht und Set-Bonus laufen aus | ✅ |
| Titan-Spitzhacke | 9/9 Bloecke einer Wand, Eisen- und Golderz als Barren; `/schmiede modus` aus = nur 1 Block | ✅ |
| Terra-Schaufel | 8 Erdbloecke im 3x3, Stein daneben bleibt | ✅ |
| Sturmaxt | Schleichend 9 von 15 Stammbloecken (alle in 8 Bloecken Reichweite, Grenze 12) | ✅ |
| Lebenssichel | Reifer Weizen sofort neu gesaet (ein Samen aus den Drops verbraucht), unreifer nicht | ✅ |
| Donnerklinge | 1. Treffer: Blitz + 15,3 Schaden; 2. Treffer innerhalb 8 s: 11,3 Schaden, kein Blitz | ✅ |
| Frostbogen | Pfeiltreffer: Langsamkeit II beim Ziel | ✅ |
| Drachenkern | Legt kein Feuer, wird nicht verbraucht | ✅ |
| Rezepte | Kern + Netheritschwert/-helm + Drachenatem = Donnerklinge/Drachenhelm; schon fertiges Drachen-Teil oder normale Feuerkugel = kein Ergebnis; Kern-Rezept (Amethyst/Echo/Netheritbruchstueck) | ✅ |
| Boss-Beute | Granitfuerst mit 3 Spielern besiegt: Platz 1 = 2 Kerne + Obsidian-Drachenstiefel, Platz 2 = Kern + Lebenssichel (50 %), Platz 3 = Kern; Chat-Meldung "erbeutet" | ✅ |

**Gefunden und behoben:** Beim Setzen des Ruestungs-Aussehens setzte Paper den Trage-Platz aller Teile auf "Kopf" zurueck (Panzer,
Hose und Stiefel waeren nur auf dem Kopf tragbar gewesen). Das steckt auch im mitgelieferten DrachenSMP-Plugin 0.1.0. MineSMPCore
uebernimmt jetzt Platz und Sound vom Netherit-Teil und aendert nur das Aussehen.

**Beim Rendern der Galerie gefunden und behoben (Modelle aus dem Paket):** Der Helm haette das Visier im Spiel nach hinten
gezeigt (`display.head` drehte ihn um 180 Grad, obwohl das Visier schon vorne im Modell liegt - Vergleich: Vanilla-Kuerbis,
Gesicht vorne, ohne Drehung). Ausserdem zeigte das Inventar bei allen 11 Teilen die schlichte Rueckseite; der Inventar-Winkel
ist jetzt um 180 Grad gedreht (verzierte Vorderseite sichtbar). Nur die Ressourcen-Dateien geaendert, Bedrock unberuehrt.

**Nicht getestet:** wie die Modelle im echten Client aussehen (Hand, Kopf, Inventar), Bedrock/Geyser-Darstellung, Zusammenspiel mit
anderen Schutz-Plugins als den eingebauten. Testhinweis: Die Bots brauchten fuer Schlaege `grim.exempt` (GrimAC verwirft Treffer von Bots
ohne Bewegungsdaten) - echte Spieler betrifft das nicht.

## Update 12.11.2026 (2): Grosse Schilder am Spawn
26.3-Testserver mit der Burg-Insel "High Crown" (bestehender Spawn, nicht neu gebaut).

| Bereich | Test | Ergebnis |
|---|---|---|
| Ressourcenpaket | 12 Schild-Grafiken in 38 Streifen (alle hoechstens 240 Pixel, passen auf Minecrafts 256er-Schriftseiten), Schrift `minesmp:signs` mit Abstand -1 zwischen den Streifen | ✅ |
| Platz | Hoehenkarte der Insel per Bot vermessen (14.071 Spalten): kein Schild steckt in Daechern oder Tuermen (Logo-Unterkante 17, darunter hoechstens 11; KISTEN nach der Pruefung um 1,5 Bloecke verschoben). Eingebauter Spawn: 9 Schilder gegen den Bauplan geprueft, 0 Ueberschneidungen | ✅ |
| Spawn-Welt | Alle 6 Schilder erscheinen ohne Neubau: richtige Grafik (Schrift `minesmp:signs`), Groesse (Logo 9,1-fach = 32 Bloecke breit), Ausrichtung (fest oder zum Spieler drehend) und Blickrichtung | ✅ |
| Schweben | Logo wechselt alle 2,5 s weich zwischen 0 und 0,35 Bloecken Hoehe | ✅ |
| Vorschau | Welt-Aufnahmen mit eingerechneten Schildern (gleiche Kamera, Verdeckung ueber die Hoehenkarte) | ✅ |

**Nicht getestet:** Darstellung im echten Minecraft-Client (Bots zeigen keine Grafik; die Vorschau ist eingerechnet, nicht aufgenommen),
Bedrock (Geyser zeigt eigene Schriften nicht - Bedrock-Spieler sehen die Schilder voraussichtlich nicht).

## Update 13.11.2026: Schatzkisten, Schatz-Token, Raenge Media und Famous
26.3-Testserver, Test-Bot (mineflayer ueber ViaVersion), Pruefung per RCON.

| Bereich | Test | Ergebnis |
|---|---|---|
| Farmwelt | Bot in der Farmwelt: nach 25 s 3, spaeter 6 Schatzkisten von selbst (u.a. in Hoehlen auf y -2, -35, -41) | ✅ |
| Nether | 3-6 Kisten von selbst (z.B. y 106, unter dem Nether-Dach) | ✅ |
| Oeffnen | Menue "Schatzkiste (Selten)", Inhalt z.B. Eisen 11, Schleimball 3, Diamant 2 bzw. Verzaubertes Buch, Pfeile, Gold, Obsidian; MineCoins direkt gutgeschrieben; Kiste danach weg; Beute nach dem Schliessen im Inventar | ✅ |
| Algorithmus | `/schatz admin chance`: 50 Kisten 0 %, 150: 0,1 %, 300: 8,7 %, 500: 19 %, 800: 36,6 %, 1000: 64,1 %, 1200: 90,6 %, 1500: 100 %, Schnitt 838 | ✅ |
| Token aus der Kiste | (Test-Einstellung 100 %) Ansage fuer alle, Andenken-Muenze, Restzeit; zweiter Token verlaengert auf 14 Tage | ✅ |
| Gratis-Crates | Mit Token: `/keys` zeigt "unbegrenzt", Crate oeffnet ohne Schluessel | ✅ |
| MVP | Neuer Spieler bekommt durch den Token das MVP-Abzeichen (LuckPerms `parent addtemp mvp 7d`) | ✅ |
| Media / Famous | `ranks.yml` eines bestehenden Servers automatisch ergaenzt (vor Premium); Namensschild wechselt durch alle 9 Abzeichen-Bilder (Glanz), Name schimmert (5 verschiedene Farbstaende in 4 s) | ✅ |

**Nicht getestet:** Rechtsklick auf die Kiste mit einem echten Client (der Test-Bot kann ohne Bewegungsdaten keine Bloecke
anklicken, auch keine normalen Kisten - geoeffnet wurde ueber Abbauen, das denselben Weg nimmt), wie der Glanz im echten
Client aussieht, die genaue Ablaufzeit von MVP in LuckPerms (LuckPerms antwortet nicht ueber RCON), Bedrock.
