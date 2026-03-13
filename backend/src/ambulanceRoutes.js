const express = require('express');
const router = express.Router();

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
        source: { lat: 12.9150, lng: 77.6250 },
        destination: { lat: 12.8700, lng: 77.6520 },
        driver: 'Naveen M',
        priority: 'Low'
    }
};

router.get('/:plate/coordinates', (req, res) => {
    const plate = req.params.plate;
    const data = ambulanceRouteData[plate];

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

router.get('/route/geojson', async (req, res) => {
    const { sourceLat, sourceLng, destinationLat, destinationLng } = req.query;

    if (!sourceLat || !sourceLng || !destinationLat || !destinationLng) {
        return res.status(400).json({
            message: 'Missing coordinates. Required query params: sourceLat, sourceLng, destinationLat, destinationLng'
        });
    }

    const osrmUrl = `https://router.project-osrm.org/route/v1/driving/${sourceLng},${sourceLat};${destinationLng},${destinationLat}?overview=full&geometries=geojson`;

    if (typeof fetch !== 'function') {
        return res.status(501).json({
            message: 'Global fetch is not available on this Node.js runtime',
            osrmUrl
        });
    }

    try {
        const response = await fetch(osrmUrl);

        if (!response.ok) {
            return res.status(response.status).json({
                message: 'Failed to fetch route from OSRM',
                osrmUrl
            });
        }

        const payload = await response.json();

        if (!payload.routes || !payload.routes.length) {
            return res.status(404).json({
                message: 'No route found for provided coordinates',
                osrmUrl,
                payload
            });
        }

        return res.json({
            source: { lat: Number(sourceLat), lng: Number(sourceLng) },
            destination: { lat: Number(destinationLat), lng: Number(destinationLng) },
            distanceMeters: payload.routes[0].distance,
            durationSeconds: payload.routes[0].duration,
            geojson: payload.routes[0].geometry
        });
    } catch (error) {
        return res.status(500).json({
            message: 'Unexpected error while fetching route geometry',
            error: error.message,
            osrmUrl
        });
    }
});

module.exports = router;
