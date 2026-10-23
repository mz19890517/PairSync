package com.pairsync.app.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.pairsync.app.Config
import com.pairsync.app.core.Log
import com.pairsync.app.core.Mailbox
import org.json.JSONObject

class NotifCapture : NotificationListenerService() {

    private val lastSeen = HashMap<String, Long>()
    private val appNameCache = HashMap<String, String>()

    @Volatile
    private var lastOffer = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.log("capture", "通知监听已连接")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Config.isComplete()) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        if (pkg in selfExcludes) return

        val e = sbn.notification.extras
        val title = e.getCharSequence("android.title")?.toString() ?: ""
        val text = e.getCharSequence("android.text")?.toString() ?: ""
        val subText = e.getCharSequence("android.subText")?.toString() ?: ""
        val bigText = e.getCharSequence("android.bigText")?.toString() ?: text

        val body = if (bigText.isNotBlank() && bigText != text) bigText else text
        if (title.isBlank() && body.isBlank()) return
        if (sbn.isOngoing) return

        val fp = pkg + "|" + title + "|" + body
        val now = System.currentTimeMillis()
        val last = lastSeen[fp] ?: 0L
        if (now - last < 15000) return
        val gap = now - lastOffer
        if (gap < 4000) return
        lastSeen[fp] = now
        lastOffer = now
        if (lastSeen.size > 300) lastSeen.clear()

        val data = JSONObject()
            .put("packageName", pkg)
            .put("appName", appName(pkg))
            .put("title", title)
            .put("text", body)
            .put("subText", subText)
            .put("postTime", sbn.postTime)
        try {
            Mailbox.offer("notif", data)
            Mailbox.offerArchive("notif", data)
        } catch (ex: Exception) {
            Log.log("capture-err", ex.toString())
        }
    }

    private fun appName(pkg: String): String {
        appNameCache[pkg]?.let { return it }
        val name = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        appNameCache[pkg] = name
        return name
    }

    companion object {
        private val selfExcludes = setOf("com.android.systemui", "com.android.launcher")
    }
}
