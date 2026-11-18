-- Cores in der Welt: wilde Cores, Tragen, Klauen, Platzieren, Verkaufen, Fallenlassen
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local CollectionService = game:GetService("CollectionService")
local Workspace = game:GetService("Workspace")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Util = require(Shared:WaitForChild("Util"))
local CoreBuilder = require(Shared:WaitForChild("CoreBuilder"))

local CoreService = {
	Wild = {}, -- [model] = { T, M, Expires }
	Carrying = {}, -- [player] = { Model, T, M, Stolen, FromOwner, FromSlot, Warned }
}

function CoreService:Init(services)
	self.S = services
	self.WildFolder = Instance.new("Folder")
	self.WildFolder.Name = "WildCores"
	self.WildFolder.Parent = Workspace
end

function CoreService:WildCount()
	local n = 0
	for _ in pairs(self.Wild) do
		n = n + 1
	end
	return n
end

---------------------------------------------------------------------------
-- Wilde Cores
---------------------------------------------------------------------------
function CoreService:SpawnWild(coreId, mutation, position, lifetime)
	local def = Config.CoreById[coreId]
	if not def then
		return nil
	end
	local rarity = Config.RarityById[def.Rarity]
	local model = CoreBuilder.Build(coreId, mutation)
	local size = CoreBuilder.Size(coreId)
	local pos = position + Vector3.new(0, size * 0.75 + 0.8, 0)
	model:PivotTo(CFrame.new(pos))
	model.PrimaryPart:SetAttribute("BasePos", pos)
	CollectionService:AddTag(model.PrimaryPart, "SpinCore")

	-- Lichtsäule für seltene Cores
	if rarity.Order >= 5 then
		local beam = Instance.new("Part")
		beam.Name = "SkyBeam"
		beam.Anchored = true
		beam.CanCollide = false
		beam.CanQuery = false
		beam.CanTouch = false
		beam.CastShadow = false
		beam.Material = Enum.Material.Neon
		beam.Color = rarity.Color
		beam.Transparency = 0.55
		beam.Shape = Enum.PartType.Cylinder
		beam.Size = Vector3.new(320, 2.2, 2.2)
		beam.CFrame = CFrame.new(position + Vector3.new(0, 160, 0)) * CFrame.Angles(0, 0, math.pi / 2)
		beam.Parent = model
	end

	local prompt = Instance.new("ProximityPrompt")
	prompt.Name = "GrabPrompt"
	prompt.ActionText = "Grab"
	prompt.ObjectText = Config.CoreDisplayName(coreId, mutation)
	prompt.HoldDuration = (rarity.Order >= 5) and 0.9 or 0.35
	prompt.MaxActivationDistance = 10
	prompt.RequiresLineOfSight = false
	prompt.KeyboardKeyCode = Enum.KeyCode.E
	prompt.Parent = model.PrimaryPart
	prompt.Triggered:Connect(function(player)
		self:GrabWild(player, model)
	end)

	model.Parent = self.WildFolder
	self.Wild[model] = { T = coreId, M = mutation, Expires = os.clock() + (lifetime or Config.WildLifetime) * (rarity.Order >= 5 and 1.6 or 1) }
	return model
end

function CoreService:RemoveWild(model)
	self.Wild[model] = nil
	if model then
		model:Destroy()
	end
end

function CoreService:GrabWild(player, model)
	local info = self.Wild[model]
	if not info then
		return
	end
	if self.Carrying[player] then
		self.S.Remotes:Notify(player, "You're already carrying a core! Bring it to your base first.", "warning")
		return
	end
	if not self:CanAct(player) then
		return
	end
	self:RemoveWild(model)
	self:StartCarry(player, info.T, info.M, false, nil, nil)
end

---------------------------------------------------------------------------
-- Tragen
---------------------------------------------------------------------------
function CoreService:CanAct(player)
	local char = player.Character
	local hum = char and char:FindFirstChildOfClass("Humanoid")
	return hum ~= nil and hum.Health > 0 and char:FindFirstChild("HumanoidRootPart") ~= nil
end

function CoreService:StartCarry(player, coreId, mutation, stolen, fromOwner, fromSlot)
	local char = player.Character
	local root = char and char:FindFirstChild("HumanoidRootPart")
	if not root then
		return false
	end
	local model = CoreBuilder.Build(coreId, mutation)
	local size = CoreBuilder.Size(coreId)
	for _, d in ipairs(model:GetDescendants()) do
		if d:IsA("BasePart") then
			d.Anchored = false
			d.Massless = true
			d.CanCollide = false
		end
	end
	local info = model.PrimaryPart:FindFirstChild("Info")
	if info then
		info.Enabled = false
	end
	model:PivotTo(root.CFrame * CFrame.new(0, 3.2 + size * 0.6, 0))
	local weld = Instance.new("WeldConstraint")
	weld.Part0 = root
	weld.Part1 = model.PrimaryPart
	weld.Parent = model.PrimaryPart
	model.Name = "CarriedCore"
	model.Parent = char
	pcall(function()
		model.PrimaryPart:SetNetworkOwner(player)
	end)

	self.Carrying[player] = { Model = model, T = coreId, M = mutation, Stolen = stolen, FromOwner = fromOwner, FromSlot = fromSlot }
	local def = Config.CoreById[coreId]
	local rarity = Config.RarityById[def.Rarity]
	self.S.Remotes:Fire("Carry", player, {
		Name = Config.CoreDisplayName(coreId, mutation),
		Rarity = def.Rarity,
		Color = Config.MutationById[mutation].Color or rarity.Color,
		Income = Config.CoreIncome(coreId, mutation),
		Stolen = stolen,
	})
	self.S.EconomyService:ApplySpeed(player)

	-- Entdeckt?
	local data = self.S.DataService:Get(player)
	if data and not data.Discovered[coreId] then
		data.Discovered[coreId] = true
		self.S.Remotes:Notify(player, "📖 NEW DISCOVERY: " .. def.Name .. " (" .. def.Rarity .. ")", "rare")
		self.S.EconomyService:MarkDirty(player)
	end
	if rarity.Order >= 5 and not stolen then
		self.S.Remotes:Announce("✨ " .. player.DisplayName .. " grabbed a " .. string.upper(def.Rarity) .. " " .. Config.CoreDisplayName(coreId, mutation) .. "!", rarity.Color)
	end
	return true
end

function CoreService:ClearCarry(player)
	local carry = self.Carrying[player]
	if not carry then
		return nil
	end
	self.Carrying[player] = nil
	if carry.Model then
		carry.Model:Destroy()
	end
	self.S.Remotes:Fire("Carry", player, nil)
	self.S.EconomyService:ApplySpeed(player)
	return carry
end

-- reason: "death" | "bonk" | "leave"
function CoreService:Drop(player, reason)
	local carry = self.Carrying[player]
	if not carry then
		return
	end
	local char = player.Character
	local root = char and char:FindFirstChild("HumanoidRootPart")
	local dropPos = root and root.Position or nil
	self:ClearCarry(player)

	-- Gestohlen? -> zurück zum Besitzer
	if carry.Stolen and carry.FromOwner and carry.FromOwner.Parent == Players then
		local owner = carry.FromOwner
		local data = self.S.DataService:Get(owner)
		local plot = self.S.PlotService:GetPlot(owner)
		if data and plot then
			local slot = carry.FromSlot
			if not slot or data.Cores[tostring(slot)] or slot > self.S.PlotService:UnlockedSlots(owner) then
				slot = self.S.PlotService:FreeSlot(owner)
			end
			if slot then
				data.Cores[tostring(slot)] = { T = carry.T, M = carry.M }
				self.S.PlotService:RefreshSlot(plot, slot)
				self.S.EconomyService:MarkDirty(owner)
				self.S.Remotes:Notify(owner, "🛡️ Your " .. Config.CoreDisplayName(carry.T, carry.M) .. " was returned to your base!", "success")
				if reason == "bonk" then
					self.S.Remotes:Notify(player, "💥 You got bonked! The core flew back home.", "error")
				end
				return
			end
		end
	end

	-- sonst: fällt auf den Boden
	if dropPos then
		local params = RaycastParams.new()
		params.FilterType = Enum.RaycastFilterType.Exclude
		params.FilterDescendantsInstances = { char, self.WildFolder }
		local hit = Workspace:Raycast(dropPos + Vector3.new(0, 2, 0), Vector3.new(0, -60, 0), params)
		local ground = hit and hit.Position or (dropPos - Vector3.new(0, 3, 0))
		local offset = Vector3.new(math.random(-3, 3), 0, math.random(-3, 3))
		self:SpawnWild(carry.T, carry.M, ground + offset, 30)
	end
end

---------------------------------------------------------------------------
-- Sockel (eigene Basis / fremde Basis)
---------------------------------------------------------------------------
function CoreService:OnSlotPrompt(player, plot, slotIndex)
	local owner = plot.Owner
	if not owner or not self:CanAct(player) then
		return
	end
	local data = self.S.DataService:Get(owner)
	local key = tostring(slotIndex)
	local core = data and data.Cores[key]
	if not core then
		return
	end
	if self.Carrying[player] then
		self.S.Remotes:Notify(player, "Your hands are full!", "warning")
		return
	end

	if player == owner then
		-- eigenen Core aufnehmen (zum Verkaufen / Umstellen)
		data.Cores[key] = nil
		self.S.PlotService:RefreshSlot(plot, slotIndex)
		self:StartCarry(player, core.T, core.M, false, nil, nil)
		self.S.EconomyService:MarkDirty(player)
		return
	end

	if self.S.PlotService:IsLocked(plot) then
		self.S.Remotes:Notify(player, "🛡️ This base is locked!", "error")
		return
	end

	-- KLAUEN
	data.Cores[key] = nil
	self.S.PlotService:RefreshSlot(plot, slotIndex)
	self.S.EconomyService:MarkDirty(owner)
	self:StartCarry(player, core.T, core.M, true, owner, slotIndex)
	self.S.Remotes:Notify(owner, "🚨 " .. player.DisplayName .. " is STEALING your " .. Config.CoreDisplayName(core.T, core.M) .. "! Bonk them!", "error")
	self.S.Remotes:Notify(player, "🦹 You stole a core! Run to your base – you're slower now!", "warning")
end

function CoreService:TryPlace(player)
	local carry = self.Carrying[player]
	if not carry then
		return
	end
	local plot = self.S.PlotService:GetPlot(player)
	local data = self.S.DataService:Get(player)
	if not plot or not data then
		return
	end
	local slot = self.S.PlotService:FreeSlot(player)
	if not slot then
		if not carry.Warned then
			carry.Warned = true
			self.S.Remotes:Notify(player, "🧱 Your base is full! Upgrade Base Expansion or sell a core at the Core Exchange.", "warning")
		end
		return
	end
	self:ClearCarry(player)
	data.Cores[tostring(slot)] = { T = carry.T, M = carry.M }
	data.Discovered[carry.T] = true
	self.S.PlotService:RefreshSlot(plot, slot)
	self.S.EconomyService:MarkDirty(player)
	local income = Config.CoreIncome(carry.T, carry.M) * self.S.EconomyService:CashMultiplier(player)
	self.S.Remotes:Notify(player, "✅ " .. Config.CoreDisplayName(carry.T, carry.M) .. " placed! +" .. Util.Money(income) .. "/s", "success")
	if carry.Stolen and carry.FromOwner then
		self.S.Remotes:Notify(carry.FromOwner, "😭 " .. player.DisplayName .. " got away with your " .. Config.CoreDisplayName(carry.T, carry.M) .. "!", "error")
		local def = Config.CoreById[carry.T]
		if Config.RarityById[def.Rarity].Order >= 5 then
			self.S.Remotes:Announce("🦹 " .. player.DisplayName .. " stole a " .. string.upper(def.Rarity) .. " core from " .. carry.FromOwner.DisplayName .. "!", Color3.fromRGB(255, 80, 80))
		end
	end
end

function CoreService:Sell(player)
	local carry = self.Carrying[player]
	if not carry then
		self.S.Remotes:Notify(player, "Bring a core here to sell it!", "info")
		return
	end
	local value = math.floor(Config.CoreIncome(carry.T, carry.M) * Config.SellMultiplier * self.S.EconomyService:CashMultiplier(player))
	self:ClearCarry(player)
	self.S.EconomyService:AddCash(player, value)
	self.S.Remotes:Fire("Collected", player, value)
	self.S.Remotes:Notify(player, "💸 Sold " .. Config.CoreDisplayName(carry.T, carry.M) .. " for " .. Util.Money(value) .. "!", "success")
	if carry.Stolen and carry.FromOwner then
		self.S.Remotes:Notify(carry.FromOwner, "😭 " .. player.DisplayName .. " sold your stolen core!", "error")
	end
end

---------------------------------------------------------------------------
-- Lifecycle
---------------------------------------------------------------------------
function CoreService:Start()
	local sellPrompt = self.S.WorldBuilder.SellPrompt
	if sellPrompt then
		sellPrompt.Triggered:Connect(function(player)
			self:Sell(player)
		end)
	end

	local function onPlayer(player)
		player.CharacterAdded:Connect(function(char)
			local hum = char:WaitForChild("Humanoid", 10)
			if hum then
				hum.Died:Connect(function()
					self:Drop(player, "death")
				end)
			end
		end)
		player.CharacterRemoving:Connect(function()
			self:Drop(player, "death")
		end)
	end
	Players.PlayerAdded:Connect(onPlayer)
	for _, p in ipairs(Players:GetPlayers()) do
		onPlayer(p)
	end

	Players.PlayerRemoving:Connect(function(player)
		self:Drop(player, "leave")
		-- Cores, die dieser Spieler gerade klaut, gehen an niemanden zurück -> landen auf dem Boden
		for thief, carry in pairs(self.Carrying) do
			if carry.FromOwner == player then
				carry.FromOwner = nil
				carry.Stolen = false
				self.S.EconomyService:ApplySpeed(thief)
			end
		end
	end)

	while true do
		task.wait(0.2)
		-- Platzieren, sobald der Träger in seiner Basis ist
		for player in pairs(self.Carrying) do
			local plot = self.S.PlotService:GetPlot(player)
			local char = player.Character
			local root = char and char:FindFirstChild("HumanoidRootPart")
			if plot and root then
				if self.S.PlotService:IsInside(plot, root.Position, 0) then
					self:TryPlace(player)
				else
					local carry = self.Carrying[player]
					if carry then
						carry.Warned = false
					end
				end
			end
		end
		-- Ablaufende wilde Cores
		local now = os.clock()
		for model, info in pairs(self.Wild) do
			if now > info.Expires or not model.Parent then
				self:RemoveWild(model)
			end
		end
	end
end

return CoreService
