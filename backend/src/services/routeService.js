const OSRM_BASE_URL = 'https://router.project-osrm.org/route/v1/driving';
const DEFAULT_TIMEOUT_MS = 15000;
const EARTH_RADIUS_METERS = 6371000;

async function fetchJsonWithTimeout(url, options = {}, timeoutMs = DEFAULT_TIMEOUT_MS) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    try {
        const response = await fetch(url, {
            ...options,
            signal: controller.signal,
            headers: {
                Accept: 'application/json',
                ...(options.headers || {})
            }
        });

        if (!response.ok) {
            throw new Error(`Request failed with status ${response.status}`);
        }

        return response.json();
    } finally {
        clearTimeout(timer);
    }
}

function toRadians(value) {
    return (value * Math.PI) / 180;
}

function haversineDistanceMeters(pointA, pointB) {
    const lat1 = toRadians(pointA.lat);
    const lat2 = toRadians(pointB.lat);
    const dLat = toRadians(pointB.lat - pointA.lat);
    const dLon = toRadians(pointB.lon - pointA.lon);

    const a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);

    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return EARTH_RADIUS_METERS * c;
}

function toLocalXY(point, referenceLat) {
    const metersPerDegreeLat = 111320;
    const metersPerDegreeLon = Math.cos(toRadians(referenceLat)) * 111320;

    return {
        x: point.lon * metersPerDegreeLon,
        y: point.lat * metersPerDegreeLat
    };
}

function projectPointToSegment(point, segmentStart, segmentEnd) {
    const referenceLat = (point.lat + segmentStart.lat + segmentEnd.lat) / 3;
    const pointXY = toLocalXY(point, referenceLat);
    const startXY = toLocalXY(segmentStart, referenceLat);
    const endXY = toLocalXY(segmentEnd, referenceLat);

    const deltaX = endXY.x - startXY.x;
    const deltaY = endXY.y - startXY.y;
    const segmentLengthSquared = deltaX * deltaX + deltaY * deltaY;

    if (segmentLengthSquared === 0) {
        return {
            distanceMeters: Math.hypot(pointXY.x - startXY.x, pointXY.y - startXY.y),
            projectedPoint: segmentStart,
            ratio: 0
        };
    }

    const ratio = Math.max(
        0,
        Math.min(
            1,
            ((pointXY.x - startXY.x) * deltaX + (pointXY.y - startXY.y) * deltaY) / segmentLengthSquared
        )
    );

    const projectedX = startXY.x + ratio * deltaX;
    const projectedY = startXY.y + ratio * deltaY;

    const projectedPoint = {
        lat: segmentStart.lat + (segmentEnd.lat - segmentStart.lat) * ratio,
        lon: segmentStart.lon + (segmentEnd.lon - segmentStart.lon) * ratio
    };

    return {
        distanceMeters: Math.hypot(pointXY.x - projectedX, pointXY.y - projectedY),
        projectedPoint,
        ratio
    };
}

function calculateBoundingBox(routePoints, paddingDegrees = 0.0025) {
    if (!Array.isArray(routePoints) || routePoints.length === 0) {
        return null;
    }

    let minLat = Infinity;
    let maxLat = -Infinity;
    let minLon = Infinity;
    let maxLon = -Infinity;

    routePoints.forEach((point) => {
        minLat = Math.min(minLat, point.lat);
        maxLat = Math.max(maxLat, point.lat);
        minLon = Math.min(minLon, point.lon);
        maxLon = Math.max(maxLon, point.lon);
    });

    return {
        minLat: minLat - paddingDegrees,
        minLon: minLon - paddingDegrees,
        maxLat: maxLat + paddingDegrees,
        maxLon: maxLon + paddingDegrees
    };
}

function getRouteSegmentLengths(routePoints) {
    const segmentLengths = [];

    for (let index = 0; index < routePoints.length - 1; index += 1) {
        segmentLengths.push(haversineDistanceMeters(routePoints[index], routePoints[index + 1]));
    }

    return segmentLengths;
}

function getDistanceAlongRouteForPoint(point, routePoints) {
    if (!point || !Array.isArray(routePoints) || routePoints.length < 2) {
        return null;
    }

    const segmentLengths = getRouteSegmentLengths(routePoints);
    let cumulativeBeforeSegment = 0;
    let bestMatch = {
        distanceMeters: Number.POSITIVE_INFINITY,
        distanceAlongRouteMeters: 0
    };

    for (let index = 0; index < routePoints.length - 1; index += 1) {
        const segmentStart = routePoints[index];
        const segmentEnd = routePoints[index + 1];
        const projection = projectPointToSegment(point, segmentStart, segmentEnd);
        const segmentDistance = segmentLengths[index] || 0;
        const distanceAlongRouteMeters = cumulativeBeforeSegment + projection.ratio * segmentDistance;

        if (projection.distanceMeters < bestMatch.distanceMeters) {
            bestMatch = {
                distanceMeters: projection.distanceMeters,
                distanceAlongRouteMeters
            };
        }

        cumulativeBeforeSegment += segmentDistance;
    }

    return bestMatch.distanceAlongRouteMeters;
}

function getDistanceToRouteMeters(point, routePoints) {
    if (!point || !Array.isArray(routePoints) || routePoints.length < 2) {
        return null;
    }

    let minimumDistance = Number.POSITIVE_INFINITY;

    for (let index = 0; index < routePoints.length - 1; index += 1) {
        const projection = projectPointToSegment(point, routePoints[index], routePoints[index + 1]);
        minimumDistance = Math.min(minimumDistance, projection.distanceMeters);
    }

    return minimumDistance;
}

async function fetchRouteGeometry({ startLat, startLon, endLat, endLon }) {
    const url = `${OSRM_BASE_URL}/${startLon},${startLat};${endLon},${endLat}?overview=full&geometries=geojson`;
    const payload = await fetchJsonWithTimeout(url);
    const route = payload.routes?.[0];

    if (!route?.geometry?.coordinates) {
        throw new Error('OSRM route geometry missing');
    }

    const routePoints = route.geometry.coordinates.map(([lon, lat]) => ({ lat, lon }));

    return {
        distance_meters: route.distance,
        duration_seconds: route.duration,
        coordinates: routePoints,
        bbox: calculateBoundingBox(routePoints)
    };
}

module.exports = {
    fetchRouteGeometry,
    haversineDistanceMeters,
    calculateBoundingBox,
    getDistanceAlongRouteForPoint,
    getDistanceToRouteMeters
};
