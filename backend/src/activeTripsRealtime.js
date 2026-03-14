const { WebSocketServer, WebSocket } = require('ws');
const { getAllActiveTrips } = require('./activeTripsService');
const {
    ensureTripSignalSimulation,
    attachRuntimeDataToTrips,
    setSignalBroadcastHandler,
    setMobileSignalBroadcastHandler,
    updateAmbulanceLocation,
    getMobileSignalPayload
} = require('./services/signalSimulationService');

const connectedClients = new Set();
const mobileSocketsByTrip = new Map(); // tripId (number) -> socket
const mobileSocketsByVehicle = new Map(); // vehicle_number (uppercase) -> socket

function sendJson(socket, payload) {
    if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(payload));
    }
}

function sendSignalUpdateToMobile(payload) {
    const tripId = payload.trip_id;
    const vehicleNumber = payload.vehicle_number ? String(payload.vehicle_number).trim().toUpperCase() : '';
    const socket = mobileSocketsByTrip.get(tripId) || mobileSocketsByVehicle.get(vehicleNumber);
    if (socket) {
        console.log(
            `[MOBILE_SIGNAL_SENT] trip_id=${tripId} reason=${payload.reason || 'unknown'} signals=${Array.isArray(payload.signals) ? payload.signals.length : 0}`
        );
        sendJson(socket, payload);
    } else {
        console.log(
            `[MOBILE_SIGNAL_SKIPPED] trip_id=${tripId} vehicle=${vehicleNumber || 'unknown'} reason=${payload.reason || 'unknown'} no_mobile_socket_mapping=true`
        );
    }
}

async function broadcastAllTrips() {
    if (connectedClients.size === 0) return;

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

    connectedClients.forEach((socket) => sendJson(socket, payload));
}

function broadcastLiveLocationUpdate(updatePayload) {
    if (connectedClients.size === 0) return;

    const payload = {
        type: 'live_location_update',
        ...updatePayload,
        timestamp: new Date().toISOString()
    };

    connectedClients.forEach((socket) => sendJson(socket, payload));
}

function broadcastTripSignalsUpdate(updatePayload) {
    if (connectedClients.size === 0) return;

    connectedClients.forEach((socket) => sendJson(socket, updatePayload));
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

    wss.on('connection', async (socket, request) => {
        connectedClients.add(socket);

        const requestUrl = new URL(request.url, 'http://localhost');
        const role = requestUrl.searchParams.get('role') || 'web';
        const tripIdRaw = requestUrl.searchParams.get('trip_id') || requestUrl.searchParams.get('tripId');
        const tripId = Number(tripIdRaw);
        const vehicleNumberRaw = requestUrl.searchParams.get('vehicle_number') || requestUrl.searchParams.get('vehicleNumber');
        const vehicleNumber = vehicleNumberRaw ? String(vehicleNumberRaw).trim().toUpperCase() : null;

        if (role === 'mobile') {
            const remoteAddress = request.socket?.remoteAddress || 'unknown';
            console.log(
                `[MOBILE_WS_CONNECTED] ip=${remoteAddress} trip_id=${Number.isFinite(tripId) && tripId > 0 ? tripId : 'unknown'} vehicle=${vehicleNumber || 'unknown'}`
            );
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

        sendInitialSignalDetails(socket, trips);

        if (role === 'mobile' && Number.isFinite(tripId) && tripId > 0) {
            mobileSocketsByTrip.set(tripId, socket);
        }

        if (role === 'mobile' && vehicleNumber) {
            mobileSocketsByVehicle.set(vehicleNumber, socket);
        }

        const initialMobileSignalPayload = getMobileSignalPayload({
            tripId: Number.isFinite(tripId) && tripId > 0 ? tripId : null,
            vehicle_number: vehicleNumber,
            reason: 'ws_connected'
        });

        if (initialMobileSignalPayload) {
            sendJson(socket, initialMobileSignalPayload);
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
                const vehicle_number = rawVehicle === undefined || rawVehicle === null
                    ? null
                    : String(rawVehicle).trim().toUpperCase();

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

                // Track which socket belongs to this trip so signal updates can be sent back
                if (hasTripId) {
                    mobileSocketsByTrip.set(trip_id, socket);
                }

                if (hasVehicle) {
                    mobileSocketsByVehicle.set(vehicle_number, socket);
                }

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
                    eta_to_hospital: Number.isFinite(eta_to_hospital) ? eta_to_hospital : null,
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
            connectedClients.delete(socket);
            // Remove any trip->socket mapping for this socket
            for (const [tripId, s] of mobileSocketsByTrip.entries()) {
                if (s === socket) mobileSocketsByTrip.delete(tripId);
            }

            for (const [vehicleNumber, s] of mobileSocketsByVehicle.entries()) {
                if (s === socket) mobileSocketsByVehicle.delete(vehicleNumber);
            }
        });
        socket.on('error', () => {
            connectedClients.delete(socket);
            for (const [tripId, s] of mobileSocketsByTrip.entries()) {
                if (s === socket) mobileSocketsByTrip.delete(tripId);
            }

            for (const [vehicleNumber, s] of mobileSocketsByVehicle.entries()) {
                if (s === socket) mobileSocketsByVehicle.delete(vehicleNumber);
            }
        });
    });

    return wss;
}

module.exports = {
    initializeActiveTripsSocket,
    broadcastAllTrips,
    broadcastLiveLocationUpdate,
    broadcastTripSignalsUpdate
};
