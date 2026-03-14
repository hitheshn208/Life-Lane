const DEFAULT_TIMEOUT_MS = 12000;
const TOMTOM_API_KEY = process.env.TOMTOM_API_KEY || '';

async function fetchJsonWithTimeout(url, timeoutMs = DEFAULT_TIMEOUT_MS) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    try {
        const response = await fetch(url, {
            signal: controller.signal,
            headers: {
                Accept: 'application/json'
            }
        });
        if (!response.ok) {
            throw new Error(`TomTom request failed with status ${response.status}`);
        }

        return response.json();
    } finally {
        clearTimeout(timer);
    }
}

function classifyTrafficLevel(trafficRatio) {
    if (!Number.isFinite(trafficRatio)) {
        return 'UNKNOWN';
    }

    if (trafficRatio > 0.8) {
        return 'LOW';
    }

    if (trafficRatio >= 0.5) {
        return 'MEDIUM';
    }

    return 'HEAVY';
}

async function getTrafficDensityForSignal(signal) {
    if (!TOMTOM_API_KEY) {
        return {
            traffic_level: 'UNKNOWN',
            traffic_ratio: null,
            current_speed: null,
            free_flow_speed: null
        };
    }

    try {
        const url = `https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/10/json?point=${signal.lat},${signal.lon}&key=${encodeURIComponent(TOMTOM_API_KEY)}`;
        const payload = await fetchJsonWithTimeout(url);
        const flow = payload.flowSegmentData || {};
        const currentSpeed = Number(flow.currentSpeed);
        const freeFlowSpeed = Number(flow.freeFlowSpeed);
        const trafficRatio = Number.isFinite(currentSpeed) && Number.isFinite(freeFlowSpeed) && freeFlowSpeed > 0
            ? currentSpeed / freeFlowSpeed
            : null;

        return {
            traffic_level: classifyTrafficLevel(trafficRatio),
            traffic_ratio: Number.isFinite(trafficRatio) ? Number(trafficRatio.toFixed(2)) : null,
            current_speed: Number.isFinite(currentSpeed) ? currentSpeed : null,
            free_flow_speed: Number.isFinite(freeFlowSpeed) ? freeFlowSpeed : null
        };
    } catch (_error) {
        return {
            traffic_level: 'UNKNOWN',
            traffic_ratio: null,
            current_speed: null,
            free_flow_speed: null
        };
    }
}

async function enrichSignalsWithTraffic(signals) {
    return Promise.all(
        (signals || []).map(async (signal) => ({
            ...signal,
            ...(await getTrafficDensityForSignal(signal))
        }))
    );
}

module.exports = {
    classifyTrafficLevel,
    getTrafficDensityForSignal,
    enrichSignalsWithTraffic
};
