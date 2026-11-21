# 🏍️ H-Sync (Pendamping Cerdas Honda RoadSync)

**H-Sync** (sebelumnya MotoCompanion) adalah aplikasi pendamping (_companion app_) cerdas dan minimalis karya **rifqyzakaria** yang dirancang khusus untuk mendampingi **Honda RoadSync (HSVCS)** di seluruh lini motor Honda (PCX, ADV, Forza, Hornet, Transalp, CB, Rebel, dll.) tanpa bentrok koneksi Bluetooth.

Aplikasi resmi ini dipelihara dan ditandatangani langsung oleh **rifqyzakaria** dengan sistem pembaruan otomatis yang terkunci ke repositori resmi GitHub `rifqyzakaria/MotoCompanion`.

---

## 🚀 Fitur Utama

1. **Mode HUD Stang Baru & Kompas Sensor Gerak HP (`HudActivity`)**
   - Kompas kini responsif mengikuti gerak, putaran, dan orientasi fisik HP secara *real-time* dengan animasi perputaran jarum yang halus (*smooth dampening*).
   - Menggabungkan sensor orientasi HP saat berhenti/kecepatan rendah dan vektor arah GPS saat melaju kencang (> 15 km/jam) untuk akurasi maksimal.

2. **Bilah Media Player HUD yang Terlihat & Mudah Dikontrol**
   - Tampilan pemutar musik di mode HUD dirancang khusus pengendara: judul lagu dan nama artis tampil jelas, dilengkapi tombol navigasi berukuran besar yang nyaman ditekan (*tactile buttons*).

3. **Pemisahan Cerdas Android Auto (Car Mode Separation)**
   - H-Sync secara otomatis mendeteksi saat ponsel terhubung ke **Android Auto** di mobil dan langsung memasuki mode *standby/pause*, sehingga tidak akan mengganggu sistem infotainment mobil Anda. H-Sync hanya aktif saat berkendara motor Honda / RoadSync!

4. **Pemeriksaan Pembaruan Otomatis**
   - Aplikasi otomatis memeriksa ketersediaan rilis resmi terbaru di GitHub saat pertama kali dibuka.

5. **Sakelar Pemutar Musik H-Sync (H-Sync Music Bridge Toggle)**
   - **Aktif (ON)**: H-Sync bertindak sebagai pemutar Spotify virtual yang meneruskan lagu dan perintah stang dari aplikasi pihak ketiga luar (seperti **BitChord**, pemutar kustom, dll.) ke layar TFT speedometer motor Honda.
   - **Nonaktif (OFF)**: Honda RoadSync dapat langsung mendeteksi dan mengontrol pemutar musik resmi ter-whitelist (seperti **Spotify resmi, YouTube Music, Samsung Music, VLC**, dll.) tanpa bentrok.

6. **Solusi "Kesalahan Aplikasi Musik" di Layar TFT / HP**
   - Sesuai petunjuk RoadSync, cukup putar lagu minimal 1 kali di aplikasi pemutar Anda (BitChord / Spotify) sebelum membuka menu Musik di RoadSync / stang motor agar RoadSync dapat membaca metadata track secara sempurna.

7. **Deteksi & Pembaca Panggilan WhatsApp Otomatis (`NotificationAnnouncerService`)**
   - Mendeteksi panggilan suara/video WhatsApp masuk secara _real-time_.
   - Menerapkan _Audio Ducking_ (volume musik otomatis mengecil saat panggilan masuk).
   - Membacakan nama kontak penelepon langsung ke intercom helm Bluetooth.

8. **Pengaturan Bahasa & Tampilan Dinamis (`LocaleThemeManager`)**
   - **Pilihan Bahasa**: Bahasa Indonesia & English. Seluruh teks UI, dialog pembaruan, dan suara pengumuman otomatis berganti.
   - **Pilihan Mode Tampilan**: Otomatis (Ikuti Sistem HP), Mode Gelap (Dark Mode), atau Mode Terang (Light Mode).

9. **Auto-Start & Auto-Stop Cerdas**
   - Otomatis aktif saat motor terhubung ke Bluetooth dan mati bersih saat kontak motor mati.

---

## 👨‍💻 Pengembang Resmi

Dibuat & Dikelola secara resmi oleh: **[rifqyzakaria](https://github.com/rifqyzakaria)**
Repositori Resmi: [rifqyzakaria/MotoCompanion](https://github.com/rifqyzakaria/MotoCompanion)

---

## 📁 Struktur Proyek (VS Code Ready)

```text
MotoCompanion/
├── .vscode/
│   ├── tasks.json             # Task build APK & flash ADB langsung dari VS Code
│   ├── settings.json          # Konfigurasi path SDK dan Java
│   └── extensions.json        # Rekomendasi ekstensi Kotlin & Java
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml                 # Query MIME audio/*, video/*, QUERY_ALL_PACKAGES
│   │   ├── java/com/motocompanion/
│   │   │   ├── App.kt                          # Inisialisasi channel notifikasi & TTS
│   │   │   ├── gps/GpsStabilizer.kt            # Algoritma anti-peta berputar
│   │   │   ├── model/
│   │   │   │   ├── Song.kt                     # Model data musik lokal
│   │   │   │   └── InstalledPlayer.kt          # Model aplikasi pemutar terinstal
│   │   │   ├── receiver/MediaButtonReceiver.kt # Meneruskan event tombol stang ke dispatcher
│   │   │   ├── service/
│   │   │   │   ├── GpsTrackingService.kt       # Layanan foreground GPS
│   │   │   │   ├── MediaSessionService.kt      # Layanan MediaSession stang motor
│   │   │   │   └── NotificationAnnouncerService.kt # Listener notifikasi WhatsApp
│   │   │   ├── tts/
│   │   │   │   ├── SlangNormalizer.kt          # Pembersih singkatan & emoji
│   │   │   │   └── TtsManager.kt               # Kontrol TTS & Audio Ducking
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt             # Panel kontrol & info pemutar aktif
│   │   │   │   ├── PlayerSelectorActivity.kt   # Layar memilih pemutar audio/video
│   │   │   │   ├── HudActivity.kt              # Speedometer & Kompas + Tombol Media
│   │   │   │   └── MusicActivity.kt            # Pemutar lagu offline mandiri
│   │   │   └── util/
│   │   │       └── MediaControlDispatcher.kt   # Engine routing kontrol tombol ke app terpilih
│   │   └── res/
│   │       ├── drawable/                       # Icon kompas, motor, card rounded
│   │       ├── layout/                         # Desain XML responsif
│   │       └── values/                         # Warna neon cyberpunk & teks bahasa Indonesia
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew                                     # Skrip eksekusi Gradle
```

---

## 🛠️ Cara Membangun APK di VS Code

1. Buka folder `MotoCompanion` di VS Code.
2. Buka Terminal (`Ctrl + ~`) atau tekan `Ctrl + Shift + B` untuk menjalankan task bawaan:
   ```bash
   ./gradlew assembleDebug
   ```
3. File APK hasil kompilasi akan berada di:
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```
   (Atau file [`MotoCompanion.apk`](MotoCompanion.apk) di root proyek).

---

## 🏍️ Cara Menghubungkan ke Honda RoadSync di Honda PCX

Agar tombol stang motor Honda PCX Anda dapat mengontrol pemutar musik (Spotify, YouTube Music, VLC, dll.) dan memunculkan judul lagu di speedometer:

1. **Buka Aplikasi Honda RoadSync** di ponsel Anda.
2. Masuk ke menu **Pengaturan Lanjutan (Settings) ➔ Sumber Musik (Music Source)**.
3. Pilih **MotoCompanion** sebagai sumber musik utama!
4. Di dalam aplikasi **MotoCompanion**, pilih aplikasi pemutar musik yang ingin Anda dengarkan (misal: Spotify, YouTube Music, VLC, Poweramp, dll.).
5. **Mulai Berkendara**:
   - Saat Honda PCX menyala dan Bluetooth terhubung, MotoCompanion otomatis aktif mendampingi di latar belakang.
   - Di saklar stang kiri motor Honda PCX Anda, masuk ke menu **Music**.
   - Tekan tombol **Play / Pause / Next / Prev** di stang. Perintah langsung diteruskan ke Spotify/YouTube Music Anda, dan judul lagu akan tampil di layar speedometer Honda PCX!

---

## 📲 Cara Mengatur Pemutar Media di HP

1. Buka **MotoCompanion** di HP Anda.
2. Pada bagian paling atas **"PILIHAN PEMUTAR MEDIA (MUSIK & VIDEO)"**, tekan tombol **"Ubah"**.
3. Aplikasi akan menampilkan seluruh pemutar musik dan video yang terpasang di HP Anda (misal: VLC, Poweramp, MX Player, Spotify, YouTube Music, pemutar buatan sendiri, dll.).
   - _Tips:_ Jika pemutar buatan sendiri tidak langsung muncul, aktifkan tombol saklar _"Tampilkan seluruh aplikasi terinstal"_.
4. Pilih aplikasi pemutar yang Anda inginkan.
5. Tekan tombol **"Uji Play / Pause"** untuk memverifikasi.
6. Sekarang, saat Anda menekan tombol di stang motor atau tombol di _intercom_ helm, musik/video di aplikasi terpilih tersebut yang akan langsung merespons (Play, Pause, Next, Prev)!
