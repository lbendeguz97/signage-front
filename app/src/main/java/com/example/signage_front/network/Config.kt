package com.example.signage_front.network

object Config {
    const val ENV = "dev" // "dev" or "prod"
    const val BASE_URL = "https://10.0.2.2:4000"
    const val BASE_URL_BACKUP = "https://backup-server.com"

    var currentBaseUrl = BASE_URL
}
