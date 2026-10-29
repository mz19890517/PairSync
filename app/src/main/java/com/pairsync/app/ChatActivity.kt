package com.pairsync.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.pairsync.app.core.ChatStore
import com.pairsync.app.core.CloudApi
import com.pairsync.app.core.Envelope
import com.pairsync.app.core.ImgUtil
import com.pairsync.app.core.Mailbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    private lateinit var list: ListView
    private lateinit var tvState: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnAtt: Button
    private lateinit var chips: Map<String, TextView>
    private var dev = ""
    private var peer = ""
    private var filter = "all"

    private class Bub(val env: Envelope, val out: Boolean)

    private val all = ArrayList<Bub>()
    private val bubs = ArrayList<Bub>()
    private var cursor: Long? = null
    private var loading = false
    private val downloading = HashSet<String>()

    private inner class BubAdapter : BaseAdapter() {
        override fun getCount() = bubs.size
        override fun getItem(i: Int) = bubs[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
            val b = bubs[i]
            val d = b.env.data
            val kind = if (b.env.t == "chat") d.optString("kind") else ""
            val v = LayoutInflater.from(this@ChatActivity).inflate(
                if (kind == "img" || kind == "file") R.layout.item_bubble_attach else R.layout.item_bubble,
                parent, false
            )
            val wrap = v.findViewById<LinearLayout>(R.id.bubbleWrap)
            val lp = wrap.layoutParams as FrameLayout.LayoutParams
            if (b.out) {
                lp.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                wrap.setBackgroundResource(R.drawable.bg_bubble_out)
            } else {
                lp.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                wrap.setBackgroundResource(R.drawable.bg_bubble_in)
            }
            wrap.layoutParams = lp
            val iv = v.findViewById<ImageView>(R.id.ivBubbleImage)
            val attRow = v.findViewById<LinearLayout>(R.id.attRow)
            val tvName = v.findViewById<TextView>(R.id.tvAttName)
            val tvSize = v.findViewById<TextView>(R.id.tvAttSize)
            val tvBody = v.findViewById<TextView>(R.id.tvBubbleBody)
            if (kind != "") {
                val f = attFile(d.optString("key"), d.optString("name"))
                if (kind == "img") {
                    attRow?.visibility = View.GONE
                    iv?.visibility = View.VISIBLE
                    if (f.exists()) {
                        iv?.setImageBitmap(decodeScaled(f))
                        iv?.setOnClickListener {
                            try {
                                startActivity(Intent(this@ChatActivity, ImagePreviewActivity::class.java).putExtra("path", f.path))
                            } catch (e: Exception) {
                                openFile(f, d.optString("mime"))
                            }
                        }
                    } else {
                        tvBody.visibility = View.VISIBLE
                        tvBody.text = if (d.optBoolean("up")) "发送中…" else "加载图片…"
                        queueAtt(b)
                    }
                } else {
                    iv?.visibility = View.GONE
                    attRow?.visibility = View.VISIBLE
                    tvName?.text = d.optString("name")
                    tvSize?.text = if (d.optBoolean("up")) "发送中…" else fmtSize(d.optInt("size"))
                    attRow?.setOnClickListener {
                        if (f.exists()) {
                            openFile(f, d.optString("mime"))
                        } else {
                            tvBody.visibility = View.VISIBLE
                            tvBody.text = "文件下载中…"
                            queueAtt(b)
                        }
                    }
                }
            } else {
                iv?.visibility = View.GONE
                attRow?.visibility = View.GONE
                tvBody.visibility = View.VISIBLE
                if (b.env.t == "chat") {
                    val showFrom = dev == "@all" && !b.out
                    tvBody.text = (if (showFrom) b.env.fromName + ": " else "") + d.optString("text")
                    if (d.optBoolean("up")) tvBody.text = tvBody.text.toString() + "（发送中…）"
                } else {
                    val head = when (b.env.t) {
                        "notif" -> ((if (dev == "@all" && !b.out) b.env.fromName + " " else "") + d.optString("appName").ifEmpty { "通知" })
                        "sms" -> "短信 " + d.optString("addr")
                        "status" -> "状态·" + b.env.fromName
                        else -> b.env.fromName
                    }
                    val body = when (b.env.t) {
                        "notif" -> d.optString("title").let { t ->
                            val x = d.optString("text").ifEmpty { d.optString("subText") }
                            if (t.isNotEmpty() && x.isNotEmpty() && t != x) "$t\n$x" else t.ifEmpty { x }
                        }
                        "sms" -> d.optString("body")
                        "status" -> {
                            val loc = d.optJSONObject("location")
                            "电量${d.optInt("battery")}%" + (if (d.optBoolean("charging")) " 充电中" else "") +
                                (loc?.let { " 位置(${it.optDouble("lat")},${it.optDouble("lon")})" } ?: "")
                        }
                        else -> d.toString()
                    }
                    tvBody.text = head + ": " + body
                }
            }
            v.findViewById<TextView>(R.id.tvBubbleTime).text = sdf.format(Date(b.env.ts))
            return v
        }
    }

    private val adapter = BubAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        dev = intent.getStringExtra("dev") ?: ""
        peer = intent.getStringExtra("name") ?: dev
        supportActionBar?.title = if (dev == "@all") "全部消息" else peer
        list = findViewById(R.id.listBubbles)
        tvState = findViewById(R.id.tvChatState)
        etInput = findViewById(R.id.etChatInput)
        btnSend = findViewById(R.id.btnChatSend)
        btnAtt = findViewById(R.id.btnChatAtt)
        btnSend.setOnClickListener {
            val text = etInput.text.toString()
            if (text.isBlank()) return@setOnClickListener
            val now = System.currentTimeMillis()
            val to = if (dev == "@all") "" else dev
            Mailbox.sendChat(text, to, now)
            etInput.setText("")
            toast("已发送" + if (to.isEmpty()) "（广播）" else "（私信）")
            val d = JSONObject().put("text", text).put("to", to)
            mergeIn(listOf(Bub(Envelope("chat", Config.deviceId, Config.deviceName, now, d, to), true)))
        }
        btnAtt.setOnClickListener {
            val opts = arrayOf("发送图片", "发送文件")
            AlertDialog.Builder(this)
                .setTitle("选择发送方式")
                .setItems(opts) { _, which ->
                    val pick = Intent(Intent.ACTION_GET_CONTENT)
                    if (which == 0) {
                        pick.type = "image/*"
                    } else {
                        pick.type = "*/*"
                        pick.addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivityForResult(pick, if (which == 0) 11 else 12)
                }
                .show()
        }
        refreshJob = io.launch {
            while (true) {
                kotlinx.coroutines.delay(2500)
                if (!resumed || loading) continue
                try {
                    val arr = CloudApi.page("", Long.MAX_VALUE, 60) ?: continue
                    val fresh = ArrayList<Bub>()
                    for (i in 0 until arr.length()) {
                        val e = envOf(arr.getJSONObject(i)) ?: continue
                        ChatStore.add(e.ts, e.t, e.from, e.fromName, e.data.toString())
                        if (keep(e)) fresh.add(Bub(e, e.from == Config.deviceId))
                    }
                    withContext(Dispatchers.Main) {
                        mergeIn(fresh)
                    }
                } catch (e: Exception) {
                }
            }
        }
        list.adapter = adapter
        chips = mapOf(
            "all" to findViewById(R.id.chipAll),
            "chat" to findViewById(R.id.chipChat),
            "notif" to findViewById(R.id.chipNotif)
        )
        chips["all"]!!.setOnClickListener { setFilter("all") }
        chips["chat"]!!.setOnClickListener { setFilter("chat") }
        chips["notif"]!!.setOnClickListener { setFilter("notif") }
        list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScroll(lv: android.widget.AbsListView, first: Int, vis: Int, total: Int) {
                if (first == 0 && total > 0 && !loading && cursor != null) loadMore()
            }

            override fun onScrollStateChanged(lv: android.widget.AbsListView, sc: Int) {
                if (sc == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE && list.firstVisiblePosition == 0 && !loading && cursor != null) loadMore()
            }
        })
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val b = bubs.getOrNull(pos)
            if (b != null) {
                showMsgMenu(b)
                true
            } else false
        }
        applyFilter()
        loadLocal()
    }

    private fun showMsgMenu(b: Bub) {
        val d = b.env.data
        val isChat = b.env.t == "chat"
        val text = d.optString("text")
        val kind = d.optString("kind")
        val f = if (kind.isNotEmpty()) attFile(d.optString("key"), d.optString("name")) else null
        val names = ArrayList<String>()
        val acts = ArrayList<Int>()
        if (isChat && text.isNotEmpty()) {
            names.add("复制文本")
            acts.add(0)
        }
        if (kind.isNotEmpty() && f != null) {
            names.add(if (f.exists()) "保存到相册/下载" else "先接收完成才能保存")
            acts.add(1)
        }
        names.add("转发…")
        acts.add(2)
        names.add("删除")
        acts.add(3)
        AlertDialog.Builder(this)
            .setTitle(if (isChat) b.env.fromName.take(12) else b.env.t)
            .setItems(names.toTypedArray()) { _, w ->
                when (acts[w]) {
                    0 -> {
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("pairsync", text))
                        toast("已复制")
                    }
                    1 -> if (f != null && f.exists()) {
                        toast(ImgUtil.save(this, f, d.optString("mime").ifEmpty { "application/octet-stream" }, d.optString("name")))
                    } else toast("附件尚未接收完成")
                    2 -> forwardTo(b)
                    3 -> {
                        ChatStore.del(b.env.ts, b.env.from, b.env.t)
                        all.remove(b)
                        bubs.remove(b)
                        adapter.notifyDataSetChanged()
                        state(infoText())
                        toast("已删除")
                    }
                }
            }
            .show()
    }

    private fun forwardTo(b: Bub) {
        io.launch {
            val parts = ChatStore.participants(Config.deviceId)
            withContext(Dispatchers.Main) {
                val names = ArrayList<String>()
                names.add("全部设备（广播）")
                for (p in parts) names.add(p.second)
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("转发到")
                    .setItems(names.toTypedArray()) { _, w ->
                        val to = if (w == 0) "" else parts.getOrNull(w - 1)?.first ?: return@setItems
                        val d = b.env.data
                        if (b.env.t == "chat") {
                            if (d.optString("kind").isNotEmpty()) {
                                val dd = JSONObject(d.toString())
                                dd.put("to", to)
                                dd.remove("up")
                                Mailbox.sendChatData(dd, to, System.currentTimeMillis())
                            } else {
                                val txt = d.optString("text")
                                if (txt.isNotEmpty()) Mailbox.sendChat(txt, to, System.currentTimeMillis())
                            }
                        } else toast("仅支持转发聊天/附件")
                        toast("已转发")
                    }
                    .show()
            }
        }
    }

    private var resumedOnce = false
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var resumed = false

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (resumedOnce) {
            applyFilter()
            adapter.notifyDataSetChanged()
        }
        resumedOnce = true
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        super.onDestroy()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data?.data == null) return
        try {
            val uri = data.data!!
            val cr = contentResolver
            val mime = cr.getType(uri) ?: "application/octet-stream"
            var name = ""
            try {
                cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: ""
                }
            } catch (e: Exception) {
            }
            if (name.isEmpty()) name = "file-" + System.currentTimeMillis() + "." + mime.substringAfterLast('/')
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return
            if (bytes.isEmpty()) {
                toast("读取失败")
                return
            }
            if (bytes.size > 25 * 1024 * 1024) {
                toast("文件超过 25MB，暂不支持")
                return
            }
            var data = bytes
            var nama = name
            var mim = mime
            if (req == 11) {
                try {
                    val cp = ImgUtil.compressImage(bytes)
                    if (cp != null) {
                        data = cp.first
                        mim = cp.second
                        nama = name.substringBeforeLast('.', name) + ".jpg"
                    }
                } catch (e: Exception) {
                }
            }
            if (data.size > 8 * 1024 * 1024) {
                toast("文件超过 8MB，暂不支持")
                return
            }
            val now = System.currentTimeMillis()
            val to = if (dev == "@all") "" else dev
            val keyAtt = Mailbox.attKey(nama, data.size, now)
            val d = JSONObject()
                .put("text", "")
                .put("to", to)
                .put("kind", if (mim.startsWith("image/")) "img" else "file")
                .put("name", nama)
                .put("size", data.size)
                .put("mime", mim)
                .put("key", keyAtt)
                .put("up", 1)
            toast("附件发送中…")
            Mailbox.sendAtt(data, nama, mim, to, now)
            mergeIn(listOf(Bub(Envelope("chat", Config.deviceId, Config.deviceName, now, d, to), true)))
        } catch (e: Exception) {
            toast("发送失败: " + (e.message ?: e.toString()))
        }
    }

    private fun envOf(o: JSONObject): Envelope? = try {
        Envelope(
            o.optString("type"),
            o.optString("fromid"),
            o.optString("fromname"),
            o.optLong("ts"),
            JSONObject(o.optString("data"))
        )
    } catch (e: Exception) {
        null
    }

    private fun keep(e: Envelope): Boolean {
        if (dev == "@all") {
            if (e.t == "chat" && e.data.optString("to").isNotEmpty()) return false
            return true
        }
        return e.from == dev ||
            (e.from == Config.deviceId && e.t == "chat" && e.data.optString("to") == dev)
    }

    private fun keyOf(b: Bub): String = "" + b.env.ts + "|" + b.env.from + "|" + b.env.t

    private fun mergeIn(fresh: List<Bub>): Int {
        val prevEmpty = all.isEmpty()
        val dset = ChatStore.deletedSet()
        val pending = HashMap<String, Int>()
        for (i in all.indices) if (all[i].env.data.optBoolean("up")) pending[keyOf(all[i])] = i
        val keys = HashSet<String>()
        for (b in all) keys.add(keyOf(b))
        val accepted = fresh.filter { !dset.contains(keyOf(it)) }
        var added = 0
        for (fb in accepted) {
            val k = keyOf(fb)
            val pi = pending[k]
            if (pi != null && !fb.env.data.optBoolean("up")) {
                all[pi] = fb
                keys.add(k)
                pending.remove(k)
                added++
            } else if (keys.add(k)) {
                all.add(fb)
                added++
            }
        }
        if (added > 0) {
            all.sortBy { it.env.ts }
            applyFilter()
            adapter.notifyDataSetChanged()
            list.post { list.setSelection(bubs.size - 1) }
            if (prevEmpty && cursor == null) cursor = all.minOfOrNull { it.env.ts }
            state(infoText())
        }
        return added
    }

    private fun setFilter(f: String) {
        if (filter == f) return
        filter = f
        updateChips()
        applyFilter()
        adapter.notifyDataSetChanged()
        state(infoText())
        list.post { list.setSelection(bubs.size - 1) }
    }

    private fun updateChips() {
        for ((id, v) in chips) {
            val on = id == filter
            v.setBackgroundResource(if (on) R.drawable.bg_pill_on else R.drawable.bg_pill_off)
            v.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF333333.toInt())
        }
    }

    private fun passes(b: Bub): Boolean = when (filter) {
        "chat" -> b.env.t == "chat"
        "notif" -> b.env.t != "chat"
        else -> true
    }

    private fun applyFilter() {
        bubs.clear()
        for (b in all) if (passes(b)) bubs.add(b)
    }

    private fun infoText(): String = when {
        all.isEmpty() -> "暂无消息"
        cursor == null -> "已加载全部，共 ${all.size} 条"
        else -> "上滑查看更早"
    }

    private fun state(s: String?) {
        tvState.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s != null) tvState.text = s
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun loadLocal() {
        if (loading) return
        loading = true
        state("加载中…")
        io.launch {
            val fresh = ArrayList<Bub>()
            try {
                for (r in ChatStore.latest(400)) {
                    try {
                        val e = Envelope(r.type, r.fromId, r.fromName, r.ts, JSONObject(r.data))
                        if (keep(e)) fresh.add(Bub(e, e.from == Config.deviceId))
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
            }
            withContext(Dispatchers.Main) {
                all.clear()
                all.addAll(fresh)
                applyFilter()
                adapter.notifyDataSetChanged()
                cursor = if (all.isEmpty()) null else all.minOfOrNull { it.env.ts }
                state(infoText())
                list.post { list.setSelection(bubs.size - 1) }
                loading = false
            }
        }
    }

    private fun loadMore() {
        val before = cursor ?: return
        if (loading) return
        loading = true
        io.launch {
            try {
                val arr = CloudApi.page("", before, 100)
                val fresh = ArrayList<Bub>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val e = envOf(arr.getJSONObject(i)) ?: continue
                        ChatStore.add(e.ts, e.t, e.from, e.fromName, e.data.toString())
                        if (keep(e)) fresh.add(Bub(e, e.from == Config.deviceId))
                    }
                }
                withContext(Dispatchers.Main) {
                    val firstBefore = list.firstVisiblePosition
                    val added = mergeInNoScroll(fresh)
                    cursor = if (added == 0) null else all.minOfOrNull { it.env.ts }
                    if (added > 0) list.setSelectionFromTop(firstBefore + added, 0)
                    state(infoText())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    state("加载失败: " + (e.message ?: e.toString()))
                }
            }
            loading = false
        }
    }

    private fun mergeInNoScroll(fresh: List<Bub>): Int {
        val dset = ChatStore.deletedSet()
        val keys = HashSet<String>()
        for (b in all) keys.add(keyOf(b))
        var added = 0
        for (fb in fresh) {
            if (dset.contains(keyOf(fb))) continue
            if (keys.add(keyOf(fb))) {
                all.add(fb)
                added++
            }
        }
        if (added > 0) {
            all.sortBy { it.env.ts }
            applyFilter()
            adapter.notifyDataSetChanged()
        }
        return added
    }

    private fun attFile(k: String, name: String): File {
        val dir = File(getExternalFilesDir("att") ?: filesDir, "att")
        if (!dir.exists()) dir.mkdirs()
        val base = k.substringAfterLast('/').removeSuffix(".enc")
        val ext = name.substringAfterLast('.', "")
        return File(dir, if (ext.isEmpty()) base else base + "." + ext)
    }

    private fun fmtSize(n: Int): String =
        if (n < 1024) "$n B"
        else if (n < 1024 * 1024) (n / 1024).toString() + " KB"
        else String.format(Locale.US, "%.1f MB", n / 1048576.0)

    private fun decodeScaled(f: File): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / s > 720 || o.outHeight / s > 720) s *= 2
        val ob = BitmapFactory.Options().apply { inSampleSize = s }
        return BitmapFactory.decodeFile(f.path, ob)
    }

    private fun queueAtt(b: Bub) {
        if (b.env.data.optBoolean("up")) return
        val key = keyOf(b)
        if (!downloading.add(key)) return
        val k = b.env.data.optString("key")
        val f = attFile(k, b.env.data.optString("name"))
        io.launch {
            var ok = false
            for (tryN in 1..3) {
                val bytes = try {
                    Mailbox.getObject(k)
                } catch (e: Exception) {
                    null
                }
                if (bytes != null) {
                    try {
                        f.writeBytes(bytes)
                        ok = true
                        break
                    } catch (e: Exception) {
                    }
                }
                kotlinx.coroutines.delay(4000L * tryN)
            }
            withContext(Dispatchers.Main) {
                downloading.remove(key)
                if (!ok) toast("附件下载失败")
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun compressImage(bytes: ByteArray): Pair<ByteArray, String>? = ImgUtil.compressImage(bytes)

    private fun openFile(f: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "com.pairsync.app.file", f)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime.ifEmpty { "application/octet-stream" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            toast("无应用可打开此文件")
        }
    }
}
