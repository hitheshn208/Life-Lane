const ambulances = [

{
plate:"KA19AB1023",
destination:"City Hospital",
status:"critical",
eta:"6 min",
progress:30,
signals:[
{time:2,status:"green"},
{time:4,status:"red"},
{time:5,status:"yellow"}
]
},

{
plate:"KA19AB2201",
destination:"Apollo Hospital",
status:"moderate",
eta:"9 min",
progress:50,
signals:[
{time:3,status:"red"},
{time:6,status:"green"},
{time:7,status:"red"},
{time:8,status:"yellow"}
]
},

{
plate:"KA19AB3320",
destination:"District Hospital",
status:"stable",
eta:"15 min",
progress:10,
signals:[
{time:5,status:"red"},
{time:9,status:"green"}
]
},

{
plate:"KA19AB8741",
destination:"Manipal Hospital",
status:"critical",
eta:"5 min",
progress:75,
signals:[
{time:1,status:"green"},
{time:3,status:"green"},
{time:4,status:"yellow"}
]
},

{
plate:"KA19AB8812",
destination:"Aster Hospital",
status:"moderate",
eta:"11 min",
progress:40,
signals:[
{time:2,status:"red"},
{time:6,status:"yellow"},
{time:9,status:"green"},
{time:10,status:"red"},
{time:11,status:"yellow"}
]
},

{
plate:"KA19AB4521",
destination:"Nitte Hospital",
status:"critical",
eta:"4 min",
progress:85,
signals:[
{time:1,status:"green"},
{time:2,status:"green"}
]
},

{
plate:"KA19AB9912",
destination:"KMC Hospital",
status:"stable",
eta:"18 min",
progress:15,
signals:[
{time:5,status:"red"},
{time:10,status:"yellow"},
{time:12,status:"green"}
]
}

]

const API_BASE_URL = "http://localhost:3000"

const container = document.getElementById("ambulanceContainer")
const pills = document.querySelectorAll(".filter-pill")

let activeFilters = []

function renderAmbulances(){

container.innerHTML = ""

const filtered = activeFilters.length === 0
? ambulances
: ambulances.filter(a => activeFilters.includes(a.status))

filtered.forEach(a=>{

const signalsHTML = a.signals.map(signal =>

`
<div class="signal-block">

<div class="signal-eta">${signal.time} min</div>

<div class="signal">🚦</div>

<div class="signal-light ${signal.status}"></div>

</div>
`

).join("")

const progressWidth = Math.min(a.progress,100)

const card = document.createElement("div")
card.className="ambulance-card"

card.innerHTML = `

<div class="ambulance-info">

<div class="ambulance-icon">🚑</div>

<div class="info-text">

<h3>${a.plate}</h3>

<p>${a.destination}</p>

<span class="status ${a.status}">
${a.status.toUpperCase()}
</span>

</div>

</div>

<div class="route">

<div class="progress-line"></div>

<div class="progress" style="width:${progressWidth}%"></div>

<div class="signals">

${signalsHTML}

<div class="hospital">🏥</div>

</div>

</div>

<div class="eta">ETA ${a.eta}</div>

`

/* CARD CLICK -> FETCH BACKEND DATA + OPEN PAGE */

card.onclick = async () => {

try {

const coordinatesResponse = await fetch(`${API_BASE_URL}/api/ambulances/${encodeURIComponent(a.plate)}/coordinates`)

if(!coordinatesResponse.ok){
throw new Error("Failed to get source/destination coordinates")
}

const coordinatesData = await coordinatesResponse.json()

const selectedAmbulanceData = {
...a,
driver: coordinatesData.driver || "—",
priority: coordinatesData.priority || "—",
source: coordinatesData.source,
destination: coordinatesData.destination,
destinationHospital: a.destination
}

sessionStorage.setItem("selectedAmbulanceData", JSON.stringify(selectedAmbulanceData))

window.location.href = "WebPage2.html"

} catch(error){

alert(error.message)

}

}

container.appendChild(card)

})

}

/* INITIAL RENDER */

renderAmbulances()

/* FILTER BUTTON LOGIC */

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

activeFilters = activeFilters.filter(f => f !== status)
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