const express = require('express');
const cors = require('cors');
const http = require('http');

const app = express();
const server = http.createServer(app);

const authRoutes = require('./auth');
const ambulanceRoutes = require('./ambulanceRoutes');
const activeTripsRoutes = require('./activeTripsRoutes');
const { initializeActiveTripsSocket } = require('./activeTripsRealtime');

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

server.listen(3000, '0.0.0.0', (err)=>{
    if(err)
        console.log("Error While starting the server");
    else
        console.log("Server running in http://localhost:3000");
})