Engine_PolyGlint : CroneEngine {

	classvar maxNumVoices = 32;

	var <glintGroup;
	var <symGroup;

	var <glintBus;
	var <ctlBus;
	var <paramSynth;

	var <voices;
	var <symStrings;

	var <paramDef;
	var <glintDef;
	var <symDef;

	*new { arg context, callback;
		^super.new(context, callback);
	}

	alloc {

		symGroup = Group.new(context.server);
		glintGroup = Group.new(context.server);

		glintBus = Bus.audio;

		ctlBus = Dictionary.with(
			\amp -> Bus.control,
			\mod -> Bus.control,
			\bend -> Bus.control,
			\voiceAttack -> Bus.control,
			\voiceRelease -> Bus.control,
			\symAmp -> Bus.control,
			\symDecay -> Bus.control,
			\symCutoff -> Bus.control,
			\symRQ -> Bus.control
		);

		voices = Dictionary.new;
		symStrings = Dictionary.new;

		paramDef = SynthDef.new(\glintParams, {
			arg amp = 0,
				mod = 0,
				bend = 0,
				ampAttack = 0.01,
				ampRelease = 0.2,
				modAttack = 0.01,
				modRelease = 0.2,
				voiceAttack = 0.01,
				voiceRelease = 0.1,
				bendLag = 0.1,
				symAmp = 0.2,
				symDecay = 1,
				symCutoff = 10000,
				symRQ = 1.5;
			Out.kr(ctlBus[\amp], LagUD.kr(amp, ampAttack, ampRelease));
			Out.kr(ctlBus[\mod], LagUD.kr(mod, modAttack, modRelease));
			Out.kr(ctlBus[\voiceAttack], voiceAttack);
			Out.kr(ctlBus[\voiceRelease], voiceRelease);
			Out.kr(ctlBus[\bend], 2.pow(Lag.kr(bend, bendLag)));
			Out.kr(ctlBus[\symAmp], symAmp);
			Out.kr(ctlBus[\symDecay], symDecay);
			Out.kr(ctlBus[\symCutoff], symCutoff);
			Out.kr(ctlBus[\symRQ], symRQ);
		}).send;

		glintDef = SynthDef.new(\glint, {
			arg out,
				hz = 220,
				pan = 0,
				gate = 0;
			var env = EnvGen.ar(Env.asr(In.kr(ctlBus[\voiceAttack]), 1, In.kr(ctlBus[\voiceRelease])), gate, doneAction:
			Done.freeSelf);
			var sine = SinOscFB.ar(hz * In.kr(ctlBus[\bend]), In.kr(ctlBus[\mod]));
			var output = In.kr(ctlBus[\amp]) * env * sine;
			Out.ar(glintBus, output);
			Out.ar(out, Pan2.ar(output, pan));
		}).send;

		symDef = SynthDef.new(\symString, {
			arg out,
				hz = 220,
				pan = 0;
			var delay = hz.reciprocal;
			var fbGain = -60.dbamp ** (delay / In.kr(ctlBus[\symDecay]));
			var fbInput = LeakDC.ar(fbGain * LocalIn.ar);
			var strInput = RLPF.ar(
				In.ar(glintBus) * In.kr(ctlBus[\symAmp]) + fbInput,
				In.kr(ctlBus[\symCutoff]),
				In.kr(ctlBus[\symRQ])
			).softclip;
			var str = DelayL.ar(strInput, 0.1, delay - ControlDur.ir);
			LocalOut.ar(str);
			Out.ar(out, Pan2.ar(DelayN.ar(str, ControlDur.ir, ControlDur.ir), pan));
		}).send;

		context.server.sync;

		paramSynth = Synth.new(\glintParams);

		// TODO: this doesn't work -- why? scope? `this`?
		/*
		paramDef.allControlNames.do({
			arg ctl;
			this.addCommand(ctl.asSymbol, "f", {
				arg msg;
				paramSynth.set(ctl.asSymbol, msg[1]);
			});
		});
		*/

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

		this.addCommand(\symAmp, "f", {
			arg msg;
			paramSynth.set(\symAmp, msg[1]);
		});

		this.addCommand(\symDecay, "f", {
			arg msg;
			paramSynth.set(\symDecay, msg[1]);
		});

		this.addCommand(\symCutoff, "f", {
			arg msg;
			paramSynth.set(\symCutoff, msg[1]);
		});

		this.addCommand(\symRQ, "f", {
			arg msg;
			paramSynth.set(\symRQ, msg[1]);
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
					voices.add(id -> Synth.new(\glint, [\hz, hz], glintGroup, \addToTail));
					NodeWatcher.register(voices[id]);
					voices[id].onFree({
						voices.removeAt(id);
					});
				});
			}, {
				"glint: too many voices".postln;
			});
		});

		this.addCommand(\pan, "if", {
			arg msg;
			var id = msg[1];
			var pos = msg[2];
			if(voices[id].notNil, {
				voices[id].set(\pan, pos);
			})
		});

		this.addCommand(\gate, "ii", {
			arg msg;
			var id = msg[1];
			var state = msg[2];
			if(voices[id].notNil, {
				voices[id].set(\gate, state);
			})
		});

		this.addCommand(\sym, "iff", {
			arg msg;
			var id = msg[1];
			var hz = msg[2];
			var pan = msg[3];
			if(hz == 0, {
				// remove string
				if(symStrings[id].notNil, {
					symStrings[id].free;
					symStrings.removeAt(id);
				});
			}, {
				// add or tune/pan string
				if(symStrings.size < maxNumVoices, {
					if(symStrings[id].notNil, {
						symStrings[id].set([
							\hz, hz,
							\pan, pan
						]);
					}, {
						symStrings.add(id -> Synth.new(\symString, [
							\hz, hz,
							\pan, pan
						], symGroup, \addToTail));
					});
				}, {
					"glint: too many sym strings".postln;
				});
			});
		});
	}

	free {
		glintBus.free;
		ctlBus.do({ |bus| bus.free; });
		voices.do({ |voice| voice.free; });
		symStrings.do({ |string| string.free; });
		paramSynth.free;
	}
}
