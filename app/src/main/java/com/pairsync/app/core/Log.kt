package com.pairsync.app.core

import java.io.File
import java.util.LinkedList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Log {
    private const val MAX = 600
    private val lines = LinkedList<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private var file: File? = null

    fun initFile(dir: File) {
        if (file == null) {
            val d = File(dir, "logs")
            if (!d.exists()) d.mkdirs()
            file = File(d, "sync.log")
        }
    }

    fun log(tag: String, msg: String) {
        val line = fmt.format(Date()) + " [" + tag + "] " + msg
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        file?.let { f ->
            try {
                java.io.PrintWriter(java.io.FileWriter(f, true)).use { it.println(line) }
            } catch (_: Exception) {
            }
        }
    }

    fun tail(n: Int = 250): String = synchronized(lines) {
        lines.takeLast(n).joinToString("\n")
    }

    fun clear() = synchronized(lines) { lines.clear() }
}
