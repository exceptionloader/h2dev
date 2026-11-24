--[[
	SackMinigame  (ModuleScript)
	Explorer:  StarterPlayer > StarterPlayerScripts > ShopController > SackMinigame

	Purely visual: the server already rolled the results and sent them in the
	payload. Flow:
	  darken + blur -> "TAP THE SACK! 7 LEFT" -> white flash explosion ->
	  decelerating reveal spinner -> result with rays/sparkles -> TAP TO CLAIM
	  -> reward flies to the HUD.
	Buy 5 / Buy 10: tap phase once, then each reveal in turn (Skip button),
	then a summary grid.
]]

local Lighting = game:GetService("Lighting")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local UserInputService = game:GetService("UserInputService")

local ShopConfig = require(ReplicatedStorage:WaitForChild("ShopConfig"))
local UIAnimations = require(ReplicatedStorage:WaitForChild("UIAnimations"))
local UIKit = require(script.Parent:WaitForChild("UIKit"))

local Colors = ShopConfig.Colors
local Maid = UIAnimations.Maid
local tween = UIAnimations.tween

local SPRAY_ITEMS = { "CandyCane", "Present", "Stocking" }
local CONFIRM_KEYS = {
	[Enum.KeyCode.Space] = true,
	[Enum.KeyCode.Return] = true,
	[Enum.KeyCode.ButtonA] = true,
}
local IDLE_AUTO_TAP = 8 -- seconds without input before the sack opens itself
local CLAIM_TIMEOUT = 15 -- seconds before a result is auto-claimed

local SackMinigame = {}
SackMinigame.__index = SackMinigame

--[[
	deps = {
		setHudHidden: (boolean) -> (),
		getRewardTarget: () -> Vector2,   -- point (in root coordinates) rewards fly to
	}
]]
function SackMinigame.new(root: GuiObject, deps)
	return setmetatable({
		Root = root,
		Deps = deps,
		Playing = false,
	}, SackMinigame)
end

---------------------------------------------------------------------------
-- helpers
---------------------------------------------------------------------------
local function fadeOut(obj: GuiObject, duration: number)
	if obj:IsA("ImageLabel") or obj:IsA("ImageButton") then
		tween(obj, duration, { ImageTransparency = 1 })
	elseif obj:IsA("TextLabel") or obj:IsA("TextButton") then
		tween(obj, duration, { TextTransparency = 1 })
	else
		tween(obj, duration, { BackgroundTransparency = 1 })
	end
	for _, d in obj:GetDescendants() do
		if d:IsA("UIStroke") then
			tween(d, duration, { Transparency = 1 })
		elseif d:IsA("TextLabel") then
			tween(d, duration, { TextTransparency = 1 })
		elseif d:IsA("ImageLabel") then
			tween(d, duration, { ImageTransparency = 1 })
		end
	end
end

local function lootIndex(key: string): number
	for i, entry in ShopConfig.Loot do
		if entry.Key == key then
			return i
		end
	end
	return 1
end

local function iconFactory(iconKey: string)
	return function()
		return UIAnimations.iconOrText(iconKey)
	end
end

-- Waits for a tap anywhere (or confirm key). Returns "tap" | "skip" | "timeout" | "abort".
local function waitForTap(stage: GuiObject, timeout: number?, skipButton: GuiButton?, armDelay: number?): string
	local result: string? = nil
	local armed = false
	task.delay(armDelay or 0.35, function()
		armed = true
	end)
	local catcher = UIKit.create("TextButton", {
		Name = "TapCatcher",
		Text = "",
		BackgroundTransparency = 1,
		Size = UDim2.fromScale(1, 1),
		ZIndex = 139,
		Parent = stage,
	})
	local connections = {
		catcher.Activated:Connect(function()
			if armed and not result then
				result = "tap"
			end
		end),
		UserInputService.InputBegan:Connect(function(input, processed)
			if not processed and armed and not result and CONFIRM_KEYS[input.KeyCode] then
				result = "tap"
			end
		end),
	}
	if skipButton then
		table.insert(
			connections,
			skipButton.Activated:Connect(function()
				if not result then
					result = "skip"
				end
			end)
		)
	end
	local started = os.clock()
	while not result do
		task.wait()
		if not stage.Parent then
			result = "abort"
		elseif timeout and os.clock() - started > timeout then
			result = "timeout"
		end
	end
	for _, c in connections do
		c:Disconnect()
	end
	catcher:Destroy()
	return result :: string
end

---------------------------------------------------------------------------
-- Phase 1: TAP THE SACK
---------------------------------------------------------------------------
function SackMinigame:_tapPhase(stage: GuiObject, fx: GuiObject, maid)
	local taps = ShopConfig.SackTapsRequired

	local holder = UIKit.create("Frame", {
		Name = "SackHolder",
		BackgroundTransparency = 1,
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.56),
		Size = UDim2.fromScale(0.36, 0.5),
		ZIndex = 110,
		Parent = stage,
	})
	UIKit.aspect(holder, 1)

	local rays = UIKit.rays({
		Size = UDim2.fromScale(1.8, 1.8),
		ZIndex = 108,
		Transparency = 0.45,
		Count = 12,
		Parent = holder,
	})
	maid:Give(UIAnimations.raysSpin(rays, 30))
	local raysScale = UIAnimations.getScale(rays)

	local glow = UIKit.create("Frame", {
		Name = "Glow",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.5),
		Size = UDim2.fromScale(0.95, 0.95),
		BackgroundColor3 = Colors.Gold,
		BackgroundTransparency = 0.78,
		ZIndex = 109,
		Parent = holder,
	})
	UIKit.corner(glow, UDim.new(1, 0))

	local sackButton = UIKit.create("TextButton", {
		Name = "Sack",
		Text = "",
		BackgroundTransparency = 1,
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.5),
		Size = UDim2.fromScale(1, 1),
		ZIndex = 111,
		Parent = holder,
	})
	UIKit.icon("SantaSack", {
		Size = UDim2.fromScale(0.86, 0.86),
		ZIndex = 112,
		Parent = sackButton,
	})

	local title = UIKit.label({
		Text = "TAP THE SACK!",
		AnchorPoint = Vector2.new(0.5, 0),
		Position = UDim2.fromScale(0.5, 0.09),
		Size = UDim2.fromScale(0.6, 0.085),
		ZIndex = 113,
		MaxTextSize = 72,
		StrokeThickness = 3,
		Parent = stage,
	})
	local counter = UIKit.label({
		Text = `{taps} LEFT`,
		AnchorPoint = Vector2.new(0.5, 0),
		Position = UDim2.fromScale(0.5, 0.18),
		Size = UDim2.fromScale(0.25, 0.045),
		ZIndex = 113,
		MaxTextSize = 36,
		Parent = stage,
	})

	UIAnimations.pop(holder, { from = 0.2, peak = 1.08, duration = 0.45 })
	UIAnimations.pop(title, { from = 0.4 })
	maid:Give(UIAnimations.bob(sackButton, 0.025, 1.6))

	local remaining = taps
	local lastInput = os.clock()

	local function spray()
		if UIAnimations.isReducedMotion() then
			return
		end
		local size = holder.AbsoluteSize.X
		local center = UIAnimations.centerIn(holder, fx) - Vector2.new(0, size * 0.18)
		for _ = 1, math.random(3, 5) do
			local key = SPRAY_ITEMS[math.random(1, #SPRAY_ITEMS)]
			local angle = math.rad(math.random(-75, 75))
			local speed = size * (2.2 + math.random() * 1.4)
			local s = size * (0.13 + math.random() * 0.06)
			UIAnimations.emit(fx, "Mini" .. key, iconFactory(key), {
				pos = center,
				vel = Vector2.new(math.sin(angle), -math.cos(angle)) * speed,
				gravity = Vector2.new(0, size * 6),
				life = 1.2,
				rotVel = (math.random() - 0.5) * 720,
				rotation = math.random(-30, 30),
				size = UDim2.fromOffset(s, s),
				zIndex = 125,
				fadeStart = 0.7,
			})
		end
	end

	local function onTap()
		if remaining <= 0 then
			return
		end
		remaining -= 1
		lastInput = os.clock()
		local progress = (taps - remaining) / taps
		counter.Text = `{remaining} LEFT`
		UIAnimations.pop(counter, { from = 1.4, peak = 0.95, duration = 0.25 })
		UIAnimations.squash(sackButton, 0.16)
		UIAnimations.shake(holder, holder.AbsoluteSize.X * 0.025, 0.22)
		spray()
		tween(glow, 0.2, { BackgroundTransparency = 0.78 - 0.5 * progress, Size = UDim2.fromScale(0.95 + 0.3 * progress, 0.95 + 0.3 * progress) })
		tween(raysScale, 0.25, { Scale = 1 + 0.25 * progress }, Enum.EasingStyle.Back, Enum.EasingDirection.Out)
	end

	maid:Give(sackButton.Activated:Connect(onTap))
	maid:Give(UserInputService.InputBegan:Connect(function(input, processed)
		if not processed and CONFIRM_KEYS[input.KeyCode] then
			onTap()
		end
	end))

	while remaining > 0 and stage.Parent do
		task.wait()
		if os.clock() - lastInput > IDLE_AUTO_TAP then
			onTap()
			lastInput = os.clock() - IDLE_AUTO_TAP + 0.25 -- keep auto-tapping quickly
		end
	end
	task.wait(0.15)

	return holder, title, counter
end

---------------------------------------------------------------------------
-- Phase 2: explosion
---------------------------------------------------------------------------
function SackMinigame:_explode(stage: GuiObject, fx: GuiObject, holder: GuiObject)
	local center = UIAnimations.centerIn(holder, fx)
	local size = holder.AbsoluteSize.X

	local flash = UIKit.create("Frame", {
		Name = "Flash",
		BackgroundColor3 = Color3.new(1, 1, 1),
		BackgroundTransparency = 0,
		Size = UDim2.fromScale(1, 1),
		ZIndex = 130,
		Parent = stage,
	})
	tween(flash, 0.7, { BackgroundTransparency = 1 }, Enum.EasingStyle.Quad, Enum.EasingDirection.Out)
	task.delay(0.75, function()
		flash:Destroy()
	end)
	holder.Visible = false

	UIAnimations.burst(fx, center, size * 0.6, { lines = 18 })
	if not UIAnimations.isReducedMotion() then
		for i = 1, 26 do
			local key = if i % 7 == 0 then "GoldenSnowflake" else SPRAY_ITEMS[math.random(1, #SPRAY_ITEMS)]
			local angle = math.random() * math.pi * 2
			local dir = Vector2.new(math.cos(angle), math.sin(angle))
			local s = size * (0.12 + math.random() * 0.08)
			UIAnimations.emit(fx, "Mini" .. key, iconFactory(key), {
				pos = center + dir * size * 0.1,
				vel = dir * size * (2 + math.random() * 2.5) + Vector2.new(0, -size * 1.2),
				gravity = Vector2.new(0, size * 3.6),
				life = 1.7,
				rotVel = (math.random() - 0.5) * 600,
				size = UDim2.fromOffset(s, s),
				zIndex = 125,
				fadeStart = 0.6,
			})
		end
		for _ = 1, 8 do
			local angle = math.random() * math.pi * 2
			local dir = Vector2.new(math.cos(angle), math.sin(angle))
			local s = size * (0.22 + math.random() * 0.12)
			UIAnimations.emit(fx, "Cloud", UIAnimations.makeCloud, {
				pos = center + dir * size * 0.2,
				vel = dir * size * 1.6,
				drag = 2.5,
				gravity = Vector2.new(0, size * 0.3),
				life = 1.2,
				size = UDim2.fromOffset(s, s * 0.7),
				zIndex = 124,
				fadeStart = 0.4,
				growTo = 1.4,
			})
		end
	end
	task.wait(0.55)
end

---------------------------------------------------------------------------
-- Phase 3: reveal spinner + result
-- Returns a table { Icon, Cleanup() }
---------------------------------------------------------------------------
function SackMinigame:_reveal(stage: GuiObject, fx: GuiObject, resultKey: string, opts: { counterText: string?, skipFlag: { value: boolean }? })
	local loot = ShopConfig.Loot
	local resultIndex = lootIndex(resultKey)
	local reduced = UIAnimations.isReducedMotion()
	local steps = if reduced then 1 else 16
	local current: GuiObject? = nil
	local iconSize = UDim2.fromScale(0.28, 0.4)

	local counterLabel = nil
	if opts.counterText then
		counterLabel = UIKit.label({
			Text = opts.counterText,
			AnchorPoint = Vector2.new(0.5, 0),
			Position = UDim2.fromScale(0.5, 0.08),
			Size = UDim2.fromScale(0.2, 0.05),
			ZIndex = 113,
			MaxTextSize = 34,
			Parent = stage,
		})
	end

	for k = 1, steps do
		if opts.skipFlag and opts.skipFlag.value then
			break
		end
		local idx = ((resultIndex - 1) - (steps - k)) % #loot + 1
		local entry = loot[idx]
		local delayTime = 0.05 + 0.4 * (k / steps) ^ 2.6
		local icon = UIKit.icon(entry.Icon, {
			Name = "RevealIcon",
			Size = iconSize,
			Position = UDim2.fromScale(if reduced then 0.5 else 0.8, 0.5),
			ZIndex = 116,
			Parent = stage,
		})
		local slide = math.min(delayTime * 0.85, 0.24)
		local stopTrail = UIAnimations.afterimageTrail(icon, fx, { interval = 0.018, fade = 0.16, startTransparency = 0.45, maxGhosts = 5 })
		tween(icon, slide, { Position = UDim2.fromScale(0.5, 0.5) }, Enum.EasingStyle.Quad, Enum.EasingDirection.Out)
		if current then
			local old = current
			tween(old, slide, { Position = UDim2.fromScale(0.2, 0.5) }, Enum.EasingStyle.Quad, Enum.EasingDirection.In)
			fadeOut(old, slide)
			task.delay(slide + 0.05, function()
				old:Destroy()
			end)
		end
		task.delay(slide, stopTrail)
		current = icon
		task.wait(if reduced then 0.2 else delayTime)
	end

	local entry = ShopConfig.getLoot(resultKey)
	if not current or current:GetAttribute("IconKey") ~= entry.Icon then
		if current then
			current:Destroy()
		end
		current = UIKit.icon(entry.Icon, {
			Name = "RevealIcon",
			Size = iconSize,
			ZIndex = 116,
			Parent = stage,
		})
	end
	local icon = current :: GuiObject
	icon.Position = UDim2.fromScale(0.5, 0.5)

	-- Result presentation
	local resultMaid = Maid.new()
	UIAnimations.pop(icon, { from = 0.55, peak = 1.22, duration = 0.5 })

	local rays = UIKit.rays({
		Size = UDim2.fromScale(0.62, 0.9),
		Position = UDim2.fromScale(0.5, 0.5),
		ZIndex = 114,
		Transparency = 0.3,
		Count = 12,
		Parent = stage,
	})
	resultMaid:Give(rays)
	resultMaid:Give(UIAnimations.raysSpin(rays, 22))
	UIAnimations.pop(rays, { from = 0.3, peak = 1.1, duration = 0.5 })

	-- sparkle snowflakes orbiting the item
	local sparkleHolder = UIKit.create("Frame", {
		Name = "Orbit",
		BackgroundTransparency = 1,
		Size = UDim2.fromScale(1, 1),
		ZIndex = 117,
		Parent = stage,
	})
	resultMaid:Give(sparkleHolder)
	local sparkles = {}
	for i = 1, 12 do
		local flake = if i % 3 == 0 then UIAnimations.sparkleFactory() else UIAnimations.snowflakeFactory()
		flake.AnchorPoint = Vector2.new(0.5, 0.5)
		flake.ZIndex = 117
		flake.Parent = sparkleHolder
		sparkles[i] = flake
	end
	local orbitTime = 0
	resultMaid:Give(UIAnimations.addLoop(function(dt)
		orbitTime += dt
		local c = UIAnimations.centerIn(icon, sparkleHolder)
		local radius = icon.AbsoluteSize.X * 0.78
		for i, flake in sparkles do
			local a = orbitTime * 0.9 + (i / #sparkles) * math.pi * 2
			local wobble = 1 + math.sin(orbitTime * 3 + i) * 0.08
			local s = icon.AbsoluteSize.X * (0.08 + 0.03 * math.sin(orbitTime * 4 + i * 1.7))
			flake.Size = UDim2.fromOffset(s, s)
			flake.Position = UDim2.fromOffset(c.X + math.cos(a) * radius * wobble, c.Y + math.sin(a) * radius * 0.85 * wobble)
			UIAnimations.setVisualTransparency(flake, 0.15 + 0.35 * (0.5 + 0.5 * math.sin(orbitTime * 5 + i)))
		end
	end))

	local nameLabel = UIKit.label({
		Text = entry.Name .. "!",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.76),
		Size = UDim2.fromScale(0.5, 0.085),
		Color = Colors.Gold,
		StrokeThickness = 3.5,
		ZIndex = 118,
		MaxTextSize = 72,
		Parent = stage,
	})
	resultMaid:Give(nameLabel)
	local cashLabel = UIKit.label({
		Text = `+{ShopConfig.formatNumber(entry.Cash)} {ShopConfig.CurrencyName}`,
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.835),
		Size = UDim2.fromScale(0.3, 0.045),
		ZIndex = 118,
		MaxTextSize = 34,
		Parent = stage,
	})
	resultMaid:Give(cashLabel)
	local claimLabel = UIKit.label({
		Text = "TAP TO CLAIM",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.91),
		Size = UDim2.fromScale(0.26, 0.045),
		ZIndex = 118,
		MaxTextSize = 34,
		Parent = stage,
	})
	resultMaid:Give(claimLabel)
	UIAnimations.pop(nameLabel, { from = 0.3, peak = 1.15, duration = 0.45 })
	UIAnimations.pop(cashLabel, { from = 0.3, duration = 0.4 })
	resultMaid:Give(UIAnimations.breathe(UIAnimations.getScale(claimLabel), 1.1, 0.9))

	if entry.Announce then
		-- Rare drop: extra golden burst + confetti
		local c = UIAnimations.centerIn(icon, fx)
		UIAnimations.burst(fx, c, icon.AbsoluteSize.X * 0.9, { color = Colors.Gold, lines = 22 })
		resultMaid:Give(UIAnimations.confetti(fx, {
			left = 0,
			right = fx.AbsoluteSize.X,
			top = 0,
			bottom = fx.AbsoluteSize.Y,
		}, 2.5, { rate = 60 }))
	else
		UIAnimations.burst(fx, UIAnimations.centerIn(icon, fx), icon.AbsoluteSize.X * 0.6, { lines = 12 })
	end

	if counterLabel then
		resultMaid:Give(counterLabel)
	end

	return {
		Icon = icon,
		Cleanup = function()
			resultMaid:Clean()
		end,
	}
end

---------------------------------------------------------------------------
-- Reward fly-out
---------------------------------------------------------------------------
function SackMinigame:_flyToHud(obj: GuiObject, fx: GuiObject, delayTime: number?)
	local start = UIAnimations.centerIn(obj, fx)
	local size = obj.AbsoluteSize
	obj.Parent = fx
	obj.AnchorPoint = Vector2.new(0.5, 0.5)
	obj.Position = UDim2.fromOffset(start.X, start.Y)
	obj.Size = UDim2.fromOffset(size.X, size.Y)
	obj.ZIndex = 135
	local scale = obj:FindFirstChild("AnimScale")
	if scale then
		scale:Destroy()
	end
	local target = self.Deps.getRewardTarget()
	task.delay(delayTime or 0, function()
		if not obj.Parent then
			return
		end
		local stopTrail = UIAnimations.afterimageTrail(obj, fx, { interval = 0.03, fade = 0.2 })
		local s = math.max(16, size.X * 0.2)
		tween(obj, 0.55, { Position = UDim2.fromOffset(target.X, target.Y), Size = UDim2.fromOffset(s, s) }, Enum.EasingStyle.Back, Enum.EasingDirection.In)
		task.wait(0.5)
		stopTrail()
		fadeOut(obj, 0.12)
		task.wait(0.14)
		UIAnimations.ripple(fx, target, s * 1.5, { color = Colors.Gold, grow = 3, duration = 0.4 })
		obj:Destroy()
	end)
end

---------------------------------------------------------------------------
-- Summary grid for Buy 5 / Buy 10
---------------------------------------------------------------------------
function SackMinigame:_summary(stage: GuiObject, results: { string }, totalCash: number)
	local panel = UIKit.create("Frame", {
		Name = "Summary",
		BackgroundTransparency = 1,
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.5),
		Size = UDim2.fromScale(0.66, 0.5),
		ZIndex = 116,
		Parent = stage,
	})
	UIKit.aspect(panel, 2.3)
	UIKit.label({
		Text = "YOUR LOOT!",
		AnchorPoint = Vector2.new(0.5, 1),
		Position = UDim2.fromScale(0.5, -0.04),
		Size = UDim2.fromScale(0.5, 0.18),
		Color = Colors.Gold,
		StrokeThickness = 3.5,
		ZIndex = 118,
		MaxTextSize = 72,
		Parent = panel,
	})
	local grid = UIKit.create("Frame", {
		BackgroundTransparency = 1,
		Size = UDim2.fromScale(1, 1),
		ZIndex = 116,
		Parent = panel,
	})
	local columns = math.min(5, #results)
	local rows = math.ceil(#results / columns)
	UIKit.create("UIGridLayout", {
		CellSize = UDim2.fromScale(0.94 / columns, 0.9 / rows),
		CellPadding = UDim2.fromScale(0.06 / columns, 0.08 / rows),
		HorizontalAlignment = Enum.HorizontalAlignment.Center,
		VerticalAlignment = Enum.VerticalAlignment.Center,
		SortOrder = Enum.SortOrder.LayoutOrder,
		Parent = grid,
	})
	local icons = {}
	for i, key in results do
		local entry = ShopConfig.getLoot(key)
		local tile = UIKit.create("Frame", {
			Name = "Tile",
			LayoutOrder = i,
			ZIndex = 117,
			Parent = grid,
		})
		UIKit.gradient(tile, entry.TileTop, entry.TileBottom)
		UIKit.corner(tile, UDim.new(0.16, 0))
		UIKit.stroke(tile, 3)
		local icon = UIKit.icon(entry.Icon, {
			Size = UDim2.fromScale(0.72, 0.72),
			Position = UDim2.fromScale(0.5, 0.42),
			ZIndex = 118,
			Parent = tile,
		})
		UIKit.label({
			Text = `+{ShopConfig.formatNumber(entry.Cash)}`,
			AnchorPoint = Vector2.new(0.5, 1),
			Position = UDim2.fromScale(0.5, 0.97),
			Size = UDim2.fromScale(0.9, 0.2),
			ZIndex = 119,
			MaxTextSize = 24,
			Parent = tile,
		})
		table.insert(icons, icon)
		tile.Visible = false
		task.delay(0.06 * i, function()
			if tile.Parent then
				UIAnimations.pop(tile, { from = 0.3, peak = 1.12, duration = 0.35 })
			end
		end)
	end
	local total = UIKit.label({
		Text = `+{ShopConfig.formatNumber(totalCash)} {ShopConfig.CurrencyName}`,
		AnchorPoint = Vector2.new(0.5, 0),
		Position = UDim2.fromScale(0.5, 1.05),
		Size = UDim2.fromScale(0.5, 0.13),
		ZIndex = 118,
		MaxTextSize = 48,
		Parent = panel,
	})
	local claim = UIKit.label({
		Text = "TAP TO CLAIM",
		AnchorPoint = Vector2.new(0.5, 0),
		Position = UDim2.fromScale(0.5, 1.2),
		Size = UDim2.fromScale(0.36, 0.1),
		ZIndex = 118,
		MaxTextSize = 34,
		Parent = panel,
	})
	UIAnimations.pop(total, { from = 0.4 })
	local stopBreath = UIAnimations.breathe(UIAnimations.getScale(claim), 1.1, 0.9)
	return panel, icons, stopBreath
end

---------------------------------------------------------------------------
-- Public: Play(payload) – yields until the player claimed everything
-- payload = { BatchId, Results = { lootKey... }, Cash, FromName? }
---------------------------------------------------------------------------
function SackMinigame:Play(payload)
	if self.Playing then
		return
	end
	self.Playing = true
	local maid = Maid.new()
	local results = payload.Results
	local ok, err = pcall(function()
		-- Setup: darken + blur, hide HUD
		local stage = UIKit.create("Frame", {
			Name = "SackMinigame",
			BackgroundTransparency = 1,
			Size = UDim2.fromScale(1, 1),
			ZIndex = 100,
			Parent = self.Root,
		})
		maid:Give(stage)
		local overlay = UIKit.create("Frame", {
			Name = "Overlay",
			BackgroundColor3 = Color3.fromRGB(8, 12, 28),
			BackgroundTransparency = 1,
			Active = true,
			Size = UDim2.fromScale(1, 1),
			ZIndex = 100,
			Parent = stage,
		})
		local fx = UIKit.create("Frame", {
			Name = "FX",
			BackgroundTransparency = 1,
			Size = UDim2.fromScale(1, 1),
			ZIndex = 115, -- above the sack and rays, below the revealed item
			Parent = stage,
		})
		maid:Give(function()
			UIAnimations.clearParticles(fx)
		end)

		local blur = Instance.new("BlurEffect")
		blur.Name = "ChristmasShopBlur"
		blur.Size = 0
		blur.Parent = Lighting
		maid:Give(blur)

		self.Deps.setHudHidden(true)
		tween(overlay, 0.35, { BackgroundTransparency = 0.38 })
		tween(blur, 0.35, { Size = 18 })

		if payload.FromName then
			local banner = UIKit.label({
				Text = `🎁 GIFT FROM {string.upper(tostring(payload.FromName))}!`,
				AnchorPoint = Vector2.new(0.5, 0),
				Position = UDim2.fromScale(0.5, 0.025),
				Size = UDim2.fromScale(0.5, 0.055),
				Color = Colors.Gold,
				ZIndex = 113,
				MaxTextSize = 40,
				Parent = stage,
			})
			UIAnimations.pop(banner, { from = 0.4 })
		end

		-- 1) tap phase
		local holder, title, counter = self:_tapPhase(stage, fx, maid)
		title:Destroy()
		counter:Destroy()

		-- 2) explosion
		self:_explode(stage, fx, holder)

		-- 3) reveals
		local flyTargets = {}
		if #results == 1 then
			local reveal = self:_reveal(stage, fx, results[1], {})
			waitForTap(stage, CLAIM_TIMEOUT, nil, 0.6)
			reveal.Cleanup()
			table.insert(flyTargets, reveal.Icon)
		else
			local skipFlag = { value = false }
			local skipButton = UIKit.create("TextButton", {
				Name = "Skip",
				Text = "",
				AutoButtonColor = false,
				AnchorPoint = Vector2.new(1, 1),
				Position = UDim2.fromScale(0.96, 0.94),
				Size = UDim2.fromScale(0.13, 0.08),
				ZIndex = 145,
				Parent = stage,
			})
			UIKit.gradient(skipButton, Colors.RedButtonTop, Colors.RedButtonBottom)
			UIKit.corner(skipButton, UDim.new(0.3, 0))
			UIKit.stroke(skipButton, 3)
			UIKit.aspect(skipButton, 2.4)
			UIKit.label({
				Text = "SKIP ⏭",
				Size = UDim2.fromScale(0.8, 0.62),
				Position = UDim2.fromScale(0.5, 0.5),
				AnchorPoint = Vector2.new(0.5, 0.5),
				ZIndex = 146,
				Parent = skipButton,
			})
			maid:Give(UIAnimations.bindButton(skipButton))
			maid:Give(skipButton.Activated:Connect(function()
				skipFlag.value = true
			end))

			for i, key in results do
				if skipFlag.value then
					break
				end
				local reveal = self:_reveal(stage, fx, key, { counterText = `{i} / {#results}`, skipFlag = skipFlag })
				local answer = "skip"
				if not skipFlag.value then
					answer = waitForTap(stage, CLAIM_TIMEOUT, skipButton, 0.5)
				end
				reveal.Cleanup()
				local icon = reveal.Icon
				tween(UIAnimations.getScale(icon), 0.2, { Scale = 0 }, Enum.EasingStyle.Back, Enum.EasingDirection.In)
				task.delay(0.22, function()
					icon:Destroy()
				end)
				if answer == "skip" then
					skipFlag.value = true
				end
				task.wait(0.15)
			end
			skipButton:Destroy()

			local panel, icons, stopBreath = self:_summary(stage, results, payload.Cash or 0)
			waitForTap(stage, CLAIM_TIMEOUT + 5, nil, 0.6)
			stopBreath()
			for _, icon in icons do
				table.insert(flyTargets, icon)
			end
			task.delay(0.9, function()
				panel:Destroy()
			end)
		end

		-- 4) claim: reward flies to the HUD while everything fades away
		self.Deps.setHudHidden(false)
		for i, icon in flyTargets do
			self:_flyToHud(icon, fx, (i - 1) * 0.05)
		end
		tween(overlay, 0.45, { BackgroundTransparency = 1 })
		tween(blur, 0.45, { Size = 0 })
		task.wait(0.8 + #flyTargets * 0.05)
	end)
	if not ok then
		warn("[SackMinigame] error:", err)
		self.Deps.setHudHidden(false)
	end
	maid:Clean()
	self.Playing = false
end

return SackMinigame
