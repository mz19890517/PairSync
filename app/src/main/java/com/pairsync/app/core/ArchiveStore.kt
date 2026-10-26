package com.pairsync.app.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

data class ArchiveRow(
    val id: Long,
    val ts: Long,
    val type: String,
    val fromId: String,
    val fromName: String,
    val data: JSONObject,
    val state: Int,
    val retries: Int
)

object ArchiveStore {
    @Volatile
    private var db: Helper? = null

    fun init(ctx: Context) {
        if (db == null) {
            synchronized(this) {
                if (db == null) db = Helper(ctx.applicationContext)
            }
        }
    }

    private const val STATE_PENDING = 0
    private const val STATE_UPLOADED = 1

    fun record(type: String, fromId: String, fromName: String, data: JSONObject, ts: Long = System.currentTimeMillis()): Long {
        val d = db ?: return -1
        return try {
            val cv = ContentValues()
            cv.put("ts", ts)
            cv.put("type", type)
            cv.put("fromId", fromId)
            cv.put("fromName", fromName)
            cv.put("data", data.toString())
            cv.put("state", STATE_PENDING)
            cv.put("nextTry", 0)
            cv.put("retries", 0)
            d.writableDatabase.insert("archive", null, cv)
        } catch (e: Exception) {
            Log.log("local-err", e.toString())
            -1
        }
    }

    fun pendingBatch(now: Long, limit: Int): List<ArchiveRow> {
        val d = db ?: return emptyList()
        val out = mutableListOf<ArchiveRow>()
        try {
            d.readableDatabase.rawQuery(
                "SELECT id, ts, type, fromId, fromName, data, state, retries FROM archive WHERE state = 0 AND nextTry <= ? ORDER BY ts ASC LIMIT ?",
                arrayOf(now.toString(), limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        ArchiveRow(
                            c.getLong(0), c.getLong(1), c.getString(2),
                            c.getString(3), c.getString(4),
                            JSONObject(c.getString(5)), c.getInt(6), c.getInt(7)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.log("local-err", e.toString())
        }
        return out
    }

    fun markUploaded(id: Long) {
        val d = db ?: return
        try {
            d.writableDatabase.execSQL("UPDATE archive SET state = 1 WHERE id = ?", arrayOf(id))
        } catch (e: Exception) {
            Log.log("local-err", e.toString())
        }
    }

    fun markRetry(id: Long, retries: Int, nextTry: Long) {
        val d = db ?: return
        try {
            d.writableDatabase.execSQL(
                "UPDATE archive SET retries = ?, nextTry = ? WHERE id = ?",
                arrayOf<Any?>(retries, nextTry, id)
            )
        } catch (e: Exception) {
            Log.log("local-err", e.toString())
        }
    }

    fun page(beforeTs: Long, limit: Int): List<ArchiveRow> {
        val d = db ?: return emptyList()
        val out = mutableListOf<ArchiveRow>()
        try {
            d.readableDatabase.rawQuery(
                "SELECT id, ts, type, fromId, fromName, data, state, retries FROM archive WHERE ts < ? ORDER BY ts DESC LIMIT ?",
                arrayOf(beforeTs.toString(), limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        ArchiveRow(
                            c.getLong(0), c.getLong(1), c.getString(2),
                            c.getString(3), c.getString(4),
                            JSONObject(c.getString(5)), c.getInt(6), c.getInt(7)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.log("local-err", e.toString())
        }
        return out
    }

    fun pendingCount(): Int {
        val d = db ?: return 0
        return try {
            d.readableDatabase.rawQuery("SELECT COUNT(*) FROM archive WHERE state = 0", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (e: Exception) {
            0
        }
    }

    fun purge(now: Long) {
        val d = db ?: return
        try {
            val cutoff = now - 90L * 24 * 3600 * 1000
            d.writableDatabase.execSQL("DELETE FROM archive WHERE state = 1 AND ts < ?", arrayOf(cutoff))
            d.writableDatabase.execSQL(
                "DELETE FROM archive WHERE id IN (SELECT id FROM archive WHERE state = 1 ORDER BY ts DESC LIMIT -1 OFFSET ?)",
                arrayOf("20000")
            )
        } catch (e: Exception) {
            Log.log("local-err", e.toString())
        }
    }

    private class Helper(ctx: Context) :
        SQLiteOpenHelper(ctx, "pairsync_archive.db", null, 1) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL(
                "CREATE TABLE archive (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "ts INTEGER NOT NULL, " +
                    "type TEXT NOT NULL, " +
                    "fromId TEXT NOT NULL, " +
                    "fromName TEXT NOT NULL, " +
                    "data TEXT NOT NULL, " +
                    "state INTEGER NOT NULL DEFAULT 0, " +
                    "nextTry INTEGER NOT NULL DEFAULT 0, " +
                    "retries INTEGER NOT NULL DEFAULT 0)"
            )
            d.execSQL("CREATE INDEX idx_ts ON archive(ts)")
            d.execSQL("CREATE INDEX idx_state ON archive(state, nextTry)")
        }

        override fun onUpgrade(d: SQLiteDatabase, old: Int, new: Int) {
            // v1
        }
    }
}
