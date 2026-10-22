package com.sempermechanics.semper.data.integrity

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.sempermechanics.semper.data.account.DeviceKeys
import com.sempermechanics.semper.data.account.entitlement.DeviceKeyThumbprint
import timber.log.Timber
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Signs a manifest's canonical bytes: the device key on a phone, a software key in a test. */
interface RecordSigner {

    /** The public key's X.509 SubjectPublicKeyInfo DER. */
    fun publicKeyDer(): ByteArray

    /** SHA256withECDSA over [message], as base64 (standard alphabet) DER. */
    fun sign(message: ByteArray): String
}

/** The Android Keystore device key ([DeviceKeys]): the one the backend's device binding checks. */
internal class DeviceRecordSigner(private val keys: DeviceKeys) : RecordSigner {

    override fun publicKeyDer(): ByteArray = keys.publicKeyDer()

    override fun sign(message: ByteArray): String = keys.signMessage(message)

    companion object {
        /** This phone's signer, or null when the Keystore cannot give one (Robolectric, a Keystore fault). */
        fun orNull(context: Context): RecordSigner? = runCatching { DeviceRecordSigner(DeviceKeys(context)) }
            .onFailure { Timber.w(it, "No device key for the record manifest; writing it unsigned") }
            .getOrNull()
    }
}

/**
 * This phone's device-key thumbprint (`DeviceKeyThumbprint.of` its public key),
 * which a manifest's `keyThumbprint` must equal to read Verified here
 * (ADR-028). Read once from the Keystore and kept; null when the Keystore
 * cannot give a key (Robolectric, a Keystore fault), and then every signed
 * manifest reads as signed elsewhere. Keystore reads: off the main thread.
 */
object LocalRecordKey {

    @Volatile
    private var cached: String? = null

    private var source: (Context) -> String? = ::fromKeystore

    fun thumbprint(context: Context): String? = cached ?: source(context)?.also { cached = it }

    /** Tests: answer [thumbprint] with [source] from now on, forgetting what was read. */
    @VisibleForTesting
    fun useForTest(source: (Context) -> String? = ::fromKeystore) {
        this.source = source
        cached = null
    }

    private fun fromKeystore(context: Context): String? =
        runCatching { DeviceKeyThumbprint.of(DeviceKeys(context).publicKeyDer()) }
            .onFailure { Timber.w(it, "No device key to check record manifests against") }
            .getOrNull()
}

/** Checks a manifest's signature against the public key it carries. */
internal object ManifestSignature {

    private const val ALGORITHM = "SHA256withECDSA"

    /** base64 (standard alphabet) of [der], as [RecordManifest.publicKey] holds it. */
    fun encodeKey(der: ByteArray): String = Base64.getEncoder().encodeToString(der)

    /**
     * Whether [signature] is [publicKey]'s over [message], and [publicKey] is
     * the key [thumbprint] names. Anything malformed is false, never a throw.
     */
    fun holds(publicKey: String, thumbprint: String?, message: ByteArray, signature: String): Boolean =
        runCatching {
            val der = Base64.getDecoder().decode(publicKey)
            if (thumbprint != DeviceKeyThumbprint.of(der)) return@runCatching false
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(message)
                verify(Base64.getDecoder().decode(signature))
            }
        }.getOrDefault(false)
}
