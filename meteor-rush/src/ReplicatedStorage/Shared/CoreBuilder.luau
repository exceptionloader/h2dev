-- Baut das 3D-Modell eines Meteor-Cores (Kristall-Cluster mit Glow, Partikeln & Info-Schild)
local CollectionService = game:GetService("CollectionService")

local Config = require(script.Parent:WaitForChild("Config"))
local Util = require(script.Parent:WaitForChild("Util"))

local CoreBuilder = {}

local SPARKLE = "rbxasset://textures/particles/sparkles_main.dds"
local SMOKE = "rbxasset://textures/particles/smoke_main.dds"

local function part(props)
	local p = Instance.new("Part")
	p.Anchored = false
	p.CanCollide = false
	p.CanTouch = false
	p.CanQuery = false
	p.CastShadow = false
	p.Massless = true
	p.TopSurface = Enum.SurfaceType.Smooth
	p.BottomSurface = Enum.SurfaceType.Smooth
	for k, v in pairs(props) do
		p[k] = v
	end
	return p
end

local function weld(a, b)
	local w = Instance.new("WeldConstraint")
	w.Part0 = a
	w.Part1 = b
	w.Parent = a
end

function CoreBuilder.Size(coreId)
	local core = Config.CoreById[coreId]
	local rarity = Config.RarityById[core.Rarity]
	return 1.9 + rarity.Order * 0.38
end

function CoreBuilder.Build(coreId, mutation)
	mutation = mutation or "None"
	local core = Config.CoreById[coreId]
	local rarity = Config.RarityById[core.Rarity]
	local mut = Config.MutationById[mutation] or Config.MutationById.None
	local s = CoreBuilder.Size(coreId)

	local coreColor = core.Color
	local shellColor = core.Shell
	local shellMaterial = Enum.Material.Glass
	if mutation == "Golden" then
		shellColor = Color3.fromRGB(255, 196, 50)
		shellMaterial = Enum.Material.Foil
	elseif mutation == "Void" then
		shellColor = Color3.fromRGB(10, 5, 20)
		coreColor = Color3.fromRGB(150, 50, 255)
		shellMaterial = Enum.Material.Glass
	end

	local model = Instance.new("Model")
	model.Name = "Core_" .. coreId
	model:SetAttribute("CoreId", coreId)
	model:SetAttribute("Mutation", mutation)

	local center = part({
		Name = "Core",
		Shape = Enum.PartType.Ball,
		Size = Vector3.new(s, s, s) * 0.72,
		Material = Enum.Material.Neon,
		Color = coreColor,
		Anchored = true,
	})
	center.Parent = model
	model.PrimaryPart = center

	-- Kristall-Splitter (obere und untere Reihe)
	local shards = 8
	for i = 1, shards do
		local upper = i <= shards / 2
		local a = (i / (shards / 2)) * math.pi * 2 + (upper and 0 or math.pi / 4)
		local tilt = upper and math.rad(48) or math.rad(132)
		local len = s * (0.85 + ((i * 37) % 10) / 30)
		local shard = part({
			Name = "Shard",
			Size = Vector3.new(s * 0.3, len, s * 0.3),
			Material = shellMaterial,
			Color = shellColor,
			Transparency = (mutation == "Void") and 0.05 or 0.18,
			Reflectance = (mutation == "Golden") and 0.25 or 0.05,
		})
		shard.CFrame = center.CFrame * CFrame.Angles(0, a, 0) * CFrame.Angles(tilt, 0, 0) * CFrame.new(0, s * 0.42, 0) * CFrame.Angles(0, math.rad(45), 0)
		shard.Parent = model
		weld(center, shard)
		if mutation == "Rainbow" then
			CollectionService:AddTag(shard, "RainbowPart")
		end
	end

	-- Orbit-Ring ab Epic
	if rarity.Order >= 4 then
		local beads = 14
		local tilt = CFrame.Angles(math.rad(70), 0, math.rad(15))
		for i = 1, beads do
			local a = i / beads * math.pi * 2
			local bead = part({
				Name = "Orbit",
				Size = Vector3.new(0.35, 0.35, 0.35) * (s / 3),
				Material = Enum.Material.Neon,
				Color = (mut.Color or rarity.Color),
			})
			bead.CFrame = center.CFrame * tilt * CFrame.new(math.cos(a) * s * 1.05, 0, math.sin(a) * s * 1.05)
			bead.Parent = model
			weld(center, bead)
		end
	end

	-- Licht
	local light = Instance.new("PointLight")
	light.Color = coreColor
	light.Range = 8 + rarity.Order * 2.2
	light.Brightness = 1.2 + rarity.Order * 0.45
	light.Shadows = false
	light.Parent = center

	-- Partikel
	local att = Instance.new("Attachment")
	att.Name = "FX"
	att.Parent = center

	local sparkles = Instance.new("ParticleEmitter")
	sparkles.Name = "Sparkles"
	sparkles.Texture = SPARKLE
	sparkles.LightEmission = 1
	sparkles.LightInfluence = 0
	sparkles.Color = ColorSequence.new(mut.Color or coreColor, rarity.Color)
	sparkles.Size = NumberSequence.new({ NumberSequenceKeypoint.new(0, 0.15 * s), NumberSequenceKeypoint.new(1, 0) })
	sparkles.Transparency = NumberSequence.new({ NumberSequenceKeypoint.new(0, 0.1), NumberSequenceKeypoint.new(1, 1) })
	sparkles.Lifetime = NumberRange.new(0.8, 1.8)
	sparkles.Speed = NumberRange.new(1, 3)
	sparkles.SpreadAngle = Vector2.new(180, 180)
	sparkles.Rate = 3 + rarity.Order * 3
	sparkles.Parent = att

	if mutation == "Void" then
		local smoke = Instance.new("ParticleEmitter")
		smoke.Texture = SMOKE
		smoke.Color = ColorSequence.new(Color3.fromRGB(40, 0, 80), Color3.fromRGB(0, 0, 0))
		smoke.Size = NumberSequence.new(s * 0.5, s * 1.4)
		smoke.Transparency = NumberSequence.new(0.5, 1)
		smoke.Lifetime = NumberRange.new(1, 2)
		smoke.Speed = NumberRange.new(0.5, 1.5)
		smoke.Rate = 10
		smoke.SpreadAngle = Vector2.new(180, 180)
		smoke.Parent = att
	elseif mutation == "Golden" then
		local glint = Instance.new("ParticleEmitter")
		glint.Texture = SPARKLE
		glint.LightEmission = 1
		glint.Color = ColorSequence.new(Color3.fromRGB(255, 230, 120))
		glint.Size = NumberSequence.new(0.4 * s, 0)
		glint.Lifetime = NumberRange.new(0.4, 0.8)
		glint.Rate = 8
		glint.Speed = NumberRange.new(0)
		glint.Parent = att
	end

	-- Info-Schild
	local gui = Instance.new("BillboardGui")
	gui.Name = "Info"
	gui.Size = UDim2.fromOffset(220, 70)
	gui.StudsOffsetWorldSpace = Vector3.new(0, s * 1.05 + 1.6, 0)
	gui.MaxDistance = 85
	gui.LightInfluence = 0
	gui.AlwaysOnTop = false
	gui.Parent = center

	local function label(name, text, y, h, color, font)
		local l = Instance.new("TextLabel")
		l.Name = name
		l.BackgroundTransparency = 1
		l.Size = UDim2.new(1, 0, h, 0)
		l.Position = UDim2.new(0, 0, y, 0)
		l.Font = font or Enum.Font.FredokaOne
		l.TextScaled = true
		l.Text = text
		l.TextColor3 = color
		local stroke = Instance.new("UIStroke")
		stroke.Thickness = 2
		stroke.Color = Color3.fromRGB(10, 10, 20)
		stroke.Parent = l
		l.Parent = gui
		return l
	end

	label("Name", Config.CoreDisplayName(coreId, mutation), 0, 0.42, mut.Color or rarity.Color)
	label("Rarity", string.upper(rarity.Id), 0.42, 0.26, rarity.Color, Enum.Font.GothamBlack)
	label("Income", Util.Money(Config.CoreIncome(coreId, mutation)) .. "/s", 0.68, 0.32, Color3.fromRGB(120, 255, 150))

	return model
end

return CoreBuilder
