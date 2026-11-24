package com.easywin.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Floating window: ikon melayang yang bisa digeser. Saat diketuk, layar ditangkap,
 * dikirim ke AI, lalu hasilnya (jawaban, penjelasan) tampil di panel.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var store: MemoryStore
    private val ui by lazy { ContextThemeWrapper(this, android.R.style.Theme_Material_Light) }
    private val main = Handler(Looper.getMainLooper())

    // Tangkap layar
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var curW = 0
    private var curH = 0
    private var capThread: HandlerThread? = null
    private var capHandler: Handler? = null
    private val lock = Any()
    private var latest: Image? = null

    // UI
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var panelAttached = false
    private lateinit var tvStatus: TextView
    private lateinit var resultBox: LinearLayout
    private lateinit var tvScore: TextView

    // Status
    private var lastResult: JSONObject? = null
    private var rated = false
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        store = MemoryStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        projection = mp
        val thread = HandlerThread("easywin-capture")
        thread.start()
        capThread = thread
        capHandler = Handler(thread.looper)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        buildBubble()
        buildPanel()
        val size = screenSize()
        ensureDisplay(size.first, size.second)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Easy Win", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Easy Win aktif")
            .setContentText("Ketuk ikon melayang untuk menjawab soal di layar")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .addAction(android.R.drawable.ic_delete, "Hentikan", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------------------------------------------------------- Ikon melayang

    private fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

    private fun buildBubble() {
        val size = dp(56)
        val tv = TextView(ui)
        tv.text = "AI"
        tv.setTextColor(Color.WHITE)
        tv.textSize = 16f
        tv.gravity = Gravity.CENTER
        tv.setTypeface(null, Typeface.BOLD)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#6750A4"))
        bg.setStroke(dp(2), Color.WHITE)
        tv.background = bg
        tv.elevation = dp(6).toFloat()

        val lp = overlayParams(size, size)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8)
        lp.y = dp(200)

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val threshold = dp(6)
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > threshold || abs(dy) > threshold) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(tv, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) analyze()
                    true
                }
                else -> false
            }
        }
        wm.addView(tv, lp)
        bubble = tv
    }

    // ---------------------------------------------------------------- Panel hasil

    private fun smallBtn(t: String, onClick: () -> Unit): Button {
        val b = Button(ui)
        b.text = t
        b.isAllCaps = false
        b.textSize = 12f
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(8), dp(6), dp(8), dp(6))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildPanel() {
        val p = LinearLayout(ui)
        p.orientation = LinearLayout.VERTICAL
        p.setPadding(dp(14), dp(12), dp(14), dp(12))
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadius = dp(18).toFloat()
        bg.setStroke(dp(1), Color.parseColor("#CCCCCC"))
        p.background = bg
        p.elevation = dp(10).toFloat()

        val header = LinearLayout(ui)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(ui)
        title.text = "Easy Win"
        title.textSize = 16f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.BLACK)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallBtn("Ulangi") { analyze() })
        header.addView(smallBtn("✕") { hidePanel() })
        p.addView(header)

        tvStatus = TextView(ui)
        tvStatus.textSize = 13f
        tvStatus.setTextColor(Color.parseColor("#444444"))
        tvStatus.setPadding(0, dp(6), 0, dp(6))
        p.addView(tvStatus)

        val sv = ScrollView(ui)
        resultBox = LinearLayout(ui)
        resultBox.orientation = LinearLayout.VERTICAL
        sv.addView(resultBox)
        p.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val actions = LinearLayout(ui)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.addView(smallBtn("💾 Simpan memori") { saveMemory() })
        actions.addView(smallBtn("✓ AI benar") { rate(true) })
        actions.addView(smallBtn("✗ AI salah") { rate(false) })
        p.addView(actions)

        tvScore = TextView(ui)
        tvScore.textSize = 12f
        tvScore.setTextColor(Color.parseColor("#444444"))
        tvScore.setPadding(0, dp(6), 0, 0)
        p.addView(tvScore)

        panel = p
        updateScore()
    }

    private fun showPanel() {
        val p = panel ?: return
        val size = screenSize()
        val lp = overlayParams((size.first * 0.94f).toInt(), (size.second * 0.62f).toInt())
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = dp(24)
        if (panelAttached) {
            wm.updateViewLayout(p, lp)
        } else {
            wm.addView(p, lp)
            panelAttached = true
        }
    }

    private fun hidePanel() {
        val p = panel ?: return
        if (panelAttached) {
            try {
                wm.removeView(p)
            } catch (e: Exception) {
            }
            panelAttached = false
        }
    }

    private fun setStatus(s: String) {
        tvStatus.text = s
    }

    private fun clearResults() {
        resultBox.removeAllViews()
        lastResult = null
        rated = false
    }

    private fun addLine(parent: LinearLayout, s: String, size: Float, color: String, bold: Boolean = false) {
        if (s.isBlank()) return
        val tv = TextView(ui)
        tv.text = s
        tv.textSize = size
        tv.setTextColor(Color.parseColor(color))
        if (bold) tv.setTypeface(null, Typeface.BOLD)
        tv.setPadding(0, dp(3), 0, dp(3))
        parent.addView(tv)
    }

    private fun addCard(q: JSONObject) {
        val card = LinearLayout(ui)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(10), dp(8), dp(10), dp(8))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F4F0FA"))
        bg.cornerRadius = dp(12).toFloat()
        card.background = bg
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)

        val num = q.optString("number")
        addLine(card, "Soal " + num, 12f, "#6750A4", true)
        addLine(card, q.optString("question"), 13f, "#222222")
        val opts = q.optJSONArray("options")
        if (opts != null && opts.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until opts.length()) {
                if (i > 0) sb.append("\n")
                sb.append(opts.optString(i))
            }
            addLine(card, sb.toString(), 12f, "#555555")
        }
        addLine(card, "Jawaban: " + q.optString("answer"), 15f, "#1B7F3B", true)
        addLine(card, "Penjelasan: " + q.optString("explanation"), 13f, "#222222")
        val conf = q.optInt("confidence", -1)
        if (conf >= 0) addLine(card, "Keyakinan AI: " + conf + "%", 11f, "#777777")
        resultBox.addView(card, lp)
    }

    private fun render(res: AiClient.Result) {
        if (!res.ok) {
            setStatus("Gagal: " + res.text)
            return
        }
        val obj = AiClient.parseJson(res.text)
        if (obj == null) {
            setStatus("Balasan AI tidak bisa dibaca. Teks mentah:")
            addLine(resultBox, res.text.take(1500), 12f, "#222222")
            return
        }
        lastResult = obj
        val qs = obj.optJSONArray("questions")
        if (qs == null || qs.length() == 0) {
            setStatus("Tidak ada soal terdeteksi. Ringkasan layar:")
            addLine(resultBox, obj.optString("memory_note"), 13f, "#222222")
            return
        }
        setStatus(qs.length().toString() + " soal terdeteksi")
        for (i in 0 until qs.length()) addCard(qs.getJSONObject(i))
    }

    // ---------------------------------------------------------------- Memori & skor

    private fun saveMemory() {
        val o = lastResult
        if (o == null) {
            toast("Belum ada hasil untuk disimpan")
            return
        }
        val sb = StringBuilder(o.optString("memory_note").trim())
        val qs = o.optJSONArray("questions")
        if (qs != null && qs.length() > 0) {
            sb.append("\n\nSoal & jawaban:")
            for (i in 0 until qs.length()) {
                val q = qs.getJSONObject(i)
                sb.append("\n")
                sb.append(q.optString("number")).append(". ")
                sb.append(q.optString("question")).append(" -> ")
                sb.append(q.optString("answer"))
            }
        }
        val content = sb.toString().trim()
        if (content.isEmpty()) {
            toast("Tidak ada yang bisa disimpan")
            return
        }
        val title = o.optString("memory_title").ifBlank { "Catatan layar" }
        store.add(title, content)
        toast("Tersimpan ke memori")
    }

    private fun rate(good: Boolean) {
        if (lastResult == null) {
            toast("Belum ada hasil untuk dinilai")
            return
        }
        if (rated) {
            toast("Hasil ini sudah dinilai")
            return
        }
        rated = true
        prefs.total = prefs.total + 1
        if (good) prefs.correct = prefs.correct + 1
        updateScore()
    }

    private fun updateScore() {
        val t = prefs.total
        val c = prefs.correct
        val kkm = prefs.kkm
        if (t == 0) {
            tvScore.text = "Akurasi AI: belum ada penilaian (KKM " + kkm + ")"
            return
        }
        val pct = c * 100 / t
        val status = if (pct >= kkm) "✔ mencapai KKM " + kkm else "✘ di bawah KKM " + kkm
        tvScore.text = "Akurasi AI: " + c + "/" + t + " (" + pct + "%) — " + status
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- Analisis layar

    private fun analyze() {
        if (busy) return
        if (prefs.apiKey().isBlank()) {
            toast("API key belum diisi di aplikasi")
            return
        }
        busy = true
        val size = screenSize()
        ensureDisplay(size.first, size.second)
        bubble?.visibility = View.INVISIBLE
        hidePanel()
        main.postDelayed({
            Thread {
                val b64 = try {
                    grabScreenBase64()
                } catch (e: Exception) {
                    null
                }
                main.post {
                    bubble?.visibility = View.VISIBLE
                    showPanel()
                    clearResults()
                    updateScore()
                    if (b64 == null) {
                        setStatus("Gagal menangkap layar. Coba lagi.")
                        busy = false
                    } else {
                        setStatus("AI sedang menganalisis…")
                        Thread {
                            val res = AiClient.ask(prefs, b64, store.contextText())
                            main.post {
                                render(res)
                                busy = false
                            }
                        }.start()
                    }
                }
            }.start()
        }, 450)
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                synchronized(lock) {
                    latest?.close()
                    latest = img
                }
            }
        }, capHandler)
        return r
    }

    private fun ensureDisplay(w: Int, h: Int) {
        val mp = projection ?: return
        val dpi = resources.displayMetrics.densityDpi
        val current = vd
        if (current == null) {
            val r = newReader(w, h)
            reader = r
            vd = mp.createVirtualDisplay(
                "easywin", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, null
            )
            curW = w
            curH = h
        } else if (w != curW || h != curH) {
            val old = reader
            val nr = newReader(w, h)
            current.resize(w, h, dpi)
            current.surface = nr.surface
            reader = nr
            curW = w
            curH = h
            synchronized(lock) {
                latest?.close()
                latest = null
            }
            old?.close()
        }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val wide = Bitmap.createBitmap(
            img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
        )
        wide.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createBitmap(wide, 0, 0, img.width, img.height)
    }

    private fun grabScreenBase64(): String? {
        for (attempt in 0 until 15) {
            val bmp = synchronized(lock) {
                val img = latest
                if (img != null) toBitmap(img) else null
            }
            if (bmp != null) return encode(bmp)
            Thread.sleep(100)
        }
        return null
    }

    private fun encode(src: Bitmap): String {
        val maxSide = 1600f
        val scale = minOf(1f, maxSide / maxOf(src.width, src.height).toFloat())
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src, (src.width * scale).toInt(), (src.height * scale).toInt(), true
            )
        } else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------- Pembersihan

    override fun onDestroy() {
        super.onDestroy()
        try {
            bubble?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        hidePanel()
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        try {
            vd?.release()
            reader?.close()
            projection?.stop()
        } catch (e: Exception) {
        }
        capThread?.quitSafely()
    }

    companion object {
        const val ACTION_STOP = "com.easywin.app.STOP"
        private const val CHANNEL_ID = "easywin"
    }
}
