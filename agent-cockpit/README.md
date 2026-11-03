# H2 Agent Cockpit

Eine Web-App, die die sieben KI-Agenten-Dashboards aus dem TikTok-Edit nachbaut, und zwar als Programm, das wirklich läuft. Kein Build, keine Abhängigkeiten: `index.html` im Browser öffnen.

```bash
cd agent-cockpit
python3 -m http.server 8080   # oder index.html direkt doppelklicken
# → http://localhost:8080
```

## Prinzip

**LLM schreibt · Engine entscheidet · Code handelt · Mensch gibt frei.**
Entscheidungen trifft billiger, nachvollziehbarer Code mit einer Konfidenz. Nur über dem Gate handelt Code automatisch. Text schreibt ein LLM nur, wenn er gebraucht wird. Alles mit Außenwirkung (senden, posten, unterschreiben) braucht einen Menschen.

## Module

| # | Modul | Was es kann | Echt oder simuliert |
|---|---|---|---|
| 01 | **Pipeline** | 6 Worker arbeiten einen Abhängigkeits-Graphen ab (kb.index, vector.db, lead.json, webhook, deploy). Zwei Tore: Preis setzen, Angebot freigeben. | Ablauf-Simulation, Tore echt interaktiv |
| 02 | **Trading-Desk** | Agenten-Kette Watcher → Analyst → Risiko → Gate → Executor → Reporter, optional mit menschlicher Freigabe. Orderbuch, Volatilität, Evidenz-Matrix, Depot, Risiko-Anzeige. | Simulierte Kurse, Papiergeld. Keine Anlageberatung |
| 03 | **Inbox-Router** | Lokaler Klassifikator (Phishing, Lead, Antwort lohnt, Rechnung, Newsletter, Risiko) mit Konfidenz und Gate. Lernt aus deinen Korrekturen. Eigene Mails testen. | Klassifikator echt, Mail-Stream simuliert |
| 04 | **Entscheider** | 12 Anwendungsfälle (E-Mail, YouTube, X-Post, Leads, Verträge …). Mehrere „Köpfe“ bewerten, die Engine wählt, Leitplanken greifen. Erzeugt `chief.py`. | Echte Regel-Engine, Köpfe per Stichwort |
| 05 | **Schwarm** | Ziel → Entscheidungs-Layer (Opus) → 3 Leads (Sonnet) → bis zu 300 Agenten (Haiku) → Merge. Kostenvergleich gegen „alles auf Opus“. | Ausführung simuliert, Kosten nach Listenpreis |
| 06 | **Kosten-Labor** | Rechner Hauptmodell vs. Subagenten, Live-Session, und Generator für eine echte `.claude/settings.json`. | Rechner + Settings echt, Session simuliert |
| 07 | **Second Brain** | CLAUDE.md-Werkstatt: Dateien bearbeiten, simulieren was Claude Code beim Start lädt (CLAUDE.md, CLAUDE.local.md, `@imports`, `.claude/rules/` mit `paths:`, Auto-Memory erste 200 Zeilen), Audit, Setup-Skript. | Lade-Regeln nach offizieller Doku, Audit echt |

## Mit Claude

Wird die Seite als claude.ai-Artifact geöffnet, erscheinen Knöpfe mit **Claude**: Mails prüfen, Köpfe bewerten und Entwürfe schreiben, Schwarm-Pläne erzeugen, CLAUDE.md prüfen. Lokal im Browser bleiben diese Knöpfe ausgeblendet, alles andere funktioniert offline.

## Preise (Kosten-Labor, Schwarm)

Anthropic-API-Listenpreise in $ pro 1 Mio. Tokens, Stand Okt 2026 (im Kosten-Labor anpassbar):

| Modell | Input | Output |
|---|---|---|
| Fable 5.1 | 10,00 | 50,00 |
| Opus 5.5 | 4,00 | 20,00 |
| Sonnet 5.5 | 2,00 | 10,00 |
| Haiku 5.5 | 0,10 | 0,50 |

## settings.json-Schlüssel

Der Generator nutzt nur Schlüssel aus der Claude-Code-Doku: `model`, `env.CLAUDE_CODE_SUBAGENT_MODEL`, `env.CLAUDE_CODE_SUBAGENT_MODEL_FORCE` (ab v2.1.257), `env.CLAUDE_CODE_EFFORT_LEVEL` (`low` · `medium` · `high` · `xhigh` · `max`).

## Aufbau

```
agent-cockpit/
├── index.html        Shell, Tabs, globale KPIs
├── css/cockpit.css   Design-Tokens, Panels, Responsive
└── js/
    ├── core.js       Helfer, Router, Render-Loop, Charts, Claude- und Download-Brücke
    ├── pipeline.js   01
    ├── trading.js    02
    ├── inbox.js      03
    ├── decide.js     04
    ├── swarm.js      05
    ├── costlab.js    06
    └── brain.js      07
```

Nur das sichtbare Modul animiert. `prefers-reduced-motion` drosselt die Animationen. Entwürfe (Second Brain, gelernte Wörter) liegen im `localStorage` des Browsers.
