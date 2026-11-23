package com.maihe.chengguang

import android.media.MediaPlayer
import android.net.Uri
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
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

@Composable
fun DetailScreen(item: CatalogItem) {
    when (item.type) {
        "audio" -> AudioDetail(item)
        "video" -> VideoDetail(item)
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
    }
}

private fun formatMs(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
fun AudioDetail(item: CatalogItem) {
    val player = remember { MediaPlayer() }
    var status by remember { mutableStateOf("准备中…") }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableIntStateOf(0) }

    LaunchedEffect(item.url) {
        try {
            player.setDataSource(item.url)
            player.setOnPreparedListener {
                durationMs = it.duration
                status = "已就绪"
            }
            player.setOnErrorListener { _, _, _ ->
                status = "加载失败，请检查网络（示例音频）"
                isPlaying = false
                true
            }
            player.setOnCompletionListener {
                isPlaying = false
                positionMs = 0
                status = "已播完"
            }
            player.prepareAsync()
        } catch (_: Exception) {
            status = "加载失败，请检查网络（示例音频）"
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
                if (status == "已就绪" || status == "已播完" || isPlaying) {
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
        Text("示例音频：正式内容将替换为 R2 链接", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
fun VideoDetail(item: CatalogItem) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(Uri.parse(item.url))
                    val controller = MediaController(ctx)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnPreparedListener { it.start() }
                    setOnErrorListener { _, _, _ -> true }
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
            }
            Text(meta, fontSize = 13.sp, color = Color.Gray)
            if (item.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(item.description, fontSize = 15.sp, color = Color.DarkGray)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("示例视频：正式内容将替换为 R2 链接", fontSize = 12.sp, color = Color.Gray)
        }
    }
}
