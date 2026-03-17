package com.rafad.indicvisiondic

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.UUID

class DeviceKeyManager(context: Context) {

    // Connect to the physical Android hardware keystore
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val prefs: SharedPreferences = context.getSharedPreferences("device_security_prefs", Context.MODE_PRIVATE)

    // The unique alias for our hardware key in the vault
    private val KEY_ALIAS = "IndicVisionHardwareKey"

    init {
        generateDeviceKeyIfNeeded()
        generateDeviceIdIfNeeded()
    }

    /**
     * Instructs the physical Android hardware to generate an un-extractable RSA Key Pair.
     */
    private fun generateDeviceKeyIfNeeded() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build()

            keyPairGenerator.initialize(parameterSpec)
            keyPairGenerator.generateKeyPair()
        }
    }

    /**
     * Generates a random UUID to uniquely identify this specific installation/phone.
     */
    private fun generateDeviceIdIfNeeded() {
        if (!prefs.contains("device_uuid")) {
            prefs.edit().putString("device_uuid", UUID.randomUUID().toString()).apply()
        }
    }

    fun getDeviceId(): String {
        return prefs.getString("device_uuid", "UNKNOWN_DEVICE")!!
    }

    /**
     * Retrieves the Public Key (which is safe to send to Supabase so it can verify us).
     */
    fun getPublicKeyBase64(): String {
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    /**
     * Uses the hardware Private Key to mathematically sign a string of data.
     */
    fun signData(data: String): String {
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(data.toByteArray(Charsets.UTF_8))

        val signatureBytes = signature.sign()
        return Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
    }
}