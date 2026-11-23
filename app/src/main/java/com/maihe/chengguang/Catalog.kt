package com.maihe.chengguang

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * 目录记录。远程目录（R2 上的 catalog.json）和本地 assets/catalog.json 结构相同。
 * status / uploadedBy / createdAt 是管理后台的字段，App 只显示 published 的，
 * 以后“指定人上传、管理员审核”就靠 status 区分。
 */
@Serializable
data class CatalogItem(
    val id: String,
    val title: String,
    val category: String,
    val type: String, // book / poem / audio / video
    val url: String = "",
    val duration: String = "",
    val size: String = "",
    val description: String = "",
    val content: String = "", // 书籍/诗歌正文，多段用 \n 分隔；音视频为空
    val status: String = "published",
    val uploadedBy: String = "",
    val createdAt: String = ""
)

val CATEGORIES = listOf("属灵书籍", "生命诗歌", "讲道音频", "讲道视频")

/**
 * 管理后台的目录地址。部署好 Worker 后把这里填成：
 *   https://chengguang-admin.<你的子域名>.workers.dev/catalog.json
 * 为空表示后台还没部署，App 只用内置目录。不要把这个地址写成猜出来的子域名。
 */
const val REMOTE_CATALOG_URL = "https://chengguang-admin.zxq4455.workers.dev/catalog.json"

fun typeChar(type: String): String = when (type) {
    "book" -> "书"
    "poem" -> "诗"
    "audio" -> "音"
    "video" -> "视"
    else -> "城"
}

fun typeLabel(type: String): String = when (type) {
    "book" -> "书籍"
    "poem" -> "诗歌"
    "audio" -> "音频"
    "video" -> "视频"
    else -> ""
}

private val catalogJson = Json { ignoreUnknownKeys = true }

/** 从 assets/catalog.json 读内置目录；读不到时返回空列表，界面会显示提示。 */
fun loadCatalog(context: Context): List<CatalogItem> {
    return try {
        val text = context.assets.open("catalog.json").bufferedReader().use { it.readText() }
        catalogJson.decodeFromString<List<CatalogItem>>(text)
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * 从管理后台拉远程目录。成功返回已过滤的列表（只留 published）；
 * 地址为空、没网、超时、内容不对都返回 null，调用方继续用内置目录。
 * 注意：这里只传回“拉取失败”这个事实，不把 URL 发给任何地方。
 */
fun fetchRemoteCatalog(): List<CatalogItem>? {
    if (REMOTE_CATALOG_URL.isBlank()) return null
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(REMOTE_CATALOG_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val items = catalogJson.decodeFromString<List<CatalogItem>>(text)
        items.filter { it.status.isBlank() || it.status == "published" }
    } catch (e: Exception) {
        reportError("catalog", e)
        null
    } finally {
        conn?.disconnect()
    }
}
