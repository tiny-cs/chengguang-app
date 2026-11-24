package com.maihe.chengguang

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Rational

/**
 * 视频小窗口（画中画）的播放/暂停按钮。
 *
 * 做法：进小窗口时把一个 RemoteAction（播放/暂停）塞进 PictureInPictureParams；
 * 点按钮发一条广播，MainActivity 里动态注册的 Receiver 收到后调 onToggle。
 *
 * 为什么要这个持有者：VideoView 活在 VideoDetail 的 Composable 里，Receiver
 * 够不着它；所以 VideoDetail 可见时把“切播放/暂停”这个动作和“是不是在播”
 * 的读法注册到这里，离开视频页时清掉。只存回调，不存 VideoView 本身。
 */
object VideoPip {
    const val ACTION_TOGGLE = "com.maihe.chengguang.VIDEO_PIP_TOGGLE"

    /** VideoDetail 可见时注册：小窗口里的按钮点一下就调它；离开视频页时清掉。 */
    var onToggle: (() -> Unit)? = null

    /** VideoDetail 注册：读 VideoView 是不是在播，画按钮图标用。 */
    var getIsPlaying: (() -> Boolean)? = null

    /** MainActivity 里动态注册（只收自己 App 的广播，不出口）。 */
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TOGGLE) onToggle?.invoke()
        }
    }

    /** 播放/暂停按钮：播着显示暂停图标，停着显示播放图标（系统最多放 3 个，这里只要 1 个）。 */
    fun toggleAction(context: Context, isPlaying: Boolean): RemoteAction {
        val iconRes =
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val title = if (isPlaying) "暂停" else "播放"
        val intent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(Icon.createWithResource(context, iconRes), title, title, intent)
    }

    /** 进小窗口 / 刷新小窗口按钮时用的参数：比例 + 播放/暂停按钮。 */
    fun pipParams(context: Context, aspectRatio: Rational, isPlaying: Boolean): PictureInPictureParams {
        return PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setActions(listOf(toggleAction(context, isPlaying)))
            .build()
    }
}
