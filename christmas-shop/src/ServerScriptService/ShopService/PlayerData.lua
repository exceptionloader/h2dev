--[[
	PlayerData  (ModuleScript)
	Explorer:  ServerScriptService > ShopService > PlayerData

	Small ProfileService-style data layer on top of a plain DataStore:
	  * session locking (a profile is only writable by one server at a time)
	  * UpdateAsync everywhere + retries with backoff
	  * autosave every 60s, save + release on leave, BindToClose flush
	  * purchase receipt log, so ProcessReceipt is idempotent
	  * Studio without API access -> offline in-memory profiles (with a warning)

	If your game already has its own data system, keep this module's API
	(load/get/waitFor/release, profile:Save, profile:HasReceipt/AddReceipt) and
	point the internals at your own system.
]]

local DataStoreService = game:GetService("DataStoreService")
local HttpService = game:GetService("HttpService")
local RunService = game:GetService("RunService")

local ShopConfig = require(game:GetService("ReplicatedStorage"):WaitForChild("ShopConfig"))

local PlayerData = {}

local JOB_ID = if game.JobId ~= "" then game.JobId else "studio-" .. HttpService:GenerateGUID(false)
local LOCK_TIMEOUT = 30 * 60 -- a lock older than this is considered dead
local LOAD_ATTEMPTS = if RunService:IsStudio() then 2 else 6
local AUTOSAVE_INTERVAL = 60
local MAX_RECEIPTS = 200

local TEMPLATE = {
	Cash = ShopConfig.StartingCash,
	Receipts = {}, -- array of purchase ids (newest last)
	GiftedPasses = {}, -- [passKey] = true (perks received as gifts)
	Stats = {
		SacksOpened = 0,
		Found = {}, -- [lootKey] = count
		RobuxSpent = 0,
	},
}

local store: DataStore? = nil
local storeError: string? = nil
do
	local ok, result = pcall(function()
		return DataStoreService:GetDataStore(ShopConfig.DataStoreName)
	end)
	if ok then
		store = result
	else
		storeError = tostring(result)
	end
end

local profiles: { [Player]: any } = {}
local loading: { [Player]: boolean } = {}
local loadedEvent = Instance.new("BindableEvent")

---------------------------------------------------------------------------
-- Helpers
---------------------------------------------------------------------------
local function deepCopy(value)
	if type(value) ~= "table" then
		return value
	end
	local copy = {}
	for k, v in value do
		copy[k] = deepCopy(v)
	end
	return copy
end

-- Fills missing keys from the template (does not remove unknown keys).
local function reconcile(data, template)
	if type(data) ~= "table" then
		return deepCopy(template)
	end
	for k, v in template do
		if data[k] == nil then
			data[k] = deepCopy(v)
		elseif type(v) == "table" and type(data[k]) == "table" and next(v) ~= nil then
			reconcile(data[k], v)
		end
	end
	return data
end

local function isStudioApiError(err: any): boolean
	local msg = tostring(err)
	return RunService:IsStudio()
		and (msg:find("StudioAccessToApisNotAllowed") ~= nil or msg:find("API") ~= nil or msg:find("403") ~= nil or msg:find("not allowed") ~= nil)
end

local function keyFor(player: Player): string
	return "Player_" .. player.UserId
end

---------------------------------------------------------------------------
-- Profile object
---------------------------------------------------------------------------
local Profile = {}
Profile.__index = Profile

local function newProfile(player: Player, data, offline: boolean)
	local self = setmetatable({
		Player = player,
		Key = keyFor(player),
		Data = data,
		Offline = offline,
		Released = false,
		ReceiptSet = {},
		UnsavedReceipts = {},
		_saving = false,
	}, Profile)
	for _, id in data.Receipts do
		self.ReceiptSet[id] = true
	end
	return self
end

function Profile:HasReceipt(id: string): boolean
	return self.ReceiptSet[id] == true
end

function Profile:AddReceipt(id: string)
	if self.ReceiptSet[id] then
		return
	end
	self.ReceiptSet[id] = true
	self.UnsavedReceipts[id] = true
	table.insert(self.Data.Receipts, id)
	while #self.Data.Receipts > MAX_RECEIPTS do
		local oldest = table.remove(self.Data.Receipts, 1)
		self.ReceiptSet[oldest] = nil
	end
end

function Profile:IsReceiptSaved(id: string): boolean
	return self.ReceiptSet[id] == true and self.UnsavedReceipts[id] == nil
end

-- Saves the profile. `release` also removes the session lock.
-- Returns true when the data is confirmed written.
function Profile:Save(release: boolean?): boolean
	while self._saving do
		task.wait()
	end
	if self.Released then
		return false
	end
	if self.Offline or not store then
		self.UnsavedReceipts = {}
		if release then
			self.Released = true
		end
		return true
	end

	self._saving = true
	local pending = table.clone(self.UnsavedReceipts)
	local sessionStolen = false
	local success = false
	for attempt = 1, 4 do
		local ok, err = pcall(function()
			(store :: DataStore):UpdateAsync(self.Key, function(old)
				local record = if type(old) == "table" then old else {}
				local lock = record.Lock
				if type(lock) == "table" and lock.JobId ~= JOB_ID then
					sessionStolen = true
					return nil -- another server owns this profile now; do not overwrite
				end
				record.Data = self.Data
				record.Lock = if release then nil else { JobId = JOB_ID, Time = os.time() }
				return record
			end)
		end)
		if ok then
			success = not sessionStolen
			break
		end
		warn(`[PlayerData] save attempt {attempt} for {self.Key} failed: {err}`)
		task.wait(math.min(2 ^ attempt, 10))
	end
	self._saving = false

	if sessionStolen then
		self.Released = true
		profiles[self.Player] = nil
		if self.Player.Parent then
			self.Player:Kick("Your data was loaded on another server. Please rejoin.")
		end
		return false
	end
	if success then
		for id in pending do
			self.UnsavedReceipts[id] = nil
		end
		if release then
			self.Released = true
		end
	end
	return success
end

---------------------------------------------------------------------------
-- Loading
---------------------------------------------------------------------------
local function loadFromStore(player: Player)
	if not store then
		return nil, storeError or "no datastore"
	end
	local key = keyFor(player)
	local lastErr = nil
	for attempt = 1, LOAD_ATTEMPTS do
		local forceSteal = attempt == LOAD_ATTEMPTS
		local lockedByOther = false
		local loaded = nil
		local ok, err = pcall(function()
			(store :: DataStore):UpdateAsync(key, function(old)
				local record = if type(old) == "table" then old else {}
				local lock = record.Lock
				if
					not forceSteal
					and type(lock) == "table"
					and lock.JobId ~= JOB_ID
					and os.time() - (tonumber(lock.Time) or 0) < LOCK_TIMEOUT
				then
					lockedByOther = true
					return nil
				end
				record.Data = reconcile(record.Data, TEMPLATE)
				record.Lock = { JobId = JOB_ID, Time = os.time() }
				loaded = record.Data
				return record
			end)
		end)
		if ok and loaded and not lockedByOther then
			return loaded, nil
		end
		if not ok then
			lastErr = err
			if isStudioApiError(err) then
				return nil, err
			end
		else
			lastErr = "profile is locked by another server"
		end
		if not player.Parent then
			return nil, "player left"
		end
		task.wait(if lockedByOther then 4 else math.min(2 ^ attempt, 8))
	end
	return nil, lastErr
end

-- Loads (or returns the already loaded) profile. Yields.
function PlayerData.load(player: Player)
	if profiles[player] then
		return profiles[player]
	end
	if loading[player] then
		return PlayerData.waitFor(player, 60)
	end
	loading[player] = true

	local data, err = loadFromStore(player)
	local profile = nil
	if data then
		profile = newProfile(player, data, false)
	elseif RunService:IsStudio() then
		warn(
			"[PlayerData] Running with an OFFLINE profile (nothing is saved). "
				.. "Enable Game Settings > Security > 'Enable Studio Access to API Services' to test saving. Reason:",
			err
		)
		profile = newProfile(player, reconcile(nil, TEMPLATE), true)
	else
		loading[player] = nil
		warn(`[PlayerData] could not load {player.Name}: {err}`)
		if player.Parent then
			player:Kick("Could not load your data. Please rejoin in a moment.")
		end
		return nil
	end

	loading[player] = nil
	if not player.Parent then
		-- left while loading: release the lock we just took
		profile:Save(true)
		return nil
	end
	profiles[player] = profile
	loadedEvent:Fire(player, profile)
	return profile
end

function PlayerData.get(player: Player)
	return profiles[player]
end

-- Waits up to `timeout` seconds for the player's profile.
function PlayerData.waitFor(player: Player, timeout: number?)
	local deadline = os.clock() + (timeout or 15)
	while not profiles[player] and player.Parent and os.clock() < deadline do
		task.wait(0.1)
	end
	return profiles[player]
end

PlayerData.Loaded = loadedEvent.Event

function PlayerData.release(player: Player)
	local profile = profiles[player]
	if not profile then
		return
	end
	profile:Save(true)
	profiles[player] = nil
end

---------------------------------------------------------------------------
-- Autosave + shutdown
---------------------------------------------------------------------------
task.spawn(function()
	while true do
		task.wait(AUTOSAVE_INTERVAL)
		for _, profile in profiles do
			task.spawn(function()
				profile:Save(false)
			end)
		end
	end
end)

game:BindToClose(function()
	local pendingCount = 0
	for player in profiles do
		pendingCount += 1
		task.spawn(function()
			PlayerData.release(player)
			pendingCount -= 1
		end)
	end
	local deadline = os.clock() + 25
	while pendingCount > 0 and os.clock() < deadline do
		task.wait(0.1)
	end
end)


return PlayerData
