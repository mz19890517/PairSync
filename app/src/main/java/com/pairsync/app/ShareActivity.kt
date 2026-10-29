package com.pairsync.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pairsync.app.core.ChatStore
import com.pairsync.app.core.ImgUtil
import com.pairsync.app.core.Mailbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareActivity : AppCompatActivity() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = intent.action
        if (a != Intent.ACTION_SEND && a != Intent.ACTION_SEND_MULTIPLE) {
            finish()
            return
        }
        val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (stream != null) {
            chooseTarget("", stream)
            return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text != null && text.isNotEmpty()) {
            chooseTarget(text, null)
            return
        }
        val clip = intent.clipData
        for (i in 0 until (clip?.itemCount ?: 0)) {
            val u = clip?.getItemAt(i)?.uri
            if (u != null) {
                chooseTarget("", u)
                return
            }
        }
        finish()
    }

    private fun chooseTarget(text: String, uri: Uri?) {
        io.launch {
            val parts = ChatStore.participants(Config.deviceId)
            withContext(Dispatchers.Main) {
                val names = ArrayList<String>()
                names.add("全部设备（广播）")
                for (p in parts) names.add(p.second)
                AlertDialog.Builder(this@ShareActivity)
                    .setTitle("分享到 PairSync")
                    .setItems(names.toTypedArray()) { _, w ->
                        val to = if (w == 0) "" else parts.getOrNull(w - 1)?.first ?: return@setItems
                        if (uri != null) io.launch { deliverImage(uri, to) } else io.launch { deliverText(text, to) }
                    }
                    .setOnCancelListener { finish() }
                    .show()
            }
        }
    }

    private suspend fun deliverText(text: String, to: String) {
        Mailbox.sendChat(text, to, System.currentTimeMillis())
        open(to)
    }

    private suspend fun deliverImage(uri: Uri, to: String) {
        try {
            var mime = contentResolver.getType(uri) ?: "application/octet-stream"
            var name = ""
            try {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: ""
                }
            } catch (e: Exception) {
            }
            if (name.isEmpty()) name = "share-" + System.currentTimeMillis() + "." + mime.substringAfterLast('/')
            var data = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (data == null || data.isEmpty()) {
                withContext(Dispatchers.Main) {
                    toast("分享内容无法读取")
                    finish()
                }
                return@deliverImage
            }
            if (mime.startsWith("image/") && data.size <= 25 * 1024 * 1024) {
                try {
                    val cp = ImgUtil.compressImage(data)
                    if (cp != null) {
                        data = cp.first
                        mime = cp.second
                        name = name.substringBeforeLast('.', name) + ".jpg"
                    }
                } catch (e: Exception) {
                }
            }
            if (data.size > 8 * 1024 * 1024) {
                withContext(Dispatchers.Main) {
                    toast("文件超过 8MB，暂不支持")
                    finish()
                }
                return@deliverImage
            }
            Mailbox.sendAtt(data, name, mime, to, System.currentTimeMillis())
            open(to)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                toast("分享失败: " + e.message)
                finish()
            }
        }
    }

    private suspend fun open(to: String) {
        withContext(Dispatchers.Main) {
            val i = Intent(this@ShareActivity, ChatActivity::class.java)
                .putExtra("dev", to)
                .putExtra("name", if (to.isEmpty()) "全部设备" else to)
            startActivity(i)
            finish()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
