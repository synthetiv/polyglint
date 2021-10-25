engine.name = 'PolyGlint'

g = grid.connect()
m = midi.connect(1).device

musicutil = require 'musicutil'
Voice = require 'voice'

voices = Voice.new(16)

grid_octave = 3
keys_held = {}

ji_ratios = {
	1,
	49/48, -- 7*7/3
	21/20, -- 7*3/5
	7/6,   -- 7/3
	6/5,   -- 3/5
	4/3,   --  /3
	7/5,   --
	3/2,   -- 3
	49/32, -- 7*7
	8/5,   --  /5
	7/4,   -- 7
	16/9   --  /3/3
}
root_midi_note = 49
root_freq = musicutil.note_num_to_freq(root_midi_note)

-- a magic chord:
-- ratio  factors (incl. octaves)
-- 49/32  7*7 (for extra shimmer)
-- 3/2    3
-- 6/5    3*2*2/5
-- 7/6    7/3/2
-- 49/32  7*7/2/2/2/2
-- 49/48  7*7/3/2/2/2
--
-- another way to spell it:
-- 3
-- 3*3/7/7
-- 3*3*2*2/7/7/5 :O
-- 8/7
-- 3/2
-- 1/1
--
-- the ratio between those middle-top notes is so good --
-- 6/5 over 7/6 = 36/35, go figure
-- and the top notes too --
-- 49/32 over 3/2 = 49/48
-- good old superparticulars involving factors of 7

-- that one also sounds good with 7/4 and 16/9 on top:
-- 16/9
-- 7/4    7/2
-- 7/6    7/3
-- 49/32  7*7/2
-- 49/48  7*7/3

-- here's another good tone cluster:
-- 49/32
-- 3/2
-- 7/5
--
-- aka:
-- 35/32 (not quite superparticular but like, in the spirit)
-- 15/14 (another 7-limit superparticular!)
-- 1/1
--
-- ratio between the two upper notes:
-- 7*5 over 3*5/7 = 7*7/3 = 49/48 again
--
-- you can also add yet another note above it for:
-- 8/5
-- 49/32
-- 3/2
-- 7/5
--
-- or:
-- 8/7 (yep, another)
-- 35/32
-- 15/14
-- 1/1
--
-- highest notes there:
-- /7 over 7*5 = /7/7/5 = 256/245 I guess
--
-- moving the root, that makes a nice interval between a fifth and a sixth:
-- 384/245
-- 3/2
-- a2/1

function tune_syms()
	for n = 1, #ji_ratios do
		for o = 1, 2 do
			local detune = 0 -- math.random() - 0.5
			local hz = root_freq * math.pow(1.1, detune) * math.pow(2, o - 3) * ji_ratios[n]
			local pan = (2 - o) - (n - 1) / (#ji_ratios - 1)
			print('sym string', hz, pan)
			engine.sym(n + o * #ji_ratios, hz, pan)
		end
	end
end
-- tune_syms()

function remove_syms()
	for n = 1, #ji_ratios do
		for o = 1, 2 do
			engine.sym(n + o * #ji_ratios, 0, 0)
		end
	end
end

function init()
	grid_clock = clock.run(function()
		while true do
			clock.sleep(1/15)
			grid_redraw()
		end
	end)
	tune_syms()
	engine.symAmp(0.1)
	engine.symDecay(5)
	engine.symCutoff(12000)
	engine.symRQ(1)
end

function bend_voices(bend)
	for i, voice in ipairs(voices.style.slots) do
		if voice.active and keys_held[voice.key_id] then
			local midi_note = voice.midi_note + bend
			local hz = get_note_hz(midi_note)
			engine.hz(voice.id, hz)
		end
	end
end

function m.event(data)
	local message = midi.to_msg(data)
	if message.type == 'cc' then
		if message.cc == 16 then -- back
			engine.mod((0.3 + message.val * 1.5) / 127)
		elseif message.cc == 17 then -- front
			local amp = message.val / 8 / 127
			engine.amp(math.pow(amp, 1.3))
		elseif message.cc == 18 then -- left
			-- print('down', message.val)
			local bend = message.val / 126 -- weird, but that's the maximum Touche seems to send
			bend_voices(-bend * 2.5)
		elseif message.cc == 19 then -- right
			-- print('up', message.val)
			local bend = message.val / 126
			bend_voices(bend * 2.5)
		else
			tab.print(message)
		end
	end
end

function grid_redraw()
end

function get_grid_note(x, y)
	return grid_octave * 12 + x + (8 - y) * 5
end

function get_pitch_class_and_octave(midi_note)
	local pitch = midi_note - root_midi_note
	local octave = math.floor(pitch / 12)
	local pitch_class = pitch % 12
	-- print(pitch_class, octave)
	return pitch_class, octave
end

function get_note_hz(midi_note)
	local pitch_class, octave = get_pitch_class_and_octave(midi_note)
	local floor = math.floor(pitch_class)
	local floor_ratio = ji_ratios[floor + 1]
	local ceil_ratio = ji_ratios[floor + 2] or 2
	local ratio = (floor_ratio + (pitch_class - floor) * (ceil_ratio - floor_ratio)) * math.pow(2, octave)
	return root_freq * ratio
end

function get_grid_key_id(x, y)
	return x + y * g.cols
end

function g.key(x, y, z)
	local key_id = get_grid_key_id(x, y)
	keys_held[key_id] = z == 1
	if z == 1 then
		local midi_note = get_grid_note(x, y)
		local hz = get_note_hz(midi_note)
		local voice = voices:get()
		voice.key_id = key_id
		voice.midi_note = midi_note
		voice.on_release = function()
			engine.gate(voice.id, 0)
		end
		voice.on_steal = voice.on_release
		engine.hz(voice.id, hz)
		engine.pan(voice.id, x / g.cols - 0.5)
		engine.gate(voice.id, 1)
		-- print('new voice ' .. voice.id)
	else
		for i, voice in ipairs(voices.style.slots) do
			if voice.active and voice.key_id == key_id then
				voice:release()
			end
		end
	end
end

function cleanup()
	if grid_clock ~= nil then
		clock.cancel(grid_clock)
	end
end
