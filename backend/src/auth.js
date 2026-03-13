const express = require('express');
const router = express.Router();
const bcrypt = require('bcrypt');
const { databasePath, getQuery, runQuery } = require('./database');
const { generateAuthToken, verifyAuthToken } = require('./authToken');

const SALT_ROUNDS = 10;

router.post('/register', async (req, res) => {
    const { name, phone, license_number, password } = req.body;
    console.log("Request Came to register");
    if (!name || !phone || !license_number || !password) {
        return res.status(400).json({
            message: 'name, phone, license_number and password are required'
        });
    }

    try {
        const passwordHash = await bcrypt.hash(password, SALT_ROUNDS);

        const result = await runQuery(
            `
                INSERT INTO drivers (name, phone, license_number, password)
                VALUES (?, ?, ?, ?)
            `,
            [name, phone, license_number, passwordHash]
        );

        const token = generateAuthToken({
            id: result.lastID,
            name,
            phone
        });

        return res.status(201).json({
            message: 'Driver registered successfully',
            driverId: result.lastID,
            token
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
                SELECT id, name, phone, license_number, password, created_at
                FROM drivers
                WHERE phone = ?
            `,
            [phone]
        );

        if (!driver) {
            return res.status(401).json({
                message: 'Invalid phone or password'
            });
        }

        const passwordMatch = await bcrypt.compare(password, driver.password || '');

        if (!passwordMatch) {
            return res.status(401).json({
                message: 'Invalid phone or password'
            });
        }

        const token = generateAuthToken(driver);
        const { password: _passwordHash, ...driverWithoutPassword } = driver;

        return res.json({
            message: 'Login successful',
            token,
            driver: driverWithoutPassword
        });
    } catch (error) {
        return res.status(500).json({
            message: 'Failed to login',
            error: error.message
        });
    }
});

router.get('/verify-token', verifyAuthToken, (req, res) => {
    console.log("Arrived for vrification")
    return res.json({
        isValid: true,
        message: 'Token is valid',
        user: req.user
    });
});

router.get('/', (_req, res) => {
    res.json({
        message: 'Auth service is running',
        databasePath
    });
});

module.exports = router;