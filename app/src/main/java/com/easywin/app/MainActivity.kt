package com.easywin.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var spProvider: Spinner
    private lateinit var apiBox: LinearLayout
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etKkm: EditText
    private lateinit var etModelUrl: EditText
    private lateinit var etHfToken: EditText
    private lateinit var cbGpu: CheckBox
    private lateinit var tvScore: TextView
    private lateinit var tvShizuku: TextView
    private lateinit var tvBrain: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var modelBusy = false

    private val providers = listOf("local", "gemini_app", "gemini_api")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        updateScore()
        updateShizukuStatus()
        updateBrainStatus()
    }

    private fun label(s: String, size: Float = 14f, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = s
        tv.textSize = size
        if (bold) tv.setTypeface(null, Typeface.BOLD)
        tv.setPadding(0, dp(10), 0, dp(4))
        return tv
    }

    private fun btn(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(28), dp(20), dp(28))
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("Easy Win", 24f, true))
        root.addView(
            label(
                "Ketuk ikon melayang di atas aplikasi apa pun. Layar dibaca, soal dijawab " +
                    "lengkap dengan penjelasan, dan hasilnya bisa disimpan sebagai memori."
            )
        )

        root.addView(label("1. Sumber jawaban", 16f, true))
        spProvider = Spinner(this)
        spProvider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(
                "Otak lokal (model di HP, tanpa API & internet)",
                "Aplikasi Gemini + Shizuku (tanpa API)",
                "Gemini API (pakai API key)"
            )
        )
        root.addView(spProvider)

        // --- Otak lokal
        root.addView(label("2. Otak lokal", 16f, true))
        tvBrain = label("", 13f)
        root.addView(tvBrain)
        root.addView(
            label(
                "Model berjalan penuh di HP, jadi butuh RAM besar. Saran: Gemma 3n E2B (.litertlm, bisa " +
                    "melihat gambar, butuh sekitar 8 GB RAM) atau Gemma 3 1B IT (ringan, hanya teks). " +
                    "Cari di Hugging Face: google/gemma-3n-E2B-it-litert-lm atau litert-community/Gemma3-1B-IT. " +
                    "Model Gemma butuh login Hugging Face dan persetujuan lisensi.",
                12f
            )
        )
        etModelUrl = EditText(this)
        etModelUrl.hint = "Link unduh langsung file .litertlm"
        etModelUrl.setSingleLine(true)
        root.addView(etModelUrl)
        etHfToken = EditText(this)
        etHfToken.hint = "Token Hugging Face (opsional, untuk model terkunci)"
        etHfToken.setSingleLine(true)
        etHfToken.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(etHfToken)
        root.addView(btn("Unduh model dari link") { downloadModel() })
        root.addView(btn("Atau pilih file model dari HP (.litertlm)") { pickModel() })
        cbGpu = CheckBox(this)
        cbGpu.text = "Pakai GPU (lebih cepat, tapi tidak stabil di sebagian HP)"
        root.addView(cbGpu)

        // --- Shizuku (opsional)
        root.addView(label("3. Shizuku (opsional)", 16f, true))
        tvShizuku = label("", 13f)
        root.addView(tvShizuku)
        root.addView(
            label(
                "Dengan Shizuku, screenshot diambil tanpa dialog izin tangkap layar. " +
                    "Wajib hanya untuk mode 'Aplikasi Gemini + Shizuku'.",
                12f
            )
        )
        root.addView(btn("Beri izin Shizuku ke Easy Win") { requestShizuku() })

        apiBox = LinearLayout(this)
        apiBox.orientation = LinearLayout.VERTICAL
        etKey = EditText(this)
        etKey.hint = "Gemini API key"
        etKey.setSingleLine(true)
        etKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        etModel = EditText(this)
        etModel.hint = "Nama model"
        etModel.setSingleLine(true)
        apiBox.addView(label("Pengaturan Gemini API (hanya untuk mode API)", 14f, true))
        apiBox.addView(etKey)
        apiBox.addView(etModel)
        apiBox.addView(label("Key gratis: aistudio.google.com/apikey. Disimpan hanya di HP ini.", 12f))
        root.addView(apiBox)

        root.addView(label("4. Nilai KKM untuk akurasi AI", 16f, true))
        etKkm = EditText(this)
        etKkm.hint = "KKM (0-100)"
        etKkm.inputType = InputType.TYPE_CLASS_NUMBER
        root.addView(etKkm)
        tvScore = label("", 13f)
        root.addView(tvScore)
        root.addView(btn("Reset skor akurasi") {
            prefs.correct = 0
            prefs.total = 0
            updateScore()
        })
        root.addView(btn("Simpan pengaturan") {
            saveFields()
            Toast.makeText(this, "Pengaturan tersimpan", Toast.LENGTH_SHORT).show()
        })

        root.addView(label("5. Jalankan", 16f, true))
        root.addView(btn("Izinkan tampil di atas aplikasi lain") { openOverlaySettings() })
        root.addView(btn("Mulai floating window") { start() })
        root.addView(btn("Hentikan floating window") {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        })

        root.addView(label("6. Memori", 16f, true))
        root.addView(btn("Kelola memori (materi & kisi-kisi)") {
            startActivity(Intent(this, MemoryActivity::class.java))
        })

        spProvider.setSelection(providers.indexOf(prefs.provider).coerceAtLeast(0))
        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.provider = providers[pos]
                apiBox.visibility = if (pos == 2) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        apiBox.visibility = if (prefs.provider == "gemini_api") View.VISIBLE else View.GONE
        etKey.setText(prefs.geminiKey)
        etModel.setText(prefs.geminiModel)
        etKkm.setText(prefs.kkm.toString())
        etModelUrl.setText(prefs.modelUrl)
        etHfToken.setText(prefs.hfToken)
        cbGpu.isChecked = prefs.useGpu
        updateScore()
        updateShizukuStatus()
        updateBrainStatus()
    }

    private fun saveFields() {
        prefs.geminiKey = etKey.text.toString().trim()
        prefs.geminiModel = etModel.text.toString().trim().ifEmpty { "gemini-2.5-flash" }
        prefs.kkm = (etKkm.text.toString().toIntOrNull() ?: 75).coerceIn(0, 100)
        prefs.modelUrl = etModelUrl.text.toString().trim()
        prefs.hfToken = etHfToken.text.toString().trim()
        prefs.useGpu = cbGpu.isChecked
        updateScore()
    }

    private fun updateScore() {
        val t = prefs.total
        val c = prefs.correct
        tvScore.text = if (t == 0) {
            "Belum ada penilaian. Di panel hasil, tekan ✓/✗ untuk menilai jawaban AI."
        } else {
            val pct = c * 100 / t
            "Akurasi AI: " + c + "/" + t + " (" + pct + "%) — " +
                (if (pct >= prefs.kkm) "mencapai KKM " else "di bawah KKM ") + prefs.kkm
        }
    }

    private fun updateShizukuStatus() {
        val geminiPkg = GeminiAppBridge.findPackage(this)
        val shizuku = when {
            !ShizukuHelper.isRunning() -> "Shizuku: belum berjalan"
            !ShizukuHelper.hasPermission() -> "Shizuku: berjalan, izin belum diberikan"
            else -> "Shizuku: siap ✔"
        }
        val gemini = if (geminiPkg != null) "Aplikasi Gemini: terpasang ✔" else "Aplikasi Gemini: tidak ditemukan"
        tvShizuku.text = shizuku + "\n" + gemini
    }

    private fun updateBrainStatus() {
        val f = LocalBrain.modelFile(this)
        tvBrain.text = if (f.exists()) {
            val mb = f.length() / (1024 * 1024)
            val vision = if (LocalBrain.isVision(prefs.modelName)) "bisa melihat gambar" else "teks saja (OCR)"
            "Model terpasang ✔ " + prefs.modelName + " (" + mb + " MB, " + vision + ")"
        } else {
            "Model belum terpasang"
        }
    }

    private fun setBrainStatus(s: String) {
        ui.post { tvBrain.text = s }
    }

    private fun downloadModel() {
        saveFields()
        val url = prefs.modelUrl
        if (url.isBlank()) {
            Toast.makeText(this, "Isi link unduh dulu", Toast.LENGTH_SHORT).show()
            return
        }
        if (modelBusy) return
        modelBusy = true
        val name = Uri.parse(url).lastPathSegment ?: "model.litertlm"
        Toast.makeText(this, "Mengunduh… biarkan aplikasi tetap terbuka", Toast.LENGTH_LONG).show()
        Thread {
            val err = ModelDownloader.download(url, prefs.hfToken, LocalBrain.modelFile(this)) { done, total ->
                val pct = if (total > 0) " (" + (done * 100 / total) + "%)" else ""
                setBrainStatus("Mengunduh: " + (done / (1024 * 1024)) + " MB" + pct)
            }
            ui.post {
                modelBusy = false
                if (err == null) {
                    prefs.modelName = name
                    Toast.makeText(this, "Model terpasang", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Gagal: " + err, Toast.LENGTH_LONG).show()
                }
                updateBrainStatus()
            }
        }.start()
    }

    private fun pickModel() {
        if (modelBusy) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_MODEL)
    }

    private fun importModel(uri: Uri) {
        modelBusy = true
        var name = "model.litertlm"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
        }
        val finalName = name
        Thread {
            var err: String? = null
            try {
                val dest = LocalBrain.modelFile(this)
                val tmp = File(dest.path + ".part")
                contentResolver.openInputStream(uri)!!.use { ins ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - last >= (32L shl 20)) {
                                last = done
                                setBrainStatus("Menyalin model: " + (done / (1024 * 1024)) + " MB")
                            }
                        }
                    }
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) err = "Gagal menyimpan file model"
            } catch (e: Exception) {
                err = e.message ?: e.toString()
            }
            ui.post {
                modelBusy = false
                if (err == null) {
                    prefs.modelName = finalName
                    Toast.makeText(this, "Model terpasang", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Gagal: " + err, Toast.LENGTH_LONG).show()
                }
                updateBrainStatus()
            }
        }.start()
    }

    private fun requestShizuku() {
        if (!ShizukuHelper.isRunning()) {
            Toast.makeText(this, "Shizuku belum berjalan. Buka aplikasi Shizuku dan jalankan dulu.", Toast.LENGTH_LONG).show()
            val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (i != null) startActivity(i)
            return
        }
        if (ShizukuHelper.hasPermission()) {
            Toast.makeText(this, "Izin Shizuku sudah diberikan", Toast.LENGTH_SHORT).show()
        } else {
            ShizukuHelper.requestPermission(200)
        }
        updateShizukuStatus()
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
        )
    }

    private fun startOverlay(resultCode: Int, data: Intent?) {
        val i = Intent(this, OverlayService::class.java)
        if (data != null) {
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
        }
        startForegroundService(i)
        Toast.makeText(this, "Aktif. Buka halaman soal lalu ketuk ikon AI.", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun captureThenStart() {
        if (ShizukuHelper.hasPermission()) {
            startOverlay(0, null)
        } else {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        }
    }

    private fun start() {
        saveFields()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan 'tampil di atas aplikasi lain' dulu", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        when (prefs.provider) {
            "local" -> {
                if (!LocalBrain.modelFile(this).exists()) {
                    Toast.makeText(this, "Pasang model otak lokal dulu", Toast.LENGTH_LONG).show()
                    return
                }
                captureThenStart()
            }
            "gemini_app" -> {
                if (!ShizukuHelper.hasPermission()) {
                    Toast.makeText(this, "Aktifkan Shizuku dan beri izin dulu", Toast.LENGTH_LONG).show()
                    return
                }
                if (GeminiAppBridge.findPackage(this) == null) {
                    Toast.makeText(this, "Aplikasi Gemini belum terpasang", Toast.LENGTH_LONG).show()
                    return
                }
                startOverlay(0, null)
            }
            else -> {
                if (prefs.apiKey().isBlank()) {
                    Toast.makeText(this, "Isi API key dulu", Toast.LENGTH_LONG).show()
                    return
                }
                captureThenStart()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                startOverlay(resultCode, data)
            } else {
                Toast.makeText(this, "Izin tangkap layar ditolak", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQ_MODEL && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) importModel(uri)
        }
    }

    companion object {
        private const val REQ_CAPTURE = 100
        private const val REQ_MODEL = 101
    }
}
