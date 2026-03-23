package com.example.signage_front.network

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

object SecurityManager {
    private const val KEY_ALIAS = "client_auth_key"
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
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()
    }

    fun createCsr(): String {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey

        val entityName = X500Name("CN=${Build.SERIAL}")
        val p10Builder = JcaPKCS10CertificationRequestBuilder(entityName, publicKey)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val csr = p10Builder.build(signer)

        return "-----BEGIN CERTIFICATE REQUEST-----\n" +
                Base64.encodeToString(csr.encoded, Base64.NO_WRAP) +
                "\n-----END CERTIFICATE REQUEST-----"
    }

    fun saveCertificate(pemCertificate: String) {
        val cf = CertificateFactory.getInstance("X.509")
        val certBytes = pemCertificate
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\n", "")
            .let { Base64.decode(it, Base64.DEFAULT) }
        
        val cert = cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.setKeyEntry(KEY_ALIAS, getPrivateKey(), null, arrayOf(cert))
    }
}
