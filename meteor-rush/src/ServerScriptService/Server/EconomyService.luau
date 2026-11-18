-- Geld, Einkommen, Upgrades, Rebirth, Daily Reward, Codes, Leaderstats & Client-Sync
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Util = require(Shared:WaitForChild("Util"))

local EconomyService = { Dirty = {}, LastRequest = {} }

function EconomyService:Init(services)
	self.S = services
end

---------------------------------------------------------------------------
-- Multiplikatoren
---------------------------------------------------------------------------
function EconomyService:CashMultiplier(player)
	local data = self.S.DataService:Get(player)
	local mult = Config.RebirthMultiplier(data and data.Rebirths or 0)
	if self.S.MonetizationService:HasPass(player, "VIP") then
		mult = mult * 2
	end
	return mult
end

function EconomyService:Luck(player)
	local data = self.S.DataService:Get(player)
	local luck = 1 + (data and data.Upgrades.Luck or 0) * Config.Upgrades.Luck.PerLevel
	if self.S.MonetizationService:HasPass(player, "Lucky") then
		luck = luck * 2
	end
	return luck * self.S.EventService:Mult("LuckMult")
end

function EconomyService:Income(player)
	local data = self.S.DataService:Get(player)
	if not data then
		return 0
	end
	local total = 0
	for _, core in pairs(data.Cores) do
		total = total + Config.CoreIncome(core.T, core.M)
	end
	return total * self:CashMultiplier(player)
end

function EconomyService:ApplySpeed(player)
	local char = player.Character
	local hum = char and char:FindFirstChildOfClass("Humanoid")
	local data = self.S.DataService:Get(player)
	if not hum or not data then
		return
	end
	local speed = 16 + data.Upgrades.Speed * Config.Upgrades.Speed.PerLevel
	if self.S.MonetizationService:HasPass(player, "Speed") then
		speed = speed + 8
	end
	local carry = self.S.CoreService and self.S.CoreService.Carrying[player]
	if carry and carry.Stolen then
		speed = speed * Config.StolenSpeedPenalty
	end
	hum.WalkSpeed = speed
end

---------------------------------------------------------------------------
-- Cash
---------------------------------------------------------------------------
function EconomyService:AddCash(player, amount)
	local data = self.S.DataService:Get(player)
	if not data then
		return
	end
	data.Cash = math.max(0, data.Cash + amount)
	if amount > 0 then
		data.TotalEarned = data.TotalEarned + amount
	end
	self:UpdateLeaderstats(player)
	self:MarkDirty(player)
end

function EconomyService:Spend(player, amount)
	local data = self.S.DataService:Get(player)
	if not data or data.Cash < amount then
		return false
	end
	data.Cash = data.Cash - amount
	self:UpdateLeaderstats(player)
	self:MarkDirty(player)
	return true
end

function EconomyService:Collect(player)
	local plot = self.S.PlotService:GetPlot(player)
	if not plot or plot.Pending < 1 then
		return
	end
	local amount = math.floor(plot.Pending)
	self.S.PlotService:SetPending(plot, 0)
	self:AddCash(player, amount)
	self.S.Remotes:Fire("Collected", player, amount)
end

function EconomyService:UpdateLeaderstats(player)
	local data = self.S.DataService:Get(player)
	local ls = player:FindFirstChild("leaderstats")
	if not data or not ls then
		return
	end
	ls.Cash.Value = Util.Money(data.Cash)
	ls.Rebirths.Value = data.Rebirths
end

---------------------------------------------------------------------------
-- Sync zum Client
---------------------------------------------------------------------------
function EconomyService:MarkDirty(player)
	self.Dirty[player] = true
end

function EconomyService:Snapshot(player)
	local data = self.S.DataService:Get(player)
	if not data then
		return nil
	end
	local Mon = self.S.MonetizationService
	local passes = {}
	for id in pairs(Config.GamePasses) do
		passes[id] = Mon:HasPass(player, id)
	end
	local dailyLeft = math.max(0, (data.LastDaily + Config.DailyCooldown) - os.time())
	local coreCount = 0
	for _ in pairs(data.Cores) do
		coreCount = coreCount + 1
	end
	return {
		Cash = data.Cash,
		Income = self:Income(player),
		Rebirths = data.Rebirths,
		Upgrades = data.Upgrades,
		Discovered = data.Discovered,
		Passes = passes,
		DailyLeft = dailyLeft,
		Streak = data.Streak,
		Slots = self.S.PlotService:UnlockedSlots(player),
		CoreCount = coreCount,
		Luck = self:Luck(player),
		TotalEarned = data.TotalEarned,
		IsStudio = game:GetService("RunService"):IsStudio(),
	}
end

---------------------------------------------------------------------------
-- Requests vom Client
---------------------------------------------------------------------------
function EconomyService:BuyUpgrade(player, id)
	local data = self.S.DataService:Get(player)
	local def = Config.Upgrades[id]
	if not data or not def then
		return false, "Invalid upgrade"
	end
	local level = data.Upgrades[id] or 0
	if level >= def.Max then
		return false, "Already maxed!"
	end
	local cost = Config.UpgradeCost(id, level)
	if not self:Spend(player, cost) then
		return false, "Not enough cash! Need " .. Util.Money(cost)
	end
	data.Upgrades[id] = level + 1
	if id == "Speed" then
		self:ApplySpeed(player)
	elseif id == "Slots" then
		local plot = self.S.PlotService:GetPlot(player)
		if plot then
			self.S.PlotService:RefreshSlots(plot)
		end
	end
	self:MarkDirty(player)
	return true, def.Name .. " upgraded to level " .. (level + 1) .. "!"
end

function EconomyService:Rebirth(player)
	local data = self.S.DataService:Get(player)
	if not data then
		return false, "No data"
	end
	local cost = Config.RebirthCost(data.Rebirths)
	if data.Cash < cost then
		return false, "You need " .. Util.Money(cost) .. " to rebirth!"
	end
	data.Cash = 0
	data.Rebirths = data.Rebirths + 1
	data.Upgrades.Speed = 0
	data.Upgrades.Luck = 0
	self:ApplySpeed(player)
	self:UpdateLeaderstats(player)
	self:MarkDirty(player)
	self.S.Remotes:Announce("🌌 " .. player.DisplayName .. " reached Rebirth " .. data.Rebirths .. "!", Color3.fromRGB(180, 120, 255))
	return true, "Rebirth complete! Cash multiplier is now x" .. Config.RebirthMultiplier(data.Rebirths)
end

function EconomyService:ClaimDaily(player)
	local data = self.S.DataService:Get(player)
	if not data then
		return false, "No data"
	end
	local now = os.time()
	if now < data.LastDaily + Config.DailyCooldown then
		return false, "Come back in " .. Util.FormatTime(data.LastDaily + Config.DailyCooldown - now)
	end
	if now > data.LastDaily + Config.DailyCooldown * 2.4 then
		data.Streak = 0
	end
	data.Streak = math.min(Config.DailyMaxStreak, data.Streak + 1)
	data.LastDaily = now
	local reward = math.floor(Config.DailyBase * data.Streak * (1 + data.Rebirths) + self:Income(player) * 120)
	self:AddCash(player, reward)
	return true, "Day " .. data.Streak .. " reward: " .. Util.Money(reward) .. "!"
end

function EconomyService:RedeemCode(player, code)
	local data = self.S.DataService:Get(player)
	if not data or type(code) ~= "string" then
		return false, "Invalid code"
	end
	code = string.upper(string.gsub(code, "%s", ""))
	local def = Config.Codes[code]
	if not def then
		return false, "That code doesn't exist."
	end
	if data.Codes[code] then
		return false, "You already redeemed this code."
	end
	data.Codes[code] = true
	if def.Cash then
		self:AddCash(player, def.Cash)
	end
	return true, "Code redeemed! +" .. Util.Money(def.Cash or 0)
end

function EconomyService:HandleRequest(player, action, arg)
	local now = os.clock()
	if self.LastRequest[player] and now - self.LastRequest[player] < 0.15 then
		return false, "Slow down!"
	end
	self.LastRequest[player] = now
	if action == "Upgrade" then
		return self:BuyUpgrade(player, arg)
	elseif action == "Rebirth" then
		return self:Rebirth(player)
	elseif action == "Daily" then
		return self:ClaimDaily(player)
	elseif action == "Code" then
		return self:RedeemCode(player, arg)
	elseif action == "Home" then
		self.S.PlotService:TeleportHome(player)
		return true, nil
	elseif action == "Snapshot" then
		return true, self:Snapshot(player)
	elseif action == "StudioTest" then
		return self.S.MonetizationService:StudioTest(player, arg)
	end
	return false, "Unknown request"
end

---------------------------------------------------------------------------
-- Lifecycle
---------------------------------------------------------------------------
function EconomyService:Start()
	local DataService = self.S.DataService

	self.S.Remotes.R.Request.OnServerInvoke = function(player, action, arg)
		local ok, a, b = pcall(function()
			return self:HandleRequest(player, action, arg)
		end)
		if not ok then
			warn("[EconomyService] Request-Fehler: " .. tostring(a))
			return false, "Something went wrong"
		end
		return a, b
	end

	DataService.Loaded.Event:Connect(function(player)
		-- Achtung: BindableEvents kopieren Tabellen -> immer das echte Profil holen
		local data = DataService:Get(player)
		if not data then
			return
		end
		local ls = Instance.new("Folder")
		ls.Name = "leaderstats"
		local cash = Instance.new("StringValue")
		cash.Name = "Cash"
		cash.Parent = ls
		local reb = Instance.new("IntValue")
		reb.Name = "Rebirths"
		reb.Parent = ls
		ls.Parent = player
		self:UpdateLeaderstats(player)

		-- Offline-Einnahmen
		if data.LastOnline and data.LastOnline > 0 then
			local away = math.clamp(os.time() - data.LastOnline, 0, Config.OfflineMaxSeconds)
			local earned = math.floor(self:Income(player) * away * Config.OfflineEarningsRate)
			if earned > 0 and away > 60 then
				self:AddCash(player, earned)
				task.delay(4, function()
					self.S.Remotes:Notify(player, "🌙 While you were away your cores earned " .. Util.Money(earned) .. "!", "success")
				end)
			end
		end

		-- Entdeckte Cores aus dem Inventar nachtragen
		for _, core in pairs(data.Cores) do
			data.Discovered[core.T] = true
		end
		self:ApplySpeed(player)
		self:MarkDirty(player)
	end)

	local function onPlayer(player)
		player.CharacterAdded:Connect(function(char)
			char:WaitForChild("Humanoid", 10)
			self:ApplySpeed(player)
		end)
	end
	Players.PlayerAdded:Connect(onPlayer)
	for _, p in ipairs(Players:GetPlayers()) do
		onPlayer(p)
	end
	Players.PlayerRemoving:Connect(function(player)
		self.Dirty[player] = nil
		self.LastRequest[player] = nil
	end)

	-- Sync-Loop
	task.spawn(function()
		while true do
			task.wait(0.25)
			for player in pairs(self.Dirty) do
				self.Dirty[player] = nil
				local snap = self:Snapshot(player)
				if snap then
					self.S.Remotes:Fire("Data", player, snap)
				end
			end
		end
	end)

	-- Einkommen jede Sekunde
	local tick = 0
	while true do
		task.wait(1)
		tick = tick + 1
		for _, player in ipairs(Players:GetPlayers()) do
			local data = DataService:Get(player)
			local plot = self.S.PlotService:GetPlot(player)
			if data and plot then
				data.PlayTime = data.PlayTime + 1
				local income = self:Income(player)
				if income > 0 then
					if self.S.MonetizationService:HasPass(player, "AutoCollect") then
						self:AddCash(player, income)
					else
						self.S.PlotService:SetPending(plot, plot.Pending + income)
					end
				end
				self.S.PlotService:UpdateSign(plot, income)
				if tick % 5 == 0 then
					self:MarkDirty(player)
				end
			end
		end
	end
end

return EconomyService
