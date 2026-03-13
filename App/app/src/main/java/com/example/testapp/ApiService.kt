package com.example.testapp

import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Ambulance(
    val id: Int? = null,
    val vehicleNumber: String,
    val ambulanceName: String,
    val ambulanceType: String,
    val registeredHospital: String,
    val registeredAt: String? = null,
    val govtCreatedAt: String? = null
)

data class AuthResult(val isSuccess: Boolean, val message: String, val driverId: String? = null, val name: String? = null, val token: String? = null)
data class VerifyResult(val isValidAmbulance: Boolean, val message: String, val ambulance: Ambulance? = null)
data class RegisterResult(val isSuccess: Boolean, val message: String, val registrationId: Int? = null, val ambulance: Ambulance? = null)
data class MyAmbulancesResult(val isSuccess: Boolean, val ambulances: List<Ambulance> = emptyList(), val message: String? = null)

object ApiService {
    private const val TIMEOUT = 5000

    suspend fun verifyToken(token: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("${Config.BASE_URL}/auth/verify-token")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            
            val code = conn.responseCode
            val response = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            val resJson = JSONObject(response)
            val isValid = resJson.optBoolean("isValid", false)
            
            if (code == 200 && isValid) {
                val user = resJson.getJSONObject("user")
                AuthResult(true, "Token valid", user.getString("driverId"), user.getString("name"), token)
            } else {
                AuthResult(false, resJson.optString("message", "Session expired"))
            }
        } catch (e: Exception) {
            AuthResult(false, "Network error: ${e.message}")
        }
    }

    suspend fun login(phone: String, pass: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("${Config.BASE_URL}/auth/login")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT

            val json = JSONObject().apply {
                put("phone", phone)
                put("password", pass)
            }
            conn.outputStream.use { it.write(json.toString().toByteArray()) }
            
            val code = conn.responseCode
            val response = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            val resJson = JSONObject(response)
            if (code == 200) {
                val driver = resJson.getJSONObject("driver")
                AuthResult(true, "Login successful", driver.getString("id"), driver.getString("name"), resJson.getString("token"))
            } else {
                AuthResult(false, resJson.optString("message", "Login failed"))
            }
        } catch (e: Exception) {
            AuthResult(false, "Network error: ${e.message}")
        }
    }

    suspend fun registerDriver(name: String, phone: String, license: String, pass: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("${Config.BASE_URL}/auth/register")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT

            val json = JSONObject().apply {
                put("name", name)
                put("phone", phone)
                put("license_number", license)
                put("password", pass)
            }
            conn.outputStream.use { it.write(json.toString().toByteArray()) }
            
            val code = conn.responseCode
            val response = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            val resJson = JSONObject(response)
            if (code == 201) {
                AuthResult(true, resJson.optString("message", "Success"), resJson.getString("driverId"), name, resJson.getString("token"))
            } else {
                AuthResult(false, resJson.optString("message", "Registration failed"))
            }
        } catch (e: Exception) {
            AuthResult(false, "Network error: ${e.message}")
        }
    }

    suspend fun verifyAmbulance(token: String, vehicleNumber: String): VerifyResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("${Config.BASE_URL}/api/ambulances/verify")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT

            val json = JSONObject().apply { put("vehicle_number", vehicleNumber) }
            conn.outputStream.use { it.write(json.toString().toByteArray()) }

            val code = conn.responseCode
            val response = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            val resJson = JSONObject(response)
            if (code == 200) {
                val ambJson = resJson.getJSONObject("ambulance")
                VerifyResult(true, resJson.getString("message"), Ambulance(
                    vehicleNumber = ambJson.getString("vehicle_number"),
                    ambulanceName = ambJson.getString("ambulance_name"),
                    ambulanceType = ambJson.getString("ambulance_type"),
                    registeredHospital = ambJson.getString("registered_hospital"),
                    govtCreatedAt = ambJson.optString("created_at")
                ))
            } else {
                VerifyResult(false, resJson.optString("message", "Verification failed"))
            }
        } catch (e: Exception) {
            VerifyResult(false, "Network error: ${e.message}")
        }
    }

    suspend fun registerAmbulance(token: String, vehicleNumber: String): RegisterResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("${Config.BASE_URL}/api/ambulances/register")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT

            val json = JSONObject().apply { put("vehicle_number", vehicleNumber) }
            conn.outputStream.use { it.write(json.toString().toByteArray()) }

            val code = conn.responseCode
            val response = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            val resJson = JSONObject(response)
            if (code == 201 || code == 409) {
                val ambJson = resJson.optJSONObject("ambulance")
                RegisterResult(true, resJson.getString("message"), resJson.optInt("registrationId"), if (ambJson != null) Ambulance(
                    vehicleNumber = ambJson.getString("vehicle_number"),
                    ambulanceName = ambJson.getString("ambulance_name"),
                    ambulanceType = ambJson.getString("ambulance_type"),
                    registeredHospital = ambJson.getString("registered_hospital")
                ) else null)
            } else {
                RegisterResult(false, resJson.optString("message", "Registration failed"))
            }
        } catch (e: Exception) {
            RegisterResult(false, "Network error: ${e.message}")
        }
    }

    suspend fun getMyAmbulances(token: String): MyAmbulancesResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("${Config.BASE_URL}/api/ambulances/my-ambulances")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT

            val code = conn.responseCode
            val response = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            val resJson = JSONObject(response)
            if (code == 200) {
                val ambulancesArray = resJson.getJSONArray("ambulances")
                val ambulancesList = mutableListOf<Ambulance>()
                for (i in 0 until ambulancesArray.length()) {
                    val item = ambulancesArray.getJSONObject(i)
                    ambulancesList.add(Ambulance(
                        id = item.getInt("id"),
                        vehicleNumber = item.getString("vehicle_number"),
                        ambulanceName = item.getString("ambulance_name"),
                        ambulanceType = item.getString("ambulance_type"),
                        registeredHospital = item.getString("registered_hospital"),
                        registeredAt = item.getString("registered_at"),
                        govtCreatedAt = item.getString("govt_created_at")
                    ))
                }
                MyAmbulancesResult(true, ambulancesList)
            } else {
                MyAmbulancesResult(false, emptyList(), resJson.optString("message", "Failed to fetch ambulances"))
            }
        } catch (e: Exception) {
            MyAmbulancesResult(false, emptyList(), "Network error: ${e.message}")
        }
    }

    suspend fun startTrip(token: String, vehicleNumber: String, patientLoc: GeoPoint, hospitalLoc: GeoPoint, details: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("${Config.BASE_URL}/api/trips/start")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT

            val json = JSONObject().apply {
                put("vehicle_number", vehicleNumber)
                put("patient_lat", patientLoc.latitude)
                put("patient_lon", patientLoc.longitude)
                put("hospital_lat", hospitalLoc.latitude)
                put("hospital_lon", hospitalLoc.longitude)
                put("patient_age", details["age"])
                put("condition", details["condition"])
                put("severity", details["severity"])
            }

            conn.outputStream.use { it.write(json.toString().toByteArray()) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }
}
