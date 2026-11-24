--[[
	ShopService  (Script)
	Explorer:  ServerScriptService > ShopService
	Children:  PlayerData, GiftInbox, RateLimiter (ModuleScripts)

	Server-authoritative backend of the Christmas Shop:
	  * creates the remotes (ReplicatedStorage > ChristmasShopRemotes)
	  * prompts purchases (the client only *asks*)
	  * MarketplaceService.ProcessReceipt for every developer product,
	    idempotent via the receipt log in PlayerData; PurchaseGranted is only
	    returned after the grant is saved
	  * Santa's Sack rolls with Random.new() on the server (2x Luck aware);
	    results are sent to the client BEFORE the minigame plays
	  * gamepass ownership + perks (2x WalkSpeed, 2x Luck)
	  * gifting through dedicated gift products + the cross-server GiftInbox
	  * daily reset timestamp for the countdown timer
	  * rate limits + input validation on every remote
]]

local MarketplaceService = game:GetService("MarketplaceService")
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local RunService = game:GetService("RunService")
local HttpService = game:GetService("HttpService")

local ShopConfig = require(ReplicatedStorage:WaitForChild("ShopConfig"))
local PlayerData = require(script:WaitForChild("PlayerData"))
local GiftInbox = require(script:WaitForChild("GiftInbox"))
local RateLimiter = require(script:WaitForChild("RateLimiter"))

local IS_STUDIO = RunService:IsStudio()
local GIFT_INTENT_TTL = 10 * 60 -- a gift prompt is valid for 10 minutes
local GIFT_POLL_INTERVAL = 60

---------------------------------------------------------------------------
-- Remotes
---------------------------------------------------------------------------
local remotesFolder = ReplicatedStorage:FindFirstChild("ChristmasShopRemotes")
if not remotesFolder then
	remotesFolder = Instance.new("Folder")
	remotesFolder.Name = "ChristmasShopRemotes"
end

local function makeRemote(name: string): RemoteEvent
	local existing = remotesFolder:FindFirstChild(name)
	if existing and existing:IsA("RemoteEvent") then
		return existing
	end
	local remote = Instance.new("RemoteEvent")
	remote.Name = name
	remote.Parent = remotesFolder
	return remote
end

local Remotes = {
	ClientReady = makeRemote("ClientReady"), -- C->S  ()
	PurchaseRequest = makeRemote("PurchaseRequest"), -- C->S  (itemKey: string, giftRecipientUserId: number?)
	SackRevealDone = makeRemote("SackRevealDone"), -- C->S  (batchId: number)
	ShopState = makeRemote("ShopState"), -- S->C  (state)
	SackResults = makeRemote("SackResults"), -- S->C  ({ BatchId, Results, Cash, FromName })
	Notify = makeRemote("Notify"), -- S->C  ({ Text, Kind, Icon })
	Announce = makeRemote("Announce"), -- S->all ({ Text })
}
remotesFolder.Parent = ReplicatedStorage

local limits = {
	ClientReady = RateLimiter.new(3, 10),
	PurchaseRequest = RateLimiter.new(6, 10),
	SackRevealDone = RateLimiter.new(20, 10),
}

---------------------------------------------------------------------------
-- Product lookup tables
---------------------------------------------------------------------------
type ProductRoute = { Key: string, Gift: boolean }
local productRoutes: { [number]: ProductRoute } = {}
local passIdToKey: { [number]: string } = {}

local function registerProduct(id: number, key: string, gift: boolean)
	if id == 0 then
		return
	end
	if productRoutes[id] then
		warn(`[ShopService] Product id {id} is used twice in ShopConfig ({productRoutes[id].Key} and {key}). Every product needs its own id!`)
	end
	productRoutes[id] = { Key = key, Gift = gift }
end

for key, def in ShopConfig.Products do
	registerProduct(def.Id, key, false)
	registerProduct(def.GiftId, key, true)
end
for key, def in ShopConfig.GamePasses do
	registerProduct(def.GiftId, key, true)
	if def.Id ~= 0 then
		passIdToKey[def.Id] = key
	end
end

---------------------------------------------------------------------------
-- Per-player session state
---------------------------------------------------------------------------
type Batch = { Held: number, Results: { string }, Timeout: thread? }
type Session = {
	Ready: boolean,
	OwnsPass: { [string]: boolean },
	HeldCash: number,
	Batches: { [number]: Batch },
	BatchCounter: number,
	Queue: { any },
	PendingGift: { ItemKey: string, RecipientId: number, RecipientName: string, At: number }?,
	ClaimingGifts: boolean,
}
local sessions: { [Player]: Session } = {}
local pendingPostGrant: { [string]: { Player: Player, Info: any, Context: any } } = {}

local function notify(player: Player, text: string, kind: string?, icon: string?)
	if player.Parent then
		Remotes.Notify:FireClient(player, { Text = text, Kind = kind or "Info", Icon = icon })
	end
end

local function ownsPass(player: Player, passKey: string): boolean
	local session = sessions[player]
	if session and session.OwnsPass[passKey] then
		return true
	end
	local profile = PlayerData.get(player)
	return profile ~= nil and profile.Data.GiftedPasses[passKey] == true
end

local function getCashValue(player: Player): IntValue?
	local leaderstats = player:FindFirstChild("leaderstats")
	local value = leaderstats and leaderstats:FindFirstChild(ShopConfig.CurrencyName)
	if value and value:IsA("IntValue") then
		return value
	end
	return nil
end

-- leaderstats shows saved cash minus cash still "hidden" behind an unrevealed sack
local function refreshLeaderstats(player: Player)
	local profile = PlayerData.get(player)
	local session = sessions[player]
	local value = getCashValue(player)
	if profile and value then
		value.Value = profile.Data.Cash - (if session then session.HeldCash else 0)
	end
end

local function sendState(player: Player)
	local session = sessions[player]
	if not session or not session.Ready then
		return
	end
	local owned = {}
	for passKey in ShopConfig.GamePasses do
		owned[passKey] = ownsPass(player, passKey)
	end
	Remotes.ShopState:FireClient(player, {
		Owned = owned,
		ResetAt = ShopConfig.getNextReset(os.time()),
		DataLoaded = PlayerData.get(player) ~= nil,
	})
end

---------------------------------------------------------------------------
-- Perks
---------------------------------------------------------------------------
local function applySpeed(player: Player)
	local character = player.Character
	local humanoid = character and character:FindFirstChildOfClass("Humanoid")
	if not humanoid then
		return
	end
	if ownsPass(player, "Speed") then
		humanoid.WalkSpeed = ShopConfig.BaseWalkSpeed * ShopConfig.SpeedMultiplier
	end
end

---------------------------------------------------------------------------
-- Santa's Sack rolling (server only)
---------------------------------------------------------------------------
local rng = Random.new()

local function rollSack(count: number, hasLuck: boolean): { string }
	local weights = ShopConfig.getLootWeights(hasLuck)
	local total = 0
	for _, w in weights do
		total += w
	end
	local results = table.create(count)
	for i = 1, count do
		local roll = rng:NextNumber() * total
		local picked = ShopConfig.Loot[#ShopConfig.Loot].Key
		for index, w in weights do
			roll -= w
			if roll < 0 then
				picked = ShopConfig.Loot[index].Key
				break
			end
		end
		results[i] = picked
	end
	return results
end

---------------------------------------------------------------------------
-- Granting
---------------------------------------------------------------------------
-- Applies an item to a loaded profile IN MEMORY and describes what happened.
local function applyItem(recipient: Player, profile, itemKey: string, robuxSpent: number?)
	local def = ShopConfig.getItem(itemKey)
	if not def then
		return nil
	end
	if robuxSpent then
		profile.Data.Stats.RobuxSpent += robuxSpent
	end
	if def.Kind == "Cash" then
		profile.Data.Cash += def.Amount
		return { Type = "Cash", ItemKey = itemKey, Amount = def.Amount }
	elseif def.Kind == "Sack" then
		local results = rollSack(def.Count, ownsPass(recipient, "Luck"))
		local cash = 0
		for _, lootKey in results do
			local loot = ShopConfig.getLoot(lootKey)
			cash += loot.Cash
			profile.Data.Stats.Found[lootKey] = (profile.Data.Stats.Found[lootKey] or 0) + 1
		end
		profile.Data.Stats.SacksOpened += def.Count
		profile.Data.Cash += cash
		return { Type = "Sack", ItemKey = itemKey, Results = results, Cash = cash }
	elseif def.Kind == "GamePass" then
		profile.Data.GiftedPasses[itemKey] = true
		return { Type = "Pass", ItemKey = itemKey }
	end
	return nil
end

local function releaseBatch(player: Player, batchId: number)
	local session = sessions[player]
	if not session then
		return
	end
	local batch = session.Batches[batchId]
	if not batch then
		return
	end
	session.Batches[batchId] = nil
	if batch.Timeout and coroutine.running() ~= batch.Timeout then
		pcall(task.cancel, batch.Timeout)
	end
	session.HeldCash = math.max(0, session.HeldCash - batch.Held)
	refreshLeaderstats(player)

	for _, lootKey in batch.Results do
		local loot = ShopConfig.getLoot(lootKey)
		if loot and loot.Announce then
			Remotes.Announce:FireAllClients({
				Text = `🌟 {player.DisplayName} found a {loot.Name} in Santa's Sack! 🌟`,
			})
		end
	end
end

local function deliverSackResults(player: Player, payload)
	local session = sessions[player]
	if not session then
		return
	end
	if session.Ready then
		Remotes.SackResults:FireClient(player, payload)
	else
		table.insert(session.Queue, payload)
	end
end

-- Effects that happen after a grant has been saved (UI, perks, leaderstats).
local function postGrant(player: Player, info, context: { Source: string, FromName: string? })
	local session = sessions[player]
	if not session or not info then
		return
	end
	local def = ShopConfig.getItem(info.ItemKey)
	local fromText = if context.FromName then `🎁 {context.FromName} gifted you ` else ""

	if info.Type == "Cash" then
		refreshLeaderstats(player)
		local text = if fromText ~= "" then `{fromText}{def.DisplayName}!` else `+{ShopConfig.formatNumber(info.Amount)} {ShopConfig.CurrencyName}!`
		notify(player, text, "Success", def.Icon)
	elseif info.Type == "Pass" then
		applySpeed(player)
		sendState(player)
		notify(player, if fromText ~= "" then `{fromText}{def.DisplayName}!` else `{def.DisplayName} unlocked!`, "Success", def.Icon)
	elseif info.Type == "Sack" then
		session.BatchCounter += 1
		local batchId = session.BatchCounter
		local batch: Batch = { Held = info.Cash, Results = info.Results, Timeout = nil }
		session.Batches[batchId] = batch
		session.HeldCash += info.Cash
		refreshLeaderstats(player)
		local timeout = ShopConfig.RevealTimeoutSeconds + (#info.Results - 1) * 4
		batch.Timeout = task.delay(timeout, function()
			releaseBatch(player, batchId)
		end)
		deliverSackResults(player, {
			BatchId = batchId,
			Results = info.Results,
			Cash = info.Cash,
			FromName = context.FromName,
		})
	end
end

---------------------------------------------------------------------------
-- Purchases
---------------------------------------------------------------------------
local Granted = Enum.ProductPurchaseDecision.PurchaseGranted
local NotYet = Enum.ProductPurchaseDecision.NotProcessedYet

local function claimGifts(player: Player) end -- forward declaration (defined below)

local function consumeGiftIntent(player: Player, itemKey: string)
	local session = sessions[player]
	local intent = session and session.PendingGift
	if intent and intent.ItemKey == itemKey and os.time() - intent.At <= GIFT_INTENT_TTL then
		return intent
	end
	return nil
end

-- Shared by ProcessReceipt and the Studio purchase simulation.
local function handlePurchase(player: Player, profile, itemKey: string, isGift: boolean, purchaseId: string, robuxSpent: number?): Enum.ProductPurchaseDecision
	-- Already handled? Make sure it is saved, then confirm.
	if profile:HasReceipt(purchaseId) then
		if not profile:IsReceiptSaved(purchaseId) and not profile:Save() then
			return NotYet
		end
		local post = pendingPostGrant[purchaseId]
		if post then
			pendingPostGrant[purchaseId] = nil
			postGrant(post.Player, post.Info, post.Context)
		end
		return Granted
	end

	local def = ShopConfig.getItem(itemKey)
	if not def then
		warn("[ShopService] unknown item", itemKey)
		return NotYet
	end

	if isGift then
		local intent = consumeGiftIntent(player, itemKey)
		if intent then
			local sent = GiftInbox.send(intent.RecipientId, purchaseId, {
				Item = itemKey,
				FromId = player.UserId,
				FromName = player.DisplayName,
				At = os.time(),
			})
			if not sent then
				return NotYet
			end
			local session = sessions[player]
			if session then
				session.PendingGift = nil
			end
			profile.Data.Stats.RobuxSpent += robuxSpent or 0
			profile:AddReceipt(purchaseId)
			profile:Save() -- best effort; the inbox entry is idempotent anyway
			notify(player, `🎁 Gift sent to {intent.RecipientName}!`, "Success", def.Icon or "Gift")
			local recipient = Players:GetPlayerByUserId(intent.RecipientId)
			if recipient then
				task.spawn(claimGifts, recipient)
			end
			return Granted
		end
		-- The recipient is unknown (e.g. the server restarted between prompt and
		-- receipt). Never lose a purchase: give the item to the buyer instead.
		notify(player, "We couldn't find your gift recipient, so the item was added to your account instead.", "Info")
	end

	local info = applyItem(player, profile, itemKey, robuxSpent)
	if not info then
		return NotYet
	end
	profile:AddReceipt(purchaseId)
	if not profile:Save() then
		-- The grant stays in memory; Roblox retries the receipt and we confirm
		-- once the save goes through.
		pendingPostGrant[purchaseId] = { Player = player, Info = info, Context = { Source = "Purchase" } }
		refreshLeaderstats(player)
		return NotYet
	end
	postGrant(player, info, { Source = "Purchase" })
	return Granted
end

MarketplaceService.ProcessReceipt = function(receipt)
	local player = Players:GetPlayerByUserId(receipt.PlayerId)
	if not player then
		return NotYet -- Roblox retries when the player joins again
	end
	local route = productRoutes[receipt.ProductId]
	if not route then
		-- Not one of ours. If your game has other dev products, route them here.
		warn("[ShopService] ProcessReceipt for unknown product", receipt.ProductId)
		return NotYet
	end
	local profile = PlayerData.get(player) or PlayerData.waitFor(player, 20)
	if not profile then
		return NotYet
	end
	local ok, result = pcall(handlePurchase, player, profile, route.Key, route.Gift, tostring(receipt.PurchaseId), receipt.CurrencySpent)
	if not ok then
		warn("[ShopService] ProcessReceipt error:", result)
		return NotYet
	end
	return result
end

-- Studio-only: instantly grant items whose ID is still 0.
local function simulatePurchase(player: Player, itemKey: string, isGift: boolean)
	local profile = PlayerData.get(player)
	if not profile then
		return
	end
	local def = ShopConfig.getItem(itemKey)
	if def.Kind == "GamePass" and not isGift then
		local session = sessions[player]
		if session then
			session.OwnsPass[itemKey] = true
		end
		applySpeed(player)
		sendState(player)
		notify(player, `[Studio] Simulated: {def.DisplayName} unlocked!`, "Success", def.Icon)
		return
	end
	notify(player, `[Studio] Simulating purchase of {def.DisplayName}`, "Info")
	handlePurchase(player, profile, itemKey, isGift, "studio-" .. HttpService:GenerateGUID(false), def.Price)
end

local function isValidUserId(value: any): boolean
	return typeof(value) == "number" and value == value and value > 0 and value % 1 == 0 and value < 2 ^ 53
end

Remotes.PurchaseRequest.OnServerEvent:Connect(function(player: Player, itemKey: any, giftRecipientId: any)
	if not limits.PurchaseRequest:check(player) then
		return
	end
	if typeof(itemKey) ~= "string" or #itemKey > 32 then
		return
	end
	local def = ShopConfig.getItem(itemKey)
	if not def then
		return
	end
	if giftRecipientId ~= nil and not isValidUserId(giftRecipientId) then
		return
	end
	local session = sessions[player]
	if not session then
		return
	end
	if not PlayerData.get(player) then
		notify(player, "Your data is still loading, try again in a second!", "Error")
		return
	end

	if giftRecipientId ~= nil then
		-- Gift flow
		local recipient = Players:GetPlayerByUserId(giftRecipientId)
		if not recipient or recipient == player then
			notify(player, "That player isn't in this server anymore.", "Error")
			return
		end
		if ShopConfig.RequireFriendshipForGifts then
			local ok, isFriend = pcall(player.IsFriendsWith, player, giftRecipientId)
			if not ok or not isFriend then
				notify(player, "You can only send gifts to your friends.", "Error")
				return
			end
		end
		if def.Kind == "GamePass" and ownsPass(recipient, itemKey) then
			notify(player, `{recipient.DisplayName} already owns {def.DisplayName}!`, "Error")
			return
		end
		session.PendingGift = {
			ItemKey = itemKey,
			RecipientId = recipient.UserId,
			RecipientName = recipient.DisplayName,
			At = os.time(),
		}
		if def.GiftId == 0 then
			if IS_STUDIO and ShopConfig.StudioSimulatePurchases then
				simulatePurchase(player, itemKey, true)
			else
				notify(player, "Gifting this item isn't available yet.", "Error")
			end
			return
		end
		MarketplaceService:PromptProductPurchase(player, def.GiftId)
		return
	end

	-- Self purchase
	if def.Kind == "GamePass" then
		if ownsPass(player, itemKey) then
			notify(player, `You already own {def.DisplayName}!`, "Info")
			sendState(player)
			return
		end
		if def.Id == 0 then
			if IS_STUDIO and ShopConfig.StudioSimulatePurchases then
				simulatePurchase(player, itemKey, false)
			else
				notify(player, "This item isn't available yet.", "Error")
			end
			return
		end
		MarketplaceService:PromptGamePassPurchase(player, def.Id)
	else
		if def.Id == 0 then
			if IS_STUDIO and ShopConfig.StudioSimulatePurchases then
				simulatePurchase(player, itemKey, false)
			else
				notify(player, "This item isn't available yet.", "Error")
			end
			return
		end
		MarketplaceService:PromptProductPurchase(player, def.Id)
	end
end)

MarketplaceService.PromptGamePassPurchaseFinished:Connect(function(player: Player, passId: number, purchased: boolean)
	local passKey = passIdToKey[passId]
	if not passKey or not purchased then
		return
	end
	local session = sessions[player]
	if session then
		session.OwnsPass[passKey] = true
	end
	applySpeed(player)
	sendState(player)
	local def = ShopConfig.GamePasses[passKey]
	notify(player, `{def.DisplayName} unlocked! Thank you! ❤`, "Success", def.Icon)
end)

---------------------------------------------------------------------------
-- Gift claiming (recipient side)
---------------------------------------------------------------------------
claimGifts = function(player: Player)
	local session = sessions[player]
	if not session or session.ClaimingGifts then
		return
	end
	local profile = PlayerData.get(player)
	if not profile then
		return
	end
	session.ClaimingGifts = true

	local gifts = GiftInbox.fetch(player.UserId)
	if gifts and next(gifts) then
		local handledIds = {}
		local granted = {}
		for giftId, gift in gifts do
			local receiptId = "gift:" .. giftId
			if not profile:HasReceipt(receiptId) then
				local info = if type(gift) == "table" then applyItem(player, profile, tostring(gift.Item)) else nil
				profile:AddReceipt(receiptId)
				if info then
					table.insert(granted, { Info = info, FromName = if type(gift) == "table" then gift.FromName else nil })
				end
			end
			table.insert(handledIds, giftId)
		end
		-- Remove from the inbox only once the grants are safely saved.
		if profile:Save() then
			GiftInbox.remove(player.UserId, handledIds)
		end
		for _, entry in granted do
			postGrant(player, entry.Info, { Source = "Gift", FromName = tostring(entry.FromName or "A friend") })
		end
	end

	if sessions[player] then
		sessions[player].ClaimingGifts = false
	end
end

task.spawn(function()
	while true do
		task.wait(GIFT_POLL_INTERVAL)
		for _, player in Players:GetPlayers() do
			task.spawn(claimGifts, player)
		end
	end
end)

---------------------------------------------------------------------------
-- Client handshake + reveal confirmation
---------------------------------------------------------------------------
Remotes.ClientReady.OnServerEvent:Connect(function(player: Player)
	if not limits.ClientReady:check(player) then
		return
	end
	local session = sessions[player]
	if not session then
		return
	end
	session.Ready = true
	sendState(player)
	local queued = session.Queue
	session.Queue = {}
	for _, payload in queued do
		Remotes.SackResults:FireClient(player, payload)
	end
end)

Remotes.SackRevealDone.OnServerEvent:Connect(function(player: Player, batchId: any)
	if not limits.SackRevealDone:check(player) then
		return
	end
	if typeof(batchId) ~= "number" or batchId % 1 ~= 0 then
		return
	end
	releaseBatch(player, batchId)
end)

---------------------------------------------------------------------------
-- Player lifecycle
---------------------------------------------------------------------------
local function checkGamePasses(player: Player)
	local session = sessions[player]
	for passKey, def in ShopConfig.GamePasses do
		if def.Id ~= 0 then
			for attempt = 1, 3 do
				local ok, owns = pcall(MarketplaceService.UserOwnsGamePassAsync, MarketplaceService, player.UserId, def.Id)
				if ok then
					if owns and session then
						session.OwnsPass[passKey] = true
					end
					break
				end
				task.wait(attempt)
			end
		end
	end
	if player.Parent then
		applySpeed(player)
		sendState(player)
	end
end

local function setupLeaderstats(player: Player)
	local leaderstats = player:FindFirstChild("leaderstats")
	if not leaderstats then
		leaderstats = Instance.new("Folder")
		leaderstats.Name = "leaderstats"
		leaderstats.Parent = player
	end
	if not getCashValue(player) then
		local cash = Instance.new("IntValue")
		cash.Name = ShopConfig.CurrencyName
		cash.Value = 0
		cash.Parent = leaderstats
	end
end

local function onPlayerAdded(player: Player)
	sessions[player] = {
		Ready = false,
		OwnsPass = {},
		HeldCash = 0,
		Batches = {},
		BatchCounter = 0,
		Queue = {},
		PendingGift = nil,
		ClaimingGifts = false,
	}
	setupLeaderstats(player)

	player.CharacterAdded:Connect(function(character)
		local humanoid = character:WaitForChild("Humanoid", 10)
		if humanoid then
			applySpeed(player)
		end
	end)

	task.spawn(checkGamePasses, player)
	local profile = PlayerData.load(player)
	if not profile or not player.Parent then
		return
	end
	refreshLeaderstats(player)
	applySpeed(player)
	sendState(player)
	claimGifts(player)
end

Players.PlayerAdded:Connect(onPlayerAdded)
for _, player in Players:GetPlayers() do
	task.spawn(onPlayerAdded, player)
end

Players.PlayerRemoving:Connect(function(player)
	local session = sessions[player]
	if session then
		for _, batch in session.Batches do
			if batch.Timeout then
				pcall(task.cancel, batch.Timeout)
			end
		end
	end
	PlayerData.release(player)
	sessions[player] = nil
	for id, post in pendingPostGrant do
		if post.Player == player then
			pendingPostGrant[id] = nil
		end
	end
end)
