# 🏍️ MotoCompanion (Pendamping Honda RoadSync)

**MotoCompanion** adalah aplikasi pendamping (*companion app*) Android yang dirancang khusus untuk bekerja berdampingan dengan **Honda RoadSync (HSVCS)** di satu ponsel tanpa bentrok atau kendala koneksi Bluetooth.

---

## 🚀 Fitur Utama

1. **Pemilih & Kontrol Pemutar Media Fleksibel (`MediaControlDispatcher` & `PlayerSelectorActivity`)**
   - Mendeteksi secara otomatis seluruh pemutar audio & video yang terinstal di HP Anda (baik dari **Google Play Store** maupun dari **luar Play Store / sideload APK pihak ketiga / buatan sendiri** seperti VLC, Poweramp, MX Player, Spotify, YouTube Music, YouTube ReVanced, Musicolet, dll.).
   - Dilengkapi saklar *"Tampilkan seluruh aplikasi terinstal"* untuk memastikan aplikasi kustom apapun yang Anda buat dapat dipilih.
   - **Multi-Tier Control Engine**:
     - *Tier 1:* Direct `MediaSessionManager` & `MediaController` TransportControls (kontrol resmi Android modern untuk Spotify, YouTube Music, VLC, dll.).
     - *Tier 2:* Explicit Component Targeting untuk pemutar background/dormant (lolos restriksi Android 8.0+).
     - *Tier 3:* Auto-Wake and Launch jika pemutar belum dibuka.
     - *Tier 4:* Global `AudioManager` Media Key Event & Ordered Broadcast.
   - **Tombol navigasi stang motor Honda** dan **tombol fisik intercom helm** (Play, Pause, Next, Prev) langsung diteruskan secara otomatis ke aplikasi pemutar yang sedang Anda pilih!
   - **Tampilan Info Lagu & Kontrol Langsung di Dashboard & Layar HUD**: Menampilkan judul lagu yang sedang berputar di aplikasi pihak ketiga secara *live* beserta tombol kontrol cepat (⏮ Prev, ⏯ Play/Pause, Next ⏭).

2. **Deteksi & Pembaca Panggilan WhatsApp Otomatis (`NotificationAnnouncerService`)**
   - Mendeteksi panggilan suara/video WhatsApp masuk secara *real-time*.
   - Menerapkan *Audio Ducking* (volume musik otomatis mengecil saat panggilan masuk).
   - Membacakan nama kontak penelepon langsung ke *intercom* helm Bluetooth Anda: *"Telepon WhatsApp masuk dari [Nama Kontak]"*.

3. **Mesin TTS Bahasa Indonesia Alami (`SlangNormalizer` + `TtsManager`)**
   - Mengunci bahasa ke Bahasa Indonesia murni.
   - Dilengkapi pembersih teks otomatis: menghilangkan emoji, tautan, dan menerjemahkan singkatan chat (*otw*, *dmn*, *bwt*, *yg*, *kbrin*, dll.) agar intonasi terdengar natural saat berkendara.

4. **Speedometer & Kompas GPS Stabil Anti-Pusing (`GpsStabilizer` + `HudActivity`)**
   - **Tampilan HUD Stang**: Layar mode malam (OLED Dark Mode) kontras tinggi dengan angka kecepatan ekstra besar.
   - **Tombol Kontrol Media Terintegrasi**: Pengendara dapat mengganti lagu atau jeda langsung dari layar HUD.
   - **Algoritma Filter Arah**:
     - Saat kecepatan `< 3.5 km/jam` (berhenti di lampu merah): Arah kompas otomatis dikunci (tidak berputar-putar akibat gangguan medan magnet kelistrikan motor).
     - Saat bergerak: Arah dikunci ke pergerakan vektor GPS dengan penghalusan *Circular Exponential Moving Average*.

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

## 📲 Cara Mengatur Pemutar Media di HP

1. Buka **MotoCompanion** di HP Anda.
2. Pada bagian paling atas **"PILIHAN PEMUTAR MEDIA (MUSIK & VIDEO)"**, tekan tombol **"Ubah"**.
3. Aplikasi akan menampilkan seluruh pemutar musik dan video yang terpasang di HP Anda (misal: VLC, Poweramp, MX Player, Spotify, YouTube Music, pemutar buatan sendiri, dll.).
   - *Tips:* Jika pemutar buatan sendiri tidak langsung muncul, aktifkan tombol saklar *"Tampilkan seluruh aplikasi terinstal"*.
4. Pilih aplikasi pemutar yang Anda inginkan.
5. Tekan tombol **"Uji Play / Pause"** untuk memverifikasi.
6. Sekarang, saat Anda menekan tombol di stang motor atau tombol di *intercom* helm, musik/video di aplikasi terpilih tersebut yang akan langsung merespons (Play, Pause, Next, Prev)!
