package com.pairsync.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pairsync.app.core.Log
import com.pairsync.app.core.Mailbox
import com.pairsync.app.service.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var etEndpoint: EditText
    private lateinit var etBucket: EditText
    private lateinit var etRegion: EditText
    private lateinit var etAk: EditText
    private lateinit var etSk: EditText
    private lateinit var etKey: EditText
    private lateinit var etName: EditText
    private lateinit var etInterval: EditText
    private lateinit var tvHint: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val logTicker = object : Runnable {
        override fun run() {
            if (::tvLog.isInitialized) tvLog.text = Log.tail(120)
            uiHandler.postDelayed(this, 2000)
        }
    }

    private val fgPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
        val fine = map[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = map[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) bgPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        updateHint()
    }

    private val bgPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateHint()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Config.init(this)
        Mailbox.refresh()
        bindViews()
        fillFields()
        wireButtons()
        setServiceButtons(SyncService.running)
    }

    private fun bindViews() {
        etEndpoint = findViewById(R.id.etEndpoint)
        etBucket = findViewById(R.id.etBucket)
        etRegion = findViewById(R.id.etRegion)
        etAk = findViewById(R.id.etAk)
        etSk = findViewById(R.id.etSk)
        etKey = findViewById(R.id.etKey)
        etName = findViewById(R.id.etName)
        etInterval = findViewById(R.id.etInterval)
        tvHint = findViewById(R.id.tvHint)
        tvLog = findViewById(R.id.tvLog)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
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

    private fun wireButtons() {
        btnStart.setOnClickListener {
            saveFromFields()
            if (!Config.isComplete()) {
                toast("配置不完整")
                return@setOnClickListener
            }
            Mailbox.refresh()
            Config.runOnBoot = true
            ContextCompat.startForegroundService(this, SyncService.startIntent(this))
            setServiceButtons(true)
            toast("同步已启动")
        }
        btnStop.setOnClickListener {
            Config.runOnBoot = false
            startService(SyncService.stopIntent(this))
            setServiceButtons(false)
            toast("同步已停止")
        }
        findViewById<Button>(R.id.btnGenKey).setOnClickListener {
            etKey.setText(Config.newKeyB64())
            toast("已生成新密钥，记得导出配置到另一台手机")
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener {
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
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("pairsync", cfg.toString()))
            toast("配置已复制（含密钥，请勿外传）")
        }
        findViewById<Button>(R.id.btnImport).setOnClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            importConfig(txt)
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            saveFromFields()
            Mailbox.refresh()
            uiScope.launch {
                toast("正在测试 S3 连通...")
                val lines = withContext(Dispatchers.IO) { Mailbox.testConnection() }
                lines.forEach { Log.log("test", it) }
                tvLog.text = Log.tail(120)
                val ok = lines.lastOrNull()?.startsWith("[√]") == true
                toast(if (ok) "S3 连通正常" else "S3 连通存在故障，看日志明细")
            }
        }
        findViewById<Button>(R.id.btnArchive).setOnClickListener {
            saveFromFields()
            Mailbox.refresh()
            startActivity(Intent(this, ArchiveActivity::class.java))
        }
        findViewById<Button>(R.id.btnPerms).setOnClickListener { askPerms() }
        findViewById<Button>(R.id.btnNotifSet).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
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
            Mailbox.refresh()
            fillFields()
            toast("导入成功")
        } catch (e: Exception) {
            toast("导入失败：" + e.message)
        }
    }

    private fun saveFromFields() {
        Config.endpoint = etEndpoint.text.toString()
        Config.bucket = etBucket.text.toString()
        Config.region = etRegion.text.toString()
        Config.ak = etAk.text.toString()
        Config.sk = etSk.text.toString()
        Config.keyB64 = etKey.text.toString()
        Config.deviceName = etName.text.toString()
        Config.intervalSec = etInterval.text.toString().toIntOrNull() ?: 10
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
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        uiHandler.post(logTicker)
        updateHint()
        setServiceButtons(SyncService.running)
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

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(logTicker)
    }
}
