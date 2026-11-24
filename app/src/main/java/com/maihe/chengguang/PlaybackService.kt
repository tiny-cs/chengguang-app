package com.maihe.chengguang

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** 前台 Service：只负责保活和通知栏控制，播放器在 AudioPlayer / VideoPlayer 单例里。 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户把 App 从最近任务里划掉：停掉音频、视频和听书，别让 App 没了还在后台出声。
     * 注意：按 Home 键退到桌面、按返回键退到桌面都不会调到这里，任务还在，
     * 音频 / 视频照常继续播——只有“划掉 App”才停，这正是要的效果。
     * 不动 onStartCommand 的 START_STICKY / 重启后自停逻辑。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AudioPlayer.stopPlayback()
        VideoPlayer.stopPlayback()
        if (TtsReader.isActive) TtsReader.stop()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 整个进程被杀后系统按 START_STICKY 重启：这时播放器和听书状态都已丢，别留一个点不动的死通知
        if (AudioPlayer.currentItem == null && VideoPlayer.currentItem == null && !TtsReader.isActive) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            AudioPlayer.ACTION_TOGGLE -> {
                if (AudioPlayer.isPlaying) AudioPlayer.pause() else AudioPlayer.resume()
            }
            AudioPlayer.ACTION_STOP -> {
                AudioPlayer.stopPlayback()
                stopSelf()
                return START_NOT_STICKY
            }
            VideoPlayer.ACTION_TOGGLE -> {
                if (VideoPlayer.isPlaying) VideoPlayer.pause() else VideoPlayer.resume()
            }
            VideoPlayer.ACTION_STOP -> {
                VideoPlayer.stopPlayback()
                stopSelf()
                return START_NOT_STICKY
            }
            TtsReader.ACTION_TTS_TOGGLE -> {
                if (TtsReader.isReading) TtsReader.pause() else TtsReader.resume()
            }
            TtsReader.ACTION_TTS_STOP -> {
                TtsReader.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // ACTION_UPDATE 或空 intent：刷新通知并保活；听书在进行时显示听书的通知，
        // 其次是谁在出声（或有东西可管）就显示谁的通知——和迷你条同一条规则，
        // 音视频不会同时播，所以一个通知位够用
        val notification =
            if (TtsReader.isActive) TtsReader.buildNotification(this)
            else if (resolveActivePlayer() == ActivePlayer.VIDEO) VideoPlayer.buildNotification(this)
            else AudioPlayer.buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                AudioPlayer.NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(AudioPlayer.NOTIF_ID, notification)
        }
        return START_STICKY
    }
}
