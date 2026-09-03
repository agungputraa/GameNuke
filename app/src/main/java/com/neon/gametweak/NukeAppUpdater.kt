package com.neon.gametweak

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Game Nuke High-Scale In-App Auto Updater.
 *
 * Utilizes static version metadata served via Edge CDN (GitHub Pages / jsDelivr)
 * completely bypassing GitHub API 60-request/hr rate limits.
 * Supports background download progress and seamless PackageInstaller dispatch.
 */
object NukeAppUpdater {
    private const val TAG = "NukeAppUpdater"

    // Primary Edge CDN endpoints
    private const val PRIMARY_ENDPOINT = "https://agungputraa.github.io/GameNuke/version.json"
    private const val CDN_FALLBACK_ENDPOINT = "https://cdn.jsdelivr.net/gh/agungputraa/GameNuke@gh-pages/version.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val apkSizeMb: String,
        val publishedAt: String
    )

    sealed class DownloadProgress {
        object Idle : DownloadProgress()
        data class Downloading(val percent: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgress()
        data class Completed(val file: File) : DownloadProgress()
        data class Error(val message: String) : DownloadProgress()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _downloadState = MutableStateFlow<DownloadProgress>(DownloadProgress.Idle)
    val downloadState: StateFlow<DownloadProgress> = _downloadState.asStateFlow()

    /**
     * Check for updates in background without blocking main thread.
     */
    fun checkForUpdates(
        context: Context,
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onUpToDate: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        scope.launch {
            try {
                val updateInfo = fetchRemoteVersion()
                if (updateInfo != null) {
                    val currentVersionCode = BuildConfig.VERSION_CODE
                    if (updateInfo.versionCode > currentVersionCode) {
                        mainHandler.post { onUpdateAvailable(updateInfo) }
                    } else {
                        mainHandler.post { onUpToDate?.invoke() }
                    }
                } else {
                    mainHandler.post { onError?.invoke("Unable to parse version metadata") }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                mainHandler.post { onError?.invoke(e.message ?: "Network error checking update") }
            }
        }
    }

    private fun fetchRemoteVersion(): UpdateInfo? {
        val endpoints = listOf(PRIMARY_ENDPOINT, CDN_FALLBACK_ENDPOINT)
        for (endpoint in endpoints) {
            try {
                val url = URL("$endpoint?t=${System.currentTimeMillis()}")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json")

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()

                    val json = JSONObject(body)
                    val versionCode = json.optInt("versionCode", 0)
                    val versionName = json.optString("versionName", "Unknown")
                    val downloadUrl = json.optString("downloadUrl", "")
                    val apkSizeMb = json.optString("apkSizeMb", "34")
                    val publishedAt = json.optString("publishedAt", "")

                    val notesBuilder = StringBuilder()
                    val notesArr = json.optJSONArray("releaseNotes")
                    if (notesArr != null) {
                        for (i in 0 until notesArr.length()) {
                            notesBuilder.append("• ").append(notesArr.getString(i)).append("\n")
                        }
                    } else {
                        notesBuilder.append(json.optString("releaseNotes", "New performance and feature improvements."))
                    }

                    return UpdateInfo(
                        versionCode = versionCode,
                        versionName = versionName,
                        releaseNotes = notesBuilder.toString().trim(),
                        downloadUrl = downloadUrl,
                        apkSizeMb = apkSizeMb,
                        publishedAt = publishedAt
                    )
                }
            } catch (err: Exception) {
                Log.w(TAG, "Endpoint failed: $endpoint", err)
            }
        }
        return null
    }

    /**
     * Download the update APK with progress updates and trigger PackageInstaller.
     */
    fun startDownloadAndInstall(context: Context, updateInfo: UpdateInfo) {
        scope.launch {
            try {
                _downloadState.value = DownloadProgress.Downloading(0, 0, 0)

                val downloadUrl = URL(updateInfo.downloadUrl)
                val conn = downloadUrl.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    throw IllegalStateException("Server returned HTTP $responseCode")
                }

                val totalLength = conn.contentLength.toLong()
                val targetFile = File(context.cacheDir, "GameNuke_v${updateInfo.versionName}.apk")
                if (targetFile.exists()) targetFile.delete()

                var bytesRead = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var lastReportTime = System.currentTimeMillis()

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read

                            val now = System.currentTimeMillis()
                            if (now - lastReportTime > 200 || bytesRead == totalLength) {
                                lastReportTime = now
                                val percent = if (totalLength > 0) ((bytesRead * 100) / totalLength).toInt() else 50
                                _downloadState.value = DownloadProgress.Downloading(percent, bytesRead, totalLength)
                            }
                        }
                    }
                }

                _downloadState.value = DownloadProgress.Completed(targetFile)

                // Dispatch installer on Main Thread
                withContext(Dispatchers.Main) {
                    launchInstaller(context, targetFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed downloading update APK", e)
                _downloadState.value = DownloadProgress.Error(e.message ?: "Failed downloading update")
            }
        }
    }

    private fun launchInstaller(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists() || apkFile.length() < 1024 * 512) {
                NukeToast.error(context, "File APK tidak valid atau unduhan belum selesai", true)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    NukeToast.success(context, "Izinkan instalasi aplikasi dari sumber ini untuk Game Nuke", long = true)
                    return
                }
            }

            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching package installer", e)
            NukeToast.error(context, "Could not launch package installer: ${e.message}", true)
        }
    }
}
