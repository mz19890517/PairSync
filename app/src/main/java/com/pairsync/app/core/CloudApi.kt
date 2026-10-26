package com.pairsync.app.core

import com.pairsync.app.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CloudApi {
    val HOST: String get() = Config.cloudHost
    const val GATE_API = "https://api.sanfengyun.com/www/break.php"
    val GATE_PW: String get() = Config.cloudGatePw
    val TOK: String get() = Config.cloudTok

    @Volatile
    private var gateCookie = ""

    @Volatile
    private var cookieTime = 0L

    fun ok(): Boolean {
        return !gateCookie.isEmpty() && System.currentTimeMillis() - cookieTime < 4 * 3600_000L
    }

    private fun solveGate(): String? {
        return try {
            val conn = URL(GATE_API + "?cmd=visit_sysdomain&password=" + java.net.URLEncoder.encode(GATE_PW, "UTF-8")).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "PairSync/1.1")
            conn.setRequestProperty("Referer", HOST + "/")
            val txt = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val st = Regex("<cmdState>(.*?)</cmdState>").find(txt)?.groupValues?.get(1) ?: return null
            val ck = Regex("<errMsg>(.*?)</errMsg>").find(txt)?.groupValues?.get(1) ?: return null
            if (st != "操作成功") return null
            ck
        } catch (e: Exception) {
            null
        }
    }

    private fun http(method: String, path: String, body: String?, host: String): Pair<Int, String>? {
        return try {
            val conn = URL(host + path).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "PairSync/1.1")
            conn.setRequestProperty("X-Tok", TOK)
            if (gateCookie.isNotEmpty()) conn.setRequestProperty("Cookie", "zhujiwusysdomain=" + gateCookie)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            Pair(code, txt)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun call(action: String, params: String = "", post: String? = null, rounds: Int = 3): String? =
        withContext(Dispatchers.IO) {
            val path = "/api.php?action=" + action + (if (params.isEmpty()) "" else "&" + params)
            for (i in 0 until rounds) {
                if (System.currentTimeMillis() - cookieTime > 4 * 3600_000L) gateCookie = ""
                var r = http(if (post != null) "POST" else "GET", path, post, HOST)
                if (r == null && i == 0) r = http(if (post != null) "POST" else "GET", path, post, HOST)
                if (r == null) return@withContext null
                if (r.second.contains("webstop/break/") || r.second.contains("系统域名") || r.second.contains("主机域名")) {
                    val ck = solveGate() ?: return@withContext null
                    gateCookie = ck
                    cookieTime = System.currentTimeMillis()
                    continue
                }
                return@withContext if (r.first in 200..299) r.second else null
            }
            null
        }

    suspend fun putRows(rows: JSONArray): Int {
        val r = call("put", post = rows.toString()) ?: return -1
        return try {
            JSONObject(r).optInt("ins", 0)
        } catch (e: Exception) {
            -1
        }
    }

    suspend fun totalCount(): Int {
        val r = call("count") ?: return -1
        return try {
            JSONObject(r).optInt("count", -1)
        } catch (e: Exception) {
            -1
        }
    }

    suspend fun devices(): JSONArray? {
        val r = call("devices") ?: return null
        return try {
            JSONArray(r)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun page(dev: String, before: Long, limit: Int, type: String = ""): JSONArray? {
        val p = StringBuilder()
        p.append("before=").append(before).append("&limit=").append(limit)
        if (dev.isNotEmpty()) p.append("&device=").append(java.net.URLEncoder.encode(dev, "UTF-8"))
        if (type.isNotEmpty()) p.append("&type=").append(java.net.URLEncoder.encode(type, "UTF-8"))
        val r = call("list", p.toString()) ?: return null
        return try {
            JSONArray(r)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun wait(after: Long, secs: Int = 20): JSONObject? =
        withContext(Dispatchers.IO) {
            val path = "/api.php?action=wait&after=" + after + "&secs=" + secs
            for (i in 0 until 3) {
                if (System.currentTimeMillis() - cookieTime > 4 * 3600_000L) gateCookie = ""
                var r: Pair<Int, String>? = try {
                    val conn = URL(HOST + path).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 20000
                    conn.readTimeout = secs * 1000 + 15000
                    conn.setRequestProperty("User-Agent", "PairSync/1.3")
                    conn.setRequestProperty("X-Tok", TOK)
                    if (gateCookie.isNotEmpty()) conn.setRequestProperty("Cookie", "zhujiwusysdomain=" + gateCookie)
                    val code = conn.responseCode
                    val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    conn.disconnect()
                    Pair(code, txt)
                } catch (e: Exception) {
                    null
                }
                if (r == null && i < 2) continue
                if (r == null) return@withContext null
                val bodyTxt = r.second.trim().removePrefix("\uFEFF")
                if (r.second.contains("webstop/break/") || r.second.contains("系统域名") || r.second.contains("主机域名")) {
                    val ck = solveGate() ?: return@withContext null
                    gateCookie = ck
                    cookieTime = System.currentTimeMillis()
                    continue
                }
                if (r.first !in 200..299) return@withContext null
                return@withContext try {
                    JSONObject(bodyTxt)
                } catch (e: Exception) {
                    null
                }
            }
            null
        }

    suspend fun ping(): Boolean {
        val r = call("devices") ?: return false
        return try {
            JSONArray(r).let { true }
        } catch (e: Exception) {
            false
        }
    }
}
