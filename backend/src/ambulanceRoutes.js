const express = require('express');
const router = express.Router();
const { allQuery, getQuery, runQuery } = require('./database');
const { verifyAuthToken } = require('./authToken');

const ambulanceRouteData = {
    KA19AB1023: {
        source: { lat: 12.9716, lng: 77.5946 },
        destination: { lat: 12.9814, lng: 77.6408 },
        driver: 'Ravi Kumar',
        priority: 'High'
    },
    KA19AB2201: {
        source: { lat: 12.9352, lng: 77.6245 },
        destination: { lat: 12.9010, lng: 77.6010 },
        driver: 'Manoj Singh',
        priority: 'Medium'
    },
    KA19AB3320: {
        source: { lat: 12.9220, lng: 77.6070 },
        destination: { lat: 12.9740, lng: 77.5960 },
        driver: 'Suresh B',
        priority: 'Low'
    },
    KA19AB8741: {
        source: { lat: 12.9900, lng: 77.5920 },
        destination: { lat: 12.9570, lng: 77.6390 },
        driver: 'Akhil P',
        priority: 'High'
    },
    KA19AB8812: {
        source: { lat: 12.9450, lng: 77.5740 },
        destination: { lat: 12.9290, lng: 77.6200 },
        driver: 'Prakash N',
        priority: 'Medium'
    },
    KA19AB4521: {
        source: { lat: 12.9670, lng: 77.6200 },
        destination: { lat: 12.9720, lng: 77.7500 },
        driver: 'Kiran H',
        priority: 'High'
    },
    KA19AB9912: {
        source: { lat: 12.908327093540354, lng: 74.85458352719478 },
        destination: { lat: 12.85906366568675, lng: 74.84803097338418 },
        driver: 'Naveen M',
        priority: 'Low'
    }
};

router.get('/:plate/coordinates', (req, res) => {
    const plate = req.params.plate;
    const data = ambulanceRouteData[plate];
    console.log('Received coordinates request for', plate);

    if (!data) {
        return res.status(404).json({
            message: 'Ambulance not found',
            plate
        });
    }

    return res.json({
        plate,
        source: data.source,
        destination: data.destination,
        driver: data.driver,
        priority: data.priority
    });
});

router.post('/verify', verifyAuthToken, async (req, res) => {
    const rawVehicleNumber = req.body?.vehicle_number;
    const vehicleNumber = typeof rawVehicleNumber === 'string'
        ? rawVehicleNumber.trim().toUpperCase()
        : '';

    if (!vehicleNumber) {
        return res.status(400).json({
            message: 'vehicle_number is required'
        });
    }

    try {
        const ambulance = await getQuery(
            `
                SELECT vehicle_number, ambulance_name, ambulance_type, registered_hospital, created_at
                FROM govt_ambulances
                WHERE vehicle_number = ?
            `,
            [vehicleNumber]
        );

        if (!ambulance) {
            return res.status(404).json({
                isValidAmbulance: false,
                message: 'Vehicle number not found in government ambulance registry',
                vehicle_number: vehicleNumber
            });
        }

        return res.json({
            isValidAmbulance: true,
            message: 'Ambulance verified successfully',
            ambulance
        });
    } catch (error) {
        return res.status(500).json({
            message: 'Failed to verify ambulance',
            error: error.message
        });
    }
});

router.post('/register', verifyAuthToken, async (req, res) => {
    const driverId = req.user?.driver_id || req.user?.driverId;
    const rawVehicleNumber = req.body?.vehicle_number;
    const vehicleNumber = typeof rawVehicleNumber === 'string'
        ? rawVehicleNumber.trim().toUpperCase()
        : '';

    if (!driverId) {
        return res.status(401).json({
            message: 'Authenticated driver id not found in token'
        });
    }

    if (!vehicleNumber) {
        return res.status(400).json({
            message: 'vehicle_number is required'
        });
    }

    try {
        const ambulance = await getQuery(
            `
                SELECT vehicle_number, ambulance_name, ambulance_type, registered_hospital
                FROM govt_ambulances
                WHERE vehicle_number = ?
            `,
            [vehicleNumber]
        );

        if (!ambulance) {
            return res.status(404).json({
                message: 'Vehicle number not found in government ambulance registry',
                vehicle_number: vehicleNumber
            });
        }

        const result = await runQuery(
            `
                INSERT INTO driver_ambulances (driver_id, vehicle_number)
                VALUES (?, ?)
            `,
            [driverId, vehicleNumber]
        );

        return res.status(201).json({
            message: 'Ambulance registered to driver successfully',
            registrationId: result.lastID,
            driver_id: Number(driverId),
            ambulance
        });
    } catch (error) {
        if (error.message.includes('UNIQUE constraint failed')) {
            return res.status(409).json({
                message: 'This ambulance is already registered by this driver',
                driver_id: Number(driverId),
                vehicle_number: vehicleNumber
            });
        }

        return res.status(500).json({
            message: 'Failed to register ambulance for driver',
            error: error.message
        });
    }
});

router.get('/my-ambulances', verifyAuthToken, async (req, res) => {
    const driverId = req.user?.driver_id || req.user?.driverId;

    if (!driverId) {
        return res.status(401).json({
            message: 'Authenticated driver id not found in token'
        });
    }

    try {
        const ambulances = await allQuery(
            `
                SELECT
                    da.id,
                    da.driver_id,
                    da.vehicle_number,
                    da.registered_at,
                    ga.ambulance_name,
                    ga.ambulance_type,
                    ga.registered_hospital,
                    ga.created_at AS govt_created_at
                FROM driver_ambulances AS da
                INNER JOIN govt_ambulances AS ga
                    ON ga.vehicle_number = da.vehicle_number
                WHERE da.driver_id = ?
                ORDER BY da.registered_at DESC
            `,
            [driverId]
        );

        return res.json({
            driver_id: Number(driverId),
            count: ambulances.length,
            ambulances
        });
    } catch (error) {
        return res.status(500).json({
            message: 'Failed to fetch driver ambulances',
            error: error.message
        });
    }
});

module.exports = router;
