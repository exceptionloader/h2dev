# API

Das Modul `api` kann von anderen Plugins genutzt werden, z.B. für ein Discord-Bridge-Plugin oder
eigene Moderationswerkzeuge.

## Einbinden

```xml
<dependency>
    <groupId>dev.exceptionloader.reports</groupId>
    <artifactId>report-api</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

```yaml
# plugin.yml des eigenen Plugins
depend: [ReportSystem]
```

Die Services werden beim Start im Bukkit-`ServicesManager` registriert:

```java
ReportService reports = Bukkit.getServicesManager().load(ReportService.class);
TemplateService templates = Bukkit.getServicesManager().load(TemplateService.class);
ProfileService profiles = Bukkit.getServicesManager().load(ProfileService.class);
```

## Threads

Alle Methoden, die ein `CompletableFuture` liefern, blockieren nicht. Die Futures werden auf einem
Async-Thread abgeschlossen. Wer danach die Bukkit-API benutzt, muss selbst zurück auf den
Main-Thread:

```java
reports.countOpen().thenAccept(count ->
        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("Offen: " + count)));
```

`TemplateService#templates()` und `#template(String)` lesen aus dem Speicher und sind auf jedem
Thread sicher.

## Beispiele

Report anlegen:

```java
ReportRequest request = new ReportRequest(
        PlayerProfile.java(reporter.getUniqueId(), reporter.getName()),
        PlayerProfile.java(target.getUniqueId(), target.getName()),
        "cheating",
        null);

reports.submit(request).thenAccept(result -> {
    switch (result) {
        case SubmitResult.Created(Report report) -> log("gespeichert: " + report.id());
        case SubmitResult.AlreadyReported(Report existing) -> log("schon offen: " + existing.id());
        case SubmitResult.Cooldown(Duration remaining) -> log("Cooldown: " + remaining);
        case SubmitResult.TooManyOpen(int limit) -> log("Limit erreicht: " + limit);
        case SubmitResult.Invalid(SubmitResult.Violation violation) -> log("ungültig: " + violation);
    }
});
```

Offene Reports seitenweise lesen:

```java
ReportQuery query = new ReportQuery(ReportFilter.openReports().withServer("survival-1"), ReportSort.NEWEST, 0, 20);
reports.find(query).thenAccept(page ->
        page.reports().forEach(report -> log(report.reportedName() + ": " + report.reason())));
```

Report schließen:

```java
reports.decide(reportId, ReportDecision.reject(moderatorUuid, "Mod", "Kein Beweis"))
        .thenAccept(result -> {
            if (result instanceof DecisionResult.AlreadyClosed(Report report)) {
                log("bereits erledigt von " + report.handledByName());
            }
        });
```

Über `decide` geschlossene Reports lösen dieselbe Benachrichtigung des Reporters aus wie Befehle
und Menüs.

## Redis-Nachrichten

Dienste außerhalb von Minecraft (Discord-Bot, Web-Dashboard) können die Kanäle direkt abonnieren.
Format und Felder sind in [architecture.md](architecture.md#redis) beschrieben; Zeitstempel sind
ISO-8601 in UTC. Nachrichten sind Signale ohne Garantie, der verbindliche Zustand steht in MongoDB.
