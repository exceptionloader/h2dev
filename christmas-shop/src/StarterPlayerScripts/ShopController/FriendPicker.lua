--[[
	FriendPicker  (ModuleScript)
	Explorer:  StarterPlayer > StarterPlayerScripts > ShopController > FriendPicker

	Modal that lists the friends who are in this server. Picking one calls
	onPick(userId). The server validates everything again.
]]

local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local ShopConfig = require(ReplicatedStorage:WaitForChild("ShopConfig"))
local UIAnimations = require(ReplicatedStorage:WaitForChild("UIAnimations"))
local UIKit = require(script.Parent:WaitForChild("UIKit"))

local Colors = ShopConfig.Colors
local LocalPlayer = Players.LocalPlayer

local FriendPicker = {}
FriendPicker.__index = FriendPicker

local friendCache: { [number]: boolean } = {}

local function isFriend(userId: number): boolean
	if not ShopConfig.RequireFriendshipForGifts then
		return true
	end
	if friendCache[userId] ~= nil then
		return friendCache[userId]
	end
	local ok, result = pcall(LocalPlayer.IsFriendsWith, LocalPlayer, userId)
	local value = ok and result == true
	friendCache[userId] = value
	return value
end

function FriendPicker.new(parent: GuiObject)
	local self = setmetatable({}, FriendPicker)
	self.Maid = UIAnimations.Maid.new()
	self.OpenMaid = UIAnimations.Maid.new()
	self.IsOpen = false

	local overlay = UIKit.create("TextButton", {
		Name = "FriendPicker",
		Text = "",
		AutoButtonColor = false,
		BackgroundColor3 = Color3.new(0, 0, 0),
		BackgroundTransparency = 0.45,
		Size = UDim2.fromScale(1, 1),
		ZIndex = 150,
		Visible = false,
		Parent = parent,
	})
	self.Overlay = overlay

	local panel = UIKit.create("CanvasGroup", {
		Name = "Panel",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.5),
		Size = UDim2.fromScale(0.42, 0.66),
		BackgroundTransparency = 1,
		ZIndex = 151,
		Parent = overlay,
	})
	UIKit.aspect(panel, 0.95)
	self.Panel = panel

	local body = UIKit.create("Frame", {
		Size = UDim2.new(1, -10, 1, -10),
		Position = UDim2.fromOffset(5, 5),
		BackgroundColor3 = Colors.BodyNavy,
		Active = true, -- sinks clicks so tapping the panel doesn't close the picker
		ZIndex = 151,
		Parent = panel,
	})
	UIKit.corner(body, UDim.new(0.06, 0))
	UIKit.stroke(body, 4)

	local header = UIKit.create("Frame", {
		Size = UDim2.fromScale(1, 0.16),
		ZIndex = 152,
		Parent = body,
	})
	UIKit.gradient(header, Colors.HeaderRed, Colors.HeaderRedDark)
	UIKit.corner(header, UDim.new(0.3, 0))
	UIKit.stroke(header, 3)
	self.Title = UIKit.label({
		Text = "GIFT TO A FRIEND",
		Size = UDim2.fromScale(0.75, 0.6),
		Position = UDim2.fromScale(0.06, 0.2),
		XAlign = Enum.TextXAlignment.Left,
		ZIndex = 153,
		Parent = header,
	})
	local close = UIKit.closeButton({
		Size = UDim2.fromScale(0.14, 0.72),
		Position = UDim2.fromScale(0.96, 0.5),
		AnchorPoint = Vector2.new(1, 0.5),
		ZIndex = 153,
		Parent = header,
	})
	self.Maid:Give(UIAnimations.bindButton(close))
	self.Maid:Give(close.Activated:Connect(function()
		self:Close()
	end))
	self.Maid:Give(overlay.Activated:Connect(function()
		self:Close()
	end))

	local list = UIKit.create("ScrollingFrame", {
		Name = "List",
		BackgroundTransparency = 1,
		Position = UDim2.fromScale(0.05, 0.2),
		Size = UDim2.fromScale(0.9, 0.76),
		CanvasSize = UDim2.new(),
		AutomaticCanvasSize = Enum.AutomaticSize.Y,
		ScrollBarThickness = 6,
		ScrollBarImageColor3 = Colors.Gold,
		ScrollingDirection = Enum.ScrollingDirection.Y,
		ZIndex = 152,
		Parent = body,
	})
	UIKit.create("UIListLayout", {
		SortOrder = Enum.SortOrder.LayoutOrder,
		Padding = UDim.new(0, 6),
		Parent = list,
	})
	self.List = list

	self.Empty = UIKit.label({
		Text = "None of your friends are in this server right now.\nInvite them to send a gift!",
		Size = UDim2.fromScale(0.86, 0.3),
		Position = UDim2.fromScale(0.07, 0.4),
		StrokeThickness = 2,
		ZIndex = 153,
		MaxTextSize = 26,
		Parent = body,
	})
	self.Empty.Visible = false
	return self
end

function FriendPicker:_addRow(player: Player, order: number, onPick: (number) -> ())
	local rowHeight = math.max(48, self.List.AbsoluteSize.Y * 0.2)
	local row = UIKit.create("Frame", {
		Name = player.Name,
		Size = UDim2.new(1, -10, 0, rowHeight),
		LayoutOrder = order,
		BackgroundColor3 = Colors.BodyNavyDark,
		ZIndex = 153,
		Parent = self.List,
	})
	UIKit.corner(row, UDim.new(0.2, 0))
	UIKit.stroke(row, 2)

	local avatar = UIKit.create("ImageLabel", {
		BackgroundColor3 = Colors.SackBlueBottom,
		Position = UDim2.fromScale(0.02, 0.1),
		Size = UDim2.fromScale(0.8, 0.8),
		ZIndex = 154,
		Parent = row,
	})
	UIKit.aspect(avatar, 1, Enum.DominantAxis.Height)
	UIKit.corner(avatar, UDim.new(1, 0))
	UIKit.stroke(avatar, 2)
	task.spawn(function()
		local ok, image = pcall(Players.GetUserThumbnailAsync, Players, player.UserId, Enum.ThumbnailType.HeadShot, Enum.ThumbnailSize.Size150x150)
		if ok and avatar.Parent then
			avatar.Image = image
		end
	end)

	UIKit.label({
		Text = player.DisplayName,
		Size = UDim2.fromScale(0.5, 0.42),
		Position = UDim2.fromScale(0.22, 0.12),
		XAlign = Enum.TextXAlignment.Left,
		ZIndex = 154,
		MaxTextSize = 28,
		Parent = row,
	})
	UIKit.label({
		Text = "@" .. player.Name,
		Size = UDim2.fromScale(0.5, 0.3),
		Position = UDim2.fromScale(0.22, 0.56),
		XAlign = Enum.TextXAlignment.Left,
		Color = Color3.fromRGB(170, 180, 210),
		StrokeThickness = 0,
		ZIndex = 154,
		MaxTextSize = 20,
		Parent = row,
	})

	local giftButton = UIKit.create("TextButton", {
		Text = "",
		AutoButtonColor = false,
		AnchorPoint = Vector2.new(1, 0.5),
		Position = UDim2.fromScale(0.97, 0.5),
		Size = UDim2.fromScale(0.24, 0.66),
		ZIndex = 154,
		Parent = row,
	})
	UIKit.gradient(giftButton, Colors.GreenTop, Colors.GreenBottom)
	UIKit.corner(giftButton, UDim.new(0.3, 0))
	UIKit.stroke(giftButton, 3)
	UIKit.label({
		Text = "GIFT",
		Size = UDim2.fromScale(0.8, 0.7),
		Position = UDim2.fromScale(0.5, 0.5),
		AnchorPoint = Vector2.new(0.5, 0.5),
		ZIndex = 155,
		Parent = giftButton,
	})
	self.OpenMaid:Give(UIAnimations.bindButton(giftButton))
	self.OpenMaid:Give(giftButton.Activated:Connect(function()
		local userId = player.UserId
		task.spawn(function()
			self:Close()
		end)
		onPick(userId)
	end))
	return row
end

function FriendPicker:Open(itemDisplayName: string, onPick: (number) -> ())
	if self.IsOpen then
		return
	end
	self.IsOpen = true
	self.OpenMaid:Clean()
	for _, child in self.List:GetChildren() do
		if child:IsA("Frame") then
			child:Destroy()
		end
	end
	self.Title.Text = `GIFT: {string.upper(itemDisplayName)}`
	self.Empty.Visible = false
	self.Overlay.Visible = true
	self.Overlay.BackgroundTransparency = 1
	UIAnimations.tween(self.Overlay, 0.2, { BackgroundTransparency = 0.45 })
	UIAnimations.pop(self.Panel, { from = 0.7 })

	-- Friend checks yield, so run them in parallel.
	local others = {}
	for _, player in Players:GetPlayers() do
		if player ~= LocalPlayer then
			table.insert(others, player)
		end
	end
	table.sort(others, function(a, b)
		return a.DisplayName:lower() < b.DisplayName:lower()
	end)
	local pending = #others
	local shown = 0
	for index, player in others do
		task.spawn(function()
			local friend = isFriend(player.UserId)
			pending -= 1
			if friend and self.IsOpen and player.Parent then
				shown += 1
				self:_addRow(player, index, onPick)
			end
			if pending == 0 and shown == 0 and self.IsOpen then
				self.Empty.Visible = true
			end
		end)
	end
	if #others == 0 then
		self.Empty.Visible = true
	end
end

function FriendPicker:Close()
	if not self.IsOpen then
		return
	end
	self.IsOpen = false
	self.OpenMaid:Clean()
	local scale = UIAnimations.getScale(self.Panel)
	UIAnimations.tween(scale, 0.18, { Scale = 0.8 }, Enum.EasingStyle.Back, Enum.EasingDirection.In)
	UIAnimations.tween(self.Panel, 0.18, { GroupTransparency = 1 })
	local tw = UIAnimations.tween(self.Overlay, 0.2, { BackgroundTransparency = 1 })
	tw.Completed:Wait()
	if not self.IsOpen then
		self.Overlay.Visible = false
	end
end

return FriendPicker
