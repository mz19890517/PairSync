package com.pairsync.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.UUID

object Config {
    const val BASE = "同步信息/"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (this::prefs.isInitialized) return
        val master = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        prefs = EncryptedSharedPreferences.create(
            ctx, "pairsync_cfg", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        if (prefs.getString("deviceId", "").isNullOrEmpty()) {
            prefs.edit().putString("deviceId", UUID.randomUUID().toString().replace("-", "").take(12)).apply()
        }
    }

    private fun put(k: String, v: String) = prefs.edit().putString(k, v.trim()).apply()
    private fun get(k: String, d: String = "") = prefs.getString(k, d)?.trim() ?: d

    var endpoint: String
        get() = get("endpoint", "https://s3.cstcloud.cn")
        set(v) = put("endpoint", v)
    var bucket: String
        get() = get("bucket")
        set(v) = put("bucket", v)
    var region: String
        get() = get("region", "us-east-1")
        set(v) = put("region", v)
    var ak: String
        get() = get("ak")
        set(v) = put("ak", v)
    var sk: String
        get() = get("sk")
        set(v) = put("sk", v)
    var keyB64: String
        get() = get("key")
        set(v) = put("key", v)
    var deviceName: String
        get() = get("name", Build.MODEL ?: "Android")
        set(v) = put("name", v)
    var intervalSec: Int
        get() = get("interval", "10").toIntOrNull() ?: 10
        set(v) = put("interval", v.toString())
    var runOnBoot: Boolean
        get() = prefs.getBoolean("runOnBoot", false)
        set(v) = prefs.edit().putBoolean("runOnBoot", v).apply()

    var legacyDone: Boolean
        get() = prefs.getBoolean("legacyDone", false)
        set(v) = prefs.edit().putBoolean("legacyDone", v).apply()

    var cloudHost: String
        get() = get("cloudHost")
        set(v) = put("cloudHost", v)
    var cloudTok: String
        get() = get("cloudTok")
        set(v) = put("cloudTok", v)
    var cloudGatePw: String
        get() = get("cloudGatePw")
        set(v) = put("cloudGatePw", v)

    val cloudOk: Boolean
        get() = cloudHost.isNotEmpty() && cloudTok.isNotEmpty() && cloudGatePw.isNotEmpty()

    val deviceId: String get() = get("deviceId")

    fun isComplete(): Boolean =
        endpoint.isNotEmpty() && bucket.isNotEmpty() && ak.isNotEmpty() && sk.isNotEmpty() && keyB64.isNotEmpty()

    fun newKeyB64(): String {
        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }
}
