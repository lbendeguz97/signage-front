package com.example.signage_front.network

import android.content.Context
import com.example.signage_front.R
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object NetworkClientProvider {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /**
     * Creates an OkHttpClient configured for mTLS.
     * @param context Required to load the Root CA from resources for the TrustManager.
     */
    fun getMTlsClient(context: Context): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        // 1. Initialize KeyManager with client certificate from AndroidKeyStore
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, null)

        // 2. Initialize TrustManager with your private Root CA
        // This ensures the client trusts the server's certificate
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        val cf = CertificateFactory.getInstance("X.509")
        val rootCaInput = context.resources.openRawResource(R.raw.ca_cert)
        val rootCa = cf.generateCertificate(rootCaInput) as X509Certificate
        trustStore.setCertificateEntry("root_ca", rootCa)

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)
        val trustManager = tmf.trustManagers[0] as X509TrustManager

        // 3. Initialize SSLContext with both KeyManager and TrustManager
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
