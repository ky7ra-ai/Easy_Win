package com.easywin.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Layar untuk melihat, menambah, dan menghapus memori (kisi-kisi, materi, catatan). */
class MemoryActivity : Activity() {

    private lateinit var store: MemoryStore
    private lateinit var listBox: LinearLayout
    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MemoryStore(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(28), dp(20), dp(28))
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this)
        title.text = "Memori"
        title.textSize = 24f
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        val hint = TextView(this)
        hint.text = "Semua memori dikirim sebagai konteks ke AI setiap kali menjawab soal " +
            "(memori terbaru diprioritaskan, maksimal sekitar 8000 karakter)."
        hint.textSize = 12f
        hint.setPadding(0, dp(6), 0, dp(12))
        root.addView(hint)

        etTitle = EditText(this)
        etTitle.hint = "Judul (mis. Kisi-kisi Biologi Bab 3)"
        etTitle.setSingleLine(true)
        root.addView(etTitle)

        etContent = EditText(this)
        etContent.hint = "Isi materi / kisi-kisi / catatan"
        etContent.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        etContent.minLines = 4
        etContent.gravity = android.view.Gravity.TOP
        root.addView(etContent)

        val add = Button(this)
        add.text = "Tambah memori"
        add.isAllCaps = false
        add.setOnClickListener {
            val t = etTitle.text.toString().trim()
            val c = etContent.text.toString().trim()
            if (c.isEmpty()) {
                Toast.makeText(this, "Isi memori tidak boleh kosong", Toast.LENGTH_SHORT).show()
            } else {
                store.add(t.ifEmpty { "Catatan" }, c)
                etTitle.setText("")
                etContent.setText("")
                refresh()
            }
        }
        root.addView(add)

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        root.addView(listBox)
        refresh()
    }

    private fun refresh() {
        listBox.removeAllViews()
        val items = store.all()
        if (items.isEmpty()) {
            val empty = TextView(this)
            empty.text = "Belum ada memori. Simpan dari floating window atau tambah manual di atas."
            empty.setPadding(0, dp(16), 0, 0)
            listBox.addView(empty)
            return
        }
        val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
        for (m in items) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(12), dp(10), dp(12), dp(10))
            val bg = GradientDrawable()
            bg.setColor(Color.parseColor("#F4F0FA"))
            bg.cornerRadius = dp(12).toFloat()
            card.background = bg

            val t = TextView(this)
            t.text = m.title
            t.textSize = 15f
            t.setTypeface(null, Typeface.BOLD)
            card.addView(t)

            val d = TextView(this)
            d.text = fmt.format(Date(m.createdAt))
            d.textSize = 11f
            card.addView(d)

            val c = TextView(this)
            c.text = if (m.content.length > 400) m.content.take(400) + "…" else m.content
            c.textSize = 13f
            c.setPadding(0, dp(6), 0, dp(6))
            card.addView(c)

            val del = Button(this)
            del.text = "Hapus"
            del.isAllCaps = false
            del.setOnClickListener {
                store.delete(m.id)
                refresh()
            }
            card.addView(del)

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(12)
            listBox.addView(card, lp)
        }
    }
}
