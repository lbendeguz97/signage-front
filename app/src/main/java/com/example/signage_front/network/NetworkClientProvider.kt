package com.example.signage_front.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object NetworkClientProvider {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    fun getMTlsClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        
        // Initialize KeyManager with our client certificate
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, null)

        // Initialize TrustManager with system default
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val trustManager = tmf.trustManagers[0] as X509TrustManager

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, tmf.trustManagers, null)

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun getStandardClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }
}
