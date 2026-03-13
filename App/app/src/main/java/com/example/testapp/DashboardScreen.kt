package com.example.testapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
fun DashboardScreen(vehicleId: String = "", authToken: String = "", onLogout: () -> Unit = {}) {
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
                    } catch (_: Exception) {
                        // Ignore error
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

        // Dark Gradient for Status Bar Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent)
                    )
                )
        )

        // Map Selection Mode UI
        AnimatedVisibility(
            visible = isMapSelectionMode,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Fixed Center Pin (Rapido Style)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .offset(y = (-28).dp),
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
                    elevation = CardDefaults.cardElevation(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Set ${searchType.uppercase()} Location",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = Color.Black
                        )
                        Text(
                            text = "Drag the map to pinpoint exactly",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { isMapSelectionMode = false },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(12.dp))
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
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (searchType == "patient") Color.Red else Color(0xFF4CAF50)
                                )
                            ) {
                                Text("Confirm", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (!isMapSelectionMode) {
            // Header Section
            SmallFloatingTopBar(onLogout = onLogout)

            // Zoom Buttons & MyLocation (Floating)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = if (isTripStarted) 180.dp else 300.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(
                    onClick = { mapView.controller.zoomIn() },
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom In")
                }
                FloatingActionButton(
                    onClick = { mapView.controller.zoomOut() },
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom Out")
                }
                FloatingActionButton(
                    onClick = {
                        mapView.controller.animateTo(ambulanceLocation)
                        mapView.controller.setZoom(17.0)
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Center Map")
                }
            }

            // Bottom Panel
            AnimatedContent(
                targetState = isTripStarted,
                transitionSpec = {
                    slideInVertically(initialOffsetY = { it }) + fadeIn() togetherWith
                    slideOutVertically(targetOffsetY = { it }) + fadeOut()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                label = "BottomPanel"
            ) { tripStarted ->
                Box(modifier = Modifier.padding(16.dp)) {
                    ControlPanel(
                        isTripStarted = tripStarted,
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
        }

        if (showSearchOverlay) {
            LocationSearchOverlay(
                type = searchType,
                sharedPref = sharedPref,
                onLocationSelected = { point, name ->
                    if (searchType == "patient") {
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
                            authToken = authToken,
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
    authToken: String,
    patientLoc: GeoPoint,
    hospitalLoc: GeoPoint,
    details: Map<String, String>
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val serverUrl = "${Config.BASE_URL}/api/trips/start"
            val url = URL(serverUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (authToken.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $authToken")
            }

            val json = JSONObject()
            json.put("vehicle_number", vehicleId)
            json.put("patient_lat", patientLoc.latitude)
            json.put("patient_lon", patientLoc.longitude)
            json.put("hospital_lat", hospitalLoc.latitude)
            json.put("hospital_lon", hospitalLoc.longitude)
            json.put("patient_age", details["age"])
            json.put("condition", details["condition"])
            json.put("severity", details["severity"])

            conn.outputStream.use { it.write(json.toString().toByteArray()) }
            conn.responseCode
        } catch (_: Exception) {
            // Ignore error
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
    
    val bangaloreBounds = "77.3,12.7,77.9,13.2"
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
            val filteredLocal = if (type == "hospital") {
                localHospitals.filter { fuzzyMatch(query, it.first) }
            } else {
                (localPlaces + localHospitals).filter { fuzzyMatch(query, it.first) }
            }
            
            searchResults = filteredLocal

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
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) { isSearching = false }
                    }
                }
            }
        } else {
            searchResults = emptyList()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.Black) }
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(if (type == "patient") "Search pickup location..." else "Search hospital...") },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        textStyle = TextStyle(color = Color.Black)
                    )
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Black) }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color.LightGray)
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (type == "patient") {
                        item {
                            ListItem(
                                headlineContent = { Text("Pick from Map", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                                leadingContent = { Icon(Icons.Default.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { onSelectOnMap() },
                                colors = ListItemDefaults.colors(containerColor = Color.White)
                            )
                            HorizontalDivider(modifier = Modifier.alpha(0.5f), color = Color.LightGray)
                        }
                    }

                    if (query.isEmpty()) {
                        val recents = sharedPref.getStringSet("recent_hospitals", setOf())?.toList() ?: listOf()
                        if (type == "hospital" && recents.isNotEmpty()) {
                            item { Text("Recent", modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp), style = MaterialTheme.typography.labelMedium, color = Color.Gray) }
                            items(recents) { name ->
                                ListItem(
                                    headlineContent = { Text(name, color = Color.Black) },
                                    leadingContent = { Icon(Icons.Default.History, contentDescription = null, tint = Color.Gray) },
                                    modifier = Modifier.clickable { 
                                        val point = localHospitals.find { it.first == name }?.second ?: GeoPoint(12.97, 77.59)
                                        onLocationSelected(point, name) 
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.White))
                            }
                        }
                        item { Text("Suggestions", modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp), style = MaterialTheme.typography.labelMedium, color = Color.Gray) }
                        val defaultList = if (type == "hospital") localHospitals else localPlaces
                        items(defaultList) { (name, point) ->
                            ListItem(
                                headlineContent = { Text(name, color = Color.Black) },
                                leadingContent = { Icon(if (type == "hospital") Icons.Default.LocalHospital else Icons.Default.Place, contentDescription = null, tint = Color.Gray) },
                                modifier = Modifier.clickable { onLocationSelected(point, name) },
                                colors = ListItemDefaults.colors(containerColor = Color.White)
                            )
                        }
                    } else {
                        items(searchResults) { (name, point) ->
                            ListItem(
                                headlineContent = { Text(name, maxLines = 2, color = Color.Black) },
                                leadingContent = { Icon(if (type == "hospital") Icons.Default.LocalHospital else Icons.Default.Place, contentDescription = null, tint = Color.Gray) },
                                modifier = Modifier.clickable { onLocationSelected(point, name) },
                                colors = ListItemDefaults.colors(containerColor = Color.White)
                            )
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
        
        val path = android.graphics.Path()
        path.moveTo(size/2f, size.toFloat())
        path.cubicTo(0f, size/2f, size/4f, 0f, size/2f, 0f)
        path.cubicTo(3*size/4f, 0f, size.toFloat(), size/2f, size/2f, size.toFloat())
        drawPath(path, paint)

        paint.color = android.graphics.Color.WHITE
        drawCircle(size / 2f, size / 3.5f, size / 6f, paint)
        
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
        paint.color = android.graphics.Color.WHITE
        drawCircle(size / 2f, size / 2f, (size / 2f) - 5f, paint)
        
        paint.color = "#FF0000".toColorInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        drawCircle(size / 2f, size / 2f, (size / 2f) - 5f, paint)

        paint.style = Paint.Style.FILL
        paint.textSize = 50f
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            if (!isTripStarted) {
                Text(
                    text = "Emergency Trip Plan",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1C1E)
                )
                Spacer(modifier = Modifier.height(20.dp))
                
                // Destination Selectors
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LocationSelector(
                        label = "Patient Location",
                        value = if (patientLocation != null) "Location Set" else "Set pickup point",
                        isSet = patientLocation != null,
                        icon = Icons.Default.Person,
                        color = Color.Red,
                        onClick = onSearchPatient
                    )
                    LocationSelector(
                        label = "Hospital Destination",
                        value = if (hospitalLocation != null) "Hospital Set" else "Select hospital",
                        isSet = hospitalLocation != null,
                        icon = Icons.Default.LocalHospital,
                        color = Color(0xFF4CAF50),
                        onClick = onSearchHospital
                    )
                }
                
                if (patientLocation != null && hospitalLocation != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onStartTrip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                    ) {
                        Text(
                            "START EMERGENCY",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(Icons.Default.FlashOn, contentDescription = null)
                    }
                }
            } else {
                // Trip Active UI
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "ESTIMATED ARRIVAL",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            letterSpacing = 1.sp
                        )
                        Text(
                            eta,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.Red
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Surface(
                            color = Color(0xFFF1F3F4),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                distance,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = Color.Red,
                    trackColor = Color(0xFFF1F3F4)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Emergency,
                        contentDescription = null,
                        tint = Color.Red,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Trip Active - Avoid Heavy Traffic",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
fun LocationSelector(
    label: String,
    value: String,
    isSet: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isSet) color.copy(alpha = 0.08f) else Color(0xFFF8F9FA),
        border = if (isSet) androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.2f)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (isSet) color else Color.White,
                shadowElevation = if (isSet) 0.dp else 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (isSet) Color.White else color
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSet) Color.Black else Color.Gray
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                if (isSet) Icons.Default.CheckCircle else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (isSet) color else Color.LightGray
            )
        }
    }
}

@Composable
fun PatientDetailsDialog(onDismiss: () -> Unit, onSubmit: (Map<String, String>) -> Unit) {
    var age by remember { mutableStateOf("") }
    var selectedCondition by remember { mutableStateOf("Heart Attack") }
    var selectedSeverity by remember { mutableStateOf("Critical") }
    val conditions = listOf("Heart Attack", "Heavy Blood Loss", "Fracture", "Stroke", "Accident Trauma", "Breathing Difficulty", "Other Emergency")
    val severities = listOf("Low", "Medium", "Critical")

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(12.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Patient Details",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(24.dp))
                
                OutlinedTextField(
                    value = age,
                    onValueChange = { age = it },
                    label = { Text("Patient Age") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    ),
                    textStyle = TextStyle(color = Color.Black)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                Text("Emergency Type", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = Color.Black)
                Spacer(modifier = Modifier.height(12.dp))
                
                conditions.forEach { condition ->
                    Surface(
                        onClick = { selectedCondition = condition },
                        shape = RoundedCornerShape(12.dp),
                        color = if (condition == selectedCondition) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = (condition == selectedCondition), onClick = { selectedCondition = condition })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(condition, color = Color.Black)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                Text("Severity Level", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = Color.Black)
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    severities.forEach { severity ->
                        FilterChip(
                            selected = (severity == selectedSeverity),
                            onClick = { selectedSeverity = severity },
                            label = { Text(severity) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { onSubmit(mapOf("age" to age, "condition" to selectedCondition, "severity" to selectedSeverity)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("START TRIP", fontWeight = FontWeight.Bold)
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
            .padding(start = 16.dp, top = 50.dp, end = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status Badge
        Surface(
            color = Color.Black.copy(alpha = 0.7f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.shadow(8.dp, RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color.Green, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("ONLINE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        
        ExtendedFloatingActionButton(
            onClick = onLogout,
            containerColor = Color.White,
            contentColor = Color.Black,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.size(width = 120.dp, height = 44.dp),
            icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp)) },
            text = { Text("Logout", fontSize = 14.sp) }
        )
    }
}
