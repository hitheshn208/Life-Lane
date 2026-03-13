const { WebSocketServer, WebSocket } = require('ws');
const { getAllActiveTrips } = require('./activeTripsService');

const dashboardClients = new Set();

function sendJson(socket, payload) {
    if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(payload));
    }
}

async function broadcastAllTrips() {
    if (dashboardClients.size === 0) return;

    const trips = await getAllActiveTrips();
    const payload = {
        type: 'active_trips_snapshot',
        count: trips.length,
        trips
    };

    dashboardClients.forEach((socket) => sendJson(socket, payload));
}

function initializeActiveTripsSocket(server) {
    const wss = new WebSocketServer({
        server,
        path: '/ws/active-trips'
    });

    wss.on('connection', async (socket) => {
        dashboardClients.add(socket);

        sendJson(socket, {
            type: 'connection_ack',
            message: 'Active trips websocket connected'
        });

        // Send immediate snapshot to the newly connected client
        const trips = await getAllActiveTrips();
        sendJson(socket, {
            type: 'active_trips_snapshot',
            count: trips.length,
            trips
        });

        socket.on('close', () => dashboardClients.delete(socket));
        socket.on('error', () => dashboardClients.delete(socket));
    });

    return wss;
}

module.exports = {
    initializeActiveTripsSocket,
    broadcastAllTrips
};
