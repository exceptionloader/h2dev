-- METEOR RUSH :: Server-Bootstrap
-- Lädt alle Services, ruft Init() (Verknüpfung) und danach Start() (Logik) auf.

local Services = {}
local order = {
	"Remotes",
	"DataService",
	"WorldBuilder",
	"EventService",
	"PlotService",
	"EconomyService",
	"CoreService",
	"MeteorService",
	"CombatService",
	"MonetizationService",
	"LeaderboardService",
}

for _, name in ipairs(order) do
	Services[name] = require(script:WaitForChild(name))
end

for _, name in ipairs(order) do
	local service = Services[name]
	if service.Init then
		service:Init(Services)
	end
end

for _, name in ipairs(order) do
	local service = Services[name]
	if service.Start then
		task.spawn(function()
			service:Start()
		end)
	end
end

print("[Meteor Rush] Server gestartet ☄️")
