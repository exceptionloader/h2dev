--[[
	UIKit  (ModuleScript)
	Explorer:  StarterPlayer > StarterPlayerScripts > ShopController > UIKit

	Small component library for the cartoon "simulator" look:
	thick dark outlines, chunky font, glossy gradients. Everything is
	scale-based so it adapts to PC, phone and tablet.
]]

local ReplicatedStorage = game:GetService("ReplicatedStorage")

local ShopConfig = require(ReplicatedStorage:WaitForChild("ShopConfig"))
local Colors = ShopConfig.Colors

local UIKit = {}

local function font(): Enum.Font
	return ShopConfig.Font or ShopConfig.FallbackFont
end
UIKit.font = font

-- create("Frame", { Size = ... }, { child1, child2 })
function UIKit.create(className: string, props: { [string]: any }?, children: { Instance }?): any
	local inst = Instance.new(className)
	if inst:IsA("GuiObject") then
		inst.BorderSizePixel = 0
	end
	local parent = nil
	if props then
		for key, value in props do
			if key == "Parent" then
				parent = value
			else
				(inst :: any)[key] = value
			end
		end
	end
	if children then
		for _, child in children do
			child.Parent = inst
		end
	end
	if parent then
		inst.Parent = parent
	end
	return inst
end
local create = UIKit.create

function UIKit.corner(parent: Instance, radius: UDim?): UICorner
	return create("UICorner", { CornerRadius = radius or UDim.new(0.18, 0), Parent = parent })
end

-- Outline for frames/buttons (Border mode so it does not affect text).
function UIKit.stroke(parent: Instance, thickness: number?, color: Color3?, transparency: number?): UIStroke
	return create("UIStroke", {
		Thickness = thickness or 3,
		Color = color or Colors.Outline,
		Transparency = transparency or 0,
		ApplyStrokeMode = Enum.ApplyStrokeMode.Border,
		LineJoinMode = Enum.LineJoinMode.Round,
		Parent = parent,
	})
end

-- Outline for text (Contextual mode).
function UIKit.textStroke(parent: Instance, thickness: number?, color: Color3?): UIStroke
	return create("UIStroke", {
		Thickness = thickness or 2.5,
		Color = color or Colors.Outline,
		ApplyStrokeMode = Enum.ApplyStrokeMode.Contextual,
		LineJoinMode = Enum.LineJoinMode.Round,
		Parent = parent,
	})
end

-- Vertical glossy gradient (top -> bottom) on a white-backed object.
function UIKit.gradient(parent: GuiObject, top: Color3, bottom: Color3, rotation: number?): UIGradient
	parent.BackgroundColor3 = Color3.new(1, 1, 1)
	return create("UIGradient", {
		Color = ColorSequence.new({
			ColorSequenceKeypoint.new(0, top),
			ColorSequenceKeypoint.new(0.55, top:Lerp(bottom, 0.45)),
			ColorSequenceKeypoint.new(1, bottom),
		}),
		Rotation = rotation or 90,
		Parent = parent,
	})
end

-- Soft white highlight on the upper half of a button/card ("gloss").
function UIKit.gloss(parent: GuiObject, strength: number?)
	local gloss = create("Frame", {
		Name = "Gloss",
		BackgroundColor3 = Color3.new(1, 1, 1),
		BackgroundTransparency = 1 - (strength or 0.22),
		Position = UDim2.fromScale(0.04, 0.06),
		Size = UDim2.fromScale(0.92, 0.42),
		ZIndex = parent.ZIndex,
		Parent = parent,
	})
	UIKit.corner(gloss, UDim.new(0.5, 0))
	create("UIGradient", {
		Rotation = 90,
		Transparency = NumberSequence.new({
			NumberSequenceKeypoint.new(0, 0),
			NumberSequenceKeypoint.new(1, 1),
		}),
		Parent = gloss,
	})
	return gloss
end

function UIKit.aspect(parent: Instance, ratio: number, dominant: Enum.DominantAxis?): UIAspectRatioConstraint
	return create("UIAspectRatioConstraint", {
		AspectRatio = ratio,
		AspectType = Enum.AspectType.FitWithinMaxSize,
		DominantAxis = dominant or Enum.DominantAxis.Width,
		Parent = parent,
	})
end

--[[
	label({ Text, Size, Position, AnchorPoint, Color, StrokeColor, StrokeThickness,
	        XAlign, MaxTextSize, ZIndex, Parent, Name, Rotation })
]]
function UIKit.label(props): TextLabel
	local label = create("TextLabel", {
		Name = props.Name or "Label",
		BackgroundTransparency = 1,
		Text = props.Text or "",
		Font = font(),
		TextScaled = true,
		TextColor3 = props.Color or Colors.Text,
		TextXAlignment = props.XAlign or Enum.TextXAlignment.Center,
		TextYAlignment = props.YAlign or Enum.TextYAlignment.Center,
		Size = props.Size or UDim2.fromScale(1, 1),
		Position = props.Position or UDim2.new(),
		AnchorPoint = props.AnchorPoint or Vector2.zero,
		Rotation = props.Rotation or 0,
		ZIndex = props.ZIndex or 1,
		RichText = props.RichText or false,
		Parent = props.Parent,
	})
	if props.StrokeThickness ~= 0 then
		UIKit.textStroke(label, props.StrokeThickness or 2.5, props.StrokeColor)
	end
	create("UITextSizeConstraint", { MaxTextSize = props.MaxTextSize or 64, MinTextSize = 6, Parent = label })
	return label
end

--[[
	icon(iconKey, { Size, Position, AnchorPoint, ZIndex, Parent, Name })
	Uses the image from ShopConfig.Icons, or the emoji fallback while the id is 0.
]]
function UIKit.icon(iconKey: string, props): GuiObject
	local obj
	if ShopConfig.hasImage(iconKey) then
		obj = create("ImageLabel", {
			BackgroundTransparency = 1,
			Image = ShopConfig.Icons[iconKey],
			ScaleType = Enum.ScaleType.Fit,
		})
	else
		obj = create("TextLabel", {
			BackgroundTransparency = 1,
			Text = ShopConfig.IconFallback[iconKey] or "?",
			TextScaled = true,
			Font = ShopConfig.FallbackFont,
			TextColor3 = Color3.new(1, 1, 1),
		})
	end
	obj.Name = props.Name or iconKey
	obj.Size = props.Size or UDim2.fromScale(1, 1)
	obj.Position = props.Position or UDim2.fromScale(0.5, 0.5)
	obj.AnchorPoint = props.AnchorPoint or Vector2.new(0.5, 0.5)
	obj.ZIndex = props.ZIndex or 1
	obj:SetAttribute("IconKey", iconKey)
	if props.Square ~= false then
		UIKit.aspect(obj, 1)
	end
	obj.Parent = props.Parent
	return obj
end

--[[
	rays({ Color, Size, Position, AnchorPoint, ZIndex, Parent, Count, Transparency, Width })
	Golden sunburst. Image if Icons.LightRays is set, else built from bars.
	Spin it with UIAnimations.raysSpin(rays).
]]
function UIKit.rays(props): GuiObject
	local color = props.Color or Colors.Gold
	local transparency = props.Transparency or 0.35
	if ShopConfig.hasImage("LightRays") then
		local img = create("ImageLabel", {
			Name = "Rays",
			BackgroundTransparency = 1,
			Image = ShopConfig.Icons.LightRays,
			ImageColor3 = color,
			ImageTransparency = transparency,
			ScaleType = Enum.ScaleType.Fit,
			Size = props.Size or UDim2.fromScale(1, 1),
			Position = props.Position or UDim2.fromScale(0.5, 0.5),
			AnchorPoint = props.AnchorPoint or Vector2.new(0.5, 0.5),
			ZIndex = props.ZIndex or 1,
			Parent = props.Parent,
		})
		UIKit.aspect(img, 1)
		return img
	end
	local holder = create("Frame", {
		Name = "Rays",
		BackgroundTransparency = 1,
		Size = props.Size or UDim2.fromScale(1, 1),
		Position = props.Position or UDim2.fromScale(0.5, 0.5),
		AnchorPoint = props.AnchorPoint or Vector2.new(0.5, 0.5),
		ZIndex = props.ZIndex or 1,
	})
	UIKit.aspect(holder, 1)
	local count = props.Count or 10
	local fade = NumberSequence.new({
		NumberSequenceKeypoint.new(0, 1),
		NumberSequenceKeypoint.new(0.2, math.min(1, transparency + 0.2)),
		NumberSequenceKeypoint.new(0.5, transparency),
		NumberSequenceKeypoint.new(0.8, math.min(1, transparency + 0.2)),
		NumberSequenceKeypoint.new(1, 1),
	})
	for i = 0, count - 1 do
		local base = i * 180 / count
		local bar = create("Frame", {
			Name = "Ray",
			AnchorPoint = Vector2.new(0.5, 0.5),
			Position = UDim2.fromScale(0.5, 0.5),
			Size = UDim2.fromScale(props.Width or 0.08, 1),
			BackgroundColor3 = color,
			Rotation = base,
			ZIndex = props.ZIndex or 1,
			Parent = holder,
		})
		bar:SetAttribute("RayBase", base)
		create("UIGradient", { Rotation = 90, Transparency = fade, Parent = bar })
	end
	local glow = create("Frame", {
		Name = "Glow",
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(0.5, 0.5),
		Size = UDim2.fromScale(0.5, 0.5),
		BackgroundColor3 = color,
		BackgroundTransparency = math.min(1, transparency + 0.15),
		ZIndex = props.ZIndex or 1,
		Parent = holder,
	})
	UIKit.corner(glow, UDim.new(1, 0))
	holder.Parent = props.Parent
	return holder
end

--[[
	priceButton({ Price, Sub, Gift, Size, Position, AnchorPoint, LayoutOrder, ZIndex, Parent })
	Green Robux button (+ optional red gift button, + optional "Buy 5" sub label).
	Returns { Root, Button, Label, GiftButton, SetOwned(bool) }
]]
function UIKit.priceButton(props)
	local z = props.ZIndex or 1
	local root = create("Frame", {
		Name = "PriceButton",
		BackgroundTransparency = 1,
		Size = props.Size or UDim2.fromScale(1, 1),
		Position = props.Position or UDim2.new(),
		AnchorPoint = props.AnchorPoint or Vector2.zero,
		LayoutOrder = props.LayoutOrder or 0,
		ZIndex = z,
	})
	local buttonHeight = if props.Sub then 0.66 else 1
	local giftWidth = if props.Gift then 0.26 else 0
	local gap = if props.Gift then 0.03 else 0

	local buttonWidth = 1 - giftWidth - gap
	-- AnchorPoint 0.5 so hover/press scaling happens around the center
	local button = create("TextButton", {
		Name = "Buy",
		Text = "",
		AutoButtonColor = false,
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.fromScale(buttonWidth / 2, buttonHeight / 2),
		Size = UDim2.fromScale(buttonWidth, buttonHeight),
		ZIndex = z + 1,
		Parent = root,
	})
	UIKit.gradient(button, Colors.GreenTop, Colors.GreenBottom)
	UIKit.corner(button, UDim.new(0.28, 0))
	UIKit.stroke(button, 3)
	UIKit.gloss(button, 0.25)
	local label = UIKit.label({
		Name = "Price",
		Text = `{ShopConfig.RobuxGlyph} {props.Price}`,
		Size = UDim2.fromScale(0.86, 0.72),
		Position = UDim2.fromScale(0.5, 0.5),
		AnchorPoint = Vector2.new(0.5, 0.5),
		ZIndex = z + 2,
		MaxTextSize = 40,
		Parent = button,
	})

	local giftButton = nil
	if props.Gift then
		giftButton = create("ImageButton", {
			Name = "Gift",
			Image = "",
			AutoButtonColor = false,
			AnchorPoint = Vector2.new(0.5, 0.5),
			Position = UDim2.fromScale(1 - giftWidth / 2, buttonHeight / 2),
			Size = UDim2.fromScale(giftWidth, buttonHeight),
			ZIndex = z + 1,
			Parent = root,
		})
		UIKit.gradient(giftButton, Colors.RedButtonTop, Colors.RedButtonBottom)
		UIKit.corner(giftButton, UDim.new(0.25, 0))
		UIKit.stroke(giftButton, 3)
		UIKit.icon("Gift", {
			Size = UDim2.fromScale(0.78, 0.78),
			ZIndex = z + 2,
			Parent = giftButton,
		})
	end

	if props.Sub then
		UIKit.label({
			Name = "Sub",
			Text = props.Sub,
			Size = UDim2.fromScale(buttonWidth, 1 - buttonHeight),
			Position = UDim2.fromScale(0, buttonHeight),
			Color = Colors.GreenTop,
			StrokeThickness = 2,
			ZIndex = z + 1,
			MaxTextSize = 26,
			Parent = root,
		})
	end

	root.Parent = props.Parent

	local api = {
		Root = root,
		Button = button,
		Label = label,
		GiftButton = giftButton,
		Owned = false,
	}
	function api.SetOwned(owned: boolean)
		api.Owned = owned
		label.Text = if owned then "OWNED ✓" else `{ShopConfig.RobuxGlyph} {props.Price}`
		button.Active = not owned
		button.AutoButtonColor = false
	end
	return api
end

-- Small round "close" button used by popups.
function UIKit.closeButton(props): TextButton
	local button = create("TextButton", {
		Name = "Close",
		Text = "",
		AutoButtonColor = false,
		Size = props.Size or UDim2.fromScale(0.08, 0.08),
		Position = props.Position or UDim2.new(),
		AnchorPoint = props.AnchorPoint or Vector2.zero,
		ZIndex = props.ZIndex or 1,
	})
	UIKit.gradient(button, Colors.RedButtonTop, Colors.RedButtonBottom)
	UIKit.corner(button, UDim.new(0.22, 0))
	UIKit.stroke(button, 3)
	UIKit.aspect(button, 1)
	UIKit.label({
		Text = "X",
		Size = UDim2.fromScale(0.7, 0.7),
		Position = UDim2.fromScale(0.5, 0.5),
		AnchorPoint = Vector2.new(0.5, 0.5),
		ZIndex = (props.ZIndex or 1) + 1,
		Parent = button,
	})
	button.Parent = props.Parent
	return button
end

-- Gold section divider:  ——— GAMEPASSES ———
function UIKit.divider(text: string, props): Frame
	local z = props.ZIndex or 1
	local frame = create("Frame", {
		Name = "Divider_" .. text,
		BackgroundTransparency = 1,
		LayoutOrder = props.LayoutOrder or 0,
		Size = UDim2.fromScale(1, 0),
		ZIndex = z,
	})
	UIKit.label({
		Text = text,
		Size = UDim2.fromScale(0.36, 1),
		Position = UDim2.fromScale(0.5, 0.5),
		AnchorPoint = Vector2.new(0.5, 0.5),
		Color = Colors.Text,
		StrokeThickness = 2,
		ZIndex = z,
		MaxTextSize = 30,
		Parent = frame,
	})
	for _, side in { 0, 1 } do
		local line = create("Frame", {
			AnchorPoint = Vector2.new(side, 0.5),
			Position = UDim2.fromScale(if side == 0 then 0.06 else 0.94, 0.5),
			Size = UDim2.new(0.25, 0, 0, 3),
			BackgroundColor3 = Colors.Gold,
			ZIndex = z,
			Parent = frame,
		})
		create("UIGradient", {
			Transparency = NumberSequence.new({
				NumberSequenceKeypoint.new(0, if side == 0 then 1 else 0),
				NumberSequenceKeypoint.new(1, if side == 0 then 0 else 1),
			}),
			Parent = line,
		})
	end
	frame.Parent = props.Parent
	return frame
end

return UIKit
