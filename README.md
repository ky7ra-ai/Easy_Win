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
