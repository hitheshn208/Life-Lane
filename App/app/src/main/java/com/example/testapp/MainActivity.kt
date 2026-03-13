package com.example.testapp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.example.testapp.ui.theme.TestAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPref = getSharedPreferences("testapp_prefs", Context.MODE_PRIVATE)
        val isLoggedInInitial = sharedPref.getBoolean("isLoggedIn", false)

        enableEdgeToEdge()
        setContent {
            TestAppTheme {
                var currentScreen by remember { 
                    mutableStateOf(if (isLoggedInInitial) "dashboard" else "login") 
                }
                var vehicleId by remember { mutableStateOf(sharedPref.getString("vehicleId", "") ?: "") }

                if (currentScreen == "login") {
                    LoginScreen(onLoginSuccess = { id ->
                        sharedPref.edit()
                            .putBoolean("isLoggedIn", true)
                            .putString("vehicleId", id)
                            .apply()
                        vehicleId = id
                        currentScreen = "dashboard"
                    })
                } else {
                    DashboardScreen(
                        vehicleId = vehicleId,
                        onLogout = {
                            sharedPref.edit().putBoolean("isLoggedIn", false).apply()
                            currentScreen = "login"
                        }
                    )
                }
            }
        }
    }
}
