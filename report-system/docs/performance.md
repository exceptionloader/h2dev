# Performance

## Main-Thread

Auf dem Server-Thread passiert nur, was die Bukkit-API verlangt: Inventare öffnen und befüllen,
Nachrichten senden, Online-Spieler nachschlagen. Alles mit I/O läuft über den Platform-Scheduler.
Selbst das Anlegen des Morphia-Datastores (inklusive Indizes) passiert im ersten Async-Task statt
beim Plugin-Start. Fällt MongoDB aus, warten nur die betroffenen Async-Tasks auf den Timeout; der
E2E-Test `resilience.js` prüft das, indem er während eines hängenden Datenbankzugriffs ein Menü
öffnet.

## Datenbank

- Jede Abfrage des Systems hat einen passenden Index (siehe [architecture.md](architecture.md#reports)).
  Die Standardansicht im Menü ("offen, neueste zuerst") ist ein reiner Index-Scan über
  `status_createdAt`.
- Das Menü lädt nur die sichtbare Seite (36 Reports, Bedrock 10). `skip` wird bei sehr tiefen
  Seiten teurer; für die üblichen ersten Seiten einer Moderationsansicht ist das vernachlässigbar.
  Bei sehr großen Datenmengen wäre Keyset-Pagination über `createdAt` der nächste Schritt.
- Statistiken sind eine einzige Aggregation mit `$facet` pro Aufruf. Für Spielerstatistiken grenzt
  ein vorgeschaltetes `$match` über die Indizes `reporter`/`reported` die Menge vorher ein.
- Der Join-Check auf offene Zustellungen trifft einen partiellen Index, der nur Dokumente mit
  `reporterNotified: false` enthält. Er bleibt also klein, egal wie viele Reports insgesamt
  existieren, und eine Abfrage ohne Treffer kostet praktisch nichts. Er läuft bei jedem Join,
  auch bei Serverwechseln, weil genau dann Ergebnisse "zwischen" zwei Servern hängen bleiben können.
- Das Abschließen eines Reports und das Beanspruchen einer Zustellung sind jeweils ein einzelnes
  `findOneAndUpdate`, ohne vorherigen Lesezugriff.
- Gespeicherte Zeiten werden auf Millisekunden gekürzt, damit das zurückgegebene Objekt dem
  gespeicherten entspricht.

## Redis

- Pro Server eine dauerhafte Verbindung für die Subscription, alle anderen Befehle teilen sich einen
  kleinen Pool (max. 16). Ein Report verursacht einen Publish und einen Lua-Aufruf für den Cooldown.
- Pub/Sub skaliert linear mit der Zahl der Server: jede Nachricht wird einmal an jeden Server
  geschickt, der sie mit einem Map-Lookup (`Server#getPlayer`) verwirft oder zustellt. Bei
  Report-Volumen (Größenordnung Nachrichten pro Minute, nicht pro Tick) ist das unkritisch, auch
  bei vielen Servern und Proxies.
- Die Proxies abonnieren nichts. Sie lesen beim Login eines Teammitglieds einmal die Anzahl der
  offenen Reports, mit 15 Sekunden Cache, sodass ein Team-Login nach einem Neustart nur eine
  Abfrage auslöst.

## Externe API

- Ergebnisse von mc-api.io werden 30 Minuten gecacht, unbekannte Namen zwei Minuten. Mehrere
  gleichzeitige Suchen nach demselben Namen teilen sich einen Request.
- Vor der API werden Online-Spieler und der User-Cache des Servers geprüft. Spieler, die gerade
  gejoint sind, landen direkt im Cache.
- Ohne API-Key erlaubt mc-api.io 60 Requests pro Minute und IP. Bei `429` oder Fehlern wird die API
  für die angegebene Zeit übersprungen und das eigene Spielerverzeichnis genutzt, damit ein
  Ausfall nicht jeden Report um den Timeout verzögert.

## Speicher und Threads

- Templates liegen als unveränderlicher Snapshot im Speicher, Lesezugriffe brauchen keine Sperre.
- Caches sind in der Größe begrenzt (5 000 Namen, 10 000 Cooldown-Einträge für den Fallback).
- Das System erzeugt keine eigenen Threads. Der MongoDB-Treiber hält einige Monitor-Threads, der
  `HttpClient` einen Selector-Thread; der Jedis-Pool läuft ohne Evictor-Thread.
- Das Bukkit-Jar ist etwa 15 MB groß, fast alles davon sind MongoDB-Treiber, Morphia und Byte Buddy.
  Gson, Adventure und SLF4J werden nicht mitgeliefert, sondern von Paper genutzt.
