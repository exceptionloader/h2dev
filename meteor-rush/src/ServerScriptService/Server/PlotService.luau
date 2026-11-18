-- Spieler-Basen: Bau, Zuweisung, Sockel, Lock-Schild, Collect-Pad
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local CollectionService = game:GetService("CollectionService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Util = require(Shared:WaitForChild("Util"))
local CoreBuilder = require(Shared:WaitForChild("CoreBuilder"))

local PlotService = { Plots = {}, ByPlayer = {} }

local G = Config.GroundHeight
local S = Config.PlotSize
local HALF = S / 2
local FT = G + 1.5 -- Oberkante Boden
local LOCK_TIME = 60

local PLOT_COLORS = {
	Color3.fromRGB(255, 120, 60),
	Color3.fromRGB(80, 200, 255),
	Color3.fromRGB(150, 255, 110),
	Color3.fromRGB(255, 90, 200),
	Color3.fromRGB(255, 220, 70),
	Color3.fromRGB(160, 110, 255),
	Color3.fromRGB(60, 255, 200),
	Color3.fromRGB(255, 80, 90),
}
local DIM = Color3.fromRGB(90, 94, 110)

local SLOT_X = { -21, -7, 7, 21 }
local SLOT_Z = { 28, 16, 4, -8 } -- hinten -> vorne (vorne = -Z = Richtung Zentrum)

local function P(props, parent)
	local p = Instance.new("Part")
	p.Anchored = true
	p.TopSurface = Enum.SurfaceType.Smooth
	p.BottomSurface = Enum.SurfaceType.Smooth
	for k, v in pairs(props) do
		p[k] = v
	end
	p.Parent = parent
	return p
end

local UP = CFrame.Angles(0, 0, math.pi / 2)

local function billboard(parent, offset, size, text, color)
	local gui = Instance.new("BillboardGui")
	gui.Size = size
	gui.StudsOffsetWorldSpace = offset
	gui.MaxDistance = 140
	gui.LightInfluence = 0
	gui.Parent = parent
	local l = Instance.new("TextLabel")
	l.Name = "Text"
	l.BackgroundTransparency = 1
	l.Size = UDim2.fromScale(1, 1)
	l.Font = Enum.Font.FredokaOne
	l.TextScaled = true
	l.Text = text
	l.TextColor3 = color
	local stroke = Instance.new("UIStroke")
	stroke.Thickness = 2.5
	stroke.Color = Color3.fromRGB(10, 10, 20)
	stroke.Parent = l
	l.Parent = gui
	return l
end

function PlotService:BuildPlot(index)
	local cf = Config.PlotCFrame(index)
	local color = PLOT_COLORS[(index - 1) % #PLOT_COLORS + 1]
	local model = Instance.new("Folder")
	model.Name = "Plot" .. index
	local plot = { Index = index, CFrame = cf, Folder = model, Color = color, Slots = {}, Trims = {}, LockedUntil = 0, Pending = 0 }

	local function at(x, y, z)
		return cf * CFrame.new(x, y, z)
	end

	-- Boden
	P({ Name = "Floor", Size = Vector3.new(S, 2, S), CFrame = at(0, FT - 1, 0), Material = Enum.Material.Slate, Color = Color3.fromRGB(50, 54, 70) }, model)
	P({ Name = "Tiles", Size = Vector3.new(S - 14, 0.2, 50), CFrame = at(0, FT + 0.1, 10), Material = Enum.Material.Marble, Color = Color3.fromRGB(205, 208, 220) }, model)

	-- Mauern
	local wallColor = Color3.fromRGB(58, 62, 82)
	local walls = {
		{ Vector3.new(S, 6, 1.5), Vector3.new(0, FT + 3, HALF - 0.75) },
		{ Vector3.new(1.5, 6, S), Vector3.new(-HALF + 0.75, FT + 3, 0) },
		{ Vector3.new(1.5, 6, S), Vector3.new(HALF - 0.75, FT + 3, 0) },
		{ Vector3.new(24, 6, 1.5), Vector3.new(-24, FT + 3, -HALF + 0.75) },
		{ Vector3.new(24, 6, 1.5), Vector3.new(24, FT + 3, -HALF + 0.75) },
	}
	for _, w in ipairs(walls) do
		P({ Name = "Wall", Size = w[1], CFrame = at(w[2].X, w[2].Y, w[2].Z), Material = Enum.Material.Concrete, Color = wallColor }, model)
		local trim = P({ Name = "Trim", Size = Vector3.new(w[1].X + 0.2, 0.5, w[1].Z + 0.2), CFrame = at(w[2].X, FT + 6.2, w[2].Z), Material = Enum.Material.Neon, Color = DIM, CastShadow = false }, model)
		table.insert(plot.Trims, trim)
	end

	-- Torbogen mit Schild
	for _, x in ipairs({ -12.75, 12.75 }) do
		P({ Name = "Pillar", Size = Vector3.new(2.5, 17, 2.5), CFrame = at(x, FT + 8.5, -HALF + 0.75), Material = Enum.Material.Concrete, Color = wallColor }, model)
		table.insert(plot.Trims, P({ Name = "PillarGlow", Size = Vector3.new(2.7, 0.6, 2.7), CFrame = at(x, FT + 14, -HALF + 0.75), Material = Enum.Material.Neon, Color = DIM, CastShadow = false }, model))
	end
	local beam = P({ Name = "SignBoard", Size = Vector3.new(28, 5, 1.2), CFrame = at(0, FT + 18, -HALF + 0.75), Material = Enum.Material.SmoothPlastic, Color = Color3.fromRGB(16, 18, 30) }, model)
	local gui = Instance.new("SurfaceGui")
	gui.Face = Enum.NormalId.Front
	gui.SizingMode = Enum.SurfaceGuiSizingMode.PixelsPerStud
	gui.PixelsPerStud = 32
	gui.LightInfluence = 0
	gui.Brightness = 1.5
	gui.Parent = beam
	local avatar = Instance.new("ImageLabel")
	avatar.Name = "Avatar"
	avatar.BackgroundTransparency = 1
	avatar.Size = UDim2.fromScale(0.17, 0.9)
	avatar.Position = UDim2.fromScale(0.02, 0.05)
	avatar.Image = ""
	avatar.Parent = gui
	local aspect = Instance.new("UIAspectRatioConstraint")
	aspect.Parent = avatar
	local nameLabel = Instance.new("TextLabel")
	nameLabel.Name = "Owner"
	nameLabel.BackgroundTransparency = 1
	nameLabel.Size = UDim2.fromScale(0.76, 0.62)
	nameLabel.Position = UDim2.fromScale(0.21, 0.04)
	nameLabel.Font = Enum.Font.FredokaOne
	nameLabel.TextScaled = true
	nameLabel.TextXAlignment = Enum.TextXAlignment.Left
	nameLabel.Text = "EMPTY BASE"
	nameLabel.TextColor3 = Color3.fromRGB(200, 200, 210)
	nameLabel.Parent = gui
	local incomeLabel = Instance.new("TextLabel")
	incomeLabel.Name = "Income"
	incomeLabel.BackgroundTransparency = 1
	incomeLabel.Size = UDim2.fromScale(0.76, 0.32)
	incomeLabel.Position = UDim2.fromScale(0.21, 0.64)
	incomeLabel.Font = Enum.Font.GothamBold
	incomeLabel.TextScaled = true
	incomeLabel.TextXAlignment = Enum.TextXAlignment.Left
	incomeLabel.Text = "Join to claim!"
	incomeLabel.TextColor3 = Color3.fromRGB(120, 255, 150)
	incomeLabel.Parent = gui
	plot.SignName, plot.SignIncome, plot.SignAvatar = nameLabel, incomeLabel, avatar
	table.insert(plot.Trims, P({ Name = "SignGlow", Size = Vector3.new(28.4, 0.5, 1.6), CFrame = at(0, FT + 15.3, -HALF + 0.75), Material = Enum.Material.Neon, Color = DIM, CastShadow = false }, model))

	-- Sockel
	for row = 1, 4 do
		for col = 1, 4 do
			local i = (row - 1) * 4 + col
			local x, z = SLOT_X[col], SLOT_Z[row]
			local ped = P({ Name = "Pedestal" .. i, Shape = Enum.PartType.Cylinder, Size = Vector3.new(2, 7, 7), CFrame = at(x, FT + 1, z) * UP, Material = Enum.Material.Marble, Color = Color3.fromRGB(235, 236, 242) }, model)
			local ring = P({ Name = "Ring", Shape = Enum.PartType.Cylinder, Size = Vector3.new(0.4, 7.8, 7.8), CFrame = at(x, FT + 0.25, z) * UP, Material = Enum.Material.Neon, Color = DIM, CastShadow = false, CanCollide = false }, model)
			local plate = P({ Name = "Plate", Shape = Enum.PartType.Cylinder, Size = Vector3.new(0.2, 5, 5), CFrame = at(x, FT + 2.05, z) * UP, Material = Enum.Material.Neon, Color = DIM, CastShadow = false, CanCollide = false }, model)
			local prompt = Instance.new("ProximityPrompt")
			prompt.Name = "SlotPrompt"
			prompt.ActionText = "Steal"
			prompt.ObjectText = ""
			prompt.HoldDuration = 1.25
			prompt.MaxActivationDistance = 9
			prompt.RequiresLineOfSight = false
			prompt.KeyboardKeyCode = Enum.KeyCode.E
			prompt.Enabled = false
			prompt:SetAttribute("Plot", index)
			prompt:SetAttribute("Slot", i)
			prompt.Parent = ped
			local lockGui = Instance.new("BillboardGui")
			lockGui.Size = UDim2.fromScale(3, 3)
			lockGui.StudsOffsetWorldSpace = Vector3.new(0, 3, 0)
			lockGui.MaxDistance = 60
			lockGui.Enabled = false
			lockGui.Parent = ped
			local lockText = Instance.new("TextLabel")
			lockText.BackgroundTransparency = 1
			lockText.Size = UDim2.fromScale(1, 1)
			lockText.Text = "🔒"
			lockText.TextScaled = true
			lockText.Parent = lockGui
			plot.Slots[i] = {
				Pedestal = ped,
				Ring = ring,
				Plate = plate,
				Prompt = prompt,
				LockGui = lockGui,
				Anchor = at(x, FT + 2, z),
			}
			prompt.Triggered:Connect(function(player)
				self.S.CoreService:OnSlotPrompt(player, plot, i)
			end)
		end
	end

	-- Collect-Pad
	local collect = P({ Name = "CollectPad", Shape = Enum.PartType.Cylinder, Size = Vector3.new(0.6, 9, 9), CFrame = at(-13, FT + 0.2, -22) * UP, Material = Enum.Material.Neon, Color = Color3.fromRGB(70, 255, 140), CastShadow = false }, model)
	P({ Name = "CollectRim", Shape = Enum.PartType.Cylinder, Size = Vector3.new(0.5, 10.5, 10.5), CFrame = at(-13, FT + 0.1, -22) * UP, Material = Enum.Material.Metal, Color = Color3.fromRGB(40, 44, 56) }, model)
	plot.CollectText = billboard(collect, Vector3.new(0, 5, 0), UDim2.new(10, 0, 3, 0), "💰 $0", Color3.fromRGB(120, 255, 150))
	plot.CollectPad = collect
	local debounce = {}
	collect.Touched:Connect(function(hit)
		local char = hit.Parent
		local player = char and Players:GetPlayerFromCharacter(char)
		if player and player == plot.Owner and not debounce[player] then
			debounce[player] = true
			self.S.EconomyService:Collect(player)
			task.delay(0.6, function()
				debounce[player] = nil
			end)
		end
	end)

	-- Lock-Pad
	local lockPad = P({ Name = "LockPad", Shape = Enum.PartType.Cylinder, Size = Vector3.new(0.6, 7.5, 7.5), CFrame = at(13, FT + 0.2, -22) * UP, Material = Enum.Material.Neon, Color = Color3.fromRGB(255, 70, 90), CastShadow = false }, model)
	P({ Name = "LockRim", Shape = Enum.PartType.Cylinder, Size = Vector3.new(0.5, 9, 9), CFrame = at(13, FT + 0.1, -22) * UP, Material = Enum.Material.Metal, Color = Color3.fromRGB(40, 44, 56) }, model)
	plot.LockText = billboard(lockPad, Vector3.new(0, 5, 0), UDim2.new(10, 0, 2.6, 0), "🔒 LOCK BASE", Color3.fromRGB(255, 120, 130))
	lockPad.Touched:Connect(function(hit)
		local char = hit.Parent
		local player = char and Players:GetPlayerFromCharacter(char)
		if player and player == plot.Owner and not self:IsLocked(plot) then
			self:Lock(plot)
		end
	end)

	-- Schutzkuppel
	local dome = P({ Name = "Dome", Size = Vector3.new(S + 8, 46, S + 8), CFrame = at(0, FT, 0), Material = Enum.Material.ForceField, Color = color, Transparency = 1, CanCollide = false, CanQuery = false, CanTouch = false, CastShadow = false }, model)
	local mesh = Instance.new("SpecialMesh")
	mesh.MeshType = Enum.MeshType.Sphere
	mesh.Parent = dome
	plot.Dome = dome

	local coresFolder = Instance.new("Folder")
	coresFolder.Name = "Cores"
	coresFolder.Parent = model
	plot.CoresFolder = coresFolder

	model.Parent = self.S.WorldBuilder.Map:WaitForChild("Plots")
	return plot
end

---------------------------------------------------------------------------
-- Abfragen
---------------------------------------------------------------------------
function PlotService:GetPlot(player)
	return self.ByPlayer[player]
end

function PlotService:SpawnCFrame(plot)
	return plot.CFrame * CFrame.new(0, FT + 3.5, -24) * CFrame.Angles(0, math.pi, 0)
end

function PlotService:IsInside(plot, pos, margin)
	local lp = plot.CFrame:PointToObjectSpace(pos)
	local m = HALF + (margin or 0)
	return math.abs(lp.X) < m and math.abs(lp.Z) < m and lp.Y < 40 and lp.Y > -10
end

function PlotService:IsLocked(plot)
	return os.clock() < plot.LockedUntil
end

function PlotService:UnlockedSlots(player)
	local data = self.S.DataService:Get(player)
	if not data then
		return Config.BaseSlots
	end
	return math.min(Config.MaxSlots, Config.BaseSlots + (data.Upgrades.Slots or 0))
end

function PlotService:FreeSlot(player)
	local data = self.S.DataService:Get(player)
	if not data then
		return nil
	end
	for i = 1, self:UnlockedSlots(player) do
		if not data.Cores[tostring(i)] then
			return i
		end
	end
	return nil
end

---------------------------------------------------------------------------
-- Visuals
---------------------------------------------------------------------------
function PlotService:RefreshSlot(plot, i)
	local slot = plot.Slots[i]
	local owner = plot.Owner
	local data = owner and self.S.DataService:Get(owner)
	local core = data and data.Cores[tostring(i)]
	local unlocked = owner and i <= self:UnlockedSlots(owner)

	if slot.Model then
		slot.Model:Destroy()
		slot.Model = nil
	end

	slot.Pedestal.Transparency = (owner and not unlocked) and 0.65 or 0
	slot.LockGui.Enabled = owner ~= nil and not unlocked

	if core and Config.CoreById[core.T] then
		local def = Config.CoreById[core.T]
		local rarity = Config.RarityById[def.Rarity]
		local model = CoreBuilder.Build(core.T, core.M)
		local size = CoreBuilder.Size(core.T)
		local pos = slot.Anchor.Position + Vector3.new(0, size * 0.75 + 1, 0)
		model:PivotTo(CFrame.new(pos))
		model.PrimaryPart:SetAttribute("BasePos", pos)
		CollectionService:AddTag(model.PrimaryPart, "SpinCore")
		model.Parent = plot.CoresFolder
		slot.Model = model
		slot.Ring.Color = rarity.Color
		slot.Plate.Color = rarity.Color
		slot.Prompt.ObjectText = Config.CoreDisplayName(core.T, core.M)
		slot.Prompt.Enabled = not self:IsLocked(plot)
	else
		slot.Ring.Color = unlocked and plot.Color:Lerp(DIM, 0.55) or DIM
		slot.Plate.Color = DIM
		slot.Prompt.Enabled = false
		slot.Prompt.ObjectText = ""
	end
end

function PlotService:RefreshSlots(plot)
	for i = 1, Config.MaxSlots do
		self:RefreshSlot(plot, i)
	end
end

function PlotService:UpdateSign(plot, income)
	if plot.Owner then
		plot.SignIncome.Text = "💸 " .. Util.Money(income) .. "/s"
	end
end

function PlotService:SetPending(plot, amount)
	plot.Pending = amount
	plot.CollectText.Text = "💰 " .. Util.Money(amount)
end

function PlotService:Lock(plot)
	plot.LockedUntil = os.clock() + LOCK_TIME
	plot.Dome.Transparency = 0
	for _, slot in ipairs(plot.Slots) do
		slot.Prompt.Enabled = false
	end
	self.S.Remotes:Notify(plot.Owner, "🔒 Base locked for " .. LOCK_TIME .. " seconds!", "success")
end

function PlotService:Unlock(plot)
	plot.LockedUntil = 0
	plot.Dome.Transparency = 1
	for i, slot in ipairs(plot.Slots) do
		slot.Prompt.Enabled = slot.Model ~= nil
	end
	if plot.Owner then
		self.S.Remotes:Notify(plot.Owner, "🔓 Your base is unlocked again – watch out for thieves!", "warning")
	end
end

---------------------------------------------------------------------------
-- Besitz
---------------------------------------------------------------------------
function PlotService:Assign(player)
	if self.ByPlayer[player] then
		return self.ByPlayer[player]
	end
	for _, plot in ipairs(self.Plots) do
		if not plot.Owner then
			plot.Owner = player
			self.ByPlayer[player] = plot
			plot.Folder:SetAttribute("OwnerId", player.UserId)
			player:SetAttribute("PlotIndex", plot.Index)
			plot.SignName.Text = player.DisplayName .. "'s Base"
			plot.SignName.TextColor3 = plot.Color
			plot.SignAvatar.Image = "rbxthumb://type=AvatarHeadShot&id=" .. player.UserId .. "&w=150&h=150"
			for _, t in ipairs(plot.Trims) do
				t.Color = plot.Color
			end
			self:RefreshSlots(plot)
			return plot
		end
	end
	return nil
end

function PlotService:Release(player)
	local plot = self.ByPlayer[player]
	if not plot then
		return
	end
	self.ByPlayer[player] = nil
	plot.Owner = nil
	plot.Folder:SetAttribute("OwnerId", nil)
	plot.LockedUntil = 0
	plot.Dome.Transparency = 1
	plot.SignName.Text = "EMPTY BASE"
	plot.SignName.TextColor3 = Color3.fromRGB(200, 200, 210)
	plot.SignIncome.Text = "Join to claim!"
	plot.SignAvatar.Image = ""
	self:SetPending(plot, 0)
	for _, t in ipairs(plot.Trims) do
		t.Color = DIM
	end
	self:RefreshSlots(plot)
end

function PlotService:TeleportHome(player)
	local plot = self.ByPlayer[player]
	local char = player.Character
	if plot and char then
		char:PivotTo(self:SpawnCFrame(plot))
	end
end

---------------------------------------------------------------------------
-- Lifecycle
---------------------------------------------------------------------------
function PlotService:Init(services)
	self.S = services
	local plotsFolder = Instance.new("Folder")
	plotsFolder.Name = "Plots"
	plotsFolder.Parent = services.WorldBuilder.Map
	for i = 1, Config.MaxPlots do
		self.Plots[i] = self:BuildPlot(i)
	end
end

function PlotService:Start()
	local DataService = self.S.DataService

	DataService.Loaded.Event:Connect(function(player)
		local data = DataService:Get(player)
		if not data then
			return
		end
		local plot = self:Assign(player)
		if not plot then
			player:Kick("This server is full – please join another one!")
			return
		end
		plot.Pending = data.Pending or 0
		self:SetPending(plot, plot.Pending)
		if player.Character then
			self:TeleportHome(player)
		end
	end)

	local function onPlayer(player)
		player.CharacterAdded:Connect(function(char)
			char:WaitForChild("HumanoidRootPart", 10)
			task.wait(0.1)
			if self.ByPlayer[player] then
				self:TeleportHome(player)
			end
		end)
	end
	Players.PlayerAdded:Connect(onPlayer)
	for _, p in ipairs(Players:GetPlayers()) do
		onPlayer(p)
	end

	Players.PlayerRemoving:Connect(function(player)
		local plot = self.ByPlayer[player]
		local data = DataService:Get(player)
		if plot and data then
			data.Pending = plot.Pending
		end
		self:Release(player)
	end)

	-- Lock-Timer & Eindringlinge rausschubsen
	while true do
		task.wait(0.25)
		for _, plot in ipairs(self.Plots) do
			if plot.Owner and plot.LockedUntil > 0 then
				local left = plot.LockedUntil - os.clock()
				if left <= 0 then
					self:Unlock(plot)
					plot.LockText.Text = "🔒 LOCK BASE"
				else
					plot.LockText.Text = "🛡️ LOCKED " .. Util.FormatTime(left)
					for _, other in ipairs(Players:GetPlayers()) do
						if other ~= plot.Owner then
							local char = other.Character
							local root = char and char:FindFirstChild("HumanoidRootPart")
							if root and self:IsInside(plot, root.Position, 4) then
								local out = (root.Position - plot.CFrame.Position) * Vector3.new(1, 0, 1)
								if out.Magnitude < 0.1 then
									out = -plot.CFrame.LookVector
								end
								self.S.Remotes:Fire("Knockback", other, out.Unit * 85 + Vector3.new(0, 35, 0))
							end
						end
					end
				end
			end
		end
	end
end

return PlotService
