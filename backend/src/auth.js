const express = require('express');
const router = express.Router();
const { databasePath, getQuery, runQuery } = require('./database');

router.post('/register', async (req, res) => {
    const { name, phone, license_number, password } = req.body;
    console.log("Request Came to register");
    if (!name || !phone || !license_number) {
        return res.status(400).json({
            message: 'name, phone, and license_number are required'
        });
    }

    try {
        const result = await runQuery(
            `
                INSERT INTO drivers (name, phone, license_number, password)
                VALUES (?, ?, ?, ?)
            `,
            [name, phone, license_number, password || null]
        );

        return res.status(201).json({
            message: 'Driver registered successfully',
            driverId: result.lastID,
        });
    } catch (error) {
        if (error.message.includes('UNIQUE constraint failed')) {
            return res.status(409).json({
                message: 'Phone or license number already exists'
            });
        }

        return res.status(500).json({
            message: 'Internal Server error, Please Try again Later'
        });
    }
});

router.post('/login', async (req, res) => {
    const { phone, password } = req.body;
    console.log("Request Came to login");
    if (!phone || !password) {
        return res.status(400).json({
            message: 'phone and password are required'
        });
    }

    try {
        const driver = await getQuery(
            `
                SELECT id, name, phone, license_number, created_at
                FROM drivers
                WHERE phone = ? AND password = ?
            `,
            [phone, password]
        );

        if (!driver) {
            return res.status(401).json({
                message: 'Invalid phone or password'
            });
        }

        return res.json({
            message: 'Login successful',
            driver
        });
    } catch (error) {
        return res.status(500).json({
            message: 'Failed to login',
            error: error.message
        });
    }
});

router.get('/', (_req, res) => {
    res.json({
        message: 'Auth service is running',
        databasePath
    });
});

module.exports = router;