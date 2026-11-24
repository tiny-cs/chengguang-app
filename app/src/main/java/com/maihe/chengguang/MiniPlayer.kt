package com.maihe.chengguang

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// MainActivity.kt 里的 Navy / Gold 是文件私有的，这里用同一色值各定义一份。
private val MiniPlayerNavy = Color(0xFF1F2A44)
private val MiniPlayerGold = Color(0xFF9A7B2D)

/** 迷你条现在显示谁：视频 / 音频 / 都没有 */
enum class ActivePlayer { VIDEO, AUDIO, NONE }

/**
 * 迷你条和通知栏共用同一条规则，决定现在显示谁：
 * 谁在出声就显示谁；都不出声时，视频有东西可管（暂停 / 缓冲 / 刚准备好）
 * 就显示视频，再没有就显示音频；两个都没有就 NONE（不占位置）。
 * 听书不在这里判断：迷你条在听书进行时本来就不显示，通知栏另有听书那条。
 */
fun resolveActivePlayer(): ActivePlayer {
    val videoActive = VideoPlayer.currentItem != null &&
        VideoPlayer.status != "已停止" && VideoPlayer.status != "已播完"
    val audioActive = AudioPlayer.currentItem != null &&
        AudioPlayer.status != "已停止" && AudioPlayer.status != "已播完"
    return when {
        VideoPlayer.isPlaying -> ActivePlayer.VIDEO
        AudioPlayer.isPlaying -> ActivePlayer.AUDIO
        videoActive -> ActivePlayer.VIDEO
        audioActive -> ActivePlayer.AUDIO
        else -> ActivePlayer.NONE
    }
}

/**
 * 主界面迷你播放条。
 *
 * 音频和视频都是后台播放：从详情页退回首页 / 全部 / 我的，播放还在继续，
 * 但主界面上原来没有任何控制，只能下拉通知栏。这条小条补上这个口子：
 * 显示正在播的标题和状态，暂停 / 继续、停止都在主界面点一下就行，
 * 点小条本身回到这条内容的详情页（音频回音频详情，视频回视频详情，
 * 回去画面从当前位置继续，不重播）。
 *
 * 显示条件（缺一个都不显示，不占位置）：
 * - 音频或视频有一条在播过（currentItem != null），且状态不是“已停止”
 *   也不是“已播完”（停了、播完了就没东西可管了）
 * - 听书（TtsReader）不在进行（听书开始时会停掉音频和视频；只剩暂停的
 *   音频 / 视频时，听书一开这条也不显示，免得两个东西抢位置）
 * - 两个都在管的时候：谁在出声就显示谁；都不出声时显示视频（见 resolveActivePlayer）
 *
 * 暂停 / 继续、停止都按到当前显示的那一个播放器上。
 * 正在准备（“准备中…”）时暂停键换成小转圈，不让用户点一个没准备好的播放器。
 *
 * 接线（MainActivity.kt 里做，本文件不管）：放在 Scaffold 的 bottomBar 里、
 * 底部导航栏上方，只在 首页 / 全部 / 我的 三个主界面显示；详情页不放，
 * 详情页自己有完整控制。onOpenDetail 传 { id -> go(Route.Detail(id)) }。
 */
@Composable
fun MiniPlayerBar(
    onOpenDetail: (itemId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (TtsReader.isActive) return
    val active = resolveActivePlayer()
    if (active == ActivePlayer.NONE) return
    val isVideo = active == ActivePlayer.VIDEO
    val item = if (isVideo) VideoPlayer.currentItem else AudioPlayer.currentItem
    if (item == null) return
    val status = if (isVideo) VideoPlayer.status else AudioPlayer.status
    val isPlaying = if (isVideo) VideoPlayer.isPlaying else AudioPlayer.isPlaying
    val isBuffering = if (isVideo) VideoPlayer.isBuffering else AudioPlayer.isBuffering

    val statusText = if (isBuffering) "正在缓冲…" else status
    val preparing = status == "准备中…"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenDetail(item.id) },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(categoryColor(item.category), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(typeChar(item.type), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(statusText, fontSize = 12.sp, color = Color.Gray)
            }
            if (preparing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MiniPlayerGold,
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(
                    onClick = {
                        if (isVideo) {
                            if (VideoPlayer.isPlaying) VideoPlayer.pause() else VideoPlayer.resume()
                        } else {
                            if (AudioPlayer.isPlaying) AudioPlayer.pause() else AudioPlayer.resume()
                        }
                    }
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "继续",
                        tint = MiniPlayerNavy
                    )
                }
            }
            IconButton(
                onClick = {
                    if (isVideo) VideoPlayer.stopPlayback() else AudioPlayer.stopPlayback()
                }
            ) {
                Icon(Icons.Default.Close, contentDescription = "停止", tint = Color.Gray)
            }
        }
    }
}
