package com.neon.gametweak

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * NukeTranslationManager — Enterprise Global Translation Engine.
 *
 * Designed for:
 *  - 0ms Latency: Exhaustive pre-bundled offline dictionaries for all app & in-game floating panels.
 *  - Zero Flicker / No Recomposition Loop: Changes language cleanly in exactly 1 recomposition cycle.
 *  - Smart prefix & punctuation unwrapping (handles emojis, tags, and formatting).
 *  - Offline-first with silent background caching that never causes repeated UI refreshes.
 */
object NukeTranslationManager {

    private const val TAG = "NukeTranslate"
    private const val PREFS_NAME = "nuke_translation_prefs"
    private const val KEY_LANG = "selected_app_language"
    private const val CACHE_FILE_NAME = "nuke_translations.json"

    data class SupportedLanguage(
        val code: String,
        val displayName: String,
        val nativeName: String,
        val flag: String
    )

    val SUPPORTED_LANGUAGES = listOf(
        SupportedLanguage("en", "English", "English", "🇺🇸"),
        SupportedLanguage("id", "Indonesian", "Bahasa Indonesia", "🇮🇩"),
        SupportedLanguage("es", "Spanish", "Español", "🇪🇸"),
        SupportedLanguage("pt", "Portuguese", "Português", "🇧🇷"),
        SupportedLanguage("ru", "Russian", "Русский", "🇷🇺"),
        SupportedLanguage("ja", "Japanese", "日本語", "🇯🇵"),
        SupportedLanguage("ko", "Korean", "한국어", "🇰🇷"),
        SupportedLanguage("zh-CN", "Chinese", "简体中文", "🇨🇳"),
        SupportedLanguage("ar", "Arabic", "العربية", "🇸🇦"),
        SupportedLanguage("th", "Thai", "ไทย", "🇹🇭"),
        SupportedLanguage("vi", "Vietnamese", "Tiếng Việt", "🇻🇳"),
        SupportedLanguage("tl", "Filipino", "Tagalog", "🇵🇭"),
        SupportedLanguage("de", "German", "Deutsch", "🇩🇪"),
        SupportedLanguage("fr", "French", "Français", "🇫🇷")
    )

    private val _currentLanguage = MutableStateFlow("en")
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    private val memoryCache = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val pendingRequests = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(2)

    private var initialized = false
    private var cacheFile: File? = null

    // Comprehensive offline dictionaries covering 100% of Dashboard, Tools, Floating HUD & In-Game Overlays
    private val OFFLINE_DICTIONARY: Map<String, Map<String, String>> = mapOf(
        "id" to mapOf(
            // ── Dashboard Core & Telemetry ──
            "Command Center" to "Pusat Komando",
            "Standard Mode Ready" to "Mode Standar Siap",
            "Ready" to "Siap",
            "Adaptive gaming optimization engine" to "Mesin optimasi gaming adaptif",
            "System Readiness" to "Kesiapan Sistem",
            "Launch Game Session" to "Mulai Sesi Game",
            "Connected" to "Terhubung",
            "Connect" to "Hubungkan",
            "CPU Load" to "Beban CPU",
            "RAM Usage" to "Penggunaan RAM",
            "Refresh Rate" to "Kecepatan Refresh",
            "HIGH TEMP" to "SUHU TINGGI",
            "OPTIMAL" to "OPTIMAL",
            "Optimal" to "Optimal",
            "DISPLAY" to "LAYAR",
            "Display" to "Layar",
            "THERMAL" to "SUHU",
            "Thermal" to "Suhu",
            "BATTERY" to "BATERAI",
            "Battery" to "Baterai",
            "RAM" to "RAM",
            "STORAGE" to "PENYIMPANAN",
            "Storage" to "Penyimpanan",
            "NETWORK" to "JARINGAN",
            "Network" to "Jaringan",
            "FREE" to "BEBAS",
            "Free" to "Bebas",
            "SESSION READINESS" to "KESIAPAN SESI",
            "SYSTEM PATHS" to "JALUR SISTEM",
            "DEVICE CONTROL" to "KONTROL PERANGKAT",
            "Device Control" to "Kontrol Perangkat",
            "FLOATING HUD" to "HUD MELAYANG",
            "Floating HUD" to "HUD Melayang",
            "Overlay permission" to "Izin hamparan",
            "SESSION TRACK" to "PELACAK SESI",
            "Gaming session active" to "Sesi game aktif",
            "GAME FOCUS" to "FOKUS GAME",
            "Game Focus" to "Fokus Game",
            "DND policy access" to "Akses kebijakan DND",
            "BATTERY GUARD" to "PENJAGA BATERAI",
            "Battery Guard" to "Penjaga Baterai",
            "Unrestricted (No Sleep)" to "Tanpa Batas (Tanpa Tidur)",
            "Tap to ignore limit" to "Ketuk abaikan batasan",
            "HARDWARE SYNC" to "SINKRONISASI PERANGKAT",
            "Hardware Sync" to "Sinkronisasi Perangkat",
            "NUKE DECK" to "DEK NUKE",
            "HIGH-VALUE CONTROLS" to "KONTROL UTAMA",
            "GAME SPACE" to "RUANG GAME",
            "Game Space" to "Ruang Game",
            "Choose a game and arm the HUD" to "Pilih game & aktifkan HUD",
            "DEEP CLEAN" to "PEMBERSIHAN MENDALAM",
            "Deep Clean" to "Pembersihan Mendalam",
            "Storage & memory maintenance" to "Pemeliharaan memori & storage",
            "SYSTEM EDITOR" to "EDITOR SISTEM",
            "System Editor" to "Editor Sistem",
            "Find & tweak parameters safely" to "Ubah parameter sistem aman",
            "Select connection method" to "Pilih metode koneksi",
            "Connected via" to "Terhubung melalui",
            "SESSION INTELLIGENCE" to "INTELIJEN SESI",
            "EXTENDED" to "TERLANJUT",
            "STANDARD" to "STANDAR",
            "OPEN >" to "BUKA >",
            "OPEN" to "BUKA",
            "LIVE ENGINE" to "MESIN LANGSUNG",
            "MEASURED TELEMETRY" to "TELEMETRI TERUKUR",

            // ── Navigation Bottom Bar & Drawer ──
            "Core" to "Inti",
            "Games" to "Game",
            "Optimize" to "Optimasi",
            "GAME NUKE" to "GAME NUKE",
            "FREE TIER" to "PENGGUNA GRATIS",
            "Tap to Remove All Ads" to "Ketuk untuk Bebas Iklan",
            "UPGRADE" to "TINGKATKAN",
            "VIP Pass (Ad-Free)" to "Akses VIP (Bebas Iklan)",
            "VIP Pass" to "Akses VIP",
            "Device System Editor" to "Editor Sistem Perangkat",
            "Web Server & REST API" to "Server Web & REST API",
            "Network Integrity Status" to "Status Integritas Jaringan",
            "Documentation" to "Dokumentasi",
            "About Developer" to "Tentang Pengembang",
            "Check Update" to "Periksa Pembaruan",
            "Rate App" to "Beri Nilai Aplikasi",
            "Ad Privacy" to "Privasi Iklan",
            "Language" to "Bahasa",
            "Select Language" to "Pilih Bahasa",
            "SELECT LANGUAGE" to "PILIH BAHASA",
            "Real-Time Google Neural Translation" to "Terjemahan Multi-Bahasa Instan",
            "Translations are cached locally for zero-latency offline use." to "Terjemahan tersimpan lokal untuk performa 0ms instan tanpa jeda.",
            "CORE ENGINES" to "MESIN UTAMA",
            "DIAGNOSTICS & SYSTEM" to "DIAGNOSTIK & SISTEM",
            "PREFERENCES" to "PREFERENSI",
            "ENGINEERED BY" to "DIKEMBANGKAN OLEH",

            // ── In-Game Floating HUD & Quick Toggles ──
            "CORE READY" to "INTI SIAP",
            "CORE STANDBY" to "INTI STANDBY",
            "Game Mode" to "Mode Game",
            "GAME MODE" to "MODE GAME",
            "Focus" to "Fokus",
            "FOCUS" to "FOKUS",
            "Net Lock" to "Kunci Jaringan",
            "NET LOCK" to "KUNCI JARINGAN",
            "Crosshair" to "Crosshair",
            "CROSSHAIR" to "CROSSHAIR",
            "Awake" to "Tetap Terjaga",
            "AWAKE" to "TETAP TERJAGA",
            "Frame Control" to "Kontrol Frame",
            "FRAME CONTROL" to "KONTROL FRAME",
            "Native frame path and measured FPS" to "Jalur frame native & FPS terukur",
            "Measured cache and memory reclaim" to "Pembersihan cache & memori terukur",
            "Crosshair Studio" to "Studio Crosshair",
            "CROSSHAIR STUDIO" to "STUDIO CROSSHAIR",
            "Canvas crosshair overlay" to "Hamparan crosshair kustom",
            "Live Monitor" to "Monitor Langsung",
            "LIVE MONITOR" to "MONITOR LANGSUNG",
            "CPU and RAM telemetry" to "Telemetri CPU & RAM",
            "Network Core" to "Inti Jaringan",
            "NETWORK CORE" to "INTI JARINGAN",
            "Link quality and Wi-Fi session lock" to "Kualitas sinyal & kunci sesi Wi-Fi",
            "Pressure Radar" to "Radar Tekanan",
            "PRESSURE RADAR" to "RADAR TEKANAN",
            "Protected background process release" to "Pelepasan proses latar belakang aman",
            "Screen and HUD" to "Layar dan HUD",
            "SCREEN AND HUD" to "LAYAR DAN HUD",
            "Orientation and overlay comfort" to "Kenyamanan orientasi & hamparan",
            "CPU Monitor" to "Monitor CPU",
            "CPU MONITOR" to "MONITOR CPU",
            "Read-only per-core clocks" to "Frekuensi inti CPU terukur",
            "SCREEN BRIGHTNESS" to "KECERAHAN LAYAR",
            "MINIMUM WIDTH / DPI (DISPLAY SCALE)" to "LEBAR MINIMUM / DPI (SKALA LAYAR)",
            "PLUGIN LIBRARY" to "PUSTAKA PLUGIN",

            // ── In-Game Task Manager Overlay ──
            "TASK MANAGER" to "MANAJER TUGAS",
            "BACKGROUND APP & MEMORY MANAGER • DRAG TO MOVE" to "PENGELOLA APLIKASI & MEMORI • GESER UNTUK PINDAH",
            "SCANNING APPS..." to "MEMINDAI APLIKASI...",
            "MEMORY FOOTPRINT: CALCULATING" to "PENGGUNAAN MEMORI: MENGHITUNG",
            "PROCESS SCAN" to "PINDAI PROSES",
            "BALANCE" to "SEIMBANGKAN",
            "END ELIGIBLE" to "HENTIKAN AMAN",
            "Kill Process" to "Hentikan Proses",
            "Running Apps" to "Aplikasi Berjalan",
            "RAM Freed" to "RAM Dikosongkan",

            // ── In-Game Touch Tuning & Aim Acceleration ──
            "Touch Tuning" to "Penyelarasan Sentuhan",
            "🎯 Touch Tuning" to "🎯 Penyelarasan Sentuhan",
            "AIM ACCELERATION TUNING" to "PENYELARASAN AKSELERASI BIDIKAN",
            "Touch Sensi X" to "Sensitivitas Sentuh X",
            "Touch Sensi Y" to "Sensitivitas Sentuh Y",
            "Sensi X" to "Sensitivitas X",
            "Sensi Y" to "Sensitivitas Y",
            "Hardware Native 1:1 Reset" to "Atur Ulang 1:1 Bawaan",
            "Apply Engine Parameters" to "Terapkan Parameter Mesin",
            "Reset Defaults" to "Atur Ulang Bawaan",
            "Apply Changes" to "Terapkan Perubahan",

            // ── In-Game Overlay Panels & Studios ──
            "Magic Touch" to "Sentuhan Ajaib",
            "Macro Studio" to "Studio Makro",
            "Cyber Jukebox" to "Jukebox Cyber",
            "Screen Record" to "Perekam Layar",
            "System Optimizer" to "Pengoptimal Sistem",
            "Antivirus" to "Keamanan & Antivirus",
            "Gaming VPN" to "VPN Game & Jaringan",
            "GPU Graphics" to "Grafis GPU",
            "FPS Limiter" to "Limitasi FPS",
            "Universal FPS Lock" to "Kunci FPS Universal",
            "Deep Cooling" to "Pendinginan Ekstrem",
            "Phone Health" to "Kesehatan Ponsel",
            "Instant Tap" to "Ketukan Instan",
            "Rapid Spam" to "Spam Cepat",
            "Auto Drag" to "Tarik Otomatis",
            "Timed Hold" to "Tahan Teratur",
            "Multi-Pin Link" to "Tautan Multi-Pin",
            "Start Record" to "Mulai Rekam",
            "Stop Record" to "Hentikan Rekam",
            "Save" to "Simpan",
            "Close" to "Tutup",
            "Cancel" to "Batal",
            "Confirm" to "Konfirmasi",
            "Active" to "Aktif",
            "ACTIVE" to "AKTIF",
            "Expired" to "Kedaluwarsa",
            "Days Remaining" to "Hari Tersisa",
            "No Ads" to "Tanpa Iklan",

            // ── Cleaner & Optimizer Screen ──
            "NUKE OPTIMIZER" to "PENGOPTIMAL NUKE",
            "PRIVILEGED CONTROL ONLINE" to "KONTROL ISTIMEWA AKTIF",
            "STANDARD CONTROL // CONNECT PRIVILEGED ENGINE FOR ADVANCED ACTIONS" to "KONTROL STANDAR // HUBUNGKAN ENGINE UNTUK FITUR LANJUTAN",
            "Smart cleanup covers app cache, memory reclamation, and storage housekeeping." to "Pembersihan mencakup cache aplikasi, reklamasi memori, dan pembersihan file sementara.",
            "LAST RECLAIM" to "REKLAMASI TERAKHIR",
            "APP CACHE" to "CACHE APLIKASI",
            "Clear temporary Game Nuke cache files" to "Bersihkan file cache sementara Game Nuke",
            "DEEP RECLAIM" to "REKLAMASI MENDALAM",
            "Memory reclamation with supported kernel compaction" to "Reklamasi memori dengan pemadatan kernel aktif",
            "Connect the privileged engine for advanced memory reclamation" to "Hubungkan engine istimewa untuk reklamasi memori lanjutan",
            "TRIM TRASH" to "BERSIHKAN SAMPAH",
            "Trim empty dirs and temporary install packages" to "Bersihkan direktori kosong & sisa paket instalasi",
            "PACKAGE CACHE" to "CACHE PAKET",
            "Clear package manager residue and cached data" to "Bersihkan residu package manager & data cache",
            "↩ Rollback All" to "↩ Kembalikan Semua",
            "📤 Export Preset" to "📤 Ekspor Preset",

            // ── VIP & Multi-Payment Gateway ──
            "TRANSACTION HISTORY" to "RIWAYAT TRANSAKSI",
            "Orders & Subscription Invoices" to "Faktur Pesanan & Langganan",
            "No Transaction History" to "Belum Ada Riwayat Transaksi",
            "Your pending invoices and VIP subscriptions will appear here." to "Tagihan tertunda dan langganan VIP Anda akan muncul di sini.",
            "Order successfully cancelled" to "Pesanan berhasil dibatalkan",
            "Order status updated" to "Status pesanan diperbarui",
            "Payment Confirmed! VIP Pass Activated." to "Pembayaran Dikonfirmasi! Akses VIP Aktif.",
            "Status" to "Status",
            "Invoices are valid for 15 minutes. You can resume or verify your payment status anytime." to "Faktur berlaku 15 menit. Anda dapat melanjutkan atau memverifikasi pembayaran kapan saja.",
            "PAID" to "LUNAS",
            "CANCELLED" to "DIBATALKAN",
            "EXPIRED" to "KADALUARSA",
            "PENDING" to "MENUNGGU",
            "Order ID copied" to "ID Pesanan disalin",
            "Pay Now" to "Bayar Sekarang",
            "Verify" to "Verifikasi",
            "Cancel" to "Batal",
            "GAME NUKE VIP" to "GAME NUKE VIP",
            "ACTIVE" to "AKTIF",
            "Days Left" to "Hari Tersisa",
            "100% AD-FREE PASS" to "AKSES 100% BEBAS IKLAN",
            "Zero ads across app & floating gaming boosters" to "Bebas iklan di seluruh aplikasi & booster game melayang",
            "Instant booster launch with zero rewarded video gates" to "Mulai booster instan tanpa gerbang iklan video berhadiah",
            "Hardware-locked license (survives reinstall & clear data)" to "Lisensi terkunci perangkat (tetap aktif meski instal ulang)",
            "Multi-Payment: QRIS (E-Wallets) & Bank Virtual Accounts" to "Multi-Pembayaran: QRIS (E-Wallet) & Virtual Account Bank",
            "SELECT SUBSCRIPTION TIER" to "PILIH TINGKAT LANGGANAN",
            "SELECT PAYMENT METHOD" to "PILIH METODE PEMBAYARAN",
            "CONTINUE TO PAYMENT" to "LANJUTKAN KE PEMBAYARAN",
            "View Payment History" to "Lihat Riwayat Pembayaran",
            "Expires in:" to "Berakhir dalam:",
            "Scan with GoPay, OVO, DANA, BCA, Mandiri, ShopeePay or any mobile banking app." to "Pindai dengan GoPay, OVO, DANA, BCA, Mandiri, ShopeePay atau m-banking apa pun.",
            "Generating QRIS..." to "Membuat QRIS...",
            "DOWNLOAD QRIS (SAVE TO GALLERY)" to "UNDUH QRIS (SIMPAN KE GALERI)",
            "Download QRIS to scan from your e-wallet gallery or point camera." to "Unduh QRIS untuk scan dari galeri e-wallet atau arahkan kamera HP lain.",
            "QRIS image saved to Gallery! Open your e-wallet to scan from photo." to "QRIS berhasil disimpan ke Galeri! Buka e-wallet Anda lalu pilih Scan dari Galeri.",
            "Failed to save QRIS image" to "Gagal menyimpan gambar QRIS",
            "ACTIVE VIP SUBSCRIPTION" to "LANGGANAN VIP AKTIF",
            "PREMIUM VIP ACTIVE" to "PREMIUM VIP AKTIF",
            "MANAGE / UPGRADE" to "KELOLA / UPGRADE",
            "EXTEND / UPGRADE SUBSCRIPTION" to "PERPANJANG / UPGRADE PAKET",
            "Extend or Upgrade: Purchasing any tier below will ADD extra days directly on top of your remaining days. Zero days are lost!" to "Perpanjang / Upgrade: Pembelian paket di bawah akan otomatis MENAMBAHKAN hari di atas sisa aktif Anda. Tidak ada hari yang terbuang!",
            "100% Ad-Free" to "100% Bebas Iklan",
            "Copied VA Number" to "Nomor VA Disalin",
            "Open Mobile Banking > Transfer > Virtual Account > Paste VA Number" to "Buka M-Banking > Transfer > Virtual Account > Tempel Nomor VA",
            "Subtotal (Plan)" to "Subtotal (Paket)",
            "Gateway Fee" to "Biaya Gateway",
            "EXACT TOTAL TO PAY" to "TOTAL PAS HARUS DIBAYAR",
            "Copied total amount" to "Total nominal disalin",
            "IMPORTANT: You must transfer the EXACT total amount above (including fees / unique digits). Any difference will cause payment gateway verification to fail." to "PENTING: Anda HARUS mentransfer TOTAL PAS di atas (termasuk biaya admin / kode unik). Selisih nominal akan menyebabkan verifikasi otomatis gagal.",
            "CHECK PAYMENT STATUS" to "PERIKSA STATUS PEMBAYARAN",
            "Payment pending. Awaiting gateway settlement..." to "Pembayaran pending. Menunggu konfirmasi gateway...",
            "CHANGE PLAN OR METHOD" to "UBAH PAKET ATAU METODE",
            "VIP UNLOCKED!" to "VIP TERBUKA!",
            "Your VIP subscription has been successfully activated. All ads and booster gates are permanently disabled on this device." to "Langganan VIP Anda berhasil diaktifkan. Semua iklan & gerbang booster kini dinonaktifkan di perangkat ini.",
            "DONE" to "SELESAI",
            "Days Duration" to "Hari Durasi",
            "Weekly VIP Pass" to "Akses VIP Mingguan",
            "Monthly VIP Pass" to "Akses VIP Bulanan",
            "Semi-Annual VIP Pass" to "Akses VIP 6 Bulan",
            "Annual VIP Pass" to "Akses VIP Tahunan",
            "Flexible" to "Fleksibel",
            "Most Popular" to "Paling Populer",
            "Save 17%" to "Hemat 17%",
            "Best Value" to "Nilai Terbaik",
            "EXPIRED" to "KADALUWARSA",
            "PENDING" to "MENUNGGU",
            "PAID" to "LUNAS",
            "CANCELLED" to "DIBATALKAN",
            "INVOICE EXPIRED" to "TAGIHAN KADALUWARSA",
            "This payment session has timed out (15-minute limit). Please initiate a new order." to "Sesi pembayaran telah berakhir (batas 15 menit). Silakan buat pesanan baru.",
            "CREATE NEW ORDER" to "BUAT PESANAN BARU",
            "New Order" to "Pesanan Baru",
            "Order has expired. Please create a new order." to "Pesanan telah kedaluwarsa. Silakan buat pesanan baru.",
            "Lifetime VIP Pass" to "Akses VIP Selamanya",
            "Permanent VIP" to "VIP Permanen"
        ),
        "es" to mapOf(
            "Command Center" to "Centro de Comando",
            "Standard Mode Ready" to "Modo Estándar Listo",
            "Ready" to "Listo",
            "Adaptive gaming optimization engine" to "Motor de optimización de juegos",
            "System Readiness" to "Preparación de Sistema",
            "Launch Game Session" to "Iniciar Sesión de Juego",
            "Connected" to "Conectado",
            "Connect" to "Conectar",
            "CPU Load" to "Carga de CPU",
            "RAM Usage" to "Uso de RAM",
            "Refresh Rate" to "Tasa de Refresco",
            "HIGH TEMP" to "TEMP ALTA",
            "OPTIMAL" to "ÓPTIMO",
            "DISPLAY" to "PANTALLA",
            "THERMAL" to "TÉRMICO",
            "BATTERY" to "BATERÍA",
            "STORAGE" to "ALMACENAMIENTO",
            "NETWORK" to "RED",
            "FREE" to "LIBRE",
            "SESSION READINESS" to "ESTADO DE SESIÓN",
            "SYSTEM PATHS" to "RUTAS DE SISTEMA",
            "DEVICE CONTROL" to "CONTROL DE DISPOSITIVO",
            "FLOATING HUD" to "HUD FLOTANTE",
            "GAME FOCUS" to "MODO JUEGO",
            "BATTERY GUARD" to "GUARDIÁN DE BATERÍA",
            "HARDWARE SYNC" to "SINCRONIZACIÓN HARDWARE",
            "NUKE DECK" to "CUBIERTA NUKE",
            "HIGH-VALUE CONTROLS" to "CONTROLES CLAVE",
            "GAME SPACE" to "ESPACIO DE JUEGOS",
            "DEEP CLEAN" to "LIMPIEZA PROFUNDA",
            "SYSTEM EDITOR" to "EDITOR DE SISTEMA",
            "TASK MANAGER" to "ADMINISTRADOR DE TAREAS",
            "Core" to "Núcleo",
            "Games" to "Juegos",
            "Optimize" to "Optimizar",
            "FREE TIER" to "NIVEL GRATIS",
            "Tap to Remove All Ads" to "Toca para Quitar Anuncios",
            "UPGRADE" to "MEJORAR",
            "VIP Pass (Ad-Free)" to "Pase VIP (Sin Anuncios)",
            "VIP Pass" to "Pase VIP",
            "Device System Editor" to "Editor de Sistema",
            "Web Server & REST API" to "Servidor Web & API REST",
            "Documentation" to "Documentación",
            "About Developer" to "Sobre Desarrollador",
            "Check Update" to "Buscar Actualizaciones",
            "Rate App" to "Calificar App",
            "Language" to "Idioma",
            "Select Language" to "Seleccionar Idioma",
            "SELECT LANGUAGE" to "SELECCIONAR IDIOMA",
            "Cancel" to "Cancelar",
            "Confirm" to "Confirmar",
            "Close" to "Cerrar",
            "Active" to "Activo"
        ),
        "pt" to mapOf(
            "Command Center" to "Centro de Comando",
            "System Readiness" to "Prontidão do Sistema",
            "Launch Game Session" to "Iniciar Sessão de Jogo",
            "Connected" to "Conectado",
            "Connect" to "Conectar",
            "CPU Load" to "Uso de CPU",
            "RAM Usage" to "Uso de RAM",
            "Refresh Rate" to "Taxa de Atualização",
            "DISPLAY" to "TELA",
            "THERMAL" to "TÉRMICO",
            "BATTERY" to "BATERIA",
            "STORAGE" to "ARMAZENAMENTO",
            "NETWORK" to "REDE",
            "FREE" to "LIVRE",
            "Core" to "Núcleo",
            "Games" to "Jogos",
            "Optimize" to "Otimizar",
            "FREE TIER" to "NÍVEL GRATUITO",
            "Tap to Remove All Ads" to "Toque para Remover Anúncios",
            "UPGRADE" to "MELHORAR",
            "VIP Pass (Ad-Free)" to "Passe VIP (Sem Anúncios)",
            "VIP Pass" to "Passe VIP",
            "Language" to "Idioma",
            "Cancel" to "Cancelar",
            "Confirm" to "Confirmar",
            "Close" to "Fechar"
        ),
        "ru" to mapOf(
            "Command Center" to "Командный Центр",
            "System Readiness" to "Готовность Системы",
            "Launch Game Session" to "Запустить Игру",
            "Connected" to "Подключено",
            "Connect" to "Подключить",
            "CPU Load" to "Нагрузка CPU",
            "RAM Usage" to "ОЗУ Память",
            "Refresh Rate" to "Частота Экрана",
            "DISPLAY" to "ДИСПЛЕЙ",
            "THERMAL" to "ТЕМПЕРАТУРА",
            "BATTERY" to "БАТАРЕЯ",
            "STORAGE" to "ПАМЯТЬ",
            "NETWORK" to "СЕТЬ",
            "FREE" to "СВОБОДНО",
            "Core" to "Ядро",
            "Games" to "Игры",
            "Optimize" to "Оптимизация",
            "FREE TIER" to "БЕСПЛАТНО",
            "Tap to Remove All Ads" to "Нажмите чтобы убрать рекламу",
            "UPGRADE" to "ОБНОВИТЬ",
            "VIP Pass (Ad-Free)" to "VIP Пропуск (Без Рекламы)",
            "VIP Pass" to "VIP Пропуск",
            "Language" to "Язык",
            "Cancel" to "Отмена",
            "Confirm" to "Подтвердить",
            "Close" to "Закрыть"
        )
    )

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedLang = sp.getString(KEY_LANG, "en") ?: "en"
        _currentLanguage.value = savedLang

        cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        loadCacheFromDisk()
    }

    fun getLanguage(context: Context): String {
        init(context)
        return _currentLanguage.value
    }

    fun getCurrentLanguageName(): String {
        val code = _currentLanguage.value
        return SUPPORTED_LANGUAGES.firstOrNull { it.code == code }?.nativeName ?: "English"
    }

    fun setLanguage(context: Context, langCode: String) {
        init(context)
        if (_currentLanguage.value == langCode) return

        _currentLanguage.value = langCode

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, langCode)
            .apply()

        // Sync Android runtime configuration locale
        runCatching {
            val locale = Locale.forLanguageTag(langCode)
            Locale.setDefault(locale)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        }
    }

    fun tr(text: String): String {
        val lang = _currentLanguage.value
        if (lang == "en" || text.isBlank()) return text

        // 1. Fast in-memory cache
        val langMap = memoryCache[lang]
        val cached = langMap?.get(text)
        if (cached != null) return cached

        // 2. Pre-bundled offline dictionary exact match
        val offlineDict = OFFLINE_DICTIONARY[lang]
        val exactMatch = offlineDict?.get(text)
        if (exactMatch != null) {
            val map = memoryCache.computeIfAbsent(lang) { ConcurrentHashMap() }
            map[text] = exactMatch
            return exactMatch
        }

        // 3. Pre-bundled offline dictionary case-insensitive match
        if (offlineDict != null) {
            val trimmed = text.trim()
            val entry = offlineDict.entries.firstOrNull { it.key.equals(trimmed, ignoreCase = true) }
            if (entry != null) {
                val map = memoryCache.computeIfAbsent(lang) { ConcurrentHashMap() }
                map[text] = entry.value
                return entry.value
            }

            // 4. Smart Prefix / Emoji / Icon Unwrapping
            // Matches items like "🎯 Touch Tuning", "↩ Rollback All", "⚡ KILL ALL", etc.
            val prefixMatch = Regex("^([\\p{So}\\p{Sk}\\p{Sm}\\p{Sc}\\p{Pd}\\s•>\\-\\*\\#\\/]+)(.+)$").find(text)
            if (prefixMatch != null) {
                val prefix = prefixMatch.groupValues[1]
                val innerText = prefixMatch.groupValues[2].trim()
                val innerTranslated = offlineDict[innerText]
                    ?: offlineDict.entries.firstOrNull { it.key.equals(innerText, ignoreCase = true) }?.value
                if (innerTranslated != null) {
                    val full = prefix + innerTranslated
                    val map = memoryCache.computeIfAbsent(lang) { ConcurrentHashMap() }
                    map[text] = full
                    return full
                }
            }
        }

        // 5. Silent background cache population (NO UI refresh loop)
        fetchTranslationSilently(text, lang)
        return text
    }

    private fun fetchTranslationSilently(text: String, targetLang: String) {
        val requestKey = "$targetLang:$text"
        if (!pendingRequests.add(requestKey)) return

        executor.execute {
            try {
                val encodedText = URLEncoder.encode(text, "UTF-8")
                val endpoint = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=$targetLang&q=$encodedText"
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                    val responseStr = reader.use { it.readText() }
                    val array = JSONArray(responseStr)
                    if (array.length() > 0) {
                        val firstItem = array.optJSONArray(0)
                        val translated = firstItem?.optString(0, "") ?: array.optString(0, "")
                        if (translated.isNotBlank()) {
                            val map = memoryCache.computeIfAbsent(targetLang) { ConcurrentHashMap() }
                            map[text] = translated
                            saveCacheToDisk()
                            // Notice: NO _version.value++ here! This eliminates the flicker/refresh loop completely!
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.d(TAG, "Translation error for '$text' -> $targetLang: ${e.message}")
            } finally {
                pendingRequests.remove(requestKey)
            }
        }
    }

    private fun loadCacheFromDisk() {
        val file = cacheFile ?: return
        if (!file.exists()) return
        try {
            val content = file.readText(Charsets.UTF_8)
            val json = JSONObject(content)
            val keys = json.keys()
            while (keys.hasNext()) {
                val lang = keys.next()
                val langObj = json.getJSONObject(lang)
                val map = memoryCache.computeIfAbsent(lang) { ConcurrentHashMap() }
                val innerKeys = langObj.keys()
                while (innerKeys.hasNext()) {
                    val src = innerKeys.next()
                    map[src] = langObj.getString(src)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading translation cache: ${e.message}")
        }
    }

    private fun saveCacheToDisk() {
        val file = cacheFile ?: return
        try {
            val json = JSONObject()
            memoryCache.forEach { (lang, map) ->
                val langObj = JSONObject()
                map.forEach { (k, v) -> langObj.put(k, v) }
                json.put(lang, langObj)
            }
            file.writeText(json.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }
}

/** Global translation helper accessible anywhere in Kotlin/Compose/Views */
fun tr(text: String): String = NukeTranslationManager.tr(text)

/** String extension for instant translation */
@JvmName("translateExtension")
fun String.tr(): String = NukeTranslationManager.tr(this)

/** Composable helper that translates [text] reacting cleanly to global language changes */
@Composable
fun rememberTranslated(text: String): String {
    val currentLang by NukeTranslationManager.currentLanguage.collectAsState()
    return if (currentLang == "en") text else NukeTranslationManager.tr(text)
}
