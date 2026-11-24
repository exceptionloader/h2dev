--[[
	ShopConfig  (ModuleScript)
	Explorer:  ReplicatedStorage > ShopConfig

	Single source of truth for the Christmas Shop. Shared by the server (ShopService)
	and the client (ShopController). Fill in every ID marked "PUT ... HERE".
	An ID of 0 means "not set up yet": in Studio those purchases are simulated
	(see StudioSimulatePurchases), in a live server they show a friendly message.
]]

local ShopConfig = {}

---------------------------------------------------------------------------
-- General
---------------------------------------------------------------------------
ShopConfig.Font = Enum.Font.FredokaOne -- alternative: Enum.Font.LuckiestGuy
ShopConfig.FallbackFont = Enum.Font.GothamBlack
ShopConfig.ToggleKey = Enum.KeyCode.B -- keyboard shortcut that opens / closes the shop
ShopConfig.GamepadToggleKey = Enum.KeyCode.ButtonY

-- Global default. A player can override it with the attribute "ReducedMotion" (bool)
-- on their Player object, and Roblox' own "Reduce Motion" setting is respected too.
ShopConfig.ReducedMotion = false

-- In Studio, buying an item whose ID is still 0 grants it instantly (no Robux, no dialog).
-- Has no effect in live servers.
ShopConfig.StudioSimulatePurchases = true

ShopConfig.RequireFriendshipForGifts = true -- false = any player in the server can receive gifts
ShopConfig.CurrencyName = "Cash" -- name of the IntValue inside leaderstats
ShopConfig.StartingCash = 0
ShopConfig.BaseWalkSpeed = 16
ShopConfig.SpeedMultiplier = 2
ShopConfig.DailyResetHourUTC = 0 -- the Santa's Sack timer counts down to this hour (UTC) every day
ShopConfig.SackTapsRequired = 7
ShopConfig.RevealTimeoutSeconds = 25 -- server reveals sack cash on the leaderboard after this (+4s per extra roll) even if the client never finishes the animation

ShopConfig.DataStoreName = "ChristmasShop_PlayerData_v1"
ShopConfig.GiftDataStoreName = "ChristmasShop_GiftInbox_v1"

ShopConfig.RobuxGlyph = utf8.char(0xE002) -- renders as the Robux icon in Roblox text

---------------------------------------------------------------------------
-- Colors
---------------------------------------------------------------------------
ShopConfig.Colors = {
	Outline = Color3.fromRGB(22, 18, 32),
	Text = Color3.fromRGB(255, 255, 255),
	Gold = Color3.fromRGB(255, 214, 64),
	GoldDark = Color3.fromRGB(214, 140, 20),
	TimerRed = Color3.fromRGB(255, 62, 82),
	HeaderRed = Color3.fromRGB(226, 52, 74),
	HeaderRedDark = Color3.fromRGB(178, 28, 52),
	BodyNavy = Color3.fromRGB(30, 34, 56),
	BodyNavyDark = Color3.fromRGB(20, 22, 38),
	GreenTop = Color3.fromRGB(122, 238, 84),
	GreenBottom = Color3.fromRGB(40, 182, 52),
	RedButtonTop = Color3.fromRGB(240, 70, 90),
	RedButtonBottom = Color3.fromRGB(168, 24, 52),
	SackBlueTop = Color3.fromRGB(92, 170, 255),
	SackBlueBottom = Color3.fromRGB(40, 92, 210),
	PassBlueTop = Color3.fromRGB(120, 196, 255),
	PassBlueBottom = Color3.fromRGB(52, 120, 226),
	PassGreenTop = Color3.fromRGB(150, 245, 110),
	PassGreenBottom = Color3.fromRGB(46, 190, 70),
	CashPurpleTop = Color3.fromRGB(226, 120, 255),
	CashPurpleBottom = Color3.fromRGB(148, 52, 214),
	Snow = Color3.fromRGB(255, 255, 255),
	SnowShade = Color3.fromRGB(206, 226, 255),
	Confetti = {
		Color3.fromRGB(255, 72, 96),
		Color3.fromRGB(80, 220, 110),
		Color3.fromRGB(255, 214, 64),
		Color3.fromRGB(90, 170, 255),
		Color3.fromRGB(255, 255, 255),
	},
}

---------------------------------------------------------------------------
-- Icons
-- Upload your images (Creator Dashboard > Development Items > Decals/Images)
-- and paste the IMAGE asset id here as "rbxassetid://123456".
-- While an id is "rbxassetid://0" the UI uses the emoji fallback below,
-- so everything already works and looks recognizable in Studio.
-- Light rays and snow clouds are drawn procedurally while their id is 0.
---------------------------------------------------------------------------
ShopConfig.Icons = {
	-- Prompt: "Cartoon Santa Claus waving from a red and gold sleigh full of presents, thick black outline, glossy mobile game icon, transparent background"
	SantaSleigh = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Big red Santa sack overflowing with colorful wrapped presents, tied with a gold rope, thick black outline, glossy cartoon game icon, transparent background"
	SantaSack = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Red and white striped candy cane, glossy highlight, thick black outline, cartoon mobile game icon, transparent background"
	CandyCane = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Green gift box with a big shiny red ribbon bow, isometric 3/4 view, thick black outline, glossy cartoon game icon, transparent background"
	Present = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Red Christmas stocking with white fluffy cuff stuffed with green cash bills, thick black outline, glossy cartoon game icon, transparent background"
	Stocking = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Chunky golden snowflake with glossy shine, thick dark outline, cartoon mobile game icon, transparent background"
	GoldenSnowflake = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Pair of red ice skates with white angel wings, motion lines, thick black outline, glossy cartoon game icon, transparent background"
	Skates = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Shiny golden four-leaf clover with a small holly sprig and red berries, thick black outline, glossy cartoon game icon, transparent background"
	Clover = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Small stack of green cash bills tied with a red ribbon, thick black outline, glossy cartoon game icon, transparent background"
	CashSmall = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Open red gift box overflowing with green cash bills and gold coins, thick black outline, glossy cartoon game icon, transparent background"
	CashMedium = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Huge red sack bursting with green cash bills and gold coins, thick black outline, glossy cartoon game icon, transparent background"
	CashLarge = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Cute snowman waving with one stick arm, red scarf and red bobble hat, thick black outline, cartoon game sticker, transparent background"
	Snowman = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Small Christmas tree with red baubles, golden star and snow on the branches, thick black outline, cartoon game sticker, transparent background"
	ChristmasTree = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Two golden jingle bells with a red bow and holly leaves hanging from a short garland, thick black outline, cartoon sticker, transparent background"
	JingleBells = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Holly leaves with three red berries, corner decoration, thick black outline, cartoon sticker, transparent background"
	HollyCorner = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Small red gift box icon with a white ribbon, flat glossy cartoon UI icon, thick black outline, transparent background"
	Gift = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Radial golden sunburst light rays, soft edges fading out, centered, transparent background, game UI effect"
	LightRays = "rbxassetid://0", -- PUT IMAGE ID HERE (optional, procedural rays are used while 0)
	-- Prompt: "Simple white snowflake particle, soft glow, transparent background, game UI particle"
	Snowflake = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "White four-point sparkle star with soft glow, transparent background, game UI particle"
	Sparkle = "rbxassetid://0", -- PUT IMAGE ID HERE
	-- Prompt: "Puffy white cartoon snow cloud with light blue shading, thick soft outline, transparent background, game UI sticker"
	SnowCloud = "rbxassetid://0", -- PUT IMAGE ID HERE (optional, procedural clouds are used while 0)
}

-- Shown (as text) while the matching icon id is still 0.
ShopConfig.IconFallback = {
	SantaSleigh = "🛷",
	SantaSack = "🎅",
	CandyCane = "🍬",
	Present = "🎁",
	Stocking = "🧦",
	GoldenSnowflake = "🌟",
	Skates = "⛸️",
	Clover = "🍀",
	CashSmall = "💵",
	CashMedium = "💰",
	CashLarge = "🤑",
	Snowman = "⛄",
	ChristmasTree = "🎄",
	JingleBells = "🔔",
	HollyCorner = "🌿",
	Gift = "🎁",
	Snowflake = "❄",
	Sparkle = "✦",
}

function ShopConfig.hasImage(iconKey: string): boolean
	local id = ShopConfig.Icons[iconKey]
	return type(id) == "string" and id ~= "" and id ~= "rbxassetid://0"
end

---------------------------------------------------------------------------
-- Developer products
--   Id      = the normal product (buyer receives it)
--   GiftId  = a separate product with the same price, used when gifting to a friend
-- Create them under Creator Dashboard > your experience > Monetization > Developer Products.
---------------------------------------------------------------------------
ShopConfig.Products = {
	Sack1 = {
		Kind = "Sack",
		Id = 0, -- PUT DEV PRODUCT ID HERE ("Santa's Sack x1", 99 R$)
		GiftId = 0, -- PUT DEV PRODUCT ID HERE ("Gift: Santa's Sack x1", 99 R$)
		Price = 99,
		Count = 1,
		Label = "Buy 1",
		DisplayName = "Santa's Sack",
	},
	Sack5 = {
		Kind = "Sack",
		Id = 0, -- PUT DEV PRODUCT ID HERE ("Santa's Sack x5", 449 R$)
		GiftId = 0, -- PUT DEV PRODUCT ID HERE ("Gift: Santa's Sack x5", 449 R$)
		Price = 449,
		Count = 5,
		Label = "Buy 5",
		DisplayName = "5 Santa's Sacks",
	},
	Sack10 = {
		Kind = "Sack",
		Id = 0, -- PUT DEV PRODUCT ID HERE ("Santa's Sack x10", 799 R$)
		GiftId = 0, -- PUT DEV PRODUCT ID HERE ("Gift: Santa's Sack x10", 799 R$)
		Price = 799,
		Count = 10,
		Label = "Buy 10",
		DisplayName = "10 Santa's Sacks",
	},
	Cash25K = {
		Kind = "Cash",
		Id = 0, -- PUT DEV PRODUCT ID HERE ("+25K Cash", 99 R$)
		GiftId = 0, -- PUT DEV PRODUCT ID HERE ("Gift: +25K Cash", 99 R$)
		Price = 99,
		Amount = 25000,
		Title = "+25K CASH",
		Icon = "CashSmall",
		IconScale = 0.78,
		DisplayName = "+25K Cash",
	},
	Cash100K = {
		Kind = "Cash",
		Id = 0, -- PUT DEV PRODUCT ID HERE ("+100K Cash", 249 R$)
		GiftId = 0, -- PUT DEV PRODUCT ID HERE ("Gift: +100K Cash", 249 R$)
		Price = 249,
		Amount = 100000,
		Title = "+100K CASH",
		Icon = "CashMedium",
		IconScale = 0.9,
		DisplayName = "+100K Cash",
	},
	Cash500K = {
		Kind = "Cash",
		Id = 0, -- PUT DEV PRODUCT ID HERE ("+500K Cash", 799 R$)
		GiftId = 0, -- PUT DEV PRODUCT ID HERE ("Gift: +500K Cash", 799 R$)
		Price = 799,
		Amount = 500000,
		Title = "+500K CASH",
		Icon = "CashLarge",
		IconScale = 1.02,
		DisplayName = "+500K Cash",
	},
}

-- Display order of the rows inside the window.
ShopConfig.SackOrder = { "Sack10", "Sack5", "Sack1" }
ShopConfig.CashOrder = { "Cash25K", "Cash100K", "Cash500K" }
ShopConfig.PassOrder = { "Speed", "Luck" }

---------------------------------------------------------------------------
-- Gamepasses
--   Id     = the gamepass id (Monetization > Passes)
--   GiftId = a DEVELOPER PRODUCT with the same price; buying it for a friend
--            unlocks the perk for them permanently (stored in their save data).
---------------------------------------------------------------------------
ShopConfig.GamePasses = {
	Speed = {
		Kind = "GamePass",
		Id = 0, -- PUT GAMEPASS ID HERE ("2x Speed", 199 R$)
		GiftId = 0, -- PUT DEV PRODUCT ID HERE ("Gift: 2x Speed", 199 R$)
		Price = 199,
		Title = "2X SPEED",
		Line1 = "Run",
		Line2 = "2X FASTER!",
		Icon = "Skates",
		Theme = "Blue",
		DisplayName = "2x Speed",
	},
	Luck = {
		Kind = "GamePass",
		Id = 0, -- PUT GAMEPASS ID HERE ("2x Luck", 249 R$)
		GiftId = 0, -- PUT DEV PRODUCT ID HERE ("Gift: 2x Luck", 249 R$)
		Price = 249,
		Title = "2X LUCK",
		Line1 = "Find",
		Line2 = "2X RARER!",
		Icon = "Clover",
		Theme = "Green",
		DisplayName = "2x Luck",
	},
}

---------------------------------------------------------------------------
-- Santa's Sack loot table
--   Weight          base chance (the 4 weights add up to 100 so they read as %)
--   LuckMultiplier  weight multiplier while the player owns 2x Luck
--   Cash            reward granted for that roll
---------------------------------------------------------------------------
ShopConfig.Loot = {
	{
		Key = "CandyCane",
		Name = "CANDY CANE",
		Icon = "CandyCane",
		Weight = 45,
		LuckMultiplier = 1,
		Cash = 1000,
		TileTop = Color3.fromRGB(120, 200, 255),
		TileBottom = Color3.fromRGB(56, 130, 230),
	},
	{
		Key = "Present",
		Name = "PRESENT",
		Icon = "Present",
		Weight = 30,
		LuckMultiplier = 1.25,
		Cash = 10000,
		TileTop = Color3.fromRGB(190, 120, 255),
		TileBottom = Color3.fromRGB(120, 56, 214),
	},
	{
		Key = "Stocking",
		Name = "STOCKING",
		Icon = "Stocking",
		Weight = 20,
		LuckMultiplier = 2,
		Cash = 50000,
		TileTop = Color3.fromRGB(255, 130, 178),
		TileBottom = Color3.fromRGB(222, 62, 120),
	},
	{
		Key = "GoldenSnowflake",
		Name = "GOLDEN SNOWFLAKE",
		Icon = "GoldenSnowflake",
		Weight = 5,
		LuckMultiplier = 2.5,
		Cash = 250000,
		Announce = true,
		Shimmer = true,
		TileTop = Color3.fromRGB(255, 226, 92),
		TileBottom = Color3.fromRGB(240, 156, 24),
	},
}

---------------------------------------------------------------------------
-- Helpers (shared by client and server)
---------------------------------------------------------------------------
function ShopConfig.getLoot(key: string)
	for _, entry in ShopConfig.Loot do
		if entry.Key == key then
			return entry
		end
	end
	return nil
end

function ShopConfig.getLootWeights(hasLuck: boolean): { number }
	local weights = {}
	for i, entry in ShopConfig.Loot do
		weights[i] = entry.Weight * (if hasLuck then entry.LuckMultiplier else 1)
	end
	return weights
end

-- Returns { [lootKey] = percent (0-100) }
function ShopConfig.getLootOdds(hasLuck: boolean): { [string]: number }
	local weights = ShopConfig.getLootWeights(hasLuck)
	local total = 0
	for _, w in weights do
		total += w
	end
	local odds = {}
	for i, entry in ShopConfig.Loot do
		odds[entry.Key] = if total > 0 then weights[i] / total * 100 else 0
	end
	return odds
end

-- Looks up any purchasable item by key ("Sack1", "Cash25K", "Speed", ...)
function ShopConfig.getItem(itemKey: string)
	if type(itemKey) ~= "string" then
		return nil
	end
	return ShopConfig.Products[itemKey] or ShopConfig.GamePasses[itemKey]
end

function ShopConfig.formatNumber(n: number): string
	local s = tostring(math.floor(n))
	local formatted = s:reverse():gsub("(%d%d%d)", "%1,"):reverse()
	if formatted:sub(1, 1) == "," then
		formatted = formatted:sub(2)
	end
	return formatted
end

function ShopConfig.formatOdds(percent: number): string
	if percent >= 10 or math.abs(percent - math.round(percent)) < 0.05 then
		return `{math.round(percent)}%`
	end
	return string.format("%.1f%%", percent)
end

-- Next daily reset as a unix timestamp (UTC).
function ShopConfig.getNextReset(now: number): number
	local offset = ShopConfig.DailyResetHourUTC * 3600
	return ((now - offset) // 86400 + 1) * 86400 + offset
end

return ShopConfig
