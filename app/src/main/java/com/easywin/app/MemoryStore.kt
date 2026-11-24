package com.easywin.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Penyimpanan memori (kisi-kisi, materi, catatan) dalam SQLite lokal. */
class MemoryStore(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "memory.db", null, 1) {

    data class Item(val id: Long, val title: String, val content: String, val createdAt: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE memory(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, content TEXT NOT NULL, created INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun add(title: String, content: String): Long {
        val v = ContentValues()
        v.put("title", title)
        v.put("content", content)
        v.put("created", System.currentTimeMillis())
        return writableDatabase.insert("memory", null, v)
    }

    fun delete(id: Long) {
        writableDatabase.delete("memory", "id = ?", arrayOf(id.toString()))
    }

    fun all(): List<Item> {
        val out = ArrayList<Item>()
        readableDatabase.query("memory", null, null, null, null, null, "id DESC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    Item(
                        c.getLong(c.getColumnIndexOrThrow("id")),
                        c.getString(c.getColumnIndexOrThrow("title")),
                        c.getString(c.getColumnIndexOrThrow("content")),
                        c.getLong(c.getColumnIndexOrThrow("created"))
                    )
                )
            }
        }
        return out
    }

    /** Gabungan memori terbaru (dibatasi panjangnya) untuk dikirim sebagai konteks ke AI. */
    fun contextText(maxChars: Int = 8000): String {
        val sb = StringBuilder()
        for (m in all()) {
            val line = "- [" + m.title + "] " + m.content + "\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        return sb.toString()
    }
}
