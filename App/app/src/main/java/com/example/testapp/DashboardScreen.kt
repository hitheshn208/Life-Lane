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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
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
    
    // Map Selection States
    var isMapSelectionMode by remember { mutableStateOf(false) }
    
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
        val pLoc = patientLocation
        val hLoc = hospitalLocation
        if (pLoc != null && hLoc != null) {
            val distanceMoved = lastCalcLocation?.distanceToAsDouble(ambulanceLocation) ?: Double.MAX_VALUE
            if (distanceMoved > 50 || lastCalcLocation == null) {
                withContext(Dispatchers.IO) {
                    try {
                        val urlString = "https://router.project-osrm.org/route/v1/driving/" +
                                "${ambulanceLocation.longitude},${ambulanceLocation.latitude};" +
                                "${pLoc.longitude},${pLoc.latitude};" +
                                "${hLoc.longitude},${hLoc.latitude}" +
                                "?overview=full&geometries=geojson"
                        
                        val url = URL(urlString)
                        val conn = url.openConnection() as HttpURLConnection
                        conn.setRequestProperty("User-Agent", "AmbulanceApp/1.0")
                        val response = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(response)
                        val routes = json.getJSONArray("routes")
                        
                        if (routes.length() > 0) {
                            val route = routes.getJSONObject(0)
                            
                            val geometry = route.getJSONObject("geometry")
                            val coordinates = geometry.getJSONArray("coordinates")
                            val allPoints = mutableListOf<GeoPoint>()
                            for (i in 0 until coordinates.length()) {
                                val coord = coordinates.getJSONArray(i)
                                allPoints.add(GeoPoint(coord.getDouble(1), coord.getDouble(0)))
                            }

                            val patientIdx = findClosestPointIndex(allPoints, pLoc)
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

        // Map Selection Mode UI
        if (isMapSelectionMode) {
            // Fixed Center Pin (Rapido Style)
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).offset(y = (-28).dp),
                        tint = if (searchType == "patient") Color.Red else Color(0xFF4CAF50)
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color.Black.copy(alpha = 0.2f), CircleShape)
                    )
                }
            }

            // Confirm Button for Map Selection
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(24.dp),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Move map to set ${searchType} location",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { isMapSelectionMode = false },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val center = mapView.mapCenter as GeoPoint
                                if (searchType == "patient") {
                                    patientLocation = center
                                } else {
                                    hospitalLocation = center
                                }
                                isMapSelectionMode = false
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (searchType == "patient") Color.Red else Color(0xFF4CAF50)
                            )
                        ) {
                            Text("Confirm Location")
                        }
                    }
                }
            }
        } else {
            // Normal Dashboard UI
            SmallFloatingTopBar(onLogout = onLogout)

            FloatingActionButton(
                onClick = {
                    mapView.controller.animateTo(ambulanceLocation)
                    mapView.controller.setZoom(17.0)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 300.dp, end = 16.dp), // Increased padding to avoid hiding behind ControlPanel
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
        }

        if (showSearchOverlay) {
            LocationSearchOverlay(
                type = searchType,
                sharedPref = sharedPref,
                onLocationSelected = { point, name ->
                    if (searchType == "patient") {
                        // For patient: pick a suggestion and then allow fine-tuning on the map
                        mapView.controller.setCenter(point)
                        isMapSelectionMode = true
                    } else {
                        hospitalLocation = point
                        val recents = sharedPref.getStringSet("recent_hospitals", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                        recents.add(name)
                        sharedPref.edit {
                            putStringSet("recent_hospitals", recents)
                        }
                        mapView.controller.animateTo(point)
                    }
                    showSearchOverlay = false
                },
                onSelectOnMap = {
                    showSearchOverlay = false
                    // If patient location already exists, start selection at that point for editing
                    if (searchType == "patient" && patientLocation != null) {
                        mapView.controller.setCenter(patientLocation)
                    }
                    isMapSelectionMode = true
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
                    
                    val pLoc = patientLocation
                    val hLoc = hospitalLocation
                    if (pLoc != null && hLoc != null) {
                        sendTripDataToServer(
                            vehicleId = vehicleId,
                            patientLoc = pLoc,
                            hospitalLoc = hLoc,
                            details = details
                        )
                    }
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

// Optimized Fuzzy Search logic
fun fuzzyMatch(query: String, target: String): Boolean {
    val q = query.lowercase().trim()
    val t = target.lowercase().trim()
    if (q.isEmpty()) return false
    if (t.contains(q)) return true
    
    // Simple edit distance (allow 1-2 character mistakes depending on length)
    val maxErrors = if (q.length > 5) 2 else 1
    return levenshteinDistance(q, t.take(q.length + 1)) <= maxErrors
}

fun levenshteinDistance(s1: String, s2: String): Int {
    val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
    for (i in 0..s1.length) dp[i][0] = i
    for (j in 0..s2.length) dp[0][j] = j
    for (i in 1..s1.length) {
        for (j in 1..s2.length) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
        }
    }
    return dp[s1.length][s2.length]
}

@Composable
fun LocationSearchOverlay(
    type: String,
    sharedPref: android.content.SharedPreferences,
    onLocationSelected: (GeoPoint, String) -> Unit,
    onSelectOnMap: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Pair<String, GeoPoint>>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    
    // Bangalore Specific Data
    val bangaloreBounds = "77.3,12.7,77.9,13.2" // viewbox for Bangalore
    val localPlaces = remember {
        listOf(
            "Majestic (KSR Bengaluru Station)" to GeoPoint(12.9733, 77.5736),
            "Mathikere" to GeoPoint(13.0324, 77.5591),
            "Madavara" to GeoPoint(13.0524, 77.4721),
            "Indiranagar" to GeoPoint(12.9719, 77.6412),
            "Koramangala" to GeoPoint(12.9352, 77.6245),
            "Jayanagar" to GeoPoint(12.9250, 77.5938),
            "Whitefield" to GeoPoint(12.9698, 77.7500),
            "Electronic City" to GeoPoint(12.8452, 77.6635),
            "Hebbal" to GeoPoint(13.0354, 77.5988),
            "Yeshwanthpur" to GeoPoint(13.0235, 77.5566),
            "Malleshwaram" to GeoPoint(12.9917, 77.5712),
            "MG Road" to GeoPoint(12.9733, 77.6117),
            "Shivajinagar" to GeoPoint(12.9857, 77.5971),
            "KR Market" to GeoPoint(12.9657, 77.5753),
            "Banashankari" to GeoPoint(12.9254, 77.5468),
            "BTM Layout" to GeoPoint(12.9166, 77.6101),
            "HSR Layout" to GeoPoint(12.9121, 77.6446),
            "Yelahanka" to GeoPoint(13.1007, 77.5963),
            "RR Nagar" to GeoPoint(12.9226, 77.5174),
            "Vidyaranyapura" to GeoPoint(13.0763, 77.5568)
        )
    }

    val localHospitals = remember {
        listOf(
            "Manipal Hospital, Old Airport Road" to GeoPoint(12.9592, 77.6444),
            "Apollo Hospital, Bannerghatta" to GeoPoint(12.8961, 77.5985),
            "Fortis Hospital, Cunningham Road" to GeoPoint(12.9892, 77.5933),
            "Victoria Hospital, City Market" to GeoPoint(12.9642, 77.5746),
            "Narayana Health, Electronic City" to GeoPoint(12.8252, 77.6800),
            "St. John's Medical College Hospital" to GeoPoint(12.9322, 77.6194),
            "Columbia Asia, Hebbal" to GeoPoint(13.0354, 77.5971),
            "Aster CMI Hospital, Sahakara Nagar" to GeoPoint(13.0538, 77.5919),
            "Sagar Hospitals, Jayanagar" to GeoPoint(12.9287, 77.5911),
            "MS Ramaiah Memorial Hospital" to GeoPoint(13.0322, 77.5647)
        )
    }

    LaunchedEffect(query) {
        if (query.isNotEmpty()) {
            // 1. Instant Local Fuzzy Search
            val filteredLocal = if (type == "hospital") {
                localHospitals.filter { fuzzyMatch(query, it.first) }
            } else {
                (localPlaces + localHospitals).filter { fuzzyMatch(query, it.first) }
            }
            
            searchResults = filteredLocal

            // 2. Network Search
            if (query.length > 2) {
                delay(600) 
                isSearching = true
                withContext(Dispatchers.IO) {
                    try {
                        val encodedQuery = URLEncoder.encode(if (type == "hospital") "$query hospital" else query, "UTF-8")
                        val url = URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=10&viewbox=$bangaloreBounds&bounded=1")
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
                            val existingNames = searchResults.map { it.first.lowercase() }
                            val newResults = results.filter { it.first.lowercase() !in existingNames }
                            searchResults = searchResults + newResults
                            isSearching = false
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { isSearching = false }
                    }
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
                    // "Select on Map" Option - Only for Patient
                    if (type == "patient") {
                        item {
                            ListItem(
                                headlineContent = { Text("Set location on map", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                                leadingContent = { Icon(Icons.Default.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { onSelectOnMap() }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }

                    if (query.isEmpty()) {
                        val recents = sharedPref.getStringSet("recent_hospitals", setOf())?.toList() ?: listOf()
                        if (type == "hospital" && recents.isNotEmpty()) {
                            item { Text("Recent Hospitals", modifier = Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                            items(recents) { name ->
                                ListItem(headlineContent = { Text(name) }, leadingContent = { Icon(Icons.Default.History, contentDescription = null) },
                                    modifier = Modifier.clickable { 
                                        val point = localHospitals.find { it.first == name }?.second ?: GeoPoint(12.97, 77.59)
                                        onLocationSelected(point, name) 
                                    })
                            }
                        }
                        item { Text("Common Bangalore Places", modifier = Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                        val defaultList = if (type == "hospital") localHospitals else localPlaces
                        items(defaultList) { (name, point) ->
                            ListItem(headlineContent = { Text(name) }, leadingContent = { Icon(if (type == "hospital") Icons.Default.LocalHospital else Icons.Default.Place, contentDescription = null) },
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
    val size = 120
    val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.applyCanvas {
        val paint = Paint().apply { this.color = color; isAntiAlias = true }
        
        // Draw Pin (Teardrop shape)
        val path = android.graphics.Path()
        path.moveTo(size/2f, size.toFloat())
        path.cubicTo(0f, size/2f, size/4f, 0f, size/2f, 0f)
        path.cubicTo(3*size/4f, 0f, size.toFloat(), size/2f, size/2f, size.toFloat())
        drawPath(path, paint)

        // Draw white circle in middle
        paint.color = android.graphics.Color.WHITE
        drawCircle(size / 2f, size / 3.5f, size / 6f, paint)
        
        // Draw Label
        paint.apply { this.color = color; textSize = 32f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        drawText(label, size / 2f, size / 3.5f + 12f, paint)
    }
    return bitmap.toDrawable(context.resources)
}

fun createCurrentLocationIcon(context: Context): Drawable {
    val size = 100
    val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.applyCanvas {
        val paint = Paint().apply { isAntiAlias = true }
        // White background circle
        paint.color = android.graphics.Color.WHITE
        drawCircle(size / 2f, size / 2f, size / 2f, paint)
        
        // Red border
        paint.color = "#FF0000".toColorInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        drawCircle(size / 2f, size / 2f, (size / 2f) - 2f, paint)

        // Draw the ambulance emoji
        paint.style = Paint.Style.FILL
        paint.textSize = 60f
        paint.textAlign = Paint.Align.CENTER
        val xPos = size / 2f
        val yPos = (size / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        drawText("🚑", xPos, yPos, paint)
    }
    return bitmap.toDrawable(context.resources)
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
            // Use Bottom-Center anchor for Pins so they don't "drift" when zooming
            pMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            pMarker.icon = createMarkerIcon(context, android.graphics.Color.RED, "P")
            mv.overlays.add(pMarker)
        }

        hospitalLoc?.let {
            val hMarker = Marker(mv)
            hMarker.position = it
            hMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            hMarker.icon = createMarkerIcon(context, "#4CAF50".toColorInt(), "H")
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
            line.outlinePaint.color = "#4CAF50".toColorInt()
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp, end = 16.dp), // Moved down for better accessibility
        horizontalArrangement = Arrangement.End
    ) {
        ExtendedFloatingActionButton(
            onClick = onLogout,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(16.dp),
            icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
            text = { Text("Logout") }
        )
    }
}
