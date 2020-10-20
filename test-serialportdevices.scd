SerialOSCClient.serialport128("/dev/tty.usbserial-m1000604")
SerialOSCClient.serialportArc2("/dev/tty.usbserial-m0000970")

(
GridKeydef(\test, { |x, y, on| SerialOSCGrid.ledLevelSet(x, y, on*15) });
EncDeltadef(\test, { |n, delta| [n, delta].debug });
)

(
SerialOSCEnc.ringSet(0, 1, 11);
)

SerialOSCGrid.ledDemo

(
var grid_port = "COM3"; // "/dev/tty.usbserial-m1000604";
var enc_port = "COM4"; // "/dev/tty.usbserial-m0000970";
SerialOSCClient.serialPortMode( // TODO: rerunning this does not work: remove must be implemented
	[
		SerialPortGrid('monome 128', grid_port),
		SerialPortEnc('monome arc 2', enc_port)
	]
);
)

(
var midiIn, midiOut;
SerialOSCClient.serialPortMode( // TODO: rerunning this does not work: remove must be implemented
	[
		SerialPortGrid('monome 128', "/dev/tty.usbserial-m1000604"),
		SerialPortEnc('monome arc 2', "/dev/tty.usbserial-m0000970")
	]
);
MIDIClient.init;
midiOut=MIDIOut.newByName("IAC Driver", "IAC Bus 1");

GridKeydef(\keys, { |x, y, state|
	//[x, y, state].debug;
	case { (y == 7) and: (x >= 4) } {
		if (state.asBoolean) {
			midiOut.noteOn(0, 60 + x);
		} {
			midiOut.noteOff(0, 60 + x);
		};
	};
});




midiIn=MIDIIn.findPort("IAC Driver", "IAC Bus 2");
MIDIIn.connect(MIDIClient.sources.detectIndex { |endpoint| endpoint == midiIn}, midiIn);
//MIDIIn.connectAll;
MIDIdef.noteOn(\test, { |... args| args.debug });
)

MIDIClient.init;
a=MIDIOut.newByName("IAC Driver", "IAC Bus 1");
a.noteOn(0, 60);

