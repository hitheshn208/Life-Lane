const express = require('express');
const app = express();
const cors = require('cors');

const authRoutes = require('./auth');
const ambulanceRoutes = require('./ambulanceRoutes');

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

app.listen(3000, '0.0.0.0', (err)=>{
    if(err)
        console.log("Error While starting the server");
    else
        console.log("Server running in http://localhost:3000");
})