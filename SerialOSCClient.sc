// TODO: MIDIFunc.learn
SerialOSCClient {
	classvar
		<all,
		<devices,
		devicesSemaphore,
		<initialized=false,
		<connectedDevices,
		oscRecvFunc,
		autoconnectDevices,
		recvSerialOSCFunc,
		beVerbose
	;

	var <name;
	var <gridSpec, <encSpec;
	var <grid, <enc;
	var <autoconnect;
	var gridResponder, tiltResponder, gridDependantFunc;
	var encDeltaResponder, encKeyResponder, encDependantFunc;
	var <>willFree;
	var <>permanent;
	var <>onFree; // TODO: naming? compare with Window
	var <>onGridConnected, <>onGridDisconnected, <>gridRefreshAction;
	var <>onEncConnected, <>onEncDisconnected, <>encRefreshAction;
	var <>gridKeyAction, <>encDeltaAction, <>encKeyAction, <>tiltAction;

	*initClass {
		all = [];
		devices = [];
		connectedDevices = [];
		devicesSemaphore = Semaphore.new;
		CmdPeriod.add(this);
		oscRecvFunc = { |msg, time, addr, recvPort|
			if (addr.ip == "127.0.0.1") {
				this.prLookupDeviceByPort(addr.port) !? { |device|
					if (connectedDevices.includes(device)) {
						// TODO: use /monome instead of /sclang ?
						if (#['/sclang/grid/key', '/sclang/tilt', '/sclang/enc/delta', '/sclang/enc/key'].includes(msg[0])) { // note: no pattern matching is performed on OSC address
							var type = msg[0].asString[7..].asSymbol;
							recvSerialOSCFunc.value(type, msg[1..], time, device);
						};
					};
				};
			};
		};
	}

	*init { |completionFunc, autoconnect=true, supportHotPlugging=true, verbose=false|
		autoconnectDevices = autoconnect;
		beVerbose = verbose;

		if (SerialOSCComm.isTrackingConnectedDevicesChanges) {
			SerialOSCComm.stopTrackingConnectedDevicesChanges
		};

		if (supportHotPlugging) {
			SerialOSCComm.startTrackingConnectedDevicesChanges(
				"127.0.0.1",
				{ |id|
					fork {
						devicesSemaphore.wait;
						if (this.prLookupDeviceById(id).isNil) {
							this.prUpdateDevicesListAsync {
								this.prLookupDeviceById(id) !? { |device|
									this.prPostDeviceAdded(device);
									this.changed(\attached, device);
									this.prNotifyChangesInDevicesList([device], []);
									if (autoconnectDevices) { this.connect(device) };
									this.prUpdateDefaultDevices([device], []);
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
						this.prLookupDeviceById(id) !? { |device|
							this.disconnect(device);
							devices.remove(device);
							device.remove;
							this.prNotifyChangesInDevicesList([], [device]);
							this.prUpdateDefaultDevices([], [device]);
							this.changed(\detached, device);
							this.prPostDeviceRemoved(device);
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
		this.prUpdateDevicesListAsync { |devicesAddedToDevicesList, devicesRemovedFromDevicesList|
			this.postDevices;
			this.prNotifyChangesInDevicesList(devicesAddedToDevicesList, devicesRemovedFromDevicesList);
			devicesRemovedFromDevicesList.do { |serialOSCDevice|
				serialOSCDevice.remove;
			};
			if (autoconnectDevices) { this.connectAll(false) };
			this.prUpdateDefaultDevices(devicesAddedToDevicesList, devicesRemovedFromDevicesList);
			completionFunc.();
		};
		devicesSemaphore.signal;
	}

	*addSerialOSCRecvFunc { |func| recvSerialOSCFunc = recvSerialOSCFunc.addFunc(func) }

	*removeSerialOSCRecvFunc { |func| recvSerialOSCFunc = recvSerialOSCFunc.removeFunc(func) }

	*freeAll {
		all.copy do: _.free;
	}

	*cmdPeriod {
		all.reject { |client| client.permanent }.copy.do(_.free);
	}

	*prNotifyChangesInDevicesList { |devicesAddedToDevicesList, devicesRemovedFromDevicesList|
		devicesAddedToDevicesList do: { |device| this.changed(\added, device) };
		devicesRemovedFromDevicesList do: { |device| this.changed(\removed, device) };
	}

	*prUpdateDefaultDevices { |devicesAddedToDevicesList, devicesRemovedFromDevicesList|
		// TODO: important, only connected devices should be able to set as default, right? cover for the case when autoconnect is not used...
		var addedGrids, addedEncs;

		addedGrids = devicesAddedToDevicesList.reject(this.prDeviceIsEncByType(_));
		if (devices.includes(SerialOSCGrid.default).not) { // TODO: instead or also check if devicesRemovedFromDevicesList includes SerialOSCGrid.default?
			SerialOSCGrid.default = if (addedGrids.notEmpty) {
				addedGrids.first
			} {
				nil // TODO: fall back on existing grids - if any (the scenario when one device is being detached) - but NOT any connected to and client - and set one as default?
			};
		};

		addedEncs = devicesAddedToDevicesList.select(this.prDeviceIsEncByType(_));
		if (devices.includes(SerialOSCEnc.default).not) { // TODO: instead or also check if devicesRemovedFromDevicesList includes SerialOSCEnc.default?
			SerialOSCEnc.default = if (addedEncs.notEmpty) {
				addedEncs.first
			} {
				nil // TODO: fall back on existing encs - if any (the scenario when one device is being detached) - but NOT any connected to and client - and set one as default?
			};
		};
	}

	*connectAll {
		devices do: { |device| this.connect(device) }
	}

	*connect { |device|
		this.ensureInitialized;

		if (connectedDevices.includes(device).not) {
			SerialOSCComm.changeDeviceMessagePrefix(
				"127.0.0.1",
				device.port,
				SerialOSCComm.prefix
			);

			SerialOSCComm.changeDeviceDestinationPort(
				"127.0.0.1",
				device.port,
				NetAddr.langPort
			);

			connectedDevices = connectedDevices.add(device);

			this.changed(\connected, device);

			SerialOSCClient.all.do { |client|
				if (client.autoconnect) { client.findAndConnectDevicesToClient };
			};

			beVerbose.if {
				Post << device << Char.space << "was connected" << Char.nl;
			};
		};
	}

	*explicitlyAddDevice { |device|
		devices = devices.add(device);
	}

	*doGridKeyAction { |x, y, state, device|
		this.prDispatchEvent('/grid/key', [x.asInteger, y.asInteger, state.asInteger], device);
	}

	*doEncDeltaAction { |n, delta, device|
		this.prDispatchEvent('/enc/delta', [n.asInteger, delta.asInteger], device);
	}

	*doTiltAction { |n, x, y, z, device|
		this.prDispatchEvent('/tilt', [n.asInteger, x.asInteger, y.asInteger, z.asInteger], device);
	}

	*doEncKeyAction { |n, state, device|
		this.prDispatchEvent('/enc/key', [n.asInteger, state.asInteger], device);
	}

	*prDispatchEvent { |type, args, device|
		this.ensureInitialized;
		recvSerialOSCFunc.value(type, args, SystemClock.seconds, device);
	}

	*disconnectAll {
		devices do: { |device| this.disconnect(device) }
	}

	*disconnect { |device|
		this.ensureInitialized;

		if (connectedDevices.includes(device)) {
			this.changed(\disconnected, device);
			device.changed(\disconnected);

			connectedDevices.remove(device);

			beVerbose.if {
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

	*prPostDeviceAdded { |device|
		Post << "A SerialOSC Device was attached to the computer:" << Char.nl;
		Post << Char.tab << device << Char.nl;
	}

	*prPostDeviceRemoved { |device|
		Post << "A SerialOSC Device was detached from the computer:" << Char.nl;
		Post << Char.tab << device << Char.nl
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
		SerialOSCComm.requestListOfDevices(
			"127.0.0.1",
			{ |list|
				var currentDevices, foundDevices, devicesToRemove, devicesToAdd;

				currentDevices = devices.as(IdentitySet);
				foundDevices = list.collect { |entry|
					devices.detect { |serialOSCDevice| serialOSCDevice.id == entry[\id] } ?? {
						if (this.prListEntryIsEncByType(entry)) {
							SerialOSCEnc(entry[\type], entry[\id], entry[\receivePort]);
						} {
							SerialOSCGrid(entry[\type], entry[\id], entry[\receivePort]);
						};
					};
				}.as(IdentitySet);

				devicesToRemove = currentDevices - foundDevices;
				devicesToAdd = foundDevices - currentDevices;

				// TODO: existing devices, not removed or added, that are in connectedDevices but do not have proper routings ought to be reconnected if autoconnectDevices is true

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

	*ensureInitialized {
		initialized.not.if { Error("SerialOSCClient has not been initialized").throw };
	}

	*grid { |name, func, autoconnect=true|
		^this.new(name, \any, \none, func, autoconnect);
	}

	*enc { |name, func, autoconnect=true|
		^this.new(name, \none, \any, func, autoconnect);
	}

	*gridEnc { |name, func, autoconnect=true|
		^this.new(name, \any, \any, func, autoconnect);
	}

	*new { |name, gridSpec=\none, encSpec=\none, func, autoconnect=true|
		^super.new.initSerialOSCClient(name, gridSpec, encSpec, func, autoconnect)
	}

	initSerialOSCClient { |argName, argGridSpec, argEncSpec, func, argAutoconnect|
		var doWhenInitialized;

		name = argName;
		gridSpec = argGridSpec;
		encSpec = argEncSpec;
		autoconnect = argAutoconnect;

		permanent = false;

		gridDependantFunc = { |thechanged, what|
			if (what == \disconnected) {
				this.disconnectGridFromClient;
			};
			if (what == \rotation) {
				this.warnIfGridDoNotMatchSpec;
			}
		};

		encDependantFunc = { |thechanged, what|
			if (what == \disconnected) {
				this.disconnectEncFromClient;
			};
		};

		doWhenInitialized = {
			if (argAutoconnect) { this.findAndConnectDevicesToClient };
		};

		if (SerialOSCClient.initialized, doWhenInitialized, {
			SerialOSCClient.init(completionFunc: doWhenInitialized);
		});

		all = all.add(this);

		func.value(this);
	}

	printOn { arg stream;
		stream << this.class.name << "(" <<<*
			[name, gridSpec, encSpec]  <<")"
	}

	usesGrid {
		^gridSpec != \none
	}

	usesEnc {
		^encSpec != \none
	}

	findGrid {
		^if (SerialOSCGrid.default.notNil) {
			if (SerialOSCGrid.default.client.isNil) {
				SerialOSCGrid.default;
			}
		} ?? SerialOSCGrid.all.select {|grid|grid.client.isNil}.first;
	}

	findEnc {
		^if (SerialOSCEnc.default.notNil) {
			if (SerialOSCEnc.default.client.isNil) {
				SerialOSCEnc.default;
			}
		} ?? SerialOSCEnc.all.select {|enc|enc.client.isNil}.first;
	}

	findAndConnectDevicesToClient {
		if (this.usesGrid and: grid.isNil) {
			grid = this.findGrid;
			grid.notNil.if { this.prConnectClientToGrid }
		};

		if (this.usesEnc and: enc.isNil) {
			enc = this.findEnc;
			enc.notNil.if { this.prConnectClientToEnc }
		};
	}

	prConnectClientToGrid {
		grid.addDependant(gridDependantFunc);
		gridResponder = GridKeyFunc.new(
			{ |x, y, state, time, device|
				gridKeyAction.value(this, x, y, state);
			},
			device: \client -> this
		);
		gridResponder.permanent = true;
		tiltResponder = TiltFunc.new(
			{ |n, x, y, z, time, device|
				tiltAction.value(this, n, x, y, z);
			},
			device: \client -> this
		);
		tiltResponder.permanent = true;
		grid.client = this;
		onGridConnected.value(this);
		beVerbose.if {
			Post << grid << Char.space << "was routed to client" << this << Char.nl;
		};
		this.refreshGrid;
	}

	prConnectClientToEnc {
		enc.addDependant(encDependantFunc);
		encDeltaResponder = EncDeltaFunc.new(
			{ |n, delta, time, device|
				encDeltaAction.value(this, n, delta);
			},
			device: \client -> this
		);
		encDeltaResponder.permanent = true;
		encKeyResponder = EncKeyFunc.new(
			{ |n, state, time, device|
				encKeyAction.value(this, n, state);
			},
			device: \client -> this
		);
		encKeyResponder.permanent = true;
		enc.client = this;
		onEncConnected.value(this);
		beVerbose.if {
			Post << enc << Char.space << "was routed to client" << this << Char.nl;
		};
		this.refreshEnc;
	}

	*route { |device, client|
		if (device.respondsTo(\ledSet)) {
			if (client.usesGrid) {
				if (client.grid.notNil) { this.disconnectGridFromClient };
				client.grid = device;
				client.prConnectClientToGrid;
				client.warnIfGridDoNotMatchSpec;
			} {
				"Client % does not use a grid".format(client).postln;
			};
		};

		if (device.respondsTo(\ringSet)) {
			if (client.usesEnc) {
				if (client.enc.notNil) { client.disconnectEncFromClient };
				client.enc = device;
				client.prConnectClientToEnc;
				client.warnIfEncDoNotMatchSpec;
			} {
				"Client % does not use an enc".format(client).postln;
			};
		};
	}

	warnIfGridDoNotMatchSpec {
		this.gridMatchSpec(grid).not.if {
			"Grid % does not match client % spec: %".format(grid, this, gridSpec).postln
		}
	}

	warnIfEncDoNotMatchSpec {
		this.encMatchSpec(enc).not.if {
			"Enc % does not match client % spec: %".format(grid, this, encSpec).postln
		}
	}

	gridMatchSpec { |argGrid|
		^if (gridSpec == \any) {
			true
		} {
			// TODO
		}
	}

	encMatchSpec { |argEnc|
		^if (encSpec == \any) {
			true
		} {
			// TODO
		}
	}

	refreshGrid {
		this.clearLeds;
		fork {
			0.01.wait;
			gridRefreshAction.value(this);
		}
	}

	refreshEnc {
		this.clearRings;
		fork {
			0.01.wait;
			encRefreshAction.value(this);
		}
	}

	disconnectGridFromClient {
		var disconnected;
		disconnected = grid;
		grid !? {
			grid.removeDependant(gridDependantFunc);
			grid.client = nil;
			grid.clearLeds;
			grid = nil;
		};
		gridResponder.free;
		tiltResponder.free;
		onGridDisconnected.value(this, disconnected);
	}

	disconnectEncFromClient {
		var disconnected;
		disconnected = enc;
		enc !? {
			enc.removeDependant(encDependantFunc);
			enc.client = nil;
			enc.clearRings;
			enc = nil;
		};
		encDeltaResponder.free;
		encKeyResponder.free;
		onEncDisconnected.value(this);
	}

	free {
		willFree.value(this);
		if (this.usesGrid) { this.disconnectGridFromClient };
		if (this.usesEnc) { this.disconnectEncFromClient };
		onFree.value(this);
		all.remove(this);
	}

	clearLeds {
		grid !? { |grid| grid.clearLeds };
	}

	enableTilt { |n|
		grid !? { |grid| grid.enableTilt(n) };
	}

	disableTilt { |n|
		grid !? { |grid| grid.disableTilt(n) };
	}

	ledSet { |x, y, state|
		grid !? { |grid| grid.ledSet(x, y, state) };
	}

	ledAll { |state|
		grid !? { |grid| grid.ledAll(state) };
	}

	ledMap { |xOffset, yOffset, bitmasks|
		grid !? { |grid| grid.ledMap(xOffset, yOffset, bitmasks) };
	}

	ledRow { |xOffset, y, bitmasks|
		grid !? { |grid| grid.ledRow(xOffset, y, bitmasks) };
	}

	ledCol { |x, yOffset, bitmasks|
		grid !? { |grid| grid.ledCol(x, yOffset, bitmasks) };
	}

	ledIntensity { |i|
		grid !? { |grid| grid.ledIntensity(i) };
	}

	ledLevelSet { |x, y, l|
		grid !? { |grid| grid.ledLevelSet(x, y, l) };
	}

	ledLevelAll { |l|
		grid !? { |grid| grid.ledLevelAll(l) };
	}

	ledLevelMap { |xOffset, yOffset, levels|
		grid !? { |grid| grid.ledLevelMap(xOffset, yOffset, levels) };
	}

	ledLevelRow { |xOffset, y, levels|
		grid !? { |grid| grid.ledLevelRow(xOffset, y, levels) };
	}

	ledLevelCol { |x, yOffset, levels|
		grid !? { |grid| grid.ledLevelCol(x, yOffset, levels) };
	}

	tiltSet { |n, state|
		grid !? { |grid| grid.tiltSet(n, state) };
	}

	clearRings {
		enc !? { |enc| enc.clearRings };
	}

	ringSet { |n, x, level|
		enc !? { |enc| enc.ringSet(n, x, level) };
	}

	ringAll { |n, level|
		enc !? { |enc| enc.ringAll(n, level) };
	}

	ringMap { |n, levels|
		enc !? { |enc| enc.ringMap(n, levels) };
	}

	ringRange { |n, x1, x2, level|
		enc !? { |enc| enc.ringRange(n, x1, x2, level) };
	}
}

SerialOSCGrid : SerialOSCDevice {
	classvar <default, <all;
	var <ledXSpec, <ledYSpec;

	*initClass {
		all = [];
	}

	*new { |type, id, port, rotation|
		^super.new(type, id, port, rotation).initSerialOSCGrid;
	}

	deviceNumColsFromType {
		^switch (type)
			{ 'monome 64' } { 8 }
			{ 'monome 128' } { 16 }
			{ 'monome 256' } { 16 }
	}

	deviceNumRowsFromType {
		^switch (type)
			{ 'monome 64' } { 8 }
			{ 'monome 128' } { 8 }
			{ 'monome 256' } { 16 }
	}

	realNumCols {
		^case 
			{ [0, 180].includes(rotation) } { this.deviceNumColsFromType }
			{ [90, 270].includes(rotation) } { this.deviceNumRowsFromType }
	}

	realNumRows {
		^case 
			{ [0, 180].includes(rotation) } { this.deviceNumRowsFromType }
			{ [90, 270].includes(rotation) } { this.deviceNumColsFromType }
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
				if (grid.respondsTo(\ledSet)) {
					default = grid;
				} {
					Error("Not a grid: %".format(grid)).throw
				}
			} {
				Error("% is not in SerialOSCClient devices list".format(grid)).throw
			};
		};
		if (default != prevDefault) {
			this.changed(\default, default);
		}
	}

	remove {
		all.remove(this);
		super.remove;
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

	*new { |type, id, port, rotation|
		^super.new(type, id, port, rotation).initSerialOSCEnc;
	}

	getNumEncs {
		^switch (type)
			{ 'monome arc 2' } { 2 }
			{ 'monome arc 4' } { 4 }
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
				if (enc.respondsTo(\ringSet)) {
					default = enc;
				} {
					Error("Not an enc: %".format(enc)).throw
				}
			} {
				Error("% is not in SerialOSCClient devices list".format(enc)).throw
			};
		};
		if (default != prevDefault) {
			this.changed(\default, default);
		}
	}

	remove {
		all.remove(this);
		super.remove;
	}
}

SerialOSCDevice {
	var <>type, <>id, <>port, <client;
	var <rotation;

	*new { arg type, id, port, rotation;
		^super.newCopyArgs(type, id, port, rotation)
	}

	client_ { |argClient|
		client = argClient;
		this.changed(\client, client);
	}

	printOn { arg stream;
		stream << this.class.name << "(" <<<*
			[type, id, port]  <<")"
	}

	setRotation { |degrees|
		SerialOSCComm.changeDeviceRotation("127.0.0.1", port, degrees);
		rotation = degrees;
		this.changed(\rotation, degrees);
	}

	prSendMsg { |address ...args|
		NetAddr("127.0.0.1", port).sendMsg(SerialOSCComm.getPrefixedAddress(address), *args);
	}

	matches { |that|
		^this==that or:{
			(this.type == that.type) and: (this.id == that.id) and: (this.port == that.port)
		}
	}

	remove {
		client = nil;
		this.changed(\removed);
	}
}

SerialOSCComm {
	classvar
		trace=false,
		deviceListSemaphore,
		deviceInfoSemaphore,
		<isTrackingConnectedDevicesChanges=false,
		serialoscAddResponseListener,
		serialoscRemoveResponseListener,
		<prefix='/sclang', // TODO: change here too?
		<serialoscPort = 12002
	;

	*trace { |on=true| trace = on }

	*traceOutput { |str|
		trace.if {
			("SerialOSCComm trace:" + str).postln;
		};
	}

	*initClass {
		deviceListSemaphore = Semaphore.new;
		deviceInfoSemaphore = Semaphore.new;
	}

	*requestListOfDevices { |serialoscHost, func, timeout=0.1|
		var
			serialoscNetAddr,
			startListeningForSerialoscResponses,
			stopListeningForSerialoscResponses,
			setupListener,
			teardownListener,
			serialoscResponseListener
		;

		serialoscNetAddr=NetAddr(serialoscHost, SerialOSCComm.serialoscPort);

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
				serialoscNetAddr
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
		// TODO: update device info list??
		this.traceOutput( "sent: /sys/port % to %".format(deviceDestinationPort, deviceReceiveNetAddr) );
	}

	*changeDeviceDestinationHost { |serialoscHost, deviceReceivePort, deviceDestinationHost|
		var deviceReceiveNetAddr;
		deviceReceiveNetAddr = NetAddr(serialoscHost, deviceReceivePort);
		deviceReceiveNetAddr.sendMsg("/sys/host", deviceDestinationHost.asString);
		// TODO: update device info list??
		this.traceOutput( "sent: /sys/host % to %".format(deviceDestinationHost.asString, deviceReceiveNetAddr) );
	}

	*changeDeviceMessagePrefix { |serialoscHost, deviceReceivePort, deviceMessagePrefix|
		var deviceReceiveNetAddr;
		deviceReceiveNetAddr = NetAddr(serialoscHost, deviceReceivePort);
		deviceReceiveNetAddr.sendMsg("/sys/prefix", deviceMessagePrefix.asString);
		// TODO: update device info list??
		this.traceOutput( "sent: /sys/prefix % to %".format(deviceMessagePrefix.asString, deviceReceiveNetAddr) );
	}

	*changeDeviceRotation { |serialoscHost, deviceReceivePort, deviceRotation|
		var rotation;
		var deviceReceiveNetAddr;

		deviceReceiveNetAddr = NetAddr(serialoscHost, deviceReceivePort);

		rotation = deviceRotation.asInteger;
		[0, 90, 180, 270].includes(rotation).not.if { Error("Bad rotation: %".format(rotation)).throw };
		deviceReceiveNetAddr.sendMsg("/sys/rotation", rotation);
		// TODO: update device info list??
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
				serialoscNetAddr
			);
			serialoscRemoveResponseListener=OSCFunc.new(
				{ |msg, time, addr, recvPort|
					this.traceOutput( "received: % from %".format(msg, addr) );
					removedFunc.(msg[1]);
					serialoscNetAddr.sendMsg("/serialosc/notify", "127.0.0.1", NetAddr.langPort);
				},
				'/serialosc/remove',
				serialoscNetAddr
			);
			this.traceOutput( "Started listening to serialosc device add / remove OSC messages" )
		};

		isTrackingConnectedDevicesChanges.if { Error("Already tracking serialosc device changes.").throw };

		serialoscNetAddr=NetAddr(serialoscHost, SerialOSCComm.serialoscPort);

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

SerialOSCXYStateMatcher : AbstractMessageMatcher {
	var x, y, state;

	*new {|x, y, state, func| ^super.new.init(x, y, state, func);}

	init {|argx, argy, argstate, argfunc| x = argx; y = argy; state = argstate; func = argfunc; }

	value {|testX, testY, testState, time, srcID|
		if (x.matchItem(testX) and: y.matchItem(testY) and: state.matchItem(testState)) {
			func.value(testX, testY, testState, time, srcID)
		}
	}
}

SerialOSCNXYZMatcher : AbstractMessageMatcher {
	var n, x, y, z;

	*new {|n, x, y, z, func| ^super.new.init(n, x, y, z, func);}

	init {|argn, argx, argy, argz, argfunc| n = argn; x = argx; y = argy; z = argz; func = argfunc; }

	value {|testN, testX, testY, testZ, time, srcID|
		if (n.matchItem(testN) and: x.matchItem(testX) and: y.matchItem(testY) and: z.matchItem(testZ)) {
			func.value(testN, testX, testY, testZ, time, srcID)
		}
	}
}

SerialOSCNDeltaMatcher : AbstractMessageMatcher {
	var n, delta;

	*new {|n, delta, func| ^super.new.init(n, delta, func);}

	init {|argn, argdelta, argfunc| n = argn; delta = argdelta; func = argfunc; }

	value {|testN, testDelta, time, srcID|
		if (n.matchItem(testN) and: delta.matchItem(testDelta)) {
			func.value(testN, testDelta, time, srcID)
		}
	}
}

SerialOSCNStateMatcher : AbstractMessageMatcher {
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
		var testMsgDevice;
		testMsgDevice = testMsg.last;
		case
		{ device === testMsgDevice } {
			func.value(*testMsg)
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
		{ device.respondsTo(\matchItem) } {
			if (device.matchItem(testMsgDevice)) { func.value(*testMsg) }
		}
	}
}
