let map
let ambulanceMarker
let hospitalMarker
let routeLayer

function setRouteLoading(isLoading,message){
const loader = document.getElementById("routeLoader")
const loaderText = document.getElementById("routeLoaderText")

if(!loader){
return
}

if(loaderText && message){
loaderText.textContent = message
}

loader.classList.toggle("hidden", !isLoading)
}

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

/* initialize map */

async function getRouteGeoJsonFromOSRM(sourcePoint,destinationPoint){
const osrmUrl = `https://router.project-osrm.org/route/v1/driving/${sourcePoint.lng},${sourcePoint.lat};${destinationPoint.lng},${destinationPoint.lat}?overview=full&geometries=geojson`

const response = await fetch(osrmUrl)

if(!response.ok){
throw new Error("Unable to load route from OSRM")
}

const payload = await response.json()

if(!payload.routes || !payload.routes.length){
throw new Error("No route returned from OSRM")
}

return {
geojson: payload.routes[0].geometry,
distanceMeters: payload.routes[0].distance,
durationSeconds: payload.routes[0].duration
}
}

async function initMap(data){

setRouteLoading(true,"Connecting to ambulance")

const startPoint = data?.source || { lat:12.9716, lng:77.5946 }
const destinationPoint = data?.destination || { lat:12.9616, lng:77.5846 }

const start = [startPoint.lat,startPoint.lng]

map = L.map("map").setView(start,13)

L.tileLayer(
"https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
{
maxZoom:19
}
).addTo(map)

/* ambulance marker */

ambulanceMarker = L.marker(start).addTo(map)

/* hospital marker */

const hospital = [destinationPoint.lat,destinationPoint.lng]

hospitalMarker = L.marker(hospital).addTo(map)

hospitalMarker.bindPopup("🏥 Hospital")

try{
const route = await getRouteGeoJsonFromOSRM(startPoint,destinationPoint)

routeLayer = L.geoJSON(route.geojson,{
style:{
color:"#2563eb",
weight:5,
opacity:0.85
}
}).addTo(map)

if(routeLayer.getBounds && routeLayer.getBounds().isValid()){
map.fitBounds(routeLayer.getBounds().pad(0.2))
}else{
const bounds = L.latLngBounds([start,hospital])
map.fitBounds(bounds.pad(0.2))
}
}catch(error){
console.error(error)
setRouteLoading(true,"Route unavailable. Showing markers...")
await new Promise(resolve => setTimeout(resolve,900))
}

if(data){
document.getElementById("plate").textContent = data.plate || "—"
document.getElementById("driver").textContent = data.driver || "—"
document.getElementById("status").textContent = data.status ? data.status.toUpperCase() : "—"
document.getElementById("hospital").textContent = data.destinationHospital || "—"
document.getElementById("eta").textContent = data.eta || "—"
document.getElementById("signals").textContent = Array.isArray(data.signals) ? data.signals.length : "—"
document.getElementById("priority").textContent = data.priority || "—"
}

setRouteLoading(false)

}

/* run map */

const selectedAmbulanceData = getSelectedAmbulanceData()
initMap(selectedAmbulanceData)