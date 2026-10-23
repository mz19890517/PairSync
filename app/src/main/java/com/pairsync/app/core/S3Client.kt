package com.pairsync.app.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class S3Client(
    endpoint: String,
    private val bucket: String,
    private val region: String,
    private val ak: String,
    private val sk: String
) {
    private val base = endpoint.trimEnd('/')
    private val host: String
    private val http = OkHttpClient()

    init {
        val u = URI(base)
        host = if (u.port > 0) u.host + ":" + u.port else u.host
    }

    fun listKeys(prefix: String, delimiter: String? = null, maxKeys: Int = 1000, token: String? = null): ListResult {
        val q = TreeMap<String, String>()
        q["list-type"] = "2"
        q["prefix"] = prefix
        q["max-keys"] = maxKeys.toString()
        if (delimiter != null) q["delimiter"] = delimiter
        if (token != null) q["continuation-token"] = token
        val resp = send("GET", "", q, null)
        resp.use {
            val body = it.body?.bytes() ?: ByteArray(0)
            if (!it.isSuccessful) throw IOException("list ${it.code}: " + bodyBrief(body))
            return parseList(body)
        }
    }

    fun getObject(key: String): ByteArray? {
        send("GET", key, TreeMap(), null).use {
            val body = it.body?.bytes() ?: ByteArray(0)
            if (it.code == 404) return null
            if (!it.isSuccessful) throw IOException("get ${it.code}: " + bodyBrief(body))
            return body
        }
    }

    fun putObject(key: String, data: ByteArray) {
        send("PUT", key, TreeMap(), data).use {
            if (!it.isSuccessful && it.code != 200 && it.code != 204) {
                throw IOException("put ${it.code}: " + bodyBrief(it.body?.bytes() ?: ByteArray(0)))
            }
        }
    }

    fun deleteObject(key: String) {
        send("DELETE", key, TreeMap(), null).use {
            if (!it.isSuccessful && it.code != 204 && it.code != 404) {
                throw IOException("delete ${it.code}: " + bodyBrief(it.body?.bytes() ?: ByteArray(0)))
            }
        }
    }

    private fun bodyBrief(b: ByteArray): String {
        val s = String(b.take(600).toByteArray(), Charsets.UTF_8).replace("\n", " ")
        return if (s.isBlank()) "(无响应体)" else s
    }

    private fun send(method: String, key: String, query: TreeMap<String, String>, body: ByteArray?): okhttp3.Response {
        throttle()
        val amzDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val stampDate = amzDate.substring(0, 8)
        val payloadHash = sha256Hex(body ?: ByteArray(0))
        val canonicalPath = enc("/" + bucket + "/" + key, true)
        val canonicalQuery = query.entries.joinToString("&") {
            enc(it.key, false) + "=" + enc(it.value, false)
        }
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = method + "\n" + canonicalPath + "\n" + canonicalQuery + "\n" +
            "host:" + host + "\n" +
            "x-amz-content-sha256:" + payloadHash + "\n" +
            "x-amz-date:" + amzDate + "\n" +
            "\n" +
            signedHeaders + "\n" +
            payloadHash
        val scope = stampDate + "/" + region + "/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))
        val kDate = hmac(("AWS4" + sk).toByteArray(Charsets.UTF_8), stampDate)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, "s3")
        val kSigning = hmac(kService, "aws4_request")
        val sig = hex(hmac(kSigning, stringToSign))
        val auth = "AWS4-HMAC-SHA256 Credential=" + ak + "/" + scope + ", SignedHeaders=" + signedHeaders + ", Signature=" + sig

        val url = base + canonicalPath + if (canonicalQuery.isNotEmpty()) "?" + canonicalQuery else ""
        val rb = Request.Builder().url(url)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
            .header("Authorization", auth)
            .header("User-Agent", "pairsync/0.1")
            .method(method, if (body != null) {
                body.toRequestBody("application/octet-stream".toMediaType())
            } else when (method) {
                "GET", "HEAD" -> null
                else -> ByteArray(0).toRequestBody(null)
            })
        return http.newCall(rb.build()).execute()
    }

    companion object {
        @Volatile
        private var lastReq = 0L
        private const val MIN_GAP_MS = 1500L

        private fun throttle() {
            val now = System.currentTimeMillis()
            val wait = lastReq + MIN_GAP_MS - now
            if (wait > 0) {
                try { Thread.sleep(wait) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            lastReq = System.currentTimeMillis()
        }
    }

    private fun enc(s: String, keepSlash: Boolean): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            when {
                c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 -> sb.append(c.toChar())
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code -> sb.append(c.toChar())
                c == '/'.code && keepSlash -> sb.append('/')
                else -> sb.append('%').append(String.format(Locale.US, "%02X", c))
            }
        }
        return sb.toString()
    }

    private fun sha256Hex(data: ByteArray): String = hex(java.security.MessageDigest.getInstance("SHA-256").digest(data))

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray(Charsets.UTF_8))

    private fun hex(b: ByteArray): String = b.joinToString("") { String.format(Locale.US, "%02x", it) }

    class ListResult {
        var truncated = false
        var token: String? = null
        val keys = mutableListOf<String>()
        val prefixes = mutableListOf<String>()
    }

    private fun parseList(xml: ByteArray): ListResult {
        val r = ListResult()
        val p = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(ByteArrayInputStream(xml), null)
        var event = p.eventType
        var inContents = false
        var inPrefixes = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when {
                event == XmlPullParser.START_TAG -> when (p.name) {
                    "Contents" -> inContents = true
                    "Key" -> if (inContents) r.keys.add(p.nextText())
                    "CommonPrefixes" -> inPrefixes = true
                    "Prefix" -> if (inPrefixes) r.prefixes.add(p.nextText())
                    "IsTruncated" -> r.truncated = p.nextText() == "true"
                    "NextContinuationToken" -> r.token = p.nextText()
                }
                event == XmlPullParser.END_TAG -> {
                    when (p.name) {
                        "Contents" -> inContents = false
                        "CommonPrefixes" -> inPrefixes = false
                    }
                }
            }
            event = p.next()
        }
        return r
    }
}
