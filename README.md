# SerialOSCClient-sc

SuperCollider client for SerialOSC compliant devices

## Description

SerialOSCClient provides plug'n'play support for [monome](http://monome.org) grids, arcs and other SerialOSC compliant devices.

At its core link::Classes/SerialOSCClient:: and its related classes are to SerialOSC devices what link::Classes/MIDIClient:: and its related classes in the SuperCollider standard library are to MIDI devices.

In addition to this, it's possible to instantiate SerialOSCClient for single-grid, single-enc or one-grid-and-one-enc use cases. SerialOSCClient instances constitute self-contained clients decoupled from the device it is using. Callback functions are provided for led refresh and responding to incoming events. SerialOSCClient instance methods are used to update led state. Built-in routing capabilities are used to map devices to clients.

## Examples

### Basic Example

``` supercollider
SerialOSCClient.init;

// Set led state:
SerialOSCGrid.ledSet(0, 0, true); // set the top-leftmost led of default connected grid (if any) to lit

// Listen to button events:
GridKeydef(\toggle, { |x, y, state, timestamp, device| [x, y, state, timestamp, device].postln }); // a press or release of any button on any connected grid will post event state information to Post Window
GridKeydef(\toggle).free; // free responder

// Let a specific button toggle its led:
a=false; // toggle state, initially unlit
GridKeydef.press(\toggle, { SerialOSCGrid.ledSet(0, 0, a = a.not) }, 0, 0, 'default'); // a press on top-leftmost button on default grid will toggle its button led
GridKeydef.press(\toggle).free; // free responder

// Boot server
s.boot;

// Hello World - pressing the top-leftmost button auditions a sinewave.
GridKeydef.press(\playSine, { a = {SinOsc.ar}.play }, 0, 0);
GridKeydef.release(\stopSine, { a.release; a=nil }, 0, 0);

// Remove GridKeydefs using .free or by pressing Cmd-.
GridKeydef.press(\playSine).free;
GridKeydef.release(\stopSine).free;
```


### Client Example

``` supercollider
(
// the toggle state example above as a client
c = SerialOSCClient.grid("Hello World") { |client|
	var state = false; // toggle state, initially unlit
	var updateLed = { client.ledSet(0, 0, state) }; // function that updates led state of button 0,0

	client.gridRefreshAction = updateLed; // when a new device is routed to this client led state will be updated

	client.gridKeyIndexPressedAction = { |x, y|
		if (x@y == 0@0) { // when button 0,0 is pressed,
			state = state.not; // state is toggled
			updateLed.value; // and led is refreshed
		}
	};
};
)

// this client will automatically be routed to a grid that is connected or gets connected later

c.unroute; // remove device-to-client routing

c.route(SerialOSCGrid.default); // state is maintained and leds refreshed when a new device is routed to the client

c.free; // or CmdPeriod frees client
```

### Grid + Arc Client Example

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

This code was developed and have been tested in SuperCollider 3.8.

## Installation

Copy the SerialOSCClient-sc folder to the user-specific or system-wide extension directory. Recompile the SuperCollider class library.

The user-specific extension directory may be retrieved by evaluating Platform.userExtensionDir in SuperCollider, the system-wide by evaluating Platform.systemExtensionDir.

## License

Copyright (c) Anton Hörnquist
