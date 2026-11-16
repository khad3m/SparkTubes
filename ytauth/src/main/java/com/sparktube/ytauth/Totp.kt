package com.sparktube.ytauth

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30-second step) with a compact Base32
 * decoder — generates the 2FA verification code Google asks for during
 * sign-in. No external dependency.
 */
object Totp {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32Decode(input: String): ByteArray {
        val clean = input.uppercase().replace(Regex("[^A-Z2-7]"), "")
        if (clean.isEmpty()) throw IllegalArgumentException("empty base32 secret")
        var bits = 0
        var value = 0
        val out = ArrayList<Byte>(clean.size * 5 / 8)
        for (ch in clean) {
            value = (value shl 5) or ALPHABET.indexOf(ch)
            bits += 5
            if (bits >= 8) {
                out.add(((value shr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    fun generate(
        secretBase32: String,
        epochSeconds: Long = System.currentTimeMillis() / 1000L,
        digits: Int = 6,
        periodSeconds: Int = 30
    ): String {
        val key = base32Decode(secretBase32)
        val counter = epochSeconds / periodSeconds
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xFF).toByte()
            c = c shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(msg)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val bin = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return ((bin % 10.0.pow(digits)).toInt()).toString().padStart(digits, '0')
    }
}
