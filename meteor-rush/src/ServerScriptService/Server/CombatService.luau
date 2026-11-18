-- "Star Bonker": Schläger, mit dem man Diebe (und alle anderen) umhaut -> sie lassen ihren Core fallen
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))

local CombatService = { Cooldown = {} }

local RANGE = 9
local COOLDOWN = 0.9

function CombatService:Init(services)
	self.S = services
end

local function buildTool()
	local tool = Instance.new("Tool")
	tool.Name = "Star Bonker"
	tool.ToolTip = "Bonk thieves so they drop your core!"
	tool.CanBeDropped = false
	tool.RequiresHandle = true
	tool.GripPos = Vector3.new(0, 0, -1.4)

	local handle = Instance.new("Part")
	handle.Name = "Handle"
	handle.Size = Vector3.new(0.6, 0.6, 4.6)
	handle.Material = Enum.Material.Metal
	handle.Color = Color3.fromRGB(50, 52, 66)
	handle.CanCollide = false
	handle.Massless = true
	handle.TopSurface = Enum.SurfaceType.Smooth
	handle.BottomSurface = Enum.SurfaceType.Smooth
	handle.Parent = tool

	local function addPart(size, offset, color, material)
		local p = Instance.new("Part")
		p.Size = size
		p.Material = material or Enum.Material.Neon
		p.Color = color
		p.CanCollide = false
		p.Massless = true
		p.CastShadow = false
		p.CFrame = handle.CFrame * offset
		local w = Instance.new("WeldConstraint")
		w.Part0 = handle
		w.Part1 = p
		w.Parent = p
		p.Parent = tool
		return p
	end
	local head = addPart(Vector3.new(1.3, 1.3, 1.6), CFrame.new(0, 0, -2.4), Color3.fromRGB(255, 140, 60))
	addPart(Vector3.new(0.75, 0.75, 0.3), CFrame.new(0, 0, 1.2), Color3.fromRGB(120, 90, 255))
	addPart(Vector3.new(0.75, 0.75, 0.3), CFrame.new(0, 0, 0.4), Color3.fromRGB(120, 90, 255))
	local light = Instance.new("PointLight")
	light.Color = Color3.fromRGB(255, 150, 70)
	light.Range = 8
	light.Brightness = 1.5
	light.Parent = head

	local trailA = Instance.new("Attachment")
	trailA.Position = Vector3.new(0, 0, 0.7)
	trailA.Parent = head
	local trailB = Instance.new("Attachment")
	trailB.Position = Vector3.new(0, 0, -0.7)
	trailB.Parent = head
	local trail = Instance.new("Trail")
	trail.Attachment0 = trailA
	trail.Attachment1 = trailB
	trail.Lifetime = 0.18
	trail.LightEmission = 1
	trail.Color = ColorSequence.new(Color3.fromRGB(255, 160, 70), Color3.fromRGB(255, 60, 140))
	trail.Transparency = NumberSequence.new(0.2, 1)
	trail.Parent = head
	return tool
end

local function inSafeZone(pos)
	return (pos * Vector3.new(1, 0, 1)).Magnitude < Config.SafeZoneRadius
end

function CombatService:Swing(player, tool)
	local now = os.clock()
	if self.Cooldown[player] and now - self.Cooldown[player] < COOLDOWN then
		return
	end
	self.Cooldown[player] = now

	local anim = Instance.new("StringValue")
	anim.Name = "toolanim"
	anim.Value = "Slash"
	anim.Parent = tool
	task.delay(0.3, function()
		anim:Destroy()
	end)

	local char = player.Character
	local root = char and char:FindFirstChild("HumanoidRootPart")
	if not root or inSafeZone(root.Position) then
		return
	end
	local best, bestDist = nil, RANGE
	for _, other in ipairs(Players:GetPlayers()) do
		if other ~= player then
			local oc = other.Character
			local orr = oc and oc:FindFirstChild("HumanoidRootPart")
			local hum = oc and oc:FindFirstChildOfClass("Humanoid")
			if orr and hum and hum.Health > 0 then
				local offset = orr.Position - root.Position
				local dist = offset.Magnitude
				if dist < bestDist and root.CFrame.LookVector:Dot(offset.Unit) > 0.1 and not inSafeZone(orr.Position) then
					best, bestDist = other, dist
				end
			end
		end
	end
	if not best then
		return
	end
	local targetRoot = best.Character.HumanoidRootPart
	local dir = ((targetRoot.Position - root.Position) * Vector3.new(1, 0, 1))
	dir = dir.Magnitude > 0.01 and dir.Unit or root.CFrame.LookVector
	self.S.Remotes:Fire("Knockback", best, dir * 70 + Vector3.new(0, 38, 0), true)
	local wasCarrying = self.S.CoreService.Carrying[best] ~= nil
	self.S.CoreService:Drop(best, "bonk")
	if wasCarrying then
		self.S.Remotes:Notify(player, "💥 BONK! " .. best.DisplayName .. " dropped their core!", "success")
	end
end

function CombatService:Give(player)
	local backpack = player:FindFirstChildOfClass("Backpack")
	if not backpack or backpack:FindFirstChild("Star Bonker") then
		return
	end
	local tool = buildTool()
	tool.Activated:Connect(function()
		self:Swing(player, tool)
	end)
	tool.Parent = backpack
end

function CombatService:Start()
	local function onPlayer(player)
		player.CharacterAdded:Connect(function()
			task.wait(0.5)
			self:Give(player)
		end)
		if player.Character then
			self:Give(player)
		end
	end
	Players.PlayerAdded:Connect(onPlayer)
	for _, p in ipairs(Players:GetPlayers()) do
		onPlayer(p)
	end
	Players.PlayerRemoving:Connect(function(player)
		self.Cooldown[player] = nil
	end)
end

return CombatService
