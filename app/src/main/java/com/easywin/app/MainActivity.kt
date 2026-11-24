package com.easywin.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var spProvider: Spinner
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etKkm: EditText
    private lateinit var tvScore: TextView

    private val providers = listOf("gemini", "claude")

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

    private fun edit(hint: String, secret: Boolean = false, number: Boolean = false): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setSingleLine(true)
        if (secret) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (number) e.inputType = InputType.TYPE_CLASS_NUMBER
        return e
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
                "Ketuk ikon melayang di atas aplikasi apa pun. Layar dibaca, soal dijawab AI " +
                    "lengkap dengan penjelasan, dan hasilnya bisa disimpan sebagai memori."
            )
        )

        root.addView(label("1. Penyedia AI", 16f, true))
        spProvider = Spinner(this)
        spProvider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Gemini (ada tier gratis)", "Claude (Anthropic)")
        )
        root.addView(spProvider)

        etKey = edit("API key", secret = true)
        etModel = edit("Nama model")
        etKkm = edit("KKM (0-100)", number = true)
        root.addView(etKey)
        root.addView(etModel)
        root.addView(
            label(
                "Gemini: buat API key gratis di aistudio.google.com/apikey. " +
                    "Claude: console.anthropic.com. Key hanya disimpan di HP ini.",
                12f
            )
        )

        root.addView(label("2. Nilai KKM untuk akurasi AI", 16f, true))
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

        root.addView(label("3. Jalankan", 16f, true))
        root.addView(btn("Izinkan tampil di atas aplikasi lain") { openOverlaySettings() })
        root.addView(btn("Mulai floating window") { start() })
        root.addView(btn("Hentikan floating window") {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        })

        root.addView(label("4. Memori", 16f, true))
        root.addView(btn("Kelola memori (materi & kisi-kisi)") {
            startActivity(Intent(this, MemoryActivity::class.java))
        })

        spProvider.setSelection(providers.indexOf(prefs.provider).coerceAtLeast(0))
        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.provider = providers[pos]
                loadFields()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        loadFields()
        etKkm.setText(prefs.kkm.toString())
        updateScore()
    }

    private fun loadFields() {
        if (prefs.provider == "claude") {
            etKey.setText(prefs.claudeKey)
            etModel.setText(prefs.claudeModel)
        } else {
            etKey.setText(prefs.geminiKey)
            etModel.setText(prefs.geminiModel)
        }
    }

    private fun saveFields() {
        val key = etKey.text.toString().trim()
        val model = etModel.text.toString().trim()
        if (prefs.provider == "claude") {
            prefs.claudeKey = key
            prefs.claudeModel = model.ifEmpty { "claude-sonnet-5-5" }
        } else {
            prefs.geminiKey = key
            prefs.geminiModel = model.ifEmpty { "gemini-2.5-flash" }
        }
        prefs.kkm = (etKkm.text.toString().toIntOrNull() ?: 75).coerceIn(0, 100)
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

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
        )
    }

    private fun start() {
        saveFields()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan 'tampil di atas aplikasi lain' dulu", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        if (prefs.apiKey().isBlank()) {
            Toast.makeText(this, "Isi API key dulu", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
            startForegroundService(i)
            Toast.makeText(this, "Aktif. Buka halaman soal lalu ketuk ikon AI.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } else if (requestCode == REQ_CAPTURE) {
            Toast.makeText(this, "Izin tangkap layar ditolak", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQ_CAPTURE = 100
    }
}
