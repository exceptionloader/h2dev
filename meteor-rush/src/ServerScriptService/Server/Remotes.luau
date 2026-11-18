-- Erstellt alle RemoteEvents/-Functions in ReplicatedStorage.Remotes
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local Players = game:GetService("Players")

local Remotes = { R = {} }

local EVENTS = {
	"Notify", -- (text, kind)
	"Data", -- (snapshot)
	"MeteorIncoming", -- (info)
	"MeteorImpact", -- (position, coreId, mutation)
	"Event", -- (eventState or nil)
	"Knockback", -- (velocity)
	"Collected", -- (amount)
	"Announce", -- (text, color)
	"Carry", -- (info or nil)
}

function Remotes:Init()
	local folder = Instance.new("Folder")
	folder.Name = "Remotes"
	for _, name in ipairs(EVENTS) do
		local r = Instance.new("RemoteEvent")
		r.Name = name
		r.Parent = folder
		self.R[name] = r
	end
	local rf = Instance.new("RemoteFunction")
	rf.Name = "Request"
	rf.Parent = folder
	self.R.Request = rf
	folder.Parent = ReplicatedStorage
end

function Remotes:Fire(name, player, ...)
	self.R[name]:FireClient(player, ...)
end

function Remotes:FireAll(name, ...)
	self.R[name]:FireAllClients(...)
end

function Remotes:Notify(player, text, kind)
	if player and player.Parent == Players then
		self.R.Notify:FireClient(player, text, kind or "info")
	end
end

function Remotes:NotifyAll(text, kind)
	self.R.Notify:FireAllClients(text, kind or "info")
end

function Remotes:Announce(text, color)
	self.R.Announce:FireAllClients(text, color or Color3.fromRGB(255, 200, 80))
end

return Remotes
