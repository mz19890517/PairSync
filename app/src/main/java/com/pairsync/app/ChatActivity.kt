package com.pairsync.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pairsync.app.core.CloudApi
import com.pairsync.app.core.Envelope
import com.pairsync.app.core.Mailbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
    private var dev = ""
    private var peer = ""

    private class Bub(val env: Envelope, val out: Boolean)

    private val bubs = ArrayList<Bub>()
    private var cursor: Long? = null
    private var loading = false

    private inner class BubAdapter : BaseAdapter() {
        override fun getCount() = bubs.size
        override fun getItem(i: Int) = bubs[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
            val v = cv ?: LayoutInflater.from(this@ChatActivity).inflate(R.layout.item_bubble, parent, false)
            val b = bubs[i]
            val wrap = v.findViewById<LinearLayout>(R.id.bubbleWrap)
            val fl = wrap.parent as FrameLayout
            val lp = wrap.layoutParams as FrameLayout.LayoutParams
            if (b.out) {
                lp.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                wrap.setBackgroundResource(R.drawable.bg_bubble_out)
            } else {
                lp.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                wrap.setBackgroundResource(R.drawable.bg_bubble_in)
            }
            wrap.layoutParams = lp
            val showFrom = dev == "@all" && !b.out
            val head = when (b.env.t) {
                "notif" -> ((if (showFrom) b.env.fromName + " " else "") + b.env.data.optString("appName").ifEmpty { "通知" })
                "sms" -> "短信 " + b.env.data.optString("addr")
                "chat" -> (if (showFrom) b.env.fromName + " " else "") + "聊天"
                "status" -> "状态·" + b.env.fromName
                else -> b.env.fromName
            }
            val body = when (b.env.t) {
                "notif" -> b.env.data.optString("title").let { t ->
                    val x = b.env.data.optString("text").ifEmpty { b.env.data.optString("subText") }
                    if (t.isNotEmpty() && x.isNotEmpty() && t != x) "$t\n$x" else t.ifEmpty { x }
                }
                "sms" -> b.env.data.optString("body")
                "chat" -> b.env.data.optString("text")
                "status" -> {
                    val loc = b.env.data.optJSONObject("location")
                    "电量${b.env.data.optInt("battery")}%" + (if (b.env.data.optBoolean("charging")) " 充电中" else "") +
                        (loc?.let { " 位置(${it.optDouble("lat")},${it.optDouble("lon")})" } ?: "")
                }
                else -> b.env.data.toString()
            }
            val tvBody = v.findViewById<TextView>(R.id.tvBubbleBody)
            tvBody.text = if (b.env.t == "chat") head.ifEmpty { body } else head + ": " + body
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
        btnSend.setOnClickListener {
            val text = etInput.text.toString()
            if (text.isBlank()) return@setOnClickListener
            val to = if (dev == "@all") "" else dev
            Mailbox.sendChat(text, to)
            etInput.setText("")
            toast("已发送" + if (to.isEmpty()) "（广播）" else "（私信）")
            val self = Envelope("chat", Config.deviceId, Config.deviceName, System.currentTimeMillis(), org.json.JSONObject().put("text", text).put("to", to), to)
            bubs.add(Bub(self, true))
            adapter.notifyDataSetChanged()
            list.setSelection(bubs.size - 1)
        }
        list.adapter = adapter
        list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScroll(lv: android.widget.AbsListView, first: Int, vis: Int, total: Int) {
                if (first == 0 && total > 0 && !loading && cursor != null) loadMore()
            }

            override fun onScrollStateChanged(lv: android.widget.AbsListView, sc: Int) {
                if (sc == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE && list.firstVisiblePosition == 0 && !loading && cursor != null) loadMore()
            }
        })
        load(true)
    }

    private fun state(s: String?) {
        tvState.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s != null) tvState.text = s
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun loadMore() {
        val before = cursor ?: return
        loadInternal(before, false)
    }

    private fun load(reset: Boolean) {
        loadInternal(Long.MAX_VALUE, true)
    }

    private fun loadInternal(before: Long, reset: Boolean) {
        if (loading) return
        loading = true
        state("加载中…")
        io.launch {
            try {
                val arr = CloudApi.page("", before, 120)
                val fresh = ArrayList<Bub>()
                var next: Long? = null
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        try {
                            val o = arr.getJSONObject(i)
                            val e = Envelope(
                                o.optString("type"),
                                o.optString("fromid"),
                                o.optString("fromname"),
                                o.optLong("ts"),
                                JSONObject(o.optString("data"))
                            )
                            if (dev != "@all") {
                                val keep = e.from == dev ||
                                    (e.from == Config.deviceId && e.t == "chat" && e.data.optString("to") == dev)
                                if (!keep) continue
                            }
                            fresh.add(Bub(e, e.from == Config.deviceId))
                        } catch (e: Exception) {
                        }
                    }
                    fresh.sortByDescending { it.env.ts }
                    next = if (fresh.size >= 50) fresh.last().env.ts else null
                    fresh.reverse()
                }
                withContext(Dispatchers.Main) {
                    val addCount = fresh.size
                    if (reset) {
                        bubs.clear()
                        bubs.addAll(fresh)
                        cursor = next
                    } else {
                        bubs.addAll(0, fresh)
                        cursor = next
                    }
                    adapter.notifyDataSetChanged()
                    if (!reset && addCount > 0) {
                        list.setSelection(addCount)
                    }
                    state(
                        when {
                            bubs.isEmpty() && arr == null -> "云端不可达（S3 备份通道未接入聊天）"
                            bubs.isEmpty() -> "暂无消息"
                            cursor == null && !reset -> null
                            cursor != null -> "上滑查看更早"
                            else -> "已到最早"
                        }
                    )
                    if (cursor == null && bubs.isNotEmpty()) state("已到最早，共 ${bubs.size} 条")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    state("加载失败: " + (e.message ?: e.toString()))
                }
            }
            loading = false
        }
    }
}
