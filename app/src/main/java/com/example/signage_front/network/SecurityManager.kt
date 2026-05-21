package com.example.signage_front.network
//SecurityManager.kt
import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

object SecurityManager {
    private const val TAG = "SecurityManager"
    private const val KEY_ALIAS = "client_auth_key_v5" // bump alias — added DIGEST_NONE for TLS client auth
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
        Log.d(TAG, "Generating EC KeyPair in AndroidKeyStore...")

        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            Log.d(TAG, "Deleting existing key with alias: $KEY_ALIAS")
            keyStore.deleteEntry(KEY_ALIAS)
        }

        // EC P-256 key for TLS client authentication.
        // DIGEST_NONE is required for Conscrypt's TLS client auth which uses NONEwithECDSA
        // (Conscrypt pre-hashes the data and calls raw ECDSA signing).
        // We also include SHA-256/384/512 for CSR signing and other operations.
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(256) // P-256
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(
                KeyProperties.DIGEST_NONE,    // Required for TLS client auth (NONEwithECDSA)
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512
            )
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
            .apply { initialize(spec) }
            .generateKeyPair()

        Log.d(TAG, "EC KeyPair generated successfully with DIGEST_NONE support.")
    }

    /**
     * Deletes all entries from the AndroidKeyStore for this application.
     */
    fun nukeAllKeys() {
        Log.d(TAG, "Nuking all keys from $KEYSTORE_PROVIDER")
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.aliases().toList().forEach { alias ->
            Log.d(TAG, "Deleting alias: $alias")
            keyStore.deleteEntry(alias)
        }
    }


    /**
     * Custom ContentSigner that uses AndroidKeyStore for signing.
     * 
     * AndroidKeyStore doesn't expose a standard JCA Signature provider service,
     * so we can't use JcaContentSignerBuilder with setProvider("AndroidKeyStore").
     * Instead, we create the Signature instance directly and pass the AndroidKeyStore
     * private key to it - the key knows how to sign using the hardware-backed keystore.
     */
    private class AndroidKeyStoreContentSigner(
        private val privateKey: PrivateKey,
        private val signatureAlgorithm: String = "SHA256withECDSA"
    ) : ContentSigner {
        
        private val outputStream = ByteArrayOutputStream()
        
        // Algorithm identifier for SHA256withECDSA
        // OID 1.2.840.10045.4.3.2 = ecdsa-with-SHA256
        override fun getAlgorithmIdentifier(): AlgorithmIdentifier {
            return AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        }
        
        override fun getOutputStream(): OutputStream = outputStream
        
        override fun getSignature(): ByteArray {
            val data = outputStream.toByteArray()
            Log.d(TAG, "Signing ${data.size} bytes with $signatureAlgorithm")
            
            // Create Signature instance - when we pass an AndroidKeyStore key,
            // the system automatically routes to the correct implementation
            val signature = Signature.getInstance(signatureAlgorithm)
            signature.initSign(privateKey)
            signature.update(data)
            
            val sig = signature.sign()
            Log.d(TAG, "Signature generated: ${sig.size} bytes")
            return sig
        }
    }

    fun createCsr(context: Context): String {
        Log.d(TAG, "Creating CSR...")
        
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
        
        Log.d(TAG, "Private key algorithm: ${privateKey.algorithm}")
        Log.d(TAG, "Private key class: ${privateKey.javaClass.name}")
        Log.d(TAG, "Public key algorithm: ${publicKey.algorithm}")

        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        Log.d(TAG, "Creating CSR with CN: $androidId")

        val nameBuilder = X500NameBuilder(BCStyle.INSTANCE).apply {
            addRDN(BCStyle.CN, androidId)
        }

        // Use our custom ContentSigner that works with AndroidKeyStore keys
        val contentSigner = AndroidKeyStoreContentSigner(privateKey)
        
        val csr = JcaPKCS10CertificationRequestBuilder(nameBuilder.build(), publicKey)
            .build(contentSigner)

        val pem = "-----BEGIN CERTIFICATE REQUEST-----\n" +
                Base64.encodeToString(csr.encoded, Base64.DEFAULT) +
                "-----END CERTIFICATE REQUEST-----"

        Log.d(TAG, "CSR generated successfully.")
        return pem
    }

    fun saveCertificate(leafPem: String, rootCaPem: String? = null) {
        Log.d(TAG, "Saving certificate chain to alias: $KEY_ALIAS")
        val cf = CertificateFactory.getInstance("X.509")

        val certList = try {
            cf.generateCertificates(ByteArrayInputStream(leafPem.toByteArray(Charsets.UTF_8)))
                .map { it as X509Certificate }
                .toMutableList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse leaf PEM: ${e.message}")
            throw e
        }

        rootCaPem?.let {
            val rootCert = cf.generateCertificate(
                ByteArrayInputStream(it.toByteArray(Charsets.UTF_8))
            ) as X509Certificate
            val alreadyPresent = certList.any { existing ->
                existing.subjectX500Principal == rootCert.subjectX500Principal
            }
            if (!alreadyPresent) certList.add(rootCert)
        }

        if (certList.isEmpty()) throw Exception("No certificates found in PEM response.")

        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw Exception("Private key not found for alias $KEY_ALIAS")

        keyStore.setKeyEntry(KEY_ALIAS, entry.privateKey, null, certList.toTypedArray())
        Log.d(TAG, "Certificate chain saved (${certList.size} certs).")
    }
}
