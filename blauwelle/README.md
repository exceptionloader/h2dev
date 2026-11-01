# BlauWelle – Vorrangschaltung zum Nachrüsten

Software für die BlauWelle-Box aus dem Businessplan „Blaulicht-Vorrangschaltung“:
Eine Box pro Kreuzung erkennt Einsatzfahrzeuge an **Blaulicht (Kamera)** und
**Martinshorn (Mikrofone)** und fordert beim vorhandenen Ampelsteuergerät
**Grün für die Zufahrt des Fahrzeugs** an. Kein Sender im Fahrzeug, keine
Leitstellen-Anbindung.

```
Kameras (je Zufahrt) ──▶ Blaulicht-Erkennung ─┐
                                              ├─▶ Fusion ─▶ Zustandsautomat ─▶ Relais ─▶ Steuergerät
Mikrofon-Array ──▶ Sirene + Richtung ─────────┘     │              │
                                                    └──── Protokoll (SQLite) ─▶ Dashboard / API
```

## Schnellstart (ohne Hardware)

```bash
pip install numpy
python -m blauwelle simulate          # alle Szenarien
python -m unittest discover -s tests  # 23 Tests
```

Beispielausgabe Szenario `einsatz` (Fahrzeug aus Norden, 54 km/h, Durchfahrt bei 20 s):

```
12.1s  request   nord   konf=0.97        ← ca. 120 m vor der Kreuzung
16.1s  green     nord   {'time_to_green_s': 4.0}
24.3s  release   nord   {'reason': 'fahrzeug_weg'}
```

| Szenario        | Situation                                          | Erwartet           |
|-----------------|----------------------------------------------------|--------------------|
| `einsatz`       | Martinshorn + Blaulicht aus Norden, fährt nach Süden | Grün nur für Nord |
| `heulton`       | Heulton (Sweep) + Blaulicht aus Osten               | Grün nur für Ost  |
| `blaues_schild` | Dauerhaft blaues Schild + konstanter Hupton         | nichts            |
| `nur_sirene`    | Sirene ohne sichtbares Blaulicht (Parallelstraße)   | nichts            |

## So funktioniert die Erkennung

**Ton (`audio.py`)** – Pro 64-ms-Rahmen wird geprüft, ob ein klarer Ton im Bereich
300–2000 Hz dominiert. Eine Sirene liegt vor, wenn über 5 s
- zwei Töne im Verhältnis ~4:3 mehrfach wechseln (deutsches Martinshorn) **oder**
- die Tonhöhe gleichmäßig über > 200 Hz gleitet (Heulton/Yelp).

Konstante Töne (Hupe, Maschine) und Rauschen zählen nicht. Die **Richtung**
kommt aus den Laufzeitunterschieden zwischen 4 Mikrofonen (GCC-PHAT,
10 cm Abstand); in der Simulation liegt sie auf ±1° genau.

**Bild (`vision.py`)** – Je Zufahrt wird nur ein Bildausschnitt (ROI) ausgewertet:
Anteil kräftig blauer Pixel. Treffer nur, wenn dieser Anteil mit 0,8–8 Hz
**blinkt** und das Licht **nicht kleiner wird** (ein wegfahrendes Fahrzeug,
von hinten gesehen, löst nichts aus). Es werden keine Bilder gespeichert.

**Fusion (`fusion.py`)** – Standard: beides muss passen (geometrisches Mittel),
weil nach § 38 StVO Wegerecht nur mit Blaulicht **und** Martinshorn besteht.
Die Sirene zählt voll nur für die Zufahrt, aus deren Richtung sie kommt.
Bestätigt wird nach 1 s über der Schwelle, verworfen nach 3 s darunter.

## Sicherheitslogik (`preemption.py`)

Die Box schaltet **nie selbst Lampen**. Sie schließt nur einen Kontakt am
Vorrang-Eingang des Steuergeräts. Zwischenzeiten, Mindestgrün und der Schutz vor
feindlichem Grün bleiben beim Steuergerät.

| Regel | Standard |
|-------|----------|
| Höchstdauer einer Anforderung, danach Zwangsfreigabe | 60 s |
| Sperre der Zufahrt nach Zwangsfreigabe | 120 s |
| Pause nach jeder Freigabe | 5 s |
| Watchdog: Hauptschleife steht → alles frei | 2 s |
| Fehler am Steuergerät → Zustand FEHLER, alles frei | bis Reset / 300 s |
| Mehrere Zufahrten gleichzeitig | höhere `priority` gewinnt, Konflikt wird protokolliert |

Stromausfall oder Absturz: Relais fallen ab (Schließer), die Ampel läuft normal.
Zusätzlich toggelt die Box einen Heartbeat-Pin für einen externen
Hardware-Watchdog, der die Relais-Versorgung trennen kann.

## Betrieb an einer echten Kreuzung

Hardware-Vorschlag: Raspberry Pi 5 (oder Jetson Orin Nano), 4 Kameras,
4-Kanal-USB-Mikrofon-Array, 4-fach-Relaiskarte, Hardware-Watchdog,
IP66-Gehäuse.

```bash
pip install numpy sounddevice opencv-python-headless gpiozero
cp config/beispiel-kreuzung.json /etc/blauwelle.json   # anpassen!
python -m blauwelle check -c /etc/blauwelle.json
python -m blauwelle run   -c /etc/blauwelle.json -v
```

In der Konfiguration pro Zufahrt: Richtung (`bearing_deg`), Kamera, Bildausschnitt,
Relais-Pin (`output_pin`), optional Grün-Rückmeldung (`feedback_pin`) und Priorität.

**Dashboard:** `http://127.0.0.1:8080/` – Zustand, Erkennung je Zufahrt, letzte
Ereignisse, Knopf „Fehlalarm“ für die Auswertung. Schreibende Aufrufe brauchen
das `api.token`. Fernzugriff nur über VPN.

| Endpoint | |
|---|---|
| `GET /api/status` | aktueller Zustand und Erkennungswerte |
| `GET /api/events?limit=50` | Ereignisprotokoll |
| `GET /api/stats` | Anforderungen, Fehlalarme, Ø Zeit bis Grün |
| `POST /api/events/<id>/feedback` | `{"value": "korrekt" \| "fehlalarm" \| "verpasst"}` |
| `POST /api/reset` | Fehlerzustand zurücksetzen |

## Was noch fehlt, bevor es auf die Straße darf

- **Feldtest mit echten Aufnahmen**: Schwellen anhand echter Sirenen, Blaulichter,
  Nacht, Regen, Gegenlicht nachjustieren. Für Fahrzeugtyp-Erkennung ggf. ein
  kleines neuronales Netz ergänzen.
- **Steuergeräte-Anbindung** mit dem Hersteller abstimmen (OCIT-Outstations oder
  Vorrang-Eingang); `OcitController` ist nur ein Platzhalter.
- **Abnahme** durch Straßenverkehrsbehörde, Prüfung nach RiLSA / DIN EN 12675,
  Datenschutz-Folgenabschätzung, Produkthaftpflicht.
