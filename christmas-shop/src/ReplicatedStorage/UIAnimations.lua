--[[
	UIAnimations  (ModuleScript)
	Explorer:  ReplicatedStorage > UIAnimations

	Reusable, pooled UI animation helpers used by the Christmas Shop:
	  pop, ripple, afterimageTrail, burst, confetti, vortexClose, raysSpin, bob
	plus Maid (cleanup), bindButton (hover/press), squash, shake, emit (particles).

	Every looping helper returns a stop function. Put those into a Maid so they
	are cleaned up when the shop closes.
]]

local GuiService = game:GetService("GuiService")
local Players = game:GetService("Players")
local RunService = game:GetService("RunService")
local TweenService = game:GetService("TweenService")

local ShopConfig = require(script.Parent:WaitForChild("ShopConfig"))

local UIAnimations = {}

---------------------------------------------------------------------------
-- Maid: collects connections, tweens, instances, functions and cleans them up
---------------------------------------------------------------------------
local Maid = {}
Maid.__index = Maid

function Maid.new()
	return setmetatable({ _tasks = {} }, Maid)
end

function Maid:Give(item)
	table.insert(self._tasks, item)
	return item
end

function Maid:Clean()
	local tasks = self._tasks
	self._tasks = {}
	for i = #tasks, 1, -1 do
		local item = tasks[i]
		local kind = typeof(item)
		if kind == "RBXScriptConnection" then
			item:Disconnect()
		elseif kind == "function" then
			local ok, err = pcall(item)
			if not ok then
				warn("[UIAnimations] cleanup error:", err)
			end
		elseif kind == "thread" then
			pcall(task.cancel, item)
		elseif kind == "Instance" then
			if item:IsA("Tween") then
				item:Cancel()
			end
			item:Destroy()
		elseif kind == "table" then
			if type(item.Clean) == "function" then
				item:Clean()
			elseif type(item.Destroy) == "function" then
				item:Destroy()
			end
		end
	end
end
Maid.Destroy = Maid.Clean

UIAnimations.Maid = Maid

---------------------------------------------------------------------------
-- Reduced motion
---------------------------------------------------------------------------
function UIAnimations.isReducedMotion(): boolean
	local player = Players.LocalPlayer
	if player then
		local attr = player:GetAttribute("ReducedMotion")
		if attr ~= nil then
			return attr == true
		end
	end
	local ok, systemSetting = pcall(function()
		return (GuiService :: any).ReducedMotionEnabled
	end)
	if ok and systemSetting == true then
		return true
	end
	return ShopConfig.ReducedMotion == true
end

---------------------------------------------------------------------------
-- Basic helpers
---------------------------------------------------------------------------
function UIAnimations.tween(
	instance: Instance,
	duration: number,
	props: { [string]: any },
	style: Enum.EasingStyle?,
	direction: Enum.EasingDirection?,
	delayTime: number?
): Tween
	local info = TweenInfo.new(
		duration,
		style or Enum.EasingStyle.Quad,
		direction or Enum.EasingDirection.Out,
		0,
		false,
		delayTime or 0
	)
	local tw = TweenService:Create(instance, info, props)
	tw:Play()
	return tw
end
local tween = UIAnimations.tween

function UIAnimations.getScale(obj: Instance, name: string?): UIScale
	local scaleName = name or "AnimScale"
	local existing = obj:FindFirstChild(scaleName)
	if existing and existing:IsA("UIScale") then
		return existing
	end
	local scale = Instance.new("UIScale")
	scale.Name = scaleName
	scale.Parent = obj
	return scale
end

-- Center of `obj` in the coordinate space of `container` (offset pixels).
function UIAnimations.centerIn(obj: GuiObject, container: GuiObject): Vector2
	return obj.AbsolutePosition + obj.AbsoluteSize / 2 - container.AbsolutePosition
end

-- Sets the "visible" transparency of a label/image/frame and its strokes.
function UIAnimations.setVisualTransparency(obj: Instance, t: number)
	if obj:GetAttribute("FadeChildren") then
		-- container made of shapes (procedural cloud): fade the shapes, keep the container invisible
		for _, child in obj:GetChildren() do
			if child:IsA("GuiObject") then
				child.BackgroundTransparency = t
			end
		end
		return
	end
	if obj:IsA("ImageLabel") or obj:IsA("ImageButton") then
		obj.ImageTransparency = t
	elseif obj:IsA("TextLabel") or obj:IsA("TextButton") then
		obj.TextTransparency = t
	elseif obj:IsA("GuiObject") then
		obj.BackgroundTransparency = t
	end
	for _, child in obj:GetChildren() do
		if child:IsA("UIStroke") then
			child.Transparency = t
		end
	end
end
local setVisualTransparency = UIAnimations.setVisualTransparency

---------------------------------------------------------------------------
-- Shared per-frame loop (one RenderStepped connection for all loops/particles)
---------------------------------------------------------------------------
local loops: { [number]: (number) -> () } = {}
local loopCounter = 0
local loopConnection: RBXScriptConnection? = nil
local particles = {}

local function stepParticles(dt: number) end -- forward declared, replaced below

local function ensureLoop()
	if loopConnection then
		return
	end
	loopConnection = RunService.RenderStepped:Connect(function(dt)
		for _, fn in loops do
			local ok, err = pcall(fn, dt)
			if not ok then
				warn("[UIAnimations] loop error:", err)
			end
		end
		stepParticles(dt)
		if next(loops) == nil and #particles == 0 and loopConnection then
			loopConnection:Disconnect()
			loopConnection = nil
		end
	end)
end

-- Runs fn(dt) every frame until the returned stop function is called.
function UIAnimations.addLoop(fn: (number) -> ()): () -> ()
	loopCounter += 1
	local id = loopCounter
	loops[id] = fn
	ensureLoop()
	return function()
		loops[id] = nil
	end
end

---------------------------------------------------------------------------
-- Object pool + particles
---------------------------------------------------------------------------
local pools: { [string]: { GuiObject } } = {}
local MAX_POOL = 120

function UIAnimations.acquire(kind: string, factory: () -> GuiObject): GuiObject
	local pool = pools[kind]
	if pool and #pool > 0 then
		local inst = table.remove(pool) :: GuiObject
		inst.Visible = true
		return inst
	end
	local inst = factory()
	inst:SetAttribute("PoolKind", kind)
	return inst
end

function UIAnimations.release(inst: GuiObject)
	local kind = inst:GetAttribute("PoolKind")
	if type(kind) ~= "string" then
		inst:Destroy()
		return
	end
	local pool = pools[kind]
	if not pool then
		pool = {}
		pools[kind] = pool
	end
	if #pool >= MAX_POOL then
		inst:Destroy()
		return
	end
	inst.Visible = false
	inst.Parent = nil
	table.insert(pool, inst)
end

--[[
	emit(container, kind, factory, params)
	params = {
		pos: Vector2, vel: Vector2, gravity: Vector2?, life: number,
		rotVel: number?, rotation: number?, sway: number?, swayFreq: number?,
		fadeStart: number? (0-1), baseTransparency: number?,
		size: UDim2?, growTo: number? (end scale multiplier), zIndex: number?
	}
]]
function UIAnimations.emit(container: GuiObject, kind: string, factory: () -> GuiObject, params)
	local inst = UIAnimations.acquire(kind, factory)
	inst.AnchorPoint = Vector2.new(0.5, 0.5)
	if params.size then
		inst.Size = params.size
	end
	inst.Rotation = params.rotation or 0
	inst.ZIndex = params.zIndex or inst.ZIndex
	inst.Position = UDim2.fromOffset(params.pos.X, params.pos.Y)
	local base = params.baseTransparency or 0
	setVisualTransparency(inst, base)
	inst.Parent = container
	table.insert(particles, {
		inst = inst,
		container = container,
		pos = params.pos,
		vel = params.vel or Vector2.zero,
		gravity = params.gravity or Vector2.zero,
		life = params.life or 1,
		age = 0,
		rotVel = params.rotVel or 0,
		sway = params.sway or 0,
		swayFreq = params.swayFreq or 3,
		phase = math.random() * math.pi * 2,
		fadeStart = params.fadeStart or 0.65,
		base = base,
		baseSize = inst.Size,
		growTo = params.growTo,
		drag = params.drag or 0,
	})
	ensureLoop()
	return inst
end

stepParticles = function(dt: number)
	local i = 1
	while i <= #particles do
		local p = particles[i]
		p.age += dt
		if p.age >= p.life or p.inst.Parent == nil then
			UIAnimations.release(p.inst)
			particles[i] = particles[#particles]
			particles[#particles] = nil
		else
			if p.drag > 0 then
				p.vel *= math.max(0, 1 - p.drag * dt)
			end
			p.vel += p.gravity * dt
			p.pos += p.vel * dt
			local sway = if p.sway ~= 0 then math.sin(p.age * p.swayFreq + p.phase) * p.sway else 0
			p.inst.Position = UDim2.fromOffset(p.pos.X + sway, p.pos.Y)
			if p.rotVel ~= 0 then
				p.inst.Rotation += p.rotVel * dt
			end
			local a = p.age / p.life
			if a > p.fadeStart then
				local f = (a - p.fadeStart) / (1 - p.fadeStart)
				setVisualTransparency(p.inst, p.base + (1 - p.base) * f)
			end
			if p.growTo then
				local s = 1 + (p.growTo - 1) * a
				local bs = p.baseSize
				p.inst.Size = UDim2.new(bs.X.Scale * s, bs.X.Offset * s, bs.Y.Scale * s, bs.Y.Offset * s)
			end
			i += 1
		end
	end
end

-- Instantly removes every particle living inside `container`.
function UIAnimations.clearParticles(container: Instance)
	local i = 1
	while i <= #particles do
		local p = particles[i]
		if p.container == container or p.inst:IsDescendantOf(container) then
			UIAnimations.release(p.inst)
			particles[i] = particles[#particles]
			particles[#particles] = nil
		else
			i += 1
		end
	end
end

---------------------------------------------------------------------------
-- Particle factories (procedural, no assets required)
---------------------------------------------------------------------------
local function iconOrText(iconKey: string, textColor: Color3?): GuiObject
	if ShopConfig.hasImage(iconKey) then
		local img = Instance.new("ImageLabel")
		img.BackgroundTransparency = 1
		img.Image = ShopConfig.Icons[iconKey]
		img.ScaleType = Enum.ScaleType.Fit
		return img
	end
	local label = Instance.new("TextLabel")
	label.BackgroundTransparency = 1
	label.Text = ShopConfig.IconFallback[iconKey] or "?"
	label.TextScaled = true
	label.Font = ShopConfig.FallbackFont
	label.TextColor3 = textColor or Color3.new(1, 1, 1)
	return label
end
UIAnimations.iconOrText = iconOrText

function UIAnimations.snowflakeFactory(): GuiObject
	return iconOrText("Snowflake", Color3.fromRGB(235, 245, 255))
end

function UIAnimations.sparkleFactory(): GuiObject
	return iconOrText("Sparkle", Color3.fromRGB(255, 250, 220))
end

function UIAnimations.confettiFactory(): GuiObject
	local f = Instance.new("Frame")
	f.BorderSizePixel = 0
	f.BackgroundColor3 = Color3.new(1, 1, 1)
	local corner = Instance.new("UICorner")
	corner.CornerRadius = UDim.new(0, 2)
	corner.Parent = f
	return f
end

function UIAnimations.dotFactory(): GuiObject
	local f = Instance.new("Frame")
	f.BorderSizePixel = 0
	f.BackgroundColor3 = Color3.new(1, 1, 1)
	local corner = Instance.new("UICorner")
	corner.CornerRadius = UDim.new(1, 0)
	corner.Parent = f
	return f
end

-- A puffy snow cloud: image if provided, otherwise built from circles.
function UIAnimations.makeCloud(): GuiObject
	if ShopConfig.hasImage("SnowCloud") then
		local img = Instance.new("ImageLabel")
		img.BackgroundTransparency = 1
		img.Image = ShopConfig.Icons.SnowCloud
		img.ScaleType = Enum.ScaleType.Fit
		return img
	end
	local holder = Instance.new("Frame")
	holder.BackgroundTransparency = 1
	holder:SetAttribute("FadeChildren", true)
	local puffs = {
		{ 0.22, 0.62, 0.46 },
		{ 0.45, 0.42, 0.62 },
		{ 0.7, 0.55, 0.5 },
		{ 0.5, 0.72, 0.42 },
		{ 0.85, 0.7, 0.32 },
	}
	for _, puff in puffs do
		local circle = Instance.new("Frame")
		circle.AnchorPoint = Vector2.new(0.5, 0.5)
		circle.Position = UDim2.fromScale(puff[1], puff[2])
		circle.Size = UDim2.fromScale(puff[3] * 0.62, puff[3])
		circle.BorderSizePixel = 0
		circle.BackgroundColor3 = Color3.new(1, 1, 1)
		local c = Instance.new("UICorner")
		c.CornerRadius = UDim.new(1, 0)
		c.Parent = circle
		local g = Instance.new("UIGradient")
		g.Rotation = 90
		g.Color = ColorSequence.new(Color3.new(1, 1, 1), ShopConfig.Colors.SnowShade)
		g.Parent = circle
		circle.Parent = holder
	end
	return holder
end

-- Ripple/burst rings
local function makeRing(color: Color3, thickness: number): Frame
	local ring = Instance.new("Frame")
	ring.AnchorPoint = Vector2.new(0.5, 0.5)
	ring.BackgroundTransparency = 1
	ring.BorderSizePixel = 0
	local corner = Instance.new("UICorner")
	corner.CornerRadius = UDim.new(1, 0)
	corner.Parent = ring
	local stroke = Instance.new("UIStroke")
	stroke.Color = color
	stroke.Thickness = thickness
	stroke.ApplyStrokeMode = Enum.ApplyStrokeMode.Border
	stroke.Parent = ring
	return ring
end

---------------------------------------------------------------------------
-- pop: scale `from` -> `peak` -> 1 (Back), optional fade for CanvasGroups
---------------------------------------------------------------------------
function UIAnimations.pop(obj: GuiObject, opts: { from: number?, peak: number?, duration: number?, fade: boolean? }?)
	local o = opts or {}
	local scale = UIAnimations.getScale(obj)
	local duration = o.duration or 0.38
	obj.Visible = true
	if obj:IsA("CanvasGroup") and o.fade ~= false then
		obj.GroupTransparency = 1
		tween(obj, duration * 0.45, { GroupTransparency = 0 })
	end
	if UIAnimations.isReducedMotion() then
		scale.Scale = 1
		return
	end
	scale.Scale = o.from or 0.6
	local first = tween(scale, duration * 0.62, { Scale = o.peak or 1.05 }, Enum.EasingStyle.Back, Enum.EasingDirection.Out)
	first.Completed:Connect(function(state)
		if state == Enum.PlaybackState.Completed then
			tween(scale, duration * 0.38, { Scale = 1 }, Enum.EasingStyle.Quad, Enum.EasingDirection.InOut)
		end
	end)
end

---------------------------------------------------------------------------
-- ripple: white ring expanding & fading from a point
---------------------------------------------------------------------------
function UIAnimations.ripple(container: GuiObject, center: Vector2, startSize: number, opts: { color: Color3?, grow: number?, duration: number?, thickness: number? }?)
	local o = opts or {}
	local duration = o.duration or 0.55
	local ring = makeRing(o.color or Color3.new(1, 1, 1), o.thickness or 4)
	ring.Position = UDim2.fromOffset(center.X, center.Y)
	ring.Size = UDim2.fromOffset(startSize, startSize)
	ring.ZIndex = 50
	ring.Parent = container
	local stroke = ring:FindFirstChildOfClass("UIStroke") :: UIStroke
	local endSize = startSize * (o.grow or 2.2)
	tween(ring, duration, { Size = UDim2.fromOffset(endSize, endSize) }, Enum.EasingStyle.Quart, Enum.EasingDirection.Out)
	tween(stroke, duration, { Transparency = 1, Thickness = 1 }, Enum.EasingStyle.Quad, Enum.EasingDirection.In)
	task.delay(duration + 0.05, function()
		ring:Destroy()
	end)
end

---------------------------------------------------------------------------
-- afterimageTrail: spawns fading ghost copies behind a moving object
-- Returns stop().
---------------------------------------------------------------------------
function UIAnimations.afterimageTrail(obj: GuiObject, container: GuiObject, opts: { interval: number?, fade: number?, startTransparency: number?, maxGhosts: number? }?): () -> ()
	if UIAnimations.isReducedMotion() then
		return function() end
	end
	local o = opts or {}
	local interval = o.interval or 0.035
	local fade = o.fade or 0.22
	local startT = o.startTransparency or 0.45
	local maxGhosts = o.maxGhosts or 5
	local ghosts = {}
	local acc = 0
	local stopLoop
	stopLoop = UIAnimations.addLoop(function(dt)
		if obj.Parent == nil then
			stopLoop()
			return
		end
		acc += dt
		if acc < interval then
			return
		end
		acc = 0
		local ghost = obj:Clone()
		for _, d in ghost:GetDescendants() do
			if d:IsA("UIScale") then
				d:Destroy()
			end
		end
		ghost.AnchorPoint = Vector2.new(0.5, 0.5)
		local c = UIAnimations.centerIn(obj, container)
		ghost.Position = UDim2.fromOffset(c.X, c.Y)
		ghost.Size = UDim2.fromOffset(obj.AbsoluteSize.X, obj.AbsoluteSize.Y)
		ghost.Rotation = obj.AbsoluteRotation
		ghost.ZIndex = math.max(1, obj.ZIndex - 1)
		setVisualTransparency(ghost, startT)
		ghost.Parent = container
		table.insert(ghosts, ghost)
		if #ghosts > maxGhosts then
			local oldest = table.remove(ghosts, 1)
			oldest:Destroy()
		end
		local fadeTargets = { ghost }
		for _, d in ghost:GetDescendants() do
			table.insert(fadeTargets, d)
		end
		for _, target in fadeTargets do
			if target:IsA("ImageLabel") or target:IsA("ImageButton") then
				tween(target, fade, { ImageTransparency = 1 })
			elseif target:IsA("TextLabel") or target:IsA("TextButton") then
				tween(target, fade, { TextTransparency = 1 })
			elseif target:IsA("UIStroke") then
				tween(target, fade, { Transparency = 1 })
			end
		end
		task.delay(fade + 0.02, function()
			local idx = table.find(ghosts, ghost)
			if idx then
				table.remove(ghosts, idx)
			end
			ghost:Destroy()
		end)
	end)
	return function()
		stopLoop()
	end
end

---------------------------------------------------------------------------
-- burst: white ring + radial speed lines + little sparkle dots
---------------------------------------------------------------------------
function UIAnimations.burst(container: GuiObject, center: Vector2, radius: number, opts: { lines: number?, color: Color3?, duration: number? }?)
	local o = opts or {}
	local color = o.color or Color3.new(1, 1, 1)
	local duration = o.duration or 0.45
	UIAnimations.ripple(container, center, radius * 0.5, { color = color, grow = 2.6, duration = duration, thickness = 6 })
	if UIAnimations.isReducedMotion() then
		return
	end
	local lineCount = o.lines or 14
	for i = 1, lineCount do
		local angle = (i / lineCount) * math.pi * 2 + math.random() * 0.2
		local dir = Vector2.new(math.sin(angle), -math.cos(angle))
		local line = Instance.new("Frame")
		line.AnchorPoint = Vector2.new(0.5, 0.5)
		line.BorderSizePixel = 0
		line.BackgroundColor3 = color
		line.Rotation = math.deg(angle)
		local len = radius * (0.35 + math.random() * 0.25)
		line.Size = UDim2.fromOffset(math.max(2, radius * 0.04), len)
		local startPos = center + dir * radius * 0.45
		line.Position = UDim2.fromOffset(startPos.X, startPos.Y)
		line.ZIndex = 50
		local corner = Instance.new("UICorner")
		corner.CornerRadius = UDim.new(1, 0)
		corner.Parent = line
		line.Parent = container
		local endPos = center + dir * radius * (1.4 + math.random() * 0.4)
		tween(line, duration, {
			Position = UDim2.fromOffset(endPos.X, endPos.Y),
			Size = UDim2.fromOffset(2, len * 0.3),
			BackgroundTransparency = 1,
		}, Enum.EasingStyle.Quart, Enum.EasingDirection.Out)
		task.delay(duration + 0.05, function()
			line:Destroy()
		end)
	end
	for _ = 1, 10 do
		local angle = math.random() * math.pi * 2
		local dir = Vector2.new(math.cos(angle), math.sin(angle))
		local size = radius * (0.06 + math.random() * 0.05)
		UIAnimations.emit(container, "Dot", UIAnimations.dotFactory, {
			pos = center + dir * radius * 0.3,
			vel = dir * radius * (2.5 + math.random() * 2),
			drag = 4,
			life = 0.5 + math.random() * 0.3,
			size = UDim2.fromOffset(size, size),
			zIndex = 51,
			fadeStart = 0.4,
		})
	end
end

---------------------------------------------------------------------------
-- confetti: snowflakes + colored confetti drifting down inside `bounds`
-- bounds = { left, right, top, bottom } in container offset pixels
-- Returns stop().
---------------------------------------------------------------------------
function UIAnimations.confetti(container: GuiObject, bounds: { left: number, right: number, top: number, bottom: number }, duration: number, opts: { rate: number?, snowOnly: boolean? }?): () -> ()
	if UIAnimations.isReducedMotion() then
		return function() end
	end
	local o = opts or {}
	local rate = o.rate or 38 -- particles per second
	local elapsed, acc = 0, 0
	local width = bounds.right - bounds.left
	local height = bounds.bottom - bounds.top
	local unit = math.max(8, width / 45)
	local stop
	stop = UIAnimations.addLoop(function(dt)
		elapsed += dt
		if elapsed >= duration then
			stop()
			return
		end
		acc += dt * rate
		while acc >= 1 do
			acc -= 1
			local x = bounds.left + math.random() * width
			local fallSpeed = height * (0.45 + math.random() * 0.35)
			local life = height / fallSpeed
			if o.snowOnly or math.random() < 0.45 then
				local size = unit * (0.9 + math.random() * 0.9)
				UIAnimations.emit(container, "Snowflake", UIAnimations.snowflakeFactory, {
					pos = Vector2.new(x, bounds.top - size),
					vel = Vector2.new(0, fallSpeed),
					life = life,
					rotVel = (math.random() - 0.5) * 120,
					sway = unit * 1.2,
					swayFreq = 2 + math.random() * 2,
					size = UDim2.fromOffset(size, size),
					zIndex = 40,
					fadeStart = 0.75,
				})
			else
				local colors = ShopConfig.Colors.Confetti
				local piece = UIAnimations.emit(container, "Confetti", UIAnimations.confettiFactory, {
					pos = Vector2.new(x, bounds.top - unit),
					vel = Vector2.new((math.random() - 0.5) * unit * 4, fallSpeed * 1.1),
					life = life,
					rotVel = (math.random() - 0.5) * 540,
					rotation = math.random() * 360,
					sway = unit * 1.6,
					swayFreq = 4 + math.random() * 3,
					size = UDim2.fromOffset(unit * 0.55, unit * 1.05),
					zIndex = 40,
					fadeStart = 0.75,
				})
				piece.BackgroundColor3 = colors[math.random(1, #colors)]
			end
		end
	end)
	return stop
end

---------------------------------------------------------------------------
-- raysSpin: rotates an ImageLabel, or every bar of a procedural ray burst
-- Returns stop().
---------------------------------------------------------------------------
function UIAnimations.raysSpin(rays: GuiObject, degreesPerSecond: number?): () -> ()
	local speed = degreesPerSecond or 25
	if UIAnimations.isReducedMotion() then
		speed *= 0.25
	end
	local angle = 0
	local bars = {}
	for _, child in rays:GetChildren() do
		if child:IsA("GuiObject") and child:GetAttribute("RayBase") ~= nil then
			table.insert(bars, child)
		end
	end
	return UIAnimations.addLoop(function(dt)
		angle = (angle + speed * dt) % 360
		if #bars > 0 then
			for _, bar in bars do
				bar.Rotation = (bar:GetAttribute("RayBase") :: number) + angle
			end
		else
			rays.Rotation = angle
		end
	end)
end

---------------------------------------------------------------------------
-- bob: gentle up/down float. amplitude is a fraction of the object's height.
-- Returns stop() (restores the original position).
---------------------------------------------------------------------------
function UIAnimations.bob(obj: GuiObject, amplitude: number?, period: number?, phase: number?): () -> ()
	local base = obj.Position
	if UIAnimations.isReducedMotion() then
		return function() end
	end
	local amp = amplitude or 0.05
	local per = period or 2.2
	local t = phase or 0
	local stop = UIAnimations.addLoop(function(dt)
		t += dt
		local offset = math.sin(t / per * math.pi * 2) * obj.AbsoluteSize.Y * amp
		obj.Position = base + UDim2.fromOffset(0, offset)
	end)
	return function()
		stop()
		obj.Position = base
	end
end

-- swing: pendulum rotation around the object's center (bells, waving snowman)
function UIAnimations.swing(obj: GuiObject, degrees: number, period: number, phase: number?): () -> ()
	local base = obj.Rotation
	if UIAnimations.isReducedMotion() then
		return function() end
	end
	local t = phase or 0
	local stop = UIAnimations.addLoop(function(dt)
		t += dt
		obj.Rotation = base + math.sin(t / period * math.pi * 2) * degrees
	end)
	return function()
		stop()
		obj.Rotation = base
	end
end

-- breathe: looping scale on a UIScale (1 -> max -> 1)
function UIAnimations.breathe(scale: UIScale, max: number, period: number): () -> ()
	if UIAnimations.isReducedMotion() then
		return function() end
	end
	local t = 0
	local stop = UIAnimations.addLoop(function(dt)
		t += dt
		scale.Scale = 1 + (max - 1) * (0.5 - 0.5 * math.cos(t / period * math.pi * 2))
	end)
	return function()
		stop()
		scale.Scale = 1
	end
end

---------------------------------------------------------------------------
-- bindButton: hover / press / release scaling. Returns a Maid.
---------------------------------------------------------------------------
function UIAnimations.bindButton(button: GuiButton, opts: { target: GuiObject?, hover: number?, press: number?, onHover: ((boolean) -> ())? }?): any
	local o = opts or {}
	local target = o.target or button
	local scale = UIAnimations.getScale(target, "HoverScale")
	local hoverScale = o.hover or 1.08
	local pressScale = o.press or 0.94
	local hovered = false
	local maid = Maid.new()
	maid:Give(button.MouseEnter:Connect(function()
		hovered = true
		tween(scale, 0.18, { Scale = hoverScale }, Enum.EasingStyle.Back, Enum.EasingDirection.Out)
		if o.onHover then
			o.onHover(true)
		end
	end))
	maid:Give(button.MouseLeave:Connect(function()
		hovered = false
		tween(scale, 0.18, { Scale = 1 }, Enum.EasingStyle.Back, Enum.EasingDirection.Out)
		if o.onHover then
			o.onHover(false)
		end
	end))
	maid:Give(button.MouseButton1Down:Connect(function()
		tween(scale, 0.07, { Scale = pressScale }, Enum.EasingStyle.Quad, Enum.EasingDirection.Out)
	end))
	maid:Give(button.MouseButton1Up:Connect(function()
		tween(scale, 0.3, { Scale = if hovered then hoverScale else 1 }, Enum.EasingStyle.Back, Enum.EasingDirection.Out)
	end))
	-- Touch: MouseLeave is not always fired, so settle back after a tap.
	maid:Give(button.Activated:Connect(function(input)
		if input and input.UserInputType == Enum.UserInputType.Touch then
			hovered = false
			task.delay(0.12, function()
				tween(scale, 0.3, { Scale = 1 }, Enum.EasingStyle.Back, Enum.EasingDirection.Out)
			end)
		end
	end))
	return maid
end

---------------------------------------------------------------------------
-- squash: squash & stretch via Size, then elastic settle
---------------------------------------------------------------------------
local baseSizes = setmetatable({}, { __mode = "k" })

function UIAnimations.squash(obj: GuiObject, amount: number?)
	local base = baseSizes[obj]
	if not base then
		base = obj.Size
		baseSizes[obj] = base
	end
	local a = amount or 0.14
	local squashed = UDim2.new(base.X.Scale * (1 + a), base.X.Offset * (1 + a), base.Y.Scale * (1 - a), base.Y.Offset * (1 - a))
	obj.Size = squashed
	tween(obj, 0.45, { Size = base }, Enum.EasingStyle.Elastic, Enum.EasingDirection.Out)
end

-- shake: quick positional jitter, restores position afterwards
function UIAnimations.shake(obj: GuiObject, intensity: number, duration: number)
	if UIAnimations.isReducedMotion() then
		return
	end
	local base = obj:GetAttribute("ShakeBase")
	if typeof(base) ~= "UDim2" then
		base = obj.Position
		obj:SetAttribute("ShakeBase", base)
	end
	local elapsed = 0
	local stop
	stop = UIAnimations.addLoop(function(dt)
		elapsed += dt
		if elapsed >= duration then
			stop()
			obj.Position = base
			obj:SetAttribute("ShakeBase", nil)
			return
		end
		local falloff = 1 - elapsed / duration
		obj.Position = base + UDim2.fromOffset((math.random() - 0.5) * 2 * intensity * falloff, (math.random() - 0.5) * 2 * intensity * falloff)
	end)
end

---------------------------------------------------------------------------
-- vortexClose: spins & shrinks a CanvasGroup into white swirling arcs,
-- then a 4-point sparkle twinkles. Calls onDone when finished.
---------------------------------------------------------------------------
function UIAnimations.vortexClose(target: CanvasGroup, container: GuiObject, onDone: (() -> ())?)
	local scale = UIAnimations.getScale(target)
	if UIAnimations.isReducedMotion() then
		local tw = tween(target, 0.2, { GroupTransparency = 1 })
		tw.Completed:Wait()
		target.Visible = false
		target.GroupTransparency = 0
		if onDone then
			onDone()
		end
		return
	end

	local center = UIAnimations.centerIn(target, container)
	local size = math.max(target.AbsoluteSize.X, target.AbsoluteSize.Y)
	local duration = 0.55

	tween(scale, duration, { Scale = 0.04 }, Enum.EasingStyle.Quad, Enum.EasingDirection.In)
	tween(target, duration, { Rotation = 360 }, Enum.EasingStyle.Quad, Enum.EasingDirection.In)
	tween(target, duration * 0.6, { GroupTransparency = 1 }, Enum.EasingStyle.Quad, Enum.EasingDirection.In, duration * 0.4)

	-- swirling white arc rings
	local rings = {}
	for i = 1, 4 do
		local ring = makeRing(Color3.new(1, 1, 1), math.max(3, size * 0.012))
		local radius = size * (0.22 + 0.16 * i)
		ring.Size = UDim2.fromOffset(radius, radius)
		ring.Position = UDim2.fromOffset(center.X, center.Y)
		ring.Rotation = math.random(0, 360)
		ring.ZIndex = 60
		local stroke = ring:FindFirstChildOfClass("UIStroke") :: UIStroke
		local gradient = Instance.new("UIGradient")
		gradient.Transparency = NumberSequence.new({
			NumberSequenceKeypoint.new(0, 0),
			NumberSequenceKeypoint.new(0.42, 0.05),
			NumberSequenceKeypoint.new(0.5, 1),
			NumberSequenceKeypoint.new(1, 1),
		})
		gradient.Parent = stroke
		stroke.Transparency = 1
		ring.Parent = container
		table.insert(rings, ring)
		local d = duration + 0.15
		tween(stroke, 0.12, { Transparency = 0 })
		tween(ring, d, { Size = UDim2.fromOffset(4, 4), Rotation = ring.Rotation + 540 + i * 60 }, Enum.EasingStyle.Quad, Enum.EasingDirection.In)
		tween(stroke, d * 0.5, { Transparency = 1 }, Enum.EasingStyle.Quad, Enum.EasingDirection.In, d * 0.5)
	end

	-- snow dots sucked into the center
	for _ = 1, 14 do
		local angle = math.random() * math.pi * 2
		local dist = size * (0.3 + math.random() * 0.4)
		local startPos = center + Vector2.new(math.cos(angle), math.sin(angle)) * dist
		local dot = UIAnimations.dotFactory()
		local s = size * 0.012 + math.random() * 4
		dot.AnchorPoint = Vector2.new(0.5, 0.5)
		dot.Size = UDim2.fromOffset(s, s)
		dot.Position = UDim2.fromOffset(startPos.X, startPos.Y)
		dot.ZIndex = 61
		dot.Parent = container
		tween(dot, duration, { Position = UDim2.fromOffset(center.X, center.Y), BackgroundTransparency = 1 }, Enum.EasingStyle.Quad, Enum.EasingDirection.In)
		task.delay(duration + 0.05, function()
			dot:Destroy()
		end)
	end

	task.wait(duration)
	target.Visible = false
	target.Rotation = 0
	scale.Scale = 1
	target.GroupTransparency = 0

	-- 4-point sparkle star twinkle
	local star = iconOrText("Sparkle", Color3.fromRGB(255, 255, 255))
	star.AnchorPoint = Vector2.new(0.5, 0.5)
	star.Position = UDim2.fromOffset(center.X, center.Y)
	star.Size = UDim2.fromOffset(size * 0.14, size * 0.14)
	star.ZIndex = 62
	local starScale = UIAnimations.getScale(star)
	starScale.Scale = 0
	star.Parent = container
	tween(starScale, 0.16, { Scale = 1.3 }, Enum.EasingStyle.Back, Enum.EasingDirection.Out)
	tween(star, 0.45, { Rotation = 90 }, Enum.EasingStyle.Quad, Enum.EasingDirection.Out)
	task.wait(0.18)
	tween(starScale, 0.25, { Scale = 0 }, Enum.EasingStyle.Back, Enum.EasingDirection.In)
	task.wait(0.27)
	star:Destroy()
	for _, ring in rings do
		ring:Destroy()
	end
	if onDone then
		onDone()
	end
end

return UIAnimations
