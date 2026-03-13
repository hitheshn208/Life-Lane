let map
let ambulanceMarker
let hospitalMarker
let routeLayer

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

/* ambulance icon */

const ambulanceIcon = L.icon({

iconUrl:"https://cdn-icons-png.flaticon.com/512/2967/2967350.png",

iconSize:[40,40],
iconAnchor:[20,20]

})

/* initialize map */

function initMap(data){

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

ambulanceMarker = L.marker(start,{
icon:ambulanceIcon
}).addTo(map)

/* hospital marker */

const hospital = [destinationPoint.lat,destinationPoint.lng]

hospitalMarker = L.marker(hospital).addTo(map)

hospitalMarker.bindPopup("🏥 Hospital")

if(data?.routeGeoJson){
routeLayer = L.geoJSON(data.routeGeoJson,{
style:{
color:"#2563eb",
weight:5,
opacity:0.85
}
}).addTo(map)

const bounds = L.latLngBounds([start,hospital])
map.fitBounds(bounds.pad(0.2))
}

if(data){
document.getElementById("plate").textContent = data.plate || "—"
document.getElementById("driver").textContent = data.driver || "—"
document.getElementById("status").textContent = data.status ? data.status.toUpperCase() : "—"
document.getElementById("hospital").textContent = data.destination || "—"
document.getElementById("eta").textContent = data.eta || "—"
document.getElementById("signals").textContent = Array.isArray(data.signals) ? data.signals.length : "—"
document.getElementById("priority").textContent = data.priority || "—"
}

}

/* run map */

const selectedAmbulanceData = getSelectedAmbulanceData()
initMap(selectedAmbulanceData)