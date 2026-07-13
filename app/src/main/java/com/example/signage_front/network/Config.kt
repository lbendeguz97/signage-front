package com.example.signage_front.network

object Config {
    const val ENV = "dev" // "dev" or "prod"
    const val BASE_URL = "https://host.docker.internal:4000"
    const val BASE_URL_BACKUP = "https://10.0.2.2:4000"
    
    // The base URL for the QR code redirect
    const val REDIRECT_ROOT = "https://mysignage.com/scan"

    var currentBaseUrl = BASE_URL
}
