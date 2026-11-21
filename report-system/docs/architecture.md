# Architektur

## Module und Abhängigkeiten

```
api  ◄──  common  ◄──  bukkit
              ▲
              └──────  bungee   (ohne Redis)
```

`api` ist bewusst schlank: Interfaces, Records und Enums, keine Bibliotheken. Andere Plugins können
dagegen kompilieren, ohne Morphia oder Jedis zu sehen. `common` enthält alles, was nicht von der
Plattform abhängt, und spricht die Plattform nur über `PlatformScheduler` (Async-Tasks) und
`BedrockPrefixProvider` (Floodgate-Prefix) an.

### Pakete

| Paket | Inhalt |
|-------|--------|
| `api` | `ReportService`, `TemplateService`, `ProfileService` |
| `api.model` | `Report`, `ReportTemplate`, `PlayerProfile`, `ReportStatus`, `Edition`, Statistiken |
| `api.query` | `ReportFilter`, `ReportQuery`, `ReportPage`, `ReportSort`, `TimeRange` |
| `api.action` | `ReportRequest`, `ReportDecision` und die Ergebnis-Typen `SubmitResult`, `DecisionResult` (sealed) |
| `api.message` | Kanalnamen und Records der Redis-Nachrichten |
| `common.storage` | `MongoConnector`, Repositories, Entities, Übersetzung von Filtern in Queries |
| `common.messaging` | `MessageBus`, `RedisMessageBus`, `RedisConnector`, JSON-Codec |
| `common.lookup` | mc-api.io-Client, Namensregeln, `DefaultProfileService` |
| `common.service` | Report- und Template-Service, Cooldowns, Zustell-Inbox, Spielerverzeichnis |
| `bukkit.flow` | Abläufe: Melden, Moderieren, Namensauflösung |
| `bukkit.ui` | `ReportUi` mit `menu` (SmartInvs) und `form` (Floodgate), Chat-Eingaben |
| `bukkit.notify` | Team-Alerts und Benachrichtigung der Reporter |
| `bukkit.command` | `/report` mit Unterbefehlen, `/reports` |

### Dependency Injection

Guice-Module:

- `StorageModule` (common): MongoDB-Settings, `Clock`
- `ServiceModule` (common): Redis/Report/Lookup-Settings, Bindings der API-Interfaces, Message-Bus
- `BukkitModule` (bukkit): Plugin, Scheduler, Logger, Floodgate oder Java-Fallback, SmartInvs
- `ProxyModule` (bungee): Plugin, Scheduler, Logger

Klassen, die Floodgate referenzieren (`FloodgateGateway`, `FormReportUi`), werden nur gebunden,
wenn das Floodgate-Plugin geladen ist. Sonst wird `JavaOnlyGateway` gebunden und alle Spieler
bekommen die Inventar-Menüs, die Geyser auch Bedrock-Spielern anzeigen kann.

Zirkuläre Abhängigkeiten zwischen Abläufen und Oberflächen (Menü ruft Ablauf, Ablauf öffnet Menü)
werden über `Provider<ReportFlow>` aufgelöst.

## Datenmodell

### `reports`

| Feld | Typ | Bemerkung |
|------|-----|-----------|
| `_id` | ObjectId | |
| `reporter`, `reported` | UUID (Binary 4) | Bedrock-UUIDs sind die Floodgate-IDs |
| `reporterName`, `reportedName` | String | Namen zum Zeitpunkt des Reports |
| `template` | String | ID des Templates, für den Filter |
| `reason` | String | Template-Name oder Freitext bei `OTHER` |
| `status` | `OPEN`, `RESOLVED`, `REJECTED` | |
| `handledBy`, `handledByName`, `modNote`, `handledAt` | | gesetzt beim Abschließen |
| `server` | String | `server-name` der Instanz |
| `createdAt` | Date | |
| `reporterNotified` | Boolean | fehlt solange offen, `false` = Ergebnis noch nicht zugestellt |

Gegenüber dem vorgegebenen Modell sind `template` (Filter nach Grund, auch bei Freitext) und
`reporterNotified` (Zustellung, siehe unten) hinzugekommen.

Indizes:

| Name | Schlüssel | Zweck |
|------|-----------|-------|
| `status_1`, `reported_1`, `reporter_1`, `createdAt_-1` | einzeln | vorgegeben; Statistiken, Duplikatprüfung, Zeitraum |
| `server_1` | `server` | Server-Filter und `distinct` für die Filterliste |
| `status_createdAt` | `status`, `createdAt -1` | Standardansicht: offene Reports, neueste zuerst |
| `pending_notifications` | `reporter`, `handledAt` mit `partialFilter {reporterNotified: false}` | offene Zustellungen beim Join |

### `templates`

`_id`, `name`, `description`, `icon`, `requiresText`, `sortOrder`, `enabled`. Ist die Collection
leer, werden die sechs Standard-Templates angelegt. Mehrere Server, die gleichzeitig zum ersten Mal
starten, sind kein Problem: das Einfügen ignoriert bereits vorhandene IDs.

### `profiles`

Letzter bekannter Name jeder UUID, die das Netzwerk betreten hat (`_id`, `name`, `nameLower`,
`edition`, `lastSeen`). Dient als Fallback, wenn mc-api.io nicht erreichbar ist.

## Redis

| Kanal | Inhalt | Sender | Empfänger |
|-------|--------|--------|-----------|
| `reports:new` | `ReportCreatedMessage` | Server, auf dem gemeldet wurde | alle Server → Teammitglieder |
| `reports:status_update` | `ReportStatusMessage` | Server des Moderators | alle Server → Reporter |
| `reports:templates` | `TemplateReloadMessage` | Server mit `/report reload` | alle anderen Server |

```json
{"reportId":"6b00f402e8f7642827b881d4","reporter":"Alex","reported":"Steve","reason":"CHEATING","server":"survival-1","timestamp":"2026-11-21T01:29:44.512Z"}
```

```json
{"reportId":"6b00f402e8f7642827b881d4","reporterUuid":"2a4a647a-9676-30ab-9b5e-49cbd9a64f88","reporterName":"Alex","reportedName":"Steve","status":"RESOLVED","handledBy":"Mod","timestamp":"2026-11-21T01:30:02.101Z"}
```

Zusätzlich liegt pro Spieler während des Cooldowns ein Schlüssel `reports:cooldown:<uuid>` mit TTL.

Die Subscription belegt eine Verbindung aus dem Pool und läuft in einem langlebigen Async-Task.
Bricht die Verbindung ab, endet der Task und ein neuer Versuch wird mit exponentiellem Backoff
(1 s bis 30 s) eingeplant. Ein Watchdog pingt die Subscription alle 20 Sekunden; antwortet Redis
eine Minute lang nicht, wird die Verbindung getrennt und neu aufgebaut. Das fängt auch halb offene
TCP-Verbindungen ab, bei denen der Lesethread sonst ewig blockieren würde. Fehlgeschlagene
Publishes werden bis zu dreimal wiederholt.

## Abläufe

### Report erstellen

```
Spieler        ReportFlow         TargetResolver / ProfileService     ReportService           MongoDB / Redis
   │ /report Steve │                         │                              │                         │
   │──────────────►│ resolve("Steve")        │                              │                         │
   │               │────────────────────────►│ online? usercache?           │                         │
   │               │                         │ cache → mc-api.io → profiles │                         │
   │               │◄────────── Found ───────│ (async)                      │                         │
   │◄── Menü/Form ─│ (Main-Thread)           │                              │                         │
   │ Template      │                                                        │                         │
   │──────────────►│ submit(request) ──────────────────────────────────────►│ offener Report?         │
   │               │                                                        │ Limit? Cooldown (Lua)   │
   │               │                                                        │ insert ───────────────► │
   │               │                                                        │ publish reports:new ──► │
   │◄─ "Dein Report wurde aufgenommen." ◄──────────── Created ──────────────│                         │
```

Ungültige Eingaben (Selbst-Report, unbekanntes Template, Freitext zu kurz/lang) werden vor dem
Datenbankzugriff abgewiesen. Duplikat und Limit sind reine Lesezugriffe; erst danach wird der
Cooldown atomar gesetzt. Schlägt das Speichern fehl, wird der Cooldown wieder freigegeben.

### Statusbenachrichtigungen

```
Moderator (lobby, Proxy 2)           MongoDB                 Redis                 survival (Proxy 1)       Reporter
     │ resolve                          │                      │                         │                     │
     │ findOneAndUpdate                 │                      │                         │                     │
     │ {_id, status: OPEN} ────────────►│ status=RESOLVED      │                         │                     │
     │                                  │ reporterNotified=false                         │                     │
     │ publish status_update ──────────────────────────────────►│────────► alle Server ──►│ online hier? ja     │
     │                                  │                      │                         │                     │
     │                                  │◄──── findOneAndUpdate {_id, reporterNotified: false} → true ─────────│
     │                                  │───── Dokument (inkl. modNote) ────────────────►│                     │
     │                                  │                      │                         │── Nachricht ───────►│
```

Alle anderen Server verwerfen die Nachricht nach einem Map-Lookup. Varianten:

- **Reporter offline:** niemand beansprucht die Zustellung. Beim nächsten Join auf irgendeinem
  Server holt `ReporterNotifier` mit `claimNextPending` alle offenen Ergebnisse (älteste zuerst,
  höchstens zehn pro Join).
- **Reporter wechselt gerade den Server:** ist er auf keinem Server sichtbar, gilt dasselbe wie
  offline. Ist er kurz auf beiden sichtbar, gewinnt genau einer den Claim.
- **Reporter geht zwischen Claim und Senden offline:** der Claim wird zurückgesetzt.
- **Redis-Nachricht geht verloren:** das Ergebnis bleibt offen und wird beim nächsten Join zugestellt.

Die Proxies sind an diesem Ablauf nicht beteiligt. Der Spieler ist immer mit genau einem
Paper-Server verbunden, und den erreicht der Broadcast unabhängig davon, über welchen Proxy der
Spieler kam. Neue Proxies oder Server brauchen keine Konfiguration.

### Moderation

`ModerationFlow` wird von Befehlen, Menüs und Formularen gleichermaßen benutzt. Das Abschließen ist
ein bedingtes Update (`status == OPEN` und `reported != moderator`). Schlägt es fehl, liest der
Service den Report nach und meldet `NotFound`, `AlreadyClosed` oder `OwnReport`.

Das Menü lädt Seiten mit `skip/limit` und `countDocuments` aus MongoDB. Es öffnet sofort mit einer
Ladeanzeige und füllt sich, sobald die Daten da sind. Ein Ticketzähler verwirft Antworten, die von
einem neueren Klick überholt wurden, und es wird nur gerendert, wenn der Spieler genau dieses
Inventar noch geöffnet hat.

## Threading

| Was | Wo |
|-----|----|
| Bukkit-API, Inventare, Nachrichten | Main-Thread |
| MongoDB, Redis-Befehle, Cooldown-Script | Async-Task des Platform-Schedulers |
| mc-api.io | `HttpClient` mit dem Platform-Scheduler als Executor |
| Redis-Subscription | ein langlebiger Async-Task, Handler springen auf den Main-Thread |
| Floodgate-Formular-Antworten | können auf einem Netty-Thread ankommen, werden sofort auf den Main-Thread verlegt |
| Chat-Eingaben (`AsyncChatEvent`) | Event async, Verarbeitung auf dem Main-Thread |

Geteilter Zustand ist entweder unveränderlich (Template-Snapshot, der als Ganzes per `volatile`
ausgetauscht wird), in `ConcurrentHashMap`s (Eingabe-Prompts, laufende Lookups) oder in
Guava-Caches. Menüzustände werden nur auf dem Main-Thread gelesen und geschrieben.

Die Bibliotheken bringen eigene Hintergrund-Threads mit, die sich nicht abschalten lassen: der
MongoDB-Treiber (Server-Monitor, Pool-Wartung) und der Selector-Thread des `HttpClient`. Der
Jedis-Pool ist so konfiguriert, dass kein Evictor-Thread entsteht.

## Fehlerbehandlung

- **MongoDB nicht erreichbar:** Der Treiber verbindet sich selbstständig neu. Operationen schlagen
  nach dem Server-Selection-Timeout (5 s) fehl, der Spieler bekommt eine Fehlermeldung, der
  Main-Thread läuft weiter. Das Anlegen der Indizes wird beim Start alle 30 Sekunden wiederholt.
- **Redis nicht erreichbar:** Reports werden trotzdem gespeichert. Der Cooldown fällt auf die lokale
  Instanz zurück, Live-Benachrichtigungen fehlen, bis die Subscription wieder steht. Ergebnisse für
  Reporter gehen nicht verloren (Zustellung beim Login).
- **mc-api.io nicht erreichbar oder Rate Limit:** Die API wird für `error-cooldown-seconds` (oder den
  `Retry-After`-Header) übersprungen, stattdessen wird in `profiles` gesucht.
- **Ungültige Nachrichten auf Redis:** werden geloggt und verworfen, die Subscription läuft weiter.
