package com.example.signage_front

import android.app.Application
import android.util.Log
import org.conscrypt.Conscrypt
import java.security.Security

/**
 * Application class that initializes security providers on app startup.
 * 
 * Conscrypt is installed as the primary TLS provider to ensure proper handling
 * of AndroidKeyStore EC keys during mTLS handshakes. Without this, the default
 * provider may attempt NONEwithECDSA signing which AndroidKeyStore doesn't support.
 */
class SignageApplication : Application() {
    
    companion object {
        private const val TAG = "SignageApplication"
    }

    override fun onCreate() {
        super.onCreate()
        initializeSecurityProviders()
    }

    private fun initializeSecurityProviders() {
        Log.d(TAG, "=== Security Provider Initialization ===")
        
        // Log providers BEFORE adding Conscrypt
        Log.d(TAG, "Providers BEFORE Conscrypt installation:")
        logSecurityProviders()

        try {
            // Create Conscrypt provider
            val conscryptProvider = Conscrypt.newProvider()
            
            // Remove any existing Conscrypt provider first (in case of duplicate)
            Security.removeProvider("Conscrypt")
            
            // Install Conscrypt as the highest-priority provider (position 1)
            // This ensures it handles TLS operations, including client auth signing
            val position = Security.insertProviderAt(conscryptProvider, 1)
            
            if (position == 1) {
                Log.d(TAG, "Conscrypt installed successfully at position 1")
            } else if (position == -1) {
                Log.w(TAG, "Conscrypt was already installed (returned -1)")
            } else {
                Log.w(TAG, "Conscrypt installed at unexpected position: $position")
            }
            
            // Configure Conscrypt for better compatibility
            try {
                // Use engine sockets by default (better for client auth)
                Conscrypt.setUseEngineSocketByDefault(true)
                Log.d(TAG, "Conscrypt engine socket mode enabled")
            } catch (e: Exception) {
                Log.w(TAG, "Could not enable engine socket mode: ${e.message}")
            }

            // Log providers AFTER adding Conscrypt
            Log.d(TAG, "Providers AFTER Conscrypt installation:")
            logSecurityProviders()

            // Verify Conscrypt is working correctly
            verifyConscryptSetup()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to install Conscrypt provider: ${e.message}", e)
            Log.e(TAG, "mTLS operations may fail with NONEwithECDSA errors")
        }

        Log.d(TAG, "=== Security Provider Initialization Complete ===")
    }

    private fun logSecurityProviders() {
        Security.getProviders().forEachIndexed { index, provider ->
            Log.d(TAG, "  [${index + 1}] ${provider.name} v${provider.version}")
        }
    }

    private fun verifyConscryptSetup() {
        // Check if Conscrypt is the preferred provider for TLS
        val sslContextProvider = try {
            javax.net.ssl.SSLContext.getInstance("TLS").provider
        } catch (e: Exception) {
            null
        }
        
        Log.d(TAG, "Default TLS SSLContext provider: ${sslContextProvider?.name ?: "unknown"}")

        // Check if SHA256withECDSA is available (needed for CSR and TLS client auth)
        val signatureProviders = Security.getProviders("Signature.SHA256withECDSA")
        if (signatureProviders != null && signatureProviders.isNotEmpty()) {
            Log.d(TAG, "SHA256withECDSA available from: ${signatureProviders.map { it.name }}")
        } else {
            Log.w(TAG, "SHA256withECDSA not found in any provider!")
        }

        // Verify AndroidKeyStore is available
        val keystoreProviders = Security.getProviders("KeyStore.AndroidKeyStore")
        if (keystoreProviders != null && keystoreProviders.isNotEmpty()) {
            Log.d(TAG, "AndroidKeyStore available from: ${keystoreProviders.map { it.name }}")
        } else {
            Log.w(TAG, "AndroidKeyStore not found!")
        }
    }
}
