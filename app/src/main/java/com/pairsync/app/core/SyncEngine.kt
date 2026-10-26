package com.pairsync.app.core

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pairsync.app.Config
import com.pairsync.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

object Notifier {
    fun post(ctx: Context, fromName: String, title: String, text: String): Boolean {
        val enabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        if (!enabled) return false
        val n: Notification = NotificationCompat.Builder(ctx, "msg")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("「$fromName」$title")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(ctx).notify(title.hashCode() * 31 + text.hashCode(), n)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}

class SyncEngine(private val ctx: Context, private val scope: kotlinx.coroutines.CoroutineScope) {
    private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            Log.log("engine", "轮询启动 间隔=${Config.intervalSec}s 设备=${Config.deviceName}(${Config.deviceId})")
            var tick = 0
            var backoff = 1
            while (isActive) {
                val t0 = System.currentTimeMillis()
                try {
                    if (tick % 6 == 0) pushStatus(tick)
                    val msgs = Mailbox.drain()
                    for ((key, env, label) in msgs) {
                        val ok = handle(env)
                        if (ok) {
                            Mailbox.ack(key)
                            Log.log("recv", "${env.fromName} $label")
                        } else {
                            Log.log("recv", "${env.fromName} $label 无法显示(缺少通知权限)，已在队列中保留")
                        }
                    }
                    if (msgs.isEmpty() && tick % 15 == 0) Log.log("idle", "空闲")
                    backoff = 1
                } catch (e: Exception) {
                    val m = e.message ?: ""
                    Log.log("engine-err", e.toString())
                    if (m.contains("429")) {
                        backoff = (backoff * 2).coerceAtMost(16)
                        Log.log("engine", "S3 限流(429)，拉长轮询 x$backoff")
                    }
                }
                val spent = (System.currentTimeMillis() - t0).toInt()
                val wait = (Config.intervalSec * 1000 - spent).coerceAtLeast(1000)
                delay(wait.toLong() * backoff)
                tick++
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        Log.log("engine", "轮询已停止")
    }

    fun running(): Boolean = job != null

    private fun handle(env: Envelope): Boolean {
        val d = env.data
        return when (env.t) {
            "notif" -> {
                val title = d.optString("title").ifEmpty { d.optString("appName") }
                val text = d.optString("text").ifEmpty { d.optString("subText") }
                Notifier.post(ctx, env.fromName, title, text)
            }
            "sms" -> Notifier.post(
                ctx, env.fromName,
                "短信 " + d.optString("addr"),
                d.optString("body")
            )
            "status" -> {
                Log.log("peer", "${env.fromName} 电量${d.optInt("battery")}% ${if (d.optBoolean("charging")) "充电中 " else ""}${d.optJSONObject("location")?.let { "位置(${it.optDouble("lat")},${it.optDouble("lon")})" } ?: "无位置"}")
                true
            }
            "chat" -> {
                val toMark = if (env.to.isEmpty()) "广播" else "私信"
                Notifier.post(ctx, env.fromName, "聊天消息（$toMark）", d.optString("text"))
            }
            else -> {
                Log.log("recv", "未知类型 ${env.t}")
                true
            }
        }
    }

    private fun pushStatus(tick: Int) {
        val data = JSONObject()
        try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                data.put("battery", i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1))
                data.put("charging", i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            }
            val loc = currentLocation(tick)
            if (loc != null) data.put("location", loc)
        } catch (e: Exception) {
            Log.log("status-err", e.toString())
        }
        scope.launch { Mailbox.pushStatus(data) }
    }

    private fun hasBgLocation(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun currentLocation(tick: Int): JSONObject? {
        if (!hasBgLocation()) {
            if (tick % 60 == 0) Log.log("loc", "未授权后台定位，状态不含位置")
            return null
        }
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val best = lm.allProviders.filter { lm.isProviderEnabled(it) }
                .mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.time } ?: return null
            JSONObject()
                .put("lat", best.latitude)
                .put("lon", best.longitude)
                .put("acc", best.accuracy.toDouble())
                .put("prov", best.provider)
                .put("t", best.time)
        } catch (e: Exception) {
            Log.log("loc-err", e.toString())
            null
        }
    }
}
