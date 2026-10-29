package com.pairsync.app.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChatRow(
    val ts: Long,
    val type: String,
    val fromId: String,
    val fromName: String,
    val data: String
)

object ChatStore {
    @Volatile
    private var db: Helper? = null

    fun init(ctx: Context) {
        if (db == null) {
            synchronized(this) {
                if (db == null) db = Helper(ctx.applicationContext)
            }
        }
    }

    fun isDeleted(ts: Long, fromId: String, type: String): Boolean {
        val d = db ?: return false
        return try {
            d.readableDatabase.rawQuery(
                "SELECT 1 FROM deleted WHERE ts = ? AND fromId = ? AND type = ? LIMIT 1",
                arrayOf(ts.toString(), fromId, type)
            ).use { it.moveToFirst() }
        } catch (e: Exception) {
            false
        }
    }

    fun deletedSet(): HashSet<String> {
        val out = HashSet<String>()
        val d = db ?: return out
        try {
            d.readableDatabase.rawQuery("SELECT ts, fromId, type FROM deleted", arrayOf("")).use { c ->
                while (c.moveToNext()) out.add("" + c.getLong(0) + "|" + c.getString(1) + "|" + c.getString(2))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun del(ts: Long, fromId: String, type: String) {
        val d = db ?: return
        try {
            d.writableDatabase.insertWithOnConflict(
                "deleted", null,
                ContentValues().apply {
                    put("ts", ts); put("fromId", fromId); put("type", type)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
            d.writableDatabase.delete("msgs", "ts = ? AND fromId = ? AND type = ?", arrayOf(ts.toString(), fromId, type))
        } catch (e: Exception) {
        }
    }

    fun participants(self: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val d = db ?: return out
        try {
            d.readableDatabase.rawQuery(
                "SELECT fromId, IFNULL(MAX(fromName),'') nm, IFNULL(MAX(ts),0) t FROM msgs WHERE type = 'chat' AND fromId != ? GROUP BY fromId ORDER BY t DESC",
                arrayOf(self)
            ).use { c ->
                while (c.moveToNext()) out.add(c.getString(0) to (c.getString(1).ifEmpty { c.getString(0) }))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun add(ts: Long, type: String, fromId: String, fromName: String, data: String): Boolean {
        if (isDeleted(ts, fromId, type)) return false
        val d = db ?: return false
        return try {
            val cv = ContentValues()
            cv.put("ts", ts)
            cv.put("type", type)
            cv.put("fromId", fromId)
            cv.put("fromName", fromName)
            cv.put("data", data)
            d.writableDatabase.insertWithOnConflict("msgs", null, cv, SQLiteDatabase.CONFLICT_IGNORE) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun maxTs(): Long {
        val d = db ?: return 0
        return try {
            d.readableDatabase.rawQuery(
                "SELECT MAX(ts) FROM msgs", arrayOf("")
            ).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0
            }
        } catch (e: Exception) {
            0
        }
    }

    fun latest(limit: Int): List<ChatRow> {
        val d = db ?: return emptyList()
        val out = mutableListOf<ChatRow>()
        try {
            d.readableDatabase.rawQuery(
                "SELECT ts, type, fromId, fromName, data FROM msgs ORDER BY ts DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        ChatRow(
                            c.getLong(0),
                            c.getString(1),
                            c.getString(2),
                            c.getString(3),
                            c.getString(4)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        return out.reversed()
    }

    private class Helper(ctx: Context) :
        SQLiteOpenHelper(ctx, "pairsync_chat.db", null, 2) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL(
                "CREATE TABLE msgs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "ts INTEGER NOT NULL, " +
                    "type TEXT NOT NULL, " +
                    "fromId TEXT NOT NULL, " +
                    "fromName TEXT NOT NULL, " +
                    "data TEXT NOT NULL, " +
                    "UNIQUE(ts, fromId, type))"
            )
            d.execSQL("CREATE INDEX idx_ts ON msgs(ts)")
            onCreateExtra(d)
        }

        private fun onCreateExtra(d: SQLiteDatabase) {
            d.execSQL(
                "CREATE TABLE deleted (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "ts INTEGER NOT NULL, " +
                    "fromId TEXT NOT NULL, " +
                    "type TEXT NOT NULL, " +
                    "UNIQUE(ts, fromId, type))"
            )
        }

        override fun onUpgrade(d: SQLiteDatabase, old: Int, new: Int) {
            if (old < 2) onCreateExtra(d)
        }
    }
}
