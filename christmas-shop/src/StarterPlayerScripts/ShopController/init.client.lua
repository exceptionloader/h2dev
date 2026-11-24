--[[
	ShopController  (LocalScript)
	Explorer:  StarterPlayer > StarterPlayerScripts > ShopController
	Children:  UIKit, Toasts, FriendPicker, SackMinigame (ModuleScripts)

	Builds the whole Christmas Shop UI from code and drives its state:
	shop button, sleigh open sequence, window, sections, hover/press,
	purchases + gifting requests, Santa's Sack minigame queue, toasts,
	vortex close. All looping animations live in `openMaid` and are cleaned
	up when the window closes.
]]

local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local StarterGui = game:GetService("StarterGui")
local TextChatService = game:GetService("TextChatService")
local UserInputService = game:GetService("UserInputService")

local ShopConfig = require(ReplicatedStorage:WaitForChild("ShopConfig"))
local UIAnimations = require(ReplicatedStorage:WaitForChild("UIAnimations"))
local UIKit = require(script:WaitForChild("UIKit"))
local Toasts = require(script:WaitForChild("Toasts"))
local FriendPicker = require(script:WaitForChild("FriendPicker"))
local SackMinigame = require(script:WaitForChild("SackMinigame"))

local LocalPlayer = Players.LocalPlayer
local PlayerGui = LocalPlayer:WaitForChild("PlayerGui")
local Remotes = ReplicatedStorage:WaitForChild("ChristmasShopRemotes")

local Colors = ShopConfig.Colors
local Maid = UIAnimations.Maid
local tween = UIAnimations.tween
local create = UIKit.create

---------------------------------------------------------------------------
-- State
---------------------------------------------------------------------------
-- "closed" | "opening" | "open" | "closing" | "minigame"
local state: string = "closed"
local shopState = {
	Owned = {} :: { [string]: boolean },
	ResetAt = ShopConfig.getNextReset(math.floor(workspace:GetServerTimeNow())),
}
local openMaid = Maid.new() -- everything that only runs while the window is open
local priceButtons: { [string]: any } = {}
local oddsLabels: { [string]: TextLabel } = {}
local bobTargets: { GuiObject } = {}
local raysTargets: { { GuiObject } } = {}
local shimmerGradients: { UIGradient } = {}
local sackQueue = {}
local processingQueue = false

---------------------------------------------------------------------------
-- ScreenGuis
---------------------------------------------------------------------------
for _, name in { "ChristmasShopHUD", "ChristmasShop" } do
	local old = PlayerGui:FindFirstChild(name)
	if old then
		old:Destroy()
	end
end

local hudGui = create("ScreenGui", {
	Name = "ChristmasShopHUD",
	ResetOnSpawn = false,
	ZIndexBehavior = Enum.ZIndexBehavior.Sibling,
	DisplayOrder = 5,
	Parent = PlayerGui,
})
pcall(function()
	(hudGui :: any).ScreenInsets = Enum.ScreenInsets.CoreUISafeInsets
end)

local mainGui = create("ScreenGui", {
	Name = "ChristmasShop",
	ResetOnSpawn = false,
	IgnoreGuiInset = true,
	ZIndexBehavior = Enum.ZIndexBehavior.Sibling,
	DisplayOrder = 50,
	Parent = PlayerGui,
})

local root = create("Frame", {
	Name = "Root",
	BackgroundTransparency = 1,
	Size = UDim2.fromScale(1, 1),
	Parent = mainGui,
})

local fx = create("Frame", {
	Name = "FX",
	BackgroundTransparency = 1,
	Size = UDim2.fromScale(1, 1),
	ZIndex = 60,
	Parent = root,
})

local toasts = Toasts.new(root)
local friendPicker = FriendPicker.new(root)

---------------------------------------------------------------------------
-- HUD hiding (used by the minigame)
---------------------------------------------------------------------------
local hiddenGuis: { ScreenGui } = {}
local hiddenCore: { Enum.CoreGuiType } = {}
local controlsDisabled = false

local function getControls()
	local ok, controls = pcall(function()
		local playerModule = require(LocalPlayer:WaitForChild("PlayerScripts"):WaitForChild("PlayerModule", 2) :: ModuleScript) :: any
		return playerModule:GetControls()
	end)
	return if ok then controls else nil
end

local function setHudHidden(hidden: boolean)
	if hidden then
		hudGui.Enabled = false
		for _, gui in PlayerGui:GetChildren() do
			if gui:IsA("ScreenGui") and gui ~= mainGui and gui ~= hudGui and gui.Enabled then
				gui.Enabled = false
				table.insert(hiddenGuis, gui)
			end
		end
		for _, coreType in { Enum.CoreGuiType.PlayerList, Enum.CoreGuiType.Backpack, Enum.CoreGuiType.Health, Enum.CoreGuiType.EmotesMenu } do
			local ok, enabled = pcall(StarterGui.GetCoreGuiEnabled, StarterGui, coreType)
			if ok and enabled then
				pcall(StarterGui.SetCoreGuiEnabled, StarterGui, coreType, false)
				table.insert(hiddenCore, coreType)
			end
		end
		local controls = getControls()
		if controls then
			controls:Disable()
			controlsDisabled = true
		end
	else
		hudGui.Enabled = true
		for _, gui in hiddenGuis do
			if gui.Parent then
				gui.Enabled = true
			end
		end
		table.clear(hiddenGuis)
		for _, coreType in hiddenCore do
			pcall(StarterGui.SetCoreGuiEnabled, StarterGui, coreType, true)
		end
		table.clear(hiddenCore)
		if controlsDisabled then
			local controls = getControls()
			if controls then
				controls:Enable()
			end
			controlsDisabled = false
		end
	end
end

local minigame = SackMinigame.new(root, {
	setHudHidden = setHudHidden,
	getRewardTarget = function(): Vector2
		-- top-right corner, where the leaderboard (Cash) is
		return Vector2.new(root.AbsoluteSize.X - 110, 48)
	end,
})

---------------------------------------------------------------------------
-- Shop button (HUD, left edge, vertically centered)
---------------------------------------------------------------------------
local shopButton = {}
do
	local holder = create("Frame", {
		Name = "ShopButton",
		BackgroundTransparency = 1,
		AnchorPoint = Vector2.new(0, 0.5),
		Position = UDim2.fromScale(0.012, 0.5),
		Size = UDim2.fromScale(0.11, 0.17),
		Parent = hudGui,
	})
	UIKit.aspect(holder, 1)
	create("UISizeConstraint", { MinSize = Vector2.new(64, 64), MaxSize = Vector2.new(150, 150), Parent = holder })

	local body = create("Frame", {
		Name = "Body",
		BackgroundTransparency = 1,
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.5),
		Size = UDim2.fromScale(1, 1),
		Parent = holder,
	})
	local rays = UIKit.rays({
		Size = UDim2.fromScale(1.45, 1.45),
		Position = UDim2.fromScale(0.5, 0.42),
		ZIndex = 1,
		Transparency = 0.35,
		Count = 9,
		Parent = body,
	})
	local button = create("TextButton", {
		Name = "Button",
		Text = "",
		BackgroundTransparency = 1,
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.5),
		Size = UDim2.fromScale(1, 1),
		ZIndex = 2,
		Parent = body,
	})
	local icon = UIKit.icon("SantaSleigh", {
		Size = UDim2.fromScale(0.95, 0.78),
		Position = UDim2.fromScale(0.5, 0.4),
		ZIndex = 3,
		Parent = button,
	})
	UIKit.label({
		Text = "SHOP",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.88),
		Size = UDim2.fromScale(0.9, 0.28),
		StrokeThickness = 3,
		ZIndex = 4,
		MaxTextSize = 34,
		Parent = button,
	})
	local sale = create("Frame", {
		Name = "Sale",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.86, 0.1),
		Size = UDim2.fromScale(0.52, 0.2),
		Rotation = 14,
		ZIndex = 5,
		Parent = button,
	})
	UIKit.gradient(sale, Color3.fromRGB(255, 236, 90), Color3.fromRGB(255, 180, 20))
	UIKit.corner(sale, UDim.new(0.35, 0))
	UIKit.stroke(sale, 2.5)
	UIKit.label({
		Text = "SALE!",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.5),
		Size = UDim2.fromScale(0.86, 0.8),
		Color = Color3.fromRGB(220, 30, 50),
		StrokeColor = Color3.new(1, 1, 1),
		StrokeThickness = 1.5,
		ZIndex = 6,
		MaxTextSize = 22,
		Parent = sale,
	})

	shopButton.Holder = holder
	shopButton.Body = body
	shopButton.Button = button
	shopButton.Icon = icon
	shopButton.Sale = sale

	-- Idle: rotating glow + breathing (runs for the whole session)
	UIAnimations.raysSpin(rays, 28)
	UIAnimations.breathe(UIAnimations.getScale(body, "BreathScale"), 1.06, 1.6)
	UIAnimations.swing(sale, 6, 1.1)
	UIAnimations.bindButton(button, { hover = 1.12, press = 0.9 })
end

---------------------------------------------------------------------------
-- Window
---------------------------------------------------------------------------
local window = create("CanvasGroup", {
	Name = "ShopWindow",
	BackgroundTransparency = 1,
	AnchorPoint = Vector2.new(0.5, 0.5),
	Position = UDim2.fromScale(0.5, 0.52),
	Size = UDim2.fromScale(0.9, 0.92),
	Visible = false,
	ZIndex = 10,
	Parent = root,
})
UIKit.aspect(window, 1.42)

local panel = create("Frame", {
	Name = "Panel",
	BackgroundTransparency = 1,
	Position = UDim2.fromScale(0.11, 0.07),
	Size = UDim2.fromScale(0.78, 0.86),
	ZIndex = 5,
	Parent = window,
})

-- Body
local body = create("Frame", {
	Name = "Body",
	Position = UDim2.fromScale(0, 0.12),
	Size = UDim2.fromScale(1, 0.88),
	Active = true,
	ZIndex = 5,
	Parent = panel,
})
UIKit.gradient(body, Colors.BodyNavy, Colors.BodyNavyDark)
UIKit.corner(body, UDim.new(0.05, 0))
UIKit.stroke(body, 4)

-- Header
local header = create("Frame", {
	Name = "Header",
	Position = UDim2.fromScale(-0.012, 0.015),
	Size = UDim2.fromScale(1.024, 0.155),
	ZIndex = 7,
	Parent = panel,
})
UIKit.gradient(header, Colors.HeaderRed, Colors.HeaderRedDark)
UIKit.corner(header, UDim.new(0.22, 0))
UIKit.stroke(header, 4)
do
	-- subtle brick / knit pattern
	local pattern = create("Frame", {
		Name = "Pattern",
		BackgroundTransparency = 1,
		ClipsDescendants = true,
		Position = UDim2.fromScale(0.015, 0.14),
		Size = UDim2.fromScale(0.97, 0.76),
		ZIndex = 7,
		Parent = header,
	})
	local bricks = 18
	for row = 0, 1 do
		local rowFrame = create("Frame", {
			BackgroundTransparency = 1,
			Position = UDim2.fromScale(if row == 1 then -0.5 / bricks else 0, row * 0.5),
			Size = UDim2.fromScale(1 + 1 / bricks, 0.5),
			ZIndex = 7,
			Parent = pattern,
		})
		create("UIListLayout", {
			FillDirection = Enum.FillDirection.Horizontal,
			Padding = UDim.new(0.004, 0),
			VerticalAlignment = Enum.VerticalAlignment.Center,
			Parent = rowFrame,
		})
		for _ = 1, bricks + 1 do
			local brick = create("Frame", {
				BackgroundColor3 = Colors.HeaderRedDark,
				BackgroundTransparency = 0.55,
				Size = UDim2.fromScale(1 / (bricks + 1) - 0.004, 0.84),
				ZIndex = 7,
				Parent = rowFrame,
			})
			UIKit.corner(brick, UDim.new(0.2, 0))
		end
	end

	-- snow cap dripping over the top edge
	local snow = create("Frame", {
		Name = "SnowCap",
		Position = UDim2.fromScale(-0.008, -0.32),
		Size = UDim2.fromScale(1.016, 0.5),
		ZIndex = 9,
		Parent = header,
	})
	UIKit.gradient(snow, Colors.Snow, Colors.SnowShade)
	UIKit.corner(snow, UDim.new(0.5, 0))
	UIKit.stroke(snow, 2.5, Color3.fromRGB(150, 180, 220))
	local drips = { { 0.07, 0.5 }, { 0.16, 0.7 }, { 0.3, 0.45 }, { 0.41, 0.62 }, { 0.55, 0.5 }, { 0.66, 0.75 }, { 0.78, 0.48 }, { 0.9, 0.66 } }
	for _, drip in drips do
		local d = create("Frame", {
			AnchorPoint = Vector2.new(0.5, 0.5),
			Position = UDim2.fromScale(drip[1], 0.82),
			Size = UDim2.fromScale(drip[2] * 0.08, drip[2] * 0.9),
			BackgroundColor3 = Colors.Snow,
			ZIndex = 9,
			Parent = snow,
		})
		UIKit.corner(d, UDim.new(1, 0))
	end

	UIKit.icon("HollyCorner", {
		Size = UDim2.fromScale(0.13, 1.15),
		Position = UDim2.fromScale(0.03, 0.05),
		ZIndex = 10,
		Parent = header,
	})
	UIKit.label({
		Name = "Title",
		Text = "CHRISTMAS SHOP",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.58),
		Size = UDim2.fromScale(0.62, 0.66),
		StrokeThickness = 3.5,
		ZIndex = 10,
		MaxTextSize = 64,
		Parent = header,
	})
end

local closeButton = UIKit.closeButton({
	Size = UDim2.fromScale(0.08, 0.72),
	Position = UDim2.fromScale(0.95, 0.58),
	AnchorPoint = Vector2.new(0.5, 0.5),
	ZIndex = 10,
	Parent = header,
})
UIAnimations.bindButton(closeButton, {
	hover = 1.12,
	press = 0.88,
	onHover = function(hovered)
		tween(closeButton, 0.3, { Rotation = if hovered then 90 else 0 }, Enum.EasingStyle.Back, Enum.EasingDirection.Out)
	end,
})

-- Decorations around the frame
local snowman = UIKit.icon("Snowman", {
	Size = UDim2.fromScale(0.15, 0.32),
	Position = UDim2.fromScale(0.095, 0.8),
	ZIndex = 3, -- behind the panel: "peeking"
	Parent = window,
})
local tree = UIKit.icon("ChristmasTree", {
	Size = UDim2.fromScale(0.14, 0.3),
	Position = UDim2.fromScale(0.905, 0.82),
	ZIndex = 8,
	Parent = window,
})
local bells = UIKit.icon("JingleBells", {
	Size = UDim2.fromScale(0.1, 0.18),
	Position = UDim2.fromScale(0.915, 0.32),
	ZIndex = 8,
	Parent = window,
})

-- Snow puff clouds that pop up along the bottom when the window opens
local cloudRow = create("Frame", {
	Name = "CloudRow",
	BackgroundTransparency = 1,
	AnchorPoint = Vector2.new(0.5, 1),
	Position = UDim2.fromScale(0.5, 1),
	Size = UDim2.fromScale(0.94, 0.12),
	ZIndex = 9,
	Parent = window,
})
local clouds = {}
for i = 1, 7 do
	local cloud = UIAnimations.makeCloud()
	cloud.AnchorPoint = Vector2.new(0.5, 1)
	cloud.Position = UDim2.fromScale((i - 0.5) / 7, 1)
	cloud.Size = UDim2.fromScale(0.2, if i % 2 == 0 then 0.85 else 1)
	cloud.ZIndex = 9
	cloud.Visible = false
	cloud.Parent = cloudRow
	clouds[i] = cloud
end

-- Scrolling content
local scroll = create("ScrollingFrame", {
	Name = "Content",
	BackgroundTransparency = 1,
	Position = UDim2.fromScale(0.025, 0.035),
	Size = UDim2.fromScale(0.95, 0.93),
	CanvasSize = UDim2.new(),
	AutomaticCanvasSize = Enum.AutomaticSize.Y,
	ScrollingDirection = Enum.ScrollingDirection.Y,
	ScrollBarThickness = 5,
	ScrollBarImageColor3 = Colors.Gold,
	ScrollBarImageTransparency = 0.2,
	ElasticBehavior = Enum.ElasticBehavior.WhenScrollable,
	VerticalScrollBarInset = Enum.ScrollBarInset.ScrollBar,
	ZIndex = 6,
	Parent = body,
})
create("UIListLayout", {
	SortOrder = Enum.SortOrder.LayoutOrder,
	HorizontalAlignment = Enum.HorizontalAlignment.Center,
	Padding = UDim.new(0, 8),
	Parent = scroll,
})
create("UIPadding", {
	PaddingTop = UDim.new(0, 6),
	PaddingBottom = UDim.new(0, 10),
	PaddingLeft = UDim.new(0, 4),
	PaddingRight = UDim.new(0, 4),
	Parent = scroll,
})

-- Rows get an offset height derived from the scroll width (aspect ratio),
-- because scale heights inside an auto-sized canvas are circular.
local rows: { Frame } = {}
local function addRow(name: string, ratio: number, order: number): Frame
	local row = create("Frame", {
		Name = name,
		BackgroundTransparency = 1,
		LayoutOrder = order,
		Size = UDim2.new(1, 0, 0, 100),
		ZIndex = 6,
		Parent = scroll,
	})
	row:SetAttribute("Ratio", ratio)
	table.insert(rows, row)
	return row
end

local function relayout()
	local width = scroll.AbsoluteSize.X - scroll.ScrollBarThickness - 8
	if width <= 0 then
		return
	end
	for _, row in rows do
		local ratio = row:GetAttribute("Ratio") :: number
		row.Size = UDim2.new(1, -scroll.ScrollBarThickness, 0, math.floor(width / ratio))
	end
end
scroll:GetPropertyChangedSignal("AbsoluteSize"):Connect(relayout)

-- Card helper: a slot in the layout + a centered card that can "lift" on hover.
local function makeCard(parent: GuiObject, slotSize: UDim2, order: number, top: Color3, bottom: Color3): (Frame, Frame)
	local slot = create("Frame", {
		Name = "Slot",
		BackgroundTransparency = 1,
		Size = slotSize,
		LayoutOrder = order,
		ZIndex = 6,
		Parent = parent,
	})
	local card = create("Frame", {
		Name = "Card",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.5),
		Size = UDim2.fromScale(1, 1),
		ZIndex = 6,
		Parent = slot,
	})
	UIKit.gradient(card, top, bottom)
	UIKit.corner(card, UDim.new(0.09, 0))
	UIKit.stroke(card, 3.5)
	local lift = UIAnimations.getScale(card, "LiftScale")
	card.MouseEnter:Connect(function()
		tween(lift, 0.2, { Scale = 1.04 }, Enum.EasingStyle.Back, Enum.EasingDirection.Out)
	end)
	card.MouseLeave:Connect(function()
		tween(lift, 0.2, { Scale = 1 }, Enum.EasingStyle.Back, Enum.EasingDirection.Out)
	end)
	return slot, card
end

local function horizontalList(parent: GuiObject, padding: number)
	create("UIListLayout", {
		FillDirection = Enum.FillDirection.Horizontal,
		HorizontalAlignment = Enum.HorizontalAlignment.Center,
		VerticalAlignment = Enum.VerticalAlignment.Center,
		SortOrder = Enum.SortOrder.LayoutOrder,
		Padding = UDim.new(padding, 0),
		Parent = parent,
	})
end

---------------------------------------------------------------------------
-- Purchases
---------------------------------------------------------------------------
local purchaseCooldownUntil = 0

local function requestPurchase(itemKey: string, recipientUserId: number?)
	if os.clock() < purchaseCooldownUntil then
		return
	end
	purchaseCooldownUntil = os.clock() + 0.6
	Remotes.PurchaseRequest:FireServer(itemKey, recipientUserId)
end

local function wirePriceButton(itemKey: string, api)
	priceButtons[itemKey] = api
	UIAnimations.bindButton(api.Button, { hover = 1.08, press = 0.94 })
	api.Button.Activated:Connect(function()
		if state ~= "open" then
			return
		end
		if api.Owned then
			toasts:Show("You already own this!", "Info")
			return
		end
		requestPurchase(itemKey)
	end)
	if api.GiftButton then
		UIAnimations.bindButton(api.GiftButton, { hover = 1.12, press = 0.9 })
		api.GiftButton.Activated:Connect(function()
			if state ~= "open" then
				return
			end
			local def = ShopConfig.getItem(itemKey)
			friendPicker:Open(def.DisplayName, function(userId)
				requestPurchase(itemKey, userId)
			end)
		end)
	end
end

---------------------------------------------------------------------------
-- Section A: Santa's Sack
---------------------------------------------------------------------------
local timerLabel: TextLabel
do
	local row = addRow("SantasSack", 2.6, 1)
	local _, card = makeCard(row, UDim2.fromScale(1, 1), 1, Colors.SackBlueTop, Colors.SackBlueBottom)

	local rays = UIKit.rays({
		Size = UDim2.fromScale(0.36, 0.98),
		Position = UDim2.fromScale(0.15, 0.5),
		Color = Color3.fromRGB(255, 244, 190),
		Transparency = 0.55,
		Count = 10,
		ZIndex = 6,
		Parent = card,
	})
	table.insert(raysTargets, { rays })
	local sack = UIKit.icon("SantaSack", {
		Size = UDim2.fromScale(0.27, 0.9),
		Position = UDim2.fromScale(0.15, 0.52),
		ZIndex = 8,
		Parent = card,
	})
	table.insert(bobTargets, sack)

	UIKit.label({
		Text = "SANTA'S SACK",
		Position = UDim2.fromScale(0.31, 0.04),
		Size = UDim2.fromScale(0.42, 0.2),
		XAlign = Enum.TextXAlignment.Left,
		Color = Colors.Gold,
		StrokeThickness = 3,
		ZIndex = 8,
		MaxTextSize = 44,
		Parent = card,
	})
	timerLabel = UIKit.label({
		Name = "Timer",
		Text = "00:00:00",
		AnchorPoint = Vector2.new(1, 0),
		Position = UDim2.fromScale(0.975, 0.05),
		Size = UDim2.fromScale(0.22, 0.17),
		XAlign = Enum.TextXAlignment.Right,
		Color = Colors.TimerRed,
		StrokeThickness = 2.5,
		ZIndex = 8,
		MaxTextSize = 38,
		Parent = card,
	})

	local tiles = create("Frame", {
		Name = "Tiles",
		BackgroundTransparency = 1,
		Position = UDim2.fromScale(0.31, 0.27),
		Size = UDim2.fromScale(0.665, 0.37),
		ZIndex = 7,
		Parent = card,
	})
	horizontalList(tiles, 0.02)
	local odds = ShopConfig.getLootOdds(false)
	for i, loot in ShopConfig.Loot do
		local tile = create("Frame", {
			Name = loot.Key,
			Size = UDim2.fromScale(0.235, 1),
			LayoutOrder = i,
			ZIndex = 7,
			Parent = tiles,
		})
		UIKit.gradient(tile, loot.TileTop, loot.TileBottom)
		UIKit.corner(tile, UDim.new(0.16, 0))
		UIKit.stroke(tile, 3)
		UIKit.gloss(tile, 0.16)
		local icon = UIKit.icon(loot.Icon, {
			Size = UDim2.fromScale(0.82, 0.82),
			Position = UDim2.fromScale(0.5, 0.46),
			ZIndex = 9,
			Parent = tile,
		})
		table.insert(bobTargets, icon)
		oddsLabels[loot.Key] = UIKit.label({
			Name = "Odds",
			Text = ShopConfig.formatOdds(odds[loot.Key]),
			AnchorPoint = Vector2.new(1, 1),
			Position = UDim2.fromScale(0.96, 0.98),
			Size = UDim2.fromScale(0.55, 0.3),
			XAlign = Enum.TextXAlignment.Right,
			StrokeThickness = 2,
			ZIndex = 10,
			MaxTextSize = 22,
			Parent = tile,
		})
		if loot.Shimmer then
			local shine = create("Frame", {
				Name = "Shimmer",
				BackgroundColor3 = Color3.new(1, 1, 1),
				Size = UDim2.fromScale(1, 1),
				ZIndex = 9,
				Parent = tile,
			})
			UIKit.corner(shine, UDim.new(0.16, 0))
			local gradient = create("UIGradient", {
				Rotation = 25,
				Offset = Vector2.new(-1, 0),
				Transparency = NumberSequence.new({
					NumberSequenceKeypoint.new(0, 1),
					NumberSequenceKeypoint.new(0.4, 1),
					NumberSequenceKeypoint.new(0.5, 0.35),
					NumberSequenceKeypoint.new(0.6, 1),
					NumberSequenceKeypoint.new(1, 1),
				}),
				Parent = shine,
			})
			table.insert(shimmerGradients, gradient)
		end
	end

	local buttons = create("Frame", {
		Name = "Buttons",
		BackgroundTransparency = 1,
		Position = UDim2.fromScale(0.31, 0.67),
		Size = UDim2.fromScale(0.665, 0.31),
		ZIndex = 7,
		Parent = card,
	})
	horizontalList(buttons, 0.02)
	for i, key in ShopConfig.SackOrder do
		local def = ShopConfig.Products[key]
		local api = UIKit.priceButton({
			Price = def.Price,
			Sub = def.Label,
			Gift = true,
			Size = UDim2.fromScale(0.32, 1),
			LayoutOrder = i,
			ZIndex = 8,
			Parent = buttons,
		})
		wirePriceButton(key, api)
	end
end

---------------------------------------------------------------------------
-- Section B: Gamepasses
---------------------------------------------------------------------------
do
	local divider = UIKit.divider("GAMEPASSES", { LayoutOrder = 2, ZIndex = 6, Parent = scroll })
	divider:SetAttribute("Ratio", 16)
	table.insert(rows, divider)
end
do
	local row = addRow("GamePasses", 3.3, 3)
	horizontalList(row, 0.02)
	for i, passKey in ShopConfig.PassOrder do
		local def = ShopConfig.GamePasses[passKey]
		local top, bottom = Colors.PassBlueTop, Colors.PassBlueBottom
		if def.Theme == "Green" then
			top, bottom = Colors.PassGreenTop, Colors.PassGreenBottom
		end
		local _, card = makeCard(row, UDim2.fromScale(0.49, 1), i, top, bottom)
		UIKit.gloss(card, 0.12)
		local icon = UIKit.icon(def.Icon, {
			Size = UDim2.fromScale(0.4, 0.9),
			Position = UDim2.fromScale(0.21, 0.5),
			ZIndex = 9,
			Parent = card,
		})
		table.insert(bobTargets, icon)
		UIKit.label({
			Text = def.Title,
			Position = UDim2.fromScale(0.42, 0.07),
			Size = UDim2.fromScale(0.55, 0.26),
			XAlign = Enum.TextXAlignment.Left,
			StrokeThickness = 3,
			ZIndex = 9,
			MaxTextSize = 44,
			Parent = card,
		})
		UIKit.label({
			Text = def.Line1,
			Position = UDim2.fromScale(0.42, 0.33),
			Size = UDim2.fromScale(0.55, 0.15),
			XAlign = Enum.TextXAlignment.Left,
			ZIndex = 9,
			MaxTextSize = 26,
			Parent = card,
		})
		UIKit.label({
			Text = def.Line2,
			Position = UDim2.fromScale(0.42, 0.47),
			Size = UDim2.fromScale(0.55, 0.17),
			XAlign = Enum.TextXAlignment.Left,
			Color = Colors.Gold,
			ZIndex = 9,
			MaxTextSize = 30,
			Parent = card,
		})
		local api = UIKit.priceButton({
			Price = def.Price,
			Gift = true,
			Position = UDim2.fromScale(0.42, 0.68),
			Size = UDim2.fromScale(0.55, 0.25),
			ZIndex = 9,
			Parent = card,
		})
		wirePriceButton(passKey, api)
	end
end

---------------------------------------------------------------------------
-- Section C: Cash
---------------------------------------------------------------------------
do
	local divider = UIKit.divider("CASH", { LayoutOrder = 4, ZIndex = 6, Parent = scroll })
	divider:SetAttribute("Ratio", 16)
	table.insert(rows, divider)
end
do
	local row = addRow("Cash", 2.75, 5)
	horizontalList(row, 0.02)
	for i, key in ShopConfig.CashOrder do
		local def = ShopConfig.Products[key]
		local _, card = makeCard(row, UDim2.fromScale(0.32, 1), i, Colors.CashPurpleTop, Colors.CashPurpleBottom)
		local rays = UIKit.rays({
			Size = UDim2.fromScale(0.95, 0.8),
			Position = UDim2.fromScale(0.5, 0.48),
			Color = Color3.fromRGB(255, 210, 255),
			Transparency = 0.6,
			Count = 10,
			ZIndex = 6,
			Parent = card,
		})
		table.insert(raysTargets, { rays })
		UIKit.label({
			Text = def.Title,
			AnchorPoint = Vector2.new(0.5, 0),
			Position = UDim2.fromScale(0.5, 0.04),
			Size = UDim2.fromScale(0.92, 0.18),
			Color = Colors.Gold,
			StrokeThickness = 3,
			ZIndex = 9,
			MaxTextSize = 34,
			Parent = card,
		})
		local scale = def.IconScale or 1
		local icon = UIKit.icon(def.Icon, {
			Size = UDim2.fromScale(0.55 * scale, 0.5 * scale),
			Position = UDim2.fromScale(0.5, 0.47),
			ZIndex = 9,
			Parent = card,
		})
		table.insert(bobTargets, icon)
		local api = UIKit.priceButton({
			Price = def.Price,
			Gift = true,
			Position = UDim2.fromScale(0.06, 0.72),
			Size = UDim2.fromScale(0.88, 0.23),
			ZIndex = 9,
			Parent = card,
		})
		wirePriceButton(key, api)
	end
end

relayout()

---------------------------------------------------------------------------
-- State updates from the server
---------------------------------------------------------------------------
local function applyShopState()
	for passKey in ShopConfig.GamePasses do
		local api = priceButtons[passKey]
		if api then
			api.SetOwned(shopState.Owned[passKey] == true)
		end
	end
	local hasLuck = shopState.Owned.Luck == true
	local odds = ShopConfig.getLootOdds(hasLuck)
	for key, label in oddsLabels do
		label.Text = ShopConfig.formatOdds(odds[key])
		label.TextColor3 = if hasLuck then Colors.Gold else Colors.Text
	end
end

local lastTimerSecond = -1
local function updateTimer()
	local now = workspace:GetServerTimeNow()
	if shopState.ResetAt <= now then
		shopState.ResetAt = ShopConfig.getNextReset(math.floor(now))
	end
	local remaining = math.max(0, math.floor(shopState.ResetAt - now))
	if remaining == lastTimerSecond then
		return
	end
	lastTimerSecond = remaining
	timerLabel.Text = string.format("%02d:%02d:%02d", remaining // 3600, (remaining % 3600) // 60, remaining % 60)
end

---------------------------------------------------------------------------
-- Loops that only run while the window is open
---------------------------------------------------------------------------
local function startOpenLoops()
	openMaid:Clean()
	for _, entry in raysTargets do
		openMaid:Give(UIAnimations.raysSpin(entry[1], 18))
	end
	for i, target in bobTargets do
		openMaid:Give(UIAnimations.bob(target, 0.05, 2 + (i % 3) * 0.35, i * 0.7))
	end
	openMaid:Give(UIAnimations.swing(bells, 9, 1.8))
	openMaid:Give(UIAnimations.swing(snowman, 5, 1.3, 0.4))
	openMaid:Give(UIAnimations.swing(tree, 1.5, 2.6))
	local shimmerTime = 0
	openMaid:Give(UIAnimations.addLoop(function(dt)
		shimmerTime = (shimmerTime + dt) % 2.4
		local x = math.clamp(shimmerTime / 1.1, 0, 1) * 2 - 1
		for _, gradient in shimmerGradients do
			gradient.Offset = Vector2.new(x, 0)
		end
		updateTimer()
	end))
	updateTimer()
end

---------------------------------------------------------------------------
-- Open sequence
---------------------------------------------------------------------------
local function popClouds()
	for i, cloud in clouds do
		task.delay((i - 1) * 0.035, function()
			UIAnimations.pop(cloud, { from = 0.1, peak = 1.15, duration = 0.35 })
		end)
		task.delay(2 + i * 0.05, function()
			if cloud.Visible then
				local tw = tween(UIAnimations.getScale(cloud), 0.3, { Scale = 0 }, Enum.EasingStyle.Back, Enum.EasingDirection.In)
				tw.Completed:Wait()
				cloud.Visible = false
			end
		end)
	end
end

local function windowBounds()
	local pos = window.AbsolutePosition - fx.AbsolutePosition
	local size = window.AbsoluteSize
	return {
		left = pos.X + size.X * 0.11,
		right = pos.X + size.X * 0.89,
		top = pos.Y + size.Y * 0.05,
		bottom = pos.Y + size.Y * 0.95,
	}
end

local function buildMiniCard(): Frame
	local card = create("Frame", {
		Name = "MiniShop",
		AnchorPoint = Vector2.new(0.5, 0.5),
		BackgroundColor3 = Colors.BodyNavy,
		ZIndex = 19,
	})
	UIKit.corner(card, UDim.new(0.12, 0))
	UIKit.stroke(card, 2)
	local top = create("Frame", { Size = UDim2.fromScale(1, 0.26), ZIndex = 20, Parent = card })
	UIKit.gradient(top, Colors.HeaderRed, Colors.HeaderRedDark)
	UIKit.corner(top, UDim.new(0.3, 0))
	local snow = create("Frame", {
		Position = UDim2.fromScale(0, -0.06),
		Size = UDim2.fromScale(1, 0.1),
		BackgroundColor3 = Colors.Snow,
		ZIndex = 21,
		Parent = card,
	})
	UIKit.corner(snow, UDim.new(1, 0))
	local hints = {
		{ 0.06, 0.34, 0.88, 0.26, Colors.SackBlueBottom },
		{ 0.06, 0.66, 0.42, 0.26, Colors.PassBlueBottom },
		{ 0.52, 0.66, 0.42, 0.26, Colors.PassGreenBottom },
	}
	for _, h in hints do
		local f = create("Frame", {
			Position = UDim2.fromScale(h[1], h[2]),
			Size = UDim2.fromScale(h[3], h[4]),
			BackgroundColor3 = h[5],
			ZIndex = 20,
			Parent = card,
		})
		UIKit.corner(f, UDim.new(0.2, 0))
	end
	return card
end

local function openShop(fast: boolean?)
	if state ~= "closed" then
		return
	end
	state = "opening"
	window.Rotation = 0
	window.GroupTransparency = 0

	if fast or UIAnimations.isReducedMotion() then
		window.Visible = true
		UIAnimations.pop(window, { from = 0.8, peak = 1.03, duration = 0.3 })
		startOpenLoops()
		task.wait(0.3)
		state = "open"
		return
	end

	-- 1) sleigh launches out of the button and flies across the top
	local btnIcon = shopButton.Icon :: GuiObject
	local start = UIAnimations.centerIn(btnIcon, fx)
	local iconSize = btnIcon.AbsoluteSize
	btnIcon.Visible = false
	local sleigh = UIKit.icon("SantaSleigh", {
		Name = "FlyingSleigh",
		Size = UDim2.fromOffset(iconSize.X, iconSize.Y),
		Position = UDim2.fromOffset(start.X, start.Y),
		ZIndex = 20,
		Parent = fx,
	})
	local screen = fx.AbsoluteSize
	local p0 = start
	local p1 = Vector2.new(screen.X * 0.3, -screen.Y * 0.02)
	local p2 = Vector2.new(screen.X + iconSize.X * 2, screen.Y * 0.1)
	local stopTrail = UIAnimations.afterimageTrail(sleigh, fx, { interval = 0.03, fade = 0.22, startTransparency = 0.4, maxGhosts = 5 })

	local FLIGHT = 0.8
	local FALL = 0.35
	local landed = false
	local dropped = false
	local elapsed = 0
	local previous = p0

	local function land(center: Vector2)
		local size = window.AbsoluteSize
		local bounds = windowBounds() -- measure before the pop shrinks the window
		UIAnimations.burst(fx, center, math.min(size.X, size.Y) * 0.3, { lines = 16 })
		popClouds()
		window.Visible = true
		UIAnimations.pop(window, { from = 0.6, peak = 1.05, duration = 0.38 })
		startOpenLoops()
		openMaid:Give(UIAnimations.confetti(fx, bounds, 1.5))
		btnIcon.Visible = true
		UIAnimations.pop(btnIcon, { from = 0.3, peak = 1.15 })
		landed = true
	end

	local function dropCard(from: Vector2)
		local target = UIAnimations.centerIn(window, fx)
		local finalWidth = window.AbsoluteSize.X * 0.22
		local card = buildMiniCard()
		card.Position = UDim2.fromOffset(from.X, from.Y + iconSize.Y * 0.3)
		card.Size = UDim2.fromOffset(finalWidth * 0.2, finalWidth * 0.15)
		card.Rotation = -18
		card.Parent = fx
		tween(card, FALL, {
			Position = UDim2.fromOffset(target.X, target.Y),
			Size = UDim2.fromOffset(finalWidth, finalWidth * 0.75),
			Rotation = 0,
		}, Enum.EasingStyle.Quad, Enum.EasingDirection.In)
		task.wait(FALL)
		card:Destroy()
		land(target)
	end

	local stopFlight
	stopFlight = UIAnimations.addLoop(function(dt)
		elapsed += dt
		local t = math.clamp(elapsed / FLIGHT, 0, 1)
		local e = t * t * (3 - 2 * t)
		local pos = (1 - e) ^ 2 * p0 + 2 * (1 - e) * e * p1 + e ^ 2 * p2
		local velocity = pos - previous
		previous = pos
		sleigh.Position = UDim2.fromOffset(pos.X, pos.Y)
		if velocity.Magnitude > 0.01 then
			sleigh.Rotation = math.clamp(math.deg(math.atan2(velocity.Y, math.abs(velocity.X))) * 0.6, -22, 22)
		end
		local grow = 1 + 0.5 * math.sin(t * math.pi)
		sleigh.Size = UDim2.fromOffset(iconSize.X * grow, iconSize.Y * grow)
		if not dropped and t >= 0.5 then
			dropped = true
			task.spawn(dropCard, pos)
		end
		if t >= 1 then
			stopFlight()
			stopTrail()
			sleigh:Destroy()
		end
	end)

	local deadline = os.clock() + 3
	while not landed and os.clock() < deadline do
		task.wait()
	end
	if not landed then
		-- safety net (e.g. frame drops): just show the window
		stopFlight()
		stopTrail()
		if sleigh.Parent then
			sleigh:Destroy()
		end
		btnIcon.Visible = true
		window.Visible = true
		UIAnimations.pop(window)
		startOpenLoops()
	end
	task.wait(0.38)
	if (state :: string) == "opening" then -- may have changed while we waited
		state = "open"
	end
end

---------------------------------------------------------------------------
-- Close sequence
---------------------------------------------------------------------------
local function rippleShopButton()
	local center = UIAnimations.centerIn(shopButton.Button, fx)
	UIAnimations.ripple(fx, center, shopButton.Button.AbsoluteSize.X * 0.8, { grow = 2.4 })
end

local function closeShop(fast: boolean?)
	if state ~= "open" then
		return
	end
	state = "closing"
	task.spawn(function()
		friendPicker:Close()
	end)
	openMaid:Clean()
	UIAnimations.clearParticles(fx)
	for _, cloud in clouds do
		cloud.Visible = false
	end

	if fast then
		local tw = tween(window, 0.18, { GroupTransparency = 1 })
		tw.Completed:Wait()
		window.Visible = false
		window.GroupTransparency = 0
	else
		UIAnimations.vortexClose(window, fx)
		-- Holder has no other UIScale (Body breathes, Button hovers), so pop that one
		UIAnimations.pop(shopButton.Holder, { from = 0.4, peak = 1.15, duration = 0.4 })
		rippleShopButton()
	end
	state = "closed"
end

local function toggleShop()
	if state == "closed" then
		openShop()
	elseif state == "open" then
		closeShop()
	end
end

shopButton.Button.Activated:Connect(function()
	rippleShopButton()
	task.spawn(toggleShop)
end)
closeButton.Activated:Connect(function()
	task.spawn(closeShop)
end)
UserInputService.InputBegan:Connect(function(input, processed)
	if processed then
		return
	end
	if input.KeyCode == ShopConfig.ToggleKey or input.KeyCode == ShopConfig.GamepadToggleKey then
		task.spawn(toggleShop)
	end
end)

---------------------------------------------------------------------------
-- Santa's Sack results -> minigame queue
---------------------------------------------------------------------------
local function processSackQueue()
	if processingQueue then
		return
	end
	processingQueue = true
	local reopen = false
	while #sackQueue > 0 do
		while state == "opening" or state == "closing" do
			task.wait()
		end
		if state == "open" then
			reopen = true
			closeShop(true)
		end
		local payload = table.remove(sackQueue, 1)
		state = "minigame"
		minigame:Play(payload)
		Remotes.SackRevealDone:FireServer(payload.BatchId)
		state = "closed"
	end
	processingQueue = false
	if reopen then
		openShop(true) -- the shop pops back in
	end
end

Remotes.SackResults.OnClientEvent:Connect(function(payload)
	if type(payload) ~= "table" or type(payload.Results) ~= "table" or #payload.Results == 0 then
		return
	end
	for _, key in payload.Results do
		if not ShopConfig.getLoot(key) then
			return
		end
	end
	table.insert(sackQueue, payload)
	task.spawn(processSackQueue)
end)

---------------------------------------------------------------------------
-- Other remotes
---------------------------------------------------------------------------
Remotes.ShopState.OnClientEvent:Connect(function(newState)
	if type(newState) ~= "table" then
		return
	end
	if type(newState.Owned) == "table" then
		shopState.Owned = newState.Owned
	end
	if type(newState.ResetAt) == "number" then
		shopState.ResetAt = newState.ResetAt
	end
	applyShopState()
	updateTimer()
end)

Remotes.Notify.OnClientEvent:Connect(function(data)
	if type(data) ~= "table" or type(data.Text) ~= "string" then
		return
	end
	toasts:Show(data.Text, data.Kind, data.Icon)
	if data.Kind == "Success" and state == "open" then
		openMaid:Give(UIAnimations.confetti(fx, windowBounds(), 0.9, { rate = 55 }))
		UIAnimations.burst(fx, UIAnimations.centerIn(window, fx), window.AbsoluteSize.Y * 0.25, { color = Colors.Gold })
	end
end)

local function systemMessage(text: string)
	local ok = pcall(function()
		if TextChatService.ChatVersion == Enum.ChatVersion.TextChatService then
			local channels = TextChatService:FindFirstChild("TextChannels")
			local general = channels and channels:FindFirstChild("RBXGeneral")
			if general and general:IsA("TextChannel") then
				general:DisplaySystemMessage(`<font color="#FFD640"><b>{text}</b></font>`)
			end
		else
			StarterGui:SetCore("ChatMakeSystemMessage", {
				Text = text,
				Color = Colors.Gold,
				Font = Enum.Font.GothamBold,
			})
		end
	end)
	if not ok then
		warn("[ShopController] could not post chat announcement")
	end
end

Remotes.Announce.OnClientEvent:Connect(function(data)
	if type(data) ~= "table" or type(data.Text) ~= "string" then
		return
	end
	systemMessage(data.Text)
	toasts:Show(data.Text, "Announce", "GoldenSnowflake")
end)

---------------------------------------------------------------------------
-- Ready
---------------------------------------------------------------------------
applyShopState()
updateTimer()
Remotes.ClientReady:FireServer()
