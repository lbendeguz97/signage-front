package com.example.signage_front.network

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

object SecurityManager {
    private const val TAG = "SecurityManager"
    // V3 Alias to ensure we bypass any cached KeyStore restrictions
    private const val KEY_ALIAS = "client_auth_key_v3"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    fun hasValidKey(): Boolean {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.containsAlias(KEY_ALIAS) && keyStore.getCertificate(KEY_ALIAS) != null
    }

    fun getPrivateKey(): PrivateKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
    }

    fun getCertificate(): X509Certificate? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
    }

    fun generateKeyPair() {
        Log.d(TAG, "Generating new KeyPair (v3) in AndroidKeyStore...")
        
        // Clean up existing key if present
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER)
        
        // Permissive spec required for Conscrypt's TLS handshake implementation
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or 
                    KeyProperties.PURPOSE_VERIFY or 
                    KeyProperties.PURPOSE_DECRYPT or
                    KeyProperties.PURPOSE_ENCRYPT
        )
            .setKeySize(2048)
            .setDigests(
                KeyProperties.DIGEST_SHA1,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
                KeyProperties.DIGEST_NONE // Mandatory for TLS raw signing
            )
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                KeyProperties.SIGNATURE_PADDING_RSA_PSS
            )
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE, // Mandatory for raw RSA in TLS
                KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1
            )
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()
        Log.d(TAG, "KeyPair (v3) generated successfully.")
    }

    fun createCsr(context: Context): String {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        Log.d(TAG, "Creating CSR with CN (ANDROID_ID): $androidId")

        val nameBuilder = X500NameBuilder(BCStyle.INSTANCE)
        nameBuilder.addRDN(BCStyle.CN, androidId)
        val entityName = nameBuilder.build()

        val p10Builder = JcaPKCS10CertificationRequestBuilder(entityName, publicKey)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val csr = p10Builder.build(signer)

        val csrEncoded = Base64.encodeToString(csr.encoded, Base64.DEFAULT)

        val pem = "-----BEGIN CERTIFICATE REQUEST-----\n" +
                csrEncoded +
                "-----END CERTIFICATE REQUEST-----"
        
        Log.d(TAG, "CSR generated successfully")
        return pem
    }

    fun saveCertificate(leafPem: String, rootCaPem: String? = null) {
        Log.d(TAG, "Attempting to save certificate to v3 alias...")
        val cf = CertificateFactory.getInstance("X.509")
        
        val certList = try {
            cf.generateCertificates(ByteArrayInputStream(leafPem.toByteArray(Charsets.UTF_8)))
                .map { it as X509Certificate }
                .toMutableList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse certificate PEM: ${e.message}")
            throw e
        }
            
        rootCaPem?.let {
            val rootCert = cf.generateCertificate(ByteArrayInputStream(it.toByteArray(Charsets.UTF_8))) as X509Certificate
            if (!certList.contains(rootCert)) {
                certList.add(rootCert)
            }
        }
        
        if (certList.isEmpty()) throw Exception("No certificates found in response")
        Log.d(TAG, "Parsed ${certList.size} certificates from response")

        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        
        keyStore.setKeyEntry(KEY_ALIAS, privateKey, null, certList.toTypedArray())
        Log.d(TAG, "Certificate chain saved successfully to $KEY_ALIAS")
    }
}
