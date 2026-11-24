--!nocheck
--[[
	Offline test harness for the Christmas Shop SERVER logic.
	Runs with the standalone Luau CLI (https://github.com/luau-lang/luau):

	    LUAU=/path/to/luau ./tests/run.sh

	It mocks the Roblox services the server uses (DataStores with failure
	injection, MarketplaceService, Players, RemoteEvents, task scheduler with
	virtual time) and checks the purchase rules: idempotent receipts, retries
	after failed saves, sack rolls + delayed reveal, gifting, validation and
	rate limits, session locking.
]]

-- The Luau CLI has no file IO, so tests/run.sh bundles the sources into
-- tests/_sources.lua (a table of path -> source) before running this file.
local SOURCES = require("./_sources")
local ROOT = "."

---------------------------------------------------------------------------
-- Virtual-time scheduler (task.*)
---------------------------------------------------------------------------
local now = 0
local queue = {}
local cancelled = setmetatable({}, { __mode = "k" })
local errors = {}

local function resume(co, ...)
	if cancelled[co] or coroutine.status(co) == "dead" then
		return
	end
	local ok, err = coroutine.resume(co, ...)
	if not ok then
		table.insert(errors, tostring(err))
		print("SCRIPT ERROR: " .. tostring(err))
	end
end

local function schedule(co, at, ...)
	table.insert(queue, { co = co, at = at, args = table.pack(...) })
end

local task = {}
function task.spawn(f, ...)
	local co = if type(f) == "thread" then f else coroutine.create(f)
	resume(co, ...)
	return co
end
function task.defer(f, ...)
	local co = if type(f) == "thread" then f else coroutine.create(f)
	schedule(co, now, ...)
	return co
end
function task.delay(t, f, ...)
	local co = coroutine.create(f)
	schedule(co, now + (t or 0), ...)
	return co
end
function task.wait(t)
	local co = coroutine.running()
	local dt = math.max(t or 0, 1 / 60)
	schedule(co, now + dt, dt)
	return coroutine.yield()
end
function task.cancel(co)
	if co == coroutine.running() then
		error("cannot cancel running thread")
	end
	cancelled[co] = true
end

local function runUntil(target)
	while true do
		local nextIndex, nextAt = nil, math.huge
		for i, item in queue do
			if item.at < nextAt then
				nextIndex, nextAt = i, item.at
			end
		end
		if not nextIndex or nextAt > target then
			break
		end
		local item = table.remove(queue, nextIndex)
		now = math.max(now, item.at)
		resume(item.co, table.unpack(item.args, 1, item.args.n))
	end
	now = math.max(now, target)
end
local function advance(seconds)
	runUntil(now + seconds)
end

local fakeOs = setmetatable({
	time = function()
		return 1764000000 + math.floor(now)
	end,
	clock = function()
		return now
	end,
}, { __index = os })

---------------------------------------------------------------------------
-- Signals + instances
---------------------------------------------------------------------------
local function Signal()
	local handlers = {}
	local s = {}
	function s:Connect(fn)
		local entry = { fn = fn, connected = true }
		table.insert(handlers, entry)
		return {
			Disconnect = function()
				entry.connected = false
			end,
		}
	end
	function s:Fire(...)
		for _, h in handlers do
			if h.connected then
				task.spawn(h.fn, ...)
			end
		end
	end
	function s:Wait()
		local co = coroutine.running()
		local conn
		conn = s:Connect(function(...)
			conn.Disconnect()
			task.spawn(co, ...)
		end)
		return coroutine.yield()
	end
	return s
end

local function enumStub(path)
	return setmetatable({}, {
		__index = function(t, k)
			local v = enumStub(path .. "." .. k)
			rawset(t, k, v)
			return v
		end,
		__tostring = function()
			return path
		end,
	})
end
local Enum = enumStub("Enum")

local InstanceMT = {}
local function newInstance(className, name)
	local inst = {
		ClassName = className,
		Name = name or className,
		_children = {},
		_attributes = {},
		_parent = nil,
	}
	function inst:FindFirstChild(n)
		for _, c in self._children do
			if c.Name == n then
				return c
			end
		end
		return nil
	end
	function inst:WaitForChild(n)
		return self:FindFirstChild(n) or error("WaitForChild would hang: " .. n)
	end
	function inst:GetChildren()
		return table.clone(self._children)
	end
	function inst:IsA(c)
		return self.ClassName == c
	end
	function inst:Destroy()
		self.Parent = nil
	end
	function inst:SetAttribute(k, v)
		self._attributes[k] = v
	end
	function inst:GetAttribute(k)
		return self._attributes[k]
	end
	function inst:FindFirstChildOfClass(c)
		for _, ch in self._children do
			if ch.ClassName == c then
				return ch
			end
		end
		return nil
	end
	return setmetatable(inst, InstanceMT)
end
InstanceMT.__index = function(t, k)
	if k == "Parent" then
		return rawget(t, "_parent")
	end
	return nil
end
InstanceMT.__newindex = function(t, k, v)
	if k == "Parent" then
		local old = rawget(t, "_parent")
		if old then
			local idx = table.find(old._children, t)
			if idx then
				table.remove(old._children, idx)
			end
		end
		rawset(t, "_parent", v)
		if v then
			table.insert(v._children, t)
		end
	else
		rawset(t, k, v)
	end
end

local fired = {} -- log of remote traffic: { remote, kind, player, payload }
local Instance = {}
function Instance.new(className)
	local inst = newInstance(className)
	if className == "RemoteEvent" then
		inst.OnServerEvent = Signal()
		function inst:FireClient(player, payload)
			table.insert(fired, { remote = self.Name, player = player, payload = payload })
		end
		function inst:FireAllClients(payload)
			table.insert(fired, { remote = self.Name, player = "ALL", payload = payload })
		end
	elseif className == "BindableEvent" then
		local sig = Signal()
		inst.Event = sig
		function inst:Fire(...)
			sig:Fire(...)
		end
	elseif className == "IntValue" then
		inst.Value = 0
	end
	return inst
end

---------------------------------------------------------------------------
-- Services
---------------------------------------------------------------------------
local function deepCopy(v)
	if type(v) ~= "table" then
		return v
	end
	local c = {}
	for k, x in v do
		c[k] = deepCopy(x)
	end
	return c
end

local stores = {}
local DataStoreService = {}
function DataStoreService:GetDataStore(name)
	if stores[name] then
		return stores[name]
	end
	local store = { name = name, data = {}, failUpdates = 0, updateCalls = 0 }
	function store:UpdateAsync(key, fn)
		self.updateCalls += 1
		if self.failUpdates > 0 then
			self.failUpdates -= 1
			error("502: simulated DataStore outage")
		end
		local result = fn(deepCopy(self.data[key]))
		if result ~= nil then
			self.data[key] = deepCopy(result)
		end
		return result
	end
	function store:GetAsync(key)
		return deepCopy(self.data[key])
	end
	stores[name] = store
	return store
end

local Players = newInstance("Players", "Players")
Players.PlayerAdded = Signal()
Players.PlayerRemoving = Signal()
function Players:GetPlayers()
	local list = {}
	for _, c in self._children do
		if c.ClassName == "Player" then
			table.insert(list, c)
		end
	end
	return list
end
function Players:GetPlayerByUserId(id)
	for _, p in self:GetPlayers() do
		if p.UserId == id then
			return p
		end
	end
	return nil
end

local friendships = {}
local function makePlayer(userId, name)
	local p = newInstance("Player", name)
	p.UserId = userId
	p.DisplayName = name
	p.CharacterAdded = Signal()
	p.Character = nil
	p.kicked = nil
	function p:Kick(msg)
		self.kicked = msg
	end
	function p:IsFriendsWith(otherId)
		return friendships[userId .. ":" .. otherId] == true or friendships[otherId .. ":" .. userId] == true
	end
	return p
end

local prompts = {}
local MarketplaceService = {
	PromptGamePassPurchaseFinished = Signal(),
}
function MarketplaceService:PromptProductPurchase(player, id)
	table.insert(prompts, { player = player, id = id, kind = "product" })
end
function MarketplaceService:PromptGamePassPurchase(player, id)
	table.insert(prompts, { player = player, id = id, kind = "pass" })
end
function MarketplaceService:UserOwnsGamePassAsync(_userId, _passId)
	return false
end

local ReplicatedStorage = newInstance("ReplicatedStorage", "ReplicatedStorage")
local ServerScriptService = newInstance("ServerScriptService", "ServerScriptService")
local guidCounter = 0
local services = {
	DataStoreService = DataStoreService,
	Players = Players,
	MarketplaceService = MarketplaceService,
	ReplicatedStorage = ReplicatedStorage,
	ServerScriptService = ServerScriptService,
	RunService = {
		IsStudio = function()
			return false
		end,
	},
	HttpService = {
		GenerateGUID = function()
			guidCounter += 1
			return "guid-" .. guidCounter
		end,
	},
}
local bindToClose = {}
local game = {
	JobId = "job-A",
	GetService = function(_, name)
		return services[name] or error("no mock for service " .. name)
	end,
	BindToClose = function(_, fn)
		table.insert(bindToClose, fn)
	end,
}

---------------------------------------------------------------------------
-- Module loading
---------------------------------------------------------------------------
local function readFile(path)
	return SOURCES[path] or error("source not bundled: " .. path)
end

local warnings = {}
local sharedEnv = setmetatable({
	game = game,
	Enum = Enum,
	Instance = Instance,
	task = task,
	os = fakeOs,
	warn = function(...)
		local parts = {}
		for i = 1, select("#", ...) do
			parts[i] = tostring(select(i, ...))
		end
		table.insert(warnings, table.concat(parts, " "))
	end,
	Color3 = { fromRGB = function(r, g, b)
		return { r, g, b }
	end },
	Random = {
		new = function()
			return {
				NextNumber = function()
					return math.random()
				end,
			}
		end,
	},
}, { __index = _G })

local moduleCache = {}
local function makeModule(name, path, parent)
	local m = newInstance("ModuleScript", name)
	m._path = path
	m.Parent = parent
	return m
end

local function loadFile(path, scriptInstance)
	local source = readFile(path)
	local chunk, err = loadstring(source, "=" .. path)
	if not chunk then
		error(err)
	end
	local env = setmetatable({ script = scriptInstance }, { __index = sharedEnv })
	env.require = function(mod)
		if moduleCache[mod] == nil then
			moduleCache[mod] = loadFile(mod._path, mod)()
		end
		return moduleCache[mod]
	end
	setfenv(chunk, env)
	return chunk
end

-- Layout mirrors the Explorer
local configModule = makeModule("ShopConfig", ROOT .. "/src/ReplicatedStorage/ShopConfig.lua", ReplicatedStorage)
local serviceScript = newInstance("Script", "ShopService")
serviceScript.Parent = ServerScriptService
makeModule("PlayerData", ROOT .. "/src/ServerScriptService/ShopService/PlayerData.lua", serviceScript)
makeModule("GiftInbox", ROOT .. "/src/ServerScriptService/ShopService/GiftInbox.lua", serviceScript)
makeModule("RateLimiter", ROOT .. "/src/ServerScriptService/ShopService/RateLimiter.lua", serviceScript)

-- ShopConfig with real-looking IDs
moduleCache[configModule] = loadFile(configModule._path, configModule)()
local Config = moduleCache[configModule]
local nextId = 1000
for _, def in Config.Products do
	nextId += 1
	def.Id = nextId
	nextId += 1
	def.GiftId = nextId
end
for _, def in Config.GamePasses do
	nextId += 1
	def.Id = nextId
	nextId += 1
	def.GiftId = nextId
end

---------------------------------------------------------------------------
-- Boot the server script
---------------------------------------------------------------------------
task.spawn(loadFile(ROOT .. "/src/ServerScriptService/ShopService/init.server.lua", serviceScript))
advance(0.1)

local Remotes = ReplicatedStorage:FindFirstChild("ChristmasShopRemotes")
local function remote(name)
	return Remotes:FindFirstChild(name)
end
local playerStore = stores[Config.DataStoreName]

---------------------------------------------------------------------------
-- Test helpers
---------------------------------------------------------------------------
local fails, passes = 0, 0
local function check(name, cond, detail)
	if cond then
		passes += 1
		print("ok   " .. name)
	else
		fails += 1
		print("FAIL " .. name .. (if detail then "  (" .. tostring(detail) .. ")" else ""))
	end
end

local function join(userId, name)
	local p = makePlayer(userId, name)
	p.Parent = Players
	Players.PlayerAdded:Fire(p)
	advance(1)
	remote("ClientReady").OnServerEvent:Fire(p)
	advance(0.1)
	return p
end

local function leave(p)
	p.Parent = nil
	Players.PlayerRemoving:Fire(p)
	advance(1)
end

local function cash(p)
	return p:FindFirstChild("leaderstats"):FindFirstChild("Cash").Value
end

local function savedRecord(p)
	return playerStore.data["Player_" .. p.UserId]
end

local function receipt(p, productId, purchaseId, spent)
	local result
	task.spawn(function()
		result = services.MarketplaceService.ProcessReceipt({
			PlayerId = p.UserId,
			ProductId = productId,
			PurchaseId = purchaseId,
			CurrencySpent = spent or 0,
		})
	end)
	local waited = 0
	while result == nil and waited < 120 do
		advance(0.25)
		waited += 0.25
	end
	return result
end

local function lastFired(remoteName, player)
	for i = #fired, 1, -1 do
		local f = fired[i]
		if f.remote == remoteName and (player == nil or f.player == player) then
			return f.payload, i
		end
	end
	return nil
end

local Granted = Enum.ProductPurchaseDecision.PurchaseGranted
local NotYet = Enum.ProductPurchaseDecision.NotProcessedYet

---------------------------------------------------------------------------
-- Tests
---------------------------------------------------------------------------
print("== join")
local alice = join(1, "Alice")
check("leaderstats created with 0 cash", cash(alice) == 0)
check("profile locked by this server", savedRecord(alice) and savedRecord(alice).Lock and savedRecord(alice).Lock.JobId == "job-A")
local state = lastFired("ShopState", alice)
check("ShopState sent after ClientReady", state ~= nil and state.Owned.Speed == false and type(state.ResetAt) == "number")

print("== cash purchase + idempotency")
check("ProcessReceipt grants +25K", receipt(alice, Config.Products.Cash25K.Id, "p1", 99) == Granted)
check("leaderstats 25,000", cash(alice) == 25000, cash(alice))
check("saved cash 25,000 + receipt logged", savedRecord(alice).Data.Cash == 25000 and table.find(savedRecord(alice).Data.Receipts, "p1") ~= nil)
check("duplicate receipt still Granted", receipt(alice, Config.Products.Cash25K.Id, "p1", 99) == Granted)
check("duplicate receipt does not grant twice", cash(alice) == 25000 and savedRecord(alice).Data.Cash == 25000)

print("== failed save -> NotProcessedYet -> retry")
playerStore.failUpdates = 4
check("save outage returns NotProcessedYet", receipt(alice, Config.Products.Cash100K.Id, "p2", 249) == NotYet)
do
	-- Autosave may persist the in-memory grant later, but always together with its receipt.
	local rec = savedRecord(alice).Data
	check("outage: cash and receipt are only ever saved together", (rec.Cash == 25000) == (table.find(rec.Receipts, "p2") == nil))
end
check("retry after outage is Granted", receipt(alice, Config.Products.Cash100K.Id, "p2", 249) == Granted)
check("granted exactly once (125,000)", savedRecord(alice).Data.Cash == 125000 and cash(alice) == 125000, savedRecord(alice).Data.Cash)

print("== unknown player / product")
check("receipt for absent player -> NotProcessedYet", services.MarketplaceService.ProcessReceipt({ PlayerId = 999, ProductId = Config.Products.Sack1.Id, PurchaseId = "x", CurrencySpent = 0 }) == NotYet)
check("receipt for unknown product -> NotProcessedYet", receipt(alice, 424242, "p-unknown") == NotYet)

print("== santa's sack x10 + delayed reveal")
local before = savedRecord(alice).Data.Cash
check("sack x10 granted", receipt(alice, Config.Products.Sack10.Id, "p3", 799) == Granted)
local sackPayload = lastFired("SackResults", alice)
check("SackResults sent with 10 results", sackPayload and #sackPayload.Results == 10)
local expected = 0
for _, key in sackPayload.Results do
	expected += Config.getLoot(key).Cash
end
check("payload cash matches loot table", sackPayload.Cash == expected)
check("saved cash includes the rolls immediately", savedRecord(alice).Data.Cash == before + expected)
check("leaderstats hides sack cash until reveal", cash(alice) == before)
check("stats: 10 sacks opened", savedRecord(alice).Data.Stats.SacksOpened == 10)
remote("SackRevealDone").OnServerEvent:Fire(alice, sackPayload.BatchId)
advance(0.1)
check("leaderstats shows sack cash after reveal", cash(alice) == before + expected)
remote("SackRevealDone").OnServerEvent:Fire(alice, sackPayload.BatchId)
advance(0.1)
check("double reveal confirmation is harmless", cash(alice) == before + expected)

print("== reveal timeout")
local before2 = cash(alice)
check("sack x1 granted", receipt(alice, Config.Products.Sack1.Id, "p4", 99) == Granted)
local p4 = lastFired("SackResults", alice)
check("cash hidden right after purchase", cash(alice) == before2)
advance(Config.RevealTimeoutSeconds + 1)
check("cash revealed after timeout without client", cash(alice) == before2 + p4.Cash)

print("== validation + rate limit")
prompts = {}
local req = remote("PurchaseRequest").OnServerEvent
req:Fire(alice, 123)
req:Fire(alice, "NotAThing")
req:Fire(alice, "Sack1", -5)
req:Fire(alice, "Sack1", 0 / 0)
req:Fire(alice, "Sack1", 1.5)
advance(0.1)
check("invalid requests never prompt", #prompts == 0, #prompts)
advance(11)
for _ = 1, 10 do
	req:Fire(alice, "Cash25K")
end
advance(0.1)
check("rate limit: max 6 prompts per 10s", #prompts == 6, #prompts)
check("prompt uses the normal product id", prompts[1].id == Config.Products.Cash25K.Id)
advance(11)

print("== gamepass purchase")
prompts = {}
req:Fire(alice, "Speed")
advance(0.1)
check("gamepass prompt", #prompts == 1 and prompts[1].kind == "pass" and prompts[1].id == Config.GamePasses.Speed.Id)
services.MarketplaceService.PromptGamePassPurchaseFinished:Fire(alice, Config.GamePasses.Speed.Id, true)
advance(0.1)
check("ShopState shows Speed owned", lastFired("ShopState", alice).Owned.Speed == true)
prompts = {}
req:Fire(alice, "Speed")
advance(0.1)
check("owned pass is not prompted again", #prompts == 0)

print("== gifting")
advance(11)
local bob = join(2, "Bob")
local carol = join(3, "Carol")
friendships["1:2"] = true
prompts = {}
req:Fire(alice, "Cash100K", carol.UserId)
advance(0.1)
check("gift to non-friend is refused", #prompts == 0)
req:Fire(alice, "Cash100K", alice.UserId)
advance(0.1)
check("gift to self is refused", #prompts == 0)
req:Fire(alice, "Cash100K", bob.UserId)
advance(0.1)
check("gift to friend prompts the GIFT product", #prompts == 1 and prompts[1].id == Config.Products.Cash100K.GiftId)
local aliceBefore = savedRecord(alice).Data.Cash
check("gift receipt granted", receipt(alice, Config.Products.Cash100K.GiftId, "g1", 249) == Granted)
check("recipient got +100K", cash(bob) == 100000 and savedRecord(bob).Data.Cash == 100000, cash(bob))
check("buyer did not get the cash", savedRecord(alice).Data.Cash == aliceBefore)
check("buyer notified", (lastFired("Notify", alice) or {}).Text:find("Bob") ~= nil)
check("recipient notified", (lastFired("Notify", bob) or {}).Text:find("gifted") ~= nil)
local inbox = stores[Config.GiftDataStoreName].data["2"]
check("inbox emptied after claim", inbox == nil or next(inbox.Gifts) == nil)
check("gift receipt replay is harmless", receipt(alice, Config.Products.Cash100K.GiftId, "g1", 249) == Granted and cash(bob) == 100000)

print("== gift a gamepass")
advance(11)
prompts = {}
req:Fire(alice, "Luck", bob.UserId)
advance(0.1)
check("pass gift prompts gift product", #prompts == 1 and prompts[1].id == Config.GamePasses.Luck.GiftId)
check("pass gift granted", receipt(alice, Config.GamePasses.Luck.GiftId, "g2", 249) == Granted)
check("recipient owns Luck (saved)", savedRecord(bob).Data.GiftedPasses.Luck == true)
check("recipient ShopState updated", lastFired("ShopState", bob).Owned.Luck == true)
advance(11)
prompts = {}
req:Fire(alice, "Luck", bob.UserId)
advance(0.1)
check("cannot gift a pass the friend already owns", #prompts == 0)

print("== gift receipt without intent falls back to buyer")
local carolBefore = savedRecord(carol).Data.Cash
check("orphan gift receipt granted", receipt(carol, Config.Products.Cash25K.GiftId, "g3", 99) == Granted)
check("buyer receives orphan gift", savedRecord(carol).Data.Cash == carolBefore + 25000)

print("== offline recipient (cross-server inbox)")
advance(11)
req:Fire(alice, "Sack1", bob.UserId)
advance(0.1)
leave(bob)
check("bob's lock released on leave", savedRecord(bob).Lock == nil)
-- intent was stored before Bob left; the receipt arrives afterwards
check("gift for departed friend still granted", receipt(alice, Config.Products.Sack1.GiftId, "g4", 99) == Granted)
check("gift waits in inbox", stores[Config.GiftDataStoreName].data["2"].Gifts["g4"] ~= nil)
fired = {}
local bob2 = join(2, "Bob")
advance(2)
local giftSack = lastFired("SackResults", bob2)
check("rejoining recipient gets the sack minigame", giftSack ~= nil and giftSack.FromName == "Alice" and #giftSack.Results == 1)
check("inbox cleared after claim", next(stores[Config.GiftDataStoreName].data["2"].Gifts) == nil)

print("== session locking")
leave(alice)
playerStore.data["Player_1"].Lock = { JobId = "other-server", Time = fakeOs.time() }
local alice2 = makePlayer(1, "Alice")
alice2.Parent = Players
Players.PlayerAdded:Fire(alice2)
advance(3)
check("locked profile is not loaded immediately", savedRecord(alice2).Lock.JobId == "other-server")
advance(60)
check("lock is taken over after retries", savedRecord(alice2).Lock.JobId == "job-A")
check("data intact after takeover", savedRecord(alice2).Data.Cash > 0)
playerStore.data["Player_1"].Lock = { JobId = "thief", Time = fakeOs.time() }
advance(61) -- autosave notices the stolen session
check("stolen session kicks the player instead of overwriting", alice2.kicked ~= nil and savedRecord(alice2).Lock.JobId == "thief")

print("== shutdown")
for _, fn in bindToClose do
	task.spawn(fn)
end
advance(5)
check("BindToClose released carol's lock", savedRecord(carol).Lock == nil)

print("")
check("no script errors", #errors == 0, errors[1])
for _, w in warnings do
	print("     warn: " .. w)
end
print(string.format("%d passed, %d failed", passes, fails))
if fails > 0 then
	error("tests failed")
end
