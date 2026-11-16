package com.roohu.connect

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun sha256Hex(b: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

fun hmacHex(key: String, data: String): String {
    val m = Mac.getInstance("HmacSHA256")
    m.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
    return m.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
}
