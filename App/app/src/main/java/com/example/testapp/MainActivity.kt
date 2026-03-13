package com.example.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.example.testapp.ui.theme.TestAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPref = getSharedPreferences("testapp_prefs", MODE_PRIVATE)
        val isLoggedInInitial = sharedPref.getBoolean("isLoggedIn", false)
        val savedName = sharedPref.getString("userName", "") ?: ""
        val savedVehicleId = sharedPref.getString("vehicleId", "") ?: ""

        enableEdgeToEdge()
        setContent {
            TestAppTheme {
                var currentScreen by remember { 
                    mutableStateOf(
                        when {
                            !isLoggedInInitial -> "login"
                            savedVehicleId.isEmpty() -> "vehicle_setup"
                            else -> "dashboard"
                        }
                    ) 
                }
                var userName by remember { mutableStateOf(savedName) }
                var vehicleId by remember { mutableStateOf(savedVehicleId) }

                when (currentScreen) {
                    "login" -> {
                        LoginScreen(onLoginSuccess = { name, phone ->
                            sharedPref.edit {
                                putBoolean("isLoggedIn", true)
                                putString("userName", name)
                                putString("userPhone", phone)
                            }
                            userName = name
                            currentScreen = "vehicle_setup"
                        })
                    }
                    "vehicle_setup" -> {
                        VehicleSetupScreen(
                            userName = userName,
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
                            onLogout = {
                                sharedPref.edit {
                                    clear()
                                }
                                vehicleId = ""
                                userName = ""
                                currentScreen = "login"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VehicleSetupScreen(userName: String, onVehicleConfirmed: (String) -> Unit) {
    var ambulanceNumber by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Welcome, $userName!",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Please enter your ambulance details to continue",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    OutlinedTextField(
                        value = ambulanceNumber,
                        onValueChange = { ambulanceNumber = it.uppercase() },
                        label = { Text("Ambulance Number") },
                        placeholder = { Text("e.g., KA 01 AB 1234") },
                        leadingIcon = { Icon(Icons.Default.LocalShipping, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { onVehicleConfirmed(ambulanceNumber) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        enabled = ambulanceNumber.length >= 4,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            "Enter Dashboard",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}
