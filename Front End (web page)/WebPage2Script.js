function getAmbulanceData(){

const params = new URLSearchParams(window.location.search)

const data = params.get("data")

return JSON.parse(decodeURIComponent(data))

}

const ambulance = getAmbulanceData()

/* fill ambulance details */

document.getElementById("ambulanceDetails").innerHTML = `

<div class="detail">
Plate Number:
<span class="value">${ambulance.plate}</span>
</div>

<div class="detail">
Destination Hospital:
<span class="value">${ambulance.destination}</span>
</div>

<div class="detail">
Patient Condition:
<span class="value">${ambulance.status.toUpperCase()}</span>
</div>

<div class="detail">
Estimated Arrival:
<span class="value">${ambulance.eta}</span>
</div>

<div class="detail">
Route Signals:
<span class="value">${ambulance.signals.length}</span>
</div>

<div class="detail">
Dispatch Time:
<span class="value">2 mins ago</span>
</div>

<div class="detail">
Ambulance Speed:
<span class="value">48 km/h</span>
</div>

`

/* system details */

document.getElementById("signalCount").innerText = ambulance.signals.length

document.getElementById("priorityLevel").innerText = ambulance.status.toUpperCase()