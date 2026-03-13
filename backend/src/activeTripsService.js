const { allQuery } = require('./database');

async function getActiveTripsForDriver(driverId) {
    return allQuery(
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
            WHERE at.driver_id = ?
            ORDER BY at.start_time DESC
        `,
        [driverId]
    );
}

async function getAllActiveTrips() {
    return allQuery(
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
            ORDER BY at.start_time DESC
        `,
        []
    );
}

module.exports = {
    getActiveTripsForDriver,
    getAllActiveTrips
};
