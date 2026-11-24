package com.easywin.app

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Pembungkus Shizuku: cek status/izin dan menjalankan perintah shell (uid shell). */
object ShizukuHelper {

    class Out(val code: Int, val bytes: ByteArray, val err: String)

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission(code: Int) {
        try {
            Shizuku.requestPermission(code)
        } catch (e: Throwable) {
        }
    }

    /** Menjalankan perintah lewat `sh -c` dengan hak shell. Panggil dari thread latar belakang. */
    fun exec(cmd: String): Out {
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val err = StringBuilder()
            val t = Thread {
                try {
                    err.append(p.errorStream.bufferedReader().readText())
                } catch (e: Exception) {
                }
            }
            t.start()
            val bytes = p.inputStream.readBytes()
            val code = p.waitFor()
            t.join(1500)
            Out(code, bytes, err.toString())
        } catch (e: Throwable) {
            Out(-1, ByteArray(0), e.toString())
        }
    }
}
