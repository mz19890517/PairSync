package com.pairsync.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pairsync.app.core.Envelope
import com.pairsync.app.core.Mailbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ArchiveActivity : AppCompatActivity() {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var spDev: Spinner
    private lateinit var spApp: Spinner
    private lateinit var list: ListView
    private lateinit var tvMsg: TextView
    private lateinit var btnMore: Button

    private var device: String? = null
    private var appFilter = "全部"
    private var cursor: Long? = null
    private var loading = false
    private val items = ArrayList<Envelope>()
    private val apps = LinkedHashSet<String>()
    private var devLabels = mutableListOf<String>()
    private var label2dev = mutableMapOf<String, String>()

    private class Row(val title: String, val sub: String)

    private inner class RowsAdapter : BaseAdapter() {
        var rows = ArrayList<Row>()
        override fun getCount() = rows.size
        override fun getItem(i: Int) = rows[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
            val v = cv ?: LayoutInflater.from(this@ArchiveActivity).inflate(R.layout.item_archive, parent, false)
            val r = rows[i]
            v.findViewById<TextView>(R.id.tvArchTitle).text = r.title
            v.findViewById<TextView>(R.id.tvArchSub).text = r.sub
            return v
        }
    }

    private val adapter = RowsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive)
        supportActionBar?.title = "信息归档"
        spDev = findViewById(R.id.spArchiveDev)
        spApp = findViewById(R.id.spArchiveApp)
        list = findViewById(R.id.listArchive)
        tvMsg = findViewById(R.id.tvArchiveMsg)
        btnMore = findViewById(R.id.btnArchMore)
        list.adapter = adapter
        list.emptyView = tvMsg
        if (!Config.isComplete()) {
            Toast.makeText(this, "尚未完成配置，先在主界面填写", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        btnMore.setOnClickListener { load(false) }
        spDev.onItemSelectedListener = devSelect
        spApp.onItemSelectedListener = appSelect
        loadDevices()
    }

    private val devSelect = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            val d = label2dev[devLabels.getOrNull(pos)] ?: return
            if (d == device) return
            device = d
            load(true)
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private val appSelect = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            val f = appList.getOrNull(pos) ?: return
            if (f == appFilter) return
            appFilter = f
            render()
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private var appList = listOf("全部")

    private fun loadDevices() {
        io.launch {
            val devs = try {
                Mailbox.archiveDevices()
            } catch (e: Exception) {
                emptyList<String>()
            }
            val names2 = try {
                Mailbox.listStatuses().associate { it.from to it.fromName }
            } catch (e: Exception) {
                emptyMap<String, String>()
            }
            withContext(Dispatchers.Main) {
                devLabels.clear()
                label2dev.clear()
                val me = "本机（" + Config.deviceId + "）"
                devLabels.add(me)
                label2dev[me] = Config.deviceId
                for (d in devs) {
                    if (d == Config.deviceId) continue
                    val lbl = (names2[d] ?: "设备 $d") + "（$d）"
                    devLabels.add(lbl)
                    label2dev[lbl] = d
                }
                spDev.adapter = ArrayAdapter(this@ArchiveActivity, android.R.layout.simple_spinner_dropdown_item, devLabels)
                spDev.setSelection(0)
                device = Config.deviceId
                load(true)
            }
        }
    }

    private fun load(reset: Boolean) {
        if (loading) return
        val dev = device ?: run { tvMsg.text = "未选择设备"; return }
        if (reset) {
            items.clear()
            apps.clear()
            cursor = null
        }
        val before = cursor ?: Long.MAX_VALUE
        loading = true
        tvMsg.text = "加载中..."
        io.launch {
            try {
                val r = Mailbox.archivePage(dev, before, 200)
                withContext(Dispatchers.Main) {
                    items.addAll(r.first)
                    for (env in r.first) apps.add(labelOf(env))
                    refreshAppSpinner()
                    cursor = r.second
                    render()
                    if (cursor == null) {
                        tvMsg.text = "已到最早，共 ${items.size} 条"
                        btnMore.isEnabled = false
                    } else {
                        tvMsg.text = "已加载 ${items.size} 条，可继续加载更早记录"
                        btnMore.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvMsg.text = "加载失败: " + (e.message ?: e.toString())
                    btnMore.isEnabled = true
                }
                loading = false
                return@launch
            }
            loading = false
        }
    }

    private fun refreshAppSpinner() {
        appList = listOf("全部") + apps.toList()
        val pos = appList.indexOf(appFilter).coerceAtLeast(0)
        spApp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, appList)
        spApp.setSelection(pos)
    }

    private fun render() {
        val shown = items.filter { appFilter == "全部" || labelOf(it) == appFilter }
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        adapter.rows = ArrayList(shown.map { Row(titleOf(it), sdf.format(Date(it.ts)) + "  " + bodyOf(it)) })
        adapter.notifyDataSetChanged()
    }

    private fun labelOf(env: Envelope): String = when (env.t) {
        "notif" -> env.data.optString("appName").ifEmpty { env.data.optString("packageName") }
        "sms" -> "短信"
        else -> env.t
    }

    private fun titleOf(env: Envelope): String {
        val who = if (env.t == "sms") env.data.optString("addr") else env.data.optString("appName").ifEmpty { env.data.optString("packageName") }
        return when (env.t) {
            "notif" -> "$who：${env.data.optString("title")}"
            "sms" -> "短信 $who"
            "status" -> "状态 " + env.fromName
            else -> "${env.fromName} ${env.t}"
        }
    }

    private fun bodyOf(env: Envelope): String = when (env.t) {
        "notif" -> env.data.optString("text").ifEmpty { env.data.optString("subText") }
        "sms" -> env.data.optString("body")
        "status" -> {
            val loc = env.data.optJSONObject("location")
            "电量${env.data.optInt("battery")}%" + (if (env.data.optBoolean("charging")) " 充电中" else "") +
                (loc?.let { " 位置(${it.optDouble("lat")},${it.optDouble("lon")})" } ?: "")
        }
        else -> env.data.toString()
    }
}
