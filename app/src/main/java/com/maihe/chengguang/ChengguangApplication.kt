package com.maihe.chengguang

import android.app.Application

/**
 * 应用入口：启动时初始化 Sentry（DSN 为空时不初始化，App 照常用）。
 * 闪退由 Sentry SDK 自动捕获，不需要各处再写。
 */
class ChengguangApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorReporting.init(this)
    }
}
