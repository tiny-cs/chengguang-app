package com.maihe.chengguang

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Build
import android.view.SurfaceHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 视频后台播放：播放器放在进程级单例里，和 AudioPlayer 一个模式。
 * 离开视频详情页只出声、画面摘掉；回主界面底部有迷你条（MiniPlayerBar），
 * 点小条回到视频页，画面从当前位置继续接上，不重播、不叠音。
 *
 * 画面挂接：VideoDetail 里是 SurfaceView，surface 建好时调 attachSurface、
 * surface 销毁时调 detachSurface。detach 只 setDisplay(null)，不 stop
 * 不 release，声音继续；回来 surface 再建好就 setDisplay，画面直接恢复。
 * MediaPlayer 的画面和声音是分开的：没接画面也能出声，接上画面就接着显示。
 *
 * 互停（只能有一个出声）：真正要出声的那一刻才停对方——
 * startPlayback() / resume() / next() 单曲重播时停 AudioPlayer 和 TtsReader；
 * 反过来 AudioPlayer 开始出声时停 VideoPlayer，TtsReader 开始朗读时停
 * VideoPlayer。停只是单向的状态清理（stopPlayback 不回头调对方），不会循环。
 *
 * 熄屏：ACTION_SCREEN_OFF 时如果在播就暂停并记下来，ACTION_SCREEN_ON 时
 * 自动续播；音频和听书熄屏继续播，不动。按 Home 不走这条路（画中画照旧）。
 *
 * 注意：倍速和播放模式也是进程级的，换一首不会重置（和 AudioPlayer 一样）。
 */
object VideoPlayer {
    const val ACTION_TOGGLE = "com.maihe.chengguang.VIDEO_ACTION_TOGGLE"
    const val ACTION_STOP = "com.maihe.chengguang.VIDEO_ACTION_STOP"

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
    /** 视频实际尺寸：画中画比例、画面宽高比都用它；没准备好时是 0 */
    var videoWidth by mutableIntStateOf(0)
        private set
    var videoHeight by mutableIntStateOf(0)
        private set

    private var player: MediaPlayer? = null
    private var appContext: Context? = null
    /** 当前接上的画面（SurfaceView 的 holder）：页面在时才有；离开页面就摘掉 */
    private var surfaceHolder: SurfaceHolder? = null

    /** 同栏目视频列表（切歌用），详情页在可见时设置 */
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

    /** 播放模式只转三个：顺序播放 / 单曲循环 / 列表循环（视频不用随机播放） */
    fun cycleMode() {
        mode = when (mode) {
            PlayMode.ORDER -> PlayMode.SINGLE
            PlayMode.SINGLE -> PlayMode.LOOP_ALL
            else -> PlayMode.ORDER
        }
    }

    /**
     * 接画面：SurfaceView 的 surface 建好时调。播放器已经在播就直接恢复画面，
     * 不 seek、不重建；还没准备好就先记下 holder，play() 里 setDisplay。
     */
    fun attachSurface(holder: SurfaceHolder) {
        surfaceHolder = holder
        try {
            player?.setDisplay(holder)
        } catch (_: Exception) {
        }
    }

    /**
     * 摘画面：surface 销毁、离开视频页时调。只 setDisplay(null)，不 stop
     * 不 release——声音继续播，回来再接画面就行。
     */
    fun detachSurface() {
        surfaceHolder = null
        try {
            player?.setDisplay(null)
        } catch (_: Exception) {
        }
    }

    // ---- 熄屏：播着就暂停，亮屏自动续播 ----

    private var screenReceiverRegistered = false
    private var pausedByScreenOff = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (isPlaying) {
                        pausedByScreenOff = true
                        pause()
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    // 只有“熄屏时被我停掉、现在还是已暂停”才续播：中间用户要是从
                    // 锁屏通知栏点了停止，状态是“已停止”，就别再把它叫起来
                    if (pausedByScreenOff && status == "已暂停") {
                        pausedByScreenOff = false
                        resume()
                    } else {
                        pausedByScreenOff = false
                    }
                }
            }
        }
    }

    private fun ensureScreenReceiver(context: Context) {
        if (screenReceiverRegistered) return
        screenReceiverRegistered = true
        try {
            ContextCompat.registerReceiver(
                context.applicationContext,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
            screenReceiverRegistered = false
        }
    }

    /**
     * 播视频前先停掉音频和听书：视频和音频、听书不能同时出声。
     * 不会循环：AudioPlayer.stopPlayback() / TtsReader.stop() 都只改自己的状态，
     * 不会回头调 VideoPlayer。
     */
    private fun stopOthersIfPlaying() {
        if (AudioPlayer.isPlaying) AudioPlayer.stopPlayback()
        if (TtsReader.isActive) TtsReader.stop()
    }

    /** 开始播一个视频。同一条且已准备好时不重复准备，直接恢复。 */
    fun play(context: Context, item: CatalogItem, source: String, offline: Boolean, autoStart: Boolean = true) {
        appContext = context.applicationContext
        ensureScreenReceiver(context)
        pausedByScreenOff = false
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
        videoWidth = 0
        videoHeight = 0
        val p = playerOrCreate()
        try {
            p.reset()
            p.setDataSource(source)
            // 画面已经在就接上；还没接上（在主界面切歌）就先不接，等回来再接
            surfaceHolder?.let { holder ->
                try { p.setDisplay(holder) } catch (_: Exception) {}
            }
            p.setOnPreparedListener {
                durationMs = it.duration
                videoWidth = it.videoWidth
                videoHeight = it.videoHeight
                status = if (offline) "已就绪（离线播放）" else "已就绪"
                applySpeed()
                if (autoStart) startPlayback() else refreshNotification()
            }
            p.setOnErrorListener { _, _, _ ->
                status = "加载失败，请检查网络"
                isPlaying = false
                isBuffering = false
                reportMessage("video-playback", item.title)
                refreshNotification()
                true
            }
            p.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> { isBuffering = true; true }
                    MediaPlayer.MEDIA_INFO_BUFFERING_END,
                    MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> { isBuffering = false; true }
                    else -> false
                }
            }
            p.setOnCompletionListener { onTrackEnded() }
            p.prepareAsync()
        } catch (_: Exception) {
            status = "加载失败，请检查网络"
            reportMessage("video-playback", item.title)
        }
        refreshNotification()
    }

    private fun startPlayback() {
        // 真正要出声了才停音频和听书：只打开远程视频页看看（autoStart=false，
        // 只准备不播）时别停别的声音
        stopOthersIfPlaying()
        try {
            playerOrCreate().start()
        } catch (_: Exception) {
            status = "播放失败，请重试"
            currentItem?.let { reportMessage("video-playback", it.title) }
            return
        }
        isPlaying = true
        status = "正在播放"
        startService()
    }

    fun resume() {
        val p = player ?: return
        if (currentItem == null) return
        // 从通知栏 / 迷你条点“继续”也会走到这里：先停音频和听书
        stopOthersIfPlaying()
        try {
            if (status == "已播完") {
                p.seekTo(0)
                positionMs = 0
            }
            p.start()
        } catch (_: Exception) {
            status = "播放失败，请重试"
            currentItem?.let { reportMessage("video-playback", it.title) }
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
            currentItem?.let { reportMessage("video-pause", it.title) }
        }
        isPlaying = false
        isBuffering = false
        if (status == "正在播放") status = "已暂停"
        refreshNotification()
    }

    /** 播放键：准备中不理会（等准备好）；失败时重试；播着就暂停；停着就恢复；换条就从头播 */
    fun toggle(context: Context, item: CatalogItem, source: String, offline: Boolean) {
        if (status == "准备中…") return
        if (status == "加载失败，请检查网络" || status == "播放失败，请重试" || status == "已停止") {
            // 失败、或通知栏/迷你条里点了“停止”：重新准备再播
            play(context, item, source, offline, autoStart = true)
            return
        }
        if (currentItem?.id != item.id) {
            play(context, item, source, offline, autoStart = true)
            return
        }
        if (isPlaying) pause() else resume()
    }

    /** 画中画里的播放/暂停按钮点的就是这个：播着就暂停，停着就继续；播到结尾了从头播。缓冲中 / 加载失败时不理会。 */
    fun togglePlayback() {
        if (status == "准备中…" || isBuffering) return
        if (status == "加载失败，请检查网络" || status == "播放失败，请重试" || status == "已停止") return
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

    /** 下一首按钮：单曲循环时从头重播，其余按模式在同栏目里切视频并自动开始播 */
    fun next() {
        val item = currentItem ?: return
        if (mode == PlayMode.SINGLE || queue.size <= 1) {
            // 单曲循环 / 只有一条：直接从头播，不走 play()，这里也要先停音频和听书
            // （player 为空时根本播不起来，就别停别的声音）
            if (player != null) stopOthersIfPlaying()
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
        val next = queue[(idx + 1).mod(queue.size)]
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
            PlayMode.LOOP_ALL -> next()
            else -> next() // SHUFFLE 不用于视频，万一进来也按列表循环走
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

    // ---- 前台 Service / 通知栏（和 AudioPlayer 同一个 Service、同一个通知位） ----

    private fun startService() {
        val ctx = appContext ?: return
        try {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, PlaybackService::class.java).setAction(AudioPlayer.ACTION_UPDATE)
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

    /** 通知栏 / 迷你条“停止”：停掉播放并撤掉通知 */
    fun stopPlayback() {
        try {
            player?.reset()
        } catch (_: Exception) {
        }
        isPlaying = false
        isBuffering = false
        positionMs = 0
        pausedByScreenOff = false
        status = "已停止"
        stopService()
    }

    fun buildNotification(context: Context): Notification {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AudioPlayer.CHANNEL_ID, "正在播放", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "音频和视频在后台播放时显示控制"
            manager.createNotificationChannel(channel)
        }
        val item = currentItem
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            context, 3,
            Intent(context, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context, 4,
            Intent(context, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, AudioPlayer.CHANNEL_ID)
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
