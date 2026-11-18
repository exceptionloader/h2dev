-- Generiert die komplette Welt prozedural:
--   * Future-Lighting, Atmosphäre, Bloom, SunRays, Color Grading, Wolken, Tag/Nacht
--   * Smooth-Terrain-Insel mit Hügeln, Bergen (Schneegipfel), Stränden, Kratern, Pfaden, Ozean
--   * Zentraler Plaza mit schwebendem Meteor-Monument, Core Exchange, Leaderboard, Shop
--   * Bäume, Kristallhaine, Palmen, Felsen, Laternen, schwebende Inseln
local Workspace = game:GetService("Workspace")
local Lighting = game:GetService("Lighting")
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local CollectionService = game:GetService("CollectionService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Util = require(Shared:WaitForChild("Util"))

local WorldBuilder = {}

local G = Config.GroundHeight
local R = Config.IslandRadius
local CELL = 4
local EXTENT = 576 -- halbe Kantenlänge des Terrain-Bereichs
local CHUNK = 32 -- Zellen pro Chunk
local YMIN = -32
local NCELLS = (EXTENT * 2) / CELL

local rng = Random.new(1337)
local smooth = Util.SmoothStep
local lerp = Util.Lerp

local plotCenters = {}
for i = 1, Config.MaxPlots do
	plotCenters[i] = Config.PlotCFrame(i).Position
end

local craters = {}
for i = 0, 3 do
	local a = math.rad(45 + i * 90)
	table.insert(craters, { X = math.cos(a) * 150, Z = math.sin(a) * 150, R = 30, Depth = 7 })
end
WorldBuilder.Craters = craters

---------------------------------------------------------------------------
-- Helfer
---------------------------------------------------------------------------
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
WorldBuilder.P = P

local function folder(name, parent)
	local f = Instance.new("Folder")
	f.Name = name
	f.Parent = parent
	return f
end

local function segDist(px, pz, ax, az, bx, bz)
	local vx, vz = bx - ax, bz - az
	local wx, wz = px - ax, pz - az
	local t = math.clamp((wx * vx + wz * vz) / (vx * vx + vz * vz), 0, 1)
	local dx, dz = px - (ax + vx * t), pz - (az + vz * t)
	return math.sqrt(dx * dx + dz * dz)
end

---------------------------------------------------------------------------
-- Höhenfunktion der Insel
---------------------------------------------------------------------------
function WorldBuilder.Sample(x, z)
	local d = math.sqrt(x * x + z * z)
	local ang = math.atan2(z, x)
	local coast = R + math.noise(math.cos(ang) * 1.6 + 3.13, math.sin(ang) * 1.6 + 7.71, 0.37) * 85 + math.noise(x / 70, z / 70, 11.3) * 22
	local t = d / coast

	local h = G + 1 + math.noise(x / 130, z / 130, 1.71) * 7 + math.noise(x / 45, z / 45, 8.13) * 2

	-- Berge im äußeren Ring
	local mask = math.clamp(math.noise(x / 170, z / 170, 20.37) * 2.4 + 0.1, 0, 1)
	local ring = smooth((t - 0.6) / 0.12) * (1 - smooth((t - 0.86) / 0.1))
	local ridged = math.clamp(1 - math.abs(math.noise(x / 60, z / 60, 5.53)) * 2.2, 0, 1)
	local mountain = (mask ^ 1.6) * ring * (65 + ridged * 45)

	-- Flache Zonen (Plaza, Basen, Wege)
	local wPlaza = 1 - smooth((d - 76) / 34)
	local wPlot, wPath = 0, 0
	for _, c in ipairs(plotCenters) do
		local dx, dz = x - c.X, z - c.Z
		local dd = math.sqrt(dx * dx + dz * dz)
		wPlot = math.max(wPlot, 1 - smooth((dd - 54) / 30))
		local pd = segDist(x, z, 0, 0, c.X, c.Z)
		wPath = math.max(wPath, 1 - smooth((pd - 7) / 11))
	end
	local flat = math.max(wPlaza, wPlot, wPath)

	h = h + mountain * (1 - flat)
	h = lerp(h, G + 0.5, flat)

	-- Krater
	local kind = nil
	for _, c in ipairs(craters) do
		local dx, dz = x - c.X, z - c.Z
		local cd = math.sqrt(dx * dx + dz * dz) / c.R
		if cd < 1.35 then
			if cd < 1 then
				h = h - (1 - cd * cd) * c.Depth
				if cd < 0.55 then
					kind = "crater"
				end
			end
			h = h + math.max(0, 1 - math.abs(cd - 1) / 0.35) * 3.5
		end
	end

	-- Küste
	if t > 0.84 then
		h = lerp(h, -1.2, smooth((t - 0.84) / 0.16))
	end
	if t > 1 then
		h = math.max(-26, -1.2 - (t - 1) * 130)
	end

	if kind == nil then
		if wPath > 0.62 and d > 74 and wPath >= wPlot * 0.9 then
			kind = "path"
		elseif wPlaza > 0.5 or wPlot > 0.6 then
			kind = "flat"
		end
	end
	return h, kind, mountain
end

---------------------------------------------------------------------------
-- Lighting
---------------------------------------------------------------------------
function WorldBuilder:SetupLighting()
	pcall(function()
		Lighting.Technology = Enum.Technology.Future
	end)
	for _, child in ipairs(Lighting:GetChildren()) do
		if child:IsA("Sky") or child:IsA("Atmosphere") or child:IsA("PostEffect") then
			child:Destroy()
		end
	end
	Lighting.Brightness = 2.6
	Lighting.ClockTime = 14.2
	Lighting.GeographicLatitude = 35
	Lighting.Ambient = Color3.fromRGB(38, 40, 58)
	Lighting.OutdoorAmbient = Color3.fromRGB(120, 126, 152)
	Lighting.EnvironmentDiffuseScale = 1
	Lighting.EnvironmentSpecularScale = 1
	Lighting.GlobalShadows = true
	Lighting.ShadowSoftness = 0.22
	Lighting.ExposureCompensation = 0.1

	local atmosphere = Instance.new("Atmosphere")
	atmosphere.Density = 0.3
	atmosphere.Offset = 0.2
	atmosphere.Color = Color3.fromRGB(199, 214, 255)
	atmosphere.Decay = Color3.fromRGB(106, 112, 170)
	atmosphere.Glare = 0.4
	atmosphere.Haze = 1.4
	atmosphere.Parent = Lighting

	local sky = Instance.new("Sky")
	sky.StarCount = 5000
	sky.CelestialBodiesShown = true
	sky.SunAngularSize = 13
	sky.MoonAngularSize = 9
	sky.Parent = Lighting

	local bloom = Instance.new("BloomEffect")
	bloom.Intensity = 0.8
	bloom.Size = 30
	bloom.Threshold = 1.15
	bloom.Parent = Lighting

	local rays = Instance.new("SunRaysEffect")
	rays.Intensity = 0.07
	rays.Spread = 0.8
	rays.Parent = Lighting

	local cc = Instance.new("ColorCorrectionEffect")
	cc.Name = "Grade"
	cc.Brightness = 0.02
	cc.Contrast = 0.12
	cc.Saturation = 0.18
	cc.TintColor = Color3.fromRGB(255, 250, 245)
	cc.Parent = Lighting

	local dof = Instance.new("DepthOfFieldEffect")
	dof.FarIntensity = 0.12
	dof.FocusDistance = 80
	dof.InFocusRadius = 140
	dof.NearIntensity = 0
	dof.Parent = Lighting

	local terrain = Workspace.Terrain
	terrain.WaterColor = Color3.fromRGB(22, 120, 140)
	terrain.WaterReflectance = 0.8
	terrain.WaterTransparency = 0.5
	terrain.WaterWaveSize = 0.18
	terrain.WaterWaveSpeed = 9
	pcall(function()
		terrain.Decoration = true
	end)
	local colors = {
		[Enum.Material.Grass] = Color3.fromRGB(86, 162, 64),
		[Enum.Material.LeafyGrass] = Color3.fromRGB(64, 138, 52),
		[Enum.Material.Sand] = Color3.fromRGB(226, 206, 150),
		[Enum.Material.Rock] = Color3.fromRGB(108, 108, 118),
		[Enum.Material.Ground] = Color3.fromRGB(100, 80, 60),
		[Enum.Material.Cobblestone] = Color3.fromRGB(140, 136, 140),
		[Enum.Material.Snow] = Color3.fromRGB(240, 246, 255),
		[Enum.Material.Basalt] = Color3.fromRGB(42, 38, 44),
		[Enum.Material.CrackedLava] = Color3.fromRGB(255, 110, 40),
		[Enum.Material.Mud] = Color3.fromRGB(70, 60, 50),
	}
	for mat, color in pairs(colors) do
		pcall(function()
			terrain:SetMaterialColor(mat, color)
		end)
	end

	local clouds = terrain:FindFirstChildOfClass("Clouds") or Instance.new("Clouds")
	clouds.Cover = 0.52
	clouds.Density = 0.6
	clouds.Color = Color3.fromRGB(255, 255, 255)
	clouds.Parent = terrain

	Workspace.GlobalWind = Vector3.new(6, 0, 3)
end

-- Tag-/Nacht-Zyklus: Tag ~11 Min, Nacht ~4 Min
function WorldBuilder:RunDayCycle()
	while true do
		local dt = task.wait(0.5)
		local t = Lighting.ClockTime
		local isDay = t >= 6 and t < 18.5
		local speed = isDay and (12.5 / 660) or (11.5 / 240) -- Spielstunden pro Sekunde
		Lighting.ClockTime = (t + speed * dt) % 24
	end
end

---------------------------------------------------------------------------
-- Terrain
---------------------------------------------------------------------------
function WorldBuilder:BuildTerrain()
	local terrain = Workspace.Terrain
	terrain:Clear()

	-- Höhenkarte
	local H, K, M = {}, {}, {}
	local maxH = 0
	for i = 1, NCELLS + 2 do
		H[i], K[i], M[i] = {}, {}, {}
		local x = -EXTENT + (i - 1.5) * CELL
		for j = 1, NCELLS + 2 do
			local z = -EXTENT + (j - 1.5) * CELL
			local h, kind, mountain = WorldBuilder.Sample(x, z)
			H[i][j], K[i][j], M[i][j] = h, kind, mountain
			if h > maxH then
				maxH = h
			end
		end
		if i % 24 == 0 then
			task.wait()
		end
	end
	self.H = H
	local YMAX = math.ceil((maxH + 8) / 4) * 4
	local NY = (YMAX - YMIN) / CELL

	local air, water = Enum.Material.Air, Enum.Material.Water

	local function surfaceMaterial(i, j, h, kind, mountain, x, z)
		local slope = math.max(math.abs(H[i + 1][j] - H[i - 1][j]), math.abs(H[i][j + 1] - H[i][j - 1])) / (2 * CELL)
		if kind == "crater" then
			return (math.noise(x / 9, z / 9, 2.2) > 0.05) and Enum.Material.CrackedLava or Enum.Material.Basalt
		elseif kind == "path" then
			return Enum.Material.Cobblestone
		elseif h < -2 then
			return (math.noise(x / 30, z / 30, 9.1) > 0.2) and Enum.Material.Mud or Enum.Material.Sand
		elseif h < 2.8 then
			return Enum.Material.Sand
		elseif h > G + 78 then
			return Enum.Material.Snow
		elseif slope > 0.95 then
			return Enum.Material.Rock
		elseif slope > 0.6 or mountain > 35 then
			return (math.noise(x / 20, z / 20, 4.4) > 0) and Enum.Material.Rock or Enum.Material.Ground
		elseif math.noise(x / 55, z / 55, 6.6) > 0.18 then
			return Enum.Material.LeafyGrass
		end
		return Enum.Material.Grass
	end

	for ci = 0, NCELLS / CHUNK - 1 do
		for cj = 0, NCELLS / CHUNK - 1 do
			local mats, occs = {}, {}
			for a = 1, CHUNK do
				local i = ci * CHUNK + a + 1
				mats[a], occs[a] = {}, {}
				for b = 1, NY do
					mats[a][b], occs[a][b] = {}, {}
				end
				for c = 1, CHUNK do
					local j = cj * CHUNK + c + 1
					local h, kind, mountain = H[i][j], K[i][j], M[i][j]
					local x = -EXTENT + (i - 1.5) * CELL
					local z = -EXTENT + (j - 1.5) * CELL
					local top = surfaceMaterial(i, j, h, kind, mountain, x, z)
					for b = 1, NY do
						local yb = YMIN + (b - 1) * CELL
						local occ = math.clamp((h - yb) / CELL, 0, 1)
						if occ > 0 then
							local depth = h - (yb + CELL)
							local mat
							if depth > 10 then
								mat = Enum.Material.Rock
							elseif depth > 3 then
								mat = (top == Enum.Material.Sand) and Enum.Material.Sand or Enum.Material.Ground
								if mountain > 30 then
									mat = Enum.Material.Rock
								end
							else
								mat = top
							end
							mats[a][b][c] = mat
							occs[a][b][c] = occ
						elseif yb + CELL <= 0 then
							mats[a][b][c] = water
							occs[a][b][c] = 1
						else
							mats[a][b][c] = air
							occs[a][b][c] = 0
						end
					end
				end
			end
			local x0 = -EXTENT + ci * CHUNK * CELL
			local z0 = -EXTENT + cj * CHUNK * CELL
			local region = Region3.new(Vector3.new(x0, YMIN, z0), Vector3.new(x0 + CHUNK * CELL, YMAX, z0 + CHUNK * CELL))
			terrain:WriteVoxels(region, CELL, mats, occs)
			task.wait()
		end
	end

	-- Offener Ozean bis zum Horizont
	local O = 2048
	local E = EXTENT
	local rects = {
		{ -O, O, E, O },
		{ -O, O, -O, -E },
		{ E, O, -E, E },
		{ -O, -E, -E, E },
	}
	for _, r in ipairs(rects) do
		local cx, cz = (r[1] + r[2]) / 2, (r[3] + r[4]) / 2
		local sx, sz = r[2] - r[1], r[4] - r[3]
		terrain:FillBlock(CFrame.new(cx, -14, cz), Vector3.new(sx, 28, sz), water)
		terrain:FillBlock(CFrame.new(cx, -30, cz), Vector3.new(sx, 4, sz), Enum.Material.Sand)
		task.wait()
	end
end

function WorldBuilder.HeightAt(x, z)
	local H = WorldBuilder.H
	if not H then
		return (WorldBuilder.Sample(x, z))
	end
	local fi = (x + EXTENT) / CELL + 1.5
	local fj = (z + EXTENT) / CELL + 1.5
	local i, j = math.floor(fi), math.floor(fj)
	if i < 1 or j < 1 or i >= NCELLS + 2 or j >= NCELLS + 2 then
		return -20
	end
	local tx, tz = fi - i, fj - j
	local a = lerp(H[i][j], H[i + 1][j], tx)
	local b = lerp(H[i][j + 1], H[i + 1][j + 1], tx)
	return lerp(a, b, tz)
end

---------------------------------------------------------------------------
-- Plaza im Zentrum
---------------------------------------------------------------------------
local function sign(parent, cf, size, title, subtitle, color)
	local board = P({
		Name = "Sign",
		Size = size,
		CFrame = cf,
		Material = Enum.Material.SmoothPlastic,
		Color = Color3.fromRGB(18, 20, 34),
	}, parent)
	local gui = Instance.new("SurfaceGui")
	gui.Face = Enum.NormalId.Front
	gui.PixelsPerStud = 40
	gui.SizingMode = Enum.SurfaceGuiSizingMode.PixelsPerStud
	gui.LightInfluence = 0
	gui.Brightness = 1.6
	gui.Parent = board
	local grad = Instance.new("Frame")
	grad.Size = UDim2.fromScale(1, 1)
	grad.BackgroundColor3 = Color3.new(1, 1, 1)
	grad.Parent = gui
	local g = Instance.new("UIGradient")
	g.Color = ColorSequence.new(Color3.fromRGB(28, 22, 60), Color3.fromRGB(12, 14, 26))
	g.Rotation = 90
	g.Parent = grad
	local stroke = Instance.new("UIStroke")
	stroke.Thickness = 10
	stroke.Color = color
	stroke.ApplyStrokeMode = Enum.ApplyStrokeMode.Border
	stroke.Parent = grad
	local t = Instance.new("TextLabel")
	t.BackgroundTransparency = 1
	t.Size = UDim2.new(0.92, 0, subtitle and 0.5 or 0.8, 0)
	t.Position = UDim2.new(0.04, 0, 0.06, 0)
	t.Font = Enum.Font.FredokaOne
	t.TextScaled = true
	t.Text = title
	t.TextColor3 = color
	t.Parent = grad
	if subtitle then
		local s = Instance.new("TextLabel")
		s.BackgroundTransparency = 1
		s.Size = UDim2.new(0.9, 0, 0.3, 0)
		s.Position = UDim2.new(0.05, 0, 0.6, 0)
		s.Font = Enum.Font.GothamBold
		s.TextScaled = true
		s.Text = subtitle
		s.TextColor3 = Color3.fromRGB(230, 230, 245)
		s.Parent = grad
	end
	return board, gui
end
WorldBuilder.Sign = sign

local function ringOfBeads(parent, center, radius, count, size, color, tilt, name)
	local model = Instance.new("Model")
	model.Name = name or "Ring"
	local anchor = P({
		Name = "Pivot",
		Size = Vector3.new(1, 1, 1),
		CFrame = center * tilt,
		Transparency = 1,
		CanCollide = false,
		CanQuery = false,
		CanTouch = false,
	}, model)
	model.PrimaryPart = anchor
	for i = 1, count do
		local a = i / count * math.pi * 2
		P({
			Name = "Bead",
			Size = Vector3.new(size, size * 0.6, size * 2.2),
			CFrame = anchor.CFrame * CFrame.Angles(0, -a, 0) * CFrame.new(radius, 0, 0),
			Material = Enum.Material.Neon,
			Color = color,
			CanCollide = false,
			CanQuery = false,
			CanTouch = false,
			CastShadow = false,
		}, model)
	end
	model.Parent = parent
	return model
end

function WorldBuilder:BuildPlaza(map)
	local plaza = folder("Plaza", map)
	local up = CFrame.Angles(0, 0, math.pi / 2)
	local accent = Color3.fromRGB(255, 140, 60)

	P({ Name = "Floor", Shape = Enum.PartType.Cylinder, Size = Vector3.new(2, 150, 150), CFrame = CFrame.new(0, G, 0) * up, Material = Enum.Material.Marble, Color = Color3.fromRGB(226, 226, 232) }, plaza)
	P({ Name = "Trim", Shape = Enum.PartType.Cylinder, Size = Vector3.new(1.6, 157, 157), CFrame = CFrame.new(0, G - 0.4, 0) * up, Material = Enum.Material.Slate, Color = Color3.fromRGB(46, 48, 62) }, plaza)
	P({ Name = "GlowRing", Shape = Enum.PartType.Cylinder, Size = Vector3.new(1.9, 152, 152), CFrame = CFrame.new(0, G - 0.03, 0) * up, Material = Enum.Material.Neon, Color = accent, CastShadow = false }, plaza)
	P({ Name = "Inner", Shape = Enum.PartType.Cylinder, Size = Vector3.new(1, 62, 62), CFrame = CFrame.new(0, G + 1.1, 0) * up, Material = Enum.Material.Slate, Color = Color3.fromRGB(34, 34, 46) }, plaza)
	P({ Name = "InnerGlow", Shape = Enum.PartType.Cylinder, Size = Vector3.new(1.05, 64, 64), CFrame = CFrame.new(0, G + 1.05, 0) * up, Material = Enum.Material.Neon, Color = Color3.fromRGB(120, 90, 255), CastShadow = false }, plaza)

	-- Leuchtlinien zu jeder Basis
	for i = 1, Config.MaxPlots do
		local dir = (plotCenters[i] * Vector3.new(1, 0, 1)).Unit
		local mid = dir * 53
		P({
			Name = "Line",
			Size = Vector3.new(1.2, 0.12, 42),
			CFrame = CFrame.lookAt(Vector3.new(mid.X, G + 1.03, mid.Z), Vector3.new(0, G + 1.03, 0)),
			Material = Enum.Material.Neon,
			Color = accent,
			CanCollide = false,
			CastShadow = false,
		}, plaza)
	end

	-- Monument: Podest + Kraterrand
	P({ Name = "Plinth1", Shape = Enum.PartType.Cylinder, Size = Vector3.new(3, 30, 30), CFrame = CFrame.new(0, G + 2.6, 0) * up, Material = Enum.Material.Basalt, Color = Color3.fromRGB(40, 38, 48) }, plaza)
	P({ Name = "Plinth2", Shape = Enum.PartType.Cylinder, Size = Vector3.new(3, 20, 20), CFrame = CFrame.new(0, G + 5, 0) * up, Material = Enum.Material.Slate, Color = Color3.fromRGB(58, 56, 70) }, plaza)
	P({ Name = "Lava", Shape = Enum.PartType.Cylinder, Size = Vector3.new(0.4, 16, 16), CFrame = CFrame.new(0, G + 6.55, 0) * up, Material = Enum.Material.Neon, Color = Color3.fromRGB(255, 90, 30), CastShadow = false }, plaza)
	for i = 1, 12 do
		local a = i / 12 * math.pi * 2
		local s = rng:NextNumber(3, 5.5)
		P({
			Name = "RimRock",
			Size = Vector3.new(s * 1.4, s, s),
			CFrame = CFrame.new(math.cos(a) * 13.5, G + 4.2 + s / 3, math.sin(a) * 13.5) * CFrame.Angles(rng:NextNumber(0, 6), rng:NextNumber(0, 6), rng:NextNumber(0, 6)),
			Material = Enum.Material.Rock,
			Color = Color3.fromRGB(70, 64, 70),
		}, plaza)
	end

	-- Schwebender Meteor (wird vom Client animiert)
	local monument = Instance.new("Model")
	monument.Name = "Monument"
	local mCF = CFrame.new(0, G + 26, 0)
	local rock = P({ Name = "Rock", Shape = Enum.PartType.Ball, Size = Vector3.new(14, 14, 14), CFrame = mCF, Material = Enum.Material.CrackedLava, Color = Color3.fromRGB(70, 46, 40), CanCollide = false }, monument)
	monument.PrimaryPart = rock
	for i = 1, 9 do
		local dir = CFrame.Angles(rng:NextNumber(0, 6.28), rng:NextNumber(0, 6.28), 0)
		P({
			Name = "Crystal",
			Size = Vector3.new(1.6, rng:NextNumber(6, 9), 1.6),
			CFrame = mCF * dir * CFrame.new(0, 6.5, 0),
			Material = Enum.Material.Neon,
			Color = (i % 2 == 0) and Color3.fromRGB(255, 120, 40) or Color3.fromRGB(150, 110, 255),
			CanCollide = false,
			CastShadow = false,
		}, monument)
	end
	local light = Instance.new("PointLight")
	light.Color = Color3.fromRGB(255, 140, 70)
	light.Range = 45
	light.Brightness = 3
	light.Shadows = true
	light.Parent = rock
	local att = Instance.new("Attachment")
	att.Parent = rock
	local fire = Instance.new("ParticleEmitter")
	fire.Texture = "rbxasset://textures/particles/fire_main.dds"
	fire.Color = ColorSequence.new(Color3.fromRGB(255, 170, 60), Color3.fromRGB(255, 50, 20))
	fire.LightEmission = 1
	fire.Size = NumberSequence.new({ NumberSequenceKeypoint.new(0, 5), NumberSequenceKeypoint.new(1, 0) })
	fire.Transparency = NumberSequence.new(0.3, 1)
	fire.Lifetime = NumberRange.new(1, 1.6)
	fire.Rate = 30
	fire.Speed = NumberRange.new(2, 5)
	fire.SpreadAngle = Vector2.new(180, 180)
	fire.Acceleration = Vector3.new(0, 6, 0)
	fire.Parent = att
	monument.Parent = plaza
	CollectionService:AddTag(monument, "MonumentSpin")

	local r1 = ringOfBeads(plaza, mCF, 15, 28, 0.9, Color3.fromRGB(255, 150, 60), CFrame.Angles(math.rad(20), 0, 0), "RingA")
	local r2 = ringOfBeads(plaza, mCF, 19, 34, 0.8, Color3.fromRGB(140, 100, 255), CFrame.Angles(math.rad(-30), 0, math.rad(25)), "RingB")
	local r3 = ringOfBeads(plaza, mCF, 23, 40, 0.7, Color3.fromRGB(80, 230, 255), CFrame.Angles(0, 0, math.rad(-12)), "RingC")
	CollectionService:AddTag(r1, "OrbitRing")
	CollectionService:AddTag(r2, "OrbitRing")
	CollectionService:AddTag(r3, "OrbitRing")
	r1:SetAttribute("Speed", 0.35)
	r2:SetAttribute("Speed", -0.25)
	r3:SetAttribute("Speed", 0.18)

	-- Spawn
	local spawn = Instance.new("SpawnLocation")
	spawn.Name = "PlazaSpawn"
	spawn.Anchored = true
	spawn.Size = Vector3.new(10, 1, 10)
	spawn.CFrame = CFrame.new(0, G + 1.2, 42)
	spawn.Transparency = 1
	spawn.CanCollide = false
	spawn.Neutral = true
	spawn.Duration = 0
	for _, c in ipairs(spawn:GetChildren()) do
		c:Destroy()
	end
	spawn.Parent = plaza

	-- Gebäude zwischen den Wegen (0°, 90°, 180°, 270°)
	local function standCF(angleDeg, radius)
		local a = math.rad(angleDeg)
		local pos = Vector3.new(math.cos(a) * radius, G + 1, math.sin(a) * radius)
		return CFrame.lookAt(pos, Vector3.new(0, G + 1, 0))
	end

	-- CORE EXCHANGE (verkaufen)
	do
		local cf = standCF(0, 60)
		local f = folder("Exchange", plaza)
		P({ Name = "Base", Size = Vector3.new(24, 1, 16), CFrame = cf * CFrame.new(0, 0.5, 0), Material = Enum.Material.Slate, Color = Color3.fromRGB(40, 42, 56) }, f)
		for _, x in ipairs({ -11, 11 }) do
			for _, z in ipairs({ -7, 7 }) do
				P({ Name = "Pillar", Size = Vector3.new(1.4, 14, 1.4), CFrame = cf * CFrame.new(x, 8, z), Material = Enum.Material.Metal, Color = Color3.fromRGB(60, 62, 76) }, f)
			end
		end
		P({ Name = "Roof", Size = Vector3.new(26, 1.2, 18), CFrame = cf * CFrame.new(0, 15.4, 0), Material = Enum.Material.Slate, Color = Color3.fromRGB(32, 32, 44) }, f)
		P({ Name = "RoofGlow", Size = Vector3.new(26.4, 0.4, 18.4), CFrame = cf * CFrame.new(0, 14.7, 0), Material = Enum.Material.Neon, Color = Color3.fromRGB(80, 255, 160), CastShadow = false }, f)
		local counter = P({ Name = "Counter", Size = Vector3.new(14, 3.6, 2.6), CFrame = cf * CFrame.new(0, 2.8, -3), Material = Enum.Material.WoodPlanks, Color = Color3.fromRGB(120, 84, 56) }, f)
		P({ Name = "CounterTop", Size = Vector3.new(14.6, 0.4, 3.2), CFrame = cf * CFrame.new(0, 4.8, -3), Material = Enum.Material.Marble, Color = Color3.fromRGB(235, 235, 240) }, f)
		P({ Name = "BackWall", Size = Vector3.new(22, 13, 0.8), CFrame = cf * CFrame.new(0, 7.5, 7.4), Material = Enum.Material.Glass, Color = Color3.fromRGB(70, 200, 160), Transparency = 0.45 }, f)
		sign(f, cf * CFrame.new(0, 18.5, 0), Vector3.new(20, 5.5, 0.6), "☄️ CORE EXCHANGE", "Bring a core here & sell it for " .. Config.SellMultiplier .. "x its income", Color3.fromRGB(80, 255, 160))

		local prompt = Instance.new("ProximityPrompt")
		prompt.Name = "SellPrompt"
		prompt.ActionText = "Sell Core"
		prompt.ObjectText = "Core Exchange"
		prompt.HoldDuration = 0.4
		prompt.MaxActivationDistance = 14
		prompt.RequiresLineOfSight = false
		prompt.KeyboardKeyCode = Enum.KeyCode.E
		prompt.Parent = counter
		self.SellPrompt = prompt

		-- Händler-NPC
		task.spawn(function()
			local ok, npc = pcall(function()
				local desc = Instance.new("HumanoidDescription")
				desc.HeadColor = Color3.fromRGB(255, 205, 160)
				desc.TorsoColor = Color3.fromRGB(40, 160, 120)
				desc.LeftArmColor = Color3.fromRGB(255, 205, 160)
				desc.RightArmColor = Color3.fromRGB(255, 205, 160)
				desc.LeftLegColor = Color3.fromRGB(30, 30, 40)
				desc.RightLegColor = Color3.fromRGB(30, 30, 40)
				return Players:CreateHumanoidModelFromDescription(desc, Enum.HumanoidRigType.R15)
			end)
			if ok and npc then
				npc.Name = "Trader"
				local hum = npc:FindFirstChildOfClass("Humanoid")
				if hum then
					hum.DisplayName = "Nova the Trader"
					hum.DisplayDistanceType = Enum.HumanoidDisplayDistanceType.Viewer
				end
				for _, d in ipairs(npc:GetDescendants()) do
					if d:IsA("BasePart") then
						d.Anchored = true
					end
				end
				npc:PivotTo(cf * CFrame.new(0, 4.2, 1))
				npc.Parent = f
			end
		end)
	end

	-- LEADERBOARD
	do
		local cf = standCF(180, 62)
		local f = folder("LeaderboardStand", plaza)
		local board, gui = sign(f, cf * CFrame.new(0, 14, 0), Vector3.new(18, 22, 0.8), "", nil, Color3.fromRGB(255, 200, 70))
		board.Name = "Leaderboard"
		P({ Name = "Leg", Size = Vector3.new(1.5, 4, 1.5), CFrame = cf * CFrame.new(-6, 1, 0), Material = Enum.Material.Metal, Color = Color3.fromRGB(60, 62, 76) }, f)
		P({ Name = "Leg", Size = Vector3.new(1.5, 4, 1.5), CFrame = cf * CFrame.new(6, 1, 0), Material = Enum.Material.Metal, Color = Color3.fromRGB(60, 62, 76) }, f)
		local frame = gui:FindFirstChildOfClass("Frame")
		local title = frame:FindFirstChildOfClass("TextLabel")
		title.Size = UDim2.new(0.92, 0, 0.11, 0)
		title.Text = "🏆 TOP EARNERS"
		local list = Instance.new("Frame")
		list.Name = "List"
		list.BackgroundTransparency = 1
		list.Size = UDim2.new(0.9, 0, 0.8, 0)
		list.Position = UDim2.new(0.05, 0, 0.18, 0)
		list.Parent = frame
		local layout = Instance.new("UIListLayout")
		layout.Padding = UDim.new(0.012, 0)
		layout.SortOrder = Enum.SortOrder.LayoutOrder
		layout.Parent = list
		self.LeaderboardList = list
	end

	-- HOW TO PLAY
	do
		local cf = standCF(90, 62)
		local f = folder("InfoStand", plaza)
		local _, gui = sign(f, cf * CFrame.new(0, 10, 0), Vector3.new(20, 16, 0.8), "", nil, Color3.fromRGB(120, 180, 255))
		P({ Name = "Leg", Size = Vector3.new(1.5, 3, 1.5), CFrame = cf * CFrame.new(0, 1, 0), Material = Enum.Material.Metal, Color = Color3.fromRGB(60, 62, 76) }, f)
		local frame = gui:FindFirstChildOfClass("Frame")
		local title = frame:FindFirstChildOfClass("TextLabel")
		title.Size = UDim2.new(0.92, 0, 0.16, 0)
		title.Text = "HOW TO PLAY"
		local body = Instance.new("TextLabel")
		body.BackgroundTransparency = 1
		body.Size = UDim2.new(0.88, 0, 0.74, 0)
		body.Position = UDim2.new(0.06, 0, 0.22, 0)
		body.Font = Enum.Font.GothamBold
		body.TextScaled = true
		body.TextXAlignment = Enum.TextXAlignment.Left
		body.TextColor3 = Color3.fromRGB(235, 235, 250)
		body.Text = "☄️  Meteors crash all over the island\n🖐️  Grab the glowing core [E]\n🏠  Carry it into YOUR base\n💰  Cores earn cash every second\n🦹  Steal cores from other bases!\n🔒  Lock your base to protect it\n🏏  Bonk thieves to make them drop it\n🌟  Rare meteors = insane income"
		body.Parent = frame
	end

	-- SHOP-KIOSK
	do
		local cf = standCF(270, 60)
		local f = folder("ShopStand", plaza)
		P({ Name = "Base", Size = Vector3.new(16, 1, 12), CFrame = cf * CFrame.new(0, 0.5, 0), Material = Enum.Material.Slate, Color = Color3.fromRGB(40, 42, 56) }, f)
		local kiosk = P({ Name = "Kiosk", Size = Vector3.new(10, 4, 4), CFrame = cf * CFrame.new(0, 3, 0), Material = Enum.Material.SmoothPlastic, Color = Color3.fromRGB(255, 190, 60) }, f)
		P({ Name = "Glow", Size = Vector3.new(10.4, 0.4, 4.4), CFrame = cf * CFrame.new(0, 5.2, 0), Material = Enum.Material.Neon, Color = Color3.fromRGB(255, 120, 200), CastShadow = false }, f)
		sign(f, cf * CFrame.new(0, 10, 2), Vector3.new(14, 4.5, 0.5), "🛒 STAR SHOP", "Passes • Boosts • Meteor Showers", Color3.fromRGB(255, 190, 60))
		local prompt = Instance.new("ProximityPrompt")
		prompt.Name = "OpenShop"
		prompt.ActionText = "Open Shop"
		prompt.ObjectText = "Star Shop"
		prompt.HoldDuration = 0
		prompt.MaxActivationDistance = 14
		prompt.RequiresLineOfSight = false
		prompt.Parent = kiosk
	end

	return plaza
end

---------------------------------------------------------------------------
-- Dekoration
---------------------------------------------------------------------------
local LEAF_COLORS = {
	Color3.fromRGB(72, 150, 60),
	Color3.fromRGB(96, 170, 64),
	Color3.fromRGB(58, 128, 72),
	Color3.fromRGB(130, 180, 70),
	Color3.fromRGB(230, 130, 90), -- Herbst-Akzent
}
local CRYSTAL_COLORS = {
	Color3.fromRGB(255, 90, 200),
	Color3.fromRGB(80, 220, 255),
	Color3.fromRGB(160, 110, 255),
}

local function buildTree(parent, pos, scale)
	local m = Instance.new("Model")
	m.Name = "Tree"
	local trunkH = rng:NextNumber(10, 16) * scale
	local lean = CFrame.Angles(rng:NextNumber(-0.06, 0.06), rng:NextNumber(0, 6.28), rng:NextNumber(-0.06, 0.06))
	local base = CFrame.new(pos) * lean
	P({
		Name = "Trunk",
		Shape = Enum.PartType.Cylinder,
		Size = Vector3.new(trunkH, 1.8 * scale, 1.8 * scale),
		CFrame = base * CFrame.new(0, trunkH / 2 - 1, 0) * CFrame.Angles(0, 0, math.pi / 2),
		Material = Enum.Material.Wood,
		Color = Color3.fromRGB(104, 72, 48),
	}, m)
	local top = (base * CFrame.new(0, trunkH - 1, 0)).Position
	local color = LEAF_COLORS[rng:NextInteger(1, #LEAF_COLORS - (rng:NextNumber() < 0.9 and 1 or 0))]
	for _ = 1, rng:NextInteger(3, 5) do
		local d = rng:NextNumber(6, 10) * scale
		local off = Vector3.new(rng:NextNumber(-3, 3), rng:NextNumber(-1, 3), rng:NextNumber(-3, 3)) * scale
		P({
			Name = "Leaves",
			Shape = Enum.PartType.Ball,
			Size = Vector3.new(d, d, d),
			CFrame = CFrame.new(top + off),
			Material = Enum.Material.Grass,
			Color = color:Lerp(Color3.fromRGB(200, 220, 80), rng:NextNumber(0, 0.15)),
		}, m)
	end
	m.Parent = parent
end

local function buildCrystalTree(parent, pos, scale)
	local m = Instance.new("Model")
	m.Name = "CrystalTree"
	local color = CRYSTAL_COLORS[rng:NextInteger(1, #CRYSTAL_COLORS)]
	local trunkH = rng:NextNumber(7, 11) * scale
	P({
		Name = "Trunk",
		Shape = Enum.PartType.Cylinder,
		Size = Vector3.new(trunkH, 1.5 * scale, 1.5 * scale),
		CFrame = CFrame.new(pos + Vector3.new(0, trunkH / 2 - 1, 0)) * CFrame.Angles(0, 0, math.pi / 2),
		Material = Enum.Material.Basalt,
		Color = Color3.fromRGB(40, 34, 50),
	}, m)
	local top = pos + Vector3.new(0, trunkH - 1, 0)
	local n = rng:NextInteger(5, 8)
	local core
	for i = 1, n do
		local a = i / n * math.pi * 2
		local len = rng:NextNumber(4, 8) * scale
		core = P({
			Name = "Crystal",
			Size = Vector3.new(1.4 * scale, len, 1.4 * scale),
			CFrame = CFrame.new(top) * CFrame.Angles(0, a, 0) * CFrame.Angles(math.rad(rng:NextNumber(20, 55)), 0, 0) * CFrame.new(0, len / 2, 0) * CFrame.Angles(0, math.rad(45), 0),
			Material = Enum.Material.Glass,
			Color = color,
			Transparency = 0.15,
			CastShadow = false,
		}, m)
	end
	local heart = P({ Name = "Heart", Shape = Enum.PartType.Ball, Size = Vector3.new(2.2, 2.2, 2.2) * scale, CFrame = CFrame.new(top), Material = Enum.Material.Neon, Color = color, CastShadow = false }, m)
	local light = Instance.new("PointLight")
	light.Color = color
	light.Range = 16
	light.Brightness = 1.6
	light.Parent = heart
	m.Parent = parent
	return core
end

local function buildPalm(parent, pos)
	local m = Instance.new("Model")
	m.Name = "Palm"
	local dir = rng:NextNumber(0, 6.28)
	local cf = CFrame.new(pos) * CFrame.Angles(0, dir, 0)
	local segs = 6
	for i = 1, segs do
		local seg = 2.6
		cf = cf * CFrame.Angles(math.rad(5), 0, 0)
		P({
			Name = "Trunk",
			Shape = Enum.PartType.Cylinder,
			Size = Vector3.new(seg + 0.3, 1.5 - i * 0.07, 1.5 - i * 0.07),
			CFrame = cf * CFrame.new(0, seg / 2, 0) * CFrame.Angles(0, 0, math.pi / 2),
			Material = Enum.Material.Wood,
			Color = Color3.fromRGB(150, 116, 76),
		}, m)
		cf = cf * CFrame.new(0, seg, 0)
	end
	local top = cf.Position
	for i = 1, 8 do
		local a = i / 8 * math.pi * 2
		P({
			Name = "Leaf",
			Size = Vector3.new(1.6, 0.25, 9),
			CFrame = CFrame.new(top) * CFrame.Angles(0, a, 0) * CFrame.Angles(math.rad(-25), 0, 0) * CFrame.new(0, 0, -4.2),
			Material = Enum.Material.Grass,
			Color = Color3.fromRGB(70, 160, 70),
			CastShadow = true,
		}, m)
	end
	P({ Name = "Coconuts", Shape = Enum.PartType.Ball, Size = Vector3.new(1.4, 1.4, 1.4), CFrame = CFrame.new(top - Vector3.new(0, 0.8, 0)), Material = Enum.Material.Wood, Color = Color3.fromRGB(110, 76, 40) }, m)
	m.Parent = parent
end

local function buildRock(parent, pos)
	local base = rng:NextNumber(3, 10)
	for _ = 1, rng:NextInteger(1, 3) do
		local s = base * rng:NextNumber(0.6, 1.1)
		P({
			Name = "Rock",
			Size = Vector3.new(s * rng:NextNumber(1, 1.6), s, s * rng:NextNumber(1, 1.4)),
			CFrame = CFrame.new(pos + Vector3.new(rng:NextNumber(-base, base) * 0.6, s * 0.2, rng:NextNumber(-base, base) * 0.6)) * CFrame.Angles(rng:NextNumber(0, 6.28), rng:NextNumber(0, 6.28), rng:NextNumber(0, 6.28)),
			Material = (rng:NextNumber() < 0.5) and Enum.Material.Rock or Enum.Material.Slate,
			Color = Color3.fromRGB(100, 100, 108):Lerp(Color3.fromRGB(140, 130, 120), rng:NextNumber()),
		}, parent)
	end
end

local function buildCrystalCluster(parent, pos, color)
	local m = Instance.new("Model")
	m.Name = "Crystals"
	local n = rng:NextInteger(4, 7)
	for i = 1, n do
		local len = rng:NextNumber(3, 9)
		local w = rng:NextNumber(1, 2.2)
		P({
			Name = "Crystal",
			Size = Vector3.new(w, len, w),
			CFrame = CFrame.new(pos) * CFrame.Angles(0, i / n * 6.28, 0) * CFrame.Angles(math.rad(rng:NextNumber(0, 40)), 0, 0) * CFrame.new(0, len / 2 - 1, 0) * CFrame.Angles(0, math.rad(45), 0),
			Material = (i == 1) and Enum.Material.Neon or Enum.Material.Glass,
			Color = color,
			Transparency = (i == 1) and 0 or 0.12,
			CastShadow = false,
		}, m)
	end
	local light = Instance.new("PointLight")
	light.Color = color
	light.Range = 14
	light.Brightness = 1.4
	light.Parent = m:FindFirstChild("Crystal")
	m.Parent = parent
end

local function buildLamp(parent, pos, facing)
	local m = Instance.new("Model")
	m.Name = "Lamp"
	local cf = CFrame.lookAt(pos, pos + facing)
	P({ Name = "Base", Size = Vector3.new(2, 1, 2), CFrame = cf * CFrame.new(0, 0.5, 0), Material = Enum.Material.Slate, Color = Color3.fromRGB(40, 40, 50) }, m)
	P({ Name = "Pole", Shape = Enum.PartType.Cylinder, Size = Vector3.new(12, 0.7, 0.7), CFrame = cf * CFrame.new(0, 6.5, 0) * CFrame.Angles(0, 0, math.pi / 2), Material = Enum.Material.Metal, Color = Color3.fromRGB(45, 46, 56) }, m)
	P({ Name = "Arm", Size = Vector3.new(0.5, 0.5, 3), CFrame = cf * CFrame.new(0, 12.3, -1.3), Material = Enum.Material.Metal, Color = Color3.fromRGB(45, 46, 56) }, m)
	local bulb = P({ Name = "Bulb", Shape = Enum.PartType.Ball, Size = Vector3.new(1.7, 1.7, 1.7), CFrame = cf * CFrame.new(0, 11.6, -2.6), Material = Enum.Material.Neon, Color = Color3.fromRGB(255, 214, 150), CastShadow = false }, m)
	local light = Instance.new("SpotLight")
	light.Face = Enum.NormalId.Bottom
	light.Angle = 110
	light.Range = 26
	light.Brightness = 2.2
	light.Color = Color3.fromRGB(255, 210, 150)
	light.Parent = bulb
	m.Parent = parent
end

local function nearAnything(x, z)
	local d = math.sqrt(x * x + z * z)
	if d < 92 then
		return true
	end
	for _, c in ipairs(plotCenters) do
		if (Vector2.new(x, z) - Vector2.new(c.X, c.Z)).Magnitude < 66 then
			return true
		end
		if segDist(x, z, 0, 0, c.X, c.Z) < 15 then
			return true
		end
	end
	return false
end
WorldBuilder.NearAnything = nearAnything

function WorldBuilder:Decorate(map)
	local deco = folder("Decoration", map)
	local trees = folder("Trees", deco)
	local rocks = folder("Rocks", deco)
	local crystals = folder("Crystals", deco)
	local lamps = folder("Lamps", deco)

	local function randomLand()
		local a = rng:NextNumber(0, math.pi * 2)
		local d = math.sqrt(rng:NextNumber()) * (R + 40)
		return math.cos(a) * d, math.sin(a) * d
	end

	-- Bäume, Palmen, Kristallbäume
	local placed = 0
	for attempt = 1, 900 do
		if placed >= 170 then
			break
		end
		local x, z = randomLand()
		if not nearAnything(x, z) then
			local h, kind, mountain = WorldBuilder.Sample(x, z)
			local hh = WorldBuilder.HeightAt(x, z)
			if kind == nil and mountain < 25 and hh > 0.5 and hh < G + 30 then
				local pos = Vector3.new(x, hh, z)
				if hh < 3 then
					buildPalm(trees, pos)
				elseif math.noise(x / 160, z / 160, 31.7) > 0.22 then
					buildCrystalTree(trees, pos, rng:NextNumber(0.9, 1.3))
				else
					buildTree(trees, pos, rng:NextNumber(0.8, 1.35))
				end
				placed = placed + 1
			end
		end
		if attempt % 40 == 0 then
			task.wait()
		end
	end

	-- Felsen (auch an Bergen)
	for _ = 1, 120 do
		local x, z = randomLand()
		if not nearAnything(x, z) then
			local hh = WorldBuilder.HeightAt(x, z)
			if hh > -4 then
				buildRock(rocks, Vector3.new(x, hh - 0.5, z))
			end
		end
	end

	-- Kristallgruppen: in Kratern + verstreut
	for _, c in ipairs(craters) do
		for k = 1, 6 do
			local a = k / 6 * math.pi * 2 + rng:NextNumber(-0.3, 0.3)
			local d = rng:NextNumber(4, c.R * 0.6)
			local x, z = c.X + math.cos(a) * d, c.Z + math.sin(a) * d
			buildCrystalCluster(crystals, Vector3.new(x, WorldBuilder.HeightAt(x, z), z), CRYSTAL_COLORS[(k % #CRYSTAL_COLORS) + 1])
		end
		-- Glühender Meteoritenrest in der Mitte
		local hh = WorldBuilder.HeightAt(c.X, c.Z)
		local core = P({ Name = "OldMeteor", Shape = Enum.PartType.Ball, Size = Vector3.new(9, 9, 9), CFrame = CFrame.new(c.X, hh + 1.5, c.Z), Material = Enum.Material.CrackedLava, Color = Color3.fromRGB(60, 40, 36) }, crystals)
		local light = Instance.new("PointLight")
		light.Color = Color3.fromRGB(255, 110, 40)
		light.Range = 30
		light.Brightness = 2
		light.Parent = core
		local att = Instance.new("Attachment")
		att.Parent = core
		local smoke = Instance.new("ParticleEmitter")
		smoke.Texture = "rbxasset://textures/particles/smoke_main.dds"
		smoke.Color = ColorSequence.new(Color3.fromRGB(90, 80, 80))
		smoke.Size = NumberSequence.new(4, 12)
		smoke.Transparency = NumberSequence.new(0.6, 1)
		smoke.Lifetime = NumberRange.new(4, 7)
		smoke.Rate = 4
		smoke.Speed = NumberRange.new(2, 4)
		smoke.Acceleration = Vector3.new(2, 1, 1)
		smoke.Parent = att
	end
	for _ = 1, 40 do
		local x, z = randomLand()
		if not nearAnything(x, z) then
			local hh = WorldBuilder.HeightAt(x, z)
			if hh > 2 then
				buildCrystalCluster(crystals, Vector3.new(x, hh, z), CRYSTAL_COLORS[rng:NextInteger(1, #CRYSTAL_COLORS)])
			end
		end
	end

	-- Laternen entlang der Wege
	for _, c in ipairs(plotCenters) do
		local dir = (c * Vector3.new(1, 0, 1)).Unit
		local side = Vector3.new(-dir.Z, 0, dir.X)
		local n = 0
		for d = 92, Config.PlotRadius - 62, 26 do
			n = n + 1
			local s = (n % 2 == 0) and 1 or -1
			local p = dir * d + side * 9.5 * s
			buildLamp(lamps, Vector3.new(p.X, WorldBuilder.HeightAt(p.X, p.Z) - 0.3, p.Z), -side * s)
		end
	end

	-- Schwebende Inseln
	local terrain = Workspace.Terrain
	for i = 1, 7 do
		local a = i / 7 * math.pi * 2 + 0.4
		local dist = rng:NextNumber(380, 620)
		local center = Vector3.new(math.cos(a) * dist, rng:NextNumber(140, 230), math.sin(a) * dist)
		local r = rng:NextNumber(22, 38)
		for k = 0, 4 do
			terrain:FillBall(center - Vector3.new(rng:NextNumber(-3, 3), k * r * 0.32, rng:NextNumber(-3, 3)), r * (1 - k * 0.19), Enum.Material.Rock)
		end
		local cutY = center.Y + r * 0.3
		terrain:FillBlock(CFrame.new(center.X, cutY + r, center.Z), Vector3.new(r * 3, r * 2, r * 3), Enum.Material.Air)
		terrain:FillCylinder(CFrame.new(center.X, cutY - 1.5, center.Z), 3, r * 0.9, Enum.Material.Grass)
		local topPos = Vector3.new(center.X, cutY, center.Z)
		if i % 2 == 0 then
			buildCrystalTree(deco, topPos, 1.8)
		else
			buildTree(deco, topPos, 1.7)
		end
		task.wait()
	end
end

---------------------------------------------------------------------------
-- Lifecycle
---------------------------------------------------------------------------
function WorldBuilder:Init(services)
	self.S = services
	local old = Workspace:FindFirstChild("Baseplate")
	if old then
		old:Destroy()
	end
	for _, s in ipairs(Workspace:GetChildren()) do
		if s:IsA("SpawnLocation") then
			s:Destroy()
		end
	end
	Workspace:SetAttribute("WorldReady", false)
	self.Map = folder("Map", Workspace)
	self:SetupLighting()
	self:BuildPlaza(self.Map)
	-- Sicherheitsboden unter allem, damit niemand während der Generierung fällt
	self.TempFloor = P({ Name = "TempFloor", Size = Vector3.new(2048, 2, 2048), CFrame = CFrame.new(0, G - 1.2, 0), Transparency = 1 }, Workspace)
end

function WorldBuilder:Start()
	local t0 = os.clock()
	self:BuildTerrain()
	self:Decorate(self.Map)
	if self.TempFloor then
		self.TempFloor:Destroy()
	end
	Workspace:SetAttribute("WorldReady", true)
	print(string.format("[WorldBuilder] Welt generiert in %.1fs", os.clock() - t0))
	self:RunDayCycle()
end

return WorldBuilder
