package com.example.testapp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
        val savedToken = sharedPref.getString("authToken", "") ?: ""

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
                var authToken by remember { mutableStateOf(savedToken) }

                when (currentScreen) {
                    "login" -> {
                        LoginScreen(onLoginSuccess = { name, phone, id, token ->
                            sharedPref.edit {
                                putBoolean("isLoggedIn", true)
                                putString("userName", name)
                                putString("userPhone", phone)
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
                            authToken = authToken,
                            onLogout = {
                                sharedPref.edit {
                                    clear()
                                }
                                vehicleId = ""
                                userName = ""
                                driverId = ""
                                authToken = ""
                                currentScreen = "login"
                            }
                        )
                    }
                }
            }
        }
    }
}

data class RecentAmbulance(
    val id: String,
    val type: String,
    val lastActive: String
)

@Composable
fun VehicleSetupScreen(userName: String, onVehicleConfirmed: (String) -> Unit) {
    val context = LocalContext.current
    val sharedPref = remember { context.getSharedPreferences("testapp_prefs", Context.MODE_PRIVATE) }
    
    val dummyRecent = listOf(
        RecentAmbulance("KA 01 AB 1234", "ALS - Advanced", "2 hours ago"),
        RecentAmbulance("KA 05 MN 5678", "BLS - Basic", "Yesterday"),
        RecentAmbulance("KA 51 XY 9012", "ALS - Advanced", "3 days ago"),
        RecentAmbulance("KA 03 GH 4321", "Patient Transport", "1 week ago")
    )

    val recentVehicles = remember { 
        val saved = sharedPref.getStringSet("recent_vehicles", setOf())?.toList()?.reversed() ?: listOf()
        if (saved.isEmpty()) {
            dummyRecent
        } else {
            saved.map { RecentAmbulance(it, "Ambulance", "Recently used") }
        }
    }
    
    var ambulanceNumber by remember { mutableStateOf("") }

    val darkSurface = Color(0xFF1A1C1E)
    val lightBackground = Color(0xFFF8F9FA)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = lightBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Dark Header Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        darkSurface,
                        RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 40.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Profile",
                            modifier = Modifier.size(36.dp),
                            tint = Color.White
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column {
                        Text(
                            text = "Driver Portal",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        Text(
                            text = userName,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.1f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Notifications, contentDescription = "Notifications", tint = Color.White)
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(40.dp))

                // Vehicle Assignment Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White,
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
                                    )
                                )
                            )
                            .padding(24.dp)
                    ) {
                        Text(
                            text = "Assign Your Vehicle",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = darkSurface
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "Enter the ambulance number to begin",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedTextField(
                            value = ambulanceNumber,
                            onValueChange = { ambulanceNumber = it.uppercase() },
                            label = { Text("Vehicle ID") },
                            placeholder = { Text("e.g. KA-01-AB-1234") },
                            leadingIcon = { 
                                Icon(
                                    Icons.Default.LocalShipping, 
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                ) 
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.Black,
                                unfocusedTextColor = Color.Black,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color(0xFFEEEEEE),
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            )
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { onVehicleConfirmed(ambulanceNumber) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp),
                            shape = RoundedCornerShape(16.dp),
                            enabled = ambulanceNumber.length >= 4,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                disabledContainerColor = Color.LightGray
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                        ) {
                            Text(
                                "START SHIFT", 
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold, 
                                    letterSpacing = 1.2.sp
                                )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
                
                // Recent Vehicles Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Recently Used",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = darkSurface
                    )
                    TextButton(onClick = { /* See all */ }) {
                        Text("See All", color = MaterialTheme.colorScheme.primary)
                    }
                }
                
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(recentVehicles) { vehicle ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onVehicleConfirmed(vehicle.id) },
                            shape = RoundedCornerShape(18.dp),
                            color = Color.White,
                            shadowElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Emergency, 
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = vehicle.id,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                        color = darkSurface
                                    )
                                    Text(
                                        text = vehicle.type,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                                
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = Color.LightGray
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
