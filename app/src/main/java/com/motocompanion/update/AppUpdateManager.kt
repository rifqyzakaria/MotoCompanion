package com.motocompanion.update

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.motocompanion.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object AppUpdateManager {

    private const val TAG = "MotoAppUpdate"
    private const val PREFS_NAME = "moto_update_prefs"
    const val KEY_GITHUB_REPO = "github_repo_path"
    const val DEFAULT_REPO = "username/MotoCompanion"

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val changelog: String,
        val apkDownloadUrl: String
    )

    fun getSavedRepo(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GITHUB_REPO, DEFAULT_REPO) ?: DEFAULT_REPO
    }

    fun setSavedRepo(context: Context, repoPath: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GITHUB_REPO, repoPath.trim()).apply()
    }

    /**
     * Memeriksa pembaruan terbaru dari GitHub Releases
     */
    fun checkForUpdates(
        context: Context,
        showUpToDateToast: Boolean = false,
        onUpdateFound: ((ReleaseInfo) -> Unit)? = null
    ) {
        val repoPath = getSavedRepo(context)
        val mainHandler = Handler(Looper.getMainLooper())

        thread {
            try {
                val apiUrl = "https://api.github.com/repos/$repoPath/releases/latest"
                val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)

                    val tagName = json.getString("tag_name")
                    val body = json.optString("body", "Pembaruan versi terbaru.")
                    val cleanRemoteVersion = tagName.replace("[^0-9.]".toRegex(), "")

                    // Cari file APK di aset rilis
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
                            val release = ReleaseInfo(tagName, cleanRemoteVersion, body, apkUrl)
                            if (onUpdateFound != null) {
                                onUpdateFound(release)
                            } else {
                                showUpdateDialog(context, release)
                            }
                        } else {
                            if (showUpToDateToast) {
                                Toast.makeText(
                                    context,
                                    "Aplikasi sudah versi terbaru ($currentVersion)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } else if (connection.responseCode == 404) {
                    mainHandler.post {
                        if (showUpToDateToast) {
                            Toast.makeText(
                                context,
                                "Repositori GitHub '$repoPath' atau rilis tidak ditemukan.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    Log.w(TAG, "GitHub API returned code: ${connection.responseCode}")
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

    fun showUpdateDialog(context: Context, release: ReleaseInfo) {
        AlertDialog.Builder(context)
            .setTitle("Pembaruan Tersedia: ${release.tagName}")
            .setMessage("Versi baru telah dirilis di GitHub!\n\nCatatan Perubahan:\n${release.changelog}\n\nUnduh dan pasang pembaruan sekarang?")
            .setPositiveButton("Perbarui Sekarang") { _, _ ->
                downloadAndInstallApk(context, release.apkDownloadUrl, release.tagName)
            }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun downloadAndInstallApk(context: Context, downloadUrl: String, tagName: String) {
        val progressDialog = ProgressDialog(context).apply {
            setTitle("Mengunduh Pembaruan")
            setMessage("Sedang mengunduh versi $tagName dari GitHub...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        val mainHandler = Handler(Looper.getMainLooper())

        thread {
            try {
                val url = URL(downloadUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                }

                val contentLength = connection.contentLength
                val apkFile = File(context.cacheDir, "MotoCompanion_update.apk")
                if (apkFile.exists()) apkFile.delete()

                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0) {
                                val progress = ((totalRead * 100) / contentLength).toInt()
                                mainHandler.post { progressDialog.progress = progress }
                            }
                        }
                    }
                }

                mainHandler.post {
                    progressDialog.dismiss()
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal mengunduh APK: ${e.message}")
                mainHandler.post {
                    progressDialog.dismiss()
                    Toast.makeText(context, "Gagal mengunduh pembaruan: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Menjalankan instalasi APK pembaruan secara otomatis melalui Package Installer bawaan Android
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(context, "File pembaruan tidak ditemukan", Toast.LENGTH_SHORT).show()
            return
        }

        // Cek izin instal aplikasi di Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "Izinkan instalasi aplikasi dari sumber ini untuk melanjutkan pembaruan", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }

        val apkUri = FileProvider.getUriForFile(
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
    }
}
