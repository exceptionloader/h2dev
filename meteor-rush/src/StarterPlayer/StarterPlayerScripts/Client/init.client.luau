-- METEOR RUSH :: Client-Bootstrap
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local SoundService = game:GetService("SoundService")
local TextChatService = game:GetService("TextChatService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Remotes = ReplicatedStorage:WaitForChild("Remotes")

local Interface = require(script:WaitForChild("Interface"))
local Effects = require(script:WaitForChild("Effects"))

Effects.Init(Remotes)
Interface.Init(Remotes)

-- Hintergrundmusik (optional, ID in Config.Sounds.Music)
if Config.Sounds.Music and Config.Sounds.Music ~= 0 then
	local music = Instance.new("Sound")
	music.Name = "Music"
	music.SoundId = "rbxassetid://" .. Config.Sounds.Music
	music.Looped = true
	music.Volume = 0.35
	music.Parent = SoundService
	music:Play()
end

-- Sound beim Einsammeln
Remotes:WaitForChild("Collected").OnClientEvent:Connect(function()
	Effects.PlaySound(Config.Sounds.Collect, SoundService, 0.6)
end)

-- [VIP]-Tag im Chat
pcall(function()
	TextChatService.OnIncomingMessage = function(message)
		local source = message.TextSource
		if not source then
			return nil
		end
		local p = Players:GetPlayerByUserId(source.UserId)
		if p and p:GetAttribute("VIP") then
			local props = Instance.new("TextChatMessageProperties")
			props.PrefixText = "<font color='#FFD24A'>[VIP]</font> " .. message.PrefixText
			return props
		end
		return nil
	end
end)
