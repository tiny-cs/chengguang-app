package com.maihe.chengguang

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 后台播放：播放器放在进程级单例里，不属于任何一个页面。
 * 离开详情页、退到首页、切到别的 App，播放都不停；前台 Service 保活，
 * 通知栏里可以暂停 / 继续 / 停止。
 *
 * 注意：倍速和播放模式也是进程级的，换一首不会重置。
 */
object AudioPlayer {
    const val CHANNEL_ID = "chengguang-playback"
    const val NOTIF_ID = 1001
    const val ACTION_TOGGLE = "com.maihe.chengguang.ACTION_TOGGLE"
    const val ACTION_STOP = "com.maihe.chengguang.ACTION_STOP"
    const val ACTION_UPDATE = "com.maihe.chengguang.ACTION_UPDATE"

    var currentItem by mutableStateOf<CatalogItem?>(null)
        private set
    var status by mutableStateOf("未开始")
        private set
    var isPlaying by mutableStateOf(false)
        private set
    /** 正在等数据（缓冲中）：详情页显示“正在缓冲…”，而不是误以为在播 */
    var isBuffering by mutableStateOf(false)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var speed by mutableStateOf(1f)
        private set
    var mode by mutableStateOf(PlayMode.ORDER)
        private set
    /** 当前在播的地址（本地路径或远程链接），详情页用它判断地址变了没有 */
    var currentSource: String? = null
        private set

    private var player: MediaPlayer? = null
    private var appContext: Context? = null

    /** 同栏目音频列表（切歌用），详情页在可见时设置 */
    var queue: List<CatalogItem> = emptyList()
    /** 详情页可见时注册：切到下一首时让页面换路由；页面不在时只管播，不用换页 */
    var onCurrentChanged: ((CatalogItem) -> Unit)? = null

    val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    fun speedLabel(): String = when (speed) {
        0.75f -> "0.75x"
        1f -> "1.0x"
        1.25f -> "1.25x"
        1.5f -> "1.5x"
        else -> "2.0x"
    }

    private fun playerOrCreate(): MediaPlayer {
        return player ?: MediaPlayer().also { player = it }
    }

    private fun applySpeed() {
        // 1.0x 也要调：播着从 0.75x 切回 1.0x 时不调会留在 0.75x，标签却显示 1.0x
        try {
            val p = player ?: return
            p.playbackParams = p.playbackParams.setSpeed(speed)
        } catch (_: Exception) {
            // 没准备好时调倍速会抛异常，等准备好再调
        }
    }

    fun cycleSpeed() {
        val idx = SPEEDS.indexOf(speed)
        speed = SPEEDS[(idx + 1).mod(SPEEDS.size)]
        applySpeed()
    }

    fun cycleMode() {
        val values = PlayMode.values()
        mode = values[(values.indexOf(mode) + 1).mod(values.size)]
    }

    /**
     * 播音频前先停掉听书：听书和音频不能同时出声（双向互停的另一半）。
     * 不会循环：TtsReader.stop() 只改 TtsReader 自己的状态（停 TTS 引擎、
     * 清朗读状态、撤通知），不会回头调 AudioPlayer；反过来
     * TtsReader.startReading() 里调 AudioPlayer.stopPlayback() 也只改
     * AudioPlayer 自己的状态。两边都是单向的状态清理，没有互相触发的回调。
     */
    private fun stopTtsIfReading() {
        if (TtsReader.isActive) TtsReader.stop()
    }

    /**
     * 播音频前先停掉视频：音频和视频不能同时出声（和停听书同一个道理）。
     * 不会循环：VideoPlayer.stopPlayback() 只改 VideoPlayer 自己的状态
     * （停播放器、清状态、撤通知），不会回头调 AudioPlayer；反过来
     * VideoPlayer 开始出声时停 AudioPlayer 也只改 AudioPlayer 自己的状态。
     * 两边都只在“真正要出声”那一刻（startPlayback / resume / next 单曲重播）
     * 停对方，prepare（只准备不播）时不触发。
     */
    private fun stopVideoIfPlaying() {
        if (VideoPlayer.isPlaying) VideoPlayer.stopPlayback()
    }

    /** 开始播一首。同一首且已准备好时不重复准备，直接恢复。 */
    fun play(context: Context, item: CatalogItem, source: String, offline: Boolean, autoStart: Boolean = true, headers: Map<String, String> = emptyMap()) {
        appContext = context.applicationContext
        if (currentItem?.id == item.id && player != null && status != "准备中…" && status != "已停止") {
            if (autoStart) resume()
            return
        }
        currentItem = item
        currentSource = source
        status = "准备中…"
        isPlaying = false
        isBuffering = false
        positionMs = 0
        durationMs = 0
        val p = playerOrCreate()
        try {
            p.reset()
            // 远程链接要带登录凭证（目录和文件现在都要登录才能拿）；
            // 本地文件不走这条，直接用路径
            if (source.startsWith("http") && headers.isNotEmpty()) {
                p.setDataSource(context, Uri.parse(source), headers)
            } else {
                p.setDataSource(source)
            }
            p.setOnPreparedListener {
                durationMs = it.duration
                status = if (offline) "已就绪（离线播放）" else "已就绪"
                applySpeed()
                if (autoStart) startPlayback() else refreshNotification()
            }
            p.setOnErrorListener { _, _, _ ->
                status = "加载失败，请检查网络"
                isPlaying = false
                isBuffering = false
                reportMessage("audio-playback", item.title)
                refreshNotification()
                true
            }
            p.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> { isBuffering = true; true }
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> { isBuffering = false; true }
                    else -> false
                }
            }
            p.setOnCompletionListener { onTrackEnded() }
            p.prepareAsync()
        } catch (_: Exception) {
            status = "加载失败，请检查网络"
            reportMessage("audio-playback", item.title)
        }
        refreshNotification()
    }

    private fun startPlayback() {
        // 真正要出声了才停听书和视频：只打开远程音频页看看（autoStart=false，只准备不播）时别停别的声音
        stopTtsIfReading()
        stopVideoIfPlaying()
        try {
            playerOrCreate().start()
        } catch (_: Exception) {
            status = "播放失败，请重试"
            currentItem?.let { reportMessage("audio-playback", it.title) }
            return
        }
        isPlaying = true
        status = "正在播放"
        startService()
    }

    fun resume() {
        val p = player ?: return
        if (currentItem == null) return
        // 从通知栏 / 迷你条点“继续”也会走到这里：先停听书和视频
        stopTtsIfReading()
        stopVideoIfPlaying()
        try {
            if (status == "已播完") {
                p.seekTo(0)
                positionMs = 0
            }
            p.start()
        } catch (_: Exception) {
            status = "播放失败，请重试"
            currentItem?.let { reportMessage("audio-playback", it.title) }
            return
        }
        isPlaying = true
        status = "正在播放"
        startService()
    }

    fun pause() {
        try {
            player?.pause()
        } catch (_: Exception) {
            currentItem?.let { reportMessage("audio-pause", it.title) }
        }
        isPlaying = false
        isBuffering = false
        if (status == "正在播放") status = "已暂停"
        refreshNotification()
    }

    /** 播放键：准备中不理会（等准备好）；失败时重试；播着就暂停；停着就恢复；换首就从头播 */
    fun toggle(context: Context, item: CatalogItem, source: String, offline: Boolean) {
        if (status == "准备中…") return
        if (status == "加载失败，请检查网络" || status == "播放失败，请重试" || status == "已停止") {
            // 失败、或通知栏里点了“停止”：重新准备再播
            play(context, item, source, offline, autoStart = true)
            return
        }
        if (currentItem?.id != item.id) {
            play(context, item, source, offline, autoStart = true)
            return
        }
        if (isPlaying) pause() else resume()
    }

    fun seekTo(ms: Int) {
        positionMs = ms
        try {
            player?.seekTo(ms)
        } catch (_: Exception) {
        }
    }

    fun refreshPosition() {
        try {
            positionMs = player?.currentPosition ?: positionMs
        } catch (_: Exception) {
        }
    }

    /** 下一首按钮：单曲循环时从头重播，其余按模式在同栏目里切歌并自动开始播 */
    fun next() {
        val item = currentItem ?: return
        if (mode == PlayMode.SINGLE || queue.size <= 1) {
            // 单曲循环 / 只有一首：直接从头播，不走 play()，这里也要先停听书和视频
            // （player 为空时根本播不起来，就别停别的声音）
            if (player != null) {
                stopTtsIfReading()
                stopVideoIfPlaying()
            }
            try {
                player?.seekTo(0)
                player?.start()
            } catch (_: Exception) {
            }
            isPlaying = true
            positionMs = 0
            status = "正在播放"
            startService()
            return
        }
        val idx = queue.indexOfFirst { it.id == item.id }
        val next = when (mode) {
            PlayMode.SHUFFLE -> queue.filter { it.id != item.id }.random()
            else -> queue[(idx + 1).mod(queue.size)]
        }
        startNext(next)
    }

    private fun onTrackEnded() {
        val item = currentItem ?: return
        when (mode) {
            PlayMode.SINGLE -> {
                try {
                    player?.seekTo(0)
                    player?.start()
                    isPlaying = true
                    positionMs = 0
                    status = "正在播放"
                } catch (_: Exception) {
                    isPlaying = false
                    positionMs = 0
                    status = "已播完"
                }
                refreshNotification()
            }
            PlayMode.ORDER -> {
                val idx = queue.indexOfFirst { it.id == item.id }
                if (idx >= 0 && idx < queue.size - 1) {
                    startNext(queue[idx + 1])
                } else {
                    isPlaying = false
                    positionMs = 0
                    status = "已播完"
                    stopService()
                }
            }
            PlayMode.LOOP_ALL, PlayMode.SHUFFLE -> next()
        }
    }

    private fun startNext(next: CatalogItem) {
        val ctx = appContext
        if (ctx != null) {
            // 详情页不可见时只能用远程地址继续播；可见时页面换路由后会再用本地地址播一次
            play(ctx, next, next.url, offline = false, autoStart = true)
        }
        onCurrentChanged?.invoke(next)
    }

    // ---- 前台 Service / 通知栏 ----

    private fun startService() {
        val ctx = appContext ?: return
        try {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, PlaybackService::class.java).setAction(ACTION_UPDATE)
            )
        } catch (_: Exception) {
        }
    }

    private fun refreshNotification() {
        // 只有播过、Service 起过之后才刷新；没播过就不起 Service，避免只打开页面就弹通知
        if (currentItem == null) return
        startService()
    }

    private fun stopService() {
        val ctx = appContext ?: return
        try {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        } catch (_: Exception) {
        }
    }

    /** 通知栏“停止”：停掉播放并撤掉通知 */
    fun stopPlayback() {
        try {
            player?.reset()
        } catch (_: Exception) {
        }
        isPlaying = false
        isBuffering = false
        positionMs = 0
        status = "已停止"
        stopService()
    }

    fun buildNotification(context: Context): Notification {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "正在播放", NotificationManager.IMPORTANCE_LOW)
            channel.description = "音频在后台播放时显示控制"
            manager.createNotificationChannel(channel)
        }
        val item = currentItem
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            context, 1,
            Intent(context, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context, 2,
            Intent(context, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(item?.title ?: "城光")
            .setContentText(if (isPlaying) "正在播放 · ${item?.category ?: ""}" else "已暂停 · ${item?.category ?: ""}")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "暂停" else "播放",
                toggleIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .build()
    }
}
