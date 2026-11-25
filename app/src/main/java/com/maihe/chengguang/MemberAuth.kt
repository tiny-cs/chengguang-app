package com.maihe.chengguang

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 管理后台的成员账号接口（和目录同一个域名）。 */
const val MEMBER_API_BASE = "https://admin.maihe334.ccwu.cc"

@Serializable
private data class MemberLoginResponse(val ok: Boolean = false, val username: String = "", val token: String = "", val error: String = "")

@Serializable
private data class MemberMeResponse(val ok: Boolean = false, val username: String = "", val error: String = "")

private val memberJson = Json { ignoreUnknownKeys = true }

/**
 * 成员登录（每人一个账号，由管理员在后台发放）。
 * token 存在 SharedPreferences "chengguang_member"，30 天有效；
 * 每次打开 App 时用 /api/member/me 校验一次：401 说明账号被停用或 token
 * 已失效，就回到登录页；只是网络不通时不踢人（缓存目录照常看，拉目录时
 * 再按 401 处理），免得在家里网差的时候一打开就被登出。
 */
class MemberAuth(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("chengguang_member", Context.MODE_PRIVATE)

    val token: String?
        get() = prefs.getString("token", null)?.takeIf { it.isNotBlank() }

    val username: String?
        get() = prefs.getString("username", null)?.takeIf { it.isNotBlank() }

    val isLoggedIn: Boolean
        get() = token != null

    /** 远程请求用的请求头：没登录就是空，不会把空 token 发出去。 */
    fun authHeaders(): Map<String, String> =
        token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    /** 登录：成功返回 null；失败返回给用户看的错误文案。 */
    suspend fun login(username: String, password: String): String? {
        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("$MEMBER_API_BASE/api/member/login").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                val body = "{\"username\":${memberJson.encodeToString(username)},\"password\":${memberJson.encodeToString(password)}}"
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val stream = if (conn.responseCode == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val data = try { memberJson.decodeFromString<MemberLoginResponse>(text) } catch (_: Exception) { null }
                if (conn.responseCode == HttpURLConnection.HTTP_OK && data?.ok == true && data.token.isNotBlank()) {
                    prefs.edit().putString("token", data.token).putString("username", data.username).apply()
                    null
                } else {
                    data?.error?.takeIf { it.isNotBlank() } ?: "账号或密码不对"
                }
            } catch (_: IOException) {
                "网络有问题，请检查网络后重试"
            } catch (e: Exception) {
                reportError("member-login", e)
                "登录失败，请重试"
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * 校验当前 token 是否还有效。
     * 200 返回 true；401 说明账号被停用或 token 失效，清掉本地凭证返回 false；
     * 网络不通或别的错误返回 true（没法确认就不踢人，拉目录时会再按 401 处理）。
     */
    suspend fun validate(): Boolean {
        val t = token ?: return false
        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("$MEMBER_API_BASE/api/member/me").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $t")
                }
                when (conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        val data = try { memberJson.decodeFromString<MemberMeResponse>(text) } catch (_: Exception) { null }
                        data?.ok == true
                    }
                    HttpURLConnection.HTTP_UNAUTHORIZED -> {
                        clearLocal()
                        false
                    }
                    else -> true
                }
            } catch (_: Exception) {
                true
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** 告诉服务器作废当前 token（失败也继续，清本地凭证不依赖它）。 */
    suspend fun logout() {
        val t = token
        if (t != null) {
            withContext(Dispatchers.IO) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL("$MEMBER_API_BASE/api/member/logout").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 10000
                        requestMethod = "POST"
                        setRequestProperty("Authorization", "Bearer $t")
                    }
                    conn.responseCode
                } catch (_: Exception) {
                    // 服务器收不到也没关系：本地凭证照样清掉
                } finally {
                    conn?.disconnect()
                }
            }
        }
        clearLocal()
    }

    fun clearLocal() {
        prefs.edit().clear().apply()
    }
}
