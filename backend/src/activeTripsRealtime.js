const { WebSocketServer, WebSocket } = require('ws');
const { getAllActiveTrips } = require('./activeTripsService');

const connectedClients = new Set();

function sendJson(socket, payload) {
    if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(payload));
    }
}

async function broadcastAllTrips() {
    if (connectedClients.size === 0) return;

    const trips = await getAllActiveTrips();
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

function initializeActiveTripsSocket(server) {
    const wss = new WebSocketServer({
        server,
        path: '/ws/active-trips'
    });

    wss.on('connection', async (socket, request) => {
        connectedClients.add(socket);

        const requestUrl = new URL(request.url, 'http://localhost');
        const role = requestUrl.searchParams.get('role') || 'web';

        sendJson(socket, {
            type: 'connection_ack',
            message: 'Active trips websocket connected',
            role
        });

        // Send immediate snapshot to the newly connected client
        const trips = await getAllActiveTrips();
        sendJson(socket, {
            type: 'active_trips_snapshot',
            count: trips.length,
            trips
        });

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

                broadcastLiveLocationUpdate({
                    source: 'mobile',
                    trip_id: hasTripId ? trip_id : null,
                    vehicle_number: hasVehicle ? vehicle_number : null,
                    lat,
                    lon,
                    eta_to_hospital: Number.isFinite(eta_to_hospital) ? eta_to_hospital : null
                });
            } catch (_error) {
                sendJson(socket, {
                    type: 'error',
                    message: 'Invalid websocket JSON payload'
                });
            }
        });

        socket.on('close', () => connectedClients.delete(socket));
        socket.on('error', () => connectedClients.delete(socket));
    });

    return wss;
}

module.exports = {
    initializeActiveTripsSocket,
    broadcastAllTrips,
    broadcastLiveLocationUpdate
};
