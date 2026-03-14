const { WebSocketServer, WebSocket } = require('ws');
const { getAllActiveTrips } = require('./activeTripsService');
const { allQuery, runQuery } = require('./database');
const {
    ensureTripSignalSimulation,
    attachRuntimeDataToTrips,
    setSignalBroadcastHandler,
    setMobileSignalBroadcastHandler,
    updateAmbulanceLocation,
    getMobileSignalPayload,
    stopTripSignalSimulation
} = require('./services/signalSimulationService');

const MOBILE_SOCKET_TIMEOUT_MS = 10000;

const connectedClients = new Set();
const visualClients = new Set();
const mobileSocketsByTrip = new Map();
const mobileSocketsByVehicle = new Map();
let activeTripsWss = null;

function normalizeVehicleNumber(value) {
    if (value === undefined || value === null) {
        return '';
    }

    return String(value).trim().toUpperCase();
}

function removeMobileSocketMappings(socket) {
    for (const [tripId, mappedSocket] of mobileSocketsByTrip.entries()) {
        if (mappedSocket === socket) {
            mobileSocketsByTrip.delete(tripId);
        }
    }

    for (const [vehicleNumber, mappedSocket] of mobileSocketsByVehicle.entries()) {
        if (mappedSocket === socket) {
            mobileSocketsByVehicle.delete(vehicleNumber);
        }
    }
}

function registerMobileSocket(socket, tripId, vehicleNumber) {
    if (Number.isFinite(Number(tripId)) && Number(tripId) > 0) {
        mobileSocketsByTrip.set(Number(tripId), socket);
    }

    const normalizedVehicleNumber = normalizeVehicleNumber(vehicleNumber);

    if (normalizedVehicleNumber) {
        mobileSocketsByVehicle.set(normalizedVehicleNumber, socket);
    }
}

function getMobileSocket({ tripId, vehicleNumber }) {
    const normalizedTripId = Number(tripId);
    const normalizedVehicleNumber = normalizeVehicleNumber(vehicleNumber);

    return mobileSocketsByTrip.get(normalizedTripId) || mobileSocketsByVehicle.get(normalizedVehicleNumber) || null;
}

function sendTripDeactivatedAndClose(socket, payload) {
    if (!socket) {
        return;
    }

    sendJson(socket, {
        type: 'trip_deactivated',
        ...payload,
        updated_at: new Date().toISOString()
    });

    if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
        socket.close(4001, 'trip_deactivated');
    }
}

function broadcastTripDeactivated(payload) {
    if (visualClients.size === 0) {
        return;
    }

    const message = {
        type: 'trip_deactivated',
        ...payload,
        updated_at: new Date().toISOString()
    };

    visualClients.forEach((socket) => sendJson(socket, message));
}

async function deactivateTripOnMobileSocketTimeout(socket) {
    if (!socket || socket.meta?.timeoutHandled) {
        return;
    }

    socket.meta.timeoutHandled = true;

    const tripId = Number(socket.meta?.tripId);
    const vehicleNumber = normalizeVehicleNumber(socket.meta?.vehicleNumber);

    if ((!Number.isFinite(tripId) || tripId <= 0) && !vehicleNumber) {
        return;
    }

    let tripsToDeactivate = [];

    if (Number.isFinite(tripId) && tripId > 0) {
        tripsToDeactivate = await allQuery(
            `
                SELECT id, vehicle_number
                FROM active_trips
                WHERE id = ?
            `,
            [tripId]
        );
    }

    if (tripsToDeactivate.length === 0 && vehicleNumber) {
        tripsToDeactivate = await allQuery(
            `
                SELECT id, vehicle_number
                FROM active_trips
                WHERE UPPER(TRIM(vehicle_number)) = ?
            `,
            [vehicleNumber]
        );
    }

    if (tripsToDeactivate.length === 0) {
        return;
    }

    const uniqueVehicleNumbers = Array.from(
        new Set(tripsToDeactivate.map((trip) => normalizeVehicleNumber(trip.vehicle_number)).filter(Boolean))
    );

    for (const normalizedVehicleNumber of uniqueVehicleNumbers) {
        await runQuery(
            `
                DELETE FROM active_trips
                WHERE UPPER(TRIM(vehicle_number)) = ?
            `,
            [normalizedVehicleNumber]
        );
    }

    tripsToDeactivate.forEach((trip) => {
        closeTripConnections({
            tripId: trip.id,
            vehicleNumber: trip.vehicle_number,
            reason: 'mobile_socket_timeout'
        });
        stopTripSignalSimulation(trip.id);
    });

    await broadcastAllTrips();

    console.log(
        `[MOBILE_WS_TIMEOUT_DEACTIVATED] trip_id=${Number.isFinite(tripId) && tripId > 0 ? tripId : 'unknown'} vehicle=${vehicleNumber || 'unknown'} timeout_ms=${MOBILE_SOCKET_TIMEOUT_MS}`
    );
}

function clearMobileSocketTimeout(socket) {
    if (socket?.meta?.mobileTimeoutHandle) {
        clearTimeout(socket.meta.mobileTimeoutHandle);
        socket.meta.mobileTimeoutHandle = null;
    }
}

function scheduleMobileSocketTimeout(socket) {
    if (!socket || socket.meta?.role !== 'mobile') {
        return;
    }

    clearMobileSocketTimeout(socket);

    socket.meta.mobileTimeoutHandle = setTimeout(() => {
        deactivateTripOnMobileSocketTimeout(socket).catch((error) => {
            console.error('Failed to deactivate trip on mobile socket timeout:', error.message);
        });
    }, MOBILE_SOCKET_TIMEOUT_MS);
}

function closeTripConnections({ tripId, vehicleNumber, reason = 'trip_deactivated' }) {
    const normalizedTripId = Number(tripId);
    const normalizedVehicleNumber = normalizeVehicleNumber(vehicleNumber);
    const socketsToClose = new Set();
    const deactivationPayload = {
        reason,
        trip_id: Number.isFinite(normalizedTripId) && normalizedTripId > 0 ? normalizedTripId : null,
        vehicle_number: normalizedVehicleNumber || null
    };

    const mobileSocket = getMobileSocket({
        tripId: normalizedTripId,
        vehicleNumber: normalizedVehicleNumber
    });

    if (mobileSocket) {
        socketsToClose.add(mobileSocket);
    }

    connectedClients.forEach((socket) => {
        const socketRole = socket.meta?.role || 'unknown';
        const socketTripId = Number(socket.meta?.tripId);
        const socketVehicleNumber = normalizeVehicleNumber(socket.meta?.vehicleNumber);
        const isMatchingTrip = Number.isFinite(normalizedTripId) && normalizedTripId > 0 && socketTripId === normalizedTripId;
        const isMatchingVehicle = normalizedVehicleNumber && socketVehicleNumber === normalizedVehicleNumber;

        if (socketRole === 'dashboard') {
            return;
        }

        if (isMatchingTrip || isMatchingVehicle) {
            socketsToClose.add(socket);
        }
    });

    socketsToClose.forEach((socket) => {
        sendTripDeactivatedAndClose(socket, deactivationPayload);
        removeMobileSocketMappings(socket);
        visualClients.delete(socket);
        connectedClients.delete(socket);
    });

    broadcastTripDeactivated(deactivationPayload);

    if (socketsToClose.size > 0) {
        console.log(
            `[TRIP_WS_CLOSED] trip_id=${Number.isFinite(normalizedTripId) && normalizedTripId > 0 ? normalizedTripId : 'unknown'} vehicle=${normalizedVehicleNumber || 'unknown'} sockets=${socketsToClose.size}`
        );
    }
}

function sendJson(socket, payload) {
    if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(payload));
    }
}

function sendSignalUpdateToMobile(payload) {
    const tripId = payload.trip_id;
    const vehicleNumber = normalizeVehicleNumber(payload.vehicle_number);
    const socket = getMobileSocket({ tripId, vehicleNumber });
    const reason = payload.reason || 'unknown';
    const shouldLogMissingSocket = new Set([
        'ws_connected',
        'ambulance_location',
        'signal_node_reached',
        'traffic_refresh'
    ]).has(reason);

    if (socket) {
        console.log(
            `[MOBILE_SIGNAL_SENT] trip_id=${tripId} reason=${reason} signals=${Array.isArray(payload.signals) ? payload.signals.length : 0}`
        );
        sendJson(socket, payload);
    } else if (shouldLogMissingSocket) {
        console.log(
            `[MOBILE_SIGNAL_SKIPPED] trip_id=${tripId} vehicle=${vehicleNumber || 'unknown'} reason=${reason} no_mobile_socket_mapping=true`
        );
    }
}

async function broadcastAllTrips() {
    if (visualClients.size === 0) return;

    const activeTrips = await getAllActiveTrips();
    await Promise.all(
        (activeTrips || []).map((trip) => ensureTripSignalSimulation(trip).catch(() => null))
    );
    const trips = attachRuntimeDataToTrips(activeTrips);
    const payload = {
        type: 'active_trips_snapshot',
        count: trips.length,
        trips
    };

    visualClients.forEach((socket) => sendJson(socket, payload));
}

function broadcastLiveLocationUpdate(updatePayload) {
    if (visualClients.size === 0) return;

    const payload = {
        type: 'live_location_update',
        ...updatePayload,
        timestamp: new Date().toISOString()
    };

    visualClients.forEach((socket) => sendJson(socket, payload));
}

function broadcastTripSignalsUpdate(updatePayload) {
    if (visualClients.size === 0) return;

    visualClients.forEach((socket) => sendJson(socket, updatePayload));
}

function sendInitialSignalDetails(socket, trips) {
    (trips || []).forEach((trip) => {
        if (!Array.isArray(trip.signals) || trip.signals.length === 0) {
            return;
        }

        sendJson(socket, {
            type: 'trip_signals_update',
            reason: 'ws_connected',
            tripId: Number(trip.id),
            trip_id: Number(trip.id),
            vehicle_number: trip.vehicle_number,
            eta_to_hospital: trip.eta_to_hospital ?? null,
            ambulanceLat: trip.ambulanceLat ?? trip.ambulance_lat ?? null,
            ambulanceLon: trip.ambulanceLon ?? trip.ambulance_lon ?? null,
            ambulance_lat: trip.ambulance_lat ?? trip.ambulanceLat ?? null,
            ambulance_lon: trip.ambulance_lon ?? trip.ambulanceLon ?? null,
            signals: trip.signals,
            route: trip.route || null,
            updated_at: new Date().toISOString()
        });
    });
}

function initializeActiveTripsSocket(server) {
    setSignalBroadcastHandler((payload) => broadcastTripSignalsUpdate(payload));
    setMobileSignalBroadcastHandler((payload) => sendSignalUpdateToMobile(payload));

    const wss = new WebSocketServer({
        server,
        path: '/ws/active-trips'
    });

    activeTripsWss = wss;

    wss.on('connection', async (socket, request) => {
        connectedClients.add(socket);

        const requestUrl = new URL(request.url, 'http://localhost');
        const role = requestUrl.searchParams.get('role') || 'web';
        const tripIdRaw = requestUrl.searchParams.get('trip_id') || requestUrl.searchParams.get('tripId');
        const tripId = Number(tripIdRaw);
        const vehicleNumberRaw = requestUrl.searchParams.get('vehicle_number') || requestUrl.searchParams.get('vehicleNumber');
        const vehicleNumber = normalizeVehicleNumber(vehicleNumberRaw);
        const remoteAddress = request.socket?.remoteAddress || 'unknown';

        socket.meta = {
            role,
            tripId: Number.isFinite(tripId) && tripId > 0 ? tripId : null,
            vehicleNumber: vehicleNumber || null
        };

        if (role === 'mobile') {
            registerMobileSocket(socket, socket.meta.tripId, socket.meta.vehicleNumber);
            scheduleMobileSocketTimeout(socket);
            console.log(
                `[MOBILE_WS_CONNECTED] ip=${remoteAddress} trip_id=${socket.meta.tripId || 'unknown'} vehicle=${socket.meta.vehicleNumber || 'unknown'}`
            );
        } else {
            visualClients.add(socket);
            if (role !== 'dashboard') {
                console.log(`[WS_CONNECTED] role=${role} ip=${remoteAddress}`);
            }
        }

        sendJson(socket, {
            type: 'connection_ack',
            message: 'Active trips websocket connected',
            role
        });

        // Send immediate snapshot to the newly connected client
        const activeTrips = await getAllActiveTrips();
        await Promise.all(
            (activeTrips || []).map((trip) => ensureTripSignalSimulation(trip).catch(() => null))
        );
        const trips = attachRuntimeDataToTrips(activeTrips);
        sendJson(socket, {
            type: 'active_trips_snapshot',
            count: trips.length,
            trips
        });

        if (role !== 'mobile') {
            sendInitialSignalDetails(socket, trips);
        }

        if (role === 'mobile') {
            const initialMobileSignalPayload = getMobileSignalPayload({
                tripId: socket.meta.tripId,
                vehicle_number: socket.meta.vehicleNumber,
                reason: 'ws_connected'
            });

            if (initialMobileSignalPayload) {
                sendJson(socket, initialMobileSignalPayload);
            }
        }

        socket.on('message', (rawMessage) => {
            try {
                const payload = JSON.parse(String(rawMessage));

                if (payload.type !== 'mobile_location_update') {
                    return;
                }

                const tripIdRaw = payload.trip_id ?? payload.tripId;
                const trip_id = Number(tripIdRaw);

                const rawVehicle = payload.vehicle_number ?? payload.vehicleNumber;
                const vehicle_number = normalizeVehicleNumber(rawVehicle);

                const latRaw = payload.lat ?? payload.latitude;
                const lonRaw = payload.lon ?? payload.lng ?? payload.longitude;
                const lat = Number(latRaw);
                const lon = Number(lonRaw);

                const etaRaw = payload.eta_to_hospital ?? payload.etaToHospital ?? payload.eta;
                const eta_to_hospital = etaRaw === undefined || etaRaw === null
                    ? null
                    : Number(etaRaw);

                const hasTripId = Number.isFinite(trip_id) && trip_id > 0;
                const hasVehicle = typeof vehicle_number === 'string' && vehicle_number.length > 0;
                const hasLatLon = Number.isFinite(lat) && Number.isFinite(lon);

                if ((!hasTripId && !hasVehicle) || !hasLatLon) {
                    sendJson(socket, {
                        type: 'error',
                        message: 'mobile_location_update requires trip_id or vehicle_number, and valid lat/lon'
                    });
                    return;
                }

                socket.meta = {
                    role: socket.meta?.role || role,
                    tripId: hasTripId ? trip_id : socket.meta?.tripId || null,
                    vehicleNumber: hasVehicle ? vehicle_number : socket.meta?.vehicleNumber || null
                };

                registerMobileSocket(socket, socket.meta.tripId, socket.meta.vehicleNumber);
                scheduleMobileSocketTimeout(socket);

                console.log(
                    `[MOBILE_LOCATION_UPDATE] trip_id=${socket.meta.tripId || 'unknown'} vehicle=${socket.meta.vehicleNumber || 'unknown'} lat=${lat} lon=${lon}`
                );

                const signalPayload = updateAmbulanceLocation({
                    tripId: hasTripId ? trip_id : null,
                    vehicle_number: hasVehicle ? vehicle_number : null,
                    lat,
                    lon,
                    eta_to_hospital: Number.isFinite(eta_to_hospital) ? eta_to_hospital : null
                });

                broadcastLiveLocationUpdate({
                    source: 'mobile',
                    trip_id: hasTripId ? trip_id : null,
                    vehicle_number: hasVehicle ? vehicle_number : null,
                    lat,
                    lon,
                    eta_to_hospital: signalPayload?.eta_to_hospital
                        ?? (Number.isFinite(eta_to_hospital) ? eta_to_hospital : null),
                    signals: signalPayload?.signals || []
                });
            } catch (_error) {
                sendJson(socket, {
                    type: 'error',
                    message: 'Invalid websocket JSON payload'
                });
            }
        });

        socket.on('close', () => {
            clearMobileSocketTimeout(socket);
            connectedClients.delete(socket);
            visualClients.delete(socket);
            removeMobileSocketMappings(socket);
            if ((socket.meta?.role || 'unknown') !== 'dashboard') {
                console.log(`[WS_DISCONNECTED] role=${socket.meta?.role || 'unknown'}`);
            }
        });

        socket.on('error', () => {
            clearMobileSocketTimeout(socket);
            connectedClients.delete(socket);
            visualClients.delete(socket);
            removeMobileSocketMappings(socket);
        });
    });

    return wss;
}

function shutdownActiveTripsSocketServer() {
    connectedClients.forEach((socket) => {
        clearMobileSocketTimeout(socket);
        removeMobileSocketMappings(socket);

        try {
            if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
                socket.close(1001, 'server_shutdown');
            }
        } catch (_error) {
        }
    });

    connectedClients.clear();
    visualClients.clear();
    mobileSocketsByTrip.clear();
    mobileSocketsByVehicle.clear();

    if (activeTripsWss) {
        try {
            activeTripsWss.close();
        } catch (_error) {
        }

        activeTripsWss = null;
    }
}

module.exports = {
    initializeActiveTripsSocket,
    broadcastAllTrips,
    broadcastLiveLocationUpdate,
    broadcastTripSignalsUpdate,
    closeTripConnections,
    shutdownActiveTripsSocketServer
};
