--[[
	GiftInbox  (ModuleScript)
	Explorer:  ServerScriptService > ShopService > GiftInbox

	Cross-server gift delivery. A gift is written into the RECIPIENT's inbox
	(DataStore key = recipient UserId, entry key = the buyer's PurchaseId, so
	writing the same gift twice is harmless). The recipient's server claims the
	inbox, saves the grant into the recipient's profile first, and only then
	removes the claimed entries. Nothing can be lost or granted twice.

	In Studio without API access an in-memory inbox is used instead.
]]

local DataStoreService = game:GetService("DataStoreService")
local RunService = game:GetService("RunService")

local ShopConfig = require(game:GetService("ReplicatedStorage"):WaitForChild("ShopConfig"))

local GiftInbox = {}

local store: DataStore? = nil
local memoryInbox: { [string]: { [string]: any } } = {}
local useMemory = false

do
	local ok, result = pcall(function()
		return DataStoreService:GetDataStore(ShopConfig.GiftDataStoreName)
	end)
	if ok then
		store = result
	else
		useMemory = true
		if RunService:IsStudio() then
			warn("[GiftInbox] DataStores unavailable in Studio, using an in-memory inbox:", result)
		else
			warn("[GiftInbox] Could not open DataStore:", result)
		end
	end
end

local function isStudioApiError(err: any): boolean
	local msg = tostring(err)
	return RunService:IsStudio() and (msg:find("StudioAccessToApisNotAllowed") ~= nil or msg:find("API") ~= nil or msg:find("403") ~= nil)
end

local function withRetries<T>(fn: () -> T, attempts: number?): (boolean, T | string)
	local tries = attempts or 4
	local lastErr
	for i = 1, tries do
		local ok, result = pcall(fn)
		if ok then
			return true, result
		end
		lastErr = result
		if isStudioApiError(result) then
			useMemory = true
			break
		end
		task.wait(math.min(2 ^ (i - 1), 8))
	end
	return false, lastErr
end

-- Adds a gift. Returns true once the write is confirmed.
function GiftInbox.send(recipientUserId: number, giftId: string, payload: { [string]: any }): boolean
	local key = tostring(recipientUserId)
	if useMemory or not store then
		memoryInbox[key] = memoryInbox[key] or {}
		memoryInbox[key][giftId] = payload
		return true
	end
	local ok, err = withRetries(function()
		return (store :: DataStore):UpdateAsync(key, function(old)
			local data = if type(old) == "table" then old else { Gifts = {} }
			data.Gifts = data.Gifts or {}
			data.Gifts[giftId] = payload
			return data
		end)
	end)
	if not ok and useMemory then
		memoryInbox[key] = memoryInbox[key] or {}
		memoryInbox[key][giftId] = payload
		return true
	end
	if not ok then
		warn("[GiftInbox] send failed:", err)
	end
	return ok
end

-- Returns { [giftId] = payload } (possibly empty) or nil when the read failed.
function GiftInbox.fetch(userId: number): { [string]: any }?
	local key = tostring(userId)
	if useMemory or not store then
		return table.clone(memoryInbox[key] or {})
	end
	local ok, result = withRetries(function()
		return (store :: DataStore):GetAsync(key)
	end, 2)
	if not ok then
		if useMemory then
			return table.clone(memoryInbox[key] or {})
		end
		warn("[GiftInbox] fetch failed:", result)
		return nil
	end
	if type(result) == "table" and type(result.Gifts) == "table" then
		return result.Gifts
	end
	return {}
end

-- Removes claimed gifts. Returns true on success.
function GiftInbox.remove(userId: number, giftIds: { string }): boolean
	if #giftIds == 0 then
		return true
	end
	local key = tostring(userId)
	if useMemory or not store then
		local box = memoryInbox[key]
		if box then
			for _, id in giftIds do
				box[id] = nil
			end
		end
		return true
	end
	local ok, err = withRetries(function()
		return (store :: DataStore):UpdateAsync(key, function(old)
			if type(old) ~= "table" or type(old.Gifts) ~= "table" then
				return nil -- nothing to remove, cancel the write
			end
			for _, id in giftIds do
				old.Gifts[id] = nil
			end
			return old
		end)
	end)
	if not ok then
		warn("[GiftInbox] remove failed:", err)
	end
	return ok
end

return GiftInbox
