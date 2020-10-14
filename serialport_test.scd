a=SerialPort.new("COM3", 115200);
a=SerialPort.new("COM4", 115200);

(
fork {
	a.read.debug;
};
)

CfGridRaw
a.putAll(Int8Array.with(0x10, 0, 0))
0x90
0x90
a.putAll(Int8Array.with(0x90, 0, 0, 15))

a.putAll(Int8Array.with(0x91, 0, 15))

0x90

Int8Array.with(144, 1, 5, 10)
Int8Array.with((-128)+144, 1, 5, 10)
SerialOSCClient.serialportArc2("COM4");
a=SerialOSCClient.devices.first
a.ringSet(0, 0, 15)
a.port.send

b=Int8Array(4)
b.add(114);

Int8Array.with(-111, 0, 5, 5).asString
0x90

SerialOSCClient.serialport128("COM3");

SerialOSCGrid.ledLevelAll(0)

a="abcd";
a[0]=3

Char.bullet.ascii

[42, 42].collect { |int| int.asAscii }.join == Int8Array.with(42, 42)

Int8Array.with(42, 42)
Int8Array.newFrom([42, 42])
Int8Array.with(0x90, 0, 5, 5)

0x90.asAscii.ascii.bitOr(0)