-- Meteor-Spawning: globale Meteore, persönliche Meteore pro Spieler, Lucky Meteors
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local Workspace = game:GetService("Workspace")
local HttpService = game:GetService("HttpService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Util = require(Shared:WaitForChild("Util"))

local MeteorService = {}
local rng = Random.new()

function MeteorService:Init(services)
	self.S = services
end

---------------------------------------------------------------------------
-- Würfeln
---------------------------------------------------------------------------
function MeteorService:RollRarity(luck, minOrder)
	local cosmicMult = self.S.EventService:Mult("CosmicMult")
	local pool = {}
	for _, r in ipairs(Config.Rarities) do
		if r.Order >= (minOrder or 1) then
			table.insert(pool, r)
		end
	end
	return Util.WeightedPick(pool, function(r)
		local w = r.Weight * (luck ^ ((r.Order - 1) * 0.6))
		if r.Id == "Cosmic" then
			w = w * cosmicMult
		end
		return w
	end, rng)
end

function MeteorService:RollMutation()
	local goldenMult = self.S.EventService:Mult("GoldenMult")
	local m = Util.WeightedPick(Config.Mutations, function(mut)
		if mut.Id == "Golden" then
			return mut.Weight * goldenMult
		end
		return mut.Weight
	end, rng)
	return m.Id
end

function MeteorService:Roll(luck, minOrder)
	local rarity = self:RollRarity(luck, minOrder)
	local list = Config.CoresByRarity[rarity.Id]
	local core = list[rng:NextInteger(1, #list)]
	return core.Id, self:RollMutation()
end

---------------------------------------------------------------------------
-- Landeplatz
---------------------------------------------------------------------------
local function landParams()
	local params = RaycastParams.new()
	params.FilterType = Enum.RaycastFilterType.Include
	params.FilterDescendantsInstances = { Workspace.Terrain }
	params.IgnoreWater = false
	return params
end

function MeteorService:ValidLanding(x, z)
	local d = math.sqrt(x * x + z * z)
	if d < Config.SafeZoneRadius + 22 then
		return nil
	end
	for _, plot in ipairs(self.S.PlotService.Plots) do
		if self.S.PlotService:IsInside(plot, Vector3.new(x, Config.GroundHeight + 2, z), 8) then
			return nil
		end
	end
	local hit = Workspace:Raycast(Vector3.new(x, 400, z), Vector3.new(0, -500, 0), landParams())
	if not hit or hit.Material == Enum.Material.Water then
		return nil
	end
	if hit.Normal.Y < 0.78 or hit.Position.Y < 1 or hit.Position.Y > Config.GroundHeight + 32 then
		return nil
	end
	return hit.Position
end

function MeteorService:RandomLanding(center, minR, maxR)
	for _ = 1, 25 do
		local x, z
		if center then
			local a = rng:NextNumber(0, math.pi * 2)
			local r = rng:NextNumber(minR, maxR)
			x, z = center.X + math.cos(a) * r, center.Z + math.sin(a) * r
		else
			local a = rng:NextNumber(0, math.pi * 2)
			local r = math.sqrt(rng:NextNumber()) * (Config.IslandRadius - 40)
			x, z = math.cos(a) * r, math.sin(a) * r
		end
		local pos = self:ValidLanding(x, z)
		if pos then
			return pos
		end
	end
	return nil
end

---------------------------------------------------------------------------
-- Start eines Meteors
---------------------------------------------------------------------------
function MeteorService:Launch(coreId, mutation, landPos)
	local def = Config.CoreById[coreId]
	local rarity = Config.RarityById[def.Rarity]
	local fall = Config.FallTime + ((rarity.Order >= 5) and 1.8 or 0)
	local a = rng:NextNumber(0, math.pi * 2)
	local start = landPos + Vector3.new(math.cos(a) * 260, 520, math.sin(a) * 260)

	self.S.Remotes:FireAll("MeteorIncoming", {
		Id = HttpService:GenerateGUID(false),
		Start = start,
		Target = landPos,
		Time = fall,
		Core = coreId,
		Mutation = mutation,
	})
	if rarity.Order >= 5 then
		self.S.Remotes:Announce("⚠️ A " .. string.upper(def.Rarity) .. " meteor is falling from the sky!", rarity.Color)
	end

	task.delay(fall, function()
		self.S.CoreService:SpawnWild(coreId, mutation, landPos)
		self.S.Remotes:FireAll("MeteorImpact", landPos, coreId, mutation)
	end)
end

function MeteorService:SpawnGlobal()
	if self.S.CoreService:WildCount() >= Config.MaxWildCores then
		return
	end
	local pos = self:RandomLanding(nil)
	if not pos then
		return
	end
	local luck = self.S.EventService:Mult("LuckMult")
	local coreId, mutation = self:Roll(luck)
	self:Launch(coreId, mutation, pos)
end

function MeteorService:SpawnPersonal(player, minOrder)
	local char = player.Character
	local root = char and char:FindFirstChild("HumanoidRootPart")
	if not root then
		return false
	end
	local pos = self:RandomLanding(root.Position, minOrder and 14 or 30, minOrder and 30 or 110)
	if not pos then
		pos = self:RandomLanding(nil)
	end
	if not pos then
		return false
	end
	local coreId, mutation = self:Roll(self.S.EconomyService:Luck(player), minOrder)
	self:Launch(coreId, mutation, pos)
	return true
end

function MeteorService:SpawnLucky(player)
	local ok = self:SpawnPersonal(player, 5)
	if ok then
		self.S.Remotes:Notify(player, "🌟 Your Lucky Meteor is incoming – look up!", "rare")
	end
	return ok
end

---------------------------------------------------------------------------
-- Lifecycle
---------------------------------------------------------------------------
function MeteorService:Start()
	while not Workspace:GetAttribute("WorldReady") do
		task.wait(0.5)
	end

	-- personliche Meteore
	task.spawn(function()
		local nextFor = {}
		while true do
			task.wait(1)
			local now = os.clock()
			for _, player in ipairs(Players:GetPlayers()) do
				if not nextFor[player] then
					nextFor[player] = now + rng:NextNumber(4, 10)
				elseif now >= nextFor[player] then
					local interval = rng:NextNumber(Config.PersonalMeteorInterval[1], Config.PersonalMeteorInterval[2])
					nextFor[player] = now + interval / self.S.EventService:Mult("SpawnMult")
					if self.S.CoreService:WildCount() < Config.MaxWildCores + 10 then
						self:SpawnPersonal(player)
					end
				end
			end
			for player in pairs(nextFor) do
				if player.Parent ~= Players then
					nextFor[player] = nil
				end
			end
		end
	end)

	-- globale Meteore
	while true do
		local interval = rng:NextNumber(Config.GlobalMeteorInterval[1], Config.GlobalMeteorInterval[2])
		task.wait(interval / self.S.EventService:Mult("SpawnMult"))
		if #Players:GetPlayers() > 0 then
			self:SpawnGlobal()
		end
	end
end

return MeteorService
