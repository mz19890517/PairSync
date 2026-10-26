package com.pairsync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pairsync.app.core.Log
import com.pairsync.app.core.Mailbox
import org.json.JSONObject
import java.io.File

class DebugBridge : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val dbg = ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        if (dbg == 0) return
        when (intent.action) {
            ACTION_EXPORT -> {
                val cfg = JSONObject()
                    .put("endpoint", Config.endpoint)
                    .put("bucket", Config.bucket)
                    .put("region", Config.region)
                    .put("ak", Config.ak)
                    .put("sk", Config.sk)
                    .put("key", Config.keyB64)
                    .put("name", Config.deviceName)
                    .put("interval", Config.intervalSec)
                    .put("chatInterval", Config.chatIntervalSec)
                    .put("cloudHost", Config.cloudHost)
                    .put("cloudTok", Config.cloudTok)
                    .put("cloudGatePw", Config.cloudGatePw)
                val dir = ctx.getExternalFilesDir(null) ?: return
                File(dir, "export.json").writeText(cfg.toString())
                Log.log("debug", "配置已导出 export.json")
            }
            ACTION_IMPORT -> {
                try {
                    val txt = try {
                        val path = intent.getStringExtra("file")
                        val dir = ctx.getExternalFilesDir(null) ?: return
                        File(dir, path ?: "export.json").readText()
                    } catch (e: Exception) {
                        null
                    } ?: intent.getStringExtra("cfg") ?: return
                    val o = JSONObject(txt)
                    if (o.has("endpoint")) Config.endpoint = o.getString("endpoint")
                    if (o.has("bucket")) Config.bucket = o.getString("bucket")
                    if (o.has("region")) Config.region = o.getString("region")
                    if (o.has("ak")) Config.ak = o.getString("ak")
                    if (o.has("sk")) Config.sk = o.getString("sk")
                    if (o.has("key")) Config.keyB64 = o.getString("key")
                    if (o.has("name")) Config.deviceName = o.getString("name")
                    if (o.has("interval")) Config.intervalSec = o.getInt("interval")
                    if (o.has("chatInterval")) Config.chatIntervalSec = o.optInt("chatInterval", 5)
                    if (o.has("cloudHost")) Config.cloudHost = o.getString("cloudHost")
                    if (o.has("cloudTok")) Config.cloudTok = o.getString("cloudTok")
                    if (o.has("cloudGatePw")) Config.cloudGatePw = o.getString("cloudGatePw")
                    Config.runOnBoot = true
                    Mailbox.refresh()
                    Log.log("debug", "配置已导入，设备=${Config.deviceName} (${Config.deviceId})")
                } catch (e: Exception) {
                    Log.log("debug-err", e.toString())
                }
            }
        }
    }

    companion object {
        const val ACTION_EXPORT = "com.pairsync.app.DEBUG_EXPORT"
        const val ACTION_IMPORT = "com.pairsync.app.DEBUG_IMPORT"
    }
}
