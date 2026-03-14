const express = require('express');
const router = express.Router();
const { allQuery, getQuery, runQuery } = require('./database');
const { verifyAuthToken } = require('./authToken');
const { getActiveTripsForDriver } = require('./activeTripsService');
const { broadcastAllTrips } = require('./activeTripsRealtime');
const {
    ensureTripSignalSimulation,
    attachRuntimeDataToTrip,
    getTripVisualizationPayload,
    stopTripSignalSimulation
} = require('./services/signalSimulationService');

const ALLOWED_SEVERITIES = new Set(['CRITICAL', 'MODERATE', 'STABLE']);

function getAuthenticatedDriverId(req) {
    return req.user?.driver_id || req.user?.driverId;
}

async function resolveVehicleNumberForDeactivation(driverId, vehicleIdRaw) {
    if (typeof vehicleIdRaw === 'string') {
        const normalized = vehicleIdRaw.trim().toUpperCase();
        if (normalized) {
            return normalized;
        }
    }

    const numericVehicleId = Number(vehicleIdRaw);
    if (!Number.isInteger(numericVehicleId) || numericVehicleId <= 0) {
        return '';
    }

    const activeTripById = await getQuery(
        `
            SELECT vehicle_number
            FROM active_trips
            WHERE driver_id = ? AND id = ?
            LIMIT 1
        `,
        [driverId, numericVehicleId]
    );

    if (activeTripById?.vehicle_number) {
        return String(activeTripById.vehicle_number).trim().toUpperCase();
    }

    const registeredAmbulanceById = await getQuery(
        `
            SELECT vehicle_number
            FROM driver_ambulances
            WHERE driver_id = ? AND id = ?
            LIMIT 1
        `,
        [driverId, numericVehicleId]
    );

    if (registeredAmbulanceById?.vehicle_number) {
        return String(registeredAmbulanceById.vehicle_number).trim().toUpperCase();
    }

    return '';
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

        const existingActiveTrip = await getQuery(
            `
                SELECT id, driver_id, vehicle_number, severity, start_time
                FROM active_trips
                WHERE vehicle_number = ?
                ORDER BY start_time DESC
                LIMIT 1
            `,
            [normalizedVehicleNumber]
        );

        if (existingActiveTrip) {
            return res.status(409).json({
                message: existingActiveTrip.driver_id === Number(driverId)
                    ? 'This ambulance already has an active trip under this driver'
                    : 'This ambulance is currently active and cannot be engaged by another driver',
                active_trip: existingActiveTrip
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

        await ensureTripSignalSimulation(createdTrip);

        await broadcastAllTrips();

        return res.status(201).json({
            message: 'Active trip created successfully',
            trip: attachRuntimeDataToTrip(createdTrip)
        });
    } catch (error) {
        return res.status(500).json({
            message: 'Failed to create active trip',
            error: error.message
        });
    }
});

router.post('/active/deactivate', verifyAuthToken, async (req, res) => {
    console.log("Recieved for deactivation");
    const tokenDriverId = getAuthenticatedDriverId(req);
    const driverIdRaw = req.body?.driver_id;
    const vehicleIdRaw = req.body?.vehicle_id ?? req.body?.vehicle_number;

    const driverId = Number(driverIdRaw);

    if (!tokenDriverId) {
        return res.status(401).json({
            message: 'Authenticated driver id not found in token'
        });
    }

    if (!Number.isInteger(driverId) || driverId <= 0 || vehicleIdRaw === undefined || vehicleIdRaw === null || String(vehicleIdRaw).trim() === '') {
        return res.status(400).json({
            message: 'driver_id and vehicle_id (or vehicle_number) are required'
        });
    }

    if (Number(tokenDriverId) !== driverId) {
        return res.status(403).json({
            message: 'driver_id does not match authenticated token'
        });
    }

    try {
        const vehicleNumber = await resolveVehicleNumberForDeactivation(driverId, vehicleIdRaw);

        if (!vehicleNumber) {
            return res.status(404).json({
                message: 'Unable to resolve vehicle from provided vehicle_id/vehicle_number for this driver',
                driver_id: driverId,
                vehicle_id: vehicleIdRaw
            });
        }

        const driverOwnsVehicle = await getQuery(
            `
                SELECT id, driver_id, vehicle_number
                FROM driver_ambulances
                WHERE driver_id = ? AND vehicle_number = ?
                LIMIT 1
            `,
            [driverId, vehicleNumber]
        );

        if (!driverOwnsVehicle) {
            return res.status(403).json({
                message: 'This vehicle is not registered under the authenticated driver',
                driver_id: driverId,
                vehicle_number: vehicleNumber
            });
        }

        const tripsToDeactivate = await allQuery(
            `
                SELECT id, driver_id, vehicle_number, severity, start_time
                FROM active_trips
                WHERE vehicle_number = ?
                ORDER BY start_time DESC
            `,
            [vehicleNumber]
        );

        if (tripsToDeactivate.length === 0) {
            return res.status(404).json({
                message: 'No active trip found for this vehicle',
                driver_id: driverId,
                vehicle_number: vehicleNumber
            });
        }

        const deletionResult = await runQuery(
            `
                DELETE FROM active_trips
                WHERE vehicle_number = ?
            `,
            [vehicleNumber]
        );

        tripsToDeactivate.forEach((trip) => {
            stopTripSignalSimulation(trip.id);
        });

        await broadcastAllTrips();

        return res.json({
            message: 'Ambulance removed from active list successfully',
            driver_id: driverId,
            vehicle_number: vehicleNumber,
            removed_count: deletionResult.changes,
            removed_trips: tripsToDeactivate
        });
    } catch (error) {
        return res.status(500).json({
            message: 'Failed to deactivate active ambulance',
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
            trips: trips.map(attachRuntimeDataToTrip)
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

        await ensureTripSignalSimulation(trip);
        const visualization = getTripVisualizationPayload(tripId);

        return res.json({
            trip: {
                ...trip,
                ...(visualization || {})
            }
        });
    } catch (error) {
        return res.status(500).json({
            message: 'Failed to fetch active trip details',
            error: error.message
        });
    }
});

module.exports = router;
