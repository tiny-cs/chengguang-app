package com.maihe.chengguang

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 离线下载（只下载、不上传）。
 *
 * 用系统 DownloadManager 做实际传输：后台下载、断点续传、App 关掉也能继续，
 * 文件放在 App 专属目录 getExternalFilesDir(DOWNLOADS)，现代 Android 不需要
 * 申请存储权限。下载记录（DownloadManager 的下载 id）存在 SharedPreferences。
 * 书籍/诗歌正文已在 catalog.json 里、随 App 自带，所以本来就能离线读，
 * 不给它们做下载按钮。
 */

enum class DownloadStatus { NOT_DOWNLOADED, DOWNLOADING, PAUSED, DOWNLOADED, FAILED }

data class DownloadState(
    val status: DownloadStatus,
    val progress: Float = 0f, // 0..1，下载中才有意义
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L
)

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb < 1) "%.1f KB".format(bytes / 1024.0) else "%.1f MB".format(mb)
}

class DownloadStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("chengguang_downloads", Context.MODE_PRIVATE)
    private val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** 每次 refresh() 后 version +1，读了 version 的 Composable 会重组并重新读状态。 */
    var version by mutableStateOf(0)
        private set

    fun refresh() {
        version++
    }

    /** 仅在 WiFi 下下载（默认开），存在 SharedPreferences，可在“我的”里开关。 */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(value) {
            prefs.edit().putBoolean("wifi_only", value).apply()
            refresh()
        }

    /** WiFi 自动下载新内容（默认开）：目录刷新后，新讲道连上 WiFi 自动下好，打开就能听。成员可以在“我的”里关掉它。 */
    var autoDownload: Boolean
        get() = prefs.getBoolean("auto_download", true)
        set(value) {
            prefs.edit().putBoolean("auto_download", value).apply()
            refresh()
        }

    /** 用户手动删掉 / 取消过的条目：不再自动下载（手动点“下载到手机”仍可下载）。 */
    private fun skippedIds(): Set<String> =
        prefs.getStringSet("auto_dl_skipped", emptySet())?.toSet() ?: emptySet()

    private fun markSkipped(item: CatalogItem) {
        val s = skippedIds().toMutableSet()
        s.add(item.id)
        prefs.edit().putStringSet("auto_dl_skipped", s).apply()
    }

    fun isAutoSkipped(item: CatalogItem): Boolean = skippedIds().contains(item.id)

    /**
     * WiFi 自动下载：目录刷新后，把还没下过、没在下、用户没删过的新音频/视频下好。
     * 只在 WiFi 下跑（不管“仅 WiFi 下载”开关），不走流量；书籍正文已在目录里，不用下载。
     */
    fun autoDownloadNew(items: List<CatalogItem>, token: String?) {
        if (!autoDownload) return
        if (!isWifiConnected()) return
        for (item in items) {
            if (item.type != "audio" && item.type != "video") continue
            if (item.url.isEmpty()) continue
            if (isAutoSkipped(item)) continue
            val st = state(item).status
            if (st == DownloadStatus.DOWNLOADED || st == DownloadStatus.DOWNLOADING || st == DownloadStatus.PAUSED) continue
            start(item, token)
        }
    }

    fun isWifiConnected(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun fileName(item: CatalogItem): String {
        val ext = item.url.substringAfterLast('.', "").substringBefore('?')
            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
            ?: if (item.type == "audio") "mp3" else "mp4"
        return "${item.id}.$ext"
    }

    fun downloadDir(): File {
        return appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads").also { it.mkdirs() }
    }

    fun localFile(item: CatalogItem): File = File(downloadDir(), fileName(item))

    private fun downloadId(item: CatalogItem): Long? {
        val id = prefs.getLong("dl_id_${item.id}", -1L)
        return if (id >= 0) id else null
    }

    private fun saveDownloadId(item: CatalogItem, id: Long) {
        prefs.edit().putLong("dl_id_${item.id}", id).apply()
    }

    private fun clearDownloadId(item: CatalogItem) {
        prefs.edit().remove("dl_id_${item.id}").apply()
    }

    /** 查询 DownloadManager 里这条下载的状态；id 已失效时返回 null。 */
    private fun queryStatus(id: Long): Triple<Int, Long, Long>? {
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        cursor?.use {
            if (!it.moveToFirst()) return null
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return Triple(status, done, total)
        }
        return null
    }

    fun state(item: CatalogItem): DownloadState {
        // 订阅 version：refresh() 后重组，重新查一次
        @Suppress("UNUSED_EXPRESSION") version
        if (item.url.isEmpty()) return DownloadState(DownloadStatus.NOT_DOWNLOADED)
        val file = localFile(item)
        val id = downloadId(item)
        if (id == null) {
            // 没有在途记录：文件在且有内容就是已下载（比如上次下载完成、App 重启后）
            return if (file.exists() && file.length() > 0) {
                DownloadState(DownloadStatus.DOWNLOADED, 1f, file.length(), file.length())
            } else {
                DownloadState(DownloadStatus.NOT_DOWNLOADED)
            }
        }
        val q = queryStatus(id)
        if (q == null) {
            // DownloadManager 里已无此记录（系统清过、或已完成被清理）
            return if (file.exists() && file.length() > 0) {
                DownloadState(DownloadStatus.DOWNLOADED, 1f, file.length(), file.length())
            } else {
                clearDownloadId(item)
                DownloadState(DownloadStatus.FAILED)
            }
        }
        val (status, done, total) = q
        return when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                if (file.exists() && file.length() > 0) {
                    DownloadState(DownloadStatus.DOWNLOADED, 1f, file.length(), file.length())
                } else {
                    clearDownloadId(item)
                    DownloadState(DownloadStatus.FAILED)
                }
            }
            DownloadManager.STATUS_FAILED -> {
                clearDownloadId(item)
                DownloadState(DownloadStatus.FAILED)
            }
            DownloadManager.STATUS_PAUSED -> DownloadState(
                DownloadStatus.PAUSED,
                if (total > 0) done.toFloat() / total else 0f, done, total
            )
            else -> DownloadState(
                DownloadStatus.DOWNLOADING,
                if (total > 0) done.toFloat() / total else 0f, done, total
            )
        }
    }

    fun isDownloaded(item: CatalogItem): Boolean = state(item).status == DownloadStatus.DOWNLOADED

    /** 播放地址：已下载用本地文件，否则用远程链接；空字符串表示没有可播的。 */
    fun playSource(item: CatalogItem): String {
        @Suppress("UNUSED_EXPRESSION") version
        return if (isDownloaded(item)) localFile(item).absolutePath else item.url
    }

    fun playUri(item: CatalogItem): Uri {
        @Suppress("UNUSED_EXPRESSION") version
        return if (isDownloaded(item)) Uri.fromFile(localFile(item)) else Uri.parse(item.url)
    }

    sealed interface StartResult {
        data object Started : StartResult
        /** 开了“仅 WiFi 下载”且当前不是 WiFi：不开始，等连上 WiFi 再点 */
        data object NeedWifi : StartResult
        data class Failed(val message: String) : StartResult
    }

    fun start(item: CatalogItem, token: String? = null): StartResult {
        // 手动点下载 = 明确想要这条：把它从“不再自动下”的名单里拿掉
        val s = skippedIds().toMutableSet()
        if (s.remove(item.id)) prefs.edit().putStringSet("auto_dl_skipped", s).apply()
        if (item.url.isEmpty()) return StartResult.Failed("这条内容没有可下载的文件")
        if (isDownloaded(item)) return StartResult.Started
        if (wifiOnly && !isWifiConnected()) return StartResult.NeedWifi
        return try {
            downloadDir().mkdirs()
            val request = DownloadManager.Request(Uri.parse(item.url))
                .setTitle(item.title)
                .setDescription("城光 · ${item.category}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(
                    appContext, Environment.DIRECTORY_DOWNLOADS, fileName(item)
                )
            // 目录和文件现在要登录才能拿：下载请求也带上凭证，不然服务器会 401
            if (!token.isNullOrBlank()) {
                request.addRequestHeader("Authorization", "Bearer $token")
            }
            if (wifiOnly) {
                request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            } else {
                request.setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                )
            }
            val id = dm.enqueue(request)
            saveDownloadId(item, id)
            refresh()
            StartResult.Started
        } catch (e: Exception) {
            reportError("download", e)
            StartResult.Failed(e.message ?: "下载启动失败")
        }
    }

    /** 取消正在进行的下载（删掉系统任务和未下完的文件）。 */
    fun cancel(item: CatalogItem) {
        // 手动取消 = 不想要这条：以后也不再自动下载它
        markSkipped(item)
        val wasDownloaded = isDownloaded(item)
        downloadId(item)?.let { dm.remove(it) }
        clearDownloadId(item)
        // 只有“还没下完”才删：已下载完成的文件不由 cancel 动
        if (!wasDownloaded) localFile(item).delete()
        refresh()
    }

    /** 删除已下载的文件，释放空间。 */
    fun delete(item: CatalogItem) {
        // 手动删掉 = 不想要这条：以后也不再自动下载它
        markSkipped(item)
        downloadId(item)?.let { dm.remove(it) }
        clearDownloadId(item)
        localFile(item).delete()
        refresh()
    }

    /**
     * 退出登录 / 账号失效时用：删掉所有下载任务和文件（音频、视频、讲章都在
     * 同一个 App 专属目录里），清掉下载记录。注意只删 dl_id_ 开头的键，
     * wifi_only 这些设置要留着。
     */
    fun clearAllDownloads() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("dl_id_") }.forEach { key ->
            val id = prefs.getLong(key, -1L)
            if (id >= 0) {
                try { dm.remove(id) } catch (_: Exception) { }
            }
            edit.remove(key)
        }
        edit.apply()
        downloadDir().listFiles()?.forEach { it.delete() }
        refresh()
    }

    fun downloadedItems(all: List<CatalogItem>): List<CatalogItem> {
        @Suppress("UNUSED_EXPRESSION") version
        return all.filter { it.type == "audio" || it.type == "video" }.filter { isDownloaded(it) }
    }

    fun totalDownloadedBytes(all: List<CatalogItem>): Long {
        @Suppress("UNUSED_EXPRESSION") version
        return downloadedItems(all).sumOf { localFile(it).length() }
    }
}
