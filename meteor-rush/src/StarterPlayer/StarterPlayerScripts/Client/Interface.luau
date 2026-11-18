-- Komplettes HUD & Menüs (alles im Code gebaut – keine externen Assets nötig)
local Players = game:GetService("Players")
local TweenService = game:GetService("TweenService")
local MarketplaceService = game:GetService("MarketplaceService")
local Lighting = game:GetService("Lighting")
local Workspace = game:GetService("Workspace")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local ProximityPromptService = game:GetService("ProximityPromptService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Util = require(Shared:WaitForChild("Util"))

local player = Players.LocalPlayer
local Interface = { State = nil, Panels = {}, Refreshers = {} }

---------------------------------------------------------------------------
-- Theme
---------------------------------------------------------------------------
local T = {
	Bg = Color3.fromRGB(16, 18, 34),
	Bg2 = Color3.fromRGB(28, 24, 58),
	Card = Color3.fromRGB(34, 36, 62),
	Text = Color3.fromRGB(240, 242, 255),
	Sub = Color3.fromRGB(170, 175, 205),
	Cash = Color3.fromRGB(110, 255, 150),
	Accent1 = Color3.fromRGB(255, 150, 60),
	Accent2 = Color3.fromRGB(255, 70, 140),
	Green1 = Color3.fromRGB(90, 230, 120),
	Green2 = Color3.fromRGB(30, 170, 90),
	Grey1 = Color3.fromRGB(90, 94, 120),
	Grey2 = Color3.fromRGB(60, 62, 86),
	Font = Enum.Font.FredokaOne,
	Font2 = Enum.Font.GothamBold,
}

local function new(class, props, children)
	local inst = Instance.new(class)
	local parent
	for k, v in pairs(props or {}) do
		if k == "Parent" then
			parent = v
		else
			inst[k] = v
		end
	end
	for _, c in ipairs(children or {}) do
		c.Parent = inst
	end
	if parent then
		inst.Parent = parent
	end
	return inst
end

local function corner(r)
	return new("UICorner", { CornerRadius = UDim.new(0, r or 14) })
end
local function stroke(color, th, tr)
	return new("UIStroke", { Color = color or Color3.new(0, 0, 0), Thickness = th or 2, Transparency = tr or 0, ApplyStrokeMode = Enum.ApplyStrokeMode.Border })
end
local function textStroke(th)
	return new("UIStroke", { Color = Color3.fromRGB(8, 8, 18), Thickness = th or 2, ApplyStrokeMode = Enum.ApplyStrokeMode.Contextual })
end
local function grad(c1, c2, rot)
	return new("UIGradient", { Color = ColorSequence.new(c1, c2), Rotation = rot or 90 })
end
local function pad(px)
	return new("UIPadding", { PaddingLeft = UDim.new(0, px), PaddingRight = UDim.new(0, px), PaddingTop = UDim.new(0, px), PaddingBottom = UDim.new(0, px) })
end

local function label(props)
	props.BackgroundTransparency = 1
	props.Font = props.Font or T.Font
	props.TextColor3 = props.TextColor3 or T.Text
	if props.TextScaled == nil then
		props.TextScaled = true
	end
	local children = props.Children or {}
	props.Children = nil
	if not props.NoStroke then
		table.insert(children, textStroke(props.StrokeSize))
	end
	props.NoStroke = nil
	props.StrokeSize = nil
	return new("TextLabel", props, children)
end

local function button(props)
	local c1, c2 = props.C1 or T.Green1, props.C2 or T.Green2
	props.C1, props.C2 = nil, nil
	local onClick = props.OnClick
	props.OnClick = nil
	local text = props.Text or ""
	props.Text = ""
	props.AutoButtonColor = false
	props.BackgroundColor3 = Color3.new(1, 1, 1)
	local scale = new("UIScale", { Scale = 1 })
	local g = grad(c1, c2, 90)
	local lbl = label({ Name = "Label", Text = text, Size = UDim2.new(1, -12, 1, -8), Position = UDim2.fromOffset(6, 4), TextSize = props.TextSize, TextScaled = props.TextSize == nil })
	props.TextSize = nil
	local radius = props.Radius or 12
	props.Radius = nil
	local b = new("TextButton", props, { corner(radius), stroke(Color3.fromRGB(10, 10, 20), 2.5, 0.2), g, scale, lbl })
	b.MouseEnter:Connect(function()
		TweenService:Create(scale, TweenInfo.new(0.12), { Scale = 1.05 }):Play()
	end)
	b.MouseLeave:Connect(function()
		TweenService:Create(scale, TweenInfo.new(0.12), { Scale = 1 }):Play()
	end)
	b.MouseButton1Down:Connect(function()
		TweenService:Create(scale, TweenInfo.new(0.06), { Scale = 0.93 }):Play()
	end)
	b.MouseButton1Up:Connect(function()
		TweenService:Create(scale, TweenInfo.new(0.1, Enum.EasingStyle.Back), { Scale = 1.05 }):Play()
	end)
	if onClick then
		b.Activated:Connect(onClick)
	end
	b:SetAttribute("C1", c1)
	return b, lbl, g
end

local function setButtonColors(g, c1, c2)
	g.Color = ColorSequence.new(c1, c2)
end

---------------------------------------------------------------------------
-- Root
---------------------------------------------------------------------------
local gui = new("ScreenGui", {
	Name = "MeteorHUD",
	ResetOnSpawn = false,
	IgnoreGuiInset = false,
	ZIndexBehavior = Enum.ZIndexBehavior.Sibling,
	Parent = player:WaitForChild("PlayerGui"),
})

local scales = {}
local function scaled(frame)
	local s = new("UIScale", { Parent = frame })
	table.insert(scales, s)
	return frame
end
local function updateScale()
	local cam = Workspace.CurrentCamera
	local vp = cam and cam.ViewportSize or Vector2.new(1600, 900)
	local s = math.clamp(math.min(vp.X / 1500, vp.Y / 860), 0.55, 1.15)
	for _, u in ipairs(scales) do
		u.Scale = s
	end
end

local blur = new("BlurEffect", { Name = "MenuBlur", Size = 0, Parent = Lighting })

---------------------------------------------------------------------------
-- Request-Helper
---------------------------------------------------------------------------
local Request
function Interface.Request(action, arg)
	local ok, success, msg = pcall(function()
		return Request:InvokeServer(action, arg)
	end)
	if not ok then
		Interface.Toast("Connection error", "error")
		return false
	end
	if msg and type(msg) == "string" then
		Interface.Toast(msg, success and "success" or "error")
	end
	return success, msg
end

---------------------------------------------------------------------------
-- Toasts
---------------------------------------------------------------------------
local toastHolder = scaled(new("Frame", {
	Name = "Toasts",
	BackgroundTransparency = 1,
	AnchorPoint = Vector2.new(1, 0),
	Position = UDim2.new(1, -16, 0.22, 0),
	Size = UDim2.fromOffset(360, 420),
	Parent = gui,
}, {
	new("UIListLayout", { Padding = UDim.new(0, 8), HorizontalAlignment = Enum.HorizontalAlignment.Right, SortOrder = Enum.SortOrder.LayoutOrder }),
}))

local TOAST_COLORS = {
	info = { Color3.fromRGB(70, 110, 255), Color3.fromRGB(40, 60, 170) },
	success = { Color3.fromRGB(60, 210, 120), Color3.fromRGB(25, 130, 80) },
	warning = { Color3.fromRGB(255, 180, 50), Color3.fromRGB(200, 110, 20) },
	error = { Color3.fromRGB(255, 70, 90), Color3.fromRGB(170, 30, 60) },
	rare = { Color3.fromRGB(200, 90, 255), Color3.fromRGB(110, 40, 200) },
}
local toastOrder = 0

function Interface.Toast(text, kind)
	local colors = TOAST_COLORS[kind or "info"] or TOAST_COLORS.info
	toastOrder = toastOrder + 1
	local holder = new("Frame", { BackgroundTransparency = 1, Size = UDim2.fromOffset(360, 54), LayoutOrder = toastOrder, Parent = toastHolder })
	local card = new("Frame", {
		BackgroundColor3 = Color3.new(1, 1, 1),
		Size = UDim2.fromScale(1, 1),
		Position = UDim2.fromScale(1.2, 0),
		Parent = holder,
	}, {
		corner(14),
		grad(colors[1], colors[2], 0),
		stroke(Color3.fromRGB(10, 10, 20), 2.5, 0.1),
		label({ Text = text, Size = UDim2.new(1, -24, 1, -12), Position = UDim2.fromOffset(12, 6), TextXAlignment = Enum.TextXAlignment.Left, Font = T.Font2 }),
	})
	TweenService:Create(card, TweenInfo.new(0.35, Enum.EasingStyle.Back, Enum.EasingDirection.Out), { Position = UDim2.fromScale(0, 0) }):Play()
	task.delay(4.5, function()
		local tw = TweenService:Create(card, TweenInfo.new(0.3, Enum.EasingStyle.Quad, Enum.EasingDirection.In), { Position = UDim2.fromScale(1.2, 0) })
		tw:Play()
		tw.Completed:Wait()
		holder:Destroy()
	end)
	local list = toastHolder:GetChildren()
	local frames = {}
	for _, c in ipairs(list) do
		if c:IsA("Frame") then
			table.insert(frames, c)
		end
	end
	if #frames > 6 then
		table.sort(frames, function(a, b)
			return a.LayoutOrder < b.LayoutOrder
		end)
		frames[1]:Destroy()
	end
end

---------------------------------------------------------------------------
-- Große Ankündigung
---------------------------------------------------------------------------
local announce = label({
	Name = "Announce",
	AnchorPoint = Vector2.new(0.5, 0.5),
	Position = UDim2.fromScale(0.5, 0.26),
	Size = UDim2.new(0.7, 0, 0, 54),
	Text = "",
	TextTransparency = 1,
	StrokeSize = 3,
	Parent = gui,
})
local announceStroke = announce:FindFirstChildOfClass("UIStroke")
local announceToken = 0
function Interface.Announce(text, color)
	announceToken = announceToken + 1
	local my = announceToken
	announce.Text = text
	announce.TextColor3 = color or T.Accent1
	announce.Size = UDim2.new(0.7, 0, 0, 40)
	announce.TextTransparency = 0
	announceStroke.Transparency = 0
	TweenService:Create(announce, TweenInfo.new(0.4, Enum.EasingStyle.Back), { Size = UDim2.new(0.7, 0, 0, 56) }):Play()
	task.delay(4, function()
		if my == announceToken then
			TweenService:Create(announce, TweenInfo.new(0.6), { TextTransparency = 1 }):Play()
			TweenService:Create(announceStroke, TweenInfo.new(0.6), { Transparency = 1 }):Play()
		end
	end)
end

---------------------------------------------------------------------------
-- Cash-Karte (links oben)
---------------------------------------------------------------------------
local cashCard = scaled(new("Frame", {
	Name = "Cash",
	BackgroundColor3 = Color3.new(1, 1, 1),
	Position = UDim2.fromOffset(16, 64),
	Size = UDim2.fromOffset(290, 104),
	Parent = gui,
}, {
	corner(18),
	grad(T.Bg2, T.Bg, 90),
	stroke(T.Accent1, 3, 0.15),
}))
local cashLabel = label({ Text = "$0", Size = UDim2.new(1, -24, 0, 50), Position = UDim2.fromOffset(12, 6), TextXAlignment = Enum.TextXAlignment.Left, TextColor3 = Color3.new(1, 1, 1), StrokeSize = 2.5, Parent = cashCard }, {})
new("UIGradient", { Color = ColorSequence.new(Color3.fromRGB(170, 255, 190), Color3.fromRGB(60, 220, 120)), Rotation = 90, Parent = cashLabel })
local incomeLabel = label({ Text = "+$0/s", Size = UDim2.new(1, -24, 0, 24), Position = UDim2.fromOffset(12, 54), TextXAlignment = Enum.TextXAlignment.Left, TextColor3 = T.Cash, Font = T.Font2, Parent = cashCard })
local multLabel = label({ Text = "", Size = UDim2.new(1, -24, 0, 18), Position = UDim2.fromOffset(12, 80), TextXAlignment = Enum.TextXAlignment.Left, TextColor3 = T.Sub, Font = T.Font2, Parent = cashCard })

local displayedCash = 0
local targetCash = 0

---------------------------------------------------------------------------
-- Panels
---------------------------------------------------------------------------
local openPanel = nil

local function closePanels()
	if openPanel then
		local p = openPanel
		openPanel = nil
		local s = p.Panel.PopScale
		TweenService:Create(s, TweenInfo.new(0.15), { Scale = 0.85 }):Play()
		task.delay(0.15, function()
			if openPanel ~= p then
				p.Visible = false
			end
		end)
	end
	TweenService:Create(blur, TweenInfo.new(0.2), { Size = 0 }):Play()
end

local function makePanel(name, title, icon, c1, c2)
	local holder = scaled(new("Frame", {
		Name = name,
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.52),
		Size = UDim2.fromOffset(640, 470),
		BackgroundTransparency = 1,
		Visible = false,
		Parent = gui,
	}))
	local panel = new("Frame", {
		Name = "Panel",
		BackgroundColor3 = Color3.new(1, 1, 1),
		Size = UDim2.fromScale(1, 1),
		Parent = holder,
	}, { corner(22), grad(T.Bg2, T.Bg, 90), stroke(c1, 3.5, 0) })
	new("UIScale", { Name = "PopScale", Scale = 1, Parent = panel })
	local header = new("Frame", {
		Name = "Header",
		BackgroundColor3 = Color3.new(1, 1, 1),
		Size = UDim2.new(1, 0, 0, 64),
		Parent = panel,
	}, { corner(22), grad(c1, c2, 0) })
	new("Frame", { BackgroundColor3 = Color3.new(1, 1, 1), BorderSizePixel = 0, Position = UDim2.new(0, 0, 1, -22), Size = UDim2.new(1, 0, 0, 22), Parent = header }, { grad(c1, c2, 0) })
	label({ Text = icon .. "  " .. title, Size = UDim2.new(1, -120, 0, 44), Position = UDim2.fromOffset(22, 10), TextXAlignment = Enum.TextXAlignment.Left, StrokeSize = 3, Parent = header })
	button({
		Name = "Close",
		Text = "✕",
		C1 = Color3.fromRGB(255, 80, 90),
		C2 = Color3.fromRGB(190, 30, 60),
		AnchorPoint = Vector2.new(1, 0),
		Position = UDim2.new(1, -12, 0, 10),
		Size = UDim2.fromOffset(44, 44),
		Parent = header,
		OnClick = closePanels,
	})
	local body = new("Frame", {
		Name = "Body",
		BackgroundTransparency = 1,
		Position = UDim2.fromOffset(18, 78),
		Size = UDim2.new(1, -36, 1, -94),
		Parent = panel,
	})
	holder:SetAttribute("Title", title)
	Interface.Panels[name] = holder
	return holder, body
end

function Interface.Open(name)
	local holder = Interface.Panels[name]
	if not holder then
		return
	end
	if openPanel == holder then
		closePanels()
		return
	end
	if openPanel then
		openPanel.Visible = false
	end
	openPanel = holder
	holder.Visible = true
	local s = holder.Panel.PopScale
	s.Scale = 0.8
	TweenService:Create(s, TweenInfo.new(0.3, Enum.EasingStyle.Back, Enum.EasingDirection.Out), { Scale = 1 }):Play()
	TweenService:Create(blur, TweenInfo.new(0.25), { Size = 14 }):Play()
	if Interface.Refreshers[name] then
		Interface.Refreshers[name]()
	end
end

local function card(parent, props)
	props.BackgroundColor3 = props.BackgroundColor3 or T.Card
	props.Parent = parent
	return new("Frame", props, { corner(16), stroke(Color3.fromRGB(255, 255, 255), 1.5, 0.88) })
end

---------------------------------------------------------------------------
-- UPGRADES
---------------------------------------------------------------------------
do
	local _, body = makePanel("Upgrades", "UPGRADES", "⚡", Color3.fromRGB(90, 160, 255), Color3.fromRGB(110, 70, 255))
	new("UIListLayout", { Padding = UDim.new(0, 12), Parent = body })
	local rows = {}
	for i, id in ipairs(Config.UpgradeOrder) do
		local def = Config.Upgrades[id]
		local row = card(body, { Size = UDim2.new(1, 0, 0, 108), LayoutOrder = i })
		label({ Text = def.Icon, Size = UDim2.fromOffset(70, 70), Position = UDim2.fromOffset(14, 19), NoStroke = true, Parent = row })
		local name = label({ Text = def.Name, Size = UDim2.new(0.5, 0, 0, 32), Position = UDim2.fromOffset(96, 12), TextXAlignment = Enum.TextXAlignment.Left, Parent = row })
		local desc = label({ Text = def.Desc, Size = UDim2.new(0.5, 0, 0, 20), Position = UDim2.fromOffset(96, 46), TextXAlignment = Enum.TextXAlignment.Left, TextColor3 = T.Sub, Font = T.Font2, NoStroke = true, Parent = row })
		local barBg = new("Frame", { BackgroundColor3 = Color3.fromRGB(20, 20, 36), Position = UDim2.fromOffset(96, 76), Size = UDim2.new(0.5, 0, 0, 14), Parent = row }, { corner(7) })
		local bar = new("Frame", { BackgroundColor3 = Color3.new(1, 1, 1), Size = UDim2.fromScale(0, 1), Parent = barBg }, { corner(7), grad(Color3.fromRGB(110, 200, 255), Color3.fromRGB(120, 90, 255), 0) })
		local btn, btnLabel, btnGrad = button({
			Text = "$0",
			AnchorPoint = Vector2.new(1, 0.5),
			Position = UDim2.new(1, -16, 0.5, 0),
			Size = UDim2.fromOffset(170, 62),
			Parent = row,
			OnClick = function()
				Interface.Request("Upgrade", id)
			end,
		})
		rows[id] = { Name = name, Desc = desc, Bar = bar, Btn = btn, Label = btnLabel, Grad = btnGrad }
	end
	Interface.Refreshers.Upgrades = function()
		local st = Interface.State
		if not st then
			return
		end
		for id, row in pairs(rows) do
			local def = Config.Upgrades[id]
			local lvl = st.Upgrades[id] or 0
			row.Name.Text = def.Name .. "  <font color='#9aa0c8'>Lv " .. lvl .. "/" .. def.Max .. "</font>"
			row.Name.RichText = true
			row.Bar.Size = UDim2.fromScale(lvl / def.Max, 1)
			if lvl >= def.Max then
				row.Label.Text = "MAXED"
				setButtonColors(row.Grad, T.Grey1, T.Grey2)
			else
				local cost = Config.UpgradeCost(id, lvl)
				row.Label.Text = Util.Money(cost)
				if st.Cash >= cost then
					setButtonColors(row.Grad, T.Green1, T.Green2)
				else
					setButtonColors(row.Grad, T.Grey1, T.Grey2)
				end
			end
		end
	end
end

---------------------------------------------------------------------------
-- REBIRTH
---------------------------------------------------------------------------
do
	local _, body = makePanel("Rebirth", "REBIRTH", "🌌", Color3.fromRGB(190, 110, 255), Color3.fromRGB(100, 50, 220))
	local big = label({ Text = "", Size = UDim2.new(1, 0, 0, 70), Position = UDim2.fromOffset(0, 6), RichText = true, StrokeSize = 3, Parent = body })
	local info = label({ Text = "", Size = UDim2.new(1, 0, 0, 30), Position = UDim2.fromOffset(0, 86), TextColor3 = T.Sub, Font = T.Font2, Parent = body })
	local warn = label({ Text = "⚠️ Resets Cash, Speed & Luck  •  ✅ Keeps all cores & base slots", Size = UDim2.new(1, 0, 0, 22), Position = UDim2.fromOffset(0, 124), TextColor3 = Color3.fromRGB(255, 200, 120), Font = T.Font2, NoStroke = true, Parent = body })
	local barBg = new("Frame", { BackgroundColor3 = Color3.fromRGB(20, 20, 36), Position = UDim2.new(0.1, 0, 0, 170), Size = UDim2.new(0.8, 0, 0, 30), Parent = body }, { corner(15), stroke(Color3.new(1, 1, 1), 1.5, 0.85) })
	local bar = new("Frame", { BackgroundColor3 = Color3.new(1, 1, 1), Size = UDim2.fromScale(0, 1), Parent = barBg }, { corner(15), grad(Color3.fromRGB(220, 140, 255), Color3.fromRGB(120, 60, 255), 0) })
	local barText = label({ Text = "", Size = UDim2.new(1, 0, 1, -6), Position = UDim2.fromOffset(0, 3), ZIndex = 3, Parent = barBg })
	local _, btnLabel, btnGrad = button({
		Text = "REBIRTH",
		AnchorPoint = Vector2.new(0.5, 0),
		Position = UDim2.new(0.5, 0, 0, 236),
		Size = UDim2.fromOffset(280, 72),
		C1 = Color3.fromRGB(210, 120, 255),
		C2 = Color3.fromRGB(120, 50, 230),
		Parent = body,
		OnClick = function()
			Interface.Request("Rebirth")
		end,
	})
	local _ = warn
	Interface.Refreshers.Rebirth = function()
		local st = Interface.State
		if not st then
			return
		end
		local cost = Config.RebirthCost(st.Rebirths)
		local now = Config.RebirthMultiplier(st.Rebirths)
		local nxt = Config.RebirthMultiplier(st.Rebirths + 1)
		big.Text = "x" .. now .. "  <font color='#b98cff'>➜</font>  <font color='#7dffa0'>x" .. nxt .. "</font>"
		info.Text = "Rebirth #" .. (st.Rebirths + 1) .. " costs " .. Util.Money(cost) .. " – permanent cash multiplier!"
		local p = math.clamp(st.Cash / cost, 0, 1)
		bar.Size = UDim2.fromScale(p, 1)
		barText.Text = Util.Money(st.Cash) .. " / " .. Util.Money(cost)
		if p >= 1 then
			btnLabel.Text = "REBIRTH NOW!"
			setButtonColors(btnGrad, Color3.fromRGB(210, 120, 255), Color3.fromRGB(120, 50, 230))
		else
			btnLabel.Text = math.floor(p * 100) .. "%"
			setButtonColors(btnGrad, T.Grey1, T.Grey2)
		end
	end
end

---------------------------------------------------------------------------
-- INDEX
---------------------------------------------------------------------------
do
	local _, body = makePanel("Index", "CORE INDEX", "📖", Color3.fromRGB(80, 220, 200), Color3.fromRGB(40, 120, 200))
	local counter = label({ Text = "", Size = UDim2.new(1, 0, 0, 26), TextColor3 = T.Sub, Font = T.Font2, Parent = body })
	local scroll = new("ScrollingFrame", {
		BackgroundTransparency = 1,
		Position = UDim2.fromOffset(0, 34),
		Size = UDim2.new(1, 0, 1, -34),
		CanvasSize = UDim2.new(),
		AutomaticCanvasSize = Enum.AutomaticSize.Y,
		ScrollBarThickness = 6,
		ScrollBarImageColor3 = Color3.fromRGB(120, 220, 210),
		BorderSizePixel = 0,
		Parent = body,
	}, {
		new("UIGridLayout", { CellSize = UDim2.fromOffset(140, 150), CellPadding = UDim2.fromOffset(10, 10), SortOrder = Enum.SortOrder.LayoutOrder }),
		new("UIPadding", { PaddingTop = UDim.new(0, 4), PaddingLeft = UDim.new(0, 4) }),
	})
	local cells = {}
	for i, def in ipairs(Config.Cores) do
		local rarity = Config.RarityById[def.Rarity]
		local c = card(scroll, { LayoutOrder = i })
		local orb = new("Frame", { AnchorPoint = Vector2.new(0.5, 0), Position = UDim2.new(0.5, 0, 0, 10), Size = UDim2.fromOffset(62, 62), BackgroundColor3 = Color3.new(1, 1, 1), Parent = c }, {
			new("UICorner", { CornerRadius = UDim.new(1, 0) }),
			stroke(rarity.Color, 3, 0),
		})
		local orbGrad = grad(def.Color, def.Shell, 45)
		orbGrad.Parent = orb
		local q = label({ Text = "?", Size = UDim2.fromScale(1, 1), Parent = orb })
		local name = label({ Text = def.Name, Position = UDim2.fromOffset(6, 78), Size = UDim2.new(1, -12, 0, 24), Parent = c })
		local rar = label({ Text = string.upper(def.Rarity), Position = UDim2.fromOffset(6, 102), Size = UDim2.new(1, -12, 0, 16), TextColor3 = rarity.Color, Font = Enum.Font.GothamBlack, Parent = c })
		local inc = label({ Text = Util.Money(def.Income) .. "/s", Position = UDim2.fromOffset(6, 120), Size = UDim2.new(1, -12, 0, 20), TextColor3 = T.Cash, Font = T.Font2, Parent = c })
		cells[def.Id] = { Orb = orb, Q = q, Name = name, Rar = rar, Inc = inc, Grad = orbGrad, Def = def }
	end
	Interface.Refreshers.Index = function()
		local st = Interface.State
		if not st then
			return
		end
		local n = 0
		for id, cell in pairs(cells) do
			local found = st.Discovered[id] == true
			if found then
				n = n + 1
			end
			cell.Q.Visible = not found
			cell.Grad.Color = found and ColorSequence.new(cell.Def.Color, cell.Def.Shell) or ColorSequence.new(Color3.fromRGB(40, 40, 60), Color3.fromRGB(25, 25, 40))
			cell.Name.Text = found and cell.Def.Name or "???"
			cell.Inc.Visible = found
		end
		counter.Text = "Discovered " .. n .. " / " .. #Config.Cores .. "  •  Mutations: Golden x3 • Rainbow x7 • Void x20"
	end
end

---------------------------------------------------------------------------
-- SHOP
---------------------------------------------------------------------------
do
	local _, body = makePanel("Shop", "STAR SHOP", "🛒", Color3.fromRGB(255, 190, 60), Color3.fromRGB(255, 90, 140))
	local scroll = new("ScrollingFrame", {
		BackgroundTransparency = 1,
		Size = UDim2.fromScale(1, 1),
		CanvasSize = UDim2.new(),
		AutomaticCanvasSize = Enum.AutomaticSize.Y,
		ScrollBarThickness = 6,
		ScrollBarImageColor3 = Color3.fromRGB(255, 190, 80),
		BorderSizePixel = 0,
		Parent = body,
	}, {
		new("UIListLayout", { Padding = UDim.new(0, 10), SortOrder = Enum.SortOrder.LayoutOrder }),
		new("UIPadding", { PaddingRight = UDim.new(0, 10), PaddingTop = UDim.new(0, 2) }),
	})
	local order = 0
	local function section(text)
		order = order + 1
		label({ Text = text, Size = UDim2.new(1, 0, 0, 30), LayoutOrder = order, TextXAlignment = Enum.TextXAlignment.Left, TextColor3 = Color3.fromRGB(255, 210, 120), Parent = scroll })
		order = order + 1
		local grid = new("Frame", { BackgroundTransparency = 1, Size = UDim2.new(1, 0, 0, 0), AutomaticSize = Enum.AutomaticSize.Y, LayoutOrder = order, Parent = scroll }, {
			new("UIGridLayout", { CellSize = UDim2.new(0.5, -6, 0, 150), CellPadding = UDim2.fromOffset(12, 12), SortOrder = Enum.SortOrder.LayoutOrder }),
		})
		return grid
	end
	local items = {}
	local function item(grid, key, def, isPass, i)
		local c = card(grid, { LayoutOrder = i })
		label({ Text = def.Icon, Size = UDim2.fromOffset(64, 64), Position = UDim2.fromOffset(12, 12), NoStroke = true, Parent = c })
		label({ Text = def.Name, Size = UDim2.new(1, -96, 0, 30), Position = UDim2.fromOffset(84, 14), TextXAlignment = Enum.TextXAlignment.Left, Parent = c })
		label({ Text = def.Desc, Size = UDim2.new(1, -96, 0, 36), Position = UDim2.fromOffset(84, 46), TextXAlignment = Enum.TextXAlignment.Left, TextYAlignment = Enum.TextYAlignment.Top, TextWrapped = true, TextColor3 = T.Sub, Font = T.Font2, NoStroke = true, Parent = c })
		local _, lbl, g = button({
			Text = "R$ " .. def.Price,
			AnchorPoint = Vector2.new(0.5, 1),
			Position = UDim2.new(0.5, 0, 1, -12),
			Size = UDim2.new(1, -24, 0, 46),
			C1 = Color3.fromRGB(80, 230, 120),
			C2 = Color3.fromRGB(30, 160, 80),
			Parent = c,
			OnClick = function()
				local st = Interface.State
				if isPass and st and st.Passes[key] then
					return
				end
				if def.Id and def.Id ~= 0 then
					if isPass then
						MarketplaceService:PromptGamePassPurchase(player, def.Id)
					else
						MarketplaceService:PromptProductPurchase(player, def.Id)
					end
				elseif st and st.IsStudio then
					Interface.Request("StudioTest", key)
				else
					Interface.Toast("Coming soon!", "info")
				end
			end,
		})
		items[key] = { Label = lbl, Grad = g, Def = def, IsPass = isPass }
		if def.Id and def.Id ~= 0 then
			task.spawn(function()
				local ok, info = pcall(function()
					return MarketplaceService:GetProductInfo(def.Id, isPass and Enum.InfoType.GamePass or Enum.InfoType.Product)
				end)
				if ok and info and info.PriceInRobux then
					def.Price = info.PriceInRobux
					if not (isPass and Interface.State and Interface.State.Passes[key]) then
						lbl.Text = "R$ " .. info.PriceInRobux
					end
				end
			end)
		end
	end
	local g1 = section("👑 GAME PASSES – forever")
	for i, key in ipairs(Config.GamePassOrder) do
		item(g1, key, Config.GamePasses[key], true, i)
	end
	local g2 = section("⚡ BOOSTS")
	for i, key in ipairs(Config.ProductOrder) do
		item(g2, key, Config.Products[key], false, i)
	end
	Interface.Refreshers.Shop = function()
		local st = Interface.State
		if not st then
			return
		end
		for key, it in pairs(items) do
			if it.IsPass and st.Passes[key] then
				it.Label.Text = "✅ OWNED"
				setButtonColors(it.Grad, T.Grey1, T.Grey2)
			elseif not (it.Def.Id and it.Def.Id ~= 0) and st.IsStudio then
				it.Label.Text = "TEST (Studio)"
			end
		end
	end
end

---------------------------------------------------------------------------
-- DAILY
---------------------------------------------------------------------------
do
	local _, body = makePanel("Daily", "DAILY REWARD", "🎁", Color3.fromRGB(255, 120, 160), Color3.fromRGB(255, 160, 60))
	local row = new("Frame", { BackgroundTransparency = 1, Size = UDim2.new(1, 0, 0, 110), Parent = body }, {
		new("UIListLayout", { FillDirection = Enum.FillDirection.Horizontal, Padding = UDim.new(0, 8), HorizontalAlignment = Enum.HorizontalAlignment.Center }),
	})
	local days = {}
	for d = 1, Config.DailyMaxStreak do
		local c = card(row, { Size = UDim2.fromOffset(78, 104), LayoutOrder = d })
		label({ Text = "DAY " .. d, Size = UDim2.new(1, -8, 0, 22), Position = UDim2.fromOffset(4, 6), Parent = c })
		label({ Text = (d == Config.DailyMaxStreak) and "💎" or "💰", Size = UDim2.new(1, 0, 0, 44), Position = UDim2.fromOffset(0, 30), NoStroke = true, Parent = c })
		label({ Text = "x" .. d, Size = UDim2.new(1, 0, 0, 20), Position = UDim2.fromOffset(0, 78), TextColor3 = T.Cash, Parent = c })
		days[d] = c
	end
	local status = label({ Text = "", Size = UDim2.new(1, 0, 0, 30), Position = UDim2.fromOffset(0, 130), TextColor3 = T.Sub, Font = T.Font2, Parent = body })
	local _, btnLabel, btnGrad = button({
		Text = "CLAIM",
		AnchorPoint = Vector2.new(0.5, 0),
		Position = UDim2.new(0.5, 0, 0, 190),
		Size = UDim2.fromOffset(280, 72),
		C1 = Color3.fromRGB(255, 130, 170),
		C2 = Color3.fromRGB(240, 90, 60),
		Parent = body,
		OnClick = function()
			Interface.Request("Daily")
		end,
	})
	Interface.Refreshers.Daily = function()
		local st = Interface.State
		if not st then
			return
		end
		local left = math.max(0, (st.DailyAt or 0) - os.clock())
		for d, c in ipairs(days) do
			local s = c:FindFirstChildOfClass("UIStroke")
			if d <= st.Streak then
				c.BackgroundColor3 = Color3.fromRGB(60, 150, 100)
				s.Transparency = 0.2
			else
				c.BackgroundColor3 = T.Card
				s.Transparency = 0.88
			end
		end
		if left <= 0 then
			btnLabel.Text = "CLAIM!"
			setButtonColors(btnGrad, Color3.fromRGB(255, 130, 170), Color3.fromRGB(240, 90, 60))
			status.Text = "Your reward is ready! Streak: " .. st.Streak .. " day(s)"
		else
			btnLabel.Text = Util.FormatTime(left)
			setButtonColors(btnGrad, T.Grey1, T.Grey2)
			status.Text = "Next reward in " .. Util.FormatTime(left) .. " – don't break your streak!"
		end
	end
end

---------------------------------------------------------------------------
-- CODES
---------------------------------------------------------------------------
do
	local _, body = makePanel("Codes", "CODES", "🎟️", Color3.fromRGB(90, 220, 255), Color3.fromRGB(60, 120, 255))
	label({ Text = "Enter a code for free rewards!", Size = UDim2.new(1, 0, 0, 30), Position = UDim2.fromOffset(0, 20), TextColor3 = T.Sub, Font = T.Font2, Parent = body })
	local box = new("TextBox", {
		PlaceholderText = "ENTER CODE",
		Text = "",
		ClearTextOnFocus = false,
		Font = T.Font,
		TextScaled = true,
		TextColor3 = T.Text,
		PlaceholderColor3 = Color3.fromRGB(110, 115, 150),
		BackgroundColor3 = Color3.fromRGB(20, 20, 36),
		AnchorPoint = Vector2.new(0.5, 0),
		Position = UDim2.new(0.5, 0, 0, 80),
		Size = UDim2.new(0.8, 0, 0, 64),
		Parent = body,
	}, { corner(14), stroke(Color3.fromRGB(90, 220, 255), 2.5, 0.2), pad(12) })
	button({
		Text = "REDEEM",
		AnchorPoint = Vector2.new(0.5, 0),
		Position = UDim2.new(0.5, 0, 0, 170),
		Size = UDim2.fromOffset(260, 66),
		C1 = Color3.fromRGB(90, 220, 255),
		C2 = Color3.fromRGB(50, 110, 240),
		Parent = body,
		OnClick = function()
			if box.Text ~= "" then
				local ok = Interface.Request("Code", box.Text)
				if ok then
					box.Text = ""
				end
			end
		end,
	})
	label({ Text = "Like & favorite the game – new codes every update! ⭐", Size = UDim2.new(1, 0, 0, 24), Position = UDim2.fromOffset(0, 270), TextColor3 = T.Sub, Font = T.Font2, NoStroke = true, Parent = body })
end

---------------------------------------------------------------------------
-- Menü-Buttons (links)
---------------------------------------------------------------------------
local menu = scaled(new("Frame", {
	Name = "Menu",
	BackgroundTransparency = 1,
	Position = UDim2.fromOffset(16, 182),
	Size = UDim2.fromOffset(176, 380),
	Parent = gui,
}, {
	new("UIGridLayout", { CellSize = UDim2.fromOffset(82, 82), CellPadding = UDim2.fromOffset(10, 10), SortOrder = Enum.SortOrder.LayoutOrder }),
}))
local badges = {}
local MENU = {
	{ "Shop", "🛒", "SHOP", Color3.fromRGB(255, 200, 60), Color3.fromRGB(255, 110, 60) },
	{ "Upgrades", "⚡", "UPGRADE", Color3.fromRGB(90, 170, 255), Color3.fromRGB(100, 70, 255) },
	{ "Rebirth", "🌌", "REBIRTH", Color3.fromRGB(200, 120, 255), Color3.fromRGB(110, 50, 230) },
	{ "Index", "📖", "INDEX", Color3.fromRGB(80, 230, 200), Color3.fromRGB(40, 130, 200) },
	{ "Daily", "🎁", "DAILY", Color3.fromRGB(255, 130, 170), Color3.fromRGB(240, 80, 80) },
	{ "Codes", "🎟️", "CODES", Color3.fromRGB(90, 220, 255), Color3.fromRGB(50, 110, 240) },
	{ "Home", "🏠", "BASE", Color3.fromRGB(120, 230, 110), Color3.fromRGB(40, 150, 80) },
}
for i, m in ipairs(MENU) do
	local b, lbl = button({
		Name = m[1],
		Text = "",
		C1 = m[4],
		C2 = m[5],
		LayoutOrder = i,
		Radius = 18,
		Parent = menu,
		OnClick = function()
			if m[1] == "Home" then
				Interface.Request("Home")
			else
				Interface.Open(m[1])
			end
		end,
	})
	lbl.Visible = false
	label({ Text = m[2], Size = UDim2.new(1, 0, 0.6, 0), Position = UDim2.fromScale(0, 0.04), NoStroke = true, Parent = b })
	label({ Text = m[3], Size = UDim2.new(1, -8, 0.28, 0), Position = UDim2.new(0, 4, 0.66, 0), StrokeSize = 2, Parent = b })
	local badge = new("Frame", { Visible = false, AnchorPoint = Vector2.new(0.5, 0.5), Position = UDim2.new(1, -6, 0, 6), Size = UDim2.fromOffset(24, 24), BackgroundColor3 = Color3.fromRGB(255, 60, 80), Parent = b }, {
		new("UICorner", { CornerRadius = UDim.new(1, 0) }),
		stroke(Color3.new(1, 1, 1), 2, 0),
		label({ Text = "!", Size = UDim2.fromScale(1, 1) }),
	})
	badges[m[1]] = badge
end

---------------------------------------------------------------------------
-- Event-Banner (oben Mitte)
---------------------------------------------------------------------------
local eventBanner = scaled(new("Frame", {
	Name = "EventBanner",
	AnchorPoint = Vector2.new(0.5, 0),
	Position = UDim2.new(0.5, 0, 0, 10),
	Size = UDim2.fromOffset(460, 78),
	BackgroundColor3 = Color3.new(1, 1, 1),
	Visible = false,
	Parent = gui,
}, { corner(18), stroke(Color3.fromRGB(10, 10, 20), 3, 0.1) }))
local eventGrad = grad(T.Accent1, T.Accent2, 0)
eventGrad.Parent = eventBanner
local eventTitle = label({ Text = "", Size = UDim2.new(1, -20, 0, 34), Position = UDim2.fromOffset(10, 6), StrokeSize = 3, Parent = eventBanner })
local eventDesc = label({ Text = "", Size = UDim2.new(1, -20, 0, 20), Position = UDim2.fromOffset(10, 40), Font = T.Font2, Parent = eventBanner })
local eventBarBg = new("Frame", { BackgroundColor3 = Color3.fromRGB(10, 10, 20), BackgroundTransparency = 0.4, Position = UDim2.new(0, 14, 1, -14), Size = UDim2.new(1, -28, 0, 6), Parent = eventBanner }, { corner(3) })
local eventBar = new("Frame", { BackgroundColor3 = Color3.new(1, 1, 1), Size = UDim2.fromScale(1, 1), Parent = eventBarBg }, { corner(3) })
local eventState = nil

function Interface.SetEvent(state)
	if state then
		eventState = { Name = state.Name, Desc = state.Desc, Color = state.Color, EndsAt = os.clock() + state.Remaining, Total = math.max(state.Remaining, 1), By = state.By }
		eventGrad.Color = ColorSequence.new(state.Color, state.Color:Lerp(Color3.new(0, 0, 0), 0.45))
		eventTitle.Text = "☄️ " .. state.Name .. " ☄️"
		eventBanner.Visible = true
		eventBanner.Size = UDim2.fromOffset(380, 60)
		TweenService:Create(eventBanner, TweenInfo.new(0.4, Enum.EasingStyle.Back), { Size = UDim2.fromOffset(460, 78) }):Play()
	else
		eventState = nil
		eventBanner.Visible = false
	end
end

---------------------------------------------------------------------------
-- Carry-Anzeige (unten Mitte)
---------------------------------------------------------------------------
local carryCard = scaled(new("Frame", {
	Name = "Carry",
	AnchorPoint = Vector2.new(0.5, 1),
	Position = UDim2.new(0.5, 0, 1, -96),
	Size = UDim2.fromOffset(440, 76),
	BackgroundColor3 = Color3.new(1, 1, 1),
	Visible = false,
	Parent = gui,
}, { corner(18), grad(T.Bg2, T.Bg, 90) }))
local carryStroke = stroke(T.Accent1, 3, 0)
carryStroke.Parent = carryCard
local carryTitle = label({ Text = "", Size = UDim2.new(1, -24, 0, 36), Position = UDim2.fromOffset(12, 6), RichText = true, Parent = carryCard })
local carryHint = label({ Text = "", Size = UDim2.new(1, -24, 0, 22), Position = UDim2.fromOffset(12, 44), TextColor3 = T.Sub, Font = T.Font2, Parent = carryCard })
local carryInfo = nil

function Interface.SetCarry(info)
	carryInfo = info
	if not info then
		carryCard.Visible = false
		return
	end
	carryCard.Visible = true
	carryStroke.Color = info.Stolen and Color3.fromRGB(255, 60, 80) or info.Color
	local hex = string.format("#%02X%02X%02X", math.floor(info.Color.R * 255), math.floor(info.Color.G * 255), math.floor(info.Color.B * 255))
	carryTitle.Text = (info.Stolen and "🦹 STOLEN: " or "✋ ") .. "<font color='" .. hex .. "'>" .. info.Name .. "</font>  <font color='#7dffa0'>" .. Util.Money(info.Income) .. "/s</font>"
	carryHint.Text = info.Stolen and "RUN to your base before the owner bonks you!" or "Bring it into your base – or sell it at the Core Exchange"
	carryCard.Size = UDim2.fromOffset(380, 66)
	TweenService:Create(carryCard, TweenInfo.new(0.35, Enum.EasingStyle.Back), { Size = UDim2.fromOffset(440, 76) }):Play()
end

---------------------------------------------------------------------------
-- "+ $X" Popups
---------------------------------------------------------------------------
function Interface.CashPopup(amount)
	local l = label({
		Text = "+" .. Util.Money(amount),
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.new(0.5, math.random(-60, 60), 0.62, 0),
		Size = UDim2.fromOffset(300, 60),
		TextColor3 = T.Cash,
		StrokeSize = 3,
		Parent = gui,
	})
	TweenService:Create(l, TweenInfo.new(1.3, Enum.EasingStyle.Quad, Enum.EasingDirection.Out), { Position = l.Position - UDim2.fromOffset(0, 120), TextTransparency = 1 }):Play()
	TweenService:Create(l:FindFirstChildOfClass("UIStroke"), TweenInfo.new(1.3), { Transparency = 1 }):Play()
	task.delay(1.4, function()
		l:Destroy()
	end)
	local s = cashCard:FindFirstChildOfClass("UIScale")
	if s then
		local base = s.Scale
		TweenService:Create(s, TweenInfo.new(0.08), { Scale = base * 1.08 }):Play()
		task.delay(0.1, function()
			TweenService:Create(s, TweenInfo.new(0.2, Enum.EasingStyle.Back), { Scale = base }):Play()
		end)
	end
end

---------------------------------------------------------------------------
-- Ladebildschirm
---------------------------------------------------------------------------
local loading = new("Frame", {
	Name = "Loading",
	BackgroundColor3 = Color3.new(1, 1, 1),
	Size = UDim2.new(1, 0, 1, 60),
	Position = UDim2.fromOffset(0, -60),
	ZIndex = 50,
	Parent = gui,
}, { grad(Color3.fromRGB(40, 20, 80), Color3.fromRGB(8, 8, 20), 90) })
local loadTitle = label({ Text = "☄️ " .. Config.GameName .. " ☄️", AnchorPoint = Vector2.new(0.5, 0.5), Position = UDim2.fromScale(0.5, 0.42), Size = UDim2.new(0.8, 0, 0.13, 0), ZIndex = 51, StrokeSize = 4, Parent = loading })
new("UIGradient", { Color = ColorSequence.new(Color3.fromRGB(255, 210, 90), Color3.fromRGB(255, 80, 140)), Rotation = 90, Parent = loadTitle })
local loadSub = label({ Text = "Generating the island...", AnchorPoint = Vector2.new(0.5, 0.5), Position = UDim2.fromScale(0.5, 0.54), Size = UDim2.new(0.6, 0, 0.04, 0), ZIndex = 51, TextColor3 = T.Sub, Font = T.Font2, Parent = loading })
local spinner = label({ Text = "☄️", AnchorPoint = Vector2.new(0.5, 0.5), Position = UDim2.fromScale(0.5, 0.64), Size = UDim2.fromOffset(60, 60), ZIndex = 51, NoStroke = true, Parent = loading })

local function hideLoading()
	TweenService:Create(loading, TweenInfo.new(0.8), { BackgroundTransparency = 1 }):Play()
	for _, d in ipairs(loading:GetDescendants()) do
		if d:IsA("TextLabel") then
			TweenService:Create(d, TweenInfo.new(0.6), { TextTransparency = 1 }):Play()
		elseif d:IsA("UIStroke") then
			TweenService:Create(d, TweenInfo.new(0.6), { Transparency = 1 }):Play()
		end
	end
	task.delay(0.9, function()
		loading:Destroy()
	end)
end

---------------------------------------------------------------------------
-- Daten anwenden
---------------------------------------------------------------------------
function Interface.ApplyState(st)
	st.DailyAt = os.clock() + (st.DailyLeft or 0)
	Interface.State = st
	targetCash = st.Cash
	incomeLabel.Text = "+" .. Util.Money(st.Income) .. "/s"
	local parts = {}
	if st.Rebirths > 0 then
		table.insert(parts, "🌌 x" .. Config.RebirthMultiplier(st.Rebirths))
	end
	if st.Passes and st.Passes.VIP then
		table.insert(parts, "👑 VIP x2")
	end
	table.insert(parts, "🍀 Luck x" .. (math.floor(st.Luck * 100) / 100))
	table.insert(parts, "🧱 " .. st.CoreCount .. "/" .. st.Slots)
	multLabel.Text = table.concat(parts, "  •  ")

	-- Badges
	local canUpgrade = false
	for _, id in ipairs(Config.UpgradeOrder) do
		local lvl = st.Upgrades[id] or 0
		if lvl < Config.Upgrades[id].Max and st.Cash >= Config.UpgradeCost(id, lvl) then
			canUpgrade = true
		end
	end
	badges.Upgrades.Visible = canUpgrade
	badges.Rebirth.Visible = st.Cash >= Config.RebirthCost(st.Rebirths)
	badges.Daily.Visible = (st.DailyLeft or 0) <= 0

	if openPanel then
		local r = Interface.Refreshers[openPanel.Name]
		if r then
			r()
		end
	end
end

---------------------------------------------------------------------------
-- Init
---------------------------------------------------------------------------
function Interface.Init(remotes)
	Request = remotes:WaitForChild("Request")
	updateScale()
	local cam = Workspace.CurrentCamera
	if cam then
		cam:GetPropertyChangedSignal("ViewportSize"):Connect(updateScale)
	end

	remotes:WaitForChild("Data").OnClientEvent:Connect(Interface.ApplyState)
	remotes:WaitForChild("Notify").OnClientEvent:Connect(Interface.Toast)
	remotes:WaitForChild("Announce").OnClientEvent:Connect(Interface.Announce)
	remotes:WaitForChild("Event").OnClientEvent:Connect(Interface.SetEvent)
	remotes:WaitForChild("Carry").OnClientEvent:Connect(Interface.SetCarry)
	remotes:WaitForChild("Collected").OnClientEvent:Connect(Interface.CashPopup)

	-- Shop-Kiosk im Plaza
	ProximityPromptService.PromptTriggered:Connect(function(prompt)
		if prompt.Name == "OpenShop" then
			Interface.Open("Shop")
		end
	end)

	-- Eigene Sockel zeigen "Pick Up" statt "Steal"
	local function fixPrompt(prompt)
		if prompt:IsA("ProximityPrompt") and prompt.Name == "SlotPrompt" then
			local function apply()
				local plotIndex = player:GetAttribute("PlotIndex")
				local mine = plotIndex ~= nil and prompt:GetAttribute("Plot") == plotIndex
				local want = mine and "Pick Up" or "Steal"
				if prompt.ActionText ~= want then
					prompt.ActionText = want
				end
				prompt.HoldDuration = mine and 0.3 or 1.25
			end
			apply()
			prompt:GetPropertyChangedSignal("ActionText"):Connect(apply)
			player:GetAttributeChangedSignal("PlotIndex"):Connect(apply)
		end
	end
	local map = Workspace:WaitForChild("Map")
	for _, d in ipairs(map:GetDescendants()) do
		fixPrompt(d)
	end
	map.DescendantAdded:Connect(fixPrompt)

	-- Ladebildschirm-Animation
	task.spawn(function()
		local t0 = os.clock()
		while loading.Parent do
			local t = os.clock() - t0
			spinner.Rotation = t * 240
			loadSub.Text = Workspace:GetAttribute("WorldReady") and "Loading your base..." or ("Generating the island" .. string.rep(".", math.floor(t * 2) % 4))
			task.wait()
		end
	end)

	-- Erste Daten holen
	task.spawn(function()
		local tries = 0
		while not Interface.State and tries < 60 do
			tries = tries + 1
			local ok, success, snap = pcall(function()
				return Request:InvokeServer("Snapshot")
			end)
			if ok and success and type(snap) == "table" then
				Interface.ApplyState(snap)
			else
				task.wait(0.5)
			end
		end
		local start = os.clock()
		while not Workspace:GetAttribute("WorldReady") and os.clock() - start < 60 do
			task.wait(0.25)
		end
		task.wait(0.5)
		hideLoading()
		task.wait(1)
		Interface.Toast("☄️ Welcome to " .. Config.GameName .. "! Grab meteor cores & bring them home.", "info")
	end)

	-- Cash-Zähler animieren + Timer
	task.spawn(function()
		while true do
			local dt = task.wait()
			if math.abs(targetCash - displayedCash) < 1 then
				displayedCash = targetCash
			else
				displayedCash = displayedCash + (targetCash - displayedCash) * math.min(1, dt * 8)
			end
			cashLabel.Text = Util.Money(displayedCash)
			if eventState then
				local left = math.max(0, eventState.EndsAt - os.clock())
				eventDesc.Text = (eventState.By and ("by " .. eventState.By .. "  •  ") or "") .. eventState.Desc .. "  •  " .. Util.FormatTime(left)
				eventBar.Size = UDim2.fromScale(left / eventState.Total, 1)
			end
			if openPanel and openPanel.Name == "Daily" and Interface.State then
				Interface.Refreshers.Daily()
			end
		end
	end)

	-- Tastatur-Shortcuts
	local UIS = game:GetService("UserInputService")
	UIS.InputBegan:Connect(function(input, processed)
		if processed then
			return
		end
		if input.KeyCode == Enum.KeyCode.M then
			Interface.Open("Shop")
		elseif input.KeyCode == Enum.KeyCode.U then
			Interface.Open("Upgrades")
		elseif input.KeyCode == Enum.KeyCode.Escape then
			closePanels()
		end
	end)
end

return Interface
