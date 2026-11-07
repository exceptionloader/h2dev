# MineSMP - Befehle

Automatisch erzeugt mit `tools/gen_commands.py`.

| Befehl | Aliase | Berechtigung | Beschreibung | Modul |
|---|---|---|---|---|
| /achievements | /erfolge | `minesmp.achievements` | Erfolge | AchievementService |
| /afk | - | `minesmp.afk` | AFK-Status | AfkService |
| /ah | /auktionshaus, /auction | `minesmp.ah` | Auktionshaus | AuctionService |
| /arena | /pvp | `minesmp.arena` | PvP-Arena | DuelService |
| /auditlog | /audit | `minesmp.admin.audit` | Zeigt das Audit-Log | AuditLog |
| /back | /zurueck | `minesmp.back` | Zur letzten Position | TeleportService |
| /baltop | /moneytop, /topmoney | `minesmp.baltop` | Reichste Spieler | EconomyCommands |
| /ban | - | `minesmp.staff.ban` | Bannt einen Spieler | PunishmentService |
| /battlepass | /bp, /pass | `minesmp.battlepass` | Battlepass | BattlePassService |
| /bounty | /kopfgeld, /bounties | `minesmp.bounty` | Kopfgelder | BountyService |
| /broadcast | /bc | `minesmp.staff.broadcast` | Rundnachricht | StaffCommands |
| /cc | /clanchat | `minesmp.clan` | Clanchat | ClanService |
| /claim | /gs, /grundstueck | `minesmp.claim` | Grundstuecke | ClaimService |
| /clan | /c, /gilde | `minesmp.clan` | Clan-System | ClanService |
| /cosmetics | /kosmetik, /trails, /pets | `minesmp.cosmetics` | Kosmetik | CosmeticService |
| /craft | /workbench, /wb | `minesmp.craft` | Werkbank | MiscCommands |
| /crate | /crates, /kisten | `minesmp.crate` | Kisten | CrateService |
| /daily | /taeglich, /tages | `minesmp.daily` | Taegliche Belohnung | RewardService |
| /delhome | /deletehome, /rmhome | `minesmp.home` | Home loeschen | TeleportService |
| /discord | /dc | `minesmp.info` | Discord-Link | MiscCommands |
| /duel | /1v1, /duell | `minesmp.duel` | 1v1-Duelle | DuelService |
| /eco | /economy | `minesmp.admin.eco` | Economy-Verwaltung | EconomyCommands |
| /ecsee | - | `minesmp.staff.invsee` | Enderchest ansehen | StaffCommands |
| /enderchest | /ec, /echest | `minesmp.enderchest` | Enderchest | MiscCommands |
| /event | /events | `minesmp.event` | Events | EventManager |
| /eventadmin | /ea | `minesmp.admin.event` | Events verwalten | EventManager |
| /fakevote | - | `minesmp.admin.vote` | Test-Vote ausloesen | VoteService |
| /farmwelt | /farmworld, /resource, /fw | `minesmp.farmwelt` | Zur Farmwelt | WorldService |
| /gems | /gem | `minesmp.gems` | Deine Gems | TokenService |
| /gemshop | /gemsshop | `minesmp.tokenshop` | Gem-Shop | TokenShopService |
| /help | /hilfe, /? | `minesmp.info` | Hilfe | MiscCommands |
| /history | /strafen, /warns | `minesmp.staff.history` | Strafverlauf | PunishmentService |
| /home | /h, /homes | `minesmp.home` | Zum Home teleportieren | TeleportService |
| /ignore | /block | `minesmp.ignore` | Spieler ignorieren | MiscCommands |
| /invsee | - | `minesmp.staff.invsee` | Inventar ansehen | StaffCommands |
| /keyall | - | `minesmp.keyall` | Naechstes Keyall | KeyallService |
| /keys | /schluessel | `minesmp.crate` | Deine Schluessel | CrateService |
| /kick | - | `minesmp.staff.kick` | Kickt einen Spieler | PunishmentService |
| /kit | /kits | `minesmp.kit` | Kits | KitService |
| /minesmp | /msmp | `minesmp.admin.reload` | MineSMP-Verwaltung | StaffCommands |
| /mitspieler | /lfg, /suche | `minesmp.lfg` | Mitspieler suchen | LfgService |
| /money | /bal, /balance, /geld, /coins | `minesmp.money` | Zeigt deinen Kontostand | EconomyCommands |
| /msg | /tell, /w, /whisper, /m | `minesmp.msg` | Private Nachricht | MiscCommands |
| /msitem | - | `minesmp.admin.items` | Custom Items geben | CustomItemService |
| /mute | - | `minesmp.staff.mute` | Stummschalten | PunishmentService |
| /navi | /navigator, /spawnmenu | `minesmp.spawn` | Spawn-Navigator | SpawnService |
| /order | /orders, /auftrag, /auftraege | `minesmp.orders` | Kaufauftraege | OrderService |
| /pay | /zahlen, /ueberweisen | `minesmp.pay` | Ueberweist MineCoins | EconomyCommands |
| /playtime | /spielzeit, /pt | `minesmp.playtime` | Spielzeit | MiscCommands |
| /poi | /meteor, /kampfzone | `minesmp.event` | Meteoriten & Kampfzonen | PoiService |
| /profile | /profil | `minesmp.profile` | Spielerprofil | ProfileService |
| /pwarp | /pw, /playerwarp | `minesmp.pwarp` | Spieler-Warps | PlayerWarpService |
| /quests | /quest, /aufgaben | `minesmp.quests` | Deine Quests | QuestService |
| /repair | /fix | `minesmp.repair` | Item reparieren | MiscCommands |
| /reply | /r | `minesmp.msg` | Antworten | MiscCommands |
| /report | /melden | `minesmp.report` | Meldet einen Spieler | ReportService |
| /reports | - | `minesmp.staff.reports` | Offene Reports | ReportService |
| /rewards | /belohnungen | `minesmp.rewards` | Belohnungen | RewardService |
| /rtp | /randomtp, /wild | `minesmp.rtp` | Zufaelliger Teleport | TeleportService |
| /rules | /regeln | `minesmp.info` | Serverregeln | MiscCommands |
| /sc | /teamchat | `minesmp.staff.chat` | Teamchat | StaffCommands |
| /sell | /verkaufen | `minesmp.sell` | Items verkaufen | ShopService |
| /sellall | - | `minesmp.sell.all` | Gesamtes Inventar verkaufen | ShopService |
| /sellhand | - | `minesmp.sell` | Item in der Hand verkaufen | ShopService |
| /sethome | /createhome | `minesmp.home` | Home setzen | TeleportService |
| /setspawn | - | `minesmp.admin.setspawn` | Spawn setzen | TeleportService |
| /settings | /einstellungen, /options | `minesmp.settings` | Einstellungen | ProfileService |
| /shop | /markt, /store | `minesmp.shop` | Server-Shop | ShopService |
| /skills | /level, /skill | `minesmp.skills` | Skills & Level | SkillService |
| /spawn | /lobby, /hub | `minesmp.spawn` | Zum Spawn | TeleportService |
| /spawner | /spawners, /spawnershop | `minesmp.spawners` | Spawner-Shop | SpawnerService |
| /staff | /team | `minesmp.staff.list` | Zeigt das Team | StaffCommands |
| /stats | /statistiken | `minesmp.stats` | Statistiken | ProfileService |
| /sterne | /stars, /stern | `minesmp.stars` | Deine Sterne | TokenService |
| /sterneshop | /starshop, /sternshop | `minesmp.tokenshop` | Sterne-Shop | TokenShopService |
| /tempban | - | `minesmp.staff.tempban` | Bannt temporaer | PunishmentService |
| /tempmute | - | `minesmp.staff.mute` | Temporaer stummschalten | PunishmentService |
| /tpa | /tpask | `minesmp.tpa` | Teleport-Anfrage | TeleportService |
| /tpaccept | /tpyes | `minesmp.tpa` | Anfrage annehmen | TeleportService |
| /tpahere | - | `minesmp.tpa` | Spieler zu dir holen | TeleportService |
| /tpdeny | /tpno | `minesmp.tpa` | Anfrage ablehnen | TeleportService |
| /trade | /handel | `minesmp.trade` | Handel mit einem Spieler | TradeService |
| /trash | /muell, /disposal | `minesmp.trash` | Muelleimer | MiscCommands |
| /unban | /pardon | `minesmp.staff.unban` | Entbannt | PunishmentService |
| /unmute | - | `minesmp.staff.mute` | Stummschaltung aufheben | PunishmentService |
| /vanish | /v | `minesmp.staff.vanish` | Unsichtbar werden | StaffCommands |
| /verzauberungen | /ce, /enchants, /verzaubern | `minesmp.ce` | Eigene Verzauberungen | EnchantService |
| /vote | /voten, /votes | `minesmp.vote` | Fuer MineSMP voten | VoteService |
| /warn | - | `minesmp.staff.warn` | Verwarnt einen Spieler | PunishmentService |
