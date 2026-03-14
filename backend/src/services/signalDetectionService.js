const OVERPASS_URL = 'https://overpass-api.de/api/interpreter';
const SIGNAL_ROUTE_PROXIMITY_METERS = 12;
const SIGNAL_CLUSTER_RADIUS_METERS = 20;
const ROUTE_BBOX_PADDING_DEGREES = 0.0008;
const DEFAULT_TIMEOUT_MS = 20000;

const {
    calculateBoundingBox,
    getDistanceAlongRouteForPoint,
    getDistanceToRouteMeters,
    haversineDistanceMeters
} = require('./routeService');

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

function buildOverpassQuery(bbox) {
    return `
        [out:json][timeout:25];
        (
          node["highway"="traffic_signals"](${bbox.minLat},${bbox.minLon},${bbox.maxLat},${bbox.maxLon});
        );
        out body;
    `;
}

async function fetchSignalsAlongRoute(routePoints) {
    if (!Array.isArray(routePoints) || routePoints.length < 2) {
        return [];
    }

    const bbox = calculateBoundingBox(routePoints, ROUTE_BBOX_PADDING_DEGREES);

    if (!bbox) {
        return [];
    }

    const payload = await fetchJsonWithTimeout(OVERPASS_URL, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded'
        },
        body: new URLSearchParams({ data: buildOverpassQuery(bbox) }).toString()
    });

    const signals = (payload.elements || [])
        .map((element) => ({
            id: element.id,
            lat: element.lat,
            lon: element.lon
        }))
        .map((signal) => ({
            ...signal,
            distance_to_route_meters: getDistanceToRouteMeters(signal, routePoints),
            distance_from_route_start: getDistanceAlongRouteForPoint(signal, routePoints)
        }))
        .filter((signal) =>
            Number.isFinite(signal.distance_to_route_meters) &&
            signal.distance_to_route_meters <= SIGNAL_ROUTE_PROXIMITY_METERS &&
            Number.isFinite(signal.distance_from_route_start)
        )
        .sort((left, right) => left.distance_from_route_start - right.distance_from_route_start);

    const clusters = [];
    let nextClusterId = 1;

    const signalsWithCluster = signals.map((signal) => {
        let cluster = clusters.find((candidateCluster) =>
            candidateCluster.members.some((member) =>
                haversineDistanceMeters(member, signal) <= SIGNAL_CLUSTER_RADIUS_METERS
            )
        );

        if (!cluster) {
            cluster = {
                id: nextClusterId,
                members: []
            };

            clusters.push(cluster);
            nextClusterId += 1;
        }

        cluster.members.push(signal);

        return {
            ...signal,
            cluster_id: cluster.id
        };
    });

    const rankByClusterAndId = new Map();

    clusters.forEach((cluster) => {
        const sorted = cluster.members
            .slice()
            .sort((left, right) => {
                if (left.distance_to_route_meters !== right.distance_to_route_meters) {
                    return left.distance_to_route_meters - right.distance_to_route_meters;
                }

                return left.distance_from_route_start - right.distance_from_route_start;
            });

        sorted.forEach((member, index) => {
            rankByClusterAndId.set(`${cluster.id}:${member.id}`, index);
        });
    });

    return signalsWithCluster.map((signal) => ({
        ...signal,
        cluster_rank: rankByClusterAndId.get(`${signal.cluster_id}:${signal.id}`) || 0,
        is_primary_in_cluster: (rankByClusterAndId.get(`${signal.cluster_id}:${signal.id}`) || 0) === 0
    }));
}

module.exports = {
    fetchSignalsAlongRoute,
    SIGNAL_ROUTE_PROXIMITY_METERS,
    SIGNAL_CLUSTER_RADIUS_METERS
};
