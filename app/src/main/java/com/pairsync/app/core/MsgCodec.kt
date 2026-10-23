package com.pairsync.app.core

import org.json.JSONObject

data class Envelope(
    val t: String,
    val from: String,
    val fromName: String,
    val ts: Long,
    val data: JSONObject
) {
    fun keyText(): String = when (t) {
        "notif" -> data.optString("packageName") + "|" + data.optString("title")
        "sms" -> "SMS " + data.optString("addr")
        else -> t
    }
}

object MsgCodec {
    fun encode(type: String, from: String, fromName: String, data: JSONObject): String =
        JSONObject()
            .put("t", type)
            .put("from", from)
            .put("fromName", fromName)
            .put("ts", System.currentTimeMillis())
            .put("data", data)
            .toString()

    fun decode(txt: String): Envelope? = try {
        val o = JSONObject(txt)
        Envelope(
            o.optString("t"),
            o.optString("from"),
            o.optString("fromName"),
            o.optLong("ts"),
            o.optJSONObject("data") ?: JSONObject()
        )
    } catch (e: Exception) {
        null
    }
}
