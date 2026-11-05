# MineSMP-Trailer aufnehmen
Echte Aufnahme vom laufenden Testserver (Paper 1.21.11 + MineSMPCore) aus Sicht eines Bots.
1. `npm i mineflayer prismarine-viewer canvas rcon-client playwright`
2. `worldView.patched.js` nach `node_modules/prismarine-viewer/viewer/lib/worldView.js` kopieren
   (rechnet 1.21.11-Block-IDs auf die 1.21.4-Texturen des Viewers um).
3. Server mit RCON (Port 25604, Passwort in record.js) und `online-mode=false` starten, `node build.js` baut den Spawn.
4. `node record.js` -> `video/*.webm`, dann mit ffmpeg schneiden (Zeiten in `video/times.json`).
Hinweis: `security.max-accounts-per-ip` fuer die Aufnahme auf >= 4 setzen (4 Bots von einer IP).
