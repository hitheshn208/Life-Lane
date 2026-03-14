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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
fun DashboardScreen(vehicleId: String = "", authToken: String = "", driverId: String = "", onBackToSetup: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val sharedPref = remember { context.getSharedPreferences("testapp_prefs", Context.MODE_PRIVATE) }
    
    val tripManager = remember(vehicleId) { TripManager(vehicleId) }

    // State to track the height of the bottom panel
    var bottomPanelHeight by remember { mutableStateOf(0.dp) }
    val animatedBottomPadding by animateDpAsState(targetValue = bottomPanelHeight, label = "fab_padding")

    // Keep screen on when Dashboard is visible
    DisposableEffect(Unit) {
        val window = (context as? ComponentActivity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

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

    // WebSocket Integration: Start/Stop trip updates
    LaunchedEffect(isTripStarted) {
        if (isTripStarted) {
            tripManager.setMapView(mapView)
            tripManager.startTrip()
        } else {
            tripManager.stopTrip()
        }
    }

    // WebSocket Integration: Keep location & ETA synced
    LaunchedEffect(ambulanceLocation, eta) {
        if (isTripStarted) {
            val etaMinutes = eta.split(" ")[0].toIntOrNull() ?: 0
            tripManager.updateLocation(ambulanceLocation.latitude, ambulanceLocation.longitude, etaMinutes)
        }
    }

    // Faster Routing Logic
    LaunchedEffect(ambulanceLocation, patientLocation, hospitalLocation) {
        val pLoc = patientLocation ?: ambulanceLocation 
        val hLoc = hospitalLocation
        if (hLoc != null) {
            val distanceMoved = lastCalcLocation?.distanceToAsDouble(ambulanceLocation) ?: Double.MAX_VALUE
            if (distanceMoved > 50 || lastCalcLocation == null) {
                withContext(Dispatchers.IO) {
                    try {
                        val urlString = if (patientLocation != null) {
                            "https://router.project-osrm.org/route/v1/driving/" +
                            "${ambulanceLocation.longitude},${ambulanceLocation.latitude};" +
                            "${patientLocation!!.longitude},${patientLocation!!.latitude};" +
                            "${hLoc.longitude},${hLoc.latitude}" +
                            "?overview=full&geometries=geojson"
                        } else {
                            "https://router.project-osrm.org/route/v1/driving/" +
                            "${ambulanceLocation.longitude},${ambulanceLocation.latitude};" +
                            "${hLoc.longitude},${hLoc.latitude}" +
                            "?overview=full&geometries=geojson"
                        }
                        
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

                            if (patientLocation != null) {
                                val patientIdx = findClosestPointIndex(allPoints, patientLocation!!)
                                val pPoints = allPoints.subList(0, patientIdx + 1).toList()
                                val hPoints = allPoints.subList(patientIdx, allPoints.size).toList()
                                withContext(Dispatchers.Main) {
                                    patientRoutePoints = pPoints
                                    hospitalRoutePoints = hPoints
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    patientRoutePoints = emptyList()
                                    hospitalRoutePoints = allPoints
                                }
                            }
                            
                            val distTotal = route.getDouble("distance") / 1000.0
                            val timeTotal = route.getDouble("duration") / 60.0
                            
                            withContext(Dispatchers.Main) {
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
            SmallFloatingTopBar(onBack = {
                scope.launch {
                    // Send deactivation request to server when Back is clicked
                    ApiService.deactivateTrip(authToken, driverId, vehicleId)
                    onBackToSetup()
                }
            })

            // Dynamic Action Buttons: Positioned relative to bottom panel height
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = animatedBottomPadding + 80.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
            }

            // Bottom Panel with Navigation Bar Insets
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 64.dp)
                    .onGloballyPositioned { coordinates ->
                        bottomPanelHeight = with(density) { coordinates.size.height.toDp() }
                    }
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
                patientLoc = patientLocation,
                ambulanceLoc = ambulanceLocation,
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
                    val pLoc = patientLocation ?: ambulanceLocation
                    val hLoc = hospitalLocation
                    if (hLoc != null) {
                        // Extract ETA number from string "XX mins"
                        val etaMinutes = eta.split(" ")[0].toIntOrNull() ?: 0
                        val severityValue = details["severity"]?.uppercase() ?: "STABLE"
                        
                        scope.launch {
                            try {
                                val result = ApiService.startTrip(
                                    token = authToken,
                                    vehicleNumber = vehicleId,
                                    patientLoc = pLoc,
                                    hospitalLoc = hLoc,
                                    severity = severityValue,
                                    etaMinutes = etaMinutes
                                )
                                
                                if (result.isSuccess) {
                                    tripManager.setTripId(result.tripId!!)
                                    showPatientDialog = false
                                    isTripStarted = true
                                    Toast.makeText(context, "Trip started successfully!", Toast.LENGTH_SHORT).show()
                                } else {
                                    // FORCE START FOR TESTING IF SERVER FAILS (Optional - remove if not needed)
                                    // isTripStarted = true
                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Connection Error: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            )
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
    patientLoc: GeoPoint?,
    ambulanceLoc: GeoPoint,
    onLocationSelected: (GeoPoint, String) -> Unit,
    onSelectOnMap: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Triple<String, GeoPoint, String>>>(emptyList()) }
    var nearbyHospitals by remember { mutableStateOf<List<Triple<String, GeoPoint, String>>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var isFetchingNearby by remember { mutableStateOf(false) }
    
    val centerLoc = patientLoc ?: ambulanceLoc
    val biasBounds = remember(centerLoc) {
        val lat = centerLoc.latitude
        val lon = centerLoc.longitude
        "${lon - 0.15},${lat - 0.15},${lon + 0.15},${lat + 0.15}"
    }

    val bangaloreAreas = remember {
        listOf(
            "Majestic" to GeoPoint(12.9733, 77.5736),
            "Mathikere" to GeoPoint(13.0324, 77.5591),
            "Madavara" to GeoPoint(13.0524, 77.4721),
            "Peenya" to GeoPoint(13.0285, 77.5197),
            "Palace Grounds" to GeoPoint(13.0035, 77.5891),
            "Indiranagar" to GeoPoint(12.9719, 77.6412),
            "Koramangala" to GeoPoint(12.9352, 77.6245),
            "Jayanagar" to GeoPoint(12.9250, 77.5938),
            "Whitefield" to GeoPoint(12.9698, 77.7500),
            "Electronic City" to GeoPoint(12.8452, 77.6635),
            "Marathahalli" to GeoPoint(12.9569, 77.7011),
            "Hebbal" to GeoPoint(13.0354, 77.5988),
            "Malleshwaram" to GeoPoint(12.9917, 77.5712),
            "Banashankari" to GeoPoint(12.9254, 77.5468),
            "RR Nagar" to GeoPoint(12.9226, 77.5174),
            "Yelahanka" to GeoPoint(13.1007, 77.5963)
        )
    }

    LaunchedEffect(centerLoc, type) {
        if (type == "hospital") {
            isFetchingNearby = true
            withContext(Dispatchers.IO) {
                try {
                    val url = URL("https://nominatim.openstreetmap.org/search?q=hospital&format=json&limit=15&viewbox=${centerLoc.longitude-0.08},${centerLoc.latitude-0.08},${centerLoc.longitude+0.08},${centerLoc.latitude+0.08}&bounded=1")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "AmbulanceApp/1.0")
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(response)
                    val results = mutableListOf<Triple<String, GeoPoint, String>>()
                    
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val name = item.getString("display_name")
                        val p = GeoPoint(item.getDouble("lat"), item.getDouble("lon"))
                        
                        var roadDistStr = String.format(Locale.US, "%.1f km", centerLoc.distanceToAsDouble(p)/1000.0)
                        try {
                            val osrmUrl = URL("https://router.project-osrm.org/route/v1/driving/${centerLoc.longitude},${centerLoc.latitude};${p.longitude},${p.latitude}?overview=false")
                            val osrmResponse = osrmUrl.openConnection().inputStream.bufferedReader().use { it.readText() }
                            val roadDist = JSONObject(osrmResponse).getJSONArray("routes").getJSONObject(0).getDouble("distance") / 1000.0
                            roadDistStr = String.format(Locale.US, "%.1f km via road", roadDist)
                        } catch (_: Exception) {}
                        
                        results.add(Triple(name, p, roadDistStr))
                    }
                    withContext(Dispatchers.Main) { 
                        nearbyHospitals = results.sortedBy { it.second.distanceToAsDouble(centerLoc) }
                        isFetchingNearby = false 
                    }
                } catch (_: Exception) { withContext(Dispatchers.Main) { isFetchingNearby = false } }
            }
        }
    }

    LaunchedEffect(query) {
        if (query.isNotEmpty()) {
            val q = query.lowercase().trim()
            val localResults = (if (type == "hospital") emptyList() else bangaloreAreas).filter { it.first.lowercase().contains(q) }.sortedBy { !it.first.lowercase().startsWith(q) }.map { Triple(it.first, it.second, "") }
            searchResults = localResults
            delay(400); isSearching = true
            withContext(Dispatchers.IO) {
                try {
                    val url = "https://nominatim.openstreetmap.org/search?q=${URLEncoder.encode(if (type=="hospital") "$query hospital" else query, "UTF-8")}&format=json&limit=10&viewbox=$biasBounds&bounded=1"
                    val response = URL(url).openConnection().apply { setRequestProperty("User-Agent", "AmbulanceApp/1.0") }.inputStream.bufferedReader().use { it.readText() }
                    val results = mutableListOf<Triple<String, GeoPoint, String>>()
                    val jsonArray = JSONArray(response)
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        results.add(Triple(item.getString("display_name"), GeoPoint(item.getDouble("lat"), item.getDouble("lon")), ""))
                    }
                    withContext(Dispatchers.Main) {
                        val existingNames = searchResults.map { it.first.lowercase() }
                        searchResults = searchResults + results.filter { it.first.lowercase() !in existingNames }
                        isSearching = false
                    }
                } catch (_: Exception) { withContext(Dispatchers.Main) { isSearching = false } }
            }
        } else { searchResults = emptyList() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars), color = Color.White) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.Black) }
                    TextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text(if (type == "patient") "Search pickup location..." else "Search hospital...") },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(focusedTextColor = Color.Black, unfocusedTextColor = Color.Black, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        singleLine = true, textStyle = TextStyle(color = Color.Black)
                    )
                    if (isSearching) { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
                    else if (query.isNotEmpty()) { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Black) } }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color.LightGray)
                if (isFetchingNearby && query.isEmpty()) { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (type == "patient") {
                        item {
                            ListItem(headlineContent = { Text("Pick from Map", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                                leadingContent = { Icon(Icons.Default.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { onSelectOnMap() }, colors = ListItemDefaults.colors(containerColor = Color.White))
                            HorizontalDivider(modifier = Modifier.alpha(0.5f), color = Color.LightGray)
                        }
                    }
                    if (query.isEmpty()) {
                        if (type == "hospital") {
                            item { Text("Hospitals Near " + (if (centerLoc == patientLoc) "Patient" else "You"), modifier = Modifier.padding(16.dp, 12.dp), style = MaterialTheme.typography.labelMedium, color = Color.Gray) }
                            items(nearbyHospitals) { (name, point, dist) ->
                                ListItem(headlineContent = { Text(name, color = Color.Black, maxLines = 2) }, leadingContent = { Icon(Icons.Default.LocalHospital, contentDescription = null, tint = Color(0xFF4CAF50)) }, supportingContent = { Text(dist, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }, modifier = Modifier.clickable { onLocationSelected(point, name) }, colors = ListItemDefaults.colors(containerColor = Color.White))
                            }
                        }
                    } else {
                        items(searchResults) { (name, point, _) ->
                            ListItem(headlineContent = { Text(name, maxLines = 3, color = Color.Black) },
                                leadingContent = { Icon(if (type == "hospital") Icons.Default.LocalHospital else Icons.Default.Place, contentDescription = null, tint = Color.Gray) },
                                modifier = Modifier.clickable { onLocationSelected(point, name) }, colors = ListItemDefaults.colors(containerColor = Color.White))
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
                Text(text = "Emergency Trip Plan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF1A1C1E))
                Spacer(modifier = Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LocationSelector(label = "Patient Location", value = if (patientLocation != null) "Location Set" else "Set pickup point", isSet = patientLocation != null, icon = Icons.Default.Person, color = Color.Red, onClick = onSearchPatient)
                    LocationSelector(label = "Hospital Destination", value = if (hospitalLocation != null) "Hospital Set" else "Select hospital", isSet = hospitalLocation != null, icon = Icons.Default.LocalHospital, color = Color(0xFF4CAF50), onClick = onSearchHospital)
                }
                if (hospitalLocation != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onStartTrip, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red), elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)) {
                        Text("START EMERGENCY", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(Icons.Default.FlashOn, contentDescription = null)
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("ESTIMATED ARRIVAL", style = MaterialTheme.typography.labelSmall, color = Color.Gray, letterSpacing = 1.sp)
                        Text(eta, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = Color.Red)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Surface(color = Color(0xFFF1F3F4), shape = RoundedCornerShape(12.dp)) {
                            Text(distance, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
                if (eta == "0 mins") {
                    Spacer(modifier = Modifier.height(20.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = Color.Red, trackColor = Color(0xFFF1F3F4))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Emergency, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Trip Active - Avoid Heavy Traffic", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun LocationSelector(label: String, value: String, isSet: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = if (isSet) color.copy(alpha = 0.08f) else Color(0xFFF8F9FA), border = if (isSet) androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.2f)) else null) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = if (isSet) color else Color.White, shadowElevation = if (isSet) 0.dp else 2.dp) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (isSet) Color.White else color) }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = if (isSet) Color.Black else Color.Gray)
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(if (isSet) Icons.Default.CheckCircle else Icons.Default.ChevronRight, contentDescription = null, tint = if (isSet) color else Color.LightGray)
        }
    }
}

@Composable
fun PatientDetailsDialog(onDismiss: () -> Unit, onSubmit: (Map<String, String>) -> Unit) {
    var age by remember { mutableStateOf("") }
    var selectedCondition by remember { mutableStateOf("Heart Attack") }
    var selectedSeverity by remember { mutableStateOf("STABLE") }
    val conditions = listOf("Heart Attack", "Heavy Blood Loss", "Fracture", "Stroke", "Accident Trauma", "Breathing Difficulty", "Other Emergency")
    val severities = listOf("STABLE", "MODERATE", "CRITICAL")
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)) {
            Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Text("Patient Details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("Patient Age") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.Black, unfocusedTextColor = Color.Black, focusedContainerColor = Color.White, unfocusedContainerColor = Color.White), textStyle = TextStyle(color = Color.Black))
                Spacer(modifier = Modifier.height(24.dp))
                Text("Emergency Type", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = Color.Black)
                Spacer(modifier = Modifier.height(12.dp))
                conditions.forEach { condition ->
                    Surface(onClick = { selectedCondition = condition }, shape = RoundedCornerShape(12.dp), color = if (condition == selectedCondition) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        FilterChip(selected = (severity == selectedSeverity), onClick = { selectedSeverity = severity }, label = { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(text = severity, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) } }, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = { onSubmit(mapOf("age" to age, "condition" to selectedCondition, "severity" to selectedSeverity)) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("START TRIP", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun SmallFloatingTopBar(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 50.dp, end = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(20.dp), modifier = Modifier.shadow(8.dp, RoundedCornerShape(20.dp))) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(Color.Green, CircleShape))
                Spacer(modifier = Modifier.width(8.dp))
                Text("ONLINE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        ExtendedFloatingActionButton(onClick = onBack, containerColor = Color.White, contentColor = Color.Black, shape = RoundedCornerShape(16.dp), modifier = Modifier.size(width = 110.dp, height = 44.dp), icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp)) }, text = { Text("Back", fontSize = 14.sp) })
    }
}
