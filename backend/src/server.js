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

app.use(cors({
    origin: '*',
    methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
    allowedHeaders: ['Origin', 'X-Requested-With', 'Content-Type', 'Accept']
}));

// app.get("/", (req, res)=>{
//     res.send("Backend is running");
// });

app.use('/auth', authRoutes);
app.use('/api/ambulances', ambulanceRoutes);
app.use('/api/trips', activeTripsRoutes);

initializeActiveTripsSocket(server);

// server.listen(3000, '0.0.0.0', (err)=>{
//     if(err)
//         console.log("Error While starting the server");
//     else {
//         getAllActiveTrips()
//             .then((trips) => bootstrapTripSignalSimulations(trips))
//             .catch((error) => console.error('Failed to bootstrap trip signal simulations:', error.message));
//         console.log("Server running in http://localhost:3000");
//     }
// })


const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log("Server running on port", PORT);
});