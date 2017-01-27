# SerialOSCClient-sc

SuperCollider client for SerialOSC compliant devices

## Description

SerialOSCClient provides plug'n'play support for monome (link::http://monome.org::) grids, arcs and other SerialOSC compliant devices. In many regards SerialOSCClient and its related classes are to SerialOSC devices what link::Classes/MIDIClient:: and its related classes in the SuperCollider standard library are to MIDI devices.

## Examples

### Example 1

``` supercollider
SerialOSCClient.init; // Initialize the SerialOSCClient.

// * Connected devices should get posted in the Post Window.
// * Note that SerialOSCClient initialization is performed asynchronously.

// Boot server
s.boot;

// Hello World - pressing the top-leftmost button auditions a sinewave.
GridKeydef.press(\playSine, { a = {SinOsc.ar}.play }, 0, 0);
GridKeydef.release(\stopSine, { a.release; a=nil }, 0, 0);

// Remove GridKeydefs using .free or by pressing Cmd-.
GridKeydef.press(\playSine).free;
GridKeydef.release(\stopSine).free;

// Now, setting leds... If everything works properly and you have a grid attached, a default SerialOSCGrid instance is defined
SerialOSCGrid.default;

// Use this instance to set leds
a=SerialOSCGrid.default;
a.ledSet(0, 0, true);
a.ledSet(0, 0, false);

// The !? idiom in SuperCollider (see help on !?) can be used to set leds of the default grid, but only if it is available (if SerialOSCGrid.default is not nil)

SerialOSCGrid.default !? { |grid| grid.ledSet(1, 2, true) };

(
// Scramble 8 random leds in a 8x8 matrix
SerialOSCGrid.default !? { |grid|
	8 do: { grid.ledSet(8.rand, 8.rand, [true, false].choose) };
};
)

// Now, on handling multiple devices

// Info on what device triggered a GridKeydef is available in the function argument list.
GridKeydef(\debug, { |x, y, state, time, device| [x, y, state, time, device].debug });

// You can use a device as filter. See OSCdef help for how this works.
GridKeydef(\debug, { |x, y, state, time, device| [x, y, state, time, device].debug }, device: SerialOSCGrid.default);

// If several devices are attached you can retrieve SerialOSCGrid and SerialOSCEnc instances from SerialOSCClient.devices
SerialOSCClient.devices;

// Pick a device and set a led
a=SerialOSCClient.devices.first;
a.ledSet(1, 2, true); // this assumes first device in list is a grid (SerialOSCGrid)
```


### Example 2

``` supercollider
// Example, grid together with arc

(
var set_arc_led;
var scramble_8_grid_leds;
var b_spec = ControlSpec.new;

// Ensure server is running
s.serverRunning.not.if {
	Error("Boot server stored in interpreter variable s.").throw
};

// Initialize client in order to use devices
SerialOSCClient.init;

// SynthDef to server
SynthDef(\test, { |freq, gate=1| Out.ar(0, ( SinOsc.ar(Lag.kr(freq)) * EnvGen.ar(Env.cutoff, gate) ) ! 2) }).add;

// Function to visualize a float value 0 - 1.0 on first encoder ring of default arc (if attached)
set_arc_led = { |value|
	SerialOSCEnc.default !? { |enc|
		enc.clearRings;
		enc.ringSet(0, SerialOSCEnc.ledXSpec.map(value), 15);
	};
};

// Function to scramble state for 8 random buttons in a 8x8 led matrix on the default grid (if attached)
scramble_8_grid_leds = {
	SerialOSCGrid.default !? { |grid|
		8 do: { grid.ledSet(8.rand, 8.rand, [true, false].choose) };
	};
};

// Initial arc encoder setting
b = 0.5;
set_arc_led.(b);

// First Arc encoder control frequency of sinewave and scrambles 8 leds
EncDeltadef(\adjustFrequency, { |n, delta|
	b = b_spec.constrain(b + (delta/1000));
	a !? { a.set(\freq, \freq.asSpec.map(b)) };
	set_arc_led.(b);
	scramble_8_grid_leds.();
}, 0);

// Hitting any grid button auditions sinewave and scrambles 8 leds
GridKeydef.press(
	\playSine,
	{
		a ?? {
			a = Synth(\test, [\freq, \freq.asSpec.map(b)]);
			scramble_8_grid_leds.();
		};
	}
);

// Releasing any grid button stops the sinewave
GridKeydef.release(\stopSine, { a.release; a = nil });
)
```

## Requirements

This code was developed and have been tested in SuperCollider 3.6.6.

## Installation

Copy the SerialOSCClient-sc folder to the user-specific or system-wide extension directory. Recompile the SuperCollider class library.

The user-specific extension directory may be retrieved by evaluating Platform.userExtensionDir in SuperCollider, the system-wide by evaluating Platform.systemExtensionDir.

## License

Copyright (c) Anton Hörnquist
