package com.easywin.app

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream

/**
 * Otak lokal: model bahasa (LiteRT-LM) yang berjalan sepenuhnya di HP, tanpa API dan tanpa internet.
 * Teks layar dibaca dengan OCR on-device; model bervisi (mis. Gemma 3n) juga menerima gambar layar.
 */
object LocalBrain {

    private var engine: Engine? = null
    private var loadedGpu = false
    private var loadedVision = false
    private val lock = Any()

    fun modelFile(ctx: Context): File = File(ctx.filesDir, "model.litertlm")

    /** Menebak dari nama file apakah model mendukung input gambar. */
    fun isVision(name: String): Boolean =
        Regex("3n|e2b|e4b|vision|vlm|-vl|gemma-?4", RegexOption.IGNORE_CASE).containsMatchIn(name)

    fun ocr(bmp: Bitmap): String {
        return try {
            val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val t = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)))
            rec.close()
            t.text
        } catch (e: Exception) {
            ""
        }
    }

    private fun ensureEngine(ctx: Context, gpu: Boolean, vision: Boolean): Engine {
        synchronized(lock) {
            val cur = engine
            if (cur != null && loadedGpu == gpu && loadedVision == vision) return cur
            try {
                cur?.close()
            } catch (e: Exception) {
            }
            engine = null
            val backend = if (gpu) Backend.GPU() else Backend.CPU()
            val path = modelFile(ctx).path
            val cache = ctx.cacheDir.path
            val config = if (vision) {
                EngineConfig(
                    modelPath = path,
                    backend = backend,
                    visionBackend = if (gpu) Backend.GPU() else Backend.CPU(),
                    cacheDir = cache
                )
            } else {
                EngineConfig(modelPath = path, backend = backend, cacheDir = cache)
            }
            val e = Engine(config)
            runBlocking { e.initialize() }
            engine = e
            loadedGpu = gpu
            loadedVision = vision
            return e
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                engine?.close()
            } catch (e: Exception) {
            }
            engine = null
        }
    }

    private fun buildPrompt(ocrText: String, memory: String, vision: Boolean): String {
        val sb = StringBuilder()
        sb.append("Kamu adalah asisten yang menjawab soal dari layar HP (soal latihan, kuis, teka-teki, hitungan).\n\n")
        sb.append("Teks yang terbaca dari layar lewat OCR (mungkin ada salah baca):\n<layar>\n")
        sb.append(if (ocrText.isBlank()) "(tidak ada teks terbaca)" else ocrText.take(6000))
        sb.append("\n</layar>\n\n")
        if (vision) sb.append("Gambar layar juga dilampirkan. Pakai gambar untuk memeriksa teks OCR, diagram, dan tabel.\n\n")
        if (memory.isNotBlank()) {
            sb.append("Ingatan tersimpan yang mungkin relevan:\n").append(memory).append("\n\n")
        }
        sb.append("Tugas: jawab setiap soal setepat mungkin. Untuk hitungan atau logika, kerjakan langkah demi langkah ")
        sb.append("lalu periksa ulang hasilnya. Format tiap soal:\n")
        sb.append("Soal <nomor>\nJawaban: <jawaban final>\nPenjelasan: <langkah singkat>\n\n")
        sb.append("Jika layar berisi materi atau kisi-kisi (bukan soal), ringkas poin pentingnya. ")
        sb.append("Gunakan bahasa yang sama dengan layar.")
        return sb.toString()
    }

    fun answer(
        ctx: Context,
        bmp: Bitmap,
        ocrText: String,
        memory: String,
        useGpu: Boolean,
        modelName: String,
        onPartial: (String) -> Unit
    ): AiClient.Result {
        return try {
            val vision = isVision(modelName)
            val eng = ensureEngine(ctx, useGpu, vision)
            val prompt = buildPrompt(ocrText, memory, vision)
            val contents = if (vision) {
                val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height).toFloat())
                val small = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
                    )
                } else bmp
                val f = File(ctx.cacheDir, "screen_local.jpg")
                FileOutputStream(f).use { small.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                Contents.of(Content.ImageFile(f.path), Content.Text(prompt))
            } else {
                Contents.of(Content.Text(prompt))
            }
            val sb = StringBuilder()
            runBlocking {
                eng.createConversation().use { conv ->
                    conv.sendMessageAsync(contents).collect { m ->
                        val s = m.toString()
                        // Aliran bisa berupa potongan baru atau teks kumulatif; tangani keduanya.
                        if (sb.isNotEmpty() && s.startsWith(sb.toString())) {
                            sb.setLength(0)
                            sb.append(s)
                        } else {
                            sb.append(s)
                        }
                        onPartial(sb.toString())
                    }
                }
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) AiClient.Result(false, "Model tidak menghasilkan jawaban.")
            else AiClient.Result(true, out)
        } catch (e: Throwable) {
            AiClient.Result(false, e.message ?: e.toString())
        }
    }
}
