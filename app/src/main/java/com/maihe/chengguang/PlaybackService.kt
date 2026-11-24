package com.maihe.chengguang

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** 前台 Service：只负责保活和通知栏控制，播放器在 AudioPlayer 单例里。 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 整个进程被杀后系统按 START_STICKY 重启：这时播放器状态已丢，别留一个点不动的死通知
        if (AudioPlayer.currentItem == null) {
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
        }
        // ACTION_UPDATE 或空 intent：刷新通知并保活
        val notification = AudioPlayer.buildNotification(this)
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
