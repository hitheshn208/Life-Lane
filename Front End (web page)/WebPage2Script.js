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

async function getRouteGeoJsonFromOSRM(sourcePoint,destinationPoint){

const osrmUrl =
`https://router.project-osrm.org/route/v1/driving/${sourcePoint.lng},${sourcePoint.lat};${destinationPoint.lng},${destinationPoint.lat}?overview=full&geometries=geojson`

const response = await fetch(osrmUrl)
const payload = await response.json()

return payload.routes[0].geometry
}

async function initMap(data){

const startPoint = data?.source || { lat:12.9716, lng:77.5946 }
const destinationPoint = data?.destination || { lat:12.9616, lng:77.5846 }

map = L.map("map").setView([startPoint.lat,startPoint.lng],13)

L.tileLayer(
"https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
{maxZoom:19}
).addTo(map)

ambulanceMarker = L.marker([startPoint.lat,startPoint.lng]).addTo(map)

hospitalMarker = L.marker([destinationPoint.lat,destinationPoint.lng]).addTo(map)

hospitalMarker.bindPopup("🏥 Hospital")

const routeGeo = await getRouteGeoJsonFromOSRM(startPoint,destinationPoint)

routeLayer = L.geoJSON(routeGeo,{
style:{
color:"#2563eb",
weight:5
}
}).addTo(map)

map.fitBounds(routeLayer.getBounds().pad(0.2))

/* sidebar data */

if(data){

document.getElementById("hospital").textContent =
data.destinationHospital || "—"

document.getElementById("eta").textContent =
data.eta || "—"

document.getElementById("priority").textContent =
data.priority || "—"

document.getElementById("etaMinutes").textContent =
data.remaining || "--"

document.getElementById("remaining").textContent =
`${data.remaining || "--"} min remaining`

/* signals */

const container = document.getElementById("signalPath")

container.innerHTML = ""

if(Array.isArray(data.signals)){

data.signals.forEach(signal=>{

const el = document.createElement("div")
el.className="signal"

const red = document.createElement("div")
const yellow = document.createElement("div")
const green = document.createElement("div")

red.className = "light red"
yellow.className = "light yellow"
green.className = "light green"

/* turn OFF all lights first */

red.style.opacity = "0.2"
yellow.style.opacity = "0.2"
green.style.opacity = "0.2"

/* activate correct one */

if(signal.status === "red"){
red.style.opacity = "1"
}

if(signal.status === "yellow"){
yellow.style.opacity = "1"
}

if(signal.status === "green"){
green.style.opacity = "1"
}

el.appendChild(red)
el.appendChild(yellow)
el.appendChild(green)

container.appendChild(el)

})

}

}

}

const selectedAmbulanceData = {
plate:"KA19AB1023",
driver:"John D Smith",
status:"critical",
destinationHospital:"St Martha's Hospital",
eta:"14:35",
priority:"HIGH",
signals:[
{status:"red"},
{status:"green"},
{status:"yellow"},
{status:"green"}
],
source:{lat:12.9716,lng:77.5946},
destination:{lat:12.9616,lng:77.5846}
}
initMap(selectedAmbulanceData)