const { fetchRouteGeometry, getDistanceAlongRouteForPoint } = require('./routeService');
const { fetchSignalsAlongRoute } = require('./signalDetectionService');
const { enrichSignalsWithTraffic } = require('./trafficDensityService');

const SIGNAL_CYCLE_BY_TRAFFIC = {
    LOW: [
        { color: 'RED', durationMs: 10000 },
        { color: 'GREEN', durationMs: 10000 }
    ],
    MEDIUM: [
        { color: 'RED', durationMs: 9000 },
        { color: 'GREEN', durationMs: 11000 }
    ],
    HEAVY: [
        { color: 'RED', durationMs: 8000 },
        { color: 'GREEN', durationMs: 12000 }
    ],
    UNKNOWN: [
        { color: 'RED', durationMs: 10000 },
        { color: 'GREEN', durationMs: 10000 }
    ]
};
const PRIORITY_DISTANCE_METERS = 150;
const PRIORITY_ETA_SECONDS = 30;
const PRIORITY_RELEASE_DISTANCE_METERS = 220;
const PRIORITY_RELEASE_ETA_SECONDS = 45;
const SIGNAL_PASSED_TOLERANCE_METERS = 20;
const SIGNAL_NODE_REACHED_DISTANCE_METERS = 20;
const DEFAULT_AMBULANCE_SPEED_MPS = 12;

const tripSignalStates = new Map();
let signalBroadcastHandler = null;
let mobileSignalBroadcastHandler = null;

function setSignalBroadcastHandler(handler) {
    signalBroadcastHandler = handler;
}

function setMobileSignalBroadcastHandler(handler) {
    mobileSignalBroadcastHandler = handler;
}

function roundNumber(value, decimals = 2) {
    if (!Number.isFinite(value)) {
        return null;
    }

    return Number(value.toFixed(decimals));
}

function getCycleForSignal(signal) {
    const trafficLevel = String(signal?.traffic_level || 'UNKNOWN').toUpperCase();
    return SIGNAL_CYCLE_BY_TRAFFIC[trafficLevel] || SIGNAL_CYCLE_BY_TRAFFIC.UNKNOWN;
}

function getBaseSignalColor(signal) {
    const cycle = getCycleForSignal(signal);
    return cycle[signal.cycle_index]?.color || cycle[0]?.color || 'RED';
}

function cloneRoute(route) {
    if (!route) {
        return null;
    }

    return {
        distance_meters: roundNumber(route.distance_meters, 1),
        duration_seconds: roundNumber(route.duration_seconds, 1),
        coordinates: route.coordinates,
        bbox: route.bbox
    };
}

function serializeSignal(signal) {
    return {
        id: signal.id,
        lat: signal.lat,
        lon: signal.lon,
        cluster_id: signal.cluster_id,
        cluster_rank: signal.cluster_rank,
        is_primary_in_cluster: signal.is_primary_in_cluster,
        distance_from_route_start: roundNumber(signal.distance_from_route_start, 1),
        distance_to_route_meters: roundNumber(signal.distance_to_route_meters, 1),
        distance_to_signal_meters: roundNumber(signal.distance_to_signal_meters, 1),
        eta_seconds: signal.eta_seconds,
        traffic_level: signal.traffic_level,
        traffic_ratio: signal.traffic_ratio,
        current_speed: signal.current_speed,
        free_flow_speed: signal.free_flow_speed,
        current_color: signal.current_color,
        priority_override: signal.priority_override,
        cluster_forced_red: signal.cluster_forced_red,
        next_change_time: signal.next_change_time,
        passed: signal.passed
    };
}

function getTripEtaToHospitalMinutes(tripState) {
    const computedEtaSeconds = Number(tripState?.computed_eta_to_hospital_seconds);

    if (Number.isFinite(computedEtaSeconds) && computedEtaSeconds >= 0) {
        return Math.max(0, Math.round(computedEtaSeconds / 60));
    }

    const configuredEtaSeconds = Number(tripState?.eta_to_hospital_seconds);

    if (Number.isFinite(configuredEtaSeconds) && configuredEtaSeconds >= 0) {
        return Math.max(0, Math.round(configuredEtaSeconds / 60));
    }

    return null;
}

function buildTripSignalPayload(tripState) {
    if (!tripState) {
        return null;
    }

    return {
        tripId: tripState.tripId,
        trip_id: tripState.tripId,
        vehicle_number: tripState.vehicle_number,
        ambulanceLat: tripState.ambulanceLocation?.lat ?? null,
        ambulanceLon: tripState.ambulanceLocation?.lon ?? null,
        ambulance_lat: tripState.ambulanceLocation?.lat ?? null,
        ambulance_lon: tripState.ambulanceLocation?.lon ?? null,
        eta_to_hospital: getTripEtaToHospitalMinutes(tripState),
        signals: tripState.signals.map(serializeSignal),
        route: cloneRoute(tripState.route),
        updated_at: new Date().toISOString()
    };
}

function buildMobileSignalPayload(tripState, reason = 'signal_update') {
    if (!tripState) {
        return null;
    }

    return {
        type: 'signal_state_update',
        reason,
        trip_id: tripState.tripId,
        vehicle_number: tripState.vehicle_number,
        updated_at: new Date().toISOString(),
        signals: tripState.signals
            .filter((s) => !s.passed)
            .map((s) => ({
                id: s.id,
                lat: s.lat,
                lon: s.lon,
                color: s.current_color,
                traffic_level: s.traffic_level,
                traffic_ratio: roundNumber(s.traffic_ratio, 3),
                current_speed_kmph: s.current_speed,
                free_flow_speed_kmph: s.free_flow_speed,
                eta_seconds: s.eta_seconds,
                distance_to_signal_meters: roundNumber(s.distance_to_signal_meters, 1),
                priority_override: s.priority_override
            }))
    };
}

function notifyTripSignalUpdate(tripState, reason = 'signal_update') {
    if (!tripState) {
        return;
    }

    if (typeof signalBroadcastHandler === 'function') {
        signalBroadcastHandler({
            type: 'trip_signals_update',
            reason,
            ...buildTripSignalPayload(tripState)
        });
    }

    if (typeof mobileSignalBroadcastHandler === 'function') {
        mobileSignalBroadcastHandler(buildMobileSignalPayload(tripState, reason));
    }
}

function getMobileSignalPayload({ tripId, vehicle_number, reason = 'ws_connected' }) {
    const tripState = getTripStateById(tripId) || getTripStateByVehicleNumber(vehicle_number);

    if (!tripState) {
        return null;
    }

    return buildMobileSignalPayload(tripState, reason);
}

function scheduleSignalTransition(tripState, signal) {
    clearTimeout(signal.timerHandle);

    const cycle = getCycleForSignal(signal);
    const cycleEntry = cycle[signal.cycle_index] || cycle[0] || { color: 'RED', durationMs: 10000 };

    signal.next_change_time = new Date(Date.now() + cycleEntry.durationMs).toISOString();

    signal.timerHandle = setTimeout(() => {
        signal.cycle_index = (signal.cycle_index + 1) % cycle.length;
        signal.current_color = getBaseSignalColor(signal);
        scheduleSignalTransition(tripState, signal);
        recomputeSignalStateForTrip(tripState);
        notifyTripSignalUpdate(tripState, 'signal_cycle');
    }, cycleEntry.durationMs);
}

function computeEstimatedSpeedMps(tripState, currentProgressMeters) {
    const remainingRouteMeters = Math.max((tripState.route?.distance_meters || 0) - currentProgressMeters, 0);
    const etaToHospitalSeconds = Number(tripState.eta_to_hospital_seconds);

    if (remainingRouteMeters > 0 && Number.isFinite(etaToHospitalSeconds) && etaToHospitalSeconds > 0) {
        return Math.max(remainingRouteMeters / etaToHospitalSeconds, 1);
    }

    return DEFAULT_AMBULANCE_SPEED_MPS;
}

function recomputeSignalStateForTrip(tripState) {
    if (!tripState?.route?.coordinates?.length) {
        return;
    }

    const currentProgressMeters = tripState.ambulanceLocation
        ? getDistanceAlongRouteForPoint(tripState.ambulanceLocation, tripState.route.coordinates)
        : 0;
    const resolvedProgressMeters = Number.isFinite(currentProgressMeters) ? currentProgressMeters : 0;
    const estimatedSpeedMps = computeEstimatedSpeedMps(tripState, resolvedProgressMeters);
    const remainingRouteMeters = Math.max((tripState.route?.distance_meters || 0) - resolvedProgressMeters, 0);

    tripState.computed_eta_to_hospital_seconds = Number.isFinite(estimatedSpeedMps) && estimatedSpeedMps > 0
        ? Math.round(remainingRouteMeters / estimatedSpeedMps)
        : null;

    tripState.signals.forEach((signal) => {
        const distanceToSignalMeters = signal.distance_from_route_start - resolvedProgressMeters;
        const aheadDistance = Math.max(distanceToSignalMeters, 0);
        const etaSeconds = Number.isFinite(estimatedSpeedMps) && estimatedSpeedMps > 0
            ? Math.round(aheadDistance / estimatedSpeedMps)
            : null;
        const hasPassed = distanceToSignalMeters < -SIGNAL_PASSED_TOLERANCE_METERS;
        const shouldEngagePriority = !hasPassed && (
            aheadDistance <= PRIORITY_DISTANCE_METERS ||
            (Number.isFinite(etaSeconds) && etaSeconds <= PRIORITY_ETA_SECONDS)
        );
        const shouldKeepPriority = !hasPassed && (
            aheadDistance <= PRIORITY_RELEASE_DISTANCE_METERS ||
            (Number.isFinite(etaSeconds) && etaSeconds <= PRIORITY_RELEASE_ETA_SECONDS)
        );

        const nextPriorityLock = hasPassed
            ? false
            : (signal.priority_lock ? shouldKeepPriority : shouldEngagePriority);

        signal.distance_to_signal_meters = roundNumber(aheadDistance, 1) || 0;
        signal.eta_seconds = hasPassed ? 0 : etaSeconds;
        signal.passed = hasPassed;
        signal.priority_lock = nextPriorityLock;
        signal.priority_override_candidate = nextPriorityLock;
        signal.priority_override = false;
        signal.cluster_forced_red = false;
        signal.current_color = getBaseSignalColor(signal);
    });

    const signalsByCluster = new Map();

    tripState.signals.forEach((signal) => {
        const clusterKey = signal.cluster_id || signal.id;

        if (!signalsByCluster.has(clusterKey)) {
            signalsByCluster.set(clusterKey, []);
        }

        signalsByCluster.get(clusterKey).push(signal);
    });

    signalsByCluster.forEach((clusterSignals) => {
        const eligibleSignals = clusterSignals.filter((signal) => !signal.passed && signal.priority_override_candidate);

        if (eligibleSignals.length === 0) {
            return;
        }

        const selectedSignal = eligibleSignals
            .slice()
            .sort((left, right) => {
                const leftRank = Number.isFinite(left.cluster_rank) ? left.cluster_rank : Number.MAX_SAFE_INTEGER;
                const rightRank = Number.isFinite(right.cluster_rank) ? right.cluster_rank : Number.MAX_SAFE_INTEGER;

                if (leftRank !== rightRank) {
                    return leftRank - rightRank;
                }

                if ((left.distance_to_route_meters || 0) !== (right.distance_to_route_meters || 0)) {
                    return (left.distance_to_route_meters || 0) - (right.distance_to_route_meters || 0);
                }

                return (left.distance_to_signal_meters || 0) - (right.distance_to_signal_meters || 0);
            })[0];

        clusterSignals.forEach((signal) => {
            if (signal === selectedSignal) {
                signal.priority_override = true;
                signal.current_color = 'GREEN';
            } else if (!signal.passed) {
                signal.cluster_forced_red = true;
                signal.current_color = 'RED';
            }
        });
    });
}

async function refreshTripTraffic(tripState) {
    if (!tripState) {
        return;
    }

    const refreshedSignals = await enrichSignalsWithTraffic(tripState.signals);
    tripState.signals = refreshedSignals.map((signal, index) => ({
        ...tripState.signals[index],
        traffic_level: signal.traffic_level,
        traffic_ratio: signal.traffic_ratio,
        current_speed: signal.current_speed,
        free_flow_speed: signal.free_flow_speed
    }));

    recomputeSignalStateForTrip(tripState);
    notifyTripSignalUpdate(tripState, 'traffic_refresh');
}

async function refreshTripTrafficForSignals(tripState, signalIds, reason = 'signal_node_reached') {
    if (!tripState || !Array.isArray(signalIds) || signalIds.length === 0) {
        return;
    }

    const uniqueSignalIds = new Set(signalIds);
    const targetSignals = tripState.signals.filter((signal) => uniqueSignalIds.has(signal.id));

    if (targetSignals.length === 0) {
        return;
    }

    const refreshedSignals = await enrichSignalsWithTraffic(targetSignals);
    const refreshedById = new Map(refreshedSignals.map((signal) => [signal.id, signal]));

    tripState.signals = tripState.signals.map((signal) => {
        const refreshedSignal = refreshedById.get(signal.id);

        if (!refreshedSignal) {
            return signal;
        }

        return {
            ...signal,
            traffic_level: refreshedSignal.traffic_level,
            traffic_ratio: refreshedSignal.traffic_ratio,
            current_speed: refreshedSignal.current_speed,
            free_flow_speed: refreshedSignal.free_flow_speed
        };
    });

    recomputeSignalStateForTrip(tripState);
    notifyTripSignalUpdate(tripState, reason);
}

function triggerTrafficRefreshWhenSignalReached(tripState) {
    if (!tripState) {
        return;
    }

    const reachedSignalIds = [];

    tripState.signals.forEach((signal) => {
        const hasReachedSignalNode = signal.passed ||
            (Number.isFinite(signal.distance_to_signal_meters) &&
                signal.distance_to_signal_meters <= SIGNAL_NODE_REACHED_DISTANCE_METERS);

        if (!hasReachedSignalNode || signal.reach_traffic_refresh_done) {
            return;
        }

        if (tripState.pendingReachRefreshSignalIds.has(signal.id)) {
            return;
        }

        signal.reach_traffic_refresh_done = true;
        tripState.pendingReachRefreshSignalIds.add(signal.id);
        reachedSignalIds.push(signal.id);
    });

    if (reachedSignalIds.length === 0) {
        return;
    }

    refreshTripTrafficForSignals(tripState, reachedSignalIds, 'signal_node_reached')
        .catch(() => {})
        .finally(() => {
            reachedSignalIds.forEach((signalId) => {
                tripState.pendingReachRefreshSignalIds.delete(signalId);
            });
        });
}

function initializeSignalTimers(tripState) {
    tripState.signals.forEach((signal) => {
        scheduleSignalTransition(tripState, signal);
    });
}

async function buildTripState(trip) {
    const route = await fetchRouteGeometry({
        startLat: trip.patient_lat,
        startLon: trip.patient_lon,
        endLat: trip.hospital_lat,
        endLon: trip.hospital_lon
    });

    const detectedSignals = await fetchSignalsAlongRoute(route.coordinates);
    const signalsWithTraffic = await enrichSignalsWithTraffic(detectedSignals);

    const tripState = {
        tripId: Number(trip.id),
        vehicle_number: trip.vehicle_number,
        route,
        signals: signalsWithTraffic.map((signal, index) => ({
            id: signal.id,
            lat: signal.lat,
            lon: signal.lon,
            cluster_id: signal.cluster_id || null,
            cluster_rank: signal.cluster_rank || 0,
            is_primary_in_cluster: Boolean(signal.is_primary_in_cluster),
            distance_from_route_start: signal.distance_from_route_start,
            distance_to_route_meters: signal.distance_to_route_meters,
            distance_to_signal_meters: signal.distance_from_route_start,
            eta_seconds: null,
            traffic_level: signal.traffic_level,
            traffic_ratio: signal.traffic_ratio,
            current_speed: signal.current_speed,
            free_flow_speed: signal.free_flow_speed,
            cycle_index: index % 2,
            current_color: index % 2 === 0 ? 'RED' : 'GREEN',
            priority_lock: false,
            priority_override_candidate: false,
            priority_override: false,
            cluster_forced_red: false,
            reach_traffic_refresh_done: false,
            next_change_time: null,
            timerHandle: null,
            passed: false
        })),
        ambulanceLocation: null,
        eta_to_hospital_seconds: Number(trip.eta_to_hospital) * 60 || null,
        computed_eta_to_hospital_seconds: null,
        pendingReachRefreshSignalIds: new Set()
    };

    recomputeSignalStateForTrip(tripState);
    initializeSignalTimers(tripState);

    return tripState;
}

async function ensureTripSignalSimulation(trip) {
    const tripId = Number(trip?.id);

    if (!Number.isInteger(tripId) || tripId <= 0) {
        return null;
    }

    if (tripSignalStates.has(tripId)) {
        return tripSignalStates.get(tripId);
    }

    const tripState = await buildTripState(trip);
    tripSignalStates.set(tripId, tripState);
    notifyTripSignalUpdate(tripState, 'trip_initialized');
    return tripState;
}

async function bootstrapTripSignalSimulations(trips) {
    for (const trip of trips || []) {
        try {
            await ensureTripSignalSimulation(trip);
        } catch (_error) {
        }
    }
}

function getTripStateById(tripId) {
    return tripSignalStates.get(Number(tripId)) || null;
}

function getTripStateByVehicleNumber(vehicleNumber) {
    const normalizedVehicleNumber = String(vehicleNumber || '').trim().toUpperCase();

    for (const tripState of tripSignalStates.values()) {
        if (String(tripState.vehicle_number).trim().toUpperCase() === normalizedVehicleNumber) {
            return tripState;
        }
    }

    return null;
}

function stopTripSignalSimulation(tripId) {
    const normalizedTripId = Number(tripId);
    const tripState = tripSignalStates.get(normalizedTripId);

    if (!tripState) {
        return false;
    }

    (tripState.signals || []).forEach((signal) => {
        clearTimeout(signal.timerHandle);
        signal.timerHandle = null;
        signal.next_change_time = null;
    });

    tripSignalStates.delete(normalizedTripId);
    return true;
}

function updateAmbulanceLocation({ tripId, vehicle_number, lat, lon, eta_to_hospital }) {
    const tripState = getTripStateById(tripId) || getTripStateByVehicleNumber(vehicle_number);

    if (!tripState) {
        return null;
    }

    if (Number.isFinite(Number(lat)) && Number.isFinite(Number(lon))) {
        tripState.ambulanceLocation = {
            lat: Number(lat),
            lon: Number(lon)
        };
    }

    if (eta_to_hospital !== undefined && eta_to_hospital !== null && Number.isFinite(Number(eta_to_hospital))) {
        tripState.eta_to_hospital_seconds = Number(eta_to_hospital) * 60;
    }

    recomputeSignalStateForTrip(tripState);
    triggerTrafficRefreshWhenSignalReached(tripState);
    notifyTripSignalUpdate(tripState, 'ambulance_location');
    return buildTripSignalPayload(tripState);
}

function attachRuntimeDataToTrip(trip) {
    const tripState = getTripStateById(trip?.id);

    if (!tripState) {
        return trip;
    }

    return {
        ...trip,
        eta_to_hospital: getTripEtaToHospitalMinutes(tripState) ?? trip.eta_to_hospital,
        ambulanceLat: tripState.ambulanceLocation?.lat ?? null,
        ambulanceLon: tripState.ambulanceLocation?.lon ?? null,
        ambulance_lat: tripState.ambulanceLocation?.lat ?? null,
        ambulance_lon: tripState.ambulanceLocation?.lon ?? null,
        route: cloneRoute(tripState.route),
        signals: tripState.signals.map(serializeSignal)
    };
}

function attachRuntimeDataToTrips(trips) {
    return (trips || []).map(attachRuntimeDataToTrip);
}

function getTripVisualizationPayload(tripId) {
    const tripState = getTripStateById(tripId);

    if (!tripState) {
        return null;
    }

    return {
        eta_to_hospital: getTripEtaToHospitalMinutes(tripState),
        ambulanceLat: tripState.ambulanceLocation?.lat ?? null,
        ambulanceLon: tripState.ambulanceLocation?.lon ?? null,
        ambulance_lat: tripState.ambulanceLocation?.lat ?? null,
        ambulance_lon: tripState.ambulanceLocation?.lon ?? null,
        route: cloneRoute(tripState.route),
        signals: tripState.signals.map(serializeSignal)
    };
}

module.exports = {
    ensureTripSignalSimulation,
    bootstrapTripSignalSimulations,
    setSignalBroadcastHandler,
    setMobileSignalBroadcastHandler,
    stopTripSignalSimulation,
    updateAmbulanceLocation,
    attachRuntimeDataToTrip,
    attachRuntimeDataToTrips,
    getTripVisualizationPayload,
    getMobileSignalPayload
};
