package com.easywin.app

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Mengunduh file model (besar) dengan dukungan redirect dan token Hugging Face opsional. */
object ModelDownloader {

    private fun open(url: URL, token: String): HttpURLConnection {
        val c = url.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 20000
        c.readTimeout = 60000
        // Token hanya dikirim ke huggingface.co, tidak ke CDN hasil redirect.
        if (token.isNotBlank() && url.host.endsWith("huggingface.co")) {
            c.setRequestProperty("Authorization", "Bearer " + token)
        }
        return c
    }

    /** Mengembalikan null bila berhasil, atau pesan error. Panggil dari thread latar belakang. */
    fun download(urlStr: String, token: String, dest: File, onProgress: (Long, Long) -> Unit): String? {
        return try {
            var url = URL(urlStr)
            var conn = open(url, token)
            var hops = 0
            while (conn.responseCode in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return "Redirect tanpa tujuan"
                conn.disconnect()
                url = URL(url, loc)
                hops++
                if (hops > 6) return "Terlalu banyak redirect"
                conn = open(url, token)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val hint = if (code == 401 || code == 403) {
                    " (model ini butuh login Hugging Face: isi token dan setujui lisensi modelnya)"
                } else ""
                return "HTTP " + code + hint
            }
            val total = conn.contentLengthLong
            val tmp = File(dest.path + ".part")
            conn.inputStream.use { ins ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= (8L shl 20)) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) return "Gagal menyimpan file model"
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }
}
