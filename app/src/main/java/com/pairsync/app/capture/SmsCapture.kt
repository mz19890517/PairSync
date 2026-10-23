package com.pairsync.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.pairsync.app.Config
import com.pairsync.app.core.Log
import com.pairsync.app.core.Mailbox
import org.json.JSONObject

class SmsCapture : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Config.isComplete()) return
        try {
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (msgs.isEmpty()) return
            val addr = msgs.firstOrNull()?.originatingAddress ?: return
            val body = msgs.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
            if (body.isBlank()) return
            val data = JSONObject()
                .put("addr", addr ?: "")
                .put("body", body)
                .put("date", System.currentTimeMillis())
            Mailbox.offer("sms", data)
            Mailbox.offerArchive("sms", data)
            Log.log("capture", "捕获短信 $addr")
        } catch (e: Exception) {
            Log.log("capture-err", e.toString())
        }
    }
}
