## Goldrausch

Ablauf wie bei grossen SMP-Servern: ein Team-Mitglied startet ihn, dann kann 14 Tage lang in jedem natuerlichen Erz ein Fund stecken.

- **Start:** `/sgoldrausch` (ab Rang **SrMod**, Recht `minesmp.goldrausch.start`). `stop` beendet vorzeitig (zweimal eingeben), `status` zeigt den Stand.
- **Spieler:** `/goldrausch` - grosses Fenster zum Scrollen wie bei HugoSMP (Goldticket, Status, Goldtickets, Diese Erze zaehlen, Event wird moderiert, Was du finden kannst, Dein Goldrausch). Knoepfe unten: Gratis-Schluessel, Goldstaub-Shop, Meilensteine, Rangliste. Bedrock: Kisten-Menue. `/goldrausch ticket|shop|ziele|top` oeffnet die Kisten-Seiten direkt.
- **Funde:** Goldnugget (Geld), Goldbarren (viel Geld), Star-Tool, Spawner, Goldticket (Hauptgewinn: 14 Tage taeglich Gratis-Schluessel, 30 Tage Premium oder Gutschein, goldener Name, Aura).
- **Goldticket-Algorithmus:** hoechstens 1 am Tag, mit Tages-Garantie zu einer geheimen Uhrzeit (siehe `goldrausch.yml` -> `ticket`).
- **Glueck:** Goldfieber (zuegig weiter abbauen), Goldene Stunde (19 Uhr), Server-Ziel, Items aus dem Goldstaub-Shop.
- **Zaehlt nicht:** Netherquarz, Nethergold, von Spielern gesetzte Erze, die Spawn-Welt.
- **Rang SrMod:** wird beim ersten Start automatisch in LuckPerms angelegt. Vergeben: `lp user <Name> parent add srmod`.
- **Team (Admin):** `/sgoldrausch abbrechen | zeit <Dauer> | staub <Spieler> <n> | fund <Spieler> <Art> | reload`.
- Alle Zahlen (Betraege, Chancen, Preise, Belohnungen): `plugins/MineSMPCore/goldrausch.yml`.

## Website

Laeuft im Plugin auf Port 8164 (`website.yml`): `http://<Server-IP>:8164/`. Port 8164 (TCP) freigeben. Anmeldung im Forum mit `/web` (Code aus dem Spiel). Vor dem Veroeffentlichen Impressum und Datenschutz in `website.yml` ausfuellen; Preise und Shop-Links dort anpassen.
