package com.example.testapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.testapp.ui.theme.TestAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun LoginScreen(onLoginSuccess: (String, String, String, String) -> Unit = { _, _, _, _ -> }) {
    var isLoginMode by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var dlNumber by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
            // Header Icon
            Surface(
                modifier = Modifier.size(100.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "🚑",
                        fontSize = 48.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (isLoginMode) "Ambulance Login" else "Ambulance Register",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            TabRow(
                selectedTabIndex = if (isLoginMode) 0 else 1,
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .width(250.dp),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {}
            ) {
                Tab(
                    selected = isLoginMode,
                    onClick = { isLoginMode = true; errorMessage = null },
                    text = { Text("Login") }
                )
                Tab(
                    selected = !isLoginMode,
                    onClick = { isLoginMode = false; errorMessage = null },
                    text = { Text("Register") }
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!isLoginMode) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Full Name") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = dlNumber,
                            onValueChange = { dlNumber = it },
                            label = { Text("Driving License Number") },
                            leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { if (it.length <= 10) phoneNumber = it },
                        label = { Text("Phone Number") },
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(imageVector = image, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )

                    errorMessage?.let {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            scope.launch(Dispatchers.IO) {
                                val result = if (isLoginMode) {
                                    loginUser(phoneNumber, password)
                                } else {
                                    registerUser(name, phoneNumber, dlNumber, password)
                                }
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    if (result.isSuccess) {
                                        onLoginSuccess(result.name ?: name, phoneNumber, result.driverId!!, result.token!!)
                                    } else {
                                        errorMessage = result.message
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        enabled = !isLoading && (isLoginMode || (name.isNotBlank() && dlNumber.isNotBlank())) && phoneNumber.length == 10 && password.length >= 4,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        } else {
                            Text(
                                if (isLoginMode) "Login" else "Register",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoginMode) {
                TextButton(onClick = { /* Handle forgot password */ }) {
                    Text(
                        "Trouble Logging In?",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

data class AuthResult(val isSuccess: Boolean, val message: String, val driverId: String? = null, val name: String? = null, val token: String? = null)
var baseURL = "http://10.202.141.236:3000"
private fun loginUser(phone: String, pass: String): AuthResult {
    return try {
        val url = URL("${baseURL}/auth/login")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")

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

        when (code) {
            200 -> {
                val resJson = JSONObject(response)
                val driver = resJson.getJSONObject("driver")
                AuthResult(true, "Login successful", driver.getString("id"), driver.getString("name"), resJson.getString("token"))
            }
            400 -> {
                val resJson = try { JSONObject(response) } catch(e: Exception) { null }
                val msg = resJson?.optString("message") ?: "phone and password are required"
                AuthResult(false, msg)
            }
            401 -> {
                val resJson = try { JSONObject(response) } catch(e: Exception) { null }
                val msg = resJson?.optString("message") ?: "Invalid phone or password"
                AuthResult(false, msg)
            }
            500 -> {
                val resJson = try { JSONObject(response) } catch(e: Exception) { null }
                val msg = resJson?.optString("message") ?: "Failed to login"
                AuthResult(false, msg)
            }
            else -> AuthResult(false, "Unknown error occurred: $code")
        }
    } catch (e: Exception) {
        AuthResult(false, "Could not connect to server: ${e.message}")
    }
}

private fun registerUser(name: String, phone: String, license: String, pass: String): AuthResult {
    return try {
        val url = URL("${baseURL}/auth/register")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")

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

        when (code) {
            201 -> {
                val resJson = JSONObject(response)
                AuthResult(true, resJson.optString("message", "Success"), resJson.getString("driverId"), name, resJson.getString("token"))
            }
            400 -> {
                val resJson = try { JSONObject(response) } catch(e: Exception) { null }
                val msg = resJson?.optString("message") ?: "name, phone, license_number and password are required"
                AuthResult(false, msg)
            }
            409 -> {
                val resJson = try { JSONObject(response) } catch(e: Exception) { null }
                val msg = resJson?.optString("message") ?: "Phone or license number already exists"
                AuthResult(false, msg)
            }
            500 -> {
                val resJson = try { JSONObject(response) } catch(e: Exception) { null }
                val msg = resJson?.optString("message") ?: "Internal Server error, Please Try again Later"
                AuthResult(false, msg)
            }
            else -> AuthResult(false, "Unknown error occurred: $code")
        }
    } catch (e: Exception) {
        AuthResult(false, "Could not connect to server: ${e.message}")
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    TestAppTheme {
        LoginScreen()
    }
}
