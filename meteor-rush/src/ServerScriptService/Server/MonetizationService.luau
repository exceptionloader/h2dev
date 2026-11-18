-- Gamepasses & Developer Products (inkl. sicherem ProcessReceipt mit Duplikat-Schutz)
local Players = game:GetService("Players")
local MarketplaceService = game:GetService("MarketplaceService")
local RunService = game:GetService("RunService")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Util = require(Shared:WaitForChild("Util"))

local MonetizationService = { Owned = {}, StudioGranted = {} }

function MonetizationService:Init(services)
	self.S = services
end

function MonetizationService:HasPass(player, passKey)
	local owned = self.Owned[player]
	if owned and owned[passKey] then
		return true
	end
	local studio = self.StudioGranted[player]
	return studio ~= nil and studio[passKey] == true
end

function MonetizationService:RefreshPasses(player)
	local owned = {}
	for key, def in pairs(Config.GamePasses) do
		if def.Id and def.Id ~= 0 then
			local ok, has = pcall(function()
				return MarketplaceService:UserOwnsGamePassAsync(player.UserId, def.Id)
			end)
			owned[key] = ok and has or false
		end
	end
	self.Owned[player] = owned
end

function MonetizationService:OnPassGranted(player, key)
	local def = Config.GamePasses[key]
	self.Owned[player] = self.Owned[player] or {}
	self.Owned[player][key] = true
	self.S.Remotes:Notify(player, "🎉 Thanks for buying " .. def.Name .. "!", "rare")
	self.S.Remotes:Announce(def.Icon .. " " .. player.DisplayName .. " just got " .. def.Name .. "!", Color3.fromRGB(255, 210, 80))
	self.S.EconomyService:ApplySpeed(player)
	self.S.EconomyService:MarkDirty(player)
	self:ApplyVipTag(player)
end

function MonetizationService:ApplyVipTag(player)
	if self:HasPass(player, "VIP") then
		player:SetAttribute("VIP", true)
	end
end

function MonetizationService:GrantProduct(player, key)
	local def = Config.Products[key]
	if key == "Shower" then
		self.S.EventService:Begin("Shower", player.DisplayName)
	elseif key == "LuckyMeteor" then
		if not self.S.MeteorService:SpawnLucky(player) then
			return false
		end
	elseif key == "CashSmall" or key == "CashBig" then
		local amount = math.max(def.Min, math.floor(self.S.EconomyService:Income(player) * 60 * def.Minutes))
		self.S.EconomyService:AddCash(player, amount)
		self.S.Remotes:Fire("Collected", player, amount)
		self.S.Remotes:Notify(player, def.Icon .. " +" .. Util.Money(amount) .. " – thank you!", "rare")
	else
		return false
	end
	return true
end

-- Nur in Roblox Studio: Produkte/Pässe gratis testen (funktioniert NICHT im Live-Spiel)
function MonetizationService:StudioTest(player, key)
	if not RunService:IsStudio() then
		return false, "Studio only"
	end
	if Config.GamePasses[key] then
		self.StudioGranted[player] = self.StudioGranted[player] or {}
		self.StudioGranted[player][key] = true
		self:OnPassGranted(player, key)
		return true, "[Studio] Pass granted: " .. key
	elseif Config.Products[key] then
		local ok = self:GrantProduct(player, key)
		return ok, ok and ("[Studio] Product granted: " .. key) or "Could not grant"
	end
	return false, "Unknown"
end

function MonetizationService:Start()
	local productByid = {}
	for key, def in pairs(Config.Products) do
		if def.Id and def.Id ~= 0 then
			productByid[def.Id] = key
		end
	end
	local passById = {}
	for key, def in pairs(Config.GamePasses) do
		if def.Id and def.Id ~= 0 then
			passById[def.Id] = key
		end
	end

	Players.PlayerAdded:Connect(function(player)
		self:RefreshPasses(player)
		self:ApplyVipTag(player)
		self.S.EconomyService:ApplySpeed(player)
		self.S.EconomyService:MarkDirty(player)
	end)
	for _, p in ipairs(Players:GetPlayers()) do
		task.spawn(function()
			self:RefreshPasses(p)
		end)
	end
	Players.PlayerRemoving:Connect(function(player)
		self.Owned[player] = nil
		self.StudioGranted[player] = nil
	end)

	MarketplaceService.PromptGamePassPurchaseFinished:Connect(function(player, passId, purchased)
		if purchased and passById[passId] then
			self:OnPassGranted(player, passById[passId])
		end
	end)

	MarketplaceService.ProcessReceipt = function(receipt)
		local player = Players:GetPlayerByUserId(receipt.PlayerId)
		if not player then
			return Enum.ProductPurchaseDecision.NotProcessedYet
		end
		local data = self.S.DataService:Get(player)
		if not data then
			return Enum.ProductPurchaseDecision.NotProcessedYet
		end
		for _, id in ipairs(data.Receipts) do
			if id == receipt.PurchaseId then
				return Enum.ProductPurchaseDecision.PurchaseGranted
			end
		end
		local key = productByid[receipt.ProductId]
		if not key then
			warn("[Monetization] Unbekanntes Produkt " .. tostring(receipt.ProductId))
			return Enum.ProductPurchaseDecision.NotProcessedYet
		end
		local ok, granted = pcall(function()
			return self:GrantProduct(player, key)
		end)
		if not ok or not granted then
			return Enum.ProductPurchaseDecision.NotProcessedYet
		end
		table.insert(data.Receipts, receipt.PurchaseId)
		while #data.Receipts > 40 do
			table.remove(data.Receipts, 1)
		end
		self.S.DataService:Save(player)
		return Enum.ProductPurchaseDecision.PurchaseGranted
	end
end

return MonetizationService
