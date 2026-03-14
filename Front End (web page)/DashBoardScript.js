const queryParams = new URLSearchParams(window.location.search)
const queryApiBase = queryParams.get("api")
const storedApiBase = window.localStorage.getItem("API_BASE_URL")

const API_BASE_URL = queryApiBase
	|| storedApiBase
	|| (window.location.protocol.startsWith("http")
		? `${window.location.protocol}//${window.location.hostname}:3000`
		: "http://localhost:3000")

if (queryApiBase) {
	window.localStorage.setItem("API_BASE_URL", queryApiBase)
}

const apiUrl = new URL(API_BASE_URL)
const wsProtocol = apiUrl.protocol === "https:" ? "wss:" : "ws:"
const WS_BASE_URL = `${wsProtocol}//${apiUrl.host}`

const container = document.getElementById("ambulanceContainer")
const emptyState = document.getElementById("emptyState")
const pills = document.querySelectorAll(".filter-pill")
const connectionStatus = document.getElementById("connectionStatus")

let activeTrips = []
let activeFilters = []
let socket = null
let reconnectTimer = null
const DASHBOARD_SIGNAL_ROUTE_MAX_METERS = 6

function setConnectionStatus(text, state) {
	if (!connectionStatus) {
		return
	}

	connectionStatus.textContent = text
	connectionStatus.className = `connection-status ${state}`
}

function normalizeSeverity(severity) {
	return (severity || "STABLE").toLowerCase()
}

function formatEta(minutes) {
	if (minutes === null || minutes === undefined || Number.isNaN(Number(minutes))) {
		return "—"
	}

	return `${Math.max(0, Math.round(Number(minutes)))} min`
}

function formatSignalEta(seconds) {
	if (seconds === null || seconds === undefined || Number.isNaN(Number(seconds))) {
		return "ETA unavailable"
	}

	if (Number(seconds) < 60) {
		return `${Math.max(0, Math.round(Number(seconds)))} sec`
	}

	return `${Math.round(Number(seconds) / 60)} min`
}

function formatCoordinate(lat, lon) {
	return `${Number(lat).toFixed(4)}, ${Number(lon).toFixed(4)}`
}

function formatStartTime(startTime) {
	if (!startTime) {
		return "—"
	}

	const parsed = new Date(String(startTime).replace(" ", "T"))
	return Number.isNaN(parsed.getTime()) ? startTime : parsed.toLocaleString()
}

function updateEmptyState(isEmpty, message) {
	if (!emptyState) {
		return
	}

	emptyState.hidden = !isEmpty

	if (message) {
		emptyState.textContent = message
	}
}

function getSignalsAhead(trip) {
	return (Array.isArray(trip.signals) ? trip.signals : [])
		.filter((signal) => {
			if (signal.passed) {
				return false
			}

			const routeDistance = Number(signal.distance_to_route_meters)
			if (!Number.isFinite(routeDistance)) {
				return false
			}

			return routeDistance <= DASHBOARD_SIGNAL_ROUTE_MAX_METERS
		})
		.sort((left, right) => {
			const leftEta = Number.isFinite(Number(left.eta_seconds)) ? Number(left.eta_seconds) : Number.POSITIVE_INFINITY
			const rightEta = Number.isFinite(Number(right.eta_seconds)) ? Number(right.eta_seconds) : Number.POSITIVE_INFINITY
			return leftEta - rightEta
		})
}

function calculateAmbulanceProgressMeters(trip, signalsAhead) {
	const candidates = []

	;(Array.isArray(trip.signals) ? trip.signals : []).forEach((signal) => {
		const routeStart = Number(signal.distance_from_route_start)
		const remaining = Number(signal.distance_to_signal_meters)

		if (Number.isFinite(routeStart) && Number.isFinite(remaining)) {
			candidates.push(Math.max(0, routeStart - remaining))
		}
	})

	if (candidates.length === 0 || !Number.isFinite(Number(trip.route?.distance_meters))) {
		return null
	}

	return Math.max(0, Math.min(...candidates, Number(trip.route.distance_meters)))
}

function renderRouteTimeline(trip) {
	const signalsAhead = getSignalsAhead(trip).slice(0, 4)
	const routeDistance = Number(trip.route?.distance_meters)
	const hasRouteDistance = Number.isFinite(routeDistance) && routeDistance > 0
	const ambulanceProgressMeters = calculateAmbulanceProgressMeters(trip, signalsAhead)
	const progressPercent = hasRouteDistance && Number.isFinite(ambulanceProgressMeters)
		? Math.max(0, Math.min(100, (ambulanceProgressMeters / routeDistance) * 100))
		: 0
	const timelineStartPercent = 4
	const timelineEndPercent = 94
	const ambulanceLeftPercent = timelineStartPercent + (progressPercent / 100) * (timelineEndPercent - timelineStartPercent)

	const signalNodes = signalsAhead.map((signal, index) => {
		const routeStartMeters = Number(signal.distance_from_route_start)
		const leftPercent = hasRouteDistance && Number.isFinite(routeStartMeters)
			? Math.max(timelineStartPercent, Math.min(timelineEndPercent, (routeStartMeters / routeDistance) * 100))
			: (index + 1) * (84 / (signalsAhead.length + 1)) + 5

		const statusClass = String(signal.current_color || "RED").toLowerCase()
		const trafficLevel = (signal.traffic_level || "UNKNOWN").toUpperCase()

		return `
			<div class="timeline-node" style="left:${leftPercent}%">
				<div class="timeline-eta">${formatSignalEta(signal.eta_seconds)}</div>
				<div class="timeline-signal ${statusClass}">${signal.priority_override ? "🟢" : "🚦"}</div>
				<div class="timeline-traffic">${trafficLevel}</div>
			</div>
		`
	}).join("")

	return `
		<div class="route-timeline">
			<div class="timeline-track"></div>
			<div class="timeline-progress" style="width:${progressPercent}%"></div>
			<div class="timeline-ambulance" style="left:${ambulanceLeftPercent}%">
				<img class="timeline-ambulance-image" src="assets/ambulance.png" alt="Ambulance" />
			</div>
			${signalNodes}
			<div class="timeline-hospital">🏥</div>
			${signalsAhead.length === 0 ? '<div class="signal-empty timeline-empty-note">No very-close on-route signals ahead.</div>' : ''}
		</div>
	`
}

function renderAmbulances() {
	container.innerHTML = ""

	const filtered = activeFilters.length === 0
		? activeTrips
		: activeTrips.filter(trip => activeFilters.includes(normalizeSeverity(trip.severity)))

	updateEmptyState(
		filtered.length === 0,
		activeTrips.length === 0 ? "No active ambulance trips found." : "No active trips for selected severity."
	)

	filtered.forEach((trip) => {
		const severityClass = normalizeSeverity(trip.severity)
		const card = document.createElement("div")
		card.className = "ambulance-card"

		card.innerHTML = `
			<div class="ambulance-main">
				<div class="ambulance-info">
					<div class="ambulance-icon">+</div>
					<div class="info-text">
						<h3>${trip.vehicle_number}</h3>
						<p>${trip.ambulance_name}</p>
						<span class="status ${severityClass}">${trip.severity}</span>
					</div>
				</div>

				<div class="trip-meta">
					<div class="meta-block">
						<span class="meta-label">Hospital</span>
						<strong>${trip.registered_hospital}</strong>
					</div>
					<div class="meta-block">
						<span class="meta-label">Started</span>
						<strong>${formatStartTime(trip.start_time)}</strong>
					</div>
					<div class="meta-block">
						<span class="meta-label">Signals Ahead</span>
						<strong>${getSignalsAhead(trip).length}</strong>
					</div>
				</div>

				<div class="eta">ETA ${formatEta(trip.eta_to_hospital)}</div>
			</div>

			${renderRouteTimeline(trip)}
		`

		card.onclick = () => {
			const selectedAmbulanceData = {
				tripId: trip.id,
				plate: trip.vehicle_number,
				driver: `Driver ${trip.driver_id}`,
				status: severityClass,
				destinationHospital: trip.registered_hospital,
				eta: formatEta(trip.eta_to_hospital),
				priority: trip.severity,
				source: { lat: trip.patient_lat, lng: trip.patient_lon },
				destination: { lat: trip.hospital_lat, lng: trip.hospital_lon },
				ambulanceName: trip.ambulance_name,
				ambulanceType: trip.ambulance_type,
				signals: trip.signals || []
			}

			sessionStorage.setItem("selectedAmbulanceData", JSON.stringify(selectedAmbulanceData))
			window.location.href = "WebPage2.html"
		}

		container.appendChild(card)
	})
}

function findTripIndex(payload) {
	return activeTrips.findIndex((trip) => {
		if (payload.tripId && Number(trip.id) === Number(payload.tripId)) {
			return true
		}

		if (payload.trip_id && Number(trip.id) === Number(payload.trip_id)) {
			return true
		}

		if (payload.vehicle_number && String(trip.vehicle_number).toUpperCase() === String(payload.vehicle_number).toUpperCase()) {
			return true
		}

		return false
	})
}

function mergeTripPayload(payload) {
	const index = findTripIndex(payload)

	if (index < 0) {
		const fallbackTripId = payload.tripId ?? payload.trip_id ?? Date.now()
		const fallbackTrip = {
			id: Number.isFinite(Number(fallbackTripId)) ? Number(fallbackTripId) : Date.now(),
			driver_id: payload.driver_id ?? null,
			vehicle_number: payload.vehicle_number || "UNKNOWN",
			ambulance_name: payload.ambulance_name || "Ambulance",
			ambulance_type: payload.ambulance_type || "—",
			registered_hospital: payload.registered_hospital || "—",
			severity: payload.severity || "STABLE",
			start_time: payload.start_time || new Date().toISOString(),
			patient_lat: payload.patient_lat ?? null,
			patient_lon: payload.patient_lon ?? null,
			hospital_lat: payload.hospital_lat ?? null,
			hospital_lon: payload.hospital_lon ?? null,
			live_lat: payload.ambulanceLat ?? payload.ambulance_lat ?? payload.lat ?? null,
			live_lon: payload.ambulanceLon ?? payload.ambulance_lon ?? payload.lon ?? null,
			eta_to_hospital: payload.eta_to_hospital ?? null,
			live_timestamp: payload.updated_at ?? payload.timestamp ?? null,
			route: payload.route || null,
			signals: Array.isArray(payload.signals) ? payload.signals : []
		}

		activeTrips = [fallbackTrip, ...activeTrips]
		return
	}

	const existing = activeTrips[index]
	activeTrips[index] = {
		...existing,
		driver_id: payload.driver_id ?? existing.driver_id,
		vehicle_number: payload.vehicle_number ?? existing.vehicle_number,
		ambulance_name: payload.ambulance_name ?? existing.ambulance_name,
		ambulance_type: payload.ambulance_type ?? existing.ambulance_type,
		registered_hospital: payload.registered_hospital ?? existing.registered_hospital,
		severity: payload.severity ?? existing.severity,
		start_time: payload.start_time ?? existing.start_time,
		patient_lat: payload.patient_lat ?? existing.patient_lat,
		patient_lon: payload.patient_lon ?? existing.patient_lon,
		hospital_lat: payload.hospital_lat ?? existing.hospital_lat,
		hospital_lon: payload.hospital_lon ?? existing.hospital_lon,
		live_lat: payload.ambulanceLat ?? payload.ambulance_lat ?? payload.lat ?? existing.live_lat,
		live_lon: payload.ambulanceLon ?? payload.ambulance_lon ?? payload.lon ?? existing.live_lon,
		eta_to_hospital: payload.eta_to_hospital ?? existing.eta_to_hospital,
		live_timestamp: payload.updated_at ?? payload.timestamp ?? existing.live_timestamp,
		route: payload.route || existing.route,
		signals: Array.isArray(payload.signals) ? payload.signals : existing.signals
	}
}

function connectActiveTripsSocket() {
	if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
		return
	}

	setConnectionStatus("Connecting...", "connecting")
	socket = new WebSocket(`${WS_BASE_URL}/ws/active-trips?role=dashboard`)

	socket.addEventListener("open", () => {
		if (reconnectTimer) {
			clearTimeout(reconnectTimer)
			reconnectTimer = null
		}
		setConnectionStatus("Live connected", "connected")
	})

	socket.addEventListener("message", (event) => {
		try {
			const payload = JSON.parse(event.data)

			if (payload.type === "active_trips_snapshot") {
				activeTrips = Array.isArray(payload.trips) ? payload.trips : []
				renderAmbulances()
				return
			}

			if (payload.type === "live_location_update" || payload.type === "trip_signals_update") {
				mergeTripPayload(payload)
				renderAmbulances()
				return
			}

			if (payload.type === "error") {
				setConnectionStatus("Socket error", "error")
				updateEmptyState(activeTrips.length === 0, payload.message || "WebSocket error")
			}
		} catch (error) {
			console.error("Invalid websocket payload", error)
		}
	})

	socket.addEventListener("close", () => {
		setConnectionStatus("Disconnected (retrying)", "error")
		reconnectTimer = setTimeout(() => {
			connectActiveTripsSocket()
		}, 2000)
	})

	socket.addEventListener("error", () => {
		setConnectionStatus("Socket error", "error")
	})
}

renderAmbulances()
connectActiveTripsSocket()

pills.forEach((pill) => {
	pill.addEventListener("click", () => {
		const status = pill.dataset.status

		if (status === "all") {
			activeFilters = []
			pills.forEach(currentPill => currentPill.classList.remove("active"))
			pill.classList.add("active")
		} else {
			document.querySelector('[data-status="all"]').classList.remove("active")

			if (activeFilters.includes(status)) {
				activeFilters = activeFilters.filter(filterValue => filterValue !== status)
				pill.classList.remove("active")
			} else {
				activeFilters.push(status)
				pill.classList.add("active")
			}

			if (activeFilters.length === 0) {
				document.querySelector('[data-status="all"]').classList.add("active")
			}
		}

		renderAmbulances()
	})
})