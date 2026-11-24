--[[
	RateLimiter  (ModuleScript)
	Explorer:  ServerScriptService > ShopService > RateLimiter

	Sliding-window limiter per player. limiter:check(player) returns false when
	the player exceeded `maxCalls` within `windowSeconds`.
]]

local Players = game:GetService("Players")

local RateLimiter = {}
RateLimiter.__index = RateLimiter

local allLimiters = setmetatable({}, { __mode = "k" })

function RateLimiter.new(maxCalls: number, windowSeconds: number)
	local self = setmetatable({
		maxCalls = maxCalls,
		window = windowSeconds,
		calls = {} :: { [Player]: { number } },
	}, RateLimiter)
	allLimiters[self] = true
	return self
end

function RateLimiter:check(player: Player): boolean
	local now = os.clock()
	local list = self.calls[player]
	if not list then
		list = {}
		self.calls[player] = list
	end
	-- drop expired timestamps
	local cutoff = now - self.window
	local i = 1
	while i <= #list do
		if list[i] < cutoff then
			table.remove(list, i)
		else
			i += 1
		end
	end
	if #list >= self.maxCalls then
		return false
	end
	table.insert(list, now)
	return true
end

Players.PlayerRemoving:Connect(function(player)
	for limiter in allLimiters do
		limiter.calls[player] = nil
	end
end)

return RateLimiter
