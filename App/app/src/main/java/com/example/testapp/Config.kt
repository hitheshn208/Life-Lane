package com.example.testapp

import java.net.URLEncoder

object Config {
    const val BASE_URL = "http://10.202.141.236:3000"
    val WS_URL: String
        get() = BASE_URL.replace("http://", "ws://") + "/ws/active-trips?role=mobile"

    fun getMobileWsUrl(vehicleNumber: String, tripId: Int? = null): String {
        val encodedVehicleNumber = URLEncoder.encode(vehicleNumber.trim().uppercase(), "UTF-8")
        val encodedTripId = if (tripId != null && tripId > 0) "&trip_id=$tripId" else ""
        return BASE_URL.replace("http://", "ws://") + "/ws/active-trips?role=mobile&vehicle_number=$encodedVehicleNumber$encodedTripId"
    }
}
