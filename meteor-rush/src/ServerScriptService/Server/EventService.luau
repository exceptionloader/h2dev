-- Server-weite Events (Meteor Shower, Lucky Skies, Golden Hour, Cosmic Storm)
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Util = require(Shared:WaitForChild("Util"))

local EventService = { Current = nil }

function EventService:Init(services)
	self.S = services
end

function EventService:State()
	local cur = self.Current
	if not cur then
		return nil
	end
	local def = Config.Events[cur.Id]
	return {
		Id = cur.Id,
		Name = def.Name,
		Desc = def.Desc,
		Color = def.Color,
		Remaining = math.max(0, cur.EndsAt - os.clock()),
		By = cur.By,
	}
end

function EventService:Broadcast()
	self.S.Remotes:FireAll("Event", self:State())
end

function EventService:Begin(id, byName, duration)
	local def = Config.Events[id]
	if not def then
		return
	end
	duration = duration or Config.EventDuration
	if self.Current and self.Current.Id == id then
		self.Current.EndsAt = self.Current.EndsAt + duration
		self.Current.By = byName or self.Current.By
	else
		self.Current = { Id = id, EndsAt = os.clock() + duration, By = byName }
	end
	self.NextAt = os.clock() + Config.EventInterval + duration
	local text = byName and (byName .. " summoned a " .. def.Name .. "!") or (def.Name .. " has begun! " .. def.Desc)
	self.S.Remotes:Announce("☄️ " .. text, def.Color)
	self:Broadcast()
end

function EventService:Mult(field)
	local cur = self.Current
	if not cur then
		return 1
	end
	return Config.Events[cur.Id][field] or 1
end

function EventService:Start()
	Players.PlayerAdded:Connect(function(player)
		task.wait(3)
		if self.Current then
			self.S.Remotes:Fire("Event", player, self:State())
		end
	end)

	self.NextAt = os.clock() + Config.EventInterval
	while true do
		task.wait(1)
		if self.Current and os.clock() >= self.Current.EndsAt then
			local def = Config.Events[self.Current.Id]
			self.Current = nil
			self.S.Remotes:NotifyAll(def.Name .. " is over.", "info")
			self:Broadcast()
		end
		if not self.Current and os.clock() >= self.NextAt then
			local list = {}
			for id, def in pairs(Config.Events) do
				table.insert(list, { Id = id, Weight = def.Weight })
			end
			table.sort(list, function(a, b)
				return a.Id < b.Id
			end)
			local pick = Util.WeightedPick(list, function(e)
				return e.Weight
			end)
			self:Begin(pick.Id)
		end
	end
end

return EventService
