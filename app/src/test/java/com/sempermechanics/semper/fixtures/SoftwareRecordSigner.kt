package com.sempermechanics.semper.fixtures

import com.sempermechanics.semper.data.integrity.RecordSigner
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * A [RecordSigner] on a software EC P-256 key, signing as the Keystore
 * device key does (SHA256withECDSA, base64 DER), for the JVM, where there is
 * no Android Keystore.
 */
class SoftwareRecordSigner(
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair(),
) : RecordSigner {

    var signed: Int = 0
        private set

    override fun publicKeyDer(): ByteArray = pair.public.encoded

    override fun sign(message: ByteArray): String {
        signed++
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(message)
            sign()
        }
        return Base64.getEncoder().encodeToString(der)
    }
}
