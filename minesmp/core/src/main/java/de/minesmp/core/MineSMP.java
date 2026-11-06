package de.minesmp.core;

import de.minesmp.core.admin.AuditLog;
import de.minesmp.core.admin.PunishmentService;
import de.minesmp.core.admin.ReportService;
import de.minesmp.core.admin.SecurityService;
import de.minesmp.core.admin.StaffCommands;
import de.minesmp.core.admin.Webhook;
import de.minesmp.core.auction.AuctionService;
import de.minesmp.core.clan.ClanService;
import de.minesmp.core.combat.BountyService;
import de.minesmp.core.combat.CombatService;
import de.minesmp.core.combat.DuelService;
import de.minesmp.core.combat.KitService;
import de.minesmp.core.combat.StatsListener;
import de.minesmp.core.cosmetic.CosmeticService;
import de.minesmp.core.crate.CrateService;
import de.minesmp.core.data.PlayerDataManager;
import de.minesmp.core.db.Database;
import de.minesmp.core.display.ChatService;
import de.minesmp.core.display.PlaceholderHook;
import de.minesmp.core.display.Placeholders;
import de.minesmp.core.display.ScoreboardService;
import de.minesmp.core.economy.EconomyCommands;
import de.minesmp.core.economy.EconomyService;
import de.minesmp.core.events.EventManager;
import de.minesmp.core.gui.MenuListener;
import de.minesmp.core.progress.AchievementService;
import de.minesmp.core.progress.BattlePassService;
import de.minesmp.core.progress.QuestService;
import de.minesmp.core.progress.SkillService;
import de.minesmp.core.shop.ChestShopService;
import de.minesmp.core.shop.ShopService;
import de.minesmp.core.trade.TradeService;
import de.minesmp.core.utility.AfkService;
import de.minesmp.core.utility.MiscCommands;
import de.minesmp.core.utility.PlayerWarpService;
import de.minesmp.core.utility.ProfileService;
import de.minesmp.core.utility.RewardService;
import de.minesmp.core.utility.TeleportService;
import de.minesmp.core.utility.VoteService;
import de.minesmp.core.util.BaseCommand;
import de.minesmp.core.util.Cooldowns;
import de.minesmp.core.util.Msg;
import de.minesmp.core.world.CustomItemService;
import de.minesmp.core.world.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public final class MineSMP extends JavaPlugin {
    private static MineSMP instance;

    private Database db;
    private PlayerDataManager data;
    private final Cooldowns cooldowns = new Cooldowns();
    private AuditLog audit;
    private Webhook webhook;
    private EconomyService eco;
    private ShopService shop;
    private AuctionService auction;
    private TradeService trade;
    private ChestShopService chestShops;
    private ClanService clans;
    private CombatService combat;
    private BountyService bounties;
    private KitService kits;
    private DuelService duels;
    private CrateService crates;
    private SkillService skills;
    private QuestService quests;
    private AchievementService achievements;
    private BattlePassService battlepass;
    private EventManager events;
    private ScoreboardService scoreboard;
    private de.minesmp.core.display.ResourcePackService pack;
    private de.minesmp.core.display.RankService ranks;
    private de.minesmp.core.display.InfoBarService infobar;
    private ChatService chat;
    private TeleportService teleport;
    private PlayerWarpService pwarps;
    private AfkService afk;
    private RewardService rewards;
    private ProfileService profile;
    private PunishmentService punish;
    private ReportService reports;
    private SecurityService security;
    private StaffCommands staff;
    private WorldService worlds;
    private CustomItemService customItems;
    private CosmeticService cosmetics;
    private de.minesmp.core.world.SpawnService spawn;
    private Placeholders placeholders;
    private final Set<String> disabledCommands = new HashSet<>();

    public static MineSMP get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        File sbFile = new File(getDataFolder(), "scoreboard.yml");
        if (sbFile.exists() && YamlConfiguration.loadConfiguration(sbFile).getInt("design-version", 1) < 4) {
            File old = new File(getDataFolder(), "scoreboard-alt.yml");
            if (old.exists()) old.delete();
            if (sbFile.renameTo(old)) getLogger().info("Neues Scoreboard-Design: alte Datei als scoreboard-alt.yml gesichert.");
        }
        for (String f : new String[]{"shop.yml", "crates.yml", "kits.yml", "quests.yml", "battlepass.yml", "events.yml",
                "skills.yml", "achievements.yml", "custom-items.yml", "scoreboard.yml", "ranks.yml"}) {
            if (!new File(getDataFolder(), f).exists()) saveResource(f, false);
        }
        Msg.load(this);
        for (String c : getConfig().getStringList("disabled-commands")) disabledCommands.add(c.toLowerCase(Locale.ROOT));

        try {
            db = new Database(this);
            db.connect();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Datenbank nicht erreichbar - MineSMPCore wird deaktiviert!", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        listen(new MenuListener());
        audit = new AuditLog(this);
        webhook = new Webhook(this);
        data = new PlayerDataManager(this);
        listen(data);
        data.loadOnlineSync();

        worlds = new WorldService(this);
        eco = new EconomyService(this);
        new EconomyCommands(this);
        punish = new PunishmentService(this);
        security = new SecurityService(this);
        reports = new ReportService(this);
        staff = new StaffCommands(this);
        customItems = new CustomItemService(this);
        shop = new ShopService(this);
        auction = new AuctionService(this);
        trade = new TradeService(this);
        chestShops = new ChestShopService(this);
        clans = new ClanService(this);
        combat = new CombatService(this);
        bounties = new BountyService(this);
        kits = new KitService(this);
        duels = new DuelService(this);
        listen(new StatsListener(this));
        skills = new SkillService(this);
        quests = new QuestService(this);
        achievements = new AchievementService(this);
        battlepass = new BattlePassService(this);
        crates = new CrateService(this);
        teleport = new TeleportService(this);
        pwarps = new PlayerWarpService(this);
        afk = new AfkService(this);
        rewards = new RewardService(this);
        profile = new ProfileService(this);
        new VoteService(this);
        new MiscCommands(this);
        cosmetics = new CosmeticService(this);
        events = new EventManager(this);
        spawn = new de.minesmp.core.world.SpawnService(this);
        placeholders = new Placeholders(this);
        chat = new ChatService(this);
        pack = new de.minesmp.core.display.ResourcePackService(this);
        ranks = new de.minesmp.core.display.RankService(this);
        scoreboard = new ScoreboardService(this);
        infobar = new de.minesmp.core.display.InfoBarService(this);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PlaceholderHook(this).register();
            getLogger().info("PlaceholderAPI-Erweiterung %minesmp_...% registriert.");
        }
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, Msg::saveIfDirty, 20 * 60, 20 * 60);
        getLogger().info("MineSMPCore " + getPluginMeta().getVersion() + " aktiviert (" + (db.isMariaDb() ? "MariaDB" : "SQLite") + ").");
    }

    @Override
    public void onDisable() {
        try {
            if (events != null) events.stopAll();
            if (duels != null) duels.shutdown();
            if (trade != null) trade.cancelAll();
            if (cosmetics != null) cosmetics.shutdown();
            if (pack != null) pack.shutdown();
            if (ranks != null) ranks.shutdown();
            if (clans != null) clans.saveAll();
            if (bounties != null) bounties.save();
            if (data != null) data.saveAll();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Fehler beim Speichern", e);
        }
        Msg.saveIfDirty();
        if (db != null) db.close();
    }

    public void listen(Listener l) {
        Bukkit.getPluginManager().registerEvents(l, this);
    }

    /** Registriert einen Befehl, sofern er nicht in config.yml deaktiviert ist. */
    public void command(BaseCommand cmd) {
        if (disabledCommands.contains(cmd.getName().toLowerCase(Locale.ROOT))) return;
        Bukkit.getCommandMap().register("minesmp", cmd);
        // Aliase sollen Vanilla-/Bukkit-Befehle (z.B. /tell, /w, /r) ueberschreiben
        if (getConfig().getBoolean("override-aliases", true)) {
            for (String alias : cmd.getAliases()) {
                if (!disabledCommands.contains(alias.toLowerCase(Locale.ROOT))) Bukkit.getCommandMap().getKnownCommands().put(alias.toLowerCase(Locale.ROOT), cmd);
            }
        }
    }

    public YamlConfiguration yaml(String name) {
        File f = new File(getDataFolder(), name);
        if (!f.exists()) saveResource(name, false);
        return YamlConfiguration.loadConfiguration(f);
    }

    public void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(this, r);
    }

    public Database db() { return db; }
    public PlayerDataManager data() { return data; }
    public Cooldowns cooldowns() { return cooldowns; }
    public AuditLog audit() { return audit; }
    public Webhook webhook() { return webhook; }
    public EconomyService eco() { return eco; }
    public ShopService shop() { return shop; }
    public AuctionService auction() { return auction; }
    public TradeService trade() { return trade; }
    public ChestShopService chestShops() { return chestShops; }
    public ClanService clans() { return clans; }
    public CombatService combat() { return combat; }
    public BountyService bounties() { return bounties; }
    public KitService kits() { return kits; }
    public DuelService duels() { return duels; }
    public CrateService crates() { return crates; }
    public SkillService skills() { return skills; }
    public QuestService quests() { return quests; }
    public AchievementService achievements() { return achievements; }
    public BattlePassService battlepass() { return battlepass; }
    public EventManager events() { return events; }
    public ScoreboardService scoreboard() { return scoreboard; }
    public de.minesmp.core.display.ResourcePackService pack() { return pack; }
    public de.minesmp.core.display.RankService ranks() { return ranks; }
    public de.minesmp.core.display.InfoBarService infobar() { return infobar; }
    public ChatService chat() { return chat; }
    public TeleportService teleport() { return teleport; }
    public PlayerWarpService pwarps() { return pwarps; }
    public AfkService afk() { return afk; }
    public RewardService rewards() { return rewards; }
    public ProfileService profile() { return profile; }
    public PunishmentService punish() { return punish; }
    public ReportService reports() { return reports; }
    public SecurityService security() { return security; }
    public StaffCommands staff() { return staff; }
    public WorldService worlds() { return worlds; }
    public CustomItemService customItems() { return customItems; }
    public CosmeticService cosmetics() { return cosmetics; }
    public de.minesmp.core.world.SpawnService spawn() { return spawn; }
    public Placeholders placeholders() { return placeholders; }
}
