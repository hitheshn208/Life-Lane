const ambulances = [

{
plate:"KA19AB1023",
destination:"City Hospital",
status:"critical",
eta:"6 min",
progress:30,
signals:[2,4,5]
},

{
plate:"KA19AB2201",
destination:"Apollo Hospital",
status:"moderate",
eta:"9 min",
progress:50,
signals:[3,6,7,8]
},

{
plate:"KA19AB3320",
destination:"District Hospital",
status:"stable",
eta:"15 min",
progress:10,
signals:[5,9]
},

{
plate:"KA19AB8741",
destination:"Manipal Hospital",
status:"critical",
eta:"5 min",
progress:75,
signals:[1,3,4]
},

{
plate:"KA19AB8812",
destination:"Aster Hospital",
status:"moderate",
eta:"11 min",
progress:40,
signals:[2,6,9,10,11]
},

{
plate:"KA19AB4521",
destination:"Nitte Hospital",
status:"critical",
eta:"4 min",
progress:85,
signals:[1,2]
},

{
plate:"KA19AB9912",
destination:"KMC Hospital",
status:"stable",
eta:"18 min",
progress:15,
signals:[5,10,12]
},

{
plate:"KA19AB1209",
destination:"Unity Hospital",
status:"moderate",
eta:"12 min",
progress:45,
signals:[4,7,9]
},

{
plate:"KA19AB4412",
destination:"Global Hospital",
status:"critical",
eta:"7 min",
progress:60,
signals:[3,4,5,6]
},

{
plate:"KA19AB7731",
destination:"Care Hospital",
status:"stable",
eta:"16 min",
progress:20,
signals:[6,12,14]
}

]

const container = document.getElementById("ambulanceContainer")

ambulances.forEach(a=>{

const signalsHTML = a.signals.map(time =>

`
<div class="signal-block">
<div class="signal-eta">${time}m</div>
<div class="signal">🚦</div>
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

container.appendChild(card)

})