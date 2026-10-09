# Game Nuke — Catatan perbaikan portal

Tanggal: 9 Oktober 2026. Sumber: ZIP `gamenukeweb.zip` yang Anda berikan.

## Perubahan

Revisi kedua memakai arah visual baru: navy gelap, panel dashboard, aksen hijau Game Nuke, dan detail biru. Tampilan dirancang dengan mobile sebagai prioritas. Ilustrasi HP di `index.html` memakai proporsi 9:19,5 dengan tinggi eksplisit, sehingga lebih panjang daripada pratinjau sebelumnya. Dashboard di dalamnya memuat ring FPS, tiga metrik, grafik riwayat sesi, empat modul, dan navigasi bawah. Angka pada ilustrasi tetap diberi label contoh. Ukuran interior mengikuti lebar frame, tanpa rotasi yang melewati panel. Header, menu, kartu fitur/VIP, pilihan edisi, FAQ, footer, halaman informasi, dan dua halaman download menggunakan aturan responsif yang konsisten. Ukuran tombol close/menu minimal 42–44 px, indikator fokus terlihat, teks panjang dapat membungkus, dan tabel metadata memakai judul baris yang semantik. Layout tidak menyembunyikan overflow horizontal seluruh halaman untuk menutupi kesalahan ukuran.

Tombol download tampil sebelum chip fitur pendukung. Panel VIP lebih ringkas di HP: rincian fitur dibuka melalui elemen `details` native, tanpa tambahan JavaScript. Seluruh 24 butir fitur, empat harga, dan 13 pemicu download tetap tersedia. Teks heading Thermal Guardian yang salah pada revisi sebelumnya juga diperbaiki. `landing.css` dimuat hanya oleh halaman utama; halaman informasi dan portal download cukup memuat CSS bersama yang lebih kecil.

Menu mobile memisahkan fokus dari konten di belakangnya, mendukung Tab dan Escape, serta mengembalikan fokus saat ditutup. Pilihan bahasa tersedia di mobile. Konten tetap terlihat bila script animasi gagal. Preferensi reduced motion mematikan gerakan dekoratif.

SEO/GEO diperbaiki melalui judul dan deskripsi yang jelas, canonical, breadcrumb, JSON-LD yang sesuai konten, informasi versi/ukuran yang konsisten, tautan panduan, serta konten instalasi, izin, troubleshooting, dan batas performa. Hreflang yang menunjuk URL sama tanpa halaman terjemahan terpisah dan koordinat Jakarta yang tidak didukung konten dihapus. Halaman pengalihan diberi noindex dan dikeluarkan dari sitemap. Metadata aplikasi dalam JSON-LD mengikuti metadata rilis yang dibaca frontend. `llms.txt` tetap tersedia sebagai indeks tambahan, tanpa klaim menjamin tampil di mesin AI.

Klaim seperti “zero latency”, “zero GPU overhead”, dan jaminan menghilangkan thermal throttling diganti dengan penjelasan alat serta batas perangkat. Angka FPS/temperatur/ping pada pratinjau diberi keterangan bahwa angka tersebut adalah contoh. Hash ditampilkan sebagai checksum metadata pengembang; situs tidak mengklaim sudah memindai atau memverifikasi APK pengguna.

Keterangan sponsor terlihat sebelum melanjutkan unduhan. Panduan dan halaman privasi menjelaskan tab baru, perpindahan tab lama ke pengiklan, serta bahwa pembelian di sponsor tidak diperlukan. Halaman final menyediakan bantuan, privasi, ketentuan, dan keterangan persiapan unduhan yang tidak mengaku sebagai progres transfer sebenarnya. Tombol salin hash memberikan pesan manual bila API clipboard tidak tersedia.

## Performa sumber

| Bagian | Sumber awal | Revisi mobile |
| --- | ---: | ---: |
| CSS halaman utama | 95.875 byte | 60,187 byte (`style.css` + `landing.css`) |
| CSS halaman informasi/download | 95.875 byte | 23,033 byte |
| CSS homepage dengan gzip lokal | 16.582 byte | 12,877 byte (dua berkas terpisah) |
| Permintaan Google Fonts dari halaman publik | Link font dan CSS import | 0 |
| Partikel dekoratif | Hingga 50 partikel di hero | Maksimal 26 di desktop yang memenuhi syarat |
| Partikel di HP/reduced motion/Save Data/perangkat berdaya rendah | Sebagian masih aktif | Tidak dirender |

Ukuran CSS homepage berkurang 37.2% dibanding sumber awal. CSS untuk halaman informasi/download berkurang 76.0%. Partikel berhenti pada tab tersembunyi atau kanvas di luar area pandang. Logo di bawah halaman memakai lazy loading. Cache versi stylesheet diperbarui. Tidak ada font, library, atau JavaScript eksternal baru untuk desain ini.

Angka di atas adalah ukuran sumber dan hasil kompresi lokal, bukan skor PageSpeed. LCP, INP, CLS, TTFB, dan skor Lighthouse tetap perlu diukur di hosting asli karena dipengaruhi server, cache, jaringan, browser, dan iklan pihak ketiga.

## Fungsi yang dipertahankan

- URL directlink, urutan dua tahap, pembukaan tab baru, dan fallback ke tab sekarang bila pop-up diblokir.
- Fungsi pembentukan sesi download, durasi sesi 30 menit, guard halaman final/legacy, dan pemicu unduhan APK.
- `download.js` sepenuhnya sama dengan sumber.
- Nilai konfigurasi `site-config.js` sama. Hanya komentar tentang AdSense diperjelas agar tidak menyarankan perilaku berbeda khusus ketika review.
- `admin.html`, `api_config.json`, `version.json`, `booster_features.json`, CNAME, gambar asli, dan README asli.
- Harga VIP: Rp 20.000 / 7 hari, Rp 50.000 / 30 hari, Rp 180.000 / 180 hari, Rp 290.000 / 365 hari.

## Pemeriksaan yang telah dijalankan

- 25 skenario regresi lokal dengan Node VM: kedua tahap sponsor, mode konfigurasi yang tersedia, pop-up berhasil/diblokir/melempar error, double click, penyimpanan browser yang diblokir, sesi valid/kedaluwarsa, sinkronisasi metadata/schema, keyboard menu, serta pembatasan renderer partikel.
- Parser HTML untuk 10 halaman publik: tidak ada ID duplikat, target ARIA hilang, tautan lokal rusak, atau anchor yang hilang. Sebanyak 317 referensi internal diperiksa.
- Tujuh blok JSON-LD dan sitemap XML valid secara sintaks.
- Sintaks JavaScript file frontend dan tiga script inline valid.
- Pemeriksaan ukuran frame secara matematis pada lebar viewport 280, 320, 360, 390, 414, 640, 768, 820, 1024, dan 1440 px; ini adalah pemeriksaan batas ukuran dari CSS, bukan render browser. Pada viewport 390 px, frame sekitar 314 × 680 px.
- File JavaScript, konfigurasi, admin, metadata, sitemap, robots, dan aset asli tidak berubah dari revisi pertama. Selain homepage, perubahan HTML revisi kedua hanya warna tema browser dan versi cache stylesheet.
- Harga serta file konfigurasi/backend/admin yang tercantum di atas dibandingkan dengan sumber. Logika fungsi directlink dan sesi utama identik setelah normalisasi line ending.

Pemeriksaan visual browser, render pada berbagai viewport, dan uji perangkat Android nyata belum dapat dijalankan di lingkungan pengerjaan. Karena itu, hasil ini belum membuktikan bahwa setiap browser/OEM bebas masalah layout. Tidak ada permintaan ke iklan sponsor atau pengujian pembayaran produksi selama regresi lokal.

## Batas terkait AdSense

Paket sumber tidak memiliki kode AdSense/publisher ID atau CMP. Perbaikan ini meningkatkan kejelasan konten, navigasi, disclosure, dan informasi privasi; bukan konfirmasi bahwa situs sudah lolos AdSense.

Alur yang Anda minta tetap dipertahankan: halaman berikut dibuka di tab baru, lalu tab lama dapat beralih ke sponsor. Apabila perilaku ini dianggap pop-under, mengalihkan ke tujuan tidak diinginkan, atau mengganggu navigasi, perbaikan elemen/konten saja tidak cukup. Google melarang AdSense pada situs yang memicu pop-under. Konten, tracking, dan perilaku tujuan sponsor juga memerlukan pemeriksaan operator. Jangan mengaktifkan konfigurasi berbeda hanya saat review dan mengembalikannya setelah diterima.

Jika kelak memasang AdSense, gunakan publisher ID dan konfigurasi consent yang benar untuk situs Anda. Jangan menampilkan unit Google di halaman pengalihan otomatis atau menempatkannya sehingga menyerupai tombol download. Data ID iklan atau deklarasi `ads.txt` tidak dibuat-buat dalam paket ini.

Referensi:

- [Google AdSense — Ad placement policies](https://support.google.com/adsense/answer/1346295?hl=en)
- [Google AdSense — Program policies](https://support.google.com/adsense/answer/48182/adsense-programme-policies)
- [Google Search — AI features and your website](https://developers.google.com/search/docs/appearance/ai-features)

## Saat memasang

1. Ekstrak ZIP dan gunakan file web hasil perbaikan. Sertakan `landing.css` baru serta `style.css` terbaru saat mengunggah; jangan hanya mengganti `index.html`. Untuk melihat halaman utama, buka `index.html` melalui server HTTP/HTTPS.
2. Pertahankan APK yang sudah ada di hosting. ZIP sumber dan ZIP hasil ini tidak menyertakan `GameNuke-v3.7.0-Triton.apk`; halaman final masih mengikuti `localApkUrl` asli. Pastikan path tersebut tersedia di hosting agar unduhan tidak 404.
3. Setelah pemasangan, uji tombol download pada browser target dan jalankan PageSpeed Insights di domain asli. Periksa viewport 320, 390, 768, 1024, dan 1440 px, termasuk menu, FAQ, kartu VIP, serta tabel checksum.

Temuan penting dari sumber: `version.json` memuat `nvidia_nim_api_key` dalam berkas metadata publik. Nilainya dipertahankan agar konsumen metadata/aplikasi tidak berubah diam-diam. Rotasi key yang sudah terbuka dan pindahkan penggunaan credential ke backend/proxy yang sesuai sebelum publikasi ulang; jangan mengirim nilai key ke pihak lain.
