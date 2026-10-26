package com.pairsync.app.core

import org.json.JSONObject

data class Envelope(
    val t: String,
    val from: String,
    val fromName: String,
    val ts: Long,
    val data: JSONObject,
    val to: String = ""
) {
    fun keyText(): String = when (t) {
        "notif" -> data.optString("packageName") + "|" + data.optString("title")
        "sms" -> "SMS " + data.optString("addr")
        "chat" -> "聊天 " + data.optString("text").take(20)
        else -> t
    }
}

object MsgCodec {
    fun encode(type: String, from: String, fromName: String, data: JSONObject): String =
        encode(type, from, fromName, System.currentTimeMillis(), data)

    fun encode(type: String, from: String, fromName: String, ts: Long, data: JSONObject, to: String = ""): String =
        JSONObject()
            .put("t", type)
            .put("from", from)
            .put("fromName", fromName)
            .put("ts", ts)
            .put("to", to)
            .put("data", data)
            .toString()

    fun decode(txt: String): Envelope? = try {
        val o = JSONObject(txt)
        Envelope(
            o.optString("t"),
            o.optString("from"),
            o.optString("fromName"),
            o.optLong("ts"),
            o.optJSONObject("data") ?: JSONObject(),
            o.optString("to")
        )
    } catch (e: Exception) {
        null
    }
}
