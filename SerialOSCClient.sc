SerialOSCClient {
	classvar
		<all,
		<devices,
		devicesSemaphore,
		<initialized=false,
		connectedDevices,
		oscRecvFunc,
		autoconnectDevices,
		recvSerialOSCFunc
	;

	var name;
	// TODO: remove var responders;
	var <useGrid, <useEnc;
	var <>grid, <>enc; // TODO: should not be setters
	var <>willFree; // TODO: clearWhenFreed
	var <>permanent; // TODO: implement CmdPeriod hook?
	var <>onFree; // TODO: naming?
	var <>onGridConnected, <>onGridDisconnected, <>gridRefreshAction;
	var <>onEncConnected, <>onEncDisconnected, <>encRefreshAction;
	var <>gridKeyAction, <>encDeltaAction, <>encKeyAction, <>tiltAction;

	*initClass {
		all = [];
		devices = [];
		connectedDevices = [];
		devicesSemaphore = Semaphore.new;
		oscRecvFunc = { |msg, time, addr, recvPort|
			if (addr.ip == "127.0.0.1") {
				this.prLookupDeviceByPort(addr.port) !? { |device|
					if (connectedDevices.includes(device.id)) {
						if (#['/sclang/grid/key', '/sclang/tilt', '/sclang/enc/delta', '/sclang/enc/key'].includes(msg[0])) { // note: no pattern matching is performed on OSC address
							var type = msg[0].asString[7..].asSymbol;
/*
	TODO: probably not needed, included in device
							var client, deviceIndex;
							SerialOSCClient.getDeviceClient(device) !? { |serialOSCClient|
								client = serialOSCClient;
								deviceIndex = serialOSCClient.deviceIndexOf(device);
							};
*/
							recvSerialOSCFunc.value(type, msg[1..], time, device);
						};
					};
				};
			};
		};
	}

	*doGridKeyAction { |x, y, state, device|
		this.prSpoofAction('/grid/key', [x.asInteger, y.asInteger, state.asInteger], device);
	}

	*doEncDeltaAction { |n, delta, device|
		this.prSpoofAction('/enc/delta', [n.asInteger, delta.asInteger], device);
	}

	*doTiltAction { |n, x, y, z, device|
		this.prSpoofAction('/tilt', [n.asInteger, x.asInteger, y.asInteger, z.asInteger], device);
	}

	*doEncKeyAction { |n, state, device|
		this.prSpoofAction('/enc/key', [n.asInteger, state.asInteger], device);
	}

	*prSpoofAction { |type, args, device| // TODO: look at how MIDIClient does spoofing?
		this.ensureInitialized;
		recvSerialOSCFunc.value(type, args, SystemClock.seconds, device);
	}

	*init { |completionFunc, autoconnect=true, supportHotPlugging=true|
		autoconnectDevices = autoconnect;

		if (SerialOSC.isTrackingConnectedDevicesChanges) {
			SerialOSC.stopTrackingConnectedDevicesChanges
		};

		if (supportHotPlugging) {
			SerialOSC.startTrackingConnectedDevicesChanges(
				"127.0.0.1",
				{ |id|
					fork {
						devicesSemaphore.wait;
						if (this.prLookupDeviceById(id).isNil) {
							this.prUpdateDevicesListAsync {
								this.prLookupDeviceById(id) !? { |serialOSCDevice|
									this.prPostDeviceAdded(id);
									this.changed(\attached, serialOSCDevice);
									if (autoconnectDevices) { this.connect(id, false) };
									this.prUpdateDefaultDevices([serialOSCDevice], []);
								};
								devicesSemaphore.signal;
							};
						} {
							devicesSemaphore.signal;
						};
					};
				},
				{ |id|
					fork {
						devicesSemaphore.wait;
						this.prLookupDeviceById(id) !? { |serialOSCDevice|
							this.prPostDeviceRemoved(id);
							devices.remove(serialOSCDevice);
							this.changed(\detached, serialOSCDevice);
							this.changed(\disconnected, serialOSCDevice);
							this.prUpdateDefaultDevices([], [serialOSCDevice]);
						};
						devicesSemaphore.signal;
					};
				}
			);
		};

		thisProcess.removeOSCRecvFunc(oscRecvFunc);
		thisProcess.addOSCRecvFunc(oscRecvFunc);

		initialized = true;

		devicesSemaphore.wait;
		this.prUpdateDevicesListAsync { |addedDevices, removedDevices|
			this.postDevices;
			if (autoconnectDevices) { this.connectAll(false) };
			addedDevices do: { |device| this.changed(\added, device) };
			removedDevices do: { |device| this.changed(\removed, device) };
			this.prUpdateDefaultDevices(addedDevices, removedDevices);
			completionFunc.();
		};
		devicesSemaphore.signal;
	}

	*addSerialOSCRecvFunc { |func| recvSerialOSCFunc = recvSerialOSCFunc.addFunc(func) }

	*removeSerialOSCRecvFunc { |func| recvSerialOSCFunc = recvSerialOSCFunc.removeFunc(func) }

	*connectAll { |verbose=true|
		devices do: { |device| this.connect(device.id, verbose) }
	}

	*freeAll {
		all.copy do: _.free;
	}

	*prUpdateDefaultDevices { |addedDevices, removedDevices|
		// TODO: important, only connected devices should be able to set as default, right? cover for the case when autoconnect is not used...
		var addedGrids, addedEncs;

		addedGrids = addedDevices.reject(this.prDeviceIsEncByType(_));
		if (devices.includes(SerialOSCGrid.default).not) {
			SerialOSCGrid.default = if (addedGrids.notEmpty) {
				addedGrids.first
			} {
				nil // TODO: fall back on existing grids - if any (the scenario when one device is being detached) - and set one as default?
			};
		};

		addedEncs = addedDevices.select(this.prDeviceIsEncByType(_));
		if (devices.includes(SerialOSCEnc.default).not and: addedEncs.notEmpty) {
			SerialOSCEnc.default = if (addedEncs.notEmpty) {
				addedEncs.first
			} {
				nil // TODO: fall back on existing encs - if any (the scenario when one device is being detached) - and set one as default?
			};
		};
	}

	*connect { |id, verbose=true|
		this.ensureInitialized;

		if (connectedDevices.includes(id).not) {
			var device;

			device = this.prLookupDeviceById(id) ?? { Error("No device with id % attached".format(id)).throw };

			SerialOSC.changeDeviceMessagePrefix("127.0.0.1", device.port, SerialOSC.prefix);
			SerialOSC.changeDeviceDestinationPort("127.0.0.1", device.port, NetAddr.langPort);

			connectedDevices = connectedDevices.add(id);

			this.changed(\connected, device);

			verbose.if {
				Post << device << Char.space << "was connected" << Char.nl;
			};
		};
	}

	*ensureInitialized {
		initialized.not.if { Error("SerialOSCClient has not been initialized").throw };
	}

	*disconnectAll { |verbose=true|
		devices do: { |device| this.disconnect(device.id, verbose) }
	}

	*disconnect { |id, verbose=true|
		initialized.not.if { Error("SerialOSCClient has not been initialized").throw };

		if (connectedDevices.includes(id)) {
			var device;

			device = this.prLookupDeviceById(id) ?? { Error("No device with id % attached".format(id)).throw };

			connectedDevices.remove(id);

			verbose.if {
				Post << device << Char.space << "was disconnected" << Char.nl;
			};
		};
	}

	*postDevices {
		if (devices.notEmpty) {
			Post << "SerialOSC Devices:" << Char.nl;
			devices.do({ |x| Post << Char.tab << x << Char.nl });
		} {
			Post << "No SerialOSC Devices are attached" << Char.nl;
		};
	}

	*prPostDeviceAdded { |id|
		Post << "A SerialOSC Device was attached:" << Char.nl;
		Post << Char.tab << this.prLookupDeviceById(id) << Char.nl;
	}

	*prPostDeviceRemoved { |id|
		Post << "A SerialOSC Device was detached:" << Char.nl;
		Post << Char.tab << this.prLookupDeviceById(id) << Char.nl
	}

	*prDeviceIsEncByType { |device|
		^this.prIsEncType(device.type)
	}

	*prListEntryIsEncByType { |device|
		^this.prIsEncType(device[\type])
	}

	*prIsEncType { |type|
		^type.asString.contains("arc")
	}

	*prUpdateDevicesListAsync { |completionFunc|
		SerialOSC.requestListOfDevices(
			"127.0.0.1",
			{ |list|
				var currentDevices, foundDevices, devicesToRemove, devicesToAdd;

				currentDevices = devices.as(IdentitySet);
				foundDevices = list.collect { |entry|
					var foundDevice = if (this.prListEntryIsEncByType(entry)) {
						SerialOSCEnc(entry[\type], entry[\id], entry[\receivePort]);
					} {
						SerialOSCGrid(entry[\type], entry[\id], entry[\receivePort]);
					};
					devices.detect { |device| device.matches(foundDevice) } ? foundDevice
				}.as(IdentitySet);

				devicesToRemove = currentDevices - foundDevices;
				devicesToAdd = foundDevices - currentDevices;
				devices.removeAll(devicesToRemove);
				devices = devices.addAll(devicesToAdd);

				completionFunc.(devicesToAdd.as(Array), devicesToRemove.as(Array)); // TODO: why isn't the devices list an IdentitySet? That would perhaps better? Would remove a lot of casts such as these ones.
			}
		);
	}

	*prLookupDeviceById { |id|
		^devices.detect { |device| device.id == id }
	}

	*prLookupDeviceByPort { |receivePort|
		^devices.detect { |device| device.port == receivePort }
	}

	*clientOf { |device|
		^all.detect { |client| client.deviceIsConnected(device) }
	}

	*grid { |name, autoconnect=true|
		^this.new(name, true, false, autoconnect);
	}

	*enc { |name, autoconnect=true|
		^this.new(name, false, true, autoconnect);
	}

	*gridEnc { |name, autoconnect=true|
		^this.new(name, true, true, autoconnect);
	}

	*new { |name, useGrid=false, useEnc=false, autoconnect=true|
		^super.new.initSerialOSCClient(name, useGrid, useEnc, autoconnect)
	}

	initSerialOSCClient { |argName, argUseGrid, argUseEnc, argAutoconnect|
		name = argName;
		useGrid = argUseGrid;
		useEnc = argUseEnc;

		if (SerialOSCClient.initialized) {
			if (argAutoconnect) { this.findAndConnectDevices };
		} {
			SerialOSCClient.init(completionFunc: {
				if (argAutoconnect) { this.findAndConnectDevices };
			});
		};
		all = all.add(this);
	}

	findAndConnectDevices {
/*
	TODO

		var indecesOfUnconnectedGrids = grids.indicesOfEqual(nil);
		SerialOSCGrid.all.select { |grid| grid.client.isNil }.keep(indecesOfUnconnectedGrids.size) do: { |grid, index| this.connectGrid(indecesOfUnconnectedGrids[index], grid) };

		// TODO: add dependant for refreshing correctly and include SerialOSCGrid.clearLeds before invoking callback

		// TODO: encs!
*/
	}

/*
	TODO

	connectGrid { |index, grid|
		grids[index] !? {
			Error("Cannot connect device %, a device is already connected as grid %.".format(grid, index)).throw
		};
		grids[index] = grid;
		grid.addDependant { |thechanged, what| // TODO: add this dependant as a func somewhere and removeDependant on free
			if (what == 'removed') {
				onGridDisconnected.value(this, index);
				grids[index] = nil;
			};
		};
		responders = responders.add(GridKeyFunc.new( // TODO: it should be possible to cover for all devices and specify just one GridKeyFunc for device: \client -> client
		{ |x, y, state, time, device|
			gridKeyAction.value(this, x, y, state, index);
		}
		));
		onGridConnected.value(this, index);
		this.refreshGrid(index);
	}
*/

	refreshGrid { |index|
		this.clearLeds(index);
		gridRefreshAction.value(this, index);
	}

	free {
		willFree.value(this);
		// TODO: remove dependants added to devices
		// TODO: remove responder funcs queried for this client? perhaps not needed?
		// TODO responders do: _.free;
		onFree.value(this);
		all.remove(this);
	}

	clearLeds { |gridNo=0|
		grid !? { |grid| grid.clearLeds };
	}

	enableTilt { |n, gridNo=0|
		grid !? { |grid| grid.enableTilt(n) };
	}

	disableTilt { |n, gridNo=0|
		grid !? { |grid| grid.disableTilt(n) };
	}

	ledSet { |x, y, state, gridNo=0|
		grid !? { |grid| grid.ledSet(x, y, state) };
	}

	ledAll { |state, gridNo=0|
		grid !? { |grid| grid.ledAll(state) };
	}

	ledMap { |xOffset, yOffset, bitmasks, gridNo=0|
		grid !? { |grid| grid.ledMap(xOffset, yOffset, bitmasks) };
	}

	ledRow { |xOffset, y, bitmasks, gridNo=0|
		grid !? { |grid| grid.ledRow(xOffset, y, bitmasks) };
	}

	ledCol { |x, yOffset, bitmasks, gridNo=0|
		grid !? { |grid| grid.ledCol(x, yOffset, bitmasks) };
	}

	ledIntensity { |i, gridNo=0|
		grid !? { |grid| grid.ledIntensity(i) };
	}

	ledLevelSet { |x, y, l, gridNo=0|
		grid !? { |grid| grid.ledLevelSet(x, y, l) };
	}

	ledLevelAll { |l, gridNo=0|
		grid !? { |grid| grid.ledLevelAll(l) };
	}

	ledLevelMap { |xOffset, yOffset, levels, gridNo=0|
		grid !? { |grid| grid.ledLevelMap(xOffset, yOffset, levels) };
	}

	ledLevelRow { |xOffset, y, levels, gridNo=0|
		grid !? { |grid| grid.ledLevelRow(xOffset, y, levels) };
	}

	ledLevelCol { |x, yOffset, levels, gridNo=0|
		grid !? { |grid| grid.ledLevelCol(x, yOffset, levels) };
	}

	tiltSet { |n, state, gridNo=0|
		grid !? { |grid| grid.tiltSet(n, state) };
	}

	clearRings { |encNo=0|
		enc !? { |enc| enc.clearRings };
	}

	ringSet { |n, x, level, encNo=0|
		enc !? { |enc| enc.ringSet(n, x, level) };
	}

	ringAll { |n, level, encNo=0|
		enc !? { |enc| enc.ringAll(n, level) };
	}

	ringMap { |n, levels, encNo=0|
		enc !? { |enc| enc.ringMap(n, levels) };
	}

	ringRange { |n, x1, x2, level, encNo=0|
		enc !? { |enc| enc.ringRange(n, x1, x2, level) };
	}

/*
	TODO
	deviceIsConnected { |device| ^this.gridIsConnected(device) or: this.encIsConnected(device) }
	gridIsConnected { |grid| ^grids.indexOf(grid).notNil }
	encIsConnected { |enc| ^encs.indexOf(enc).notNil }
	deviceIndexOf { |device| ^(grids.indexOf(device) ? encs.indexOf(device)) }
*/
}

SerialOSCGrid : SerialOSCDevice {
	classvar <default, <all;
	var <ledXSpec, <ledYSpec; // TODO: implement grid bounds

	*initClass {
		all = [];
	}

	*new { |type, id, port|
		^super.new(type, id, port).initSerialOSCGrid;
	}

	initSerialOSCGrid {
		all = all.add(this);
	}

	*clearLeds {
		default !? { |grid| grid.clearLeds };
	}

	*enableTilt { |n|
		default !? { |grid| grid.enableTilt(n) };
	}

	*disableTilt { |n|
		default !? { |grid| grid.disableTilt(n) };
	}

	*ledSet { |x, y, state|
		default !? { |grid| grid.ledSet(x, y, state) };
	}

	*ledAll { |state|
		default !? { |grid| grid.ledAll(state) };
	}

	*ledMap { |xOffset, yOffset, bitmasks|
		default !? { |grid| grid.ledMap(xOffset, yOffset, bitmasks) };
	}

	*ledRow { |xOffset, y, bitmasks|
		default !? { |grid| grid.ledRow(xOffset, y, bitmasks) };
	}

	*ledCol { |x, yOffset, bitmasks|
		default !? { |grid| grid.ledCol(x, yOffset, bitmasks) };
	}

	*ledIntensity { |i|
		default !? { |grid| grid.ledIntensity(i) };
	}

	*ledLevelSet { |x, y, l|
		default !? { |grid| grid.ledLevelSet(x, y, l) };
	}

	*ledLevelAll { |l|
		default !? { |grid| grid.ledLevelAll(l) };
	}

	*ledLevelMap { |xOffset, yOffset, levels|
		default !? { |grid| grid.ledLevelMap(xOffset, yOffset, levels) };
	}

	*ledLevelRow { |xOffset, y, levels|
		default !? { |grid| grid.ledLevelRow(xOffset, y, levels) };
	}

	*ledLevelCol { |x, yOffset, levels|
		default !? { |grid| grid.ledLevelCol(x, yOffset, levels) };
	}

	*tiltSet { |n, state|
		default !? { |grid| grid.tiltSet(n, state) };
	}

	clearLeds {
		this.ledAll(0);
	}

	enableTilt { |n| this.tiltSet(n, true) }

	disableTilt { |n| this.tiltSet(n, false) }

	ledSet { |x, y, state|
		this.prSendMsg('/grid/led/set', x.asInteger, y.asInteger, state.asInteger);
	}

	ledAll { |state|
		this.prSendMsg('/grid/led/all', state.asInteger);
	}

	ledMap { |xOffset, yOffset, bitmasks|
		this.performList(\prSendMsg, ['/grid/led/map', xOffset.asInteger, yOffset.asInteger] ++ bitmasks);
	}

	ledRow { |xOffset, y, bitmasks|
		this.performList(\prSendMsg, ['/grid/led/row', xOffset.asInteger, y.asInteger] ++ bitmasks);
	}

	ledCol { |x, yOffset, bitmasks|
		this.performList(\prSendMsg, ['/grid/led/col', x.asInteger, yOffset.asInteger] ++ bitmasks);
	}

	ledIntensity { |i|
		this.prSendMsg('/grid/led/intensity', i.asInteger);
	}

	ledLevelSet { |x, y, l|
		this.prSendMsg('/grid/led/level/set', x.asInteger, y.asInteger, l.asInteger);
	}

	ledLevelAll { |l|
		this.prSendMsg('/grid/led/level/all', l.asInteger);
	}

	ledLevelMap { |xOffset, yOffset, levels|
		this.performList(\prSendMsg, ['/grid/led/level/map', xOffset.asInteger, yOffset.asInteger] ++ levels);
	}

	ledLevelRow { |xOffset, y, levels|
		this.performList(\prSendMsg, ['/grid/led/level/row', xOffset.asInteger, y.asInteger] ++ levels);
	}

	ledLevelCol { |x, yOffset, levels|
		this.performList(\prSendMsg, ['/grid/led/level/col', x.asInteger, yOffset.asInteger] ++ levels);
	}

	tiltSet { |n, state|
		this.prSendMsg('/tilt/set', n.asInteger, state.asInteger);
	}

	*default_ { |grid|
		var prevDefault;
		prevDefault = default;
		if (grid.isNil) {
			default = nil;
		} {
			if (SerialOSCClient.devices.includes(grid)) {
				// TODO: verify it's a SerialOSCGrid
				default = grid;
			} {
				Error("% is not in SerialOSCClient devices list".format(grid)).throw
			};
		};
		if (default != prevDefault) {
			this.changed(\default, default);
			NotificationCenter.notify(this, \default, default);
		}
	}

	prRemove {
		all.remove(this);
		this.changed(\removed);
	}
}

SerialOSCEnc : SerialOSCDevice {
	classvar <default, <all;
	classvar <ledXSpec, <ledLSpec;

	*initClass {
		ledXSpec = ControlSpec(0, 63, step: 1);
		ledLSpec = ControlSpec(0, 15, step: 1);
		all = [];
	}

	*new { |type, id, port|
		^super.new(type, id, port).initSerialOSCEnc;
	}

	initSerialOSCEnc {
		all = all.add(this);
	}

	*clearRings {
		default !? { |enc| enc.clearRings };
	}

	*ringSet { |n, x, level|
		default !? { |enc| enc.ringSet(n, x, level) };
	}

	*ringAll { |n, level|
		default !? { |enc| enc.ringAll(n, level) };
	}

	*ringMap { |n, levels|
		default !? { |enc| enc.ringMap(n, levels) };
	}

	*ringRange { |n, x1, x2, level|
		default !? { |enc| enc.ringRange(n, x1, x2, level) };
	}

	clearRings {
		4.do { |n| this.ringAll(n, 0) };
	}

	ringSet { |n, x, level|
		this.prSendMsg('/ring/set', n.asInteger, x.asInteger, level.asInteger);
	}

	ringAll { |n, level|
		this.prSendMsg('/ring/all', n.asInteger, level.asInteger);
	}

	ringMap { |n, levels|
		this.performList(\prSendMsg, ['/ring/map', n.asInteger] ++ levels);
	}

	ringRange { |n, x1, x2, level|
		this.prSendMsg('/ring/range', n.asInteger, x1.asInteger, x2.asInteger, level.asInteger);
	}

	*default_ { |enc|
		var prevDefault;
		prevDefault = default;
		if (enc.isNil) {
			default = nil;
		} {
			if (SerialOSCClient.devices.includes(enc)) {
				// TODO: verify it's a SerialOSCEnc
				default = enc;
			} {
				Error("% is not in SerialOSCClient devices list".format(enc)).throw
			};
		};
		if (default != prevDefault) {
			this.changed(\default, default);
			NotificationCenter.notify(this, \default, default);
		}
	}
}

SerialOSCDevice {
	var <>type, <>id, <>port;

	*new { arg type, id, port;
		^super.newCopyArgs(type, id, port)
	}

	printOn { arg stream;
		stream << this.class.name << "(" <<<*
			[type, id, port]  <<")"
	}

	prSendMsg { |address ...args|
		NetAddr("127.0.0.1", port).sendMsg(SerialOSC.getPrefixedAddress(address), *args);
	}

	matches { |that|
		^this==that or:{
			(this.type == that.type) and: (this.id == that.id) and: (this.port == that.port)
		}
	}

	client { ^SerialOSCClient.clientOf(this) }

	clientIndex { ^this.client.deviceIndexOf(this) }

}

SerialOSC {
	classvar
		trace=false,
		deviceListSemaphore,
		deviceInfoSemaphore,
		<isTrackingConnectedDevicesChanges=false,
		serialoscAddResponseListener,
		serialoscRemoveResponseListener,
		<prefix='/sclang',
		<port = 12002
	;

	*trace { |on=true| trace = on }

	*traceOutput { |str|
		trace.if {
			("SerialOSC trace:" + str).postln;
		};
	}

	*initClass {
		deviceListSemaphore = Semaphore.new;
		deviceInfoSemaphore = Semaphore.new;
	}

	*requestListOfDevices { |serialoscHost, func, timeout=0.1, verbose=false| // TODO: implement verbosity, print devices as MIDIClient.init
		var
			serialoscNetAddr,
			startListeningForSerialoscResponses,
			stopListeningForSerialoscResponses,
			setupListener,
			teardownListener,
			serialoscResponseListener
		;

		serialoscNetAddr=NetAddr(serialoscHost, SerialOSC.port);

		startListeningForSerialoscResponses = { |serialoscNetAddr, listOfDevices|
			setupListener.(serialoscNetAddr, listOfDevices);
			this.traceOutput( "Started listening to serialosc device list OSC responses" )
		};

		stopListeningForSerialoscResponses = {
			teardownListener.();
			this.traceOutput( "Stopped listening to serialosc device list OSC responses" )
		};

		setupListener = { |serialoscNetAddr, listOfDevices|
			serialoscResponseListener=OSCFunc.new(
				{ |msg, time, addr, recvPort|
					var id, type, receivePort;
					id = msg[1];
					type = msg[2];
					receivePort = msg[3].asInteger;
					this.traceOutput( "received: /serialosc/device % % % from %".format(id, type, receivePort, addr) );
					listOfDevices.add(
						IdentityDictionary[
							\id -> id,
							\type -> type,
							\receivePort -> receivePort
						]
					);
				},
				'/serialosc/device',
//		serialoscNetAddr // TODO: this is new, not sure if it works, test whether it does with a new device
			);
		};

		teardownListener = {
			serialoscResponseListener.free;
		};

		fork {
			var listOfDevices;

			deviceListSemaphore.wait;

			listOfDevices = List.new();

			startListeningForSerialoscResponses.(serialoscNetAddr, listOfDevices);

			this.prSendSerialoscListMsg(serialoscNetAddr);
			this.traceOutput( "waiting % seconds serialosc device list reponses...".format(timeout) );
			timeout.wait;
			stopListeningForSerialoscResponses.();

			deviceListSemaphore.signal;

			func.(listOfDevices);
		}
	}

	*getPrefixedAddress { |address|
		^(prefix.asString++address.asString).asSymbol
	}

	*prSendSerialoscListMsg { |serialoscNetAddr|
		var ip, port;

		ip = NetAddr.localAddr.ip;
		port = NetAddr.langPort;
		serialoscNetAddr.sendMsg("/serialosc/list", ip, port); // request a list of the currently connected devices, sent to host:port of SCLang
		this.traceOutput( "sent: /serialosc/list % % to %".format(ip, port, serialoscNetAddr) );
	}

	// TODO: start to use this and populate SerialOSCGrid and SerialOSCEnc instances with more detailed information
	*requestInformationAboutDevice { |serialoscHost, deviceReceivePort, func, timeout=0.1|
		var
			deviceReceiveNetAddr,
			startListeningForSerialoscDeviceResponses,
			stopListeningForSerialoscDeviceResponses,
			setupListeners,
			teardownListeners,
			serialoscDeviceResponseListeners
		;

		startListeningForSerialoscDeviceResponses = { |deviceReceiveNetAddr, deviceInfo|
			setupListeners.(deviceReceiveNetAddr, deviceInfo);
			this.traceOutput( "Started listening to serialosc device info OSC responses" )
		};

		stopListeningForSerialoscDeviceResponses = {
			teardownListeners.();
			this.traceOutput( "Stopped listening to serialosc device info OSC responses" )
		};

		setupListeners = { |deviceReceiveNetAddr, deviceInfo|
			serialoscDeviceResponseListeners=['port', 'host', 'id', 'prefix', 'rotation', 'size'].collect { |attribute|
				OSCFunc.new(
					{ |msg, time, addr, recvPort|
						this.traceOutput( "received: % from %".format(msg, addr) );
						switch (attribute,
							'port', { deviceInfo.put('destinationPort', msg[1].asInteger) },
							'host', { deviceInfo.put('destinationHost', msg[1]) },
							'id', { deviceInfo.put('id', msg[1]) },
							'prefix', { deviceInfo.put('prefix', msg[1]) },
							'rotation', { deviceInfo.put('rotation', msg[1].asInteger) },
							'size', {
								deviceInfo.put(
									'size',
										IdentityDictionary[
										\x -> msg[1].asInteger,
										\y -> msg[2].asInteger
									]
								)
							}
						);
					},
					("/sys/"++attribute.asString).asSymbol
				)
			};
		};

		teardownListeners = {
			serialoscDeviceResponseListeners.do(_.free);
		};

		fork {
			var deviceInfo;

			deviceListSemaphore.wait;

			deviceReceiveNetAddr=NetAddr(serialoscHost, deviceReceivePort);

			deviceInfo = IdentityDictionary.new;

			startListeningForSerialoscDeviceResponses.(deviceReceiveNetAddr, deviceInfo);

			this.prSendDeviceSysInfoMsg(deviceReceiveNetAddr);

			timeout.wait;

			stopListeningForSerialoscDeviceResponses.();

			deviceListSemaphore.signal;

			func.(deviceInfo);
		}
	}

	*prSendDeviceSysInfoMsg { |deviceReceiveNetAddr|
		var ip, port;

		ip = NetAddr.localAddr.ip;
		port = NetAddr.langPort;
		deviceReceiveNetAddr.sendMsg("/sys/info", ip, port); // request a list of the currently connected devices, sent to host:port of SCLang
		this.traceOutput( "sent: /sys/info % % to %".format(ip, port, deviceReceiveNetAddr) );
	}


	*changeDeviceDestinationPort { |serialoscHost, deviceReceivePort, deviceDestinationPort|
		var deviceReceiveNetAddr;
		deviceReceiveNetAddr = NetAddr(serialoscHost, deviceReceivePort);
		deviceReceiveNetAddr.sendMsg("/sys/port", deviceDestinationPort.asInteger);
		this.traceOutput( "sent: /sys/port % to %".format(deviceDestinationPort, deviceReceiveNetAddr) );
	}

	*changeDeviceDestinationHost { |serialoscHost, deviceReceivePort, deviceDestinationHost|
		var deviceReceiveNetAddr;
		deviceReceiveNetAddr = NetAddr(serialoscHost, deviceReceivePort);
		deviceReceiveNetAddr.sendMsg("/sys/host", deviceDestinationHost.asString);
		this.traceOutput( "sent: /sys/host % to %".format(deviceDestinationHost.asString, deviceReceiveNetAddr) );
	}

	*changeDeviceMessagePrefix { |serialoscHost, deviceReceivePort, deviceMessagePrefix|
		var deviceReceiveNetAddr;
		deviceReceiveNetAddr = NetAddr(serialoscHost, deviceReceivePort);
		deviceReceiveNetAddr.sendMsg("/sys/prefix", deviceMessagePrefix.asString);
		this.traceOutput( "sent: /sys/prefix % to %".format(deviceMessagePrefix.asString, deviceReceiveNetAddr) );
	}

	*changeDeviceRotation { |serialoscHost, deviceReceivePort, deviceRotation|
		var rotation;
		var deviceReceiveNetAddr;

		deviceReceiveNetAddr = NetAddr(serialoscHost, deviceReceivePort);

		rotation = deviceRotation.asInteger;
		[0, 90, 180, 270].includes(rotation).not.if { Error("Bad rotation: %".format(rotation)).throw };
		deviceReceiveNetAddr.sendMsg("/sys/rotation", rotation);
		this.traceOutput( "sent: /sys/rotation % to %".format(rotation, deviceReceiveNetAddr) );
	}

	*startTrackingConnectedDevicesChanges { |serialoscHost, addedFunc, removedFunc|
		var
			serialoscNetAddr,
			startListeningForSerialoscResponses,
			setupListeners
		;

		startListeningForSerialoscResponses = { |serialoscNetAddr, addedFunc, removedFunc|
			setupListeners.(serialoscNetAddr, addedFunc, removedFunc);
			isTrackingConnectedDevicesChanges = true;
		};

		setupListeners = { |serialoscNetAddr, addedFunc, removedFunc|
			serialoscAddResponseListener=OSCFunc.new(
				{ |msg, time, addr, recvPort|
					this.traceOutput( "received: % from %".format(msg, addr) );
					addedFunc.(msg[1]);
					serialoscNetAddr.sendMsg("/serialosc/notify", "127.0.0.1", NetAddr.langPort);
				},
				'/serialosc/add',
//		serialoscNetAddr // TODO: this is new, not sure if it works, test whether it does with a new device
			);
			serialoscRemoveResponseListener=OSCFunc.new(
				{ |msg, time, addr, recvPort|
					this.traceOutput( "received: % from %".format(msg, addr) );
					removedFunc.(msg[1]);
					serialoscNetAddr.sendMsg("/serialosc/notify", "127.0.0.1", NetAddr.langPort);
				},
				'/serialosc/remove',
//		serialoscNetAddr // TODO: this is new, not sure if it works, test whether it does with a new device
			);
			this.traceOutput( "Started listening to serialosc device add / remove OSC messages" )
		};

		isTrackingConnectedDevicesChanges.if { Error("Already tracking serialosc device changes.").throw };

		serialoscNetAddr=NetAddr(serialoscHost, SerialOSC.port);

		startListeningForSerialoscResponses.(serialoscNetAddr, addedFunc, removedFunc);

		serialoscNetAddr.sendMsg("/serialosc/notify", "127.0.0.1", NetAddr.langPort); // request that next device change (connect/disconnect) is sent to host:port
	}

	*stopTrackingConnectedDevicesChanges {
		var
			stopListeningForSerialoscResponses,
			teardownListeners;

		stopListeningForSerialoscResponses = {
			teardownListeners.();
			isTrackingConnectedDevicesChanges = false;
		};

		teardownListeners = {
			serialoscAddResponseListener.free;
			serialoscRemoveResponseListener.free;
			this.traceOutput( "Stopped listening to serialosc device add / remove OSC messages" )
		};

		isTrackingConnectedDevicesChanges.not.if { Error("Not listening for serialosc responses.").throw };
		stopListeningForSerialoscResponses.();
	}
}

GridKeydef : GridKeyFunc {
	classvar <all;
	var <key;

	*initClass {
		all = IdentityDictionary.new;
	}

	*new { arg key, func, x, y, state, device;
		var res = all.at(key), wasDisabled;
		if(res.isNil) {
			^super.new(func, x, y, state, device).addToAll(key);
		} {
			if(func.notNil) {
				wasDisabled = res.enabled.not;
				res.disable;
				try {
					res.init(func, x, y, state, device);
					if(wasDisabled, { res.disable; });
				} {|err|
					res.free;
					err.throw;
				}
			}
		}
		^res
	}

	*press { |key, func, x, y, device|
		^this.new(key, func, x, y, true, device);
	}

	*release { |key, func, x, y, device|
		^this.new(key, func, x, y, false, device);
	}

	addToAll {|argkey| key = argkey; all.put(key, this) }

	free { all[key] = nil; super.free; }

	printOn { arg stream; stream << this.class.name << "(" <<* [key, x, y, state, srcID] << ")" }

	*freeAll {
		var objs = all.copy;
		objs.do(_.free)
	}
}

GridKeyFunc : AbstractResponderFunc {
	var <x, <y, <state;

	*initClass {
		Class.initClassTree(SerialOSCMessageDispatcher);
	}

	*new { |func, x, y, state, device|
		^super.new.init(func, x, y, state, device);
	}

	*press { |func, x, y, device|
		^this.new(func, x, y, true, device);
	}

	*release { |func, x, y, device|
		^this.new(func, x, y, false, device);
	}

// TODO	*cmdPeriod { this.trace(false) }

	init {|argfunc, argx, argy, argstate, argdevice|
		x = argx ? x;
		y = argy ? y;
		state = argstate ? state;
		if (state.notNil) {
			state = state.asInteger;
		};
		srcID = argdevice ? srcID;
		func = argfunc ? func;
		dispatcher = SerialOSCMessageDispatcher.default;
		this.enable;
		allFuncProxies.add(this);
	}

	type { ^'/grid/key' }

	printOn { arg stream; stream << this.class.name << "(" <<* [x, y, state, srcID] << ")" }
}

Tiltdef : TiltFunc {
	classvar <all;
	var <key;

	*initClass {
		all = IdentityDictionary.new;
	}

	*new { arg key, func, n, x, y, z, device;
		var res = all.at(key), wasDisabled;
		if(res.isNil) {
			^super.new(func, n, x, y, z, device).addToAll(key);
		} {
			if(func.notNil) {
				wasDisabled = res.enabled.not;
				res.disable;
				try {
					res.init(func, n, x, y, z, device);
					if(wasDisabled, { res.disable; });
				} {|err|
					res.free;
					err.throw;
				}
			}
		}
		^res
	}

	addToAll {|argkey| key = argkey; all.put(key, this) }

	free { all[key] = nil; super.free; }

	printOn { arg stream; stream << this.class.name << "(" <<* [key, n, x, y, z, srcID] << ")" }

	*freeAll {
		var objs = all.copy;
		objs.do(_.free)
	}
}

TiltFunc : AbstractResponderFunc {
	var <n, <x, <y, <z;

	*initClass {
		Class.initClassTree(SerialOSCMessageDispatcher);
	}

	*new { |func, n, x, y, z, device|
		^super.new.init(func, n, x, y, z, device);
	}

// TODO	*cmdPeriod { this.trace(false) }

	init {|argfunc, argn, argx, argy, argz, argdevice|
		n = argn ? n;
		x = argx ? x;
		y = argy ? y;
		z = argz ? z;
		srcID = argdevice ? srcID;
		func = argfunc ? func;
		dispatcher = SerialOSCMessageDispatcher.default;
		this.enable;
		allFuncProxies.add(this);
	}

	type { ^'/tilt' }

	printOn { arg stream; stream << this.class.name << "(" <<* [n, x, y, z, srcID] << ")" }
}

EncDeltadef : EncDeltaFunc {
	classvar <all;
	var <key;

	*initClass {
		all = IdentityDictionary.new;
	}

	*new { arg key, func, n, delta, device;
		var res = all.at(key), wasDisabled;
		if(res.isNil) {
			^super.new(func, n, delta, device).addToAll(key);
		} {
			if(func.notNil) {
				wasDisabled = res.enabled.not;
				res.disable;
				try {
					res.init(func, n, delta, device);
					if(wasDisabled, { res.disable; });
				} {|err|
					res.free;
					err.throw;
				}
			}
		}
		^res
	}

	addToAll {|argkey| key = argkey; all.put(key, this) }

	free { all[key] = nil; super.free; }

	printOn { arg stream; stream << this.class.name << "(" <<* [key, n, delta, srcID] << ")" }

	*freeAll {
		var objs = all.copy;
		objs.do(_.free)
	}
}

EncDeltaFunc : AbstractResponderFunc {
	var <n, <delta;

	*initClass {
		Class.initClassTree(SerialOSCMessageDispatcher);
	}

	*new { |func, n, delta, device|
		^super.new.init(func, n, delta, device);
	}

// TODO	*cmdPeriod { this.trace(false) }

	init {|argfunc, argn, argdelta, argdevice|
		n = argn ? n;
		delta = argdelta ? delta;
		srcID = argdevice ? srcID;
		func = argfunc ? func;
		dispatcher = SerialOSCMessageDispatcher.default;
		this.enable;
		allFuncProxies.add(this);
	}

	type { ^'/enc/delta' }

	printOn { arg stream; stream << this.class.name << "(" <<* [n, delta, srcID] << ")" }
}

EncKeydef : EncKeyFunc {
	classvar <all;
	var <key;

	*initClass {
		all = IdentityDictionary.new;
	}

	*new { arg key, func, n, state, device;
		var res = all.at(key), wasDisabled;
		if(res.isNil) {
			^super.new(func, n, state, device).addToAll(key);
		} {
			if(func.notNil) {
				wasDisabled = res.enabled.not;
				res.disable;
				try {
					res.init(func, n, state, device);
					if(wasDisabled, { res.disable; });
				} {|err|
					res.free;
					err.throw;
				}
			}
		}
		^res
	}

	addToAll {|argkey| key = argkey; all.put(key, this) }

	free { all[key] = nil; super.free; }

	printOn { arg stream; stream << this.class.name << "(" <<* [key, n, state, srcID] << ")" }

	*freeAll {
		var objs = all.copy;
		objs.do(_.free)
	}
}

EncKeyFunc : AbstractResponderFunc {
	var <n, <state;

	*initClass {
		Class.initClassTree(SerialOSCMessageDispatcher);
	}

	*new { |func, n, state, device|
		^super.new.init(func, n, state, device);
	}

// TODO	*cmdPeriod { this.trace(false) }

	init {|argfunc, argn, argstate, argdevice|
		n = argn ? n;
		state = argstate ? state;
		srcID = argdevice ? srcID;
		func = argfunc ? func;
		dispatcher = SerialOSCMessageDispatcher.default;
		this.enable;
		allFuncProxies.add(this);
	}

	type { ^'/enc/key' }

	printOn { arg stream; stream << this.class.name << "(" <<* [n, state, srcID] << ")" }
}

SerialOSCMessageDispatcher : AbstractWrappingDispatcher {
	classvar <default;

	*initClass {
		default = SerialOSCMessageDispatcher.new;
	}

	wrapFunc {|funcProxy|
		var func, srcID;
		func = funcProxy.func;
		srcID = funcProxy.srcID;
		func = switch(funcProxy.type,
			'/grid/key', {
				if (funcProxy.x.isNil and: funcProxy.y.isNil and: funcProxy.state.isNil) {
					func
				} {
					SerialOSCXYStateMatcher.new(funcProxy.x, funcProxy.y, funcProxy.state, func);
				}
			},
			'/tilt', {
				if (funcProxy.n.isNil and: funcProxy.x.isNil and: funcProxy.y.isNil and: funcProxy.z.isNil) {
					func
				} {
					SerialOSCNXYZMatcher.new(funcProxy.n, funcProxy.x, funcProxy.y, funcProxy.z, func);
				}
			},
			'/enc/delta', {
				if (funcProxy.n.isNil and: funcProxy.delta.isNil) {
					func
				} {
					SerialOSCNDeltaMatcher.new(funcProxy.n, funcProxy.delta, func);
				}
			},
			'/enc/key', {
				if (funcProxy.n.isNil and: funcProxy.state.isNil) {
					func
				} {
					SerialOSCNStateMatcher.new(funcProxy.n, funcProxy.state, func);
				}
			}
		);
		^case(
			{ srcID.notNil }, { SerialOSCFuncDeviceMatcher(srcID, func) },
			{ func }
		);
	}

	getKeysForFuncProxy {|funcProxy| ^[funcProxy.type];}

	value {|type, args, time, srcID|
		switch (type,
			'/grid/key', {
				active[type].value(args[0], args[1], args[2], time, srcID);
			},
			'/enc/delta', {
				active[type].value(args[0], args[1], time, srcID);
			},
			'/enc/key', {
				active[type].value(args[0], args[1], time, srcID);
			},
			'/tilt', {
				active[type].value(args[0], args[1], args[2], args[3], time, srcID);
			},
		)
	}

	register {
		SerialOSCClient.addSerialOSCRecvFunc(this);
		registered = true;
	}

	unregister {
		SerialOSCClient.removeSerialOSCRecvFunc(this);
		registered = false;
	}

	typeKey { ^('SerialOSC control').asSymbol }
}

SerialOSCXYStateMatcher : AbstractMessageMatcher { // TODO: optimization idea: split up x, y and state in different instances?
	var x, y, state;

	*new {|x, y, state, func| ^super.new.init(x, y, state, func);}

	init {|argx, argy, argstate, argfunc| x = argx; y = argy; state = argstate; func = argfunc; }

	value {|testX, testY, testState, time, srcID|
		if (x.matchItem(testX) and: y.matchItem(testY) and: state.matchItem(testState)) {
			func.value(testX, testY, testState, time, srcID)
		}
	}
}

SerialOSCNXYZMatcher : AbstractMessageMatcher { // TODO: optimization idea: split up n, x, y and z in different instances?
	var n, x, y, z;

	*new {|n, x, y, z, func| ^super.new.init(n, x, y, z, func);}

	init {|argn, argx, argy, argz, argfunc| n = argn; x = argx; y = argy; z = argz; func = argfunc; }

	value {|testN, testX, testY, testZ, time, srcID|
		if (n.matchItem(testN) and: x.matchItem(testX) and: y.matchItem(testY) and: z.matchItem(testZ)) {
			func.value(testN, testX, testY, testZ, time, srcID)
		}
	}
}

SerialOSCNDeltaMatcher : AbstractMessageMatcher { // TODO: optimization idea: split up n and delta in different instances?
	var n, delta;

	*new {|n, delta, func| ^super.new.init(n, delta, func);}

	init {|argn, argdelta, argfunc| n = argn; delta = argdelta; func = argfunc; }

	value {|testN, testDelta, time, srcID|
		if (n.matchItem(testN) and: delta.matchItem(testDelta)) {
			func.value(testN, testDelta, time, srcID)
		}
	}
}

SerialOSCNStateMatcher : AbstractMessageMatcher { // TODO: optimization idea: split up n and state in different instances?
	var n, state;

	*new {|n, state, func| ^super.new.init(n, state, func);}

	init {|argn, argstate, argfunc| n = argn; state = argstate; func = argfunc; }

	value {|testN, testState, time, srcID|
		if (n.matchItem(testN) and: state.matchItem(testState)) {
			func.value(testN, testState, time, srcID)
		}
	}
}

SerialOSCFuncDeviceMatcher : AbstractMessageMatcher {
	var device;

	*new{|device, func| ^super.new.init(device, func) }

	init {|argdevice, argfunc| device = argdevice; func = argfunc; }

	value {|...testMsg|
/*
		TODO: replaced this with a suggestion, to also be able to match by association id -> 'm070' eller port -> 1234 or type -> 'monome 64'
		if(device == testMsg.last, {func.value(*testMsg)});
		TODO: below is untested
*/

/*
		TODO: add 'default' symbol check for filtering on default device, is it possible?
		TODO: add 'numCols' check for filtering on device with a certain number of (or range of) cols
		TODO: add 'numRows' check for filtering on device with a certain number of (or rang of) rows
*/
		var testMsgDevice;
		testMsgDevice = testMsg.last;
		case
		{ device === testMsgDevice } { // TODO: verify === works as intended
			func.value(*testMsg)
		}
		{ device.respondsTo(\matchItem) } {
			if (device.matchItem(testMsgDevice)) { func.value(*testMsg) }
		}
		{ device.respondsTo(\key) and: device.respondsTo(\value) } {
			if (
				((device.key == \id) and: (device.value == testMsgDevice.id)) or:
				((device.key == \port) and: (device.value == testMsgDevice.port)) or:
				((device.key == \type) and: (device.value == testMsgDevice.type)) or:
				((device.key == \client) and: (device.value == testMsgDevice.client))
			) { func.value(*testMsg) };
		}
		{ device == 'default' } {
			if (
				( (SerialOSCGrid == testMsgDevice.class) and: (SerialOSCGrid.default == testMsgDevice) )
				or:
				( (SerialOSCEnc == testMsgDevice.class) and: (SerialOSCEnc.default == testMsgDevice) )
			) {
				func.value(*testMsg)
			}
		}
	}
}
