package com.pairsync.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.pairsync.app.core.Log
import com.pairsync.app.core.Mailbox

class PairSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("msg", "同步消息", NotificationManager.IMPORTANCE_HIGH)
            )
            nm.createNotificationChannel(
                NotificationChannel("svc", "同步服务", NotificationManager.IMPORTANCE_LOW)
            )
        }
        try {
            Mailbox.bootstrap(this)
            Log.log("app", "启动完成")
        } catch (e: Exception) {
            Log.log("app-err", e.toString())
        }
    }
}
