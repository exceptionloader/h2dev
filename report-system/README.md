# Report-System

Serverübergreifendes Report-System für Minecraft-Netzwerke mit Paper-Servern hinter einem oder
mehreren BungeeCord-Proxies. Spieler melden andere Spieler per GUI (Java) oder Formular (Bedrock),
das Team wird auf allen Servern sofort benachrichtigt und bearbeitet die Reports über ein Menü oder
per Befehl. Der Reporter erfährt das Ergebnis, egal auf welchem Server oder Proxy er gerade ist -
oder beim nächsten Login, falls er offline war.

- Java 21, Maven Multi-Module
- Paper 1.21.3, BungeeCord 1.21
- MongoDB (Morphia 2.4) als Datenhaltung, Redis Pub/Sub für die Kommunikation zwischen den Servern
- Guice für Dependency Injection, SmartInvs für Menüs, Floodgate/Cumulus für Bedrock-Formulare
- Offline-Spieler werden über [mc-api.io](https://mc-api.io/docs) aufgelöst (Java und Bedrock)

## Inhalt

- [Funktionen](#funktionen)
- [Architektur](#architektur)
- [Benachrichtigungen über mehrere Proxies](#benachrichtigungen-über-mehrere-proxies)
- [Setup](#setup)
- [Build & Deployment](#build--deployment)
- [Konfiguration](#konfiguration)
- [Befehle und Rechte](#befehle-und-rechte)
- [Templates verwalten](#templates-verwalten)
- [Tests](#tests)
- [Branches und Commits](#branches-und-commits)
- [Anmerkungen](#anmerkungen)

Weiterführend: [Architektur im Detail](docs/architecture.md) · [Performance](docs/performance.md) ·
[API für andere Plugins](docs/api.md)

## Funktionen

**Melden**
- `/report` öffnet eine Auswahl aller sichtbaren Online-Spieler (Köpfe), `/report <name>` springt
  direkt zur Grund-Auswahl
- Offline-Spieler: Online-Spieler und User-Cache des Servers werden zuerst geprüft, danach
  mc-api.io. Ob ein Name zu Java oder Bedrock gehört, entscheidet der Floodgate-Prefix
  (standardmäßig `.`, ohne Floodgate aus der Config)
- Templates `CHEATING`, `INSULT`, `BUGUSING`, `GRIEFING`, `SPAM`, `OTHER` (mit Pflicht-Freitext);
  liegen in MongoDB und lassen sich zur Laufzeit erweitern
- Java: SmartInvs-Menüs, Freitext über den Chat. Bedrock: Floodgate-Formulare mit denselben Schritten
- Spam-Schutz: netzwerkweiter Cooldown (atomar in Redis), kein zweiter offener Report gegen
  denselben Spieler, Limit offener Reports pro Spieler, keine Selbst-Reports, Längenprüfung und
  Bereinigung von Freitexten

**Moderation**
- Live-Benachrichtigung an alle Teammitglieder (`report.admin`) auf allen Servern, klickbar
- `/reports`: Menü mit Filtern (Status, Grund, Server, Zeitraum), Sortierung (Datum, Server, Grund)
  und seitenweisem Laden aus der Datenbank; Detailansicht mit Annehmen/Ablehnen
- Bedrock-Moderatoren bekommen dieselbe Verwaltung als Formulare
- Befehle `resolve`, `reject`, `details`, `stats`, `reload`
- Ein Report kann nur einmal abgeschlossen werden, auch wenn zwei Moderatoren gleichzeitig klicken;
  Reports gegen sich selbst können Moderatoren nicht bearbeiten
- Login-Reminder auf dem Proxy: `[REPORT] Du hast aktuell X offene Reports. Nutze /reports.`

## Architektur

```
                ┌─────────────┐        ┌─────────────┐
 Spieler ──────►│  Proxy 1    │        │  Proxy 2    │◄────── Spieler
                │  bungee     │        │  bungee     │
                └──────┬──────┘        └──────┬──────┘
                       │  Login-Reminder (liest MongoDB)
        ┌──────────────┼──────────────────────┼──────────────┐
        ▼              ▼                      ▼              ▼
  ┌──────────┐   ┌──────────┐           ┌──────────┐   ┌──────────┐
  │ survival │   │  lobby   │    ...    │ bedwars  │   │  ...     │   bukkit-Plugin
  └────┬─────┘   └────┬─────┘           └────┬─────┘   └────┬─────┘
       │              │                      │              │
       └───────┬──────┴──────────┬───────────┴──────────────┘
               ▼                 ▼
        ┌────────────┐    ┌────────────┐
        │  MongoDB   │    │   Redis    │   reports:new, reports:status_update,
        │  reports   │    │  Pub/Sub   │   reports:templates, Cooldowns
        └────────────┘    └────────────┘
```

| Modul    | Inhalt |
|----------|--------|
| `api`    | Service-Interfaces (`ReportService`, `TemplateService`, `ProfileService`), Records für Reports, Filter, Ergebnisse und Redis-Nachrichten, Enums. Keine Abhängigkeiten außer JetBrains Annotations. |
| `common` | Plattformunabhängige Implementierung: Morphia-Entities und Repositories, Redis-Message-Bus mit Reconnect, Cooldowns, mc-api.io-Client mit Cache und Fallback, Guice-Module. Kennt weder Bukkit noch BungeeCord, nur das Interface `PlatformScheduler`. |
| `bukkit` | Paper-Plugin: Befehle, SmartInvs-Menüs, Floodgate-Formulare, Chat-Eingaben, Benachrichtigungen. |
| `bungee` | BungeeCord-Plugin: Reminder für das Team beim Login. Nutzt nur den MongoDB-Teil von `common`. |

Die Bukkit-Seite trennt Abläufe von Darstellung: `ReportFlow` und `ModerationFlow` enthalten die
Schritte, `ReportUi` hat eine Implementierung für Java (`MenuReportUi`) und eine für Bedrock
(`FormReportUi`). Welche benutzt wird, entscheidet `ReportUiRouter` pro Spieler.

**Threading:** Es gibt keine eigenen Threads oder Executor-Services. Alle Datenbank-, Redis- und
HTTP-Zugriffe laufen über `PlatformScheduler`, der auf den Bukkit- bzw. BungeeCord-Scheduler
abbildet. Repositories sind als `@Blocking` markiert und werden nur aus Async-Tasks aufgerufen; die
Services geben `CompletableFuture`s zurück, `MainThread` bringt Ergebnisse zurück auf den
Server-Thread. Details in [docs/architecture.md](docs/architecture.md#threading).

## Benachrichtigungen über mehrere Proxies

Kein Server und kein Proxy weiß, wo ein bestimmter Spieler gerade online ist. Das Ergebnis eines
Reports wird deshalb nicht an einen Spieler adressiert, sondern an alle Server verteilt:

1. Der Moderator schließt den Report. Das Update in MongoDB ist bedingt (`status == OPEN`), damit
   gewinnt bei gleichzeitigen Klicks genau ein Moderator. Gleichzeitig wird `reporterNotified` auf
   `false` gesetzt.
2. Auf `reports:status_update` geht eine Nachricht an alle Server.
3. Jeder Server prüft lokal, ob der Reporter bei ihm online ist (`O(1)`). Nur dieser Server
   beansprucht die Zustellung mit einem atomaren `findOneAndUpdate(reporterNotified: false → true)`.
4. Nur wer den Claim gewinnt, schickt die Nachricht. Dadurch gibt es keine doppelten Nachrichten,
   auch wenn der Spieler gerade zwischen zwei Servern wechselt und kurz auf beiden sichtbar ist.
5. Ist der Reporter offline (oder gerade in keinem Server sichtbar), bleibt der Eintrag offen. Beim
   nächsten Join auf einem beliebigen Server werden offene Ergebnisse beansprucht und zugestellt.
   Ein partieller Index auf `reporterNotified: false` hält diese Prüfung billig.

Verlässt der Spieler den Server zwischen Claim und Zustellung, wird der Claim zurückgegeben.
Die Redis-Nachricht ist also nur ein Signal; die Wahrheit liegt in MongoDB. Geht eine Nachricht
verloren (Redis kurz weg), wird das Ergebnis beim nächsten Login zugestellt.

Mehr dazu, inklusive Sequenzdiagramm: [docs/architecture.md](docs/architecture.md#statusbenachrichtigungen).

## Setup

### MongoDB und Redis

Für Entwicklung und Tests liegt eine `docker-compose.yml` bei:

```bash
docker compose up -d
docker compose ps        # beide Container sollten "healthy" sein
```

| Dienst  | Image            | Port              | Volume       |
|---------|------------------|-------------------|--------------|
| MongoDB | `mongo:7.0`      | `127.0.0.1:27017` | `mongo-data` |
| Redis   | `redis:7.4-alpine` | `127.0.0.1:6379` | `redis-data` |

Die Datenbank `reports` mit ihren Collections und Indizes legt das Plugin beim ersten Start selbst
an, ebenso die Standard-Templates.

Für den Produktivbetrieb:
- MongoDB mit Authentifizierung betreiben (Benutzer mit `readWrite` auf die Report-Datenbank) und
  die Zugangsdaten in die URI schreiben, idealerweise als Replica Set
- Redis mit Passwort oder ACL-User (`requirepass` / `user ... on >passwort ~reports:* &reports:* +@all`)
- Beide Dienste nicht öffentlich erreichbar machen

### Voraussetzungen der Server

- Paper 1.21.3 (oder neuer 1.21.x) mit Java 21
- BungeeCord 1.21 mit `ip_forward: true`, auf den Paper-Servern `settings.bungeecord: true`
- Optional Floodgate auf den Paper-Servern für Bedrock-Formulare. Ohne Floodgate bekommen alle
  Spieler die Java-Menüs; der Bedrock-Prefix für Namenssuchen kommt dann aus der Config

## Build & Deployment

```bash
mvn clean package
```

| Artefakt | Ziel |
|----------|------|
| `bukkit/target/ReportSystem-Bukkit-<version>.jar` | `plugins/` jedes Paper-Servers |
| `bungee/target/ReportSystem-Bungee-<version>.jar` | `plugins/` jedes Proxies |

Beide Jars enthalten ihre Bibliotheken (relocated unter `dev.exceptionloader.reports.libs`), damit
es keine Konflikte mit anderen Plugins gibt. Gson, Adventure und SLF4J stellt Paper bereit.

Deployment:
1. Jars in die Plugin-Ordner kopieren und Server einmal starten, damit die Configs entstehen
2. Auf jedem Paper-Server `server-name` setzen (wird an jedem Report gespeichert) und die
   Verbindungsdaten eintragen
3. Proxy-Config anpassen, Server neu starten

JavaDoc der API: `mvn -pl api javadoc:javadoc`, danach unter `api/target/reports/apidocs`.

## Konfiguration

### Paper (`plugins/ReportSystem/config.yml`)

```yaml
server-name: "survival-1"

mongodb:
  uri: "mongodb://reports:geheim@mongo-1:27017,mongo-2:27017/reports?replicaSet=rs0&authSource=reports"
  database: "reports"

redis:
  host: "redis.internal"
  port: 6379
  username: "reports"
  password: "geheim"
  database: 0
  ssl: false
  timeout-millis: 2000

reports:
  cooldown-seconds: 60          # netzwerkweit zwischen zwei Reports eines Spielers
  max-open-per-reporter: 5      # 0 = kein Limit
  min-text-length: 5            # Freitext bei OTHER und Begründungen beim Ablehnen
  max-text-length: 200
  template-refresh-minutes: 5   # zusätzlich zum Reload über Redis

lookup:
  base-url: "https://mc-api.io"
  timeout-millis: 4000
  cache-minutes: 30
  unknown-name-cache-seconds: 120
  error-cooldown-seconds: 60    # so lange wird die API nach einem Fehler übersprungen
  bedrock-prefix: "."           # nur ohne Floodgate relevant

display:
  date-format: "dd.MM.yyyy HH:mm"
  timezone: "Europe/Berlin"
  bedrock-head-url: "https://mc-api.io/render/FACE/%uuid%?size=64"
```

Alle Texte stehen in `messages.yml` (MiniMessage). Fehlende Schlüssel werden aus den mitgelieferten
Standardtexten ergänzt, ein leerer Wert schaltet eine Nachricht ab. `/report reload` lädt Texte und
Templates neu; Verbindungsdaten erfordern einen Neustart.

### BungeeCord (`plugins/ReportSystem/config.yml`)

```yaml
mongodb:
  uri: "mongodb://localhost:27017"
  database: "reports"

reminder:
  permission: "report.admin"
  delay-seconds: 3
  cache-seconds: 15
  notify-when-empty: false
  message: "&8[&cREPORT&8] &7Du hast aktuell &e%count% &7offene Reports. Nutze &e/reports&7."
```

## Befehle und Rechte

| Befehl | Recht | Beschreibung |
|--------|-------|--------------|
| `/report` | `report.use` | Spielerauswahl öffnen |
| `/report <spieler>` | `report.use` | Spieler direkt melden, auch offline |
| `/reports` | `report.admin` | Report-Verwaltung (Konsole: Liste der offenen Reports) |
| `/report resolve <id>` | `report.admin` | Report als berechtigt abschließen |
| `/report reject <id> <grund>` | `report.admin` | Report mit Begründung ablehnen |
| `/report details <id>` | `report.admin` | Alle Informationen zu einem Report |
| `/report stats [spieler]` | `report.admin` | Statistiken gesamt oder für einen Spieler |
| `/report reload` | `report.admin` | Nachrichten und Templates neu laden, Templates netzwerkweit |

`report.use` haben standardmäßig alle Spieler, `report.admin` nur OPs. Wer `report.admin` hat,
bekommt außerdem die Live-Benachrichtigungen. Auf dem Proxy muss das Recht ebenfalls vergeben sein
(z.B. über LuckPerms auf BungeeCord), damit der Login-Reminder greift.

Report-IDs sind die 24-stelligen ObjectIds aus MongoDB. In Benachrichtigungen und Details sind sie
anklickbar, niemand muss sie abtippen.

## Templates verwalten

Templates liegen in der Collection `templates`. Ein neues Template braucht nur `_id`, `name` und
`description`:

```javascript
db.templates.insertOne({
  _id: "teaming",
  name: "TEAMING",
  description: "Unerlaubtes Teaming",
  icon: "LEAD",           // Material für das Java-Menü, Standard PAPER
  requiresText: false,    // true = Spieler muss den Vorfall beschreiben
  sortOrder: 70           // Position im Menü
})

db.templates.updateOne({ _id: "spam" }, { $set: { enabled: false } })   // ausblenden
```

Danach `/report reload` auf einem beliebigen Server; die anderen laden über `reports:templates`
nach. Ohne Reload übernehmen alle Server die Änderung spätestens nach `template-refresh-minutes`.

## Tests

```bash
mvn test
```

Unit-Tests decken die reine Logik ab: Namensregeln und Edition-Erkennung, Auswertung der
mc-api.io-Antworten, Filter- und Sortierlogik, JSON-Format der Redis-Nachrichten,
Eingabebereinigung, API-Records.

Der Ordner [`e2e/`](e2e/README.md) startet ein lokales Netzwerk aus zwei Paper-Servern hinter zwei
BungeeCord-Proxies und spielt die Abläufe mit Mineflayer-Bots durch: Reporter und Moderator auf
unterschiedlichen Proxies, Offline-Zustellung, Duplikate, Cooldown, Statistiken, Redis-Neustart,
MongoDB-Ausfall und Template-Reload.

## Branches und Commits

- `main` enthält nur releasefähige Stände, jeder Merge wird getaggt (`v1.0.0`, ...)
- `develop` ist der Integrationszweig
- Features und Fixes entstehen in `feature/<thema>` bzw. `fix/<thema>` und gehen per Pull Request
  nach `develop`
- Commit-Nachrichten folgen [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat(bukkit): ...`, `fix(common): ...`, `docs: ...`), der Scope ist das Modul

## Anmerkungen

- **BungeeCord-API-Version:** Die Vorgabe `net.md-5:bungeecord-api:1.21-R0.1-SNAPSHOT` ist seit der
  Abschaltung von OSSRH in keinem Repository mehr verfügbar. Verwendet wird das inhaltsgleiche
  Release `1.21-R0.1` von Maven Central.
- **Status-Nachricht:** Das Format von `reports:status_update` enthält wie vorgegeben keine
  Moderator-Notiz. Der zustellende Server liest sie beim Claim direkt aus MongoDB.
- **Unterbefehle vs. Spielernamen:** Für Spieler mit `report.admin` haben Unterbefehle Vorrang. Ein
  Spieler namens `stats` lässt sich vom Team daher über das Menü melden, nicht per `/report stats`.
