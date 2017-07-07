# SerialOSCClient-sc

SuperCollider client for SerialOSC compliant devices

## Description

SerialOSCClient provides plug'n'play support for [monome](http://monome.org) grids, arcs and other SerialOSC compliant devices.

At its core SerialOSCClient and its related classes are to SerialOSC devices what MIDIClient and its related classes in the SuperCollider standard library are to MIDI devices.

In addition, it's possible to instantiate SerialOSCClient for single-grid, single-enc or one-grid-and-one-enc use cases. SerialOSCClient instances constitute self-contained clients decoupled from the devices controlling it at any given time. SerialOSCClient callback functions and instance methods are provided for update of led state and responding to incoming events. Built-in routing capabilities are used to map devices to clients.

## Examples

### Basic Examples

Initialize SerialOSCClient.

``` supercollider
SerialOSCClient.init;
```

Set led state of a grid:

``` supercollider
SerialOSCGrid.ledSet(0, 0, 1); // set the top-leftmost led of default grid (first one attached) to lit
SerialOSCGrid.ledSet(0, 0, 0); // set the top-leftmost led of default grid (first one attached) to unlit
```

Listen to button events from a grid:

``` supercollider
// a press or release of any button on any attached grid posts event state information to Post Window
GridKeydef(\test, { |x, y, state| (if (state == 1, "key down", "key up") + "at (%,%)".format(x, y)).postln });

GridKeydef(\test).free; // or CmdPeriod frees responder
```

Let a specific grid button toggle its led:

``` supercollider
(
a=false; // led state, initially unlit
GridKeydef.press(\toggle, { SerialOSCGrid.ledSet(0, 0, a = a.not) }, 0, 0, 'default'); // a press on top-leftmost button on default grid will toggle its button led
)
GridKeydef.press(\toggle).free; // free responder
```

### Client Example

Self-contained clients are created by instantiating SerialOSCClient. This is recommended for single-grid, single-enc or one-grid-and-one-enc use.

``` supercollider
(
// the toggle state example above as a client
c = SerialOSCClient.grid("Hello World") { |client|
	var lit = false; // toggle state, initially unlit
	var updateLed = { client.ledSet(0, 0, lit) }; // function that updates led state of button 0,0

	client.gridRefreshAction = updateLed; // when a new device is routed to this client led state will be updated

	client.gridKeyAction = { |client, x, y, state|
        if ((x == 0) and: (y == 0) and: (state == 1)) { // when button 0,0 is pressed,
			lit = lit.not; // led state is toggled
			updateLed.value; // and led is refreshed
		}
	};
};
)

SerialOSCClient.postRoutings; // the client will automatically be routed to a grid connected to the computer

g=c.grid; // get routed grid
g.disconnect; // disconnecting routed grid from SerialOSCClient,
SerialOSCClient.postRoutings; // will detach it from the client

g.connect; // connecting the grid again,
SerialOSCClient.postRoutings; // will reroute it to the client and refresh the leds according to the current state of the client

c.free; // or CmdPeriod frees client
```

## Requirements

This code was developed and has been tested in SuperCollider 3.8.0.

## Installation

Copy the SerialOSCClient-sc folder to the user-specific or system-wide extension directory. Recompile the SuperCollider class library.

The user-specific extension directory may be retrieved by evaluating Platform.userExtensionDir in SuperCollider, the system-wide by evaluating Platform.systemExtensionDir.

## License

Copyright (c) Anton Hörnquist
