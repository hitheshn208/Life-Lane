const express = require('express');
const router = express.Router();
const { getQuery, runQuery } = require('./database');
const { verifyAuthToken } = require('./authToken');
const { getActiveTripsForDriver } = require('./activeTripsService');
const { broadcastAllTrips } = require('./activeTripsRealtime');

const ALLOWED_SEVERITIES = new Set(['CRITICAL', 'MODERATE', 'STABLE']);

function getAuthenticatedDriverId(req) {
    return req.user?.driver_id || req.user?.driverId;
}

router.post('/active', verifyAuthToken, async (req, res) => {
    const driverId = getAuthenticatedDriverId(req);
    const {
        vehicle_number,
        patient_lat,
        patient_lon,
        hospital_lat,
        hospital_lon,
        severity,
        eta_to_hospital
    } = req.body;

    const normalizedVehicleNumber = typeof vehicle_number === 'string'
        ? vehicle_number.trim().toUpperCase()
        : '';
    const normalizedSeverity = typeof severity === 'string'
        ? severity.trim().toUpperCase()
        : '';

    if (!driverId) {
        return res.status(401).json({
            message: 'Authenticated driver id not found in token'
        });
    }

    if (
        !normalizedVehicleNumber ||
        patient_lat === undefined ||
        patient_lon === undefined ||
        hospital_lat === undefined ||
        hospital_lon === undefined ||
        eta_to_hospital === undefined ||
        !normalizedSeverity
    ) {
        return res.status(400).json({
            message: 'vehicle_number, patient_lat, patient_lon, hospital_lat, hospital_lon, severity and eta_to_hospital are required'
        });
    }

    if (!ALLOWED_SEVERITIES.has(normalizedSeverity)) {
        return res.status(400).json({
            message: 'severity must be one of CRITICAL, MODERATE or STABLE'
        });
    }

    try {
        const driverAmbulance = await getQuery(
            `
                SELECT
                    da.driver_id,
                    da.vehicle_number,
                    ga.ambulance_name,
                    ga.ambulance_type,
                    ga.registered_hospital
                FROM driver_ambulances AS da
                INNER JOIN govt_ambulances AS ga
                    ON ga.vehicle_number = da.vehicle_number
                WHERE da.driver_id = ? AND da.vehicle_number = ?
            `,
            [driverId, normalizedVehicleNumber]
        );

        if (!driverAmbulance) {
            return res.status(403).json({
                message: 'This ambulance is not registered under the authenticated driver',
                driver_id: Number(driverId),
                vehicle_number: normalizedVehicleNumber
            });
        }

        const result = await runQuery(
            `
                INSERT INTO active_trips (
                    driver_id,
                    vehicle_number,
                    patient_lat,
                    patient_lon,
                    hospital_lat,
                    hospital_lon,
                    severity,
                    eta_to_hospital
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            `,
            [
                driverId,
                normalizedVehicleNumber,
                Number(patient_lat),
                Number(patient_lon),
                Number(hospital_lat),
                Number(hospital_lon),
                normalizedSeverity,
                Number(eta_to_hospital)
            ]
        );

        const createdTrip = await getQuery(
            `
                SELECT
                    at.id,
                    at.driver_id,
                    at.vehicle_number,
                    at.patient_lat,
                    at.patient_lon,
                    at.hospital_lat,
                    at.hospital_lon,
                    at.severity,
                    at.eta_to_hospital,
                    at.start_time,
                    ga.ambulance_name,
                    ga.ambulance_type,
                    ga.registered_hospital
                FROM active_trips AS at
                INNER JOIN govt_ambulances AS ga
                    ON ga.vehicle_number = at.vehicle_number
                WHERE at.id = ?
            `,
            [result.lastID]
        );

        await broadcastAllTrips();

        return res.status(201).json({
            message: 'Active trip created successfully',
            trip: createdTrip
        });
    } catch (error) {
        return res.status(500).json({
            message: 'Failed to create active trip',
            error: error.message
        });
    }
});

router.get('/active', verifyAuthToken, async (req, res) => {
    const driverId = getAuthenticatedDriverId(req);

    if (!driverId) {
        return res.status(401).json({
            message: 'Authenticated driver id not found in token'
        });
    }

    try {
        const trips = await getActiveTripsForDriver(driverId);

        return res.json({
            driver_id: Number(driverId),
            count: trips.length,
            trips
        });
    } catch (error) {
        return res.status(500).json({
            message: 'Failed to fetch active trips',
            error: error.message
        });
    }
});

router.get('/active/public/:tripId', async (req, res) => {
    const tripId = Number(req.params.tripId);

    if (!Number.isInteger(tripId) || tripId <= 0) {
        return res.status(400).json({
            message: 'tripId must be a positive integer'
        });
    }

    try {
        const trip = await getQuery(
            `
                SELECT
                    at.id,
                    at.driver_id,
                    at.vehicle_number,
                    at.patient_lat,
                    at.patient_lon,
                    at.hospital_lat,
                    at.hospital_lon,
                    at.severity,
                    at.eta_to_hospital,
                    at.start_time,
                    ga.ambulance_name,
                    ga.ambulance_type,
                    ga.registered_hospital
                FROM active_trips AS at
                INNER JOIN govt_ambulances AS ga
                    ON ga.vehicle_number = at.vehicle_number
                WHERE at.id = ?
            `,
            [tripId]
        );

        if (!trip) {
            return res.status(404).json({
                message: 'Active trip not found',
                trip_id: tripId
            });
        }

        return res.json({
            trip
        });
    } catch (error) {
        return res.status(500).json({
            message: 'Failed to fetch active trip details',
            error: error.message
        });
    }
});

module.exports = router;
