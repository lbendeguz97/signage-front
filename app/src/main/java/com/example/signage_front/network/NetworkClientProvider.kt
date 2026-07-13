package com.example.signage_front.network
//NetworkClientProvider.kt
import android.content.Context
import android.util.Log
import com.example.signage_front.R
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

object NetworkClientProvider {
    private const val KEY_ALIAS = "client_auth_key_v5" // must match SecurityManager alias
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TAG = "NetworkClient"

    // -------------------------------------------------------------------------
    // Custom KeyManager for AndroidKeyStore keys.
    // Returns the certificate chain and private key for TLS client authentication.
    // The private key must support NONEwithECDSA (via DIGEST_NONE in KeyGenParameterSpec)
    // for Conscrypt's TLS client auth to work properly.
    // -------------------------------------------------------------------------
    private class AndroidKeyStoreKeyManager(private val alias: String) : X509ExtendedKeyManager() {

        private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        override fun chooseClientAlias(
            keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?
        ): String {
            Log.d(TAG, "chooseClientAlias called, keyTypes: ${keyType?.joinToString()}, returning: $alias")
            return alias
        }

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?
        ): String {
            Log.d(TAG, "chooseEngineClientAlias called, keyTypes: ${keyType?.joinToString()}, returning: $alias")
            return alias
        }

        override fun getCertificateChain(alias: String?): Array<X509Certificate> {
            val chain = (keyStore.getCertificateChain(this.alias) ?: return emptyArray())
                .map { it as X509Certificate }
                .toTypedArray()
            Log.d(TAG, "getCertificateChain returning ${chain.size} certificates")
            return chain
        }

        override fun getPrivateKey(alias: String?): PrivateKey? {
            val key = keyStore.getKey(this.alias, null) as? PrivateKey
            Log.d(TAG, "getPrivateKey called, key available: ${key != null}, class: ${key?.javaClass?.name}")
            return key
        }

        override fun getClientAliases(
            keyType: String?, issuers: Array<out Principal>?
        ): Array<String> = arrayOf(alias)

        // Server-side — unused
        override fun chooseServerAlias(
            keyType: String?, issuers: Array<out Principal>?, socket: Socket?
        ): String? = null
        override fun getServerAliases(
            keyType: String?, issuers: Array<out Principal>?
        ): Array<String>? = null
    }

    // -------------------------------------------------------------------------

    private val eventListenerFactory = object : EventListener.Factory {
        override fun create(call: Call): EventListener = object : EventListener() {
            override fun callStart(call: Call) {
                Log.d(TAG, "🚀 Call Start: ${call.request().url}")
            }
            override fun secureConnectStart(call: Call) {
                Log.d(TAG, "🔒 TLS Handshake Start")
            }
            override fun secureConnectEnd(call: Call, handshake: Handshake?) {
                Log.d(TAG, "✅ TLS Handshake End: ${handshake?.tlsVersion}")
            }
            override fun requestHeadersEnd(call: Call, request: Request) {
                Log.d(TAG, "📤 Request Headers Sent")
            }
            override fun responseHeadersStart(call: Call) {
                Log.d(TAG, "⏳ Waiting for Response...")
            }
            override fun responseHeadersEnd(call: Call, response: Response) {
                Log.d(TAG, "📥 Response: ${response.code}")
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

    private val DockerDns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            if (hostname == "host.docker.internal") {
                val addresses = mutableListOf<java.net.InetAddress>()
                try {
                    val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val netInterface = interfaces.nextElement()
                        if (netInterface.isLoopback || !netInterface.isUp) continue
                        val interfaceAddresses = netInterface.inetAddresses
                        while (interfaceAddresses.hasMoreElements()) {
                            val addr = interfaceAddresses.nextElement()
                            if (addr is java.net.Inet4Address) {
                                val bytes = addr.address
                                bytes[3] = 1.toByte()
                                addresses.add(java.net.InetAddress.getByAddress(hostname, bytes))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to detect gateway dynamically: ${e.message}")
                }
                
                // Fallbacks
                val fallbacks = listOf(
                    byteArrayOf(172.toByte(), 23.toByte(), 0.toByte(), 1.toByte()),
                    byteArrayOf(172.toByte(), 17.toByte(), 0.toByte(), 1.toByte()),
                    byteArrayOf(10.toByte(), 0.toByte(), 2.toByte(), 2.toByte())
                )
                for (fb in fallbacks) {
                    try {
                        val addr = java.net.InetAddress.getByAddress(hostname, fb)
                        if (addresses.none { it.hostAddress == addr.hostAddress }) {
                            addresses.add(addr)
                        }
                    } catch (ignored: Exception) {}
                }
                Log.d(TAG, "Resolved host.docker.internal to: ${addresses.joinToString { it.hostAddress }}")
                return addresses
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    fun getMTlsClient(context: Context): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val keyManager = AndroidKeyStoreKeyManager(KEY_ALIAS)
        val trustManager = getTrustManager(context)

        // Get the Conscrypt provider explicitly
        val conscryptProvider = Security.getProvider("Conscrypt")
        Log.d(TAG, "Conscrypt provider: ${conscryptProvider?.name ?: "NOT FOUND"}")
        
        // Use Conscrypt's SSLContext explicitly to ensure proper handling of
        // AndroidKeyStore keys during TLS client authentication.
        val sslContext = if (conscryptProvider != null) {
            Log.d(TAG, "Using Conscrypt SSLContext for mTLS")
            SSLContext.getInstance("TLS", conscryptProvider).apply {
                init(arrayOf(keyManager), arrayOf(trustManager), null)
            }
        } else {
            Log.w(TAG, "Conscrypt not available, falling back to default SSLContext")
            SSLContext.getInstance("TLS").apply {
                init(arrayOf(keyManager), arrayOf(trustManager), null)
            }
        }
        
        Log.d(TAG, "SSLContext provider: ${sslContext.provider.name}")

        // Verify the certificate chain is available
        val certChain = keyManager.getCertificateChain(KEY_ALIAS)
        Log.d(TAG, "Client certificate chain length: ${certChain.size}")
        certChain.forEachIndexed { index, cert ->
            Log.d(TAG, "  Cert[$index]: ${cert.subjectX500Principal}")
        }
        
        // Verify the private key is accessible
        val privateKey = keyManager.getPrivateKey(KEY_ALIAS)
        Log.d(TAG, "Private key available: ${privateKey != null}")
        privateKey?.let {
            Log.d(TAG, "Private key algorithm: ${it.algorithm}")
            Log.d(TAG, "Private key class: ${it.javaClass.name}")
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor(logging)
            .eventListenerFactory(eventListenerFactory)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dns(DockerDns)
            .build()
    }

    fun getStandardClient(context: Context): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val trustManager = getTrustManager(context)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor(logging)
            .eventListenerFactory(eventListenerFactory)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dns(DockerDns)
            .build()
    }
}