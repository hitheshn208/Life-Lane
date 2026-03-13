const express = require('express');
const app = express();

const authRoutes = require('./auth');
const ambulanceRoutes = require('./ambulanceRoutes');

app.use(express.json());

app.use((req, res, next) => {
    res.header('Access-Control-Allow-Origin', '*');
    res.header('Access-Control-Allow-Headers', 'Origin, X-Requested-With, Content-Type, Accept');
    res.header('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS');

    if (req.method === 'OPTIONS') {
        return res.sendStatus(204);
    }

    next();
});

// app.get("/", (req, res)=>{
//     res.send("Backend is running");
// });

app.use('/auth', authRoutes);
app.use('/api/ambulances', ambulanceRoutes);

app.listen(3000, (err)=>{
    if(err)
        console.log("Error While starting the server");
    else
        console.log("Server running in http://localhost:3000");
})