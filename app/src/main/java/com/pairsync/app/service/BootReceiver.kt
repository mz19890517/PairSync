package com.pairsync.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pairsync.app.Config
import com.pairsync.app.core.Mailbox

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Config.runOnBoot || !Config.isComplete()) return
        Mailbox.bootstrap(ctx)
        ctx.startForegroundService(SyncService.startIntent(ctx))
    }
}
