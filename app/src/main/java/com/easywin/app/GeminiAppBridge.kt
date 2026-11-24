package com.easywin.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Xml
import androidx.core.content.FileProvider
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader

/**
 * Jembatan tanpa API: tangkapan layar dibagikan ke aplikasi Gemini di HP, prompt dikirim
 * (tombol Kirim ditekan lewat Shizuku bila perlu), lalu teks jawaban dibaca dari layar
 * aplikasi Gemini itu sendiri memakai `uiautomator dump`.
 *
 * Catatan: ini mengotomasi antarmuka aplikasi Gemini sehingga bisa rusak bila tampilannya berubah.
 */
object GeminiAppBridge {

    private val PKGS = listOf(
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox"
    )
    private val SEND_LABELS = setOf("send", "kirim", "send message", "kirim pesan", "submit")
    private val STOP_LABELS = setOf(
        "stop", "berhenti", "hentikan", "stop response", "hentikan respons", "hentikan jawaban"
    )
    private val BOUNDS = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")

    private class Node(
        val pkg: String,
        val cls: String,
        val text: String,
        val desc: String,
        val cx: Int,
        val cy: Int,
        val clickable: Boolean
    ) {
        fun matches(labels: Set<String>): Boolean =
            labels.contains(text.trim().lowercase()) || labels.contains(desc.trim().lowercase())
    }

    /** Paket Gemini yang bisa menerima gambar yang dibagikan, atau null bila belum terpasang. */
    fun findPackage(ctx: Context): String? {
        val probe = Intent(Intent.ACTION_SEND)
        probe.type = "image/png"
        for (p in PKGS) {
            probe.setPackage(p)
            if (ctx.packageManager.resolveActivity(probe, 0) != null) return p
        }
        return null
    }

    private fun dump(): List<Node>? {
        val file = "/data/local/tmp/ew_dump.xml"
        val o = ShizukuHelper.exec(
            "rm -f " + file + "; uiautomator dump " + file + " >/dev/null 2>&1; cat " + file
        )
        val xml = String(o.bytes, Charsets.UTF_8)
        if (!xml.contains("<hierarchy")) return null
        val out = ArrayList<Node>()
        try {
            val p = Xml.newPullParser()
            p.setInput(StringReader(xml))
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "node") {
                    val m = BOUNDS.find(p.getAttributeValue(null, "bounds") ?: "")
                    if (m != null) {
                        val l = m.groupValues[1].toInt()
                        val t = m.groupValues[2].toInt()
                        val r = m.groupValues[3].toInt()
                        val b = m.groupValues[4].toInt()
                        out.add(
                            Node(
                                p.getAttributeValue(null, "package") ?: "",
                                p.getAttributeValue(null, "class") ?: "",
                                p.getAttributeValue(null, "text") ?: "",
                                p.getAttributeValue(null, "content-desc") ?: "",
                                (l + r) / 2,
                                (t + b) / 2,
                                p.getAttributeValue(null, "clickable") == "true"
                            )
                        )
                    }
                }
                ev = p.next()
            }
        } catch (e: Exception) {
            return null
        }
        return out
    }

    private fun extractAnswer(nodes: List<Node>, prompt: String): String {
        val head = prompt.take(30)
        val sb = StringBuilder()
        for (n in nodes) {
            val t = n.text.trim()
            if (t.length < 15) continue
            if (t.contains(head)) continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(t)
        }
        return sb.toString()
    }

    private fun tap(n: Node) {
        ShizukuHelper.exec("input tap " + n.cx + " " + n.cy)
    }

    fun run(ctx: Context, png: ByteArray, prompt: String): AiClient.Result {
        val pkg = findPackage(ctx)
            ?: return AiClient.Result(false, "Aplikasi Gemini belum terpasang di HP ini.")

        val dir = File(ctx.cacheDir, "shared")
        dir.mkdirs()
        val f = File(dir, "screen.png")
        f.writeBytes(png)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

        val share = Intent(Intent.ACTION_SEND)
        share.type = "image/png"
        share.putExtra(Intent.EXTRA_STREAM, uri)
        share.putExtra(Intent.EXTRA_TEXT, prompt)
        share.setPackage(pkg)
        share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(share)
        } catch (e: Exception) {
            return AiClient.Result(false, "Tidak bisa membuka Gemini: " + (e.message ?: e.toString()))
        }

        val deadline = System.currentTimeMillis() + 120_000L
        var sent = false
        var pasted = false
        var sentAt = 0L
        var lastAnswer = ""
        var stable = 0
        var lastSeen: List<Node> = emptyList()

        Thread.sleep(2500)
        while (System.currentTimeMillis() < deadline) {
            val all = dump()
            if (all == null) {
                // uiautomator gagal saat layar masih beranimasi; coba lagi.
                Thread.sleep(1500)
                continue
            }
            val g = all.filter { it.pkg in PKGS }
            if (g.isEmpty()) {
                Thread.sleep(1200)
                continue
            }
            lastSeen = g

            if (!sent) {
                val sendBtn = g.firstOrNull { it.matches(SEND_LABELS) }
                if (sendBtn != null) {
                    tap(sendBtn)
                    sent = true
                    sentAt = System.currentTimeMillis()
                    Thread.sleep(2500)
                    continue
                }
                val edit = g.firstOrNull { it.cls.endsWith("EditText") }
                if (edit != null && !pasted) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("prompt", prompt))
                    tap(edit)
                    Thread.sleep(600)
                    ShizukuHelper.exec("input keyevent 279")
                    pasted = true
                    Thread.sleep(900)
                    continue
                }
                Thread.sleep(1200)
                continue
            }

            val generating = g.any { it.matches(STOP_LABELS) }
            val answer = extractAnswer(g, prompt)
            if (!generating && answer.isNotBlank() && answer == lastAnswer) stable++ else stable = 0
            lastAnswer = answer
            if (stable >= 2 && System.currentTimeMillis() - sentAt > 5000) {
                ShizukuHelper.exec("input keyevent 4")
                return AiClient.Result(true, answer)
            }
            Thread.sleep(1500)
        }

        val seen = lastSeen.map { it.text.trim() }.filter { it.isNotEmpty() }.take(15)
        val why = if (!sent) "tombol Kirim tidak ditemukan" else "jawaban belum selesai/terbaca"
        return AiClient.Result(
            false,
            "Waktu habis: " + why + ". Teks terakhir di layar Gemini: " + seen.joinToString(" | ").take(500)
        )
    }
}
