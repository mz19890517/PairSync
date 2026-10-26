package com.pairsync.app.core

import android.content.Context
import com.pairsync.app.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

object Mailbox {
    @Volatile
    private var client: S3Client? = null

    private val skipUntil = HashMap<String, Long>()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun bootstrap(ctx: Context) {
        Config.init(ctx)
        Log.initFile(ctx.getExternalFilesDir(null) ?: ctx.filesDir)
        ArchiveStore.init(ctx)
        refresh()
        scope.launch { archiveUploader() }
    }

    fun refresh() {
        client = if (Config.isComplete()) {
            try {
                S3Client(Config.endpoint, Config.bucket, Config.region, Config.ak, Config.sk)
            } catch (e: Exception) {
                Log.log("cfg-err", e.toString())
                null
            }
        } else null
    }

    fun ready(): Boolean = client != null

    fun offer(type: String, data: JSONObject) {
        scope.launch { push(type, data) }
    }

    fun sendChat(text: String, to: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val ts = System.currentTimeMillis()
        val data = JSONObject().put("text", body).put("to", to)
        val rid = try {
            ArchiveStore.record("chat", Config.deviceId, Config.deviceName, data, ts)
        } catch (e: Exception) {
            Log.log("local-err", e.toString())
            -1L
        }
        scope.launch {
            try {
                var cloudIns = -1
                if (Config.cloudOk && rid > 0) {
                    try {
                        cloudIns = CloudApi.putRows(
                            org.json.JSONArray().put(
                                JSONObject()
                                    .put("id", Config.deviceId + "-" + ts + "-" + rid)
                                    .put("ts", ts)
                                    .put("type", "chat")
                                    .put("from", Config.deviceId)
                                    .put("from_name", Config.deviceName)
                                    .put("data", data.toString())
                            )
                        )
                    } catch (e: Exception) {
                        Log.log("chat-err", "直写: " + e.toString())
                    }
                }
                if (cloudIns >= 0 && rid > 0) {
                    ArchiveStore.markUploaded(rid)
                    Log.log("chat", "云端 " + (if (to.isEmpty()) "广播" else to) + " : " + body.take(40))
                    return@launch
                }
                if (rid > 0) Log.log("chat", "直写失败转通道兜底")
            } catch (e: Exception) {
                Log.log("chat-err", e.toString())
            }
            val c = client
            if (c == null) {
                Log.log("chat", "未配置，发送失败")
                return@launch
            }
            try {
                val env = MsgCodec.encode("chat", Config.deviceId, Config.deviceName, ts, data, to)
                val blob = Crypto.encrypt(env.toByteArray(Charsets.UTF_8), Config.keyB64)
                if (blob == null) return@launch Log.log("chat-err", "加密失败")
                val k = Config.BASE + "msg/" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".enc"
                c.putObject(k, blob)
                Log.log("chat", "S3 " + (if (to.isEmpty()) "广播" else to) + " : " + body.take(40))
            } catch (e: Exception) {
                Log.log("chat-err", e.toString())
            }
        }
    }

    fun offerArchive(type: String, data: JSONObject) {
        try {
            ArchiveStore.record(type, Config.deviceId, Config.deviceName, data)
        } catch (e: Exception) {
            Log.log("local-err", e.toString())
        }
        scope.launch { archiveUploader() }
    }

    private val uploaderWake = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun archiveUploader() {
        if (!uploaderWake.compareAndSet(false, true)) return
        try {
            while (true) {
                val c = client
                if (c == null || !Config.isComplete()) {
                    delay(15_000)
                    continue
                }
                val now = System.currentTimeMillis()
                if (now < sendPauseUntil) {
                    delay(5_000)
                    continue
                }
                val batch = ArchiveStore.pendingBatch(now, 50)
                if (batch.isEmpty()) {
                    ArchiveStore.purge(now)
                    delay(10_000)
                    continue
                }
                var was429 = false
                if (batch.size >= 1) {
                    var cloudDone = false
                    if (Config.cloudOk && !was429) {
                        try {
                            val rows = org.json.JSONArray()
                            for (row in batch) {
                                rows.put(
                                    JSONObject()
                                        .put("id", Config.deviceId + "-" + row.ts + "-" + row.id)
                                        .put("ts", row.ts)
                                        .put("type", row.type)
                                        .put("from", row.fromId)
                                        .put("from_name", row.fromName)
                                        .put("data", row.data.toString())
                                )
                            }
                            val ins = CloudApi.putRows(rows)
                            cloudDone = ins >= 0
                            if (cloudDone) Log.log(
                                "arch",
                                "云端 x" + batch.size + " ins=" + ins + " total=" + CloudApi.totalCount()
                            )
                            else Log.log("arch", "云端上传失败，转 S3 通道 x" + batch.size)
                        } catch (e: Exception) {
                            Log.log("arch-err", "云端: " + e.toString())
                        }
                    }
                    if (cloudDone) {
                        for (row in batch) ArchiveStore.markUploaded(row.id)
                        delay(2_000)
                        continue
                    }
                    try {
                        val arr = org.json.JSONArray()
                        for (row in batch) {
                            arr.put(JSONObject().put("t", row.type).put("from", row.fromId).put("fromName", row.fromName).put("ts", row.ts).put("data", row.data).toString())
                        }
                        val env = JSONObject().put("v", 1).put("n", batch.size).put("e", arr).toString()
                        val blob = Crypto.encrypt(env.toByteArray(Charsets.UTF_8), Config.keyB64)
                        if (blob == null) {
                            for (row in batch) ArchiveStore.markRetry(row.id, 12, System.currentTimeMillis() + 3600_000L)
                            continue
                        }
                        val k = Config.BASE + "arch/" + Config.deviceId + "/" + batch.first().ts + "-" +
                            UUID.randomUUID().toString().substring(0, 8) + ".enc"
                        c.putObject(k, blob)
                        for (row in batch) ArchiveStore.markUploaded(row.id)
                        Log.log("arch", "${"批量x" + batch.size} -> $k")
                    } catch (e: Exception) {
                        val m = e.message ?: ""
                        val nextDelay = if (m.contains("429")) {
                            was429 = true
                            sendPauseUntil = System.currentTimeMillis() + 60_000L
                            60_000L * (1 shl batch.first().retries.coerceAtMost(6))
                        } else {
                            Log.log("arch-err", m)
                            30_000L
                        }
                        for (row in batch) {
                            ArchiveStore.markRetry(row.id, row.retries + 1, System.currentTimeMillis() + nextDelay)
                        }
                    }
                }
                if (was429) delay(60_000)
            }
        } finally {
            uploaderWake.set(false)
        }
    }

    private fun decodeArchiveText(txt: String): List<Envelope> {
        val out = mutableListOf<Envelope>()
        try {
            val o = JSONObject(txt)
            if (o.has("v") && o.has("e")) {
                val arr = o.optJSONArray("e") ?: return emptyList()
                for (i in 0 until arr.length()) {
                    MsgCodec.decode(arr.optString(i))?.let { out.add(it) }
                }
                return out
            }
        } catch (e: Exception) {
        }
        MsgCodec.decode(txt)?.let { out.add(it) }
        return out
    }

    suspend fun archiveDevices(): List<String> {
        val c = client ?: return emptyList()
        return try {
            val r = c.listKeys(Config.BASE + "arch/", delimiter = "/")
            val raw = r.prefixes + r.keys
            raw.mapNotNull { k ->
                k.removePrefix(Config.BASE + "arch/").trimEnd('/').substringBefore('/').takeIf { it.isNotEmpty() }
            }.distinct()
        } catch (e: Exception) {
            Log.log("arch-err", e.toString())
            emptyList()
        }
    }

    suspend fun archivePage(
        dev: String,
        beforeTs: Long,
        limit: Int
    ): Pair<List<Envelope>, Long?> {
        val c = client ?: return Pair(emptyList(), null)
        val prefix = Config.BASE + "arch/" + dev + "/"
        try {
            var token: String? = null
            val ks = mutableListOf<Pair<Long, String>>()
            do {
                val r = c.listKeys(prefix, maxKeys = 1000, token = token)
                for (k in r.keys) {
                    val ts = k.removePrefix(prefix).substringBefore('-').toLongOrNull() ?: continue
                    ks.add(ts to k)
                }
                token = if (r.truncated) r.token else null
            } while (token != null)
            val sel = ks.filter { it.first < beforeTs }.sortedBy { it.first }.takeLast(limit)
            val out = mutableListOf<Envelope>()
            var fail429 = false
            for ((ts, k) in sel) {
                try {
                    val blob = c.getObject(k) ?: continue
                    val txt = Crypto.decrypt(blob, Config.keyB64) ?: continue
                    decodeArchiveText(String(txt, Charsets.UTF_8))?.let { out.addAll(it) }
                } catch (e: Exception) {
                    val m = e.message ?: ""
                    if (m.contains("429")) fail429 = true
                    Log.log("arch-err", "$k: $m")
                }
            }
            if (fail429) sendPauseUntil = System.currentTimeMillis() + 60_000L
            val cursor: Long? = if (fail429) out.lastOrNull()?.ts else {
                val first = sel.firstOrNull()?.first
                if (ks.any { it.first < (first ?: Long.MAX_VALUE) }) first else null
            }
            return Pair(out, cursor)
        } catch (e: Exception) {
            Log.log("arch-err", e.toString())
            return Pair(emptyList(), null)
        }
    }

    @Volatile
    private var sendPauseUntil = 0L

    suspend fun push(type: String, data: JSONObject) {
        val c = client
        if (c == null) {
            Log.log("send", "未配置，丢弃 $type")
            return
        }
        val now = System.currentTimeMillis()
        if (now < sendPauseUntil) {
            Log.log("send", "限流冷却中，丢弃 $type")
            return
        }
        try {
            val env = MsgCodec.encode(type, Config.deviceId, Config.deviceName, data)
            val blob = Crypto.encrypt(env.toByteArray(Charsets.UTF_8), Config.keyB64)
            if (blob == null) return Log.log("send-err", "加密失败")
            val k = Config.BASE + "msg/" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".enc"
            c.putObject(k, blob)
            Log.log("send", "$type -> $k")
        } catch (e: Exception) {
            val m = e.message ?: ""
            if (m.contains("429")) {
                sendPauseUntil = System.currentTimeMillis() + 60_000L
                Log.log("send-err", "$type: 429 限流，冷却 60s")
            } else {
                Log.log("send-err", "$type: $m")
            }
        }
    }

    suspend fun pushStatus(data: JSONObject) {
        val c = client ?: return
        try {
            val env = MsgCodec.encode("status", Config.deviceId, Config.deviceName, data)
            val blob = Crypto.encrypt(env.toByteArray(Charsets.UTF_8), Config.keyB64) ?: return
            c.putObject(Config.BASE + "dev/" + Config.deviceId + ".json.enc", blob)
        } catch (e: Exception) {
            Log.log("status-err", e.toString())
        }
    }

    suspend fun drain(chats: Boolean = false): List<Triple<String, Envelope, String>> {
        val c = client ?: return emptyList()
        val out = mutableListOf<Triple<String, Envelope, String>>()
        val now = System.currentTimeMillis()
        var keys: List<String> = c.listKeys(Config.BASE + "msg/").keys
        if (!Config.legacyDone) {
            val legacy = c.listKeys("msg/").keys
            keys = keys + legacy
            if (legacy.isEmpty()) Config.legacyDone = true
        }
        keys = keys.filterNot { it.endsWith("/") }
        keys = keys.filterNot { (skipUntil[it] ?: 0L) > now }
        for (k in keys) {
            try {
                val blob = c.getObject(k)
                if (blob == null) {
                    if (!chats) Log.log("recv", "对象不存在 $k")
                    continue
                }
                val txtBytes = Crypto.decrypt(blob, Config.keyB64)
                if (txtBytes == null) {
                    Log.log("recv-err", "解密失败(密钥不一致?) $k，已丢弃")
                    c.deleteObject(k)
                    continue
                }
                val env = MsgCodec.decode(String(txtBytes, Charsets.UTF_8))
                if (env == null) {
                    Log.log("recv-err", "解析失败 $k，已丢弃")
                    c.deleteObject(k)
                    continue
                }
                if (env.t == "chat") {
                    if (!chats) continue
                    if (env.to == Config.deviceId) {
                        c.deleteObject(k)
                        continue
                    }
                    if (env.from == Config.deviceId) continue
                    if (env.to.isNotEmpty() && env.to != Config.deviceId) continue
                    out.add(Triple(k, env, env.keyText()))
                    continue
                }
                if (chats) continue
                if (env.from == Config.deviceId) {
                    if (env.to.isEmpty()) {
                        c.deleteObject(k)
                    }
                    continue
                }
                if (env.to.isNotEmpty() && env.to != Config.deviceId) {
                    continue
                }
                out.add(Triple(k, env, env.keyText()))
            } catch (e: Exception) {
                val m = e.message ?: ""
                skipUntil[k] = if (m.contains("429")) System.currentTimeMillis() + 300_000L else System.currentTimeMillis() + 30_000L
                Log.log("recv-err", "$k: $e")
            }
        }
        if (skipUntil.size > 100) {
            val cut = System.currentTimeMillis()
            skipUntil.entries.removeIf { it.value < cut }
        }
        return out
    }

    suspend fun ack(key: String) {
        try {
            client?.deleteObject(key)
        } catch (e: Exception) {
            Log.log("ack-err", "$key: $e")
        }
    }

    suspend fun listStatuses(): List<Envelope> {
        val c = client ?: return emptyList()
        val out = mutableListOf<Envelope>()
        try {
            var devKeys: List<String> = c.listKeys(Config.BASE + "dev/").keys
            if (!Config.legacyDone) devKeys = devKeys + c.listKeys("dev/").keys
            for (k in devKeys.toSet().filterNot { it.endsWith("/") }) {
                val blob = c.getObject(k) ?: continue
                val txt = Crypto.decrypt(blob, Config.keyB64) ?: continue
                MsgCodec.decode(String(txt, Charsets.UTF_8))?.let { out.add(it) }
            }
        } catch (e: Exception) {
            Log.log("peers-err", e.toString())
        }
        return out
    }

    suspend fun testConnection(): List<String> {
        val out = mutableListOf<String>()
        var c = client
        if (c == null || !Config.isComplete()) {
            out.add("[X] 配置不完整: endpoint/bucket/ak/sk/key 需全部填写")
            return out
        }
        out.add("[>] 地址  " + Config.endpoint + " / 桶 " + Config.bucket + " / region " + Config.region)
        val stamp = Config.deviceId + "-" + System.currentTimeMillis()
        val content = ("pairsync-test " + Config.deviceId + " " + System.currentTimeMillis()).toByteArray(Charsets.UTF_8)
        val probeKey = Config.BASE + "probe/" + stamp + ".txt"
        try {
            c.putObject(probeKey, content)
            out.add("[OK] PUT 写入  $probeKey")
        } catch (e: Exception) {
            val m = e.message ?: ""
            if (m.contains("429")) {
                out.add("[~] PUT 被限流(429)，配置无问题；建议等几分钟再试")
                return out
            }
            out.add("[X] PUT 失败: " + m.take(200) + "(常见原因: AK/SK/签名/区域错, 或 Bucket 名称不对)")
            return out
        }
        try {
            val got = c.getObject(probeKey)
            when {
                got == null -> out.add("[X] GET 失败: 刚写入的对象读不回")
                got.contentEquals(content) -> out.add("[OK] GET 读回 内容一致")
                else -> out.add("[X] GET 读回 但内容不一致")
            }
        } catch (e: Exception) {
            val m = e.message ?: ""
            if (m.contains("429")) out.add("[~] GET 被限流(429)，不影响配置判定")
            else out.add("[X] GET 失败: $m")
        }
        try {
            c.deleteObject(probeKey)
            out.add("[OK] DELETE 清理  探针已删除")
        } catch (e: Exception) {
            out.add("[~] DELETE 失败(不影响连通): " + e.message)
        }
        try {
            val n = c.listKeys(Config.BASE + "msg/").keys.count { !it.endsWith("/") }
            out.add("[OK] LIST 信箱  msg/ 下还有 $n 条未处理")
        } catch (e: Exception) {
            out.add("[~] LIST 失败(可能限流): " + e.message)
        }
        val bad = out.any { it.startsWith("[X]") }
        if (bad) out.add("[X] 存在故障，按上述明细排查")
        else out.add("[√] 全链路畅通，可正常收发")
        return out
    }
}
