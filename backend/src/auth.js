const express = require('express');
const router = express.Router();

// Register route
router.post('/register', (req, res) => {
    // Registration logic here
    res.send('User registered');
});

// Login route
router.post('/login', (req, res) => {
    // Login logic here
    res.send('User logged in');
});

router.get('/', (req,res)=>{
    console.log("Recievec the request");
})

module.exports = router;