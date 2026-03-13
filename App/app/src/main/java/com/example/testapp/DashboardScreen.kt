package com.example.testapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

@SuppressLint("MissingPermission")
@Composable
fun DashboardScreen(vehicleId: String = "", onLogout: () -> Unit = {}) {
    val context = LocalContext.current
    val sharedPref = remember { context.getSharedPreferences("testapp_prefs", Context.MODE_PRIVATE) }
    
    LaunchedEffect(Unit) {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasLocationPermission = isGranted }
    )

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    var ambulanceLocation by remember { mutableStateOf(GeoPoint(12.9716, 77.5946)) }
    var patientLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var hospitalLocation by remember { mutableStateOf<GeoPoint?>(null) }
    
    var showSearchOverlay by remember { mutableStateOf(false) }
    var searchType by remember { mutableStateOf("patient") }
    var showPatientDialog by remember { mutableStateOf(false) }
    var isTripStarted by remember { mutableStateOf(false) }
    
    var distance by remember { mutableStateOf("0.0 km") }
    var eta by remember { mutableStateOf("0 mins") }
    var patientRoutePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var hospitalRoutePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }

    val mapView = remember { MapView(context) }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // Throttled update state
    var lastCalcLocation by remember { mutableStateOf<GeoPoint?>(null) }

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2000)
                .build()

            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let {
                        val newLoc = GeoPoint(it.latitude, it.longitude)
                        ambulanceLocation = newLoc
                    }
                }
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    // Faster Routing Logic
    LaunchedEffect(ambulanceLocation, patientLocation, hospitalLocation) {
        if (patientLocation != null && hospitalLocation != null) {
            val distanceMoved = lastCalcLocation?.distanceToAsDouble(ambulanceLocation) ?: Double.MAX_VALUE
            if (distanceMoved > 50 || lastCalcLocation == null) {
                withContext(Dispatchers.IO) {
                    try {
                        val urlString = "https://router.project-osrm.org/route/v1/driving/" +
                                "${ambulanceLocation.longitude},${ambulanceLocation.latitude};" +
                                "${patientLocation!!.longitude},${patientLocation!!.latitude};" +
                                "${hospitalLocation!!.longitude},${hospitalLocation!!.latitude}" +
                                "?overview=full&geometries=geojson"
                        
                        val url = URL(urlString)
                        val conn = url.openConnection() as HttpURLConnection
                        conn.setRequestProperty("User-Agent", "AmbulanceApp/1.0")
                        val response = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(response)
                        val routes = json.getJSONArray("routes")
                        
                        if (routes.length() > 0) {
                            val route = routes.getJSONObject(0)
                            val legs = route.getJSONArray("legs")
                            
                            val geometry = route.getJSONObject("geometry")
                            val coordinates = geometry.getJSONArray("coordinates")
                            val allPoints = mutableListOf<GeoPoint>()
                            for (i in 0 until coordinates.length()) {
                                val coord = coordinates.getJSONArray(i)
                                allPoints.add(GeoPoint(coord.getDouble(1), coord.getDouble(0)))
                            }

                            val patientIdx = findClosestPointIndex(allPoints, patientLocation!!)
                            val pPoints = allPoints.subList(0, patientIdx + 1).toList()
                            val hPoints = allPoints.subList(patientIdx, allPoints.size).toList()
                            
                            val distTotal = route.getDouble("distance") / 1000.0
                            val timeTotal = route.getDouble("duration") / 60.0
                            
                            withContext(Dispatchers.Main) {
                                patientRoutePoints = pPoints
                                hospitalRoutePoints = hPoints
                                distance = String.format(Locale.US, "%.1f km", distTotal)
                                eta = String.format(Locale.US, "%.0f mins", timeTotal)
                                lastCalcLocation = ambulanceLocation
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        OSMMapView(
            mapView = mapView,
            ambulanceLoc = ambulanceLocation,
            patientLoc = patientLocation,
            hospitalLoc = hospitalLocation,
            patientRoutePoints = patientRoutePoints,
            hospitalRoutePoints = hospitalRoutePoints,
            onMapClick = { _ -> }
        )

        SmallFloatingTopBar(onLogout = onLogout)

        FloatingActionButton(
            onClick = {
                mapView.controller.animateTo(ambulanceLocation)
                mapView.controller.setZoom(17.0)
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 180.dp, end = 16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Center Map")
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            ControlPanel(
                isTripStarted = isTripStarted,
                patientLocation = patientLocation,
                hospitalLocation = hospitalLocation,
                distance = distance,
                eta = eta,
                onSearchPatient = { searchType = "patient"; showSearchOverlay = true },
                onSearchHospital = { searchType = "hospital"; showSearchOverlay = true },
                onStartTrip = { showPatientDialog = true }
            )
        }

        if (showSearchOverlay) {
            LocationSearchOverlay(
                type = searchType,
                sharedPref = sharedPref,
                onLocationSelected = { point, name ->
                    if (searchType == "patient") {
                        patientLocation = point
                    } else {
                        hospitalLocation = point
                        val recents = sharedPref.getStringSet("recent_hospitals", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                        recents.add(name)
                        sharedPref.edit().putStringSet("recent_hospitals", recents).apply()
                    }
                    showSearchOverlay = false
                    mapView.controller.animateTo(point)
                },
                onDismiss = { showSearchOverlay = false }
            )
        }

        if (showPatientDialog) {
            PatientDetailsDialog(
                onDismiss = { showPatientDialog = false },
                onSubmit = { details ->
                    showPatientDialog = false
                    isTripStarted = true
                    
                    // SEND DATA TO SERVER
                    sendTripDataToServer(
                        vehicleId = vehicleId,
                        patientLoc = patientLocation!!,
                        hospitalLoc = hospitalLocation!!,
                        details = details
                    )
                }
            )
        }
    }
}

// Function to send data to your server
fun sendTripDataToServer(
    vehicleId: String,
    patientLoc: GeoPoint,
    hospitalLoc: GeoPoint,
    details: Map<String, String>
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val serverUrl = "https://your-backend-api.com/start-trip"
            val url = URL(serverUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            val json = JSONObject()
            json.put("vehicle_id", vehicleId)
            json.put("patient_lat", patientLoc.latitude)
            json.put("patient_lon", patientLoc.longitude)
            json.put("hospital_lat", hospitalLoc.latitude)
            json.put("hospital_lon", hospitalLoc.longitude)
            json.put("patient_age", details["age"])
            json.put("condition", details["condition"])
            json.put("severity", details["severity"])

            conn.outputStream.use { it.write(json.toString().toByteArray()) }
            conn.responseCode
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun findClosestPointIndex(points: List<GeoPoint>, target: GeoPoint): Int {
    var minIdx = 0
    var minDistance = Double.MAX_VALUE
    for (i in points.indices) {
        val d = points[i].distanceToAsDouble(target)
        if (d < minDistance) {
            minDistance = d
            minIdx = i
        }
    }
    return minIdx
}

@Composable
fun LocationSearchOverlay(
    type: String,
    sharedPref: android.content.SharedPreferences,
    onLocationSelected: (GeoPoint, String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Pair<String, GeoPoint>>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val recents = remember { sharedPref.getStringSet("recent_hospitals", setOf())?.toList() ?: listOf() }
    
    val nearbyHospitals = listOf(
        "Manipal Hospital, Old Airport Road" to GeoPoint(12.9592, 77.6444),
        "Apollo Hospital, Bannerghatta" to GeoPoint(12.8961, 77.5985),
        "Fortis Hospital, Cunningham Road" to GeoPoint(12.9892, 77.5933),
        "Victoria Hospital, City Market" to GeoPoint(12.9642, 77.5746)
    )

    LaunchedEffect(query) {
        if (query.length > 2) {
            delay(500)
            isSearching = true
            withContext(Dispatchers.IO) {
                try {
                    val encodedQuery = URLEncoder.encode(if (type == "hospital") "$query hospital" else query, "UTF-8")
                    val url = URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=10")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "AmbulanceApp/1.0")
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(response)
                    val results = mutableListOf<Pair<String, GeoPoint>>()
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val name = item.getString("display_name")
                        val lat = item.getDouble("lat")
                        val lon = item.getDouble("lon")
                        results.add(name to GeoPoint(lat, lon))
                    }
                    withContext(Dispatchers.Main) {
                        searchResults = results
                        isSearching = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { isSearching = false }
                }
            }
        } else {
            searchResults = emptyList()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(if (type == "patient") "Search Patient Location..." else "Search Hospital...") },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                        singleLine = true
                    )
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (query.isEmpty() && type == "hospital") {
                        if (recents.isNotEmpty()) {
                            item { Text("Recent Hospitals", modifier = Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                            items(recents) { name ->
                                ListItem(headlineContent = { Text(name) }, leadingContent = { Icon(Icons.Default.History, contentDescription = null) },
                                    modifier = Modifier.clickable { 
                                        val point = nearbyHospitals.find { it.first == name }?.second ?: GeoPoint(12.97, 77.59)
                                        onLocationSelected(point, name) 
                                    })
                            }
                        }
                        item { Text("Nearby Hospitals", modifier = Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                        items(nearbyHospitals) { (name, point) ->
                            ListItem(headlineContent = { Text(name) }, leadingContent = { Icon(Icons.Default.LocalHospital, contentDescription = null) },
                                modifier = Modifier.clickable { onLocationSelected(point, name) })
                        }
                    } else {
                        items(searchResults) { (name, point) ->
                            ListItem(headlineContent = { Text(name, maxLines = 2) }, leadingContent = { Icon(if (type == "hospital") Icons.Default.LocalHospital else Icons.Default.Place, contentDescription = null) },
                                modifier = Modifier.clickable { onLocationSelected(point, name) })
                        }
                    }
                }
            }
        }
    }
}

fun createMarkerIcon(context: Context, color: Int, label: String): Drawable {
    val size = 100
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { this.color = color; style = Paint.Style.FILL; isAntiAlias = true }
    canvas.drawCircle(size / 2f, size / 2f, size / 2.2f, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size / 3f, paint)
    paint.apply { this.color = color; textSize = 40f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val xPos = size / 2f
    val yPos = (size / 2f) - ((paint.descent() + paint.ascent()) / 2f)
    canvas.drawText(label, xPos, yPos, paint)
    return BitmapDrawable(context.resources, bitmap)
}

fun createCurrentLocationIcon(context: Context): Drawable {
    val size = 64
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { isAntiAlias = true }
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.color = android.graphics.Color.parseColor("#2196F3")
    canvas.drawCircle(size / 2f, size / 2f, size / 2.5f, paint)
    return BitmapDrawable(context.resources, bitmap)
}

@Composable
fun OSMMapView(
    mapView: MapView,
    ambulanceLoc: GeoPoint,
    patientLoc: GeoPoint?,
    hospitalLoc: GeoPoint?,
    patientRoutePoints: List<GeoPoint>,
    hospitalRoutePoints: List<GeoPoint>,
    onMapClick: (GeoPoint) -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(mapView) {
        mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.0)
            controller.setCenter(ambulanceLoc)
        }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize(), update = { mv ->
        mv.overlays.clear()
        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { onMapClick(p); return true }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        mv.overlays.add(MapEventsOverlay(mapEventsReceiver))

        val ambMarker = Marker(mv)
        ambMarker.position = ambulanceLoc
        ambMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        ambMarker.icon = createCurrentLocationIcon(context)
        mv.overlays.add(ambMarker)

        patientLoc?.let {
            val pMarker = Marker(mv)
            pMarker.position = it
            pMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            pMarker.icon = createMarkerIcon(context, android.graphics.Color.RED, "P")
            mv.overlays.add(pMarker)
        }

        hospitalLoc?.let {
            val hMarker = Marker(mv)
            hMarker.position = it
            hMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            hMarker.icon = createMarkerIcon(context, android.graphics.Color.parseColor("#4CAF50"), "H")
            mv.overlays.add(hMarker)
        }

        if (patientRoutePoints.isNotEmpty()) {
            val line = Polyline()
            line.setPoints(patientRoutePoints)
            line.outlinePaint.color = android.graphics.Color.RED
            line.outlinePaint.strokeWidth = 12f
            mv.overlays.add(line)
        }

        if (hospitalRoutePoints.isNotEmpty()) {
            val line = Polyline()
            line.setPoints(hospitalRoutePoints)
            line.outlinePaint.color = android.graphics.Color.parseColor("#4CAF50")
            line.outlinePaint.strokeWidth = 12f
            mv.overlays.add(line)
        }
        mv.invalidate()
    })
}

@Composable
fun ControlPanel(
    isTripStarted: Boolean,
    patientLocation: GeoPoint?,
    hospitalLocation: GeoPoint?,
    distance: String,
    eta: String,
    onSearchPatient: () -> Unit,
    onSearchHospital: () -> Unit,
    onStartTrip: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!isTripStarted) {
                Text(text = "Where is the patient going?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onSearchPatient, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (patientLocation != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.secondary)) {
                        Icon(Icons.Default.Person, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (patientLocation != null) "Patient Set" else "Find Patient")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onSearchHospital, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (hospitalLocation != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.secondary)) {
                        Icon(Icons.Default.LocalHospital, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (hospitalLocation != null) "Hospital Set" else "Set Hospital")
                    }
                }
                if (patientLocation != null && hospitalLocation != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onStartTrip, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                        Text("START EMERGENCY TRIP", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("ETA to Hospital", style = MaterialTheme.typography.bodySmall)
                        Text(eta, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.Red)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Distance", style = MaterialTheme.typography.bodySmall)
                        Text(distance, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun PatientDetailsDialog(onDismiss: () -> Unit, onSubmit: (Map<String, String>) -> Unit) {
    var age by remember { mutableStateOf("") }
    var selectedCondition by remember { mutableStateOf("Heart Attack") }
    var selectedSeverity by remember { mutableStateOf("Critical") }
    var notes by remember { mutableStateOf("") }
    val conditions = listOf("Heart Attack", "Heavy Blood Loss", "Fracture", "Stroke", "Accident Trauma", "Breathing Difficulty", "Other Emergency")
    val severities = listOf("Low", "Medium", "Critical")

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text("Patient Emergency Details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("Patient Age") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
                Text("Emergency Condition", fontWeight = FontWeight.Bold)
                conditions.forEach { condition ->
                    Row(modifier = Modifier.fillMaxWidth().selectable(selected = (condition == selectedCondition), onClick = { selectedCondition = condition }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = (condition == selectedCondition), onClick = { selectedCondition = condition })
                        Text(condition)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("Severity Level", fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    severities.forEach { severity ->
                        FilterChip(selected = (severity == selectedSeverity), onClick = { selectedSeverity = severity }, label = { Text(severity) })
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Additional Notes") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { onSubmit(mapOf("age" to age, "condition" to selectedCondition, "severity" to selectedSeverity)) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text("DONE - START TRIP")
                }
            }
        }
    }
}

@Composable
fun SmallFloatingTopBar(onLogout: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
        FloatingActionButton(onClick = onLogout, containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Logout")
        }
    }
}
