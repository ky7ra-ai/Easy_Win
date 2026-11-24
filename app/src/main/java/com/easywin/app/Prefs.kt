package com.easywin.app

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("easywin", Context.MODE_PRIVATE)

    private fun str(k: String, d: String): String = sp.getString(k, d) ?: d
    private fun putStr(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun int(k: String, d: Int): Int = sp.getInt(k, d)
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()

    /** "gemini_app" (tanpa API, lewat Shizuku) atau "gemini_api" (pakai API key). */
    var provider: String
        get() {
            val v = str("provider", "gemini_app")
            return if (v == "gemini") "gemini_api" else v
        }
        set(v) = putStr("provider", v)

    var geminiKey: String
        get() = str("geminiKey", "")
        set(v) = putStr("geminiKey", v)

    var claudeKey: String
        get() = str("claudeKey", "")
        set(v) = putStr("claudeKey", v)

    var geminiModel: String
        get() = str("geminiModel", "gemini-2.5-flash")
        set(v) = putStr("geminiModel", v)

    var claudeModel: String
        get() = str("claudeModel", "claude-sonnet-5-5")
        set(v) = putStr("claudeModel", v)

    /** Nilai KKM (0-100) sebagai ambang akurasi AI. */
    var kkm: Int
        get() = int("kkm", 75)
        set(v) = putInt("kkm", v)

    /** Jumlah analisis yang dinilai "AI benar". */
    var correct: Int
        get() = int("correct", 0)
        set(v) = putInt("correct", v)

    /** Jumlah analisis yang sudah dinilai. */
    var total: Int
        get() = int("total", 0)
        set(v) = putInt("total", v)

    fun apiKey(): String = if (provider == "claude") claudeKey else geminiKey
    fun model(): String = if (provider == "claude") claudeModel else geminiModel
}
