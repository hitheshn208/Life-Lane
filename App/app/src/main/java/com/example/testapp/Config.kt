package com.example.testapp

object Config {
    const val BASE_URL = "http://10.202.141.236:3000"
    val WS_URL: String
        get() = BASE_URL.replace("http://", "ws://") + "/ws/active-trips?role=mobile"
}
