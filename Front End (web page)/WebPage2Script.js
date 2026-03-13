let map
let ambulanceMarker
let hospitalMarker

/* ambulance icon */

const ambulanceIcon = L.icon({

iconUrl:"https://cdn-icons-png.flaticon.com/512/2967/2967350.png",

iconSize:[40,40],
iconAnchor:[20,20]

})

/* initialize map */

function initMap(){

const start = [12.9716,77.5946]   // temporary

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

const hospital = [12.9616,77.5846]

hospitalMarker = L.marker(hospital).addTo(map)

hospitalMarker.bindPopup("🏥 Hospital")

}

/* run map */

initMap()