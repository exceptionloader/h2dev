-- Globales Leaderboard (OrderedDataStore) auf der Tafel im Plaza
local DataStoreService = game:GetService("DataStoreService")
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Util = require(Shared:WaitForChild("Util"))

local LeaderboardService = { Names = {} }

local store
pcall(function()
	store = DataStoreService:GetOrderedDataStore(Config.LeaderboardStoreName)
end)

function LeaderboardService:Init(services)
	self.S = services
end

function LeaderboardService:Submit(userId, value)
	if not store then
		return
	end
	pcall(function()
		store:SetAsync(tostring(userId), math.floor(math.min(value or 0, 9e15)))
	end)
end

function LeaderboardService:NameOf(userId)
	if self.Names[userId] then
		return self.Names[userId]
	end
	local ok, name = pcall(function()
		return Players:GetNameFromUserIdAsync(userId)
	end)
	name = ok and name or ("User" .. userId)
	self.Names[userId] = name
	return name
end

local MEDALS = { "🥇", "🥈", "🥉" }
local ROW_COLORS = { Color3.fromRGB(255, 205, 60), Color3.fromRGB(215, 220, 235), Color3.fromRGB(230, 150, 90) }

function LeaderboardService:Render(entries)
	local list = self.S.WorldBuilder.LeaderboardList
	if not list then
		return
	end
	for _, c in ipairs(list:GetChildren()) do
		if c:IsA("Frame") then
			c:Destroy()
		end
	end
	if #entries == 0 then
		entries = { { Name = "Be the first!", Value = 0 } }
	end
	for i, e in ipairs(entries) do
		local row = Instance.new("Frame")
		row.Name = "Row" .. i
		row.LayoutOrder = i
		row.Size = UDim2.new(1, 0, 0.088, 0)
		row.BackgroundColor3 = Color3.fromRGB(255, 255, 255)
		row.BackgroundTransparency = 0.92
		local corner = Instance.new("UICorner")
		corner.CornerRadius = UDim.new(0.3, 0)
		corner.Parent = row
		local rank = Instance.new("TextLabel")
		rank.BackgroundTransparency = 1
		rank.Size = UDim2.fromScale(0.14, 1)
		rank.Font = Enum.Font.FredokaOne
		rank.TextScaled = true
		rank.Text = MEDALS[i] or ("#" .. i)
		rank.TextColor3 = ROW_COLORS[i] or Color3.fromRGB(200, 200, 215)
		rank.Parent = row
		local name = Instance.new("TextLabel")
		name.BackgroundTransparency = 1
		name.Position = UDim2.fromScale(0.15, 0.1)
		name.Size = UDim2.fromScale(0.55, 0.8)
		name.Font = Enum.Font.GothamBold
		name.TextScaled = true
		name.TextXAlignment = Enum.TextXAlignment.Left
		name.Text = e.Name
		name.TextColor3 = ROW_COLORS[i] or Color3.fromRGB(235, 235, 245)
		name.Parent = row
		local val = Instance.new("TextLabel")
		val.BackgroundTransparency = 1
		val.Position = UDim2.fromScale(0.7, 0.1)
		val.Size = UDim2.fromScale(0.28, 0.8)
		val.Font = Enum.Font.FredokaOne
		val.TextScaled = true
		val.TextXAlignment = Enum.TextXAlignment.Right
		val.Text = Util.Money(e.Value)
		val.TextColor3 = Color3.fromRGB(120, 255, 150)
		val.Parent = row
		row.Parent = list
	end
end

function LeaderboardService:Refresh()
	if not store then
		self:Render({})
		return
	end
	local ok, pages = pcall(function()
		return store:GetSortedAsync(false, 10)
	end)
	if not ok then
		self:Render({})
		return
	end
	local entries = {}
	for _, item in ipairs(pages:GetCurrentPage()) do
		local userId = tonumber(item.key)
		table.insert(entries, { Name = userId and self:NameOf(userId) or item.key, Value = item.value })
	end
	self:Render(entries)
end

function LeaderboardService:Start()
	task.wait(5)
	while true do
		self:Refresh()
		task.wait(120)
	end
end

return LeaderboardService
