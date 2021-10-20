Engine_PolyGlint : CroneEngine {

	classvar maxNumVoices = 16;

	var ctlBus;
	var paramSynth;
	var voices;

	*new { arg context, callback;
		^super.new(context, callback);
	}

	alloc {

		ctlBus = Dictionary.with(
			\amp -> Bus.control,
			\mod -> Bus.control,
			\bend -> Bus.control,
			\voiceAttack -> Bus.control,
			\voiceRelease -> Bus.control
		);

		voices = Dictionary.new;

		SynthDef.new(\glintParams, {
			arg amp = 0,
				mod = 0,
				bend = 0,
				ampAttack = 0.01,
				ampRelease = 0.2,
				modAttack = 0.01,
				modRelease = 0.2,
				voiceAttack = 0.01,
				voiceRelease = 0.1,
				bendLag = 0.1;
			Out.kr(ctlBus[\amp], LagUD.kr(amp, ampAttack, ampRelease));
			Out.kr(ctlBus[\mod], LagUD.kr(mod, modAttack, modRelease));
			Out.kr(ctlBus[\voiceAttack], voiceAttack);
			Out.kr(ctlBus[\voiceRelease], voiceRelease);
			Out.kr(ctlBus[\bend], 2.pow(Lag.kr(bend, bendLag)));
		}).send;

		context.server.sync;

		"glint params init'd".postln;

		SynthDef.new(\glint, {
			arg out,
				hz = 220,
				gate = 0;
			var env = EnvGen.ar(Env.asr(In.kr(ctlBus[\voiceAttack]), 1, In.kr(ctlBus[\voiceRelease])), gate, doneAction:
			Done.freeSelf);
			var sine = SinOscFB.ar(hz * In.kr(ctlBus[\bend]), In.kr(ctlBus[\mod]));
			// TODO: pan voices :)
			Out.ar(out, In.kr(ctlBus[\amp]) * env * sine ! 2);
		}).send;

		context.server.sync;

		"glint synth init'd".postln;

		paramSynth = Synth.new(\glintParams);

		this.addCommand(\amp, "f", {
			arg msg;
			paramSynth.set(\amp, msg[1]);
		});

		this.addCommand(\mod, "f", {
			arg msg;
			paramSynth.set(\mod, msg[1]);
		});

		this.addCommand(\bend, "f", {
			arg msg;
			paramSynth.set(\bend, msg[1]);
		});

		// TODO: a/r params

		this.addCommand(\hz, "if", {
			arg msg;
			var id = msg[1];
			var hz = msg[2];
			if(voices.size < maxNumVoices, {
				if(voices[id].notNil, {
					voices[id].set(\hz, hz);
				}, {
					voices.add(id -> Synth.new(\glint, [\hz, hz, \gate, 1]));
					NodeWatcher.register(voices[id]);
					voices[id].onFree({
						voices.removeAt(id);
					});
				});
			}, {
				"glint: too many voices".postln;
			});
		});

		this.addCommand(\off, "i", {
			arg msg;
			var id = msg[1];
			var hz = msg[2];
			if(voices[id].notNil, {
				voices[id].set(\gate, 0);
			}, {
				"glint: unknown voice " ++ id.postln;
			})
		});

		"glint alloc'd".postln;
	}

	free {
		ctlBus.do({ |bus| bus.free; });
		voices.do({ |voice| voice.free; });
		paramSynth.free;
	}
}
