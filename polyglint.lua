engine.name = 'PolyGlint'

g = grid.connect()
m = midi.connect(1).device

musicutil = require 'musicutil'
Voice = require 'voice'

voices = Voice.new(16)

grid_octave = 2

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

function init()
	grid_clock = clock.run(function()
		while true do
			clock.sleep(1/15)
			grid_redraw()
		end
	end)
end

function m.event(data)
	local message = midi.to_msg(data)
	if message.type == 'cc' then
		if message.cc == 16 then -- back
			engine.mod(message.val * 1.9 / 127)
		elseif message.cc == 17 then -- front
			engine.amp(message.val / 8 / 127)
		elseif message.cc == 18 then -- left
			-- print('down', message.val)
			local bend = message.val / 126 -- weird, but that's the maximum Touche seems to send
			bend = bend * bend
			engine.bend(-bend)
		elseif message.cc == 19 then -- right
			-- print('up', message.val)
			local bend = message.val / 126
			bend = bend * bend
			engine.bend(bend)
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
	return pitch_class, octave
end

function get_grid_key_id(x, y)
	return x + y * g.cols
end

function g.key(x, y, z)
	local key_id = get_grid_key_id(x, y)
	if z == 1 then
		local pitch_class, octave = get_pitch_class_and_octave(get_grid_note(x, y))
		local ratio = ji_ratios[pitch_class + 1] * math.pow(2, octave)
		local hz = root_freq * ratio
		local voice = voices:get()
		voice.key_id = key_id
		voice.on_release = function()
			engine.off(voice.id)
		end
		voice.on_steal = voice.on_release
		engine.hz(voice.id, hz)
	else
		for i, voice in ipairs(voices.style.slots) do
			if voice.key_id == key_id then
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
