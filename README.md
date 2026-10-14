# Aplikasi Ujian SMPN 4 Tanjung Medan (Android)

Aplikasi Android berbasis WebView untuk ujian online sekolah. Aplikasi ini
membuka sistem ujian (Hub Apps Script + halaman ujian di Vercel) dalam mode
terkunci agar siswa tidak bisa berbuat curang.

## Fitur
- Layar tidak bisa di-screenshot atau direkam (FLAG_SECURE)
- Mode layar penuh dan tombol Back dinonaktifkan
- Layar disematkan (screen pinning) selama ujian berlangsung
- Deteksi pelanggaran: menekan Home, menarik bar notifikasi, pindah aplikasi,
  melepas sematan layar. Dilaporkan ke sistem ujian (3 pelanggaran = Curang)
- Hanya domain yang diizinkan yang bisa dibuka (script.google.com,
  googleusercontent.com, vercel.app)
- Mendukung kamera dan unggah foto untuk jawaban Esai

## Konfigurasi
Edit `app/src/main/java/id/sch/smpn4/ujian/MainActivity.kt`:

| Konstanta | Keterangan |
|-----------|------------|
| `HUB_URL` | URL web app Hub (Apps Script) |
| `NPSN`    | NPSN sekolah yang membuka aplikasi |

Halaman ujian di Vercel (`pages/exam.js`) harus memiliki hook
`window.onNativeViolation` dan pemanggilan `window.AndroidApp.lock()/unlock()`.

## Cara build APK
Build berjalan otomatis lewat GitHub Actions setiap ada push ke branch `main`.

1. Buka tab **Actions** dan pilih run terbaru yang berstatus hijau
2. Di bagian **Artifacts**, unduh `ujian-apk`
3. Ekstrak zip-nya untuk mendapat `app-debug.apk`

Build manual: **Actions → Build APK → Run workflow**.

## Cara pasang di HP siswa
1. Kirim `app-debug.apk` ke HP siswa (Drive / WhatsApp)
2. Izinkan **Install dari sumber tidak dikenal**
3. Buka aplikasi. Saat ujian dimulai, pilih **Mulai** pada dialog
   "Sematkan aplikasi"

Jika tidak muncul dialog, aktifkan di **Setelan → Keamanan → Sematkan layar**.

## Catatan
- APK debug memakai tanda tangan yang berbeda di tiap build, jadi untuk
  memperbarui aplikasi siswa harus uninstall dulu lalu install ulang
- Persyaratan: Android 7.0 (API 24) ke atas
