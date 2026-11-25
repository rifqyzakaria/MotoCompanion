package com.motocompanion.update

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.util.Linkify
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.motocompanion.BuildConfig
import com.motocompanion.R
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt

object AppUpdateManager {

    private const val TAG = "MotoAppUpdate"
    const val OFFICIAL_AUTHOR = "rifqyzakaria"
    const val OFFICIAL_REPO = "rifqyzakaria/MotoCompanion"

    private var activeUpdateDialog: Dialog? = null

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val changelog: String,
        val apkDownloadUrl: String
    )

    fun getSavedRepo(context: Context? = null): String {
        return OFFICIAL_REPO
    }

    /**
     * Memeriksa pembaruan terbaru langsung dari repositori resmi GitHub rifqyzakaria
     */
    fun checkForUpdates(
        context: Context,
        showUpToDateToast: Boolean = false,
        onUpdateFound: ((ReleaseInfo) -> Unit)? = null
    ) {
        val repoPath = OFFICIAL_REPO
        val mainHandler = Handler(Looper.getMainLooper())

        thread {
            try {
                val apiUrl = "https://api.github.com/repos/$repoPath/releases/latest"
                val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "rifqyzakaria-HSync")
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)

                    val tagName = json.getString("tag_name")
                    val rawBody = json.optString("body", "Official H-Sync update.")
                    val cleanRemoteVersion = tagName.replace("[^0-9.]".toRegex(), "")

                    var apkUrl: String? = null
                    val assets = json.getJSONArray("assets")
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.getString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.getString("browser_download_url")
                            break
                        }
                    }

                    val currentVersion = BuildConfig.VERSION_NAME

                    mainHandler.post {
                        if (apkUrl != null && isNewerVersion(cleanRemoteVersion, currentVersion)) {
                            val release = ReleaseInfo(tagName, cleanRemoteVersion, rawBody, apkUrl)
                            if (onUpdateFound != null) {
                                onUpdateFound(release)
                            } else {
                                showUpdateDialog(context, release)
                            }
                        } else if (showUpToDateToast) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.update_up_to_date, currentVersion),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    Log.w(TAG, "GitHub API code: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal memeriksa pembaruan GitHub: ${e.message}")
                mainHandler.post {
                    if (showUpToDateToast) {
                        Toast.makeText(context, "Gagal terhubung ke GitHub: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        val length = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until length) {
            val r = if (i < remoteParts.size) remoteParts[i] else 0
            val c = if (i < currentParts.size) currentParts[i] else 0
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * Menampilkan modal Software Update bergaya frosted dark minimalis (auto-download + What's new + Install Now / Later)
     */
    fun showUpdateDialog(context: Context, release: ReleaseInfo) {
        val activity = findActivity(context) ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        // Cegah duplikasi modal jika sudah sedang tampil
        if (activeUpdateDialog?.isShowing == true) return

        // Keamanan Anti-Tamper: Verifikasi bahwa URL unduhan resmi berasal dari github.com / githubusercontent
        val uri = Uri.parse(release.apkDownloadUrl)
        val host = uri.host?.lowercase() ?: ""
        if (!host.endsWith("github.com") && !host.endsWith("githubusercontent.com")) {
            Toast.makeText(context, "Peringatan Keamanan: URL pembaruan tidak resmi!", Toast.LENGTH_LONG).show()
            return
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_software_update, null)
        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvUpdateModalSubtitle)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressUpdateBar)
        val dividerWhatsNew = dialogView.findViewById<View>(R.id.dividerWhatsNew)
        val tvChangelog = dialogView.findViewById<TextView>(R.id.tvUpdateChangelog)
        val layoutDownloading = dialogView.findViewById<LinearLayout>(R.id.layoutDownloadingActions)
        val layoutReady = dialogView.findViewById<LinearLayout>(R.id.layoutReadyActions)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnUpdateCancel)
        val btnInstallNow = dialogView.findViewById<TextView>(R.id.btnUpdateInstallNow)
        val btnLater = dialogView.findViewById<TextView>(R.id.btnUpdateLater)

        // Render Markdown changelog kaya (Headings, Bullets, Bold, Monospace Pills, Links)
        tvChangelog.text = buildRichChangelogSpannable(activity, release.changelog)
        tvChangelog.movementMethod = LinkMovementMethod.getInstance()

        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(dialogView)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }

        // Konfigurasi ukuran jendela modal & efek blur frosted glass (Android 12+)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setDimAmount(0.65f)
            val metrics = activity.resources.displayMetrics
            val targetWidth = minOf((metrics.widthPixels * 0.86f).roundToInt(), dpToPx(activity, 332f))
            win.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    val attrs = win.attributes
                    attrs.blurBehindRadius = 28
                    win.attributes = attrs
                } catch (_: Exception) {}
            }
        }

        val targetApkFile = File(activity.cacheDir, "H-Sync_${release.versionName}.apk")
        val partApkFile = File(activity.cacheDir, "H-Sync_${release.versionName}.apk.part")
        val isCancelled = AtomicBoolean(false)
        var activeConnection: HttpURLConnection? = null
        var isDownloadReady = false

        fun switchToReadyState() {
            if (activity.isFinishing || activity.isDestroyed) return
            isDownloadReady = true
            tvSubtitle.text = activity.getString(R.string.update_ready_format, release.versionName)
            progressBar.visibility = View.GONE
            dividerWhatsNew.visibility = View.VISIBLE
            layoutDownloading.visibility = View.GONE
            btnInstallNow.text = activity.getString(R.string.update_btn_install_now)
            layoutReady.visibility = View.VISIBLE
        }

        fun switchToErrorState() {
            if (activity.isFinishing || activity.isDestroyed) return
            isDownloadReady = false
            tvSubtitle.text = activity.getString(R.string.update_failed_format, release.versionName)
            progressBar.visibility = View.GONE
            dividerWhatsNew.visibility = View.VISIBLE
            layoutDownloading.visibility = View.GONE
            btnInstallNow.text = activity.getString(R.string.update_btn_retry)
            layoutReady.visibility = View.VISIBLE
        }

        fun startBackgroundDownload() {
            isDownloadReady = false
            isCancelled.set(false)
            tvSubtitle.text = activity.getString(R.string.update_downloading_format, release.versionName)
            progressBar.progress = 0
            progressBar.visibility = View.VISIBLE
            dividerWhatsNew.visibility = View.GONE
            layoutReady.visibility = View.GONE
            layoutDownloading.visibility = View.VISIBLE

            val mainHandler = Handler(Looper.getMainLooper())

            thread {
                try {
                    if (partApkFile.exists()) partApkFile.delete()
                    val conn = (URL(release.apkDownloadUrl).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("User-Agent", "rifqyzakaria-HSync")
                        connectTimeout = 15000
                        readTimeout = 20000
                        instanceFollowRedirects = true
                    }
                    activeConnection = conn

                    val contentLength = conn.contentLength
                    var lastReportedProgress = 0

                    conn.inputStream.use { input ->
                        FileOutputStream(partApkFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead = 0
                            var totalRead = 0L

                            while (!isCancelled.get() && input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead
                                if (contentLength > 0) {
                                    val progress = ((totalRead * 100L) / contentLength).toInt().coerceIn(0, 100)
                                    if (progress != lastReportedProgress) {
                                        lastReportedProgress = progress
                                        mainHandler.post {
                                            if (dialog.isShowing && !activity.isFinishing) {
                                                ObjectAnimator.ofInt(progressBar, "progress", progress).apply {
                                                    duration = 140L
                                                    interpolator = DecelerateInterpolator()
                                                    start()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isCancelled.get()) {
                        if (partApkFile.exists()) partApkFile.delete()
                        return@thread
                    }

                    if (isValidApkFile(partApkFile)) {
                        if (targetApkFile.exists()) targetApkFile.delete()
                        partApkFile.renameTo(targetApkFile)
                        mainHandler.post {
                            if (dialog.isShowing) {
                                switchToReadyState()
                            }
                        }
                    } else {
                        if (partApkFile.exists()) partApkFile.delete()
                        mainHandler.post {
                            if (dialog.isShowing) {
                                switchToErrorState()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (partApkFile.exists()) partApkFile.delete()
                    if (!isCancelled.get()) {
                        Log.e(TAG, "Gagal mengunduh pembaruan APK: ${e.message}")
                        mainHandler.post {
                            if (dialog.isShowing) {
                                switchToErrorState()
                            }
                        }
                    }
                }
            }
        }

        btnCancel.setOnClickListener {
            isCancelled.set(true)
            thread {
                try { activeConnection?.disconnect() } catch (_: Exception) {}
                try { if (partApkFile.exists()) partApkFile.delete() } catch (_: Exception) {}
            }
            dialog.dismiss()
        }

        btnLater.setOnClickListener {
            dialog.dismiss()
        }

        btnInstallNow.setOnClickListener {
            if (isDownloadReady && isValidApkFile(targetApkFile)) {
                val launched = installApk(activity, targetApkFile)
                if (launched) {
                    dialog.dismiss()
                }
            } else {
                startBackgroundDownload()
            }
        }

        dialog.setOnDismissListener {
            if (activeUpdateDialog == dialog) {
                activeUpdateDialog = null
            }
        }

        activeUpdateDialog = dialog
        dialog.show()

        // Jika APK versi terbaru ini sudah pernah terunduh utuh sebelumnya, langsung siap pasang!
        if (isValidApkFile(targetApkFile)) {
            switchToReadyState()
        } else {
            startBackgroundDownload()
        }
    }

    private fun isValidApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 200_000L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val b0 = raf.read()
                val b1 = raf.read()
                // Signature ZIP/APK standar adalah 'P' (0x50) dan 'K' (0x4B)
                b0 == 0x50 && b1 == 0x4B
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Memformat teks rilis Markdown GitHub menjadi tampilan tipografi modern bergaya iOS / BitChord:
     * - Judul seksi (## / ###) tampil tebal dan proporsional
     * - Poin daftar (- / * / •) tampil rapi dengan hanging indent
     * - Teks **tebal** dan blok kode `inline` diberi pill highlight
     */
    private fun buildRichChangelogSpannable(context: Context, rawMarkdown: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val lines = rawMarkdown.lines()
        val bulletIndentPx = dpToPx(context, 13f)

        var isFirstContentLine = true

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("files:", ignoreCase = true)) continue

            when {
                trimmed.startsWith("#") -> {
                    val headingText = trimmed.trimStart('#').trim()
                    if (headingText.isEmpty()) continue
                    if (!isFirstContentLine) {
                        builder.append("\n\n")
                    }
                    val start = builder.length
                    appendInlineMarkdown(context, builder, headingText)
                    val end = builder.length
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(1.22f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(ForegroundColorSpan(Color.WHITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    isFirstContentLine = false
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                    val itemText = trimmed.substring(2).trim()
                    if (!isFirstContentLine) {
                        builder.append("\n")
                    }
                    val start = builder.length
                    builder.append("• ")
                    appendInlineMarkdown(context, builder, itemText)
                    val end = builder.length
                    builder.setSpan(
                        LeadingMarginSpan.Standard(0, bulletIndentPx),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    isFirstContentLine = false
                }
                else -> {
                    if (!isFirstContentLine) {
                        builder.append("\n\n")
                    }
                    appendInlineMarkdown(context, builder, trimmed)
                    isFirstContentLine = false
                }
            }
        }

        try {
            Linkify.addLinks(builder, Linkify.WEB_URLS)
        } catch (_: Exception) {}

        return builder
    }

    private fun appendInlineMarkdown(context: Context, builder: SpannableStringBuilder, text: String) {
        var i = 0
        val n = text.length
        while (i < n) {
            when {
                text.startsWith("**", i) -> {
                    val closeIdx = text.indexOf("**", i + 2)
                    if (closeIdx != -1) {
                        val boldContent = text.substring(i + 2, closeIdx)
                        val start = builder.length
                        builder.append(boldContent)
                        val end = builder.length
                        builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        builder.setSpan(ForegroundColorSpan(Color.WHITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = closeIdx + 2
                    } else {
                        builder.append(text[i])
                        i++
                    }
                }
                text[i] == '`' -> {
                    val closeIdx = text.indexOf('`', i + 1)
                    if (closeIdx != -1 && closeIdx > i + 1) {
                        val codeContent = text.substring(i + 1, closeIdx)
                        val start = builder.length
                        builder.append(codeContent)
                        val end = builder.length
                        builder.setSpan(
                            RoundedCodeSpan(context),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        i = closeIdx + 1
                    } else {
                        builder.append(text[i])
                        i++
                    }
                }
                else -> {
                    builder.append(text[i])
                    i++
                }
            }
        }
    }

    /**
     * Span khusus untuk menggambar kotak pill abu-abu gelap membulat pada teks kode (seperti `H-Sync-v1.7.0.apk`)
     */
    private class RoundedCodeSpan(context: Context) : ReplacementSpan() {
        private val bgColor = Color.parseColor("#3A3A40")
        private val textColor = Color.parseColor("#F2F2F7")
        private val cornerRadius = dpToPx(context, 4f).toFloat()
        private val horizontalPadding = dpToPx(context, 5f).toFloat()
        private val verticalInset = dpToPx(context, 1.5f).toFloat()

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val codePaint = Paint(paint).apply {
                typeface = Typeface.MONOSPACE
                textSize = paint.textSize * 0.92f
            }
            return (codePaint.measureText(text, start, end) + horizontalPadding * 2).roundToInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val codePaint = Paint(paint).apply {
                typeface = Typeface.MONOSPACE
                textSize = paint.textSize * 0.92f
                isAntiAlias = true
            }
            val width = codePaint.measureText(text, start, end)
            val rect = RectF(
                x,
                top.toFloat() + verticalInset,
                x + width + horizontalPadding * 2,
                bottom.toFloat() - verticalInset
            )

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bgColor
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

            codePaint.color = textColor
            canvas.drawText(text, start, end, x + horizontalPadding, y.toFloat(), codePaint)
        }
    }

    private fun findActivity(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return (dp * context.resources.displayMetrics.density).roundToInt()
    }

    /**
     * Menjalankan instalasi APK pembaruan secara otomatis melalui Package Installer bawaan Android.
     * Mengembalikan true jika intent installer berhasil diluncurkan.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) {
            Toast.makeText(context, "File pembaruan tidak ditemukan", Toast.LENGTH_SHORT).show()
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    context,
                    "Izinkan instalasi aplikasi dari sumber ini lalu tekan Pasang Sekarang kembali",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return false
            }
        }

        return try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gagal meluncurkan PackageInstaller: ${e.message}")
            Toast.makeText(context, "Gagal membuka installer APK: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }
}
