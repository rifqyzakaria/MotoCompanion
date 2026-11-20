# 🏍️ H-Sync (Pendamping Cerdas Honda RoadSync)

**H-Sync** (sebelumnya MotoCompanion) adalah aplikasi pendamping (_companion app_) Android yang dirancang khusus untuk bekerja berdampingan dengan **Honda RoadSync (HSVCS)** di seluruh lini motor Honda yang mendukung konektivitas RoadSync (PCX, ADV, Forza, Hornet, Transalp, CB, Rebel, dll.) tanpa bentrok atau kendala koneksi Bluetooth.

---

## 🚀 Fitur Utama

1. **Pemilih & Kontrol Pemutar Media Fleksibel (`MediaControlDispatcher` & `PlayerSelectorActivity`)**
   - Mendeteksi secara otomatis seluruh pemutar audio & video yang terinstal di HP Anda (baik dari **Google Play Store** maupun dari **luar Play Store / sideload APK pihak ketiga / buatan sendiri** seperti VLC, Poweramp, MX Player, Spotify, YouTube Music, YouTube ReVanced, Musicolet, dll.).
   - Dilengkapi saklar _"Tampilkan seluruh aplikasi terinstal"_ untuk memastikan aplikasi kustom apapun yang Anda buat dapat dipilih.
   - **Multi-Tier Control Engine**:
     - _Tier 1:_ Direct `MediaSessionManager` & `MediaController` TransportControls (kontrol resmi Android modern untuk Spotify, YouTube Music, VLC, dll.).
     - _Tier 2:_ Explicit Component Targeting untuk pemutar background/dormant (lolos restriksi Android 8.0+).
     - _Tier 3:_ Auto-Wake and Launch jika pemutar belum dibuka.
     - _Tier 4:_ Global `AudioManager` Media Key Event & Ordered Broadcast.
   - **Tombol navigasi stang motor Honda** dan **tombol fisik intercom helm** (Play, Pause, Next, Prev) langsung diteruskan secara otomatis ke aplikasi pemutar yang sedang Anda pilih!
   - **Tampilan Info Lagu & Kontrol Langsung di Dashboard & Layar HUD**: Menampilkan judul lagu yang sedang berputar di aplikasi pihak ketiga secara _live_ beserta tombol kontrol cepat (⏮ Prev, ⏯ Play/Pause, Next ⏭).

2. **Deteksi & Pembaca Panggilan WhatsApp Otomatis (`NotificationAnnouncerService`)**
   - Mendeteksi panggilan suara/video WhatsApp masuk secara _real-time_.
   - Menerapkan _Audio Ducking_ (volume musik otomatis mengecil saat panggilan masuk).
   - Membacakan nama kontak penelepon langsung ke _intercom_ helm Bluetooth Anda: _"Telepon WhatsApp masuk dari [Nama Kontak]"_.

3. **Mesin TTS Bahasa Indonesia Alami (`SlangNormalizer` + `TtsManager`)**
   - Mengunci bahasa ke Bahasa Indonesia murni.
   - Dilengkapi pembersih teks otomatis: menghilangkan emoji, tautan, dan menerjemahkan singkatan chat (_otw_, _dmn_, _bwt_, _yg_, _kbrin_, dll.) agar intonasi terdengar natural saat berkendara.

4. **Speedometer & Kompas GPS Stabil Anti-Pusing (`GpsStabilizer` + `HudActivity`)**
   - **Tampilan HUD Stang**: Layar mode malam (OLED Dark Mode) kontras tinggi dengan angka kecepatan ekstra besar.
   - **Tombol Kontrol Media Terintegrasi**: Pengendara dapat mengganti lagu atau jeda langsung dari layar HUD.
   - **Algoritma Filter Arah**:
     - Saat kecepatan `< 3.5 km/jam` (berhenti di lampu merah): Arah kompas otomatis dikunci (tidak berputar-putar akibat gangguan medan magnet kelistrikan motor).
     - Saat bergerak: Arah dikunci ke pergerakan vektor GPS dengan penghalusan _Circular Exponential Moving Average_.

5. **Pengaturan Bahasa & Tampilan Dinamis (`LocaleThemeManager`)**
   - **Dukungan Dua Bahasa**: Pilihan instan antara **Bahasa Indonesia** dan **English**, atau otomatis mengikuti bahasa sistem HP. Seluruh antarmuka, dialog, panduan, dan suara pengumuman TTS otomatis berganti bahasa.
   - **Pilihan Mode Tampilan Fleksibel**:
     - **Otomatis (Ikuti Sistem / Device)**: Menyesuaikan secara otomatis dengan tema perangkat Anda.
     - **Mode Gelap (Dark Mode)**: Tampilan OLED kontras tinggi yang ramah mata dan hemat daya.
     - **Mode Terang (Light Mode)**: Tampilan bersih dengan visibilitas optimal di bawah terik sinar matahari siang hari.

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
