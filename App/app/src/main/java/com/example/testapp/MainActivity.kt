package com.example.testapp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.edit
import com.example.testapp.ui.theme.TestAppTheme
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPref = getSharedPreferences("testapp_prefs", Context.MODE_PRIVATE)
        val savedName = sharedPref.getString("userName", "") ?: ""
        val savedDriverId = sharedPref.getString("driverId", "") ?: ""
        val savedToken = sharedPref.getString("authToken", "") ?: ""

        sharedPref.edit { remove("vehicleId") }

        enableEdgeToEdge()
        setContent {
            // Force darkTheme = false and dynamicColor = false to fix visibility issues
            TestAppTheme(darkTheme = false, dynamicColor = false) {
                var currentScreen by remember { mutableStateOf("loading") }
                var userName by remember { mutableStateOf(savedName) }
                var vehicleId by remember { mutableStateOf("") }
                var driverId by remember { mutableStateOf(savedDriverId) }
                var authToken by remember { mutableStateOf(savedToken) }

                LaunchedEffect(Unit) {
                    if (authToken.isEmpty()) {
                        currentScreen = "login"
                    } else {
                        val result = withTimeoutOrNull(6000) {
                            ApiService.verifyToken(authToken)
                        }
                        
                        if (result != null && result.isSuccess) {
                            userName = result.name ?: userName
                            driverId = result.driverId ?: driverId
                            currentScreen = "vehicle_setup"
                        } else {
                            sharedPref.edit { 
                                remove("authToken")
                                remove("isLoggedIn")
                            }
                            authToken = ""
                            currentScreen = "login"
                        }
                    }
                }

                when (currentScreen) {
                    "loading" -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    "login" -> {
                        LoginScreen(onLoginSuccess = { name, _, id, token ->
                            sharedPref.edit {
                                putBoolean("isLoggedIn", true)
                                putString("userName", name)
                                putString("driverId", id)
                                putString("authToken", token)
                            }
                            userName = name
                            driverId = id
                            authToken = token
                            currentScreen = "vehicle_setup"
                        })
                    }
                    "vehicle_setup" -> {
                        VehicleSetupScreen(
                            userName = userName,
                            authToken = authToken,
                            onLogout = {
                                sharedPref.edit { clear() }
                                vehicleId = ""
                                userName = ""
                                driverId = ""
                                authToken = ""
                                currentScreen = "login"
                            },
                            onVehicleConfirmed = { id ->
                                sharedPref.edit {
                                    putString("vehicleId", id)
                                }
                                vehicleId = id
                                currentScreen = "dashboard"
                            }
                        )
                    }
                    "dashboard" -> {
                        DashboardScreen(
                            vehicleId = vehicleId,
                            authToken = authToken,
                            onBackToSetup = {
                                currentScreen = "vehicle_setup"
                            }
                        )
                    }
                }
            }
        }
    }
}
