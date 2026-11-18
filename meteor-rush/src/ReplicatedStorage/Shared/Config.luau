-- METEOR RUSH :: zentrale Konfiguration (Server + Client)
-- Alles was du balancen oder monetarisieren willst, stellst du HIER ein.

local Config = {}

Config.GameName = "METEOR RUSH"
Config.Version = "1.0.0"

-- DataStores (Namen ändern = alle Spielstände werden zurückgesetzt!)
Config.DataStoreName = "MeteorRush_Data_v1"
Config.LeaderboardStoreName = "MeteorRush_TopEarned_v1"

---------------------------------------------------------------------------
-- WELT
---------------------------------------------------------------------------
Config.MaxPlots = 8 -- = maximale Spieler pro Server (in den Game Settings auf 8 stellen!)
Config.PlotRadius = 235 -- Abstand der Basen vom Zentrum
Config.PlotSize = 72
Config.IslandRadius = 440
Config.GroundHeight = 8
Config.SafeZoneRadius = 58 -- im Zentrum kann niemand gebonkt werden

function Config.PlotCFrame(index)
	local angle = (index - 1) / Config.MaxPlots * math.pi * 2 + math.pi / Config.MaxPlots
	local pos = Vector3.new(math.cos(angle) * Config.PlotRadius, Config.GroundHeight, math.sin(angle) * Config.PlotRadius)
	-- -Z (LookVector) zeigt zum Zentrum = Eingang der Basis
	return CFrame.lookAt(pos, Vector3.new(0, Config.GroundHeight, 0))
end

---------------------------------------------------------------------------
-- METEORE / CORES
---------------------------------------------------------------------------
Config.BaseSlots = 4
Config.MaxSlots = 16
Config.GlobalMeteorInterval = { 4, 7 } -- Sekunden (min, max)
Config.PersonalMeteorInterval = { 16, 24 } -- jeder Spieler bekommt "seine" Meteore in der Nähe
Config.MaxWildCores = 40
Config.FallTime = 3.2
Config.WildLifetime = 75
Config.SellMultiplier = 30 -- Verkaufspreis = Einkommen/s * Multiplikator
Config.OfflineEarningsRate = 0.25
Config.OfflineMaxSeconds = 4 * 3600
Config.StolenSpeedPenalty = 0.8

Config.Rarities = {
	{ Id = "Common", Order = 1, Weight = 5500, Color = Color3.fromRGB(205, 214, 224) },
	{ Id = "Uncommon", Order = 2, Weight = 2800, Color = Color3.fromRGB(96, 230, 120) },
	{ Id = "Rare", Order = 3, Weight = 1150, Color = Color3.fromRGB(70, 165, 255) },
	{ Id = "Epic", Order = 4, Weight = 400, Color = Color3.fromRGB(184, 96, 255) },
	{ Id = "Legendary", Order = 5, Weight = 120, Color = Color3.fromRGB(255, 190, 40) },
	{ Id = "Mythic", Order = 6, Weight = 26, Color = Color3.fromRGB(255, 60, 110) },
	{ Id = "Cosmic", Order = 7, Weight = 4, Color = Color3.fromRGB(80, 255, 240) },
}

Config.Cores = {
	{ Id = "Pebble", Name = "Space Pebble", Rarity = "Common", Income = 1, Color = Color3.fromRGB(190, 196, 210), Shell = Color3.fromRGB(95, 98, 112) },
	{ Id = "Dust", Name = "Stardust Clump", Rarity = "Common", Income = 2, Color = Color3.fromRGB(255, 240, 200), Shell = Color3.fromRGB(150, 135, 120) },
	{ Id = "Ember", Name = "Ember Core", Rarity = "Uncommon", Income = 6, Color = Color3.fromRGB(255, 120, 40), Shell = Color3.fromRGB(90, 40, 30) },
	{ Id = "Frost", Name = "Frost Shard", Rarity = "Uncommon", Income = 9, Color = Color3.fromRGB(150, 230, 255), Shell = Color3.fromRGB(200, 240, 255) },
	{ Id = "Nebula", Name = "Nebula Orb", Rarity = "Rare", Income = 28, Color = Color3.fromRGB(120, 110, 255), Shell = Color3.fromRGB(60, 40, 130) },
	{ Id = "Plasma", Name = "Plasma Heart", Rarity = "Rare", Income = 40, Color = Color3.fromRGB(60, 200, 255), Shell = Color3.fromRGB(20, 70, 120) },
	{ Id = "Solar", Name = "Solar Flare", Rarity = "Epic", Income = 130, Color = Color3.fromRGB(255, 200, 60), Shell = Color3.fromRGB(255, 110, 20) },
	{ Id = "Toxic", Name = "Toxic Comet", Rarity = "Epic", Income = 175, Color = Color3.fromRGB(140, 255, 60), Shell = Color3.fromRGB(40, 90, 30) },
	{ Id = "Aurora", Name = "Aurora Crystal", Rarity = "Legendary", Income = 600, Color = Color3.fromRGB(100, 255, 190), Shell = Color3.fromRGB(190, 120, 255) },
	{ Id = "Inferno", Name = "Inferno Titan", Rarity = "Legendary", Income = 850, Color = Color3.fromRGB(255, 70, 20), Shell = Color3.fromRGB(60, 15, 10) },
	{ Id = "Eclipse", Name = "Eclipse Eye", Rarity = "Mythic", Income = 3500, Color = Color3.fromRGB(255, 40, 90), Shell = Color3.fromRGB(15, 10, 25) },
	{ Id = "Quasar", Name = "Quasar Crown", Rarity = "Mythic", Income = 5000, Color = Color3.fromRGB(255, 120, 230), Shell = Color3.fromRGB(80, 30, 120) },
	{ Id = "Singularity", Name = "Singularity", Rarity = "Cosmic", Income = 25000, Color = Color3.fromRGB(80, 255, 240), Shell = Color3.fromRGB(5, 5, 12) },
	{ Id = "BigBang", Name = "Big Bang Seed", Rarity = "Cosmic", Income = 40000, Color = Color3.fromRGB(255, 255, 255), Shell = Color3.fromRGB(255, 200, 90) },
}

Config.Mutations = {
	{ Id = "None", Name = "", Weight = 9300, Mult = 1 },
	{ Id = "Golden", Name = "Golden", Weight = 560, Mult = 3, Color = Color3.fromRGB(255, 205, 60) },
	{ Id = "Rainbow", Name = "Rainbow", Weight = 120, Mult = 7, Color = Color3.fromRGB(255, 100, 200) },
	{ Id = "Void", Name = "Void", Weight = 20, Mult = 20, Color = Color3.fromRGB(140, 40, 255) },
}

---------------------------------------------------------------------------
-- UPGRADES & REBIRTH
---------------------------------------------------------------------------
Config.Upgrades = {
	Speed = { Name = "Speed Boots", Icon = "👟", Max = 12, BaseCost = 150, Growth = 2.0, PerLevel = 1.5, Desc = "+1.5 WalkSpeed per level" },
	Luck = { Name = "Lucky Charm", Icon = "🍀", Max = 15, BaseCost = 400, Growth = 2.15, PerLevel = 0.15, Desc = "+15% meteor luck per level" },
	Slots = { Name = "Base Expansion", Icon = "🧱", Max = 12, BaseCost = 300, Growth = 2.6, PerLevel = 1, Desc = "+1 pedestal in your base" },
}
Config.UpgradeOrder = { "Speed", "Luck", "Slots" }

function Config.UpgradeCost(id, level)
	local u = Config.Upgrades[id]
	return math.floor(u.BaseCost * u.Growth ^ level)
end

function Config.RebirthCost(rebirths)
	return math.floor(50000 * 4 ^ rebirths)
end

function Config.RebirthMultiplier(rebirths)
	return 1 + rebirths * 0.5
end

---------------------------------------------------------------------------
-- MONETARISIERUNG  (IDs aus dem Creator Hub eintragen! 0 = deaktiviert)
---------------------------------------------------------------------------
Config.GamePasses = {
	VIP = { Id = 0, Name = "VIP", Icon = "👑", Desc = "2x Cash forever + VIP chat tag", Price = 249 },
	AutoCollect = { Id = 0, Name = "Auto Collect", Icon = "🧲", Desc = "Base cash flies straight into your wallet", Price = 149 },
	Lucky = { Id = 0, Name = "Super Luck", Icon = "🍀", Desc = "2x meteor luck forever", Price = 299 },
	Speed = { Id = 0, Name = "Rocket Boots", Icon = "🚀", Desc = "+8 WalkSpeed forever", Price = 99 },
}
Config.GamePassOrder = { "VIP", "AutoCollect", "Lucky", "Speed" }

Config.Products = {
	Shower = { Id = 0, Name = "Meteor Shower", Icon = "☄️", Desc = "Summon a server-wide meteor shower! Everyone sees your name.", Price = 99 },
	LuckyMeteor = { Id = 0, Name = "Lucky Meteor", Icon = "🌟", Desc = "A guaranteed Legendary+ meteor crashes next to you", Price = 149 },
	CashSmall = { Id = 0, Name = "Cash Pouch", Icon = "💰", Desc = "10 minutes of income (min 5K)", Minutes = 10, Min = 5000, Price = 49 },
	CashBig = { Id = 0, Name = "Cash Vault", Icon = "🏦", Desc = "60 minutes of income (min 50K)", Minutes = 60, Min = 50000, Price = 199 },
}
Config.ProductOrder = { "Shower", "LuckyMeteor", "CashSmall", "CashBig" }

---------------------------------------------------------------------------
-- SERVER EVENTS
---------------------------------------------------------------------------
Config.EventInterval = 420
Config.EventDuration = 120
Config.Events = {
	Shower = { Name = "METEOR SHOWER", Desc = "Meteors fall 4x faster!", Weight = 40, SpawnMult = 4, Color = Color3.fromRGB(255, 130, 50) },
	Lucky = { Name = "LUCKY SKIES", Desc = "3x luck for everyone!", Weight = 30, LuckMult = 3, Color = Color3.fromRGB(90, 255, 130) },
	Golden = { Name = "GOLDEN HOUR", Desc = "5x Golden mutations!", Weight = 20, GoldenMult = 5, Color = Color3.fromRGB(255, 205, 60) },
	Cosmic = { Name = "COSMIC STORM", Desc = "10x Cosmic meteors!", Weight = 10, CosmicMult = 10, SpawnMult = 2, Color = Color3.fromRGB(120, 80, 255) },
}

---------------------------------------------------------------------------
-- DAILY REWARD & CODES
---------------------------------------------------------------------------
Config.DailyCooldown = 20 * 3600
Config.DailyBase = 750
Config.DailyMaxStreak = 7

Config.Codes = {
	LAUNCH = { Cash = 2500 },
	METEOR = { Cash = 7500 },
	STARFALL = { Cash = 15000 },
}

---------------------------------------------------------------------------
-- SOUNDS (optional: eigene Sound-IDs aus der Toolbox eintragen, 0 = aus)
---------------------------------------------------------------------------
Config.Sounds = {
	Music = 0,
	Collect = 0,
	Legendary = 0,
	Click = 0,
}

---------------------------------------------------------------------------
-- Lookups
---------------------------------------------------------------------------
Config.RarityById = {}
for _, r in ipairs(Config.Rarities) do
	Config.RarityById[r.Id] = r
end
Config.CoreById = {}
Config.CoresByRarity = {}
for _, c in ipairs(Config.Cores) do
	Config.CoreById[c.Id] = c
	Config.CoresByRarity[c.Rarity] = Config.CoresByRarity[c.Rarity] or {}
	table.insert(Config.CoresByRarity[c.Rarity], c)
end
Config.MutationById = {}
for _, m in ipairs(Config.Mutations) do
	Config.MutationById[m.Id] = m
end

function Config.CoreIncome(coreId, mutation)
	local core = Config.CoreById[coreId]
	if not core then
		return 0
	end
	local mut = Config.MutationById[mutation or "None"] or Config.MutationById.None
	return core.Income * mut.Mult
end

function Config.CoreDisplayName(coreId, mutation)
	local core = Config.CoreById[coreId]
	local mut = Config.MutationById[mutation or "None"]
	local name = core and core.Name or "???"
	if mut and mut.Name ~= "" then
		return mut.Name .. " " .. name
	end
	return name
end

return Config
