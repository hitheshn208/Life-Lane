package com.example.testapp

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import org.osmdroid.views.MapView
import java.util.concurrent.TimeUnit

data class TrafficSignal(
    val id: String,
    val lat: Double,
    val lon: Double,
    val color: String,
    val trafficLevel: String,
    val eta: Int,
    val distance: Int
)

class TripManager(private val vehicleNumber: String) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    
    private var webSocket: WebSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var currentLat: Double = 0.0
    private var currentLon: Double = 0.0
    private var currentEta: Int = 0
    private var isTripActive = false
    private var currentTripId: Int? = null

    // Signal data state for Compose to observe
    val signals = mutableStateListOf<TrafficSignal>()

    fun setTripId(tripId: Int) {
        this.currentTripId = tripId
    }

    fun updateLocation(lat: Double, lon: Double, eta: Int) {
        currentLat = lat
        currentLon = lon
        currentEta = eta
    }

    fun startTrip() {
        if (isTripActive) return
        isTripActive = true
        connect()
        startUpdates()
    }

    fun stopTrip() {
        isTripActive = false
        job?.cancel()
        webSocket?.close(1000, "Trip ended")
        webSocket = null
        signals.clear()
    }

    private fun connect() {
        if (!isTripActive) return
        
        val request = Request.Builder()
            .url(Config.WS_URL)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("TripManager", "WebSocket Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "signal_state_update") {
                        handleSignalUpdate(json)
                    }
                } catch (e: Exception) {
                    Log.e("TripManager", "Error parsing message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("TripManager", "Error: ${t.message}")
                if (isTripActive) {
                    scope.launch {
                        delay(5000)
                        connect()
                    }
                }
            }
        })
    }

    private fun handleSignalUpdate(json: JSONObject) {
        val tripId = json.optInt("trip_id")
        if (currentTripId != null && tripId != currentTripId) return

        val signalsArray = json.optJSONArray("signals") ?: return
        val newSignals = mutableListOf<TrafficSignal>()
        
        for (i in 0 until signalsArray.length()) {
            val s = signalsArray.getJSONObject(i)
            newSignals.add(TrafficSignal(
                id = s.getString("id"),
                lat = s.getDouble("lat"),
                lon = s.getDouble("lon"),
                color = s.optString("color", "YELLOW"),
                trafficLevel = s.optString("traffic_level", "UNKNOWN"),
                eta = s.optInt("eta_seconds", 0),
                distance = s.optInt("distance_to_signal_meters", 0)
            ))
        }

        // Update state on Main thread for Compose
        CoroutineScope(Dispatchers.Main).launch {
            signals.clear()
            signals.addAll(newSignals)
        }
    }

    private fun startUpdates() {
        job?.cancel()
        job = scope.launch {
            while (isActive && isTripActive) {
                sendLocationUpdate()
                delay(1000)
            }
        }
    }

    private fun sendLocationUpdate() {
        if (currentLat == 0.0 && currentLon == 0.0) return
        
        val json = JSONObject().apply {
            put("type", "mobile_location_update")
            put("vehicle_number", vehicleNumber)
            put("lat", currentLat)
            put("lon", currentLon)
            put("eta_to_hospital", currentEta)
        }
        
        webSocket?.send(json.toString())
    }

    // Deprecated methods replaced by state management
    fun setMapView(view: MapView) {}
}
