package com.maihe.chengguang

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.widget.MediaController
import android.widget.VideoView
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.net.URLEncoder

@Composable
fun DetailScreen(item: CatalogItem, downloads: DownloadStore) {
    when (item.type) {
        "audio" -> AudioDetail(item, downloads)
        "video" -> VideoDetail(item, downloads)
        else -> ReadingDetail(item)
    }
}

@Composable
fun ReadingDetail(item: CatalogItem) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(item.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(item.category, fontSize = 13.sp, color = Color.Gray)
        if (item.description.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(item.description, fontSize = 15.sp, color = Color.DarkGray)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        val body = item.content.ifEmpty { "（正文待管理员上传）" }
        body.split("\n").forEach { paragraph ->
            if (paragraph.isBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
            } else {
                Text(paragraph, fontSize = 17.sp, lineHeight = 28.sp)
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(8.dp))
        Text("正文已随 App 自带，离线也能读，无需下载。", fontSize = 12.sp, color = Color.Gray)
    }
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

/** 讲章附件：PDF 直接用系统查看器打开；PPT/PPTX 用微软在线预览打开（要联网）。 */
@Composable
fun SlidesSection(item: CatalogItem) {
    if (item.pdfUrl.isBlank()) return
    val context = LocalContext.current
    var hint by remember { mutableStateOf("") }
    val lower = item.pdfUrl.lowercase()
    val isPdf = lower.endsWith(".pdf")
    val isSlides = lower.endsWith(".ppt") || lower.endsWith(".pptx")
    val fileName = item.pdfUrl.substringAfterLast("/").substringBefore("?").ifEmpty { "讲章.pdf" }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("讲章", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                when {
                    isPdf -> "PDF 讲章，可在线查看，也可下载到手机离线看"
                    isSlides -> "PPT 讲章，在线预览打开（要联网）；建议管理员转成 PDF 再传，打开更快"
                    else -> "讲章附件"
                },
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    hint = if (openSlides(context, item.pdfUrl, isPdf)) "" else "打不开讲章，请检查网络后重试"
                }) { Text("查看讲章") }
                OutlinedButton(onClick = {
                    hint = if (downloadSlides(context, item, fileName)) "已开始下载，可在通知栏查看" else "下载失败，请检查网络后重试"
                }) { Text("下载讲章") }
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

@Composable
fun AudioDetail(item: CatalogItem, downloads: DownloadStore) {
    val player = remember { MediaPlayer() }
    var status by remember { mutableStateOf("准备中…") }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableIntStateOf(0) }

    // 播放地址：已下载用本地文件，否则用远程链接。下载完成时地址会变，
    // 重新准备播放器，下次播放就走离线文件。
    val source = downloads.playSource(item)

    LaunchedEffect(source) {
        isPlaying = false
        positionMs = 0
        try {
            player.reset()
            player.setDataSource(source)
            player.setOnPreparedListener {
                durationMs = it.duration
                status = if (downloads.isDownloaded(item)) "已就绪（离线播放）" else "已就绪"
            }
            player.setOnErrorListener { _, _, _ ->
                status = "加载失败，请检查网络"
                isPlaying = false
                reportMessage("audio-playback", item.title)
                true
            }
            player.setOnCompletionListener {
                isPlaying = false
                positionMs = 0
                status = "已播完"
            }
            player.prepareAsync()
        } catch (_: Exception) {
            status = "加载失败，请检查网络"
            reportMessage("audio-playback", item.title)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            try {
                positionMs = player.currentPosition
            } catch (_: Exception) {
            }
            delay(500)
        }
    }

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
            onClick = {
                if (status == "已就绪" || status == "已就绪（离线播放）" || status == "已播完" || isPlaying) {
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                        status = "已暂停"
                    } else {
                        if (status == "已播完") {
                            try {
                                player.seekTo(0)
                            } catch (_: Exception) {
                            }
                            positionMs = 0
                        }
                        player.start()
                        isPlaying = true
                        status = "正在播放"
                    }
                }
            },
            modifier = Modifier
                .size(72.dp)
                .background(Color(0xFF9A7B2D), CircleShape)
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Slider(
            value = positionMs.toFloat(),
            onValueChange = { positionMs = it.toInt() },
            onValueChangeFinished = {
                try {
                    player.seekTo(positionMs)
                } catch (_: Exception) {
                }
            },
            valueRange = 0f..(durationMs.coerceAtLeast(1)).toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatMs(positionMs), fontSize = 13.sp, color = Color.Gray)
            Text(if (durationMs > 0) formatMs(durationMs) else item.duration, fontSize = 13.sp, color = Color.Gray)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(status, fontSize = 13.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        Text("内容由管理员在后台上传", fontSize = 12.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(20.dp))
        DownloadSection(item, downloads)
        Spacer(modifier = Modifier.height(16.dp))
        SlidesSection(item)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun VideoDetail(item: CatalogItem, downloads: DownloadStore) {
    val uri = downloads.playUri(item)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val controller = MediaController(ctx)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnPreparedListener { it.start() }
                    setOnErrorListener { _, _, _ ->
                        reportMessage("video-playback", item.title)
                        true
                    }
                }
            },
            update = { view ->
                // 已下载后地址会变成本地文件：地址变了才重新设置，避免每次重组都重播
                if (view.tag != uri.toString()) {
                    view.tag = uri.toString()
                    view.setVideoURI(uri)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
        )
        Column(modifier = Modifier.padding(20.dp)) {
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
            SlidesSection(item)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
