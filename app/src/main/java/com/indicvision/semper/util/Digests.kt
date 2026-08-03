package com.indicvision.semper.util

import java.security.MessageDigest

/** Shared digest helpers (upload integrity, API request signing). */
object Digests {
    private const val SHA_256 = "SHA-256"

    fun sha256(): MessageDigest = MessageDigest.getInstance(SHA_256)

    fun sha256(bytes: ByteArray): ByteArray = sha256().digest(bytes)
}
