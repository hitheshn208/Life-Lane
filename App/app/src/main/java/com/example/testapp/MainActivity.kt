package com.example.testapp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.example.testapp.ui.theme.TestAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPref = getSharedPreferences("testapp_prefs", Context.MODE_PRIVATE)
        val isLoggedInInitial = sharedPref.getBoolean("isLoggedIn", false)
        val savedName = sharedPref.getString("userName", "") ?: ""
        val savedVehicleId = sharedPref.getString("vehicleId", "") ?: ""
        val savedDriverId = sharedPref.getString("driverId", "") ?: ""

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
                var driverId by remember { mutableStateOf(savedDriverId) }

                when (currentScreen) {
                    "login" -> {
                        LoginScreen(onLoginSuccess = { name, phone, id ->
                            sharedPref.edit {
                                putBoolean("isLoggedIn", true)
                                putString("userName", name)
                                putString("userPhone", phone)
                                putString("driverId", id)
                            }
                            userName = name
                            driverId = id
                            currentScreen = "vehicle_setup"
                        })
                    }
                    "vehicle_setup" -> {
                        VehicleSetupScreen(
                            userName = userName,
                            onVehicleConfirmed = { id ->
                                sharedPref.edit {
                                    putString("vehicleId", id)
                                    val recents = sharedPref.getStringSet("recent_vehicles", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                                    recents.add(id)
                                    putStringSet("recent_vehicles", recents)
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
                                driverId = ""
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
    val context = LocalContext.current
    val sharedPref = remember { context.getSharedPreferences("testapp_prefs", Context.MODE_PRIVATE) }
    val recentVehicles = remember { 
        sharedPref.getStringSet("recent_vehicles", setOf())?.toList()?.reversed() ?: listOf() 
    }
    
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
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(60.dp))
            
            Text(
                text = "Welcome, $userName!",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Select or enter your ambulance number",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Main Input Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { onVehicleConfirmed(ambulanceNumber) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        enabled = ambulanceNumber.length >= 4,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Start Duty", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }

            if (recentVehicles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.History, 
                        contentDescription = null, 
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Recent Ambulances",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recentVehicles) { vehicle ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onVehicleConfirmed(vehicle) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.LocalShipping, 
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = vehicle,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
