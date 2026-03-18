package com.rafad.indicvisiondic

import android.content.Context
import android.provider.Settings // 🚀 NEW: Connects to the physical hardware ID
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature

class DeviceKeyManager(private val context: Context) { // 🚀 Notice the 'private val'

    // Connect to the physical Android hardware keystore
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    // The unique alias for our hardware key in the vault
    private val KEY_ALIAS = "IndicVisionHardwareKey"

    init {
        generateDeviceKeyIfNeeded()
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
     * 🚀 THE MAGIC FIX: Reads the permanent Android Hardware ID.
     * This survives uninstalls and "Clear Data" wipes!
     */
    fun getDeviceId(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return androidId ?: "UNKNOWN_HARDWARE_ID"
    }

    fun getPublicKeyBase64(): String {
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    fun signData(data: String): String {
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(data.toByteArray(Charsets.UTF_8))

        val signatureBytes = signature.sign()
        return Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
    }
}