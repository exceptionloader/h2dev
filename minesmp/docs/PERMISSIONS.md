# Berechtigungen

Alle Spielerrechte stecken in **`minesmp.player`** (Standard: alle Spieler, siehe `plugin.yml`).
Rechte, die nicht in `plugin.yml` stehen, haben Server-Operatoren automatisch - **gib Teammitgliedern Rechte
ueber LuckPerms-Gruppen statt OP** (OP hat z.B. auch `minesmp.combat.bypass` und `minesmp.chat.bypass`).
Fertige Gruppen: `scripts/luckperms-setup.txt`.

## Spieler (in minesmp.player enthalten)
`minesmp.money` `minesmp.pay` `minesmp.baltop` `minesmp.shop` `minesmp.sell` `minesmp.sell.all` `minesmp.ah` `minesmp.trade`
`minesmp.playershop.create` `minesmp.clan` `minesmp.bounty` `minesmp.kit` `minesmp.kit.starter` `minesmp.duel` `minesmp.arena`
`minesmp.crate` `minesmp.skills` `minesmp.quests` `minesmp.achievements` `minesmp.battlepass` `minesmp.daily` `minesmp.rewards`
`minesmp.event` `minesmp.home` `minesmp.tpa` `minesmp.back` `minesmp.spawn` `minesmp.rtp` `minesmp.farmwelt` `minesmp.pwarp`
`minesmp.afk` `minesmp.trash` `minesmp.msg` `minesmp.ignore` `minesmp.playtime` `minesmp.info` `minesmp.profile` `minesmp.stats`
`minesmp.settings` `minesmp.report` `minesmp.staff.list` `minesmp.vote` `minesmp.cosmetics`
`minesmp.pve` `minesmp.gems` `minesmp.stars` `minesmp.tokenshop` `minesmp.keyall` `minesmp.spawners` `minesmp.orders` `minesmp.lfg` `minesmp.claim` `minesmp.ce`

## Rang-Vorteile (VIP/MVP/Legende/Premium)
| Recht | Wirkung |
|---|---|
| `minesmp.homes.<n>` | Max. Homes (Standard 3) |
| `minesmp.ah.limit.<n>` | Max. Auktionen (Standard 5) |
| `minesmp.pwarp.limit.<n>` | Max. Spieler-Warps (Standard 1) |
| `minesmp.sell.multiplier.<vip/mvp/legende>` | Verkaufs-Multiplikator (config.yml) |
| `minesmp.kit.<name>` | Kit nutzen |
| `minesmp.craft`, `minesmp.enderchest` | /craft, /ec |
| `minesmp.repair`, `minesmp.repair.all`, `minesmp.repair.nocooldown` | /repair |
| `minesmp.back.death` | /back nach dem Tod |
| `minesmp.xp.boost` | +25 % Skill-XP |
| `minesmp.chat.color` | Farbcodes im Chat |
| `minesmp.battlepass.premium` | Premium-Battlepass |
| `minesmp.trail.<id>` / `minesmp.pet.<id>` | Kosmetik (herzen, flammen, noten, magie, funken, seele / katze, fuchs, hund, axolotl, biene) |
| `minesmp.afk.nokick` | Kein AFK-Kick |
| `minesmp.teleport.instant` | Keine Teleport-Wartezeit |
| `minesmp.rtp.nocooldown`, `minesmp.kit.nocooldown` | Keine Cooldowns |
| `minesmp.crate.anywhere` | Crates ueberall oeffnen (wenn open-anywhere=false) |
| `minesmp.playershop.unlimited` | Unbegrenzt Spieler-Shops |
| `minesmp.joinfull` | Auch auf den vollen Server (Premium) |
| `minesmp.clan.bonus.<n>` | n zusaetzliche Clan-Plaetze, wenn der Spieler Clan-Leader ist (Premium: 4) |
| `minesmp.claims.<n>` | Max. Grundstuecks-Chunks (Standard 9) |
| `minesmp.orders.limit.<n>` | Max. aktive Kaufauftraege (Standard 5) |
| `minesmp.stars.bonus.<n>` | n Extra-Sterne pro Spielzeit-Belohnung |

## Team
| Recht | Wirkung |
|---|---|
| `minesmp.staff.member` | Gilt als Teammitglied (/staff, kann nicht ignoriert werden) |
| `minesmp.staff.chat` | /sc Teamchat |
| `minesmp.staff.reports` | Report-Benachrichtigungen, /reports |
| `minesmp.staff.mute` / `.warn` / `.kick` / `.ban` / `.tempban` / `.unban` | Strafen |
| `minesmp.staff.history` | /history |
| `minesmp.staff.invsee` (+ `.modify`) | /invsee, /ecsee (nur ansehen / bearbeiten) |
| `minesmp.staff.vanish` | /vanish, sieht unsichtbare Teammitglieder |
| `minesmp.staff.socialspy` | SocialSpy in /settings |
| `minesmp.staff.broadcast` | /broadcast |
| `minesmp.staff.commands` | Namespace-Befehle (`/minecraft:...`), /pl usw. |
| `minesmp.staff.bypass` | Kann nur von Admins bestraft werden |
| `minesmp.chat.bypass`, `minesmp.chat.links` | Kein Anti-Spam / Links erlaubt |
| `minesmp.combat.bypass` | Kein Combat-Tag |
| `minesmp.money.others`, `minesmp.spawn.others` | Fremde Kontostaende / andere zum Spawn |

### Was welcher Team-Rang darf (mitgelieferte Rang-Datei)
| Rang | Rechte |
|---|---|
| **Owner, Admin, Entwickler** | Alles: alle MineSMP-Rechte, LuckPerms, WorldEdit, WorldGuard, CoreProtect, GrimAC, Server-Befehle; bauen am Spawn, Kreativmodus ueberall |
| **Moderator** | Alles vom Supporter + Bann / Tempbann / Entbannen, `/vanish` und `/vanishultra`, `/invsee` (nur ansehen), SocialSpy, `/broadcast`, Teleport zu Spielern, Events starten und stoppen (`/eventadmin`) |
| **Supporter** | Teamchat, Reports bearbeiten, Stummschalten, Kicken, Verwarnen, Straf-Verlauf, Anti-Cheat-Meldungen, CoreProtect nur nachschauen (wer hat was abgebaut) |
| **Builder** | Teamchat, bauen und abbauen am Spawn; WorldEdit und Kreativmodus **nur in der Spawn-Welt** (Bau-Modus: das eigene Inventar wird gesichert, Kreativ-Items kommen nicht in die Survival-Welt) |

Nicht fuer Moderator, Supporter und Builder: Geld/Gems/Sterne/Items/Spawner vergeben, Kisten und Keyall verwalten, Grundstuecke umgehen,
Rollbacks, Konfiguration neu laden, Spawn neu bauen, Rechte vergeben. Einzelne Rechte nachtraeglich geben:
`lp group moderator permission set <recht> true`.

### Spawn-Schutz
Ohne `minesmp.spawn.build` laesst sich in der Spawn-Welt nichts veraendern: abgebaute Bloecke sind sofort wieder da, Falltueren, Zauntore,
Blumentoepfe, Schilder, Rahmen, Ruestungsstaender, Betten, Hebel usw. bleiben wie sie sind, Werkzeuge (Hacke, Schaufel, Axt, Schere,
Knochenmehl, Feuerzeug, Eimer ...) wirken nicht, nichts waechst, brennt oder zerfaellt. Tueren, Knoepfe, Werkbaenke und Endertruhen bleiben benutzbar.
`minesmp.creative.free`: Kreativmodus ueberall und ohne Bau-Modus (Owner, Admin, Entwickler).

### Vanish
`minesmp.staff.vanish` erlaubt `/vanish` (komplett unsichtbar, auch der eigene Skin) und `/vanishultra` (du siehst deinen Skin, Spieler nicht).
Wer `minesmp.staff.member` oder `minesmp.staff.vanish` hat, sieht Teammitglieder im Vanish immer
(im `/vanish` als durchsichtige Figur, im `/vanishultra` normal). Namensschild und Tab-Eintrag sehen fuer das Team aus wie immer;
wer gerade im Vanish ist, zeigt `/vanish list`.

## Admin
`minesmp.spawn.build` (am Spawn bauen), `minesmp.admin.spawn` (/minesmp buildspawn, /minesmp winter on|off),
`minesmp.admin.eco` (/eco), `minesmp.admin.crate` (/crate givekey/setblock), `minesmp.admin.duel` (Arenen),
`minesmp.admin.event` (/eventadmin), `minesmp.admin.farmwelt` (/farmwelt reset), `minesmp.admin.items` (/msitem),
`minesmp.admin.reload` (/minesmp reload), `minesmp.admin.setspawn`, `minesmp.admin.audit` (/auditlog), `minesmp.admin.vote` (/fakevote),
`minesmp.playershop.admin`, `minesmp.pwarp.admin`, `minesmp.admin` (Bypass bei Strafen gegen Team),
`minesmp.admin.tokens` (/gems give, /sterne give), `minesmp.tokens.others`, `minesmp.admin.spawners` (/spawner give, fremde Spawner),
`minesmp.admin.claims` (/claim bypass, /claim adminremove), `minesmp.admin.ce` (/ce give, /ce add), `minesmp.admin.keyall` (/keyall now),
`minesmp.admin.poi` (/poi start, /poi stop).
