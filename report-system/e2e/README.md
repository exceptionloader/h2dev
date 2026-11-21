# End-to-End-Tests

Startet ein lokales Netzwerk aus zwei Paper-Servern hinter zwei BungeeCord-Proxies und spielt
die Abläufe mit [Mineflayer](https://github.com/PrismarineJS/mineflayer)-Bots durch.

```
proxy-1 :25577 ──► survival-1 :25566      Reporter
proxy-2 :25578 ──► lobby-1    :25567      Moderator (mit Floodgate)
```

Voraussetzungen: Java 21, Node 18+, Docker. Die Server laufen im Offline-Modus und sind nur für
Tests gedacht.

```bash
docker compose up -d                # im Projektverzeichnis
mvn clean package                   # Plugins bauen
cd e2e
./network.sh setup                  # Paper, BungeeCord, Floodgate laden und konfigurieren
./network.sh start
npm run scenario                    # Melden, Moderieren, Cross-Proxy-Benachrichtigung
./network.sh reset                  # Testdatenbank leeren
npm run resilience                  # Redis-Neustart, MongoDB-Ausfall, Template-Reload
./network.sh stop
```

`VERBOSE=1 npm run scenario` zeigt zusätzlich den Chat der Bots.
