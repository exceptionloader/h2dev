-- Speichern & Laden der Spielstände (DataStore mit Retries, Autosave, BindToClose)
local DataStoreService = game:GetService("DataStoreService")
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Util = require(Shared:WaitForChild("Util"))

local DataService = {
	Profiles = {}, -- [player] = { Data = {...}, CanSave = bool }
	Loaded = Instance.new("BindableEvent"),
}

local TEMPLATE = {
	Cash = 0,
	Rebirths = 0,
	TotalEarned = 0,
	Upgrades = { Speed = 0, Luck = 0, Slots = 0 },
	Cores = {}, -- ["slot"] = { T = coreId, M = mutation }
	Discovered = {}, -- [coreId] = true
	LastDaily = 0,
	Streak = 0,
	Codes = {},
	Receipts = {},
	LastOnline = 0,
	Pending = 0,
	PlayTime = 0,
}

local store
local okStore = pcall(function()
	store = DataStoreService:GetDataStore(Config.DataStoreName)
end)

local function reconcile(data, template)
	for k, v in pairs(template) do
		if data[k] == nil then
			data[k] = Util.DeepCopy(v)
		elseif type(v) == "table" and type(data[k]) == "table" and next(v) ~= nil then
			reconcile(data[k], v)
		end
	end
end

local function retry(fn, attempts)
	local lastErr
	for i = 1, attempts do
		local ok, result = pcall(fn)
		if ok then
			return true, result
		end
		lastErr = result
		task.wait(1.5 * i)
	end
	return false, lastErr
end

function DataService:Init(services)
	self.S = services
end

function DataService:Get(player)
	local profile = self.Profiles[player]
	return profile and profile.Data
end

function DataService:WaitFor(player, timeout)
	local start = os.clock()
	while player.Parent == Players and not self.Profiles[player] do
		if timeout and os.clock() - start > timeout then
			return nil
		end
		task.wait(0.1)
	end
	return self:Get(player)
end

function DataService:Load(player)
	local key = "p_" .. player.UserId
	local data, canSave = nil, false
	if okStore and store then
		local ok, result = retry(function()
			return store:GetAsync(key)
		end, 3)
		if ok then
			data = result
			canSave = true
		else
			warn("[DataService] Laden fehlgeschlagen für " .. player.Name .. ": " .. tostring(result))
		end
	end
	if type(data) ~= "table" then
		data = Util.DeepCopy(TEMPLATE)
	end
	reconcile(data, TEMPLATE)
	if player.Parent ~= Players then
		return
	end
	self.Profiles[player] = { Data = data, CanSave = canSave }
	if not canSave then
		self.S.Remotes:Notify(player, "⚠️ Data could not be loaded – progress will NOT be saved this session.", "error")
	end
	self.Loaded:Fire(player)
end

function DataService:Save(player)
	local profile = self.Profiles[player]
	if not profile or not profile.CanSave or not store then
		return false
	end
	local data = profile.Data
	data.LastOnline = os.time()
	local ok, err = retry(function()
		store:UpdateAsync("p_" .. player.UserId, function()
			return data
		end)
	end, 3)
	if not ok then
		warn("[DataService] Speichern fehlgeschlagen für " .. player.Name .. ": " .. tostring(err))
	end
	if self.S.LeaderboardService then
		self.S.LeaderboardService:Submit(player.UserId, data.TotalEarned)
	end
	return ok
end

function DataService:Start()
	Players.PlayerAdded:Connect(function(player)
		self:Load(player)
	end)
	for _, player in ipairs(Players:GetPlayers()) do
		task.spawn(function()
			self:Load(player)
		end)
	end

	Players.PlayerRemoving:Connect(function(player)
		-- andere Services räumen zuerst auf (Cores die getragen werden etc.)
		task.wait()
		self:Save(player)
		self.Profiles[player] = nil
	end)

	game:BindToClose(function()
		local threads = 0
		for player in pairs(self.Profiles) do
			threads = threads + 1
			task.spawn(function()
				self:Save(player)
				threads = threads - 1
			end)
		end
		local start = os.clock()
		while threads > 0 and os.clock() - start < 25 do
			task.wait(0.2)
		end
	end)

	-- Autosave
	while true do
		task.wait(90)
		for player in pairs(self.Profiles) do
			task.spawn(function()
				self:Save(player)
			end)
			task.wait(0.5)
		end
	end
end

return DataService
