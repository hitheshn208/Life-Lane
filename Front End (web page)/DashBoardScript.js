const API_BASE_URL = "http://localhost:3000"
const WS_BASE_URL = API_BASE_URL.replace(/^http/, "ws")

const container = document.getElementById("ambulanceContainer")
const emptyState = document.getElementById("emptyState")
const pills = document.querySelectorAll(".filter-pill")
const connectionStatus = document.getElementById("connectionStatus")

let activeTrips = []
let activeFilters = []
let socket = null

function setConnectionStatus(text,state){
if(!connectionStatus){
return
}

connectionStatus.textContent = text
connectionStatus.className = `connection-status ${state}`
}

function normalizeSeverity(severity){
return (severity || "STABLE").toLowerCase()
}

function formatEta(minutes){
return `${minutes} min`
}

function formatCoordinate(lat,lon){
return `${Number(lat).toFixed(4)}, ${Number(lon).toFixed(4)}`
}

function formatStartTime(startTime){
if(!startTime){
return "—"
}

const parsed = new Date(String(startTime).replace(" ","T"))

if(Number.isNaN(parsed.getTime())){
return startTime
}

return parsed.toLocaleString()
}

function updateEmptyState(isEmpty,message){
if(!emptyState){
return
}

emptyState.hidden = !isEmpty

if(message){
emptyState.textContent = message
}
}

function renderAmbulances(){

container.innerHTML = ""

const filtered = activeFilters.length === 0
? activeTrips
: activeTrips.filter(trip => activeFilters.includes(normalizeSeverity(trip.severity)))

updateEmptyState(filtered.length === 0, activeTrips.length === 0 ? "No active ambulance trips found." : "No active trips for selected severity.")

filtered.forEach(trip => {

const severityClass = normalizeSeverity(trip.severity)

const card = document.createElement("div")
card.className = "ambulance-card"

card.innerHTML = `
<div class="ambulance-info">
<div class="ambulance-icon">🚑</div>
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
<span class="meta-label">Type</span>
<strong>${trip.ambulance_type}</strong>
</div>
<div class="meta-block">
<span class="meta-label">Patient</span>
<strong>${formatCoordinate(trip.patient_lat, trip.patient_lon)}</strong>
</div>
<div class="meta-block">
<span class="meta-label">Started</span>
<strong>${formatStartTime(trip.start_time)}</strong>
</div>
</div>

<div class="eta">ETA ${formatEta(trip.eta_to_hospital)}</div>
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
ambulanceType: trip.ambulance_type
}

sessionStorage.setItem("selectedAmbulanceData", JSON.stringify(selectedAmbulanceData))
window.location.href = "WebPage2.html"
}

container.appendChild(card)
})
}

function connectActiveTripsSocket(){
setConnectionStatus("Connecting...", "connecting")

socket = new WebSocket(`${WS_BASE_URL}/ws/active-trips?role=dashboard`)

socket.addEventListener("open", () => {
setConnectionStatus("Live connected", "connected")
})

socket.addEventListener("message", (event) => {
try{
const payload = JSON.parse(event.data)

if(payload.type === "active_trips_snapshot"){
activeTrips = Array.isArray(payload.trips) ? payload.trips : []
renderAmbulances()
return
}

if(payload.type === "live_location_update"){
const updateTripIndex = activeTrips.findIndex(trip => {
if(payload.trip_id && Number(trip.id) === Number(payload.trip_id)){
return true
}

if(payload.vehicle_number && String(trip.vehicle_number).toUpperCase() === String(payload.vehicle_number).toUpperCase()){
return true
}

return false
})

if(updateTripIndex >= 0){
const existing = activeTrips[updateTripIndex]
activeTrips[updateTripIndex] = {
...existing,
live_lat: payload.lat,
live_lon: payload.lon,
eta_to_hospital: payload.eta_to_hospital ?? existing.eta_to_hospital,
live_timestamp: payload.timestamp
}
renderAmbulances()
}

return
}

if(payload.type === "error"){
setConnectionStatus("Socket error", "error")
updateEmptyState(activeTrips.length === 0, payload.message || "WebSocket error")
}
}catch(error){
console.error("Invalid websocket payload", error)
}
})

socket.addEventListener("close", () => {
setConnectionStatus("Disconnected", "error")
})

socket.addEventListener("error", () => {
setConnectionStatus("Socket error", "error")
})
}

renderAmbulances()
connectActiveTripsSocket()

pills.forEach(pill => {
pill.addEventListener("click", () => {
const status = pill.dataset.status

if(status === "all"){
activeFilters = []
pills.forEach(p => p.classList.remove("active"))
pill.classList.add("active")
}else{
document.querySelector('[data-status="all"]').classList.remove("active")

if(activeFilters.includes(status)){
activeFilters = activeFilters.filter(filterValue => filterValue !== status)
pill.classList.remove("active")
}else{
activeFilters.push(status)
pill.classList.add("active")
}

if(activeFilters.length === 0){
document.querySelector('[data-status="all"]').classList.add("active")
}
}

renderAmbulances()
})
})