# Easy Win

Aplikasi Android pribadi untuk menguji kemampuan AI menjawab soal dari layar.
Ikon melayang (floating window) membaca layar, AI menjawab soal beserta penjelasannya,
dan hasilnya bisa disimpan sebagai memori (materi, kisi-kisi, catatan).

## Fitur

- **Floating window**: ikon "AI" yang bisa digeser. Ketuk untuk menangkap layar dan menganalisis.
- **Hasil di panel**: pilihan jawaban, jawaban AI, penjelasan langkah demi langkah, dan tingkat keyakinan.
- **Simpan memori**: tombol di panel untuk menyimpan ringkasan layar. Memori juga bisa ditambah/dihapus manual
  di menu "Kelola memori". Memori dikirim sebagai konteks ke AI pada analisis berikutnya.
- **Akurasi vs KKM**: nilai tiap hasil dengan ✓ (AI benar) atau ✗ (AI salah); aplikasi menghitung akurasi
  dan membandingkannya dengan KKM yang kamu atur.
- **Penyedia AI**: Gemini (ada tier gratis) atau Claude. Model bisa diganti di pengaturan.

## Mode jawaban

- **Otak lokal (default)**: model AI (LiteRT-LM) berjalan penuh di HP, tanpa API dan tanpa internet.
  Teks layar dibaca dengan OCR on-device; model bervisi seperti Gemma 3n juga menerima gambar layar.
  Pasang model sekali lewat tombol "Unduh model dari link" (boleh pakai token Hugging Face) atau
  "Pilih file model" (unduh dulu `.litertlm` lewat browser). Butuh RAM besar; model kecil kurang akurat.

- **Aplikasi Gemini + Shizuku (tanpa API, default)**: screenshot diambil lewat Shizuku, dibagikan ke aplikasi
  Gemini di HP, tombol Kirim ditekan otomatis bila perlu, lalu teks jawabannya dibaca dari layar aplikasi
  Gemini dan ditampilkan di panel. Ini mengotomasi antarmuka aplikasi Gemini, jadi bisa rusak bila
  tampilannya berubah. Hanya teks yang terlihat di layar Gemini yang terbaca (jawaban sangat panjang bisa terpotong).
- **Gemini API (opsional)**: lebih stabil dan hasilnya terstruktur. Butuh API key gratis dari AI Studio.

Persyaratan mode tanpa API: aplikasi Shizuku berjalan + izin diberikan, aplikasi Gemini terpasang dan sudah login.

## Mengunduh APK

Setiap push ke `main` membangun APK lewat GitHub Actions.

1. Buka tab **Releases** di repository ini, pilih rilis **latest**, unduh `EasyWin.apk`.
   (Alternatif: tab **Actions** → run terbaru → artifact `EasyWin-debug-apk`.)
2. Instal di HP (izinkan "instal dari sumber tidak dikenal").

## Cara pakai

1. Buka aplikasi, pilih penyedia AI, isi **API key** dan simpan.
   - Gemini: https://aistudio.google.com/apikey
   - Claude: https://console.anthropic.com
2. Tekan **Izinkan tampil di atas aplikasi lain**, lalu **Mulai floating window** dan setujui izin tangkap layar.
3. Buka halaman soal, ketuk ikon **AI**. Hasil muncul di panel bawah.

## Catatan

- API key disimpan lokal di HP, tidak ada di kode.
- Aplikasi yang memblokir tangkapan layar akan tampil hitam pada hasil tangkapan; ini perilaku sistem Android.
- Rotasi layar didukung secara dasar; jika hasil tangkapan terlihat janggal, hentikan lalu mulai ulang floating window.
- Build ini adalah APK debug untuk pemakaian pribadi.

## Struktur

- `OverlayService.kt`: ikon melayang, panel hasil, tangkap layar (MediaProjection)
- `AiClient.kt`: panggilan ke Gemini / Claude (tanpa library tambahan)
- `MemoryStore.kt`: penyimpanan memori (SQLite)
- `MainActivity.kt`, `MemoryActivity.kt`: pengaturan dan pengelolaan memori
