package com.example.testapp

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
                Log.d("TripManager", "Message: $text")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("TripManager", "Error: ${t.message}")
                if (isTripActive) {
                    scope.launch {
                        delay(5000) // Reconnect after 5 seconds
                        connect()
                    }
                }
            }
        })
    }

    private fun startUpdates() {
        job?.cancel()
        job = scope.launch {
            while (isActive && isTripActive) {
                sendLocationUpdate()
                delay(3000)
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
        
        val sent = webSocket?.send(json.toString()) ?: false
        if (sent) {
            Log.d("TripManager", "Sent update: $json")
        } else {
            Log.w("TripManager", "Failed to send update (WS might be down)")
        }
    }
}
