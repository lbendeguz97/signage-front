package com.example.signage_front.network

import android.content.Context
import android.util.Log
import com.example.signage_front.R
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

object NetworkClientProvider {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TAG = "OkHttpEvents"

    private val eventListenerFactory = object : EventListener.Factory {
        override fun create(call: Call): EventListener = object : EventListener() {
            override fun callStart(call: Call) {
                Log.d(TAG, "🚀 Call Start: ${call.request().url}")
            }
            override fun secureConnectStart(call: Call) {
                Log.d(TAG, "🔒 TLS Handshake Start")
            }
            override fun secureConnectEnd(call: Call, handshake: Handshake?) {
                Log.d(TAG, "✅ TLS Handshake End")
            }
            override fun requestHeadersEnd(call: Call, request: Request) {
                Log.d(TAG, "📤 Request Headers Sent")
            }
            override fun responseHeadersStart(call: Call) {
                Log.d(TAG, "⏳ Waiting for Response Headers...")
            }
            override fun responseHeadersEnd(call: Call, response: Response) {
                Log.d(TAG, "📥 Response Received: ${response.code}")
            }
            override fun callFailed(call: Call, ioe: IOException) {
                Log.e(TAG, "❌ Call Failed: ${ioe.message}")
            }
        }
    }

    private fun getTrustManager(context: Context): X509TrustManager {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        val cf = CertificateFactory.getInstance("X.509")
        context.resources.openRawResource(R.raw.ca_cert).use { input ->
            val rootCa = cf.generateCertificate(input) as X509Certificate
            trustStore.setCertificateEntry("root_ca", rootCa)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)
        return tmf.trustManagers[0] as X509TrustManager
    }

    fun getMTlsClient(context: Context): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, null)

        val trustManager = getTrustManager(context)
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(kmf.keyManagers, arrayOf(trustManager), null)

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor(logging)
            .eventListenerFactory(eventListenerFactory)
            .protocols(listOf(Protocol.HTTP_1_1)) // Force HTTP/1.1 to avoid ALPN hangs
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun getStandardClient(context: Context): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val trustManager = getTrustManager(context)
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, arrayOf(trustManager), null)

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor(logging)
            .eventListenerFactory(eventListenerFactory)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
