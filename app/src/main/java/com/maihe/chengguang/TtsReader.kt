package com.maihe.chengguang

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 读书器 TTS 听书（Phase 2 第 1 块）。
 * 用系统 TextToSpeech 朗读当前这本书的正文，不用加新依赖。
 *
 * 做法：整本书先按中文标点切成一句一句（PlanItem），一次只往 TTS 队列里
 * 加一句（QUEUE_ADD）；上一句读完（onDone）再加下一句。这样暂停/恢复、
 * 换语速/换音色都能从当前这句接着来，也不会一次性把整本书塞进队列。
 *
 * 跟读高亮：每句的 utterance ID 是 p{段落号}s{句号}，onStart 时解析出来，
 * 读书页就知道当前读到第几段第几句，标金色底并滚过去。
 *
 * 后台播放：朗读状态放在进程级单例里，和 AudioPlayer 一样走 PlaybackService
 * 前台保活 + 通知栏（暂停/继续/停止）。开始听书时先停掉正在播的音频。
 *
 * 注意：UtteranceProgressListener 的回调不在主线程，改 Compose 状态、
 * 调 tts.speak 之前都要先切回主线程（mainHandler.post）。
 */

// ---------- 分句 ----------

/** 一句：文本 + 它在段落里的字符范围（跟读高亮用）。 */
data class SentenceSpan(val text: String, val start: Int, val end: Int)

private val SENTENCE_END = setOf('。', '！', '？', '；', '…', '\n')

/** 按中文标点（。！？；…）和换行把一段切成句子，标点留在句尾；“……”算一句的结尾。 */
fun splitParagraph(text: String): List<SentenceSpan> {
    val spans = mutableListOf<SentenceSpan>()
    var start = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c in SENTENCE_END) {
            var end = i + 1
            while (end < text.length && text[end] == '…') end++
            addSpan(text, spans, start, end)
            start = end
            i = end
        } else {
            i++
        }
    }
    if (start < text.length) addSpan(text, spans, start, text.length)
    // 太长的句子按逗号/顿号/冒号再切（单句太长 TTS 容易失败）
    return spans.flatMap { splitLongSpan(text, it) }
}

private fun addSpan(text: String, out: MutableList<SentenceSpan>, start: Int, end: Int) {
    val raw = text.substring(start, end)
    val lead = raw.indexOfFirst { !it.isWhitespace() }
    if (lead < 0) return
    val trail = raw.indexOfLast { !it.isWhitespace() }
    out.add(SentenceSpan(raw.substring(lead, trail + 1), start + lead, start + trail + 1))
}

private fun splitLongSpan(text: String, span: SentenceSpan, maxLen: Int = 400): List<SentenceSpan> {
    if (span.text.length <= maxLen) return listOf(span)
    val out = mutableListOf<SentenceSpan>()
    var start = span.start
    var i = span.start
    val end = span.end
    while (i < end) {
        val c = text[i]
        if ((c == '，' || c == '、' || c == '：' || c == '；') && i - start + 1 >= 100) {
            addSpan(text, out, start, i + 1)
            start = i + 1
        }
        i++
    }
    if (start < end) addSpan(text, out, start, end)
    return out.ifEmpty { listOf(span) }
}

// ---------- TTS 单例 ----------

object TtsReader {
    const val ACTION_TTS_UPDATE = "com.maihe.chengguang.ACTION_TTS_UPDATE"
    const val ACTION_TTS_TOGGLE = "com.maihe.chengguang.ACTION_TTS_TOGGLE"
    const val ACTION_TTS_STOP = "com.maihe.chengguang.ACTION_TTS_STOP"

    // 语速：滑杆 0.5x–4.0x，以 0.1 为一格（显示一位小数）
    const val SPEED_MIN = 0.5f
    const val SPEED_MAX = 4f
    const val SPEED_STEP = 0.1f
    val SLEEP_OPTIONS = listOf(0, 15, 30, 60)
    val SLEEP_LABELS = listOf("不限", "15分钟", "30分钟", "60分钟")

    var currentBook by mutableStateOf<CatalogItem?>(null)
        private set
    var status by mutableStateOf("未开始")
        private set
    var isReading by mutableStateOf(false)
        private set
    var isActive by mutableStateOf(false)
        private set
    var isReady by mutableStateOf(false)
        private set
    /** 引擎就绪但没有中文语音：界面显示提示，不闪退 */
    var noChineseVoice by mutableStateOf(false)
        private set
    /** 跟读位置：当前读到第几段第几句（-1 表示没有在读） */
    var currentPara by mutableIntStateOf(-1)
        private set
    var currentSent by mutableIntStateOf(-1)
        private set
    var speed by mutableStateOf(1f)
        private set
    /** 选中的中文语音名；空表示系统默认 */
    var voiceName by mutableStateOf("")
        private set
    /** 定时停止（分钟）；0 表示不限 */
    var sleepMinutes by mutableIntStateOf(0)
        private set
    var chineseVoices by mutableStateOf<List<Voice>>(emptyList())
        private set

    private data class PlanItem(val para: Int, val sent: Int, val text: String)

    private var tts: TextToSpeech? = null
    private var prefsLoaded = false
    private var appContext: Context? = null
    private var pendingStart: (() -> Unit)? = null
    private var plan: List<PlanItem> = emptyList()
    private var planIndex = 0
    private var consecutiveErrors = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    fun speedLabel(): String = "%.1fx".format(Locale.US, speed)

    fun sleepLabel(): String =
        if (sleepMinutes <= 0) "定时：不限" else "定时：${sleepMinutes}分钟"

    // ---------- 设置（存在 "chengguang_reader"，和排版设置同一个 SharedPreferences） ----------

    private fun loadPrefs(context: Context) {
        if (prefsLoaded) return
        prefsLoaded = true
        val prefs = context.getSharedPreferences("chengguang_reader", Context.MODE_PRIVATE)
        speed = normalizeSpeed(prefs.getFloat("tts_speed", 1f))
        voiceName = prefs.getString("tts_voice_name", "") ?: ""
        sleepMinutes = prefs.getInt("tts_sleep_minutes", 0).let { if (it in SLEEP_OPTIONS) it else 0 }
    }

    /** 语速归一化到 0.1 的格子并夹在 0.5x–4.0x 内（旧档位 0.75/1.25 也会落到最近的格子上）。 */
    private fun normalizeSpeed(value: Float): Float =
        ((value / SPEED_STEP).roundToInt() * SPEED_STEP).coerceIn(SPEED_MIN, SPEED_MAX)

    // 注意：不能叫 setSpeed——var speed 自带的 setter 已经占了 setSpeed(F)V 这个 JVM 签名，同名函数会编译冲突
    fun updateSpeed(value: Float) {
        val v = normalizeSpeed(value)
        speed = v
        appContext?.getSharedPreferences("chengguang_reader", Context.MODE_PRIVATE)
            ?.edit()?.putFloat("tts_speed", v)?.apply()
        applyVoiceAndRate()
        // 正在读就从当前这句重读，语速马上生效
        if (isReading) restartCurrent()
    }

    fun setVoice(name: String) {
        voiceName = name
        appContext?.getSharedPreferences("chengguang_reader", Context.MODE_PRIVATE)
            ?.edit()?.putString("tts_voice_name", name)?.apply()
        applyVoiceAndRate()
        if (isReading) restartCurrent()
    }

    // 同上：不能叫 setSleepMinutes，var sleepMinutes 自带的 setter 已占了这个签名
    fun updateSleepMinutes(minutes: Int) {
        val value = if (minutes in SLEEP_OPTIONS) minutes else 0
        sleepMinutes = value
        appContext?.getSharedPreferences("chengguang_reader", Context.MODE_PRIVATE)
            ?.edit()?.putInt("tts_sleep_minutes", value)?.apply()
        // 正在读就从现在重新计时
        if (isReading) scheduleSleep()
    }

    private fun applyVoiceAndRate() {
        val engine = tts ?: return
        try {
            engine.setSpeechRate(speed)
        } catch (_: Exception) {
        }
        try {
            val selected = chineseVoices.find { it.name == voiceName }
            if (selected != null) engine.voice = selected
        } catch (_: Exception) {
        }
    }

    // ---------- 引擎初始化（异步） ----------

    /** 引擎没建好就建一个；已建好/正在建就直接返回，建好后会跑 pendingStart。 */
    fun ensureInit(context: Context) {
        loadPrefs(context)
        appContext = context.applicationContext
        if (isReady || tts != null) return
        tts = TextToSpeech(appContext) { result ->
            // OnInitListener 回调在主线程
            if (result != TextToSpeech.SUCCESS) {
                // 先记下书名再 failInit：failInit 会把 currentBook 置空，之后就报不上去了
                val title = currentBook?.title
                failInit("朗读失败，请重试")
                title?.let { reportMessage("tts-reading", it) }
                return@TextToSpeech
            }
            val langResult = tts?.setLanguage(Locale.CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                noChineseVoice = true
                failInit("没有中文语音")
                return@TextToSpeech
            }
            val voices = try {
                tts?.voices?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            chineseVoices = voices.filter { it.locale.language == "zh" }
            if (chineseVoices.isEmpty()) {
                noChineseVoice = true
                failInit("没有中文语音")
                return@TextToSpeech
            }
            noChineseVoice = false
            applyVoiceAndRate()
            tts?.setOnUtteranceProgressListener(progressListener)
            isReady = true
            status = "已就绪"
            pendingStart?.invoke()
            pendingStart = null
        }
    }

    /** 初始化失败：收起听书状态，下次点听书会重新建引擎再试一次。 */
    private fun failInit(message: String) {
        isActive = false
        currentBook = null
        plan = emptyList()
        planIndex = 0
        status = message
        tts = null
        pendingStart = null
    }

    // ---------- 朗读控制 ----------

    /**
     * 开始听书：从 startPara 那段往后读。
     * 同一本书、暂停中：直接恢复接着读，不重头来。
     */
    fun startReading(context: Context, item: CatalogItem, paragraphs: List<String>, startPara: Int) {
        loadPrefs(context)
        appContext = context.applicationContext
        // 同一本书、暂停中：恢复接着读
        if (currentBook?.id == item.id && isActive && !isReading && plan.isNotEmpty()) {
            resume()
            return
        }
        // 停掉正在播的音频，避免两个声音打架
        if (AudioPlayer.isPlaying) AudioPlayer.stopPlayback()
        currentBook = item
        plan = paragraphs.flatMapIndexed { pIdx, para ->
            splitParagraph(para).mapIndexed { sIdx, span -> PlanItem(pIdx, sIdx, span.text) }
        }
        planIndex = plan.indexOfFirst { it.para >= startPara }.takeIf { it >= 0 } ?: 0
        currentPara = -1
        currentSent = -1
        isActive = true
        status = "正在准备…"
        if (!isReady) {
            pendingStart = { beginSpeaking() }
            ensureInit(context)
        } else {
            beginSpeaking()
        }
    }

    private fun beginSpeaking() {
        if (!isActive || currentBook == null) return
        isReading = true
        status = "正在朗读"
        startService()
        scheduleSleep()
        speakCurrent()
    }

    /** 读 plan[planIndex] 这一句；读完后 onDone 会推进到下一句。 */
    private fun speakCurrent() {
        val item = currentBook ?: return
        if (planIndex >= plan.size) {
            // 整本读完了
            isReading = false
            status = "已读完"
            currentPara = -1
            currentSent = -1
            isActive = false
            cancelSleep()
            stopService()
            return
        }
        val next = plan[planIndex]
        currentPara = next.para
        currentSent = next.sent
        applyVoiceAndRate()
        try {
            tts?.speak(next.text, TextToSpeech.QUEUE_ADD, null, utteranceId(next))
        } catch (_: Exception) {
            status = "朗读失败，请重试"
            reportMessage("tts-reading", item.title)
        }
    }

    /** 当前这句重读一遍（换语速/换音色后马上生效用）。 */
    private fun restartCurrent() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        speakCurrent()
    }

    private fun utteranceId(item: PlanItem) = "p${item.para}s${item.sent}"

    private fun parseUtteranceId(id: String?): Pair<Int, Int>? {
        if (id.isNullOrEmpty()) return null
        val match = Regex("^p(\\d+)s(\\d+)$").matchEntire(id) ?: return null
        val p = match.groupValues[1].toIntOrNull() ?: return null
        val s = match.groupValues[2].toIntOrNull() ?: return null
        return p to s
    }

    fun pause() {
        if (!isReading) return
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        isReading = false
        status = "已暂停"
        refreshNotification()
    }

    fun resume() {
        if (!isActive || currentBook == null || isReading) return
        if (!isReady) {
            pendingStart = { beginSpeaking() }
            appContext?.let { ensureInit(it) }
            return
        }
        isReading = true
        status = "正在朗读"
        startService()
        scheduleSleep()
        speakCurrent()
    }

    fun togglePause() {
        if (isReading) pause() else resume()
    }

    /** 关闭：停止朗读，恢复正常阅读界面。 */
    fun stop() {
        stopInternal("未开始")
    }

    private fun stopInternal(finalStatus: String) {
        cancelSleep()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        isReading = false
        isActive = false
        currentBook = null
        currentPara = -1
        currentSent = -1
        plan = emptyList()
        planIndex = 0
        status = finalStatus
        stopService()
    }

    // ---------- 定时停止 ----------

    private fun scheduleSleep() {
        cancelSleep()
        if (sleepMinutes <= 0) return
        val runnable = Runnable { stopInternal("定时到，已停止") }
        sleepRunnable = runnable
        mainHandler.postDelayed(runnable, sleepMinutes * 60_000L)
    }

    private fun cancelSleep() {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
    }

    // ---------- 朗读进度回调（回调不在主线程，先切回主线程） ----------

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            mainHandler.post {
                val pos = parseUtteranceId(utteranceId)
                if (pos != null) {
                    currentPara = pos.first
                    currentSent = pos.second
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                if (!isReading) return@post
                consecutiveErrors = 0
                planIndex++
                speakCurrent()
            }
        }

        @Suppress("DEPRECATION")
        override fun onError(utteranceId: String?) {
            onSpeechError()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onSpeechError()
        }

        private fun onSpeechError() {
            mainHandler.post {
                if (!isReading) return@post
                consecutiveErrors++
                val title = currentBook?.title ?: ""
                if (consecutiveErrors >= 3) {
                    status = "朗读失败，请重试"
                    reportMessage("tts-reading", title)
                    stop()
                } else {
                    // 这一句读不出来就跳过，读下一句
                    planIndex++
                    speakCurrent()
                }
            }
        }
    }

    // ---------- 前台 Service / 通知栏（和 AudioPlayer 一样的模式） ----------

    private fun startService() {
        val ctx = appContext ?: return
        try {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, PlaybackService::class.java).setAction(ACTION_TTS_UPDATE)
            )
        } catch (_: Exception) {
        }
    }

    private fun refreshNotification() {
        if (!isActive) return
        startService()
    }

    private fun stopService() {
        val ctx = appContext ?: return
        try {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        } catch (_: Exception) {
        }
    }

    fun buildNotification(context: Context): Notification {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AudioPlayer.CHANNEL_ID, "正在播放", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "音频在后台播放时显示控制"
            manager.createNotificationChannel(channel)
        }
        val book = currentBook
        val openApp = PendingIntent.getActivity(
            context, 10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            context, 11,
            Intent(context, PlaybackService::class.java).setAction(ACTION_TTS_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context, 12,
            Intent(context, PlaybackService::class.java).setAction(ACTION_TTS_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, AudioPlayer.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(book?.title ?: "城光")
            .setContentText(if (isReading) "正在朗读 · 听书" else "已暂停 · 听书")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                if (isReading) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isReading) "暂停" else "继续",
                toggleIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .build()
    }
}
