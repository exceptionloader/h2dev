--[[
	Toasts  (ModuleScript)
	Explorer:  StarterPlayer > StarterPlayerScripts > ShopController > Toasts

	Stacked notifications at the top center of the screen.
]]

local ReplicatedStorage = game:GetService("ReplicatedStorage")

local ShopConfig = require(ReplicatedStorage:WaitForChild("ShopConfig"))
local UIAnimations = require(ReplicatedStorage:WaitForChild("UIAnimations"))
local UIKit = require(script.Parent:WaitForChild("UIKit"))

local Colors = ShopConfig.Colors
local MAX_TOASTS = 4
local LIFETIME = 3.4

local Toasts = {}
Toasts.__index = Toasts

local KIND_COLORS = {
	Success = { Color3.fromRGB(110, 230, 90), Color3.fromRGB(34, 160, 60) },
	Error = { Color3.fromRGB(255, 110, 110), Color3.fromRGB(190, 36, 60) },
	Info = { Color3.fromRGB(90, 160, 255), Color3.fromRGB(40, 86, 200) },
	Announce = { Color3.fromRGB(255, 220, 90), Color3.fromRGB(226, 140, 20) },
}

function Toasts.new(parent: GuiObject)
	local holder = UIKit.create("Frame", {
		Name = "Toasts",
		BackgroundTransparency = 1,
		AnchorPoint = Vector2.new(0.5, 0),
		Position = UDim2.fromScale(0.5, 0.07),
		Size = UDim2.fromScale(0.5, 0.36),
		ZIndex = 200,
		Parent = parent,
	})
	UIKit.create("UIListLayout", {
		SortOrder = Enum.SortOrder.LayoutOrder,
		HorizontalAlignment = Enum.HorizontalAlignment.Center,
		Padding = UDim.new(0.02, 0),
		Parent = holder,
	})
	return setmetatable({ Holder = holder, Active = {}, Counter = 0 }, Toasts)
end

function Toasts:Show(text: string, kind: string?, iconKey: string?)
	self.Counter += 1
	local colors = KIND_COLORS[kind or "Info"] or KIND_COLORS.Info

	while #self.Active >= MAX_TOASTS do
		local oldest = table.remove(self.Active, 1)
		oldest:Destroy()
	end

	local slot = UIKit.create("Frame", {
		Name = "Toast",
		BackgroundTransparency = 1,
		Size = UDim2.fromScale(1, 0.22),
		LayoutOrder = self.Counter,
		ZIndex = 200,
		Parent = self.Holder,
	})
	local pill = UIKit.create("CanvasGroup", {
		Name = "Pill",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.5),
		Size = UDim2.fromScale(1, 1),
		BackgroundTransparency = 1,
		ZIndex = 200,
		Parent = slot,
	})
	local bg = UIKit.create("Frame", {
		Size = UDim2.new(1, -8, 1, -8),
		Position = UDim2.fromOffset(4, 4),
		ZIndex = 200,
		Parent = pill,
	})
	UIKit.gradient(bg, colors[1], colors[2])
	UIKit.corner(bg, UDim.new(0.5, 0))
	UIKit.stroke(bg, 3, Colors.Outline)
	local textLeft = 0.05
	if iconKey then
		UIKit.icon(iconKey, {
			Size = UDim2.fromScale(0.12, 0.9),
			Position = UDim2.fromScale(0.07, 0.5),
			ZIndex = 201,
			Parent = bg,
		})
		textLeft = 0.14
	end
	UIKit.label({
		Text = text,
		Size = UDim2.fromScale(0.95 - textLeft, 0.62),
		Position = UDim2.fromScale(textLeft, 0.19),
		XAlign = if iconKey then Enum.TextXAlignment.Left else Enum.TextXAlignment.Center,
		ZIndex = 201,
		MaxTextSize = 28,
		Parent = bg,
	})
	table.insert(self.Active, slot)

	UIAnimations.pop(pill, { from = 0.5, duration = 0.35 })
	task.delay(LIFETIME, function()
		if slot.Parent then
			local tw = UIAnimations.tween(pill, 0.3, { GroupTransparency = 1 })
			UIAnimations.tween(UIAnimations.getScale(pill), 0.3, { Scale = 0.8 }, Enum.EasingStyle.Back, Enum.EasingDirection.In)
			tw.Completed:Wait()
			local idx = table.find(self.Active, slot)
			if idx then
				table.remove(self.Active, idx)
			end
			slot:Destroy()
		end
	end)
end

return Toasts
