package com.maihe.chengguang

import android.content.Context
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid

/**
 * 报错收集（Sentry）。组织 maihe（和麦禾 VPN Windows 版同一个 Sentry 账号），
 * 项目 chengguang-android（待建，见 README 的 Sentry 一节）。
 *
 * DSN 不写死在代码里：构建时从 Gradle 属性 sentryDsn 或环境变量 SENTRY_DSN 注入
 * （见 app/build.gradle.kts 的 buildConfigField）。DSN 为空时 Sentry 不初始化，
 * App 照常用，什么都不发。正式启用：建好 Sentry 项目拿到 DSN 后，
 * 在 GitHub 仓库加 Secret SENTRY_DSN，并在工作流里构建时传入 -PsentryDsn=$SENTRY_DSN。
 *
 * 只发报错堆栈，不发内容、链接、账号信息：
 * - beforeSend 里把消息和异常值里的链接、UUID、文件路径替换掉；不采集默认个人信息；
 * - 上报点只传标题和错误类型，从不传 item.url（R2 链接）和任何账号字段。
 */
object ErrorReporting {
    private val uuidRegex = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val urlRegex = Regex("https?://\\S+")
    private val filePathRegex = Regex("(/[A-Za-z0-9._-]+)+\\.(mp3|mp4|m4a|pdf|json|apk)")
    private val windowsPathRegex = Regex("[A-Za-z]:\\\\[^\\s\"']+")

    fun scrub(text: String?): String? {
        if (text == null) return null
        return text
            .replace(uuidRegex, "[UUID]")
            .replace(urlRegex, "[链接]")
            .replace(filePathRegex, "[文件]")
            .replace(windowsPathRegex, "[路径]")
    }

    fun init(context: Context) {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return // 没配 DSN：不初始化，不发任何东西
        try {
            SentryAndroid.init(context) { options ->
                options.dsn = dsn
                options.tracesSampleRate = 0.0 // 不采性能数据，只要报错
                options.isSendDefaultPii = false
                options.isEnableAutoSessionTracking = true
                // 注意：Sentry 8.x 的 SentryEvent 上没有 request / user / throwable
                // 这三个字段（7.x 有，8.x 删了），脱敏只处理实际存在的 message 和 exceptions。
                options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
                    try {
                        event.message?.let { it.formatted = scrub(it.formatted) }
                        event.exceptions?.forEach { ex ->
                            ex.value?.let { v -> ex.value = scrub(v) }
                        }
                    } catch (_: Exception) {
                        // 脱敏本身出错也别挡住上报
                    }
                    event
                }
            }
        } catch (_: Exception) {
            // Sentry 初始化失败只影响报错收集，绝不能影响 App 启动
        }
    }

    /** 上报一条带标题的错误消息（不含链接、URL、账号）。 */
    fun reportMessage(area: String, title: String) {
        try {
            Sentry.withScope { scope ->
                scope.setTag("area", area)
                Sentry.captureMessage("[$area] $title", SentryLevel.ERROR)
            }
        } catch (_: Exception) {
        }
    }

    /** 上报一个异常（只发类型和脱敏后的消息）。 */
    fun reportError(area: String, e: Throwable) {
        try {
            Sentry.withScope { scope ->
                scope.setTag("area", area)
                Sentry.captureException(e)
            }
        } catch (_: Exception) {
        }
    }
}

/** 供 Catalog.kt / Downloads.kt 等直接调用的顶层函数。 */
fun reportError(area: String, e: Throwable) = ErrorReporting.reportError(area, e)
fun reportMessage(area: String, title: String) = ErrorReporting.reportMessage(area, title)
