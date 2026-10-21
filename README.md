# LAN Control 5

Kontrol 1 komputer utama + beberapa komputer klien Windows melalui LAN atau Tailscale:
layar, mouse/keyboard, kontrol serentak, transfer file, clipboard, restart/shutdown.
Koneksi terenkripsi AES-GCM dengan kode koneksi minimal 12 karakter.

## Unduh
1. Buka folder [`rilis`](rilis) dan unduh **`LAN-Control-5-<versi>.zip`** terbaru
   (klik file-nya, lalu tombol **Download raw file** / ikon unduh di kanan atas).
2. Ekstrak ke folder tetap, misalnya `C:\LAN-Control-5`. Butuh Java 17 atau lebih baru.
3. Komputer utama: `Jalankan-Utama.bat` — komputer klien: `Jalankan-Klien.bat`
   (atau `Jalankan-Klien-Admin.bat`). Satu JAR untuk keduanya; file `.bat` menentukan mode.

Baca `MULAI-DI-SINI.txt` di dalam ZIP. Panduan lengkap: [PANDUAN.txt](PANDUAN.txt).

File lain di `rilis/` (`.jar`, `.jar.sig`, `versi-terbaru.txt`) dipakai oleh pembaruan otomatis.

## Pembaruan otomatis
Mulai v1.6.0 komputer utama memeriksa folder `rilis/` di repository ini, memverifikasi tanda tangan
Ed25519 setiap JAR, memasangnya saat tidak dipakai, lalu meneruskannya ke klien.
Hanya JAR yang ditandatangani kunci rilis pemilik yang diterima.

## Build dari sumber
`java Build.java` lalu `java -jar LAN-Control-5.jar --test`.
Cara menerbitkan versi: [CARA-RILIS.txt](CARA-RILIS.txt).
