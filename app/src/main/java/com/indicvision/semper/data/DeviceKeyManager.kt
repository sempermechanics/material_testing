// Keystore/crypto: literal key sizes and the early-return guards over key state
// read clearest inline, so MagicNumber / ReturnCount are suppressed here.
@file:Suppress("MagicNumber", "ReturnCount")

package com.indicvision.semper.data

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Device identity for the challenge-response device binding.
 *
 * - A non-exportable **EC P-256** key pair lives in the Android Keystore; the
 *   private key never leaves the device. The public key is uploaded once at
 *   registration and stored in Firestore by the backend.
 * - The device id is a random UUID persisted in app-private storage — **not**
 *   IMEI / serial / MAC / ANDROID_ID (per the security requirements).
 * - [signMessage] produces an ECDSA-SHA256 signature the backend verifies with
 *   the stored public key (see backend/app/deps.py).
 */
class DeviceKeyManager(private val context: Context) {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val KEY_ALIAS = "IndicDeviceKeyEc"
        const val PREFS = "indic_device"
        const val K_DEVICE_ID = "device_id"

        // The infamous Android 2.2 bug value shared by many devices — never use it.
        const val LEGACY_BAD_ANDROID_ID = "9774d56d682e549c"
    }

    init {
        generateDeviceKeyIfNeeded()
    }

    private fun generateDeviceKeyIfNeeded() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore",
            )
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
    }

    /**
     * Stable device id that **survives app reinstalls**: `ANDROID_ID` (scoped to
     * the app signing key + user + device — not a hardware serial/IMEI/MAC, needs
     * no permission; resets only on factory reset). Because it survives reinstall,
     * the backend can *heal* the re-registered Keystore key on the same device id
     * instead of treating a reinstall as a brand-new device. Falls back to a
     * persisted random UUID if ANDROID_ID is unavailable.
     */
    @Suppress("HardwareIds") // ANDROID_ID is app-scoped, not a hardware identifier
    fun getDeviceId(): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        )
        if (!androidId.isNullOrBlank() && androidId != LEGACY_BAD_ANDROID_ID) {
            return "and-$androidId"
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(K_DEVICE_ID, null)?.let { return it }
        val id = "dev-" + UUID.randomUUID().toString()
        prefs.edit().putString(K_DEVICE_ID, id).apply()
        return id
    }

    /** SubjectPublicKeyInfo as a standard PEM block (parsed by the backend). */
    fun getPublicKeyPem(): String {
        val der = keyStore.getCertificate(KEY_ALIAS).publicKey.encoded
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        val wrapped = b64.chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$wrapped\n-----END PUBLIC KEY-----\n"
    }

    /** ECDSA-SHA256 over [message]; returns base64(DER) for the X-Signature header. */
    fun signMessage(message: ByteArray): String {
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(message)
        }
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }
}
