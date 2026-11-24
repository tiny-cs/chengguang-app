package com.maihe.chengguang

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// 给开发者建议：发到管理后台 /api/feedback，存在 R2 的 feedback.json 里，
// 只有管理员登录后台才能看到。读者不用登录，匿名也能发，可选留联系方式。
const val FEEDBACK_URL = "https://admin.maihe334.ccwu.cc/api/feedback"
const val MAX_FEEDBACK_CHARS = 2000

/**
 * 提交一条建议。成功返回 null，失败返回给用户看的错误原因。
 * 联系方式可空；正文 1~2000 字，超了由后台再校验一次。
 */
suspend fun submitFeedback(text: String, contact: String): String? {
    val body = text.trim()
    if (body.isEmpty()) return "请先写一点建议再提交"
    if (body.length > MAX_FEEDBACK_CHARS) return "建议太长了，请控制在 2000 字以内"
    return withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(FEEDBACK_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            val payload = JSONObject().apply {
                put("text", body)
                put("contact", contact.trim())
            }.toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            val code = conn.responseCode
            val resp = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            } catch (_: Exception) {
                ""
            }
            if (code in 200..299 && JSONObject(resp).optBoolean("ok", false)) {
                null
            } else {
                try {
                    JSONObject(resp).optString("error", "").ifEmpty { "提交失败，请检查网络后重试" }
                } catch (_: Exception) {
                    "提交失败，请检查网络后重试"
                }
            }
        } catch (_: Exception) {
            "提交失败，请检查网络后重试"
        } finally {
            conn?.disconnect()
        }
    }
}
