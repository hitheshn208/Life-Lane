const fs = require('fs');
const path = require('path');

const envFilePath = path.join(__dirname, '..', '.env');

if (fs.existsSync(envFilePath)) {
    const envContents = fs.readFileSync(envFilePath, 'utf8');

    envContents
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter((line) => line && !line.startsWith('#') && line.includes('='))
        .forEach((line) => {
            const separatorIndex = line.indexOf('=');
            const key = line.slice(0, separatorIndex).trim();
            const value = line.slice(separatorIndex + 1).trim();

            if (key && process.env[key] === undefined) {
                process.env[key] = value;
            }
        });
}

const express = require('express');
const cors = require('cors');
const http = require('http');

const app = express();
const server = http.createServer(app);

const authRoutes = require('./auth');
const ambulanceRoutes = require('./ambulanceRoutes');
const activeTripsRoutes = require('./activeTripsRoutes');
const { initializeActiveTripsSocket } = require('./activeTripsRealtime');
const { getAllActiveTrips } = require('./activeTripsService');
const { bootstrapTripSignalSimulations } = require('./services/signalSimulationService');

app.use(express.json());

const CORS_ORIGIN = process.env.CORS_ORIGIN || '*';

app.use(cors({
    origin: CORS_ORIGIN,
    methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
    allowedHeaders: ['Origin', 'X-Requested-With', 'Content-Type', 'Accept', 'Authorization']
}));

app.get('/', (_req, res) => {
    res.status(200).json({
        ok: true,
        service: 'Build-for-Bangalore backend',
        timestamp: new Date().toISOString()
    });
});

app.get('/health', (_req, res) => {
    res.status(200).json({ ok: true });
});

app.use('/auth', authRoutes);
app.use('/api/ambulances', ambulanceRoutes);
app.use('/api/trips', activeTripsRoutes);

initializeActiveTripsSocket(server);

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', async () => {
    try {
        const trips = await getAllActiveTrips();
        await bootstrapTripSignalSimulations(trips);
    } catch (error) {
        console.error('Failed to bootstrap trip signal simulations:', error.message);
    }

    console.log('Server running on port', PORT);
});

process.on('SIGTERM', () => {
    server.close(() => process.exit(0));
});

process.on('SIGINT', () => {
    server.close(() => process.exit(0));
});