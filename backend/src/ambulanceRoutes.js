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

module.exports = router;
