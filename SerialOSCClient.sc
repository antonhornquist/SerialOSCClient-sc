SerialOSCClient {
	classvar prefix='/monome';
	classvar defaultLegacyModeListenPort=8080;
	classvar devicesListSemaphore;
	classvar autoconnect;
	classvar <>verbose;
	classvar recvSerialOSCFunc, oscRecvFunc, legacyModeOscRecvFunc;
	classvar deviceAddedHandler, deviceRemovedHandler;

	classvar <devices, <connectedDevices;
	classvar <all;
	classvar <initialized=false;
	classvar <runningLegacyMode=false;

	var gridKeyResponder, tiltResponder;
	var encDeltaResponder, encKeyResponder;

	var <name;
	var <autoroute;
	var <>permanent;
	var <gridSpec, <encSpec;
	var <grid, <enc;

	var <>willFree;
	var <>onFree;
	var <active;

	var <>onGridRouted, <>onGridUnrouted, <>gridRefreshAction;
	var <>onEncRouted, <>onEncUnrouted, <>encRefreshAction;
	var <>gridKeyAction, <>encDeltaAction, <>encKeyAction, <>tiltAction;

	*initClass {
		all = [];
		devices = [];
		connectedDevices = [];
		devicesListSemaphore = Semaphore.new;
		CmdPeriod.add(this);

		oscRecvFunc = { |msg, time, addr, recvPort|
			if (addr.ip == "127.0.0.1") {
				this.prLookupDeviceByPort(addr.port) !? { |device|
					if (connectedDevices.includes(device)) {
						if (#['/monome/grid/key', '/monome/tilt', '/monome/enc/delta', '/monome/enc/key'].includes(msg[0])) { // note: no pattern matching is performed on OSC address
							var type = msg[0].asString[7..].asSymbol;
							recvSerialOSCFunc.value(type, msg[1..], time, device);
						};
					};
				};
			};
		};

		legacyModeOscRecvFunc = { |msg, time, addr, recvPort|
			if (addr.ip == "127.0.0.1") {
				this.prLookupDeviceByPort(addr.port) !? { |device|
					if (connectedDevices.includes(device)) {
						if ('/monome/press' == msg[0]) { // note: no pattern matching is performed on OSC address
							recvSerialOSCFunc.value('/grid/key', msg[1..], time, device);
						};
					};
				};
			};
		};

		deviceAddedHandler = { |id|
			fork {
				devicesListSemaphore.wait;
				if (this.prLookupDeviceById(id).isNil) {
					this.prUpdateDevicesListAsync {
						this.prLookupDeviceById(id) !? { |device|
							this.prSyncAfterDeviceListChanges([device], []);
							SerialOSCClientNotification.postDeviceAttached(device);
						};
						devicesListSemaphore.signal;
					};
				} {
					devicesListSemaphore.signal;
				};
			};
		};

		deviceRemovedHandler = { |id|
			fork {
				devicesListSemaphore.wait;
				this.prLookupDeviceById(id) !? { |device|
					this.prSyncAfterDeviceListChanges([], [device]);
					SerialOSCClientNotification.postDeviceDetached(device);
				};
				devicesListSemaphore.signal;
			};
		};

	}

	*init { |completionFunc, autoconnect=true, autodiscover=true, verbose=false|
		this.prInit(autoconnect, verbose);

		if (autodiscover) {
			SerialOSC.startTrackingConnectedDevicesChanges(deviceAddedHandler, deviceRemovedHandler);
		};

		thisProcess.addOSCRecvFunc(oscRecvFunc);

		initialized = true;
		runningLegacyMode = false;

		this.prUpdateDevicesListAsync { |devicesAddedToDevicesList, devicesRemovedFromDevicesList|
			this.postDevices;
			this.prSyncAfterDeviceListChanges(devicesAddedToDevicesList, devicesRemovedFromDevicesList);
			completionFunc.value;
		};
	}

	*legacy40h { |autoconnect=true, verbose=false|
		this.prLegacyMode(autoconnect, verbose, LegacySerialOSCGrid('monome 40h', nil, defaultLegacyModeListenPort, 0));
	}

	*legacy64 { |autoconnect=true, verbose=false|
		this.prLegacyMode(autoconnect, verbose, LegacySerialOSCGrid('monome 64', nil, defaultLegacyModeListenPort, 0));
	}

	*legacy128 { |autoconnect=true, verbose=false|
		this.prLegacyMode(autoconnect, verbose, LegacySerialOSCGrid('monome 128', nil, defaultLegacyModeListenPort, 0));
	}

	*legacy256 { |autoconnect=true, verbose=false|
		this.prLegacyMode(autoconnect, verbose, LegacySerialOSCGrid('monome 256', nil, defaultLegacyModeListenPort, 0));
	}

	*prLegacyMode { |autoconnect=true, verbose=false, legacyGrid|
		var devicesRemovedFromDevicesList;

		this.prInit(autoconnect, verbose);

		thisProcess.addOSCRecvFunc(legacyModeOscRecvFunc);

		initialized = true;
		runningLegacyMode = true;

		devicesRemovedFromDevicesList = devices;
		devices = [legacyGrid];

		this.postDevices;
		this.prSyncAfterDeviceListChanges(devices, devicesRemovedFromDevicesList);

		"SerialOSCClient is running in legacy mode. For an attached grid to work MonomeSerial has to run and be configured with Host Port %, Address Prefix /monome and Listen Port %.".format(NetAddr.langPort, legacyGrid.port).postln;
	}

	*prInit { |argAutoconnect, argVerbose|
		autoconnect = argAutoconnect;
		verbose = argVerbose;

		this.prRemoveRegisteredOSCRecvFuncsIfAny;

		if (SerialOSC.isTrackingConnectedDevicesChanges) {
			SerialOSC.stopTrackingConnectedDevicesChanges
		};
	}

	*prSyncAfterDeviceListChanges { |devicesAddedToDevicesList, devicesRemovedFromDevicesList|
		devicesRemovedFromDevicesList do: _.remove;
		devicesAddedToDevicesList do: { |device|
			SerialOSCClientNotification.deviceAttached(device);
		};
		if (autoconnect) { devicesAddedToDevicesList do: _.connect };
		this.prUpdateDefaultDevices(devicesAddedToDevicesList, devicesRemovedFromDevicesList);
	}

	*prRemoveRegisteredOSCRecvFuncsIfAny {
		thisProcess.removeOSCRecvFunc(oscRecvFunc);
		thisProcess.removeOSCRecvFunc(legacyModeOscRecvFunc);
	}

	*addSerialOSCRecvFunc { |func| recvSerialOSCFunc = recvSerialOSCFunc.addFunc(func) }

	*removeSerialOSCRecvFunc { |func| recvSerialOSCFunc = recvSerialOSCFunc.removeFunc(func) }

	*freeAll {
		all.copy do: _.free;
	}

	*cmdPeriod {
		all.reject { |client| client.permanent }.copy.do(_.free);
	}

	*prGetPrefixedAddress { |address|
		^(prefix.asString++address.asString).asSymbol
	}

	*prUpdateDefaultDevices { |devicesAddedToDevicesList, devicesRemovedFromDevicesList|
		this.prUpdateDefaultGrid(devicesAddedToDevicesList, devicesRemovedFromDevicesList);
		this.prUpdateDefaultEnc(devicesAddedToDevicesList, devicesRemovedFromDevicesList);
	}

	*prUpdateDefaultGrid { |devicesAddedToDevicesList, devicesRemovedFromDevicesList|
		var addedAndConnected, connectedNotRoutedToAClient;

		addedAndConnected = devicesAddedToDevicesList.reject(this.prDeviceIsEncByType(_)).select(_.isConnected);
		connectedNotRoutedToAClient = SerialOSCGrid.connected.reject { |device| device.client.notNil };

		case
			{ SerialOSCGrid.default.isNil and: addedAndConnected.notEmpty } {
				SerialOSCGrid.default = addedAndConnected.first
			}
			{ devices.includes(SerialOSCGrid.default).not } {
				SerialOSCGrid.default = case
					{ addedAndConnected.notEmpty } { addedAndConnected.first }
					{ connectedNotRoutedToAClient.notEmpty } { connectedNotRoutedToAClient.first }
			}
	}

	*prUpdateDefaultEnc { |devicesAddedToDevicesList, devicesRemovedFromDevicesList|
		var addedAndConnected, connectedNotRoutedToAClient;

		addedAndConnected = devicesAddedToDevicesList.select(this.prDeviceIsEncByType(_)).select(_.isConnected);
		connectedNotRoutedToAClient = SerialOSCEnc.connected.reject { |device| device.client.notNil };

		case
			{ SerialOSCEnc.default.isNil and: addedAndConnected.notEmpty } {
				SerialOSCEnc.default = addedAndConnected.first
			}
			{ devices.includes(SerialOSCEnc.default).not } {
				SerialOSCEnc.default = case
					{ addedAndConnected.notEmpty } { addedAndConnected.first }
					{ connectedNotRoutedToAClient.notEmpty } { connectedNotRoutedToAClient.first }
			}
	}

	*connectAll {
		devices do: { |device| this.connect(device) }
	}

	*connect { |device|
		this.prEnsureInitialized;

		if (connectedDevices.includes(device).not) {
			runningLegacyMode.not.if {
				SerialOSC.changeDeviceMessagePrefix(
					device.port,
					prefix
				);

				SerialOSC.changeDeviceDestinationPort(
					device.port,
					NetAddr.langPort
				);
			};

			connectedDevices = connectedDevices.add(device);

			SerialOSCClientNotification.deviceConnected(device);

			this.prAutorouteDeviceToClients;
		};
	}

	*prAutorouteDeviceToClients {
		var clients;

		clients = SerialOSCClient.all.select(_.autoroute);

		clients.do { |client| client.findAndRouteUnusedDevicesToClient(true) };
		clients.do { |client| client.findAndRouteUnusedDevicesToClient(false) };
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
		this.prEnsureInitialized;
		recvSerialOSCFunc.value(type, args, SystemClock.seconds, device);
	}

	*disconnectAll {
		devices do: { |device| this.disconnect(device) }
	}

	*disconnect { |device|
		this.prEnsureInitialized;

		if (connectedDevices.includes(device)) {
			device.unroute;

			connectedDevices.remove(device);

			SerialOSCClientNotification.deviceDisconnected(device);
		};
	}

	*postDevices {
		if (devices.notEmpty) {
			"SerialOSC Devices:".postln;
			devices.do { |device| (Char.tab ++ device).postln };
		} {
			"No SerialOSC Devices are attached".postln;
		};
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
		SerialOSC.requestListOfDevices { |list|
			var currentDevices, foundDevices, devicesToRemove, devicesToAdd;

			currentDevices = devices.as(IdentitySet);
			foundDevices = list.collect { |entry|
				devices.detect { |serialOSCDevice| serialOSCDevice.id == entry[\id] } ?? {
					if (this.prListEntryIsEncByType(entry)) {
						SerialOSCEnc(entry[\type], entry[\id], entry[\receivePort]);
					} {
						SerialOSCGrid(entry[\type], entry[\id], entry[\receivePort], 0);
					};
				};
			}.as(IdentitySet);

			devicesToRemove = currentDevices - foundDevices;
			devicesToAdd = foundDevices - currentDevices;

			devices.removeAll(devicesToRemove);
			devices = devices.addAll(devicesToAdd);

			completionFunc.(devicesToAdd.as(Array), devicesToRemove.as(Array));
		};
	}

	*prLookupDeviceById { |id|
		^devices.detect { |device| device.id == id.asSymbol }
	}

	*prLookupDeviceByPort { |receivePort|
		^devices.detect { |device| device.port == receivePort }
	}

	*prEnsureInitialized {
		initialized.not.if { Error("SerialOSCClient has not been initialized").throw };
	}

	*grid { |name, func, gridSpec=\any, autoroute=true|
		^this.new(name, gridSpec, \none, func, autoroute);
	}

	*enc { |name, func, encSpec=\any, autoroute=true|
		^this.new(name, \none, encSpec, func, autoroute);
	}

	*gridEnc { |name, func, gridSpec, encSpec, autoroute=true|
		^this.new(name, gridSpec, encSpec, func, autoroute);
	}

	*new { |name, gridSpec=\any, encSpec=\any, func, autoroute=true|
		^super.new.initSerialOSCClient(name, gridSpec, encSpec, func, autoroute)
	}

	*doWhenInitialized { |func|
		if (initialized, func, {
			this.init(func);
		});
	}

	initSerialOSCClient { |argName, argGridSpec, argEncSpec, func, argAutoroute|
		name = argName;
		gridSpec = argGridSpec;
		encSpec = argEncSpec;
		autoroute = argAutoroute;
		permanent = false;

		func.value(this);

		active = true;

		SerialOSCClient.doWhenInitialized {
			if (argAutoroute) { this.findAndRouteUnusedDevicesToClient(false) };
		};

		all = all.add(this);
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

	*prFindGrid { |gridSpec, strict|
		var strictMatch = this.prDefaultGridIfFreeAndMatching(gridSpec) ? this.prFirstFreeGridMatching(gridSpec);

		^if (strict) {
			strictMatch
		} {
			strictMatch ? this.prDefaultGridIfFree ? SerialOSCGrid.unrouted.first;
		}
	}

	*prDefaultGridIfFreeAndMatching { |gridSpec|
		var freeDefaultGrid = this.prDefaultGridIfFree;
		^if (freeDefaultGrid.notNil) {
			if (this.gridMatchesSpec(freeDefaultGrid, gridSpec)) {
				freeDefaultGrid;
			}
		};
	}

	*prDefaultGridIfFree {
		var defaultGrid = SerialOSCGrid.default;
		^if (defaultGrid.notNil) {
			if (defaultGrid.client.isNil) {
				defaultGrid;
			}
		};
	}

	*prFirstFreeGridMatching { |gridSpec|
		^SerialOSCGrid.unrouted.select {|grid|this.gridMatchesSpec(grid, gridSpec)}.first;
	}

	*prFindEnc { |encSpec, strict|
		var strictMatch = this.prDefaultEncIfFreeAndMatching(encSpec) ? this.prFirstFreeEncMatching(encSpec);

		^if (strict) {
			strictMatch
		} {
			strictMatch ? this.prDefaultEncIfFree ? SerialOSCEnc.unrouted.first;
		}
	}

	*prDefaultEncIfFreeAndMatching { |encSpec|
		var freeDefaultEnc = this.prDefaultEncIfFree;
		^if (freeDefaultEnc.notNil) {
			if (this.encMatchesSpec(freeDefaultEnc, encSpec)) {
				freeDefaultEnc;
			}
		};
	}

	*prDefaultEncIfFree {
		var defaultEnc = SerialOSCEnc.default;
		^if (defaultEnc.notNil) {
			if (defaultEnc.client.isNil) {
				defaultEnc;
			}
		};
	}

	*prFirstFreeEncMatching { |encSpec|
		^SerialOSCEnc.unrouted.select {|enc|this.encMatchesSpec(enc, encSpec)}.first;
	}

	findAndRouteUnusedDevicesToClient { |strict|
		this.findAndRouteAnyUnusedGridToClient(strict);
		this.findAndRouteAnyUnusedEncToClient(strict);
	}

	findAndRouteAnyUnusedGridToClient { |strict|
		if (this.usesGrid and: grid.isNil) {
			SerialOSCClient.prFindGrid(gridSpec, strict) !? { |foundGrid| this.prRouteGridToClient(foundGrid) }
		};
	}

	findAndRouteAnyUnusedEncToClient { |strict|
		if (this.usesEnc and: enc.isNil) {
			SerialOSCClient.prFindEnc(encSpec, strict) !? { |foundEnc| this.prRouteEncToClient(foundEnc) }
		};
	}

	prRouteGridToClient { |argGrid|
		grid = argGrid;
		gridKeyResponder = GridKeyFunc.new(
			{ |x, y, state, time, device|
				gridKeyAction.value(this, x, y, state);
			},
			device: \client -> this
		);
		gridKeyResponder.permanent = true;
		tiltResponder = TiltFunc.new(
			{ |n, x, y, z, time, device|
				tiltAction.value(this, n, x, y, z);
			},
			device: \client -> this
		);
		tiltResponder.permanent = true;
		grid.client = this;
		onGridRouted.value(this, grid);
		SerialOSCClientNotification.deviceRouted(grid, this);
		this.warnIfGridDoesNotMatchSpec;
		this.clearAndRefreshGrid;
	}

	prRouteEncToClient { |argEnc|
		enc = argEnc;
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
		onEncRouted.value(this, enc);
		SerialOSCClientNotification.deviceRouted(enc, this);
		this.warnIfEncDoesNotMatchSpec;
		this.clearAndRefreshEnc;
	}

	asSerialOSCClient { ^this }

	grabDevices {
		this.grabGrid;
		this.grabEnc;
	}

	grabGrid {
		if (this.usesGrid and: SerialOSCGrid.all.notEmpty) {
			SerialOSCClient.route(SerialOSCGrid.all.first, this);
		};
	}

	grabEnc {
		if (this.usesEnc and: SerialOSCEnc.all.notEmpty) {
			SerialOSCClient.route(SerialOSCEnc.all.first, this);
		};
	}

	*route { |device, client|
		this.prRoute(device, client.asSerialOSCClient);
	}

	*prRoute { |device, client|
		if (device.respondsTo(\ledSet)) { this.prRouteGrid(device, client) };
		if (device.respondsTo(\ringSet)) { this.prRouteEnc(device, client) };
	}

	*prRouteGrid { |grid, client|
		client.usesGrid.not.if {
			"Client % does not use a grid".format(client).postln;
		} {
			client.grid.notNil.if { client.unrouteGrid };
			grid.client.notNil.if { grid.unroute };
			client.prRouteGridToClient(grid);
		};
	}

	*prRouteEnc { |enc, client|
		client.usesEnc.not.if {
			"Client % does not use an enc".format(client).postln;
		} {
			client.enc.notNil.if { client.unrouteEnc };
			enc.client.notNil.if { enc.unroute };
			client.prRouteEncToClient(enc);
		};
	}

	*postRoutings {
		all.do { |client|
			client.postln;
			if (client.usesGrid) {
				if (client.grid.notNil) {
					"\trouted to %".format(client.grid)
				} {
					"\tno grid routed"
				}.postln
			};
			if (client.usesEnc) {
				if (client.enc.notNil) {
					"\trouted to %".format(client.enc)
				} {
					"\tno enc routed"
				}.postln
			};
		}
	}

	warnIfGridDoesNotMatchSpec {
		SerialOSCClient.gridMatchesSpec(grid, gridSpec).not.if {
			"Note: Grid % does not match client % spec: %".format(grid, this, gridSpec).postln
		}
	}

	warnIfEncDoesNotMatchSpec {
		SerialOSCClient.encMatchesSpec(enc, encSpec).not.if {
			"Note: Enc % does not match client % spec: %".format(enc, this, encSpec).postln
		}
	}

	*gridMatchesSpec { |grid, gridSpec|
		var numCols, numRows;
		numCols = grid.numCols;
		numRows = grid.numRows;
		^case
			{gridSpec == \any} { true }
			{gridSpec.respondsTo(\key) and: gridSpec.respondsTo(\value)} {
				((gridSpec.key == \numCols) and: (gridSpec.value == numCols))
				or:
				((gridSpec.key == \numRows) and: (gridSpec.value == numRows))
			}
			{gridSpec.respondsTo(\keys)} {
				(gridSpec[\numCols] == numCols) and: (gridSpec[\numRows] == numRows)
			}
	}

	*encMatchesSpec { |enc, encSpec|
		^(encSpec == \any) or: (encSpec == enc.numEncs)
	}

	clearAndRefreshGrid {
		grid !? {
			this.clearLeds;
			this.refreshGrid;
		};
	}

	refreshGrid {
		grid !? {
			gridRefreshAction.value(this);
		}
	}

	clearAndRefreshEnc {
		enc !? {
			this.clearRings;
			this.refreshEnc;
		};
	}

	refreshEnc {
		enc !? {
			encRefreshAction.value(this);
		}
	}

	unrouteGrid {
		grid !? {
			var gridToUnroute;
			gridToUnroute = grid;
			grid.client = nil;
			grid.clearLeds;
			grid = nil;
			gridKeyResponder.free;
			tiltResponder.free;
			onGridUnrouted.value(this, gridToUnroute);
			SerialOSCClientNotification.deviceUnrouted(gridToUnroute, this);
		};
	}

	unrouteEnc {
		enc !? {
			var encToUnroute;
			encToUnroute = enc;
			enc.client = nil;
			enc.clearRings;
			enc = nil;
			encDeltaResponder.free;
			encKeyResponder.free;
			onEncUnrouted.value(this, encToUnroute);
			SerialOSCClientNotification.deviceUnrouted(encToUnroute, this);
		};
	}

	free {
		if (all.includes(this)) {
			willFree.value(this);
			if (this.usesGrid) { this.unrouteGrid };
			if (this.usesEnc) { this.unrouteEnc };
			all.remove(this);
			active = false;
			onFree.value(this);
		};
	}

	clearLeds {
		grid !? { |grid| grid.clearLeds };
	}

	activateTilt { |n|
		grid !? { |grid| grid.activateTilt(n) };
	}

	deactivateTilt { |n|
		grid !? { |grid| grid.deactivateTilt(n) };
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

SerialOSCClientNotification {
	*deviceAttached { |device|
		this.changed(\attached, device);
		this.verbosePost("% was attached".format(device));
	}

	*deviceDetached { |device|
		this.changed(\detached, device);
		this.verbosePost("% was detached".format(device));
	}

	*deviceConnected { |device|
		this.changed(\connected, device);
		device.changed(\connected);
		this.verbosePost("% was connected".format(device));
	}

	*deviceDisconnected { |device|
		this.changed(\disconnected, device);
		device.changed(\disconnected);
		this.verbosePost("% was disconnected".format(device));
	}

	*deviceRouted { |device, client|
		SerialOSCClient.changed(\routed, device, client);
		device.changed(\routed, client);
		this.verbosePost("% was routed to client %".format(device, client));
	}

	*deviceUnrouted { |device, client|
		SerialOSCClient.changed(\unrouted, device, client);
		device.changed(\unrouted, client);
		this.verbosePost("% was unrouted from client %".format(device, client));
	}

	*postDeviceAttached { |device|
		"A SerialOSC Device was attached to the computer:".postln;
		(Char.tab ++ device).postln;
	}

	*postDeviceDetached { |device|
		"A SerialOSC Device was detached from the computer:".postln;
		(Char.tab ++ device).postln;
	}

	*verbosePost { |message|
		SerialOSCClient.verbose.if { message.postln };
	}
}

LegacySerialOSCGrid : SerialOSCGrid {
	ledSet { |x, y, state|
		this.prSendMsg('/led', x.asInteger, y.asInteger, state.asInteger);
	}

	ledAll { |state|
		16.do { |x|
			16.do { |y|
				this.ledSet(x, y, state);
			}
		};
	}

	ledMap { |xOffset, yOffset, bitmasks|
		NotYetImplementedError("Method not yet supported in Legacy Mode", thisMethod).throw;
	}

	ledRow { |xOffset, y, bitmasks|
		NotYetImplementedError("Method not yet supported in Legacy Mode", thisMethod).throw;
	}

	ledCol { |x, yOffset, bitmasks|
		NotYetImplementedError("Method not yet supported in Legacy Mode", thisMethod).throw;
	}

	ledIntensity { |i|
		NotYetImplementedError("Method not yet supported in Legacy Mode", thisMethod).throw;
	}

	ledLevelSet { |x, y, l|
		this.ledSet(x.asInteger, y.asInteger, if (l == 15, 1, 0));
	}

	ledLevelAll { |l|
		this.ledAll(if (l == 15, 1, 0));
	}

	ledLevelMap { |xOffset, yOffset, levels|
		NotYetImplementedError("Method not yet supported in Legacy Mode", thisMethod).throw;
	}

	ledLevelRow { |xOffset, y, levels|
		NotYetImplementedError("Method not yet supported in Legacy Mode", thisMethod).throw;
	}

	ledLevelCol { |x, yOffset, levels|
		NotYetImplementedError("Method not yet supported in Legacy Mode", thisMethod).throw;
	}
}

SerialOSCGrid : SerialOSCDevice {
	classvar <default;
	var <rotation;

	*all {
		^SerialOSCClient.devices.reject { |device|
			SerialOSCClient.prIsEncType(device.type)
		}
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

	*unrouted {
		^this.all.select {|grid|grid.client.isNil}
	}

	*connected {
		^this.all.select(_.isConnected)
	}

	*new { |type, id, port, rotation|
		^super.new(type, id, port).initSerialOSCGrid(rotation);
	}

	initSerialOSCGrid { |argRotation|
		rotation = argRotation;
	}

	*doWithDefaultWhenInitialized { |func, defaultNotAvailableFunc|
		var fallback = { ("No default grid available").postln };
		SerialOSCClient.doWhenInitialized {
			if (default.notNil) {
				func.value(default);
			} {
				(defaultNotAvailableFunc ? fallback).value;
			};
		};
	}

	*testLeds {
		this.doWithDefaultWhenInitialized(
			_.testLeds,
			{ ("Unable to run test, no default grid available").postln }
		);
	}

	*clearLeds {
		this.doWithDefaultWhenInitialized(_.clearLeds);
	}

	*activateTilt { |n|
		this.doWithDefaultWhenInitialized { |grid| grid.activateTilt(n) };
	}

	*deactivateTilt { |n|
		this.doWithDefaultWhenInitialized { |grid| grid.deactivateTilt(n) };
	}

	*ledSet { |x, y, state|
		this.doWithDefaultWhenInitialized { |grid| grid.ledSet(x, y, state) };
	}

	*ledAll { |state|
		this.doWithDefaultWhenInitialized { |grid| grid.ledAll(state) };
	}

	*ledMap { |xOffset, yOffset, bitmasks|
		this.doWithDefaultWhenInitialized { |grid| grid.ledMap(xOffset, yOffset, bitmasks) };
	}

	*ledRow { |xOffset, y, bitmasks|
		this.doWithDefaultWhenInitialized { |grid| grid.ledRow(xOffset, y, bitmasks) };
	}

	*ledCol { |x, yOffset, bitmasks|
		this.doWithDefaultWhenInitialized { |grid| grid.ledCol(x, yOffset, bitmasks) };
	}

	*ledIntensity { |i|
		this.doWithDefaultWhenInitialized { |grid| grid.ledIntensity(i) };
	}

	*ledLevelSet { |x, y, l|
		this.doWithDefaultWhenInitialized { |grid| grid.ledLevelSet(x, y, l) };
	}

	*ledLevelAll { |l|
		this.doWithDefaultWhenInitialized { |grid| grid.ledLevelAll(l) };
	}

	*ledLevelMap { |xOffset, yOffset, levels|
		this.doWithDefaultWhenInitialized { |grid| grid.ledLevelMap(xOffset, yOffset, levels) };
	}

	*ledLevelRow { |xOffset, y, levels|
		this.doWithDefaultWhenInitialized { |grid| grid.ledLevelRow(xOffset, y, levels) };
	}

	*ledLevelCol { |x, yOffset, levels|
		this.doWithDefaultWhenInitialized { |grid| grid.ledLevelCol(x, yOffset, levels) };
	}

	*tiltSet { |n, state|
		this.doWithDefaultWhenInitialized { |grid| grid.tiltSet(n, state) };
	}

	*numButtons { ^default !? _.numButtons }
	*numCols { ^default !? _.numCols }
	*numRows { ^default !? _.numRows }
	*rotation { ^default !? _.rotation }
	*rotation_ { |degrees| this.doWithDefaultWhenInitialized { |grid| grid.rotation_(degrees) } }
	*ledXSpec { ^default !? _.ledXSpec }
	*ledYSpec { ^default !? _.ledYSpec }

	testLeds { |varibright=true|
		this.unroute;

		CmdPeriod.doOnce { this.clearLeds };

		fork {
			15.do { |level|
				this.ledLevelAll(15-level);
				0.03.wait;
			};
			this.clearLeds;

			inf.do {
				var delay = 0.04;
				if (varibright) {
					this.prSplashFromVari(Point.new(this.numCols.rand, this.numRows.rand), 8, delay);
				} {
					this.prSplashFromMono(Point.new(this.numCols.rand, this.numRows.rand), 8, delay);
				};
				((delay-0.02.rand)*8).wait;
			};
		};
	}

	clearLeds {
		this.ledAll(0);
	}

	activateTilt { |n| this.tiltSet(n, true) }

	deactivateTilt { |n| this.tiltSet(n, false) }

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

	ledXSpec { ^ControlSpec(0, this.numCols, step: 1) }

	ledYSpec { ^ControlSpec(0, this.numRows, step: 1) }

	numButtons { ^this.numCols*this.numRows }

	numCols {
		^case
			{ [0, 180].includes(rotation) } { this.prDeviceNumColsFromType }
			{ [90, 270].includes(rotation) } { this.prDeviceNumRowsFromType }
	}

	numRows {
		^case
			{ [0, 180].includes(rotation) } { this.prDeviceNumRowsFromType }
			{ [90, 270].includes(rotation) } { this.prDeviceNumColsFromType }
	}

	rotation_ { |degrees|
		SerialOSC.changeDeviceRotation(port, degrees);
		rotation = degrees;
		this.changed(\rotation, degrees);
		if (client.notNil) {
			client.warnIfGridDoesNotMatchSpec;
			client.clearAndRefreshGrid;
		}
	}

	prDeviceNumColsFromType {
		^switch (type)
			{ 'monome 64' } { 8 }
			{ 'monome 40h' } { 8 }
			{ 'monome 128' } { 16 }
			{ 'monome 256' } { 16 }
	}

	prDeviceNumRowsFromType {
		^switch (type)
			{ 'monome 64' } { 8 }
			{ 'monome 40h' } { 8 }
			{ 'monome 128' } { 8 }
			{ 'monome 256' } { 16 }
	}

	unroute { client !? _.unrouteGrid }

	prSplashFromVari { |origin, size=8, delay=0.1|
		this.prSplashFrom(origin, size, delay, { |x, y, level| this.ledLevelSet(x, y, level) });
	}

	prSplashFromMono { |origin, size=8, delay=0.1|
		this.prSplashFrom(origin, size, delay, { |x, y, level| this.ledSet(x, y, 1) });
	}

	prSplashFrom { |origin, size, delay=0.1, setFunc|
		var numCols = this.numCols, numRows = this.numRows;
		var prevPoints = Array.new;
		var leds = Array.fill(numCols * numRows, { |i| Point.new(i mod: numCols, i div: numCols) });
		var getPointsWithinDistance = { |origin, points, distance|
			points.select { |point| point.dist(origin).round == distance };
		};

		this.unroute;

		fork {
			size.do { |distance|
				var newPoints = getPointsWithinDistance.value(origin, leds, distance);
				newPoints.do { |point| setFunc.value(point.x, point.y, 0 max: (15-(distance*2))) };
				prevPoints = newPoints;
				delay.wait;
				prevPoints.do { |point| this.ledSet(point.x, point.y, 0) };
			};
		};

		Server.default.serverRunning.if {
/*
			{
				SinOsc.ar(
					1000+1000.rand,
					mul: (-20).dbamp
				) * EnvGen.ar(Env.perc(releaseTime: delay*8), doneAction: 2) ! 2
			}.play
			{
				SinOsc.ar(
					LinCongC.ar(150, LFNoise2.kr(10, 0.1, 1), LFNoise2.kr(0.1, 0.1, 0.1), LFNoise2.kr(0.1), 0, 100, 600),
					mul: (-10).dbamp
				) * EnvGen.ar(Env.perc(releaseTime: delay*8), doneAction: 2) ! 2
			}.play
*/
			{
				SinOscFB.ar(
					(62+7+(origin.x.degreeToKey(Scale.major))).midicps,
					2.0,
					mul: (-10).dbamp
				) * EnvGen.ar(Env.perc(releaseTime: delay*8), doneAction: 2) ! 2
			}.play
		};
	}
}

SerialOSCEnc : SerialOSCDevice {
	classvar <default;
	classvar <ledXSpec;

	*initClass {
		ledXSpec = ControlSpec(0, 63, step: 1);
	}

	*all {
		^SerialOSCClient.devices.select { |device|
			SerialOSCClient.prIsEncType(device.type)
		}
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

	*unrouted {
		^this.all.select {|enc|enc.client.isNil}
	}

	*connected {
		^this.all.select(_.isConnected)
	}

	*new { |type, id, port|
		^super.new(type, id, port).initSerialOSCEnc;
	}

	initSerialOSCEnc {
	}

	*doWithDefaultWhenInitialized { |func, defaultNotAvailableFunc|
		var fallback = { ("No default enc available").postln };
		SerialOSCClient.doWhenInitialized {
			if (default.notNil) {
				func.value(default);
			} {
				(defaultNotAvailableFunc ? fallback).value;
			};
		};
	}

	*testLeds {
		this.doWithDefaultWhenInitialized(
			_.testLeds,
			{ ("Unable to run test, no default enc available").postln }
		);
	}

	*clearRings {
		this.doWithDefaultWhenInitialized(_.clearRings);
	}

	*ringSet { |n, x, level|
		this.doWithDefaultWhenInitialized { |enc| enc.ringSet(n, x, level) };
	}

	*ringAll { |n, level|
		this.doWithDefaultWhenInitialized { |enc| enc.ringAll(n, level) };
	}

	*ringMap { |n, levels|
		this.doWithDefaultWhenInitialized { |enc| enc.ringMap(n, levels) };
	}

	*ringRange { |n, x1, x2, level|
		this.doWithDefaultWhenInitialized { |enc| enc.ringRange(n, x1, x2, level) };
	}

	*nSpec {
		^default !? _.nSpec
	}

	*numEncs {
		^default !? _.numEncs
	}

	testLeds {
		fork {
			this.clearRings;
			this.numEncs.do { |n|
				64.do { |x|
					this.ringSet(n, x, 15);
					0.005.wait;
				};
			};
			this.numEncs.do { |n|
				64.do { |x|
					this.ringSet(n, x, 0);
					0.005.wait;
				};
			};
			this.clearRings;
		};
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

	nSpec {
		^ControlSpec(0, this.numEncs, step: 1);
	}

	numEncs {
		^switch (type)
			{ 'monome arc 2' } { 2 }
			{ 'monome arc 4' } { 4 }
	}

	unroute { client !? _.unrouteEnc }
}

SerialOSCDevice {
	var <type, <id, <port, <>client;
	classvar <ledLSpec;

	*initClass {
		ledLSpec = ControlSpec(0, 15, step: 1);
	}

	*new { arg type, id, port;
		^super.newCopyArgs(type.asSymbol, id.asSymbol, port.asInteger)
	}

	*connect {
		this.default !? { |device| device.connect };
	}

	*disconnect {
		this.default !? { |device| device.disconnect };
	}

	printOn { arg stream;
		stream << this.class.name << "(" <<<*
			[type, id, port]  <<")"
	}

	prSendMsg { |address ...args|
		NetAddr(SerialOSC.defaultSerialoscdHost, port).sendMsg(SerialOSCClient.prGetPrefixedAddress(address), *args);
	}

	remove {
		this.disconnect;
		SerialOSCClient.devices.remove(this);
		SerialOSCClientNotification.deviceDetached(this);
	}

	connect { SerialOSCClient.connect(this) }

	disconnect { SerialOSCClient.disconnect(this) }

	isConnected {
		^SerialOSCClient.connectedDevices.includes(this);
	}
}

SerialOSC {
	classvar
		trace=false,
		asyncSerialoscdResponseSemaphore,
		<isTrackingConnectedDevicesChanges=false,
		serialOSCAddResponseListener,
		serialOSCRemoveResponseListener,
		<>defaultSerialoscdHost = "127.0.0.1",
		<>defaultSerialoscdPort = 12002
	;

	*trace { |on=true| trace = on }

	*initClass {
		asyncSerialoscdResponseSemaphore = Semaphore.new;
	}

	*requestListOfDevices { |func, timeout=0.5, serialoscdHost, serialoscdPort|
		var
			serialOSCNetAddr,
			startListeningForSerialoscResponses,
			stopListeningForSerialoscResponses,
			setupListener,
			teardownListener,
			serialOSCResponseListener
		;

		serialOSCNetAddr=NetAddr(serialoscdHost ? SerialOSC.defaultSerialoscdHost, serialoscdPort ? SerialOSC.defaultSerialoscdPort);

		startListeningForSerialoscResponses = { |serialOSCNetAddr, listOfDevices|
			setupListener.(serialOSCNetAddr, listOfDevices);
			this.prTraceOutput( "Started listening to serialosc device list OSC responses" )
		};

		stopListeningForSerialoscResponses = {
			teardownListener.();
			this.prTraceOutput( "Stopped listening to serialosc device list OSC responses" )
		};

		setupListener = { |serialOSCNetAddr, listOfDevices|
			serialOSCResponseListener=OSCFunc.new(
				{ |msg, time, addr, recvPort|
					var id, type, receivePort;
					id = msg[1].asSymbol;
					type = msg[2].asSymbol;
					receivePort = msg[3].asInteger;
					this.prTraceOutput( "received: /serialosc/device % % % from %".format(id, type, receivePort, addr) );
					listOfDevices.add(
						IdentityDictionary[
							\id -> id,
							\type -> type,
							\receivePort -> receivePort
						]
					);
				},
				'/serialosc/device',
				serialOSCNetAddr
			);
		};

		teardownListener = {
			serialOSCResponseListener.free;
		};

		fork {
			var listOfDevices;

			asyncSerialoscdResponseSemaphore.wait;

			listOfDevices = List.new;

			startListeningForSerialoscResponses.(serialOSCNetAddr, listOfDevices);

			this.prSendMessage(
				["/serialosc/list", NetAddr.localAddr.ip, NetAddr.langPort],
				serialoscdPort ? SerialOSC.defaultSerialoscdPort,
				serialoscdHost
			);
			this.prTraceOutput( "waiting % seconds serialosc device list reponses...".format(timeout) );
			timeout.wait;
			stopListeningForSerialoscResponses.();

			asyncSerialoscdResponseSemaphore.signal;

			func.(listOfDevices);
		}
	}

	*requestInformationAboutDevice { |deviceReceivePort, func, timeout=0.5, serialoscdHost|
		var
			deviceReceiveNetAddr,
			startListeningForSerialoscDeviceResponses,
			stopListeningForSerialoscDeviceResponses,
			setupListeners,
			teardownListeners,
			serialOSCDeviceResponseListeners
		;

		startListeningForSerialoscDeviceResponses = { |deviceReceiveNetAddr, deviceInfo|
			setupListeners.(deviceReceiveNetAddr, deviceInfo);
			this.prTraceOutput( "Started listening to serialosc device info OSC responses" )
		};

		stopListeningForSerialoscDeviceResponses = {
			teardownListeners.();
			this.prTraceOutput( "Stopped listening to serialosc device info OSC responses" )
		};

		setupListeners = { |deviceReceiveNetAddr, deviceInfo|
			serialOSCDeviceResponseListeners=['port', 'host', 'id', 'prefix', 'rotation', 'size'].collect { |attribute|
				OSCFunc.new(
					{ |msg, time, addr, recvPort|
						this.prTraceOutput( "received: % from %".format(msg, addr) );
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
			serialOSCDeviceResponseListeners.do(_.free);
		};

		fork {
			var deviceInfo;

			asyncSerialoscdResponseSemaphore.wait;

			deviceReceiveNetAddr=NetAddr(serialoscdHost ? SerialOSC.defaultSerialoscdHost, deviceReceivePort);
			deviceInfo = IdentityDictionary.new;
			startListeningForSerialoscDeviceResponses.(deviceReceiveNetAddr, deviceInfo);

			this.prSendMessage(
				["/sys/info", NetAddr.localAddr.ip, NetAddr.langPort],
				deviceReceivePort,
				serialoscdHost
			);
			timeout.wait;
			stopListeningForSerialoscDeviceResponses.();

			asyncSerialoscdResponseSemaphore.signal;

			func.(deviceInfo);
		}
	}

	*startTrackingConnectedDevicesChanges { |addedFunc, removedFunc, serialoscdHost, serialoscdPort|
		var
			serialOSCNetAddr,
			startListeningForSerialoscResponses,
			setupListeners
		;

		startListeningForSerialoscResponses = { |serialOSCNetAddr, addedFunc, removedFunc|
			setupListeners.(serialOSCNetAddr, addedFunc, removedFunc);
			isTrackingConnectedDevicesChanges = true;
		};

		setupListeners = { |serialOSCNetAddr, addedFunc, removedFunc|
			serialOSCAddResponseListener=OSCFunc.new(
				{ |msg, time, addr, recvPort|
					this.prTraceOutput( "received: % from %".format(msg, addr) );
					addedFunc.(msg[1]);
					this.prSendRequestNextDeviceChangeMsg(serialoscdHost, serialoscdPort);
				},
				'/serialosc/add',
				serialOSCNetAddr
			);
			serialOSCRemoveResponseListener=OSCFunc.new(
				{ |msg, time, addr, recvPort|
					this.prTraceOutput( "received: % from %".format(msg, addr) );
					removedFunc.(msg[1]);
					this.prSendRequestNextDeviceChangeMsg(serialoscdHost, serialoscdPort);
				},
				'/serialosc/remove',
				serialOSCNetAddr
			);
			this.prTraceOutput( "Started listening to serialosc device add / remove OSC messages" )
		};

		isTrackingConnectedDevicesChanges.if { Error("Already tracking serialosc device changes.").throw };

		serialOSCNetAddr=NetAddr(serialoscdHost ? SerialOSC.defaultSerialoscdHost, serialoscdPort ? SerialOSC.defaultSerialoscdPort);

		startListeningForSerialoscResponses.(serialOSCNetAddr, addedFunc, removedFunc);

		this.prSendRequestNextDeviceChangeMsg(serialoscdHost, serialoscdPort);
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
			serialOSCAddResponseListener.free;
			serialOSCRemoveResponseListener.free;
			this.prTraceOutput( "Stopped listening to serialosc device add / remove OSC messages" )
		};

		isTrackingConnectedDevicesChanges.not.if { Error("Not listening for serialosc responses.").throw };
		stopListeningForSerialoscResponses.();
	}

	*prSendRequestNextDeviceChangeMsg { |serialoscdHost, serialoscdPort|
		this.prSendMessage(
			["/serialosc/notify", "127.0.0.1", NetAddr.langPort],
			serialoscdPort ? SerialOSC.defaultSerialoscdPort,
			serialoscdHost
		);
	}

	*changeDeviceDestinationPort { |deviceReceivePort, deviceDestinationPort, serialoscdHost|
		this.prSendMessage(
			["/sys/port", deviceDestinationPort.asInteger],
			deviceReceivePort,
			serialoscdHost
		);
	}

	*changeDeviceDestinationHost { |deviceReceivePort, deviceDestinationHost, serialoscdHost|
		this.prSendMessage(
			["/sys/host", deviceDestinationHost.asString],
			deviceReceivePort,
			serialoscdHost
		);
	}

	*changeDeviceMessagePrefix { |deviceReceivePort, deviceMessagePrefix, serialoscdHost|
		this.prSendMessage(
			["/sys/prefix", deviceMessagePrefix.asString],
			deviceReceivePort,
			serialoscdHost
		);
	}

	*changeDeviceRotation { |deviceReceivePort, deviceRotation, serialoscdHost|
		var rotation;

		rotation = deviceRotation.asInteger;
		[0, 90, 180, 270].includes(rotation).not.if { Error("Invalid rotation: %".format(rotation)).throw };
		this.prSendMessage(
			["/sys/rotation", rotation],
			deviceReceivePort,
			serialoscdHost
		);
	}

	*prSendMessage { |message, port, serialoscdHost|
		var netAddr;
		netAddr = NetAddr(serialoscdHost ? SerialOSC.defaultSerialoscdHost, port);
		netAddr.performList(\sendMsg, message);
		this.prTraceOutput( "sent: % to %".format(message.join(" "), netAddr) );
	}

	*prTraceOutput { |str|
		trace.if { ("SerialOSC trace:" + str).postln };
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

	// swap out func and wait
	learn {|learnState = false|
		var learnFunc;
		learnFunc = this.learnFunc(learnState);
		this.disable;
		this.init(learnFunc); // keep old args if specified, so we can learn from particular channels, srcs, etc.
	}

	learnFunc {|learnState|
		var oldFunc, learnFunc;
		oldFunc = func; // old funk is ultimately better than new funk
		^{|x, y, state, timestamp, device|
			"GridKeyFunc learned: x: %\ty: %\tstate: %\tdevice: %\t\n".postf(x, y, state, device);
			this.disable;
			this.remove(learnFunc);
			oldFunc.value(x, y, state, device);// do first action
			this.init(oldFunc, x, y, if(learnState, state, nil), device);
		}
	}

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

	// swap out func and wait
	learn {
		var learnFunc;
		learnFunc = this.learnFunc;
		this.disable;
		this.init(learnFunc); // keep old args if specified, so we can learn from particular channels, srcs, etc.
	}

	learnFunc {
		var oldFunc, learnFunc;
		oldFunc = func; // old funk is ultimately better than new funk
		^{|n, delta, timestamp, device|
			"GridKeyFunc learned: n: %\tdelta: %\tdevice: %\t\n".postf(n, delta, device);
			this.disable;
			this.remove(learnFunc);
			oldFunc.value(n, delta, device);// do first action
			this.init(oldFunc, n, nil, device);
		}
	}

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
