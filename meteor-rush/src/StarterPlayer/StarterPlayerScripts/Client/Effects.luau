-- Client-seitige Effekte: fallende Meteore, Einschläge, Kamera-Shake, rotierende Cores,
-- Monument-Animation, Asteroidengürtel, Ambient-Partikel, Event-Farbgrading, Knockback
local Players = game:GetService("Players")
local RunService = game:GetService("RunService")
local TweenService = game:GetService("TweenService")
local CollectionService = game:GetService("CollectionService")
local Lighting = game:GetService("Lighting")
local Debris = game:GetService("Debris")
local Workspace = game:GetService("Workspace")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local CoreBuilder = require(Shared:WaitForChild("CoreBuilder"))

local player = Players.LocalPlayer
local Effects = {}

local FIRE = "rbxasset://textures/particles/fire_main.dds"
local SMOKE = "rbxasset://textures/particles/smoke_main.dds"
local SPARKLE = "rbxasset://textures/particles/sparkles_main.dds"

local fxFolder = Instance.new("Folder")
fxFolder.Name = "ClientFX"
fxFolder.Parent = Workspace

local shake = 0

local function P(props)
	local p = Instance.new("Part")
	p.Anchored = true
	p.CanCollide = false
	p.CanQuery = false
	p.CanTouch = false
	p.CastShadow = false
	p.TopSurface = Enum.SurfaceType.Smooth
	p.BottomSurface = Enum.SurfaceType.Smooth
	for k, v in pairs(props) do
		p[k] = v
	end
	p.Parent = props.Parent or fxFolder
	return p
end

local function emitter(parent, props)
	local e = Instance.new("ParticleEmitter")
	for k, v in pairs(props) do
		e[k] = v
	end
	e.Parent = parent
	return e
end

local function playSound(id, parent, volume)
	if not id or id == 0 then
		return
	end
	local s = Instance.new("Sound")
	s.SoundId = "rbxassetid://" .. id
	s.Volume = volume or 0.6
	s.Parent = parent or Workspace
	s:Play()
	Debris:AddItem(s, 8)
end
Effects.PlaySound = playSound

---------------------------------------------------------------------------
-- Meteore
---------------------------------------------------------------------------
local function rarityOf(coreId)
	local def = Config.CoreById[coreId]
	return Config.RarityById[def.Rarity]
end

local function warningMarker(target, rarity, color)
	local d = 10 + rarity.Order * 2.5
	local ring = P({
		Name = "Warning",
		Shape = Enum.PartType.Cylinder,
		Size = Vector3.new(0.15, d, d),
		CFrame = CFrame.new(target + Vector3.new(0, 0.25, 0)) * CFrame.Angles(0, 0, math.pi / 2),
		Material = Enum.Material.Neon,
		Color = color,
		Transparency = 0.5,
	})
	local inner = P({
		Name = "WarningInner",
		Shape = Enum.PartType.Cylinder,
		Size = Vector3.new(0.17, d * 0.2, d * 0.2),
		CFrame = ring.CFrame,
		Material = Enum.Material.Neon,
		Color = Color3.new(1, 1, 1),
		Transparency = 0.3,
	})
	local column
	if rarity.Order >= 4 then
		column = P({
			Name = "Column",
			Shape = Enum.PartType.Cylinder,
			Size = Vector3.new(220, d * 0.35, d * 0.35),
			CFrame = CFrame.new(target + Vector3.new(0, 110, 0)) * CFrame.Angles(0, 0, math.pi / 2),
			Material = Enum.Material.Neon,
			Color = color,
			Transparency = 0.82,
		})
	end
	TweenService:Create(ring, TweenInfo.new(0.45, Enum.EasingStyle.Sine, Enum.EasingDirection.InOut, -1, true), { Transparency = 0.85 }):Play()
	return function()
		ring:Destroy()
		inner:Destroy()
		if column then
			column:Destroy()
		end
	end, inner, d
end

function Effects.Meteor(info)
	local def = Config.CoreById[info.Core]
	if not def then
		return
	end
	local rarity = rarityOf(info.Core)
	local mut = Config.MutationById[info.Mutation or "None"]
	local color = (mut and mut.Color) or rarity.Color
	local s = CoreBuilder.Size(info.Core) * 1.6

	local cleanup, inner, d = warningMarker(info.Target, rarity, color)

	local rock = P({ Name = "Meteor", Shape = Enum.PartType.Ball, Size = Vector3.new(s, s, s), Material = Enum.Material.CrackedLava, Color = Color3.fromRGB(55, 36, 30), CFrame = CFrame.new(info.Start) })
	local glow = P({ Name = "Glow", Shape = Enum.PartType.Ball, Size = Vector3.new(s, s, s) * 1.35, Material = Enum.Material.Neon, Color = color, Transparency = 0.55, CFrame = CFrame.new(info.Start) })
	local att = Instance.new("Attachment")
	att.Parent = rock
	emitter(att, {
		Texture = FIRE,
		Color = ColorSequence.new({ ColorSequenceKeypoint.new(0, Color3.new(1, 1, 1)), ColorSequenceKeypoint.new(0.3, color), ColorSequenceKeypoint.new(1, Color3.fromRGB(255, 70, 20)) }),
		LightEmission = 1,
		Size = NumberSequence.new({ NumberSequenceKeypoint.new(0, s * 1.3), NumberSequenceKeypoint.new(1, 0) }),
		Transparency = NumberSequence.new(0.1, 1),
		Lifetime = NumberRange.new(0.4, 0.8),
		Rate = 90,
		Speed = NumberRange.new(0, 2),
		SpreadAngle = Vector2.new(30, 30),
	})
	emitter(att, {
		Texture = SMOKE,
		Color = ColorSequence.new(Color3.fromRGB(70, 60, 60), Color3.fromRGB(30, 30, 35)),
		Size = NumberSequence.new(s * 0.9, s * 3.2),
		Transparency = NumberSequence.new(0.35, 1),
		Lifetime = NumberRange.new(1.6, 2.6),
		Rate = 28,
		Speed = NumberRange.new(0, 1),
		RotSpeed = NumberRange.new(-40, 40),
	})
	emitter(att, {
		Texture = SPARKLE,
		Color = ColorSequence.new(color),
		LightEmission = 1,
		Size = NumberSequence.new(0.6, 0),
		Lifetime = NumberRange.new(0.4, 0.9),
		Rate = 40,
		Speed = NumberRange.new(8, 20),
		SpreadAngle = Vector2.new(60, 60),
	})
	local a0 = Instance.new("Attachment")
	a0.Position = Vector3.new(s * 0.45, 0, 0)
	a0.Parent = rock
	local a1 = Instance.new("Attachment")
	a1.Position = Vector3.new(-s * 0.45, 0, 0)
	a1.Parent = rock
	local trail = Instance.new("Trail")
	trail.Attachment0 = a0
	trail.Attachment1 = a1
	trail.FaceCamera = true
	trail.LightEmission = 1
	trail.Lifetime = 0.7
	trail.Color = ColorSequence.new(color, Color3.fromRGB(255, 90, 30))
	trail.Transparency = NumberSequence.new(0, 1)
	trail.WidthScale = NumberSequence.new(1, 0)
	trail.Parent = rock
	local light = Instance.new("PointLight")
	light.Color = color
	light.Range = 30 + rarity.Order * 4
	light.Brightness = 4
	light.Parent = rock

	local startT = os.clock()
	local conn
	conn = RunService.RenderStepped:Connect(function()
		local alpha = math.clamp((os.clock() - startT) / info.Time, 0, 1)
		local eased = alpha ^ 1.6
		local pos = info.Start:Lerp(info.Target, eased)
		local t = os.clock() - startT
		local cf = CFrame.new(pos) * CFrame.Angles(t * 3, t * 2, t)
		rock.CFrame = cf
		glow.CFrame = cf
		if inner then
			local k = 0.2 + 0.8 * alpha
			inner.Size = Vector3.new(0.17, d * k, d * k)
		end
		if alpha >= 1 then
			conn:Disconnect()
			cleanup()
			glow:Destroy()
			rock.Transparency = 1
			light.Enabled = false
			for _, e in ipairs(att:GetChildren()) do
				e.Enabled = false
			end
			trail.Enabled = false
			Debris:AddItem(rock, 3)
		end
	end)
end

function Effects.Impact(position, coreId, mutation)
	local def = Config.CoreById[coreId]
	if not def then
		return
	end
	local rarity = rarityOf(coreId)
	local mut = Config.MutationById[mutation or "None"]
	local color = (mut and mut.Color) or rarity.Color

	local ex = Instance.new("Explosion")
	ex.Position = position
	ex.BlastRadius = 5 + rarity.Order
	ex.BlastPressure = 0
	ex.DestroyJointRadiusPercent = 0
	ex.ExplosionType = Enum.ExplosionType.NoCraters
	ex.Visible = rarity.Order >= 3
	ex.Parent = Workspace

	-- Schockwelle
	local ring = P({
		Shape = Enum.PartType.Cylinder,
		Size = Vector3.new(0.4, 2, 2),
		CFrame = CFrame.new(position + Vector3.new(0, 0.6, 0)) * CFrame.Angles(0, 0, math.pi / 2),
		Material = Enum.Material.Neon,
		Color = color,
		Transparency = 0.1,
	})
	local R = 26 + rarity.Order * 7
	TweenService:Create(ring, TweenInfo.new(0.7, Enum.EasingStyle.Quart, Enum.EasingDirection.Out), { Size = Vector3.new(0.4, R, R), Transparency = 1 }):Play()
	Debris:AddItem(ring, 0.8)

	-- Blitzlicht
	local flash = P({ Size = Vector3.new(1, 1, 1), CFrame = CFrame.new(position + Vector3.new(0, 3, 0)), Transparency = 1 })
	local light = Instance.new("PointLight")
	light.Color = color
	light.Range = 40 + rarity.Order * 6
	light.Brightness = 10
	light.Parent = flash
	TweenService:Create(light, TweenInfo.new(1.2, Enum.EasingStyle.Quad), { Brightness = 0 }):Play()
	local att = Instance.new("Attachment")
	att.Parent = flash
	local dust = emitter(att, {
		Texture = SMOKE,
		Color = ColorSequence.new(Color3.fromRGB(150, 130, 115), Color3.fromRGB(90, 85, 85)),
		Size = NumberSequence.new(4, 14),
		Transparency = NumberSequence.new(0.3, 1),
		Lifetime = NumberRange.new(1.5, 3),
		Speed = NumberRange.new(10, 26),
		SpreadAngle = Vector2.new(80, 80),
		Drag = 3,
		Rate = 0,
		RotSpeed = NumberRange.new(-60, 60),
	})
	dust:Emit(22 + rarity.Order * 4)
	local sparks = emitter(att, {
		Texture = SPARKLE,
		Color = ColorSequence.new(color),
		LightEmission = 1,
		Size = NumberSequence.new(1, 0),
		Lifetime = NumberRange.new(0.6, 1.4),
		Speed = NumberRange.new(30, 60),
		SpreadAngle = Vector2.new(70, 70),
		Acceleration = Vector3.new(0, -50, 0),
		Rate = 0,
	})
	sparks:Emit(30 + rarity.Order * 10)
	Debris:AddItem(flash, 4)

	-- Gesteinsbrocken
	for _ = 1, 4 + rarity.Order do
		local sz = math.random(6, 16) / 10
		local rock = Instance.new("Part")
		rock.Size = Vector3.new(sz, sz, sz)
		rock.Material = Enum.Material.CrackedLava
		rock.Color = Color3.fromRGB(60, 42, 36)
		rock.CanQuery = false
		rock.CanTouch = false
		rock.CFrame = CFrame.new(position + Vector3.new(0, 2, 0)) * CFrame.Angles(math.random() * 6, math.random() * 6, 0)
		rock.Parent = fxFolder
		rock.AssemblyLinearVelocity = Vector3.new(math.random(-30, 30), math.random(35, 60), math.random(-30, 30))
		rock.AssemblyAngularVelocity = Vector3.new(math.random(-10, 10), math.random(-10, 10), math.random(-10, 10))
		Debris:AddItem(rock, 3.5)
	end

	-- Kamera-Shake je nach Entfernung
	local cam = Workspace.CurrentCamera
	if cam then
		local dist = (cam.CFrame.Position - position).Magnitude
		local power = math.clamp(1 - dist / 180, 0, 1) * (0.5 + rarity.Order * 0.18)
		shake = math.max(shake, power)
	end
	if rarity.Order >= 5 then
		playSound(Config.Sounds.Legendary, nil, 0.8)
	end
end

---------------------------------------------------------------------------
-- Animierte Welt
---------------------------------------------------------------------------
local spinning = {}
local rainbow = {}

local function trackSpin(part)
	spinning[part] = { Phase = math.random() * 6.28 }
end

local function buildAsteroidBelt()
	local parts, data = {}, {}
	local rng = Random.new(42)
	for i = 1, 70 do
		local s = rng:NextNumber(5, 26)
		local p = P({
			Name = "Asteroid",
			Size = Vector3.new(s * rng:NextNumber(0.8, 1.4), s, s * rng:NextNumber(0.8, 1.3)),
			Material = (i % 3 == 0) and Enum.Material.Basalt or Enum.Material.Rock,
			Color = Color3.fromRGB(90, 86, 96):Lerp(Color3.fromRGB(140, 110, 100), rng:NextNumber()),
			CastShadow = false,
		})
		table.insert(parts, p)
		table.insert(data, {
			Angle = rng:NextNumber(0, math.pi * 2),
			Radius = rng:NextNumber(720, 860),
			Height = rng:NextNumber(300, 420),
			Rot = CFrame.Angles(rng:NextNumber(0, 6), rng:NextNumber(0, 6), 0),
			Spin = rng:NextNumber(-0.3, 0.3),
		})
	end
	return parts, data
end

local function buildAmbient()
	local box = P({ Name = "AmbientBox", Size = Vector3.new(120, 40, 120), Transparency = 1 })
	local motes = emitter(box, {
		Texture = SPARKLE,
		Color = ColorSequence.new(Color3.fromRGB(255, 240, 200)),
		LightEmission = 1,
		Size = NumberSequence.new({ NumberSequenceKeypoint.new(0, 0), NumberSequenceKeypoint.new(0.5, 0.35), NumberSequenceKeypoint.new(1, 0) }),
		Transparency = NumberSequence.new(0.3),
		Lifetime = NumberRange.new(4, 7),
		Rate = 14,
		Speed = NumberRange.new(0.3, 1.2),
		SpreadAngle = Vector2.new(180, 180),
		Shape = Enum.ParticleEmitterShape.Box,
		ShapeStyle = Enum.ParticleEmitterShapeStyle.Volume,
	})
	local fireflies = emitter(box, {
		Texture = SPARKLE,
		Color = ColorSequence.new(Color3.fromRGB(190, 255, 120)),
		LightEmission = 1,
		Size = NumberSequence.new({ NumberSequenceKeypoint.new(0, 0), NumberSequenceKeypoint.new(0.3, 0.7), NumberSequenceKeypoint.new(0.7, 0.5), NumberSequenceKeypoint.new(1, 0) }),
		Lifetime = NumberRange.new(3, 6),
		Rate = 0,
		Speed = NumberRange.new(0.5, 2),
		SpreadAngle = Vector2.new(180, 180),
		Shape = Enum.ParticleEmitterShape.Box,
		ShapeStyle = Enum.ParticleEmitterShapeStyle.Volume,
	})
	return box, motes, fireflies
end

---------------------------------------------------------------------------
-- Event-Grading
---------------------------------------------------------------------------
local grade = Instance.new("ColorCorrectionEffect")
grade.Name = "EventGrade"
grade.Parent = Lighting

function Effects.SetEvent(state)
	local target = Color3.new(1, 1, 1)
	local sat = 0
	if state then
		target = Color3.new(1, 1, 1):Lerp(state.Color, 0.22)
		sat = 0.12
	end
	TweenService:Create(grade, TweenInfo.new(2.5), { TintColor = target, Saturation = sat }):Play()
	local atmo = Lighting:FindFirstChildOfClass("Atmosphere")
	if atmo then
		local c = state and Color3.fromRGB(199, 214, 255):Lerp(state.Color, 0.45) or Color3.fromRGB(199, 214, 255)
		TweenService:Create(atmo, TweenInfo.new(3), { Color = c }):Play()
	end
end

---------------------------------------------------------------------------
-- Knockback
---------------------------------------------------------------------------
function Effects.Knockback(velocity, tumble)
	local char = player.Character
	local root = char and char:FindFirstChild("HumanoidRootPart")
	local hum = char and char:FindFirstChildOfClass("Humanoid")
	if not root or not hum then
		return
	end
	if tumble then
		hum.PlatformStand = true
		shake = math.max(shake, 0.8)
		task.delay(0.9, function()
			if hum.Parent then
				hum.PlatformStand = false
				hum:ChangeState(Enum.HumanoidStateType.GettingUp)
			end
		end)
	else
		hum:ChangeState(Enum.HumanoidStateType.Jumping)
	end
	root.AssemblyLinearVelocity = velocity
end

---------------------------------------------------------------------------
-- Init
---------------------------------------------------------------------------
function Effects.Init(remotes)
	remotes:WaitForChild("MeteorIncoming").OnClientEvent:Connect(Effects.Meteor)
	remotes:WaitForChild("MeteorImpact").OnClientEvent:Connect(Effects.Impact)
	remotes:WaitForChild("Knockback").OnClientEvent:Connect(Effects.Knockback)
	remotes:WaitForChild("Event").OnClientEvent:Connect(Effects.SetEvent)

	for _, p in ipairs(CollectionService:GetTagged("SpinCore")) do
		trackSpin(p)
	end
	CollectionService:GetInstanceAddedSignal("SpinCore"):Connect(trackSpin)
	CollectionService:GetInstanceRemovedSignal("SpinCore"):Connect(function(p)
		spinning[p] = nil
	end)
	for _, p in ipairs(CollectionService:GetTagged("RainbowPart")) do
		rainbow[p] = math.random()
	end
	CollectionService:GetInstanceAddedSignal("RainbowPart"):Connect(function(p)
		rainbow[p] = math.random()
	end)
	CollectionService:GetInstanceRemovedSignal("RainbowPart"):Connect(function(p)
		rainbow[p] = nil
	end)

	-- Monument & Ringe
	local monuments, rings = {}, {}
	local function addMonument(m)
		if m:IsA("Model") then
			monuments[m] = m:GetPivot()
		end
	end
	local function addRing(m)
		if m:IsA("Model") then
			rings[m] = m:GetPivot()
		end
	end
	for _, m in ipairs(CollectionService:GetTagged("MonumentSpin")) do
		addMonument(m)
	end
	for _, m in ipairs(CollectionService:GetTagged("OrbitRing")) do
		addRing(m)
	end
	CollectionService:GetInstanceAddedSignal("MonumentSpin"):Connect(addMonument)
	CollectionService:GetInstanceAddedSignal("OrbitRing"):Connect(addRing)

	local beltParts, beltData = buildAsteroidBelt()
	local box, _, fireflies = buildAmbient()
	local beltCFrames = table.create(#beltParts)

	RunService.RenderStepped:Connect(function()
		local t = os.clock()

		for part, info in pairs(spinning) do
			if part.Parent and part.Anchored then
				local base = part:GetAttribute("BasePos")
				if base then
					part.CFrame = CFrame.new(base + Vector3.new(0, math.sin(t * 2 + info.Phase) * 0.35, 0)) * CFrame.Angles(0, t * 1.1 + info.Phase, 0)
				end
			elseif not part.Parent then
				spinning[part] = nil
			end
		end

		for part, offset in pairs(rainbow) do
			if part.Parent then
				part.Color = Color3.fromHSV((t * 0.25 + offset) % 1, 0.75, 1)
			else
				rainbow[part] = nil
			end
		end

		for m, base in pairs(monuments) do
			if m.Parent then
				m:PivotTo(base * CFrame.new(0, math.sin(t * 0.8) * 1.5, 0) * CFrame.Angles(0, t * 0.25, math.sin(t * 0.4) * 0.08))
			end
		end
		for m, base in pairs(rings) do
			if m.Parent then
				m:PivotTo(base * CFrame.Angles(0, t * (m:GetAttribute("Speed") or 0.2), 0))
			end
		end

		for i, d in ipairs(beltData) do
			local a = d.Angle + t * 0.006
			beltCFrames[i] = CFrame.new(math.cos(a) * d.Radius, d.Height + math.sin(t * 0.2 + i) * 3, math.sin(a) * d.Radius) * d.Rot * CFrame.Angles(t * d.Spin, 0, 0)
		end
		Workspace:BulkMoveTo(beltParts, beltCFrames, Enum.BulkMoveMode.FireCFrameChanged)

		local cam = Workspace.CurrentCamera
		if cam then
			box.CFrame = CFrame.new(cam.CFrame.Position)
		end
		local clock = Lighting.ClockTime
		fireflies.Rate = (clock > 18.5 or clock < 6) and 18 or 0
	end)

	-- Kamera-Shake
	RunService:BindToRenderStep("MeteorShake", Enum.RenderPriority.Camera.Value + 1, function(dt)
		if shake > 0.01 then
			local cam = Workspace.CurrentCamera
			local t = os.clock() * 35
			cam.CFrame = cam.CFrame * CFrame.Angles(math.noise(t, 0.5, 0) * shake * 0.05, math.noise(0.5, t, 0) * shake * 0.05, math.noise(0, 0.5, t) * shake * 0.03)
			shake = shake * math.exp(-dt * 4.5)
		else
			shake = 0
		end
	end)
end

return Effects
