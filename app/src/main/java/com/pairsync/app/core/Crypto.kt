package com.pairsync.app.core

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    fun encrypt(data: ByteArray, keyB64: String): ByteArray? = try {
        val key = Base64.decode(keyB64, Base64.DEFAULT)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        iv + c.doFinal(data)
    } catch (e: Exception) {
        Log.log("crypto-err", "encrypt: $e")
        null
    }

    fun decrypt(blob: ByteArray, keyB64: String): ByteArray? = try {
        val key = Base64.decode(keyB64, Base64.DEFAULT)
        val iv = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.doFinal(ct)
    } catch (e: Exception) {
        null
    }
}
