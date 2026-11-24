package com.easywin.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Klien AI sederhana tanpa dependensi tambahan: mendukung Gemini dan Claude (vision). */
object AiClient {

    data class Result(val ok: Boolean, val text: String)

    private fun buildPrompt(memory: String): String {
        val mem = if (memory.isBlank()) "(kosong)" else memory
        return """
Kamu adalah asisten yang membaca tangkapan layar dan menjawab soal yang terlihat (soal ujian latihan, kuis, teka-teki, hitungan, dan sejenisnya).

Langkah kerja:
1. Baca SEMUA soal yang terlihat di gambar, lengkap dengan pilihan jawabannya.
2. Jawab tiap soal setepat mungkin. Berpikir hati-hati; untuk hitungan atau logika, periksa ulang hasilnya sebelum menjawab.
3. Jika layar berisi materi atau kisi-kisi (bukan soal), jangan mengarang soal. Cukup ringkas isinya sebagai catatan belajar.
4. Gunakan bahasa yang sama dengan bahasa di layar.
5. Jika ada ingatan di bawah yang relevan dengan soal, manfaatkan. Jika tidak relevan, abaikan.

Ingatan tersimpan:
$mem

Balas HANYA dengan JSON valid (tanpa markdown, tanpa teks lain) dengan bentuk:
{
  "questions": [
    {
      "number": "nomor soal",
      "question": "teks soal singkat",
      "options": ["A. ...", "B. ..."],
      "answer": "jawaban final, misal 'B. ...' atau hasil akhir",
      "explanation": "penjelasan langkah demi langkah yang ringkas",
      "confidence": 0
    }
  ],
  "memory_title": "judul singkat untuk isi layar ini",
  "memory_note": "ringkasan isi layar (materi, kisi-kisi, konsep penting) yang layak disimpan sebagai ingatan"
}
Jika bukan pilihan ganda, isi "options" dengan array kosong. "confidence" adalah angka 0-100.
""".trimIndent()
    }

    fun ask(prefs: Prefs, imageBase64: String, memory: String): Result {
        return try {
            val prompt = buildPrompt(memory)
            if (prefs.provider == "claude") askClaude(prefs, imageBase64, prompt)
            else askGemini(prefs, imageBase64, prompt)
        } catch (e: Exception) {
            Result(false, e.message ?: e.toString())
        }
    }

    private fun askGemini(prefs: Prefs, img: String, prompt: String): Result {
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", img)
                )
            )
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2)
            )
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            prefs.model() + ":generateContent"
        val resp = post(
            url,
            mapOf("Content-Type" to "application/json", "x-goog-api-key" to prefs.apiKey()),
            body.toString()
        )
        if (resp.first !in 200..299) return Result(false, "HTTP " + resp.first + ": " + errMsg(resp.second))
        val outParts = JSONObject(resp.second)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until outParts.length()) sb.append(outParts.getJSONObject(i).optString("text"))
        return Result(true, sb.toString())
    }

    private fun askClaude(prefs: Prefs, img: String, prompt: String): Result {
        val content = JSONArray()
            .put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", img)
                )
            )
            .put(JSONObject().put("type", "text").put("text", prompt))
        val body = JSONObject()
            .put("model", prefs.model())
            .put("max_tokens", 4096)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content))
            )
        val resp = post(
            "https://api.anthropic.com/v1/messages",
            mapOf(
                "Content-Type" to "application/json",
                "x-api-key" to prefs.apiKey(),
                "anthropic-version" to "2023-06-01"
            ),
            body.toString()
        )
        if (resp.first !in 200..299) return Result(false, "HTTP " + resp.first + ": " + errMsg(resp.second))
        val blocks = JSONObject(resp.second).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return Result(true, sb.toString())
    }

    private fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 120000
        c.doOutput = true
        for ((k, v) in headers) c.setRequestProperty(k, v)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        return Pair(code, text)
    }

    private fun errMsg(raw: String): String {
        return try {
            val e = JSONObject(raw).optJSONObject("error")
            e?.optString("message")?.takeIf { it.isNotBlank() } ?: raw.take(300)
        } catch (ex: Exception) {
            raw.take(300)
        }
    }

    /** Mengambil objek JSON pertama dari teks balasan AI (toleran terhadap teks di sekitarnya). */
    fun parseJson(text: String): JSONObject? {
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return try {
            JSONObject(text.substring(s, e + 1))
        } catch (ex: Exception) {
            null
        }
    }
}
