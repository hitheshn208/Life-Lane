const API_BASE_URL = "http://localhost:3000"
const WS_BASE_URL = API_BASE_URL.replace(/^http/, "ws")

let map
let patientMarker
let hospitalMarker
let ambulanceLiveMarker
let patientToHospitalLayer
let tripSocket
let currentTripId = null
let currentVehicleNumber = null

function getSelectedAmbulanceData(){
const raw = sessionStorage.getItem("selectedAmbulanceData")

if(!raw){
return null
}

try{
return JSON.parse(raw)
}catch(_error){
return null
}
}

function setLoader(visible,text){
const loader = document.getElementById("routeLoader")
const loaderText = document.getElementById("routeLoaderText")

if(loader){
loader.classList.toggle("hidden", !visible)
}

if(loaderText && text){
loaderText.textContent = text
}
}

async function getRouteGeoJsonFromOSRM(sourcePoint,destinationPoint){
const osrmUrl =
`https://router.project-osrm.org/route/v1/driving/${sourcePoint.lng},${sourcePoint.lat};${destinationPoint.lng},${destinationPoint.lat}?overview=full&geometries=geojson`

const response = await fetch(osrmUrl)

if(!response.ok){
throw new Error(`OSRM request failed with ${response.status}`)
}

const payload = await response.json()

if(!payload.routes || !payload.routes[0] || !payload.routes[0].geometry){
throw new Error("OSRM route geometry missing")
}

return payload.routes[0].geometry
}

async function fetchActiveTripDetails(tripId){
const response = await fetch(`${API_BASE_URL}/api/trips/active/public/${tripId}`)

if(!response.ok){
throw new Error(`Failed to fetch active trip ${tripId}`)
}

const payload = await response.json()
return payload.trip
}

function updateSidebar(trip){
document.getElementById("hospital").textContent = trip.registered_hospital || "—"
document.getElementById("eta").textContent = `${trip.eta_to_hospital ?? "--"} min`
document.getElementById("priority").textContent = trip.severity || "—"
document.getElementById("etaMinutes").textContent = String(trip.eta_to_hospital ?? "--")
document.getElementById("remaining").textContent = `${trip.eta_to_hospital ?? "--"} min remaining`

const signalPath = document.getElementById("signalPath")
if(signalPath){
signalPath.innerHTML = ""
}

function updateEtaOnSidebar(etaToHospital){
if(etaToHospital === null || etaToHospital === undefined){
return
}

document.getElementById("eta").textContent = `${etaToHospital} min`
document.getElementById("etaMinutes").textContent = String(etaToHospital)
document.getElementById("remaining").textContent = `${etaToHospital} min remaining`
}

function upsertAmbulanceLiveMarker(lat, lon){
if(!map){
return
}

const safeLat = Number(lat)
const safeLon = Number(lon)

if(!Number.isFinite(safeLat) || !Number.isFinite(safeLon)){
return
}

if(!ambulanceLiveMarker){
ambulanceLiveMarker = L.marker([safeLat, safeLon]).addTo(map).bindPopup("🚑 Live Ambulance")
return
}

ambulanceLiveMarker.setLatLng([safeLat, safeLon])
}

function connectTripLiveSocket(){
tripSocket = new WebSocket(`${WS_BASE_URL}/ws/active-trips?role=webpage2`)

tripSocket.addEventListener("message", (event) => {
try{
const payload = JSON.parse(event.data)

if(payload.type !== "live_location_update"){
return
}

const matchedByTripId = currentTripId && payload.trip_id && Number(currentTripId) === Number(payload.trip_id)
const matchedByVehicle = currentVehicleNumber && payload.vehicle_number && String(currentVehicleNumber).toUpperCase() === String(payload.vehicle_number).toUpperCase()

if(!matchedByTripId && !matchedByVehicle){
return
}

upsertAmbulanceLiveMarker(payload.lat, payload.lon)
updateEtaOnSidebar(payload.eta_to_hospital)
}catch(error){
console.error("Invalid websocket payload", error)
}
})
}
}

async function initMap(trip){
const patientPoint = {
lat: Number(trip.patient_lat),
lng: Number(trip.patient_lon)
}
const hospitalPoint = {
lat: Number(trip.hospital_lat),
lng: Number(trip.hospital_lon)
}

map = L.map("map").setView([patientPoint.lat,patientPoint.lng],13)

L.tileLayer(
"https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
{maxZoom:19}
).addTo(map)

patientMarker = L.marker([patientPoint.lat,patientPoint.lng]).addTo(map).bindPopup("🧍 Patient")
hospitalMarker = L.marker([hospitalPoint.lat,hospitalPoint.lng]).addTo(map).bindPopup("🏥 Hospital")

setLoader(true, "Loading route...")

try{
const patientToHospitalGeo = await getRouteGeoJsonFromOSRM(patientPoint, hospitalPoint)

patientToHospitalLayer = L.geoJSON(patientToHospitalGeo,{
style:{
color:"#dc2626",
weight:5
}
}).addTo(map)

const boundsLayer = L.featureGroup([patientToHospitalLayer])
map.fitBounds(boundsLayer.getBounds().pad(0.2))
}catch(error){
console.error("Failed to load route:", error)
setLoader(true, "Failed to load route")
setTimeout(() => setLoader(false), 1200)
return
}

setLoader(false)
updateSidebar(trip)
}

async function initializePage(){
const selected = getSelectedAmbulanceData()
const selectedTripId = Number(selected?.tripId)

if(!Number.isInteger(selectedTripId) || selectedTripId <= 0){
alert("No trip selected from dashboard")
return
}

try{
setLoader(true, "Fetching active trip details...")
const trip = await fetchActiveTripDetails(selectedTripId)
currentTripId = trip.id
currentVehicleNumber = trip.vehicle_number
await initMap(trip)
connectTripLiveSocket()
}catch(error){
console.error(error)
setLoader(true, "Unable to load selected active trip")
}
}

initializePage()