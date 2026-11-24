package com.maihe.chengguang

import android.app.Activity
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.util.Rational
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.io.File
import java.net.URLEncoder

@Composable
fun DetailScreen(
    item: CatalogItem,
    downloads: DownloadStore,
    bookshelf: BookshelfStore? = null,
    siblings: List<CatalogItem> = emptyList(),
    onSelectItem: (String) -> Unit = {},
    onViewPdf: (String, String) -> Unit = { _, _ -> },
    onFullscreenChange: (Boolean) -> Unit = {},
    vocab: VocabStore? = null
) {
    when (item.type) {
        "audio" -> AudioDetail(item, downloads, siblings, onSelectItem, onViewPdf)
        "video" -> VideoDetail(item, downloads, onViewPdf, onFullscreenChange)
        else -> ReadingDetail(item, bookshelf, vocab)
    }
}

@Composable
fun ReadingDetail(item: CatalogItem, bookshelf: BookshelfStore? = null, vocab: VocabStore? = null) {
    ReaderScreen(item, bookshelf, vocab)
}

private fun formatMs(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** 音频/视频详情页共用的下载区：下载按钮 + 进度 + 取消 / 已下载 + 删除。 */
@Composable
fun DownloadSection(item: CatalogItem, downloads: DownloadStore) {
    val state = downloads.state(item)
    var hint by remember { mutableStateOf("") }

    // 下载进行中时每 0.5 秒刷新一次进度
    LaunchedEffect(state.status) {
        while (state.status == DownloadStatus.DOWNLOADING || state.status == DownloadStatus.PAUSED) {
            delay(500)
            downloads.refresh()
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("离线下载", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))
            when (state.status) {
                DownloadStatus.NOT_DOWNLOADED -> {
                    if (item.size.isNotEmpty()) Text("文件约 ${item.size}", fontSize = 13.sp, color = Color.Gray)
                    if (downloads.wifiOnly) Text("仅在 WiFi 下下载（可在“我的”里改）", fontSize = 13.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = {
                        when (val r = downloads.start(item)) {
                            DownloadStore.StartResult.Started -> hint = ""
                            DownloadStore.StartResult.NeedWifi -> hint = "已设为仅 WiFi 下载，请连接 WiFi 后再试"
                            is DownloadStore.StartResult.Failed -> hint = "下载失败：${r.message}"
                        }
                    }) { Text("下载到手机") }
                }
                DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED -> {
                    val percent = (state.progress * 100).toInt()
                    Text(
                        if (state.status == DownloadStatus.PAUSED) "已暂停 $percent%"
                        else "正在下载 $percent%",
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        if (state.totalBytes > 0) "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}"
                        else "已下载 ${formatBytes(state.downloadedBytes)}",
                        fontSize = 13.sp, color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(onClick = { downloads.cancel(item) }) { Text("取消下载") }
                }
                DownloadStatus.DOWNLOADED -> {
                    Text("✓ 已下载，可离线播放", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(onClick = { downloads.delete(item) }) { Text("删除下载") }
                }
                DownloadStatus.FAILED -> {
                    Text("下载失败，请检查网络后重试", fontSize = 14.sp, color = Color(0xFF8E4A2F))
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = {
                        when (val r = downloads.start(item)) {
                            DownloadStore.StartResult.Started -> hint = ""
                            DownloadStore.StartResult.NeedWifi -> hint = "已设为仅 WiFi 下载，请连接 WiFi 后再试"
                            is DownloadStore.StartResult.Failed -> hint = "下载失败：${r.message}"
                        }
                    }) { Text("重新下载") }
                }
            }
            if (hint.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(hint, fontSize = 13.sp, color = Color(0xFF8E4A2F))
            }
        }
    }
}

/** 讲章附件：PDF 在 App 里直接看（内置阅读器）；PPT/PPTX 用微软在线预览打开（要联网）。
 * 下载过的不会重复下载：下载文件夹里有了就显示“已下载”，点查看直接打开那份。 */
@Composable
fun SlidesSection(item: CatalogItem, onViewPdf: (String, String) -> Unit = { _, _ -> }) {
    if (item.pdfUrl.isBlank()) return
    val context = LocalContext.current
    var hint by remember { mutableStateOf("") }
    val lower = item.pdfUrl.lowercase()
    val isPdf = lower.endsWith(".pdf")
    val isSlides = lower.endsWith(".ppt") || lower.endsWith(".pptx")
    val fileName = item.pdfUrl.substringAfterLast("/").substringBefore("?").ifEmpty { "讲章.pdf" }
    val downloadsFile = remember(fileName) {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
    }
    var slidesDownloaded by remember { mutableStateOf(downloadsFile.exists()) }
    var waitingDownload by remember { mutableStateOf(false) }

    // 进页面时看一眼下载文件夹（外面下好的、以前下好的都认）
    LaunchedEffect(item.pdfUrl) {
        slidesDownloaded = downloadsFile.exists()
    }
    // 点了下载后每秒看一次文件出来没有，出来了就显示“已下载”，最多等 2 分钟
    LaunchedEffect(waitingDownload) {
        if (!waitingDownload) return@LaunchedEffect
        var tries = 0
        while (tries < 120) {
            delay(1000)
            tries++
            if (downloadsFile.exists()) {
                slidesDownloaded = true
                waitingDownload = false
                hint = ""
                return@LaunchedEffect
            }
        }
        waitingDownload = false
        hint = "下载好像没完成，请看通知栏"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("讲章", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                when {
                    isPdf -> "PDF 讲章，在 App 里直接看，也可下载到手机离线看"
                    isSlides -> "PPT 讲章，在线预览打开（要联网）；建议管理员转成 PDF 再传，打开更快"
                    else -> "讲章附件"
                },
                fontSize = 14.sp
            )
            if (slidesDownloaded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("✓ 已下载，可离线查看", fontSize = 14.sp)
            } else if (waitingDownload) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("正在下载…", fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (isPdf) {
                        onViewPdf(item.pdfUrl, item.title + "讲章")
                    } else {
                        hint = if (openSlides(context, item.pdfUrl, isPdf)) "" else "打不开讲章，请检查网络后重试"
                    }
                }) { Text("查看讲章") }
                if (slidesDownloaded) {
                    OutlinedButton(onClick = {
                        if (downloadsFile.delete()) {
                            slidesDownloaded = false
                            hint = "已删除下载的讲章"
                        } else {
                            hint = "删除失败"
                        }
                    }) { Text("删除下载") }
                } else {
                    OutlinedButton(onClick = {
                        if (downloadSlides(context, item, fileName)) {
                            waitingDownload = true
                            hint = "已开始下载，可在通知栏查看"
                        } else {
                            hint = "下载失败，请检查网络后重试"
                        }
                    }) { Text("下载讲章") }
                }
            }
            if (hint.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(hint, fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}

private fun openSlides(context: Context, url: String, isPdf: Boolean): Boolean {
    return try {
        val viewUrl = if (isPdf) url
        else "https://view.officeapps.live.com/op/view.aspx?src=" + URLEncoder.encode(url, "UTF-8")
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(viewUrl)))
        true
    } catch (_: Exception) {
        reportMessage("slides-open", url)
        false
    }
}

private fun downloadSlides(context: Context, item: CatalogItem, fileName: String): Boolean {
    return try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(item.pdfUrl))
            .setTitle(item.title + "讲章")
            .setDescription(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        dm.enqueue(req)
        true
    } catch (_: Exception) {
        reportMessage("slides-download", item.title)
        false
    }
}

/** 播放模式：播完后做什么。单曲循环只重复这一首，其余在同栏目音频里切歌。 */
enum class PlayMode(val label: String) {
    ORDER("顺序播放"), SINGLE("单曲循环"), LOOP_ALL("列表循环"), SHUFFLE("随机播放")
}

@Composable
fun AudioDetail(
    item: CatalogItem,
    downloads: DownloadStore,
    siblings: List<CatalogItem> = emptyList(),
    onSelectItem: (String) -> Unit = {},
    onViewPdf: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    // 播放地址：已下载用本地文件，否则用远程链接
    val source = downloads.playSource(item)
    val offline = downloads.isDownloaded(item)

    // 详情页可见时：把同栏目列表交给播放器（切歌用），切歌时让页面换路由
    DisposableEffect(item.id) {
        AudioPlayer.queue = siblings
        AudioPlayer.onCurrentChanged = { next ->
            if (next.id != item.id) onSelectItem(next.id)
        }
        onDispose { AudioPlayer.onCurrentChanged = null }
    }

    // 进这个页面：如果播的不是这首——
    //   * 别的歌正在播：别打断它，只等用户点播放再切过来；
    //   * 没在播：已下载的直接自动播，远程的先准备好、等用户点播放（避免一打开就偷跑流量）。
    // 如果正在播这首但地址变了（如下载完成变成离线文件），用新地址重新准备，播着的继续播。
    LaunchedEffect(item.id, source) {
        if (AudioPlayer.currentItem?.id != item.id) {
            if (!AudioPlayer.isPlaying) {
                AudioPlayer.play(context, item, source, offline, autoStart = offline)
            }
        } else if (AudioPlayer.currentSource != source) {
            AudioPlayer.play(context, item, source, offline, autoStart = AudioPlayer.isPlaying)
        }
    }

    // 播着的时候每 0.5 秒刷一次进度条
    LaunchedEffect(AudioPlayer.isPlaying) {
        while (AudioPlayer.isPlaying) {
            AudioPlayer.refreshPosition()
            delay(500)
        }
    }

    // 播放状态都存在 AudioPlayer 单例里：离开这个页面也不会丢，回来继续显示
    val status = AudioPlayer.status
    val isPlaying = AudioPlayer.isPlaying
    val isBuffering = AudioPlayer.isBuffering
    val mode = AudioPlayer.mode
    // 正在播别的歌：这个页面显示那首的状态，进度条先不动，点播放就切到这首
    val playingOther = AudioPlayer.currentItem?.id != null && AudioPlayer.currentItem?.id != item.id
    val shownPosition = if (playingOther) 0 else AudioPlayer.positionMs
    val shownDuration = if (playingOther) 0 else AudioPlayer.durationMs
    val statusText = if (playingOther && isPlaying) "正在播：${AudioPlayer.currentItem?.title}"
        else if (isBuffering && !playingOther) "正在缓冲…"
        else status
    var dragMs by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(Color(0xFF2E7D6B), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("音", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(item.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text("${item.category} · ${item.duration}", fontSize = 13.sp, color = Color.Gray)
        if (item.description.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(item.description, fontSize = 15.sp, color = Color.DarkGray)
        }
        Spacer(modifier = Modifier.height(28.dp))
        IconButton(
            onClick = { AudioPlayer.toggle(context, item, source, offline) },
            modifier = Modifier
                .size(72.dp)
                .background(Color(0xFF9A7B2D), CircleShape)
        ) {
            Icon(
                if (isPlaying && !playingOther) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying && !playingOther) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OutlinedButton(onClick = { AudioPlayer.cycleSpeed() }) { Text("倍速 ${AudioPlayer.speedLabel()}", fontSize = 14.sp) }
            OutlinedButton(onClick = { AudioPlayer.cycleMode() }) { Text("模式：${mode.label}", fontSize = 14.sp) }
            OutlinedButton(onClick = { AudioPlayer.next() }) { Text("下一首", fontSize = 14.sp) }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Slider(
            value = (dragMs ?: shownPosition).toFloat(),
            onValueChange = { dragMs = it.toInt() },
            onValueChangeFinished = {
                dragMs?.let { AudioPlayer.seekTo(it) }
                dragMs = null
            },
            valueRange = 0f..(shownDuration.coerceAtLeast(1)).toFloat(),
            enabled = !playingOther,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatMs(dragMs ?: shownPosition), fontSize = 13.sp, color = Color.Gray)
            Text(if (shownDuration > 0) formatMs(shownDuration) else item.duration, fontSize = 13.sp, color = Color.Gray)
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (isBuffering && !playingOther) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("正在缓冲…", fontSize = 13.sp, color = Color.Gray)
            }
        } else {
            Text(statusText, fontSize = 13.sp, color = Color.Gray)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("离开这个页面也会继续播，通知栏里可以暂停或停止", fontSize = 12.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        Text("内容由管理员在后台上传", fontSize = 12.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(20.dp))
        DownloadSection(item, downloads)
        Spacer(modifier = Modifier.height(16.dp))
        SlidesSection(item, onViewPdf)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun VideoDetail(
    item: CatalogItem,
    downloads: DownloadStore,
    onViewPdf: (String, String) -> Unit = { _, _ -> },
    onFullscreenChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val localView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uri = downloads.playUri(item)
    // 缓冲状态：打开先显示“正在加载…”，准备好/出第一帧后消失；
    // 播着播着网卡了（BUFFERING_START）会再出现，播不下去会显示失败。
    // 用 remember(item.id) 存：换一条视频就回到“加载中”，不沿用上一条的状态。
    val buffering = remember(item.id) { mutableStateOf(true) }
    val playFailed = remember(item.id) { mutableStateOf(false) }
    // 全屏状态：进全屏转横屏、藏系统栏、藏标题栏；退出回竖屏、恢复系统栏。
    var isFullscreen by remember(item.id) { mutableStateOf(false) }
    // 画中画要用的：VideoView 引用、视频实际尺寸、页面是不是在前台、
    // 是不是刚点了进小窗口（进画中画那一下 Activity 也会走 onPause，别误停）。
    val videoViewRef = remember { mutableStateOf<VideoView?>(null) }
    val videoSize = remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val pageResumed = remember { mutableStateOf(true) }
    val autoPaused = remember { mutableStateOf(false) }
    val pipRequested = remember { mutableStateOf(false) }
    // 画中画状态在 MainActivity 上（系统回调里更新）：小窗口里只显示视频本身。
    val inPip = (activity as? MainActivity)?.pipMode?.value == true

    fun setSystemBars(fullscreen: Boolean) {
        val act = activity ?: return
        val controller = WindowCompat.getInsetsController(act.window, localView)
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun enterFullscreen() {
        val act = activity ?: return
        isFullscreen = true
        onFullscreenChange(true)
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setSystemBars(true)
    }

    fun exitFullscreen() {
        val act = activity ?: return
        isFullscreen = false
        onFullscreenChange(false)
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setSystemBars(false)
    }

    /** 画中画窗口比例：按视频实际尺寸来，夹在系统允许的范围里（1:2.39 ~ 2.39:1），超了会抛异常。 */
    fun pipAspectRatio(w: Int, h: Int): Rational {
        if (w <= 0 || h <= 0) return Rational(16, 9)
        val ratio = w.toFloat() / h
        return when {
            ratio > 2.39f -> Rational(239, 100)
            ratio < 1f / 2.39f -> Rational(100, 239)
            else -> Rational(w, h)
        }
    }

    /** 进小窗口：返回 true 表示系统接了。失败时不改任何状态，视频留在原地继续播。 */
    fun enterPip(): Boolean {
        val act = activity ?: return false
        val (w, h) = videoSize.value ?: (16 to 9)
        pipRequested.value = true
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(pipAspectRatio(w, h))
            .build()
        val ok = act.enterPictureInPictureMode(params)
        if (!ok) pipRequested.value = false
        return ok
    }

    // 全屏时按返回先退全屏，不直接退回上一页（这个 BackHandler 比 ChengguangApp
    // 里那个后注册，启用时优先触发——和书架页多选返回同一个做法）。
    BackHandler(enabled = isFullscreen) { exitFullscreen() }

    // 按 Home / 切到最近任务时自动进小窗口（只在播着的时候；暂停着不进）。
    // 离开页面时取消注册，同时把方向和系统栏恢复成跟随系统
    // （退出全屏时设了竖屏锁定，这里松开，免得别的页面也被锁竖屏）。
    DisposableEffect(item.id) {
        val act = activity as? MainActivity
        act?.onUserLeaveHintAction = {
            if (videoViewRef.value?.isPlaying == true) enterPip()
        }
        onDispose {
            act?.onUserLeaveHintAction = null
            activity?.let {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                if (localView.isAttachedToWindow) {
                    WindowCompat.getInsetsController(it.window, localView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
            onFullscreenChange(false)
        }
    }

    // 页面前后台：切后台（非画中画）时暂停，回来时如果之前是被系统停的就接着播；
    // 画中画时 Activity 也会走 onPause，但视频要继续播，别停。
    // 刚打开还在缓冲、用户就按 Home 的情况：OnPrepared 里先别自动播，等回来再播，
    // 不然会在后台突然出声。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    pageResumed.value = false
                    val act = activity
                    if (act?.isInPictureInPictureMode == false && !pipRequested.value) {
                        videoViewRef.value?.let { vv ->
                            if (vv.isPlaying) {
                                vv.pause()
                                autoPaused.value = true
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    pageResumed.value = true
                    pipRequested.value = false
                    if (autoPaused.value) {
                        videoViewRef.value?.start()
                        autoPaused.value = false
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 全屏/小窗口时只显示视频本身（标题、下载、讲章都藏起来）；
    // 视频 Box 是同一个节点，只是换了尺寸，所以横竖屏切换、进出小窗口
    // 都不会让 VideoView 重建、重播（地址没变时 update 里也不重置）。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (isFullscreen || inPip) Modifier else Modifier.verticalScroll(rememberScrollState()))
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (isFullscreen || inPip) Modifier.fillMaxSize()
                    else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        videoViewRef.value = this
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setOnPreparedListener { mp ->
                            videoSize.value = mp.videoWidth to mp.videoHeight
                            buffering.value = false
                            // 页面在前台（或在小窗口里）才自动播；刚打开还在缓冲就切后台了，
                            // 先别出声，等回来再播（见上面的 autoPaused）。
                            if (pageResumed.value || activity?.isInPictureInPictureMode == true) {
                                mp.start()
                            } else {
                                autoPaused.value = true
                            }
                        }
                        setOnInfoListener { _, what, _ ->
                            when (what) {
                                MediaPlayer.MEDIA_INFO_BUFFERING_START -> buffering.value = true
                                MediaPlayer.MEDIA_INFO_BUFFERING_END,
                                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> buffering.value = false
                            }
                            false
                        }
                        setOnErrorListener { _, _, _ ->
                            buffering.value = false
                            playFailed.value = true
                            reportMessage("video-playback", item.title)
                            true
                        }
                    }
                },
                update = { view ->
                    // 已下载后地址会变成本地文件：地址变了才重新设置，避免每次重组都重播
                    if (view.tag != uri.toString()) {
                        view.tag = uri.toString()
                        videoSize.value = null
                        buffering.value = true
                        playFailed.value = false
                        view.setVideoURI(uri)
                    }
                },
                onRelease = { v ->
                    // 离开视频页就彻底停掉，不然 VideoView 会在后台继续出声
                    // （进画中画、转屏都不会走到这里，组合还在，不会误停）。
                    videoViewRef.value = null
                    v.stopPlayback()
                },
                modifier = Modifier.fillMaxSize()
            )
            if (buffering.value) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("正在加载…", color = Color.White, fontSize = 14.sp)
                    }
                }
            } else if (playFailed.value) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "视频加载失败，请检查网络后重试",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            // 全屏时右上角留一个退出按钮（返回键也能退，这里是给习惯点的人的）；
            // 小窗口里不显示——小窗口点一下是回 App，不是退全屏。
            if (isFullscreen && !inPip) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopEnd
                ) {
                    OutlinedButton(
                        onClick = { exitFullscreen() },
                        modifier = Modifier.padding(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) { Text("退出全屏", fontSize = 13.sp) }
                }
            }
        }
        if (!isFullscreen && !inPip) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { enterFullscreen() }) { Text("全屏", fontSize = 14.sp) }
                OutlinedButton(onClick = { enterPip() }) { Text("小窗口", fontSize = 14.sp) }
            }
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(item.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                val meta = buildString {
                    append(item.category)
                    if (item.duration.isNotEmpty()) append(" · ${item.duration}")
                    if (item.size.isNotEmpty()) append(" · ${item.size}")
                    if (downloads.isDownloaded(item)) append(" · 已下载")
                }
                Text(meta, fontSize = 13.sp, color = Color.Gray)
                if (item.description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(item.description, fontSize = 15.sp, color = Color.DarkGray)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("内容由管理员在后台上传", fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(20.dp))
                DownloadSection(item, downloads)
                Spacer(modifier = Modifier.height(16.dp))
                SlidesSection(item, onViewPdf)
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
