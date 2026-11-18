-- Kleine Helfer, die Server und Client benutzen
local Util = {}

local SUFFIXES = { "", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc" }

function Util.Abbrev(n)
	n = tonumber(n) or 0
	local negative = n < 0
	n = math.abs(n)
	if n < 1000 then
		return (negative and "-" or "") .. tostring(math.floor(n))
	end
	local i = 1
	while n >= 1000 and i < #SUFFIXES do
		n = n / 1000
		i = i + 1
	end
	local s
	if n >= 100 then
		s = string.format("%d", math.floor(n))
	elseif n >= 10 then
		s = string.format("%.1f", math.floor(n * 10) / 10)
	else
		s = string.format("%.2f", math.floor(n * 100) / 100)
	end
	if string.find(s, "%.") then
		s = (string.gsub(s, "0+$", ""))
		s = (string.gsub(s, "%.$", ""))
	end
	return (negative and "-" or "") .. s .. SUFFIXES[i]
end

function Util.Money(n)
	return "$" .. Util.Abbrev(n)
end

function Util.FormatTime(seconds)
	seconds = math.max(0, math.floor(seconds))
	local h = math.floor(seconds / 3600)
	local m = math.floor((seconds % 3600) / 60)
	local s = seconds % 60
	if h > 0 then
		return string.format("%d:%02d:%02d", h, m, s)
	end
	return string.format("%d:%02d", m, s)
end

-- list: Array, weightOf: function(item) -> number
function Util.WeightedPick(list, weightOf, rng)
	local total = 0
	for _, item in ipairs(list) do
		total = total + math.max(0, weightOf(item))
	end
	if total <= 0 then
		return list[1]
	end
	local roll = (rng and rng:NextNumber() or math.random()) * total
	for _, item in ipairs(list) do
		roll = roll - math.max(0, weightOf(item))
		if roll <= 0 then
			return item
		end
	end
	return list[#list]
end

function Util.Lerp(a, b, t)
	return a + (b - a) * t
end

function Util.SmoothStep(t)
	t = math.clamp(t, 0, 1)
	return t * t * (3 - 2 * t)
end

function Util.DeepCopy(t)
	if type(t) ~= "table" then
		return t
	end
	local out = {}
	for k, v in pairs(t) do
		out[k] = Util.DeepCopy(v)
	end
	return out
end

return Util
