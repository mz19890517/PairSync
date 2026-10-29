package com.pairsync.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pairsync.app.core.CloudApi
import com.pairsync.app.core.Envelope
import com.pairsync.app.core.Log
import com.pairsync.app.core.Mailbox
import com.pairsync.app.service.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    private lateinit var tabHost: FrameLayout
    private var curTab = ""

    private class Row(val dev: String, val name: String, val preview: String, val time: String)

    private val rows = ArrayList<Row>()
    private val chatsAdapter = RowAdapter(false)
    private val devicesAdapter = RowAdapter(true)

    private inner class RowAdapter(val devicesMode: Boolean) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(i: Int) = rows[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
            val v = cv ?: LayoutInflater.from(this@HomeActivity).inflate(R.layout.item_chat_row, parent, false)
            val r = rows[i]
            v.findViewById<TextView>(R.id.tvAvatar).text = if (r.name.isEmpty()) "?" else r.name.take(1)
            v.findViewById<TextView>(R.id.tvChatTitle).text = r.name
            v.findViewById<TextView>(R.id.tvChatPreview).text = r.preview
            v.findViewById<TextView>(R.id.tvChatTime).text = r.time
            return v
        }
    }

    private val logTicker = object : Runnable {
        override fun run() {
            if (curTab == "settings") tvLog?.text = Log.tail(120)
            uiHandler.postDelayed(this, 2000)
        }
    }

    private val fgPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
        val fine = map[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = map[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) bgPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (curTab == "settings") updateHint()
    }

    private val bgPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (curTab == "settings") updateHint()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        Config.init(this)
        Mailbox.refresh()
        findViewById<View>(R.id.tabChats).setOnClickListener { showTab("chats") }
        findViewById<View>(R.id.tabDevices).setOnClickListener { showTab("devices") }
        findViewById<View>(R.id.tabSettings).setOnClickListener { showTab("settings") }
        showTab("chats")
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(logTicker)
        if (curTab == "settings" && SyncService.running.not()) autoStartCheck()
        else if (curTab == "settings") setServiceButtons(true)
        else autoStartCheck()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(logTicker)
    }

    private fun autoStartCheck() {
        val canAuto = Config.runOnBoot && Config.isComplete() && !SyncService.running
        Log.log("auto", "runOnBoot=${Config.runOnBoot} complete=${Config.isComplete()} running=${SyncService.running} -> $canAuto")
        if (canAuto) {
            try {
                ContextCompat.startForegroundService(this, SyncService.startIntent(this))
            } catch (e: Exception) {
                Log.log("app", "自动恢复同步失败: " + e.message)
            }
        }
    }

    private fun colorTab(which: String) {
        val cSel = 0xFF07C160.toInt()
        val cNo = 0xFF888888.toInt()
        findViewById<TextView>(R.id.txTabChats).setTextColor(if (which == "chats") cSel else cNo)
        findViewById<TextView>(R.id.txTabDevices).setTextColor(if (which == "devices") cSel else cNo)
        findViewById<TextView>(R.id.txTabSettings).setTextColor(if (which == "settings") cSel else cNo)
    }

    private fun showTab(which: String) {
        curTab = which
        colorTab(which)
        tabHost = findViewById(R.id.tabHost)
        tabHost.removeAllViews()
        val lv: ListView
        when (which) {
            "chats" -> {
                supportActionBar?.title = "PairSync"
                LayoutInflater.from(this).inflate(R.layout.tab_chats, tabHost, true)
                lv = findViewById(R.id.listChats)
                lv.adapter = chatsAdapter
                lv.setOnItemClickListener { _, _, pos, _ -> openChat(pos) }
                loadRows()
            }
            "devices" -> {
                supportActionBar?.title = "设备"
                LayoutInflater.from(this).inflate(R.layout.tab_devices, tabHost, true)
                lv = findViewById(R.id.listDevices)
                lv.adapter = devicesAdapter
                lv.setOnItemClickListener { _, _, pos, _ -> openChat(pos) }
                loadRows()
            }
            "settings" -> {
                supportActionBar?.title = "设置"
                LayoutInflater.from(this).inflate(R.layout.activity_main, tabHost, true)
                bindSettings()
                autoStartCheck()
            }
        }
    }

    private fun openChat(pos: Int) {
        val r = rows.getOrNull(pos) ?: return
        if (r.dev.isEmpty()) return
        val i = Intent(this, ChatActivity::class.java)
        i.putExtra("dev", r.dev)
        i.putExtra("name", r.name)
        startActivity(i)
    }

    private fun loadRows() {
        io.launch {
            val fresh = ArrayList<Row>()
            val devs = try {
                CloudApi.devices()
            } catch (e: Exception) {
                null
            }
            if (devs == null) {
                fresh.add(Row("", "云端服务不可达", "拉取设备列表失败，到设置页点「测试 S3」检查网络", ""))
                withContext(Dispatchers.Main) {
                    rows.clear()
                    rows.addAll(fresh)
                    chatsAdapter.notifyDataSetChanged()
                    devicesAdapter.notifyDataSetChanged()
                }
                return@launch
            }
            val names = HashMap<String, String>()
            val counts = HashMap<String, Int>()
            val lts = HashMap<String, Long>()
            for (i in 0 until devs.length()) {
                try {
                    val o = devs.getJSONObject(i)
                    val id = o.optString("id")
                    names[id] = o.optString("name").ifEmpty { "设备 " + id }
                    counts[id] = o.optInt("n")
                    lts[id] = o.optLong("t")
                } catch (e: Exception) {
                }
            }
            val lastBy = HashMap<String, Envelope>()
            val recent = try {
                CloudApi.page("", Long.MAX_VALUE, 80)
            } catch (e: Exception) {
                null
            }
            if (recent != null) {
                for (i in 0 until recent.length()) {
                    try {
                        val o = recent.getJSONObject(i)
                        val e = Envelope(
                            o.optString("type"),
                            o.optString("fromid"),
                            o.optString("fromname"),
                            o.optLong("ts"),
                            JSONObject(o.optString("data"))
                        )
                        if ((lastBy[e.from]?.ts ?: 0L) < e.ts) lastBy[e.from] = e
                    } catch (e: Exception) {
                    }
                }
            }
            val latest = lastBy.values.maxByOrNull { it.ts }
            withContext(Dispatchers.Main) {
                val all = Row(
                    "@all", "全部消息",
                    latest?.let { it.fromName + "·" + previewOf(it) } ?: "所有设备的通知会聚合在这里",
                    latest?.let { sdf.format(Date(it.ts)) } ?: ""
                )
                rows.clear()
                rows.add(all)
                for (id in names.keys.sortedByDescending { lts[it] ?: 0L }) {
                    val e = lastBy[id]
                    rows.add(
                        Row(
                            id, names[id] ?: id,
                            e?.let { previewOf(it) } ?: ("存档 ${counts[id] ?: 0} 条"),
                            (lts[id] ?: 0L).takeIf { it > 0 }?.let { sdf.format(Date(it)) } ?: ""
                        )
                    )
                }
                if (names.isEmpty()) rows.add(Row("", "暂无设备", "有设备上传归档后这里会出现", ""))
                chatsAdapter.notifyDataSetChanged()
                devicesAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun previewOf(e: Envelope): String {
        val t = when (e.t) {
            "notif" -> "[" + e.data.optString("appName").ifEmpty { "通知" } + "] " +
                e.data.optString("text").ifEmpty { e.data.optString("title") }
            "sms" -> "[短信] " + e.data.optString("body")
            "status" -> "[状态] 电量${e.data.optInt("battery")}%"
            else -> "[" + e.t + "]"
        }
        return t.replace("\n", " ").take(60)
    }

    // ===== 设置页（原 MainActivity 全部逻辑） =====

    lateinit var etEndpoint: EditText
    lateinit var etBucket: EditText
    lateinit var etRegion: EditText
    lateinit var etAk: EditText
    lateinit var etSk: EditText
    lateinit var etKey: EditText
    lateinit var etName: EditText
    lateinit var etInterval: EditText
    lateinit var tvHint: TextView
    var tvLog: TextView? = null
    lateinit var btnStart: Button
    lateinit var btnStop: Button

    private fun bindSettings() {
        val v = tabHost
        etEndpoint = v.findViewById(R.id.etEndpoint)
        etBucket = v.findViewById(R.id.etBucket)
        etRegion = v.findViewById(R.id.etRegion)
        etAk = v.findViewById(R.id.etAk)
        etSk = v.findViewById(R.id.etSk)
        etKey = v.findViewById(R.id.etKey)
        etName = v.findViewById(R.id.etName)
        etInterval = v.findViewById(R.id.etInterval)
        tvHint = v.findViewById(R.id.tvHint)
        tvLog = v.findViewById(R.id.tvLog)
        btnStart = v.findViewById(R.id.btnStart)
        btnStop = v.findViewById(R.id.btnStop)
        fillFields()
        wireButtons(v)
        setServiceButtons(SyncService.running)
    }

    private fun fillFields() {
        etEndpoint.setText(Config.endpoint)
        etBucket.setText(Config.bucket)
        etRegion.setText(Config.region)
        etAk.setText(Config.ak)
        etSk.setText(Config.sk)
        etKey.setText(Config.keyB64)
        etName.setText(Config.deviceName)
        etInterval.setText(Config.intervalSec.toString())
        updateHint()
    }

    private fun wireButtons(v: View) {
        btnStart.setOnClickListener {
            saveFromFields()
            if (!Config.isComplete()) {
                toast("配置不完整")
                return@setOnClickListener
            }
            io.launch {
                var conflict = ""
                try {
                    val arr = CloudApi.page("", Long.MAX_VALUE, 200)
                    if (arr != null) {
                        val my = Config.deviceName.trim()
                        if (my.isNotEmpty()) {
                            for (i in 0 until arr.length()) {
                                try {
                                    val o = arr.getJSONObject(i)
                                    if (o.optString("fromid") != Config.deviceId && o.optString("fromname").trim() == my) {
                                        conflict = my
                                        break
                                    }
                                } catch (e: Exception) {
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                }
                withContext(Dispatchers.Main) {
                    if (conflict.isNotEmpty()) {
                        androidx.appcompat.app.AlertDialog.Builder(this@HomeActivity)
                            .setTitle("检测到同名设备")
                            .setMessage("云端已有设备使用名称「" + conflict + "」。两台设备同名会导致消息混串，请先在下方「设备名」里改成别的名字，再开启同步。")
                            .setPositiveButton("知道了", null)
                            .show()
                        return@withContext
                    }
                    Mailbox.refresh()
                    Config.runOnBoot = true
                    try {
                        ContextCompat.startForegroundService(this@HomeActivity, SyncService.startIntent(this@HomeActivity))
                    } catch (e: Exception) {
                        toast("启动失败: " + e.message)
                        return@withContext
                    }
                    setServiceButtons(true)
                    toast("同步已启动")
                }
            }
        }
        btnStop.setOnClickListener {
            Config.runOnBoot = false
            startService(SyncService.stopIntent(this))
            setServiceButtons(false)
            toast("同步已停止")
        }
        v.findViewById<Button>(R.id.btnGenKey).setOnClickListener {
            etKey.setText(Config.newKeyB64())
            toast("已生成新密钥，记得导出配置到另一台手机")
        }
        v.findViewById<Button>(R.id.btnExport).setOnClickListener {
            saveFromFields()
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
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("pairsync", cfg.toString()))
            toast("配置已复制（S3+云端+间隔，可在其他设备粘贴导入）")
        }
        v.findViewById<Button>(R.id.btnImport).setOnClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            importConfig(txt)
        }
        v.findViewById<Button>(R.id.btnTest).setOnClickListener {
            saveFromFields()
            Mailbox.refresh()
            io.launch {
                toast("正在测试 S3 连通...")
                val lines = withContext(Dispatchers.IO) { Mailbox.testConnection() }
                lines.forEach { Log.log("test", it) }
                withContext(Dispatchers.Main) {
                    tvLog?.text = Log.tail(120)
                    val ok = lines.lastOrNull()?.startsWith("[√]") == true
                    toast(if (ok) "S3 连通正常" else "S3 连通存在故障，看日志明细")
                }
            }
        }
        v.findViewById<Button>(R.id.btnArchive).setOnClickListener {
            saveFromFields()
            Mailbox.refresh()
            startActivity(Intent(this, ArchiveActivity::class.java))
        }
        v.findViewById<Button>(R.id.btnManage).setOnClickListener { askManagePw() }
        v.findViewById<Button>(R.id.btnPerms).setOnClickListener { askPerms() }
        v.findViewById<Button>(R.id.btnNotifSet).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        v.findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                toast("无法跳转，请手动设置电池优化")
            }
        }
    }

    private fun askManagePw() {
        val et = EditText(this)
        et.hint = "输入管理密码"
        et.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设备管理")
            .setView(et)
            .setPositiveButton("进入") { _, _ ->
                if (et.text.toString() == "mz19890517") showDeviceManager() else toast("管理密码错误")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeviceManager() {
        io.launch {
            val devs = try {
                CloudApi.devices()
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (devs == null) {
                    toast("云端不可达，拉取设备失败")
                    return@withContext
                }
                val ids = ArrayList<String>()
                val labels = ArrayList<String>()
                for (i in 0 until devs.length()) {
                    try {
                        val o = devs.getJSONObject(i)
                        val id = o.optString("id")
                        if (id == Config.deviceId) continue
                        ids.add(id)
                        labels.add(
                            (o.optString("name").ifEmpty { "设备 " + id }) + "  ·  " + id.take(8) +
                                "  ·  " + o.optInt("n") + " 条"
                        )
                    } catch (e: Exception) {
                    }
                }
                if (ids.isEmpty()) {
                    toast("没有其他云端设备")
                    return@withContext
                }
                androidx.appcompat.app.AlertDialog.Builder(this@HomeActivity)
                    .setTitle("管理云端设备（点按删除）")
                    .setItems(labels.toTypedArray()) { _, w ->
                        val victim = ids.getOrNull(w) ?: return@setItems
                        androidx.appcompat.app.AlertDialog.Builder(this@HomeActivity)
                            .setTitle("删除设备")
                            .setMessage("将从云端清除「$victim」的全部归档记录（上传、聊天、通知）。对方设备仍在运行时会随下一条归档重新出现；对方同步的数据在本机的归档不会受影响。继续？")
                            .setPositiveButton("删除") { _, _ ->
                                io.launch {
                                    val n = try {
                                        CloudApi.purge(victim)
                                    } catch (e: Exception) {
                                        -1
                                    }
                                    withContext(Dispatchers.Main) {
                                        toast(if (n >= 0) "已从云端删除 $n 条记录" else "删除失败，云端不可达")
                                    }
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    private fun importConfig(txt: String) {
        try {
            val o = JSONObject(txt.trim())
            Config.endpoint = o.optString("endpoint", "https://s3.cstcloud.cn")
            Config.bucket = o.optString("bucket")
            Config.region = o.optString("region", "us-east-1")
            Config.ak = o.optString("ak")
            Config.sk = o.optString("sk")
            if (o.optString("key").isNotEmpty()) Config.keyB64 = o.optString("key")
            if (o.optString("name").isNotEmpty()) Config.deviceName = o.optString("name")
            o.optInt("interval", -1).takeIf { it > 0 }?.let { Config.intervalSec = it }
            o.optInt("chatInterval", -1).takeIf { it > 0 }?.let { Config.chatIntervalSec = it }
            if (o.optString("cloudHost").isNotEmpty()) Config.cloudHost = o.optString("cloudHost")
            if (o.optString("cloudTok").isNotEmpty()) Config.cloudTok = o.optString("cloudTok")
            if (o.optString("cloudGatePw").isNotEmpty()) Config.cloudGatePw = o.optString("cloudGatePw")
            Mailbox.refresh()
            fillFields()
            toast("导入成功")
        } catch (e: Exception) {
            toast("导入失败：" + e.message)
        }
    }

    private fun saveFromFields() {
        try {
            Config.endpoint = etEndpoint.text.toString()
            Config.bucket = etBucket.text.toString()
            Config.region = etRegion.text.toString()
            Config.ak = etAk.text.toString()
            Config.sk = etSk.text.toString()
            Config.keyB64 = etKey.text.toString()
            Config.deviceName = etName.text.toString()
            Config.intervalSec = etInterval.text.toString().toIntOrNull() ?: 10
        } catch (e: Exception) {
        }
    }

    private fun askPerms() {
        val ps = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) ps.add(Manifest.permission.POST_NOTIFICATIONS)
        ps.add(Manifest.permission.ACCESS_FINE_LOCATION)
        ps.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        fgPerms.launch(ps.toTypedArray())
    }

    private fun updateHint() {
        val perms = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        val notifListener = notifListening()
        tvHint.text = buildString {
            append("权限状态：")
            if (perms) append("基础权限未齐 ")
            if (!notifListener) append("通知监听未开启")
            if (perms || !notifListener) append("\n请点「申请权限」与「开启通知监听」")
            else append("全部就绪")
        }
    }

    private fun notifListening(): Boolean {
        if (Build.VERSION.SDK_INT < 22) return true
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return packageName in s
    }

    private fun setServiceButtons(running: Boolean) {
        if (curTab != "settings") return
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
