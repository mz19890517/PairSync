package com.pairsync.app.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pairsync.app.R
import com.pairsync.app.core.Log
import com.pairsync.app.core.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class SyncService : Service() {
    companion object {
        const val ACTION_START = "com.pairsync.app.START"
        const val ACTION_STOP = "com.pairsync.app.STOP"

        @Volatile
        var running: Boolean = false

        fun startIntent(ctx: Context): Intent = Intent(ctx, SyncService::class.java).setAction(ACTION_START)

        fun stopIntent(ctx: Context): Intent = Intent(ctx, SyncService::class.java).setAction(ACTION_STOP)
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private lateinit var engine: SyncEngine

    override fun onCreate() {
        super.onCreate()
        engine = SyncEngine(this, scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                engine.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startFg()
                engine.start()
                running = true
                Log.log("service", "前台服务已启动")
            }
        }
        return START_STICKY
    }

    private fun startFg() {
        val n: Notification = NotificationCompat.Builder(this, "svc")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("信息互相同步中")
            .setContentText("通知/短信/状态/位置 已在交换")
            .setOngoing(true)
            .build()
        val sdk = Build.VERSION.SDK_INT
        when {
            sdk >= 34 -> startForeground(
                1001, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            sdk >= 29 -> startForeground(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(1001, n)
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
