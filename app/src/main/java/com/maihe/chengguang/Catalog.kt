package com.maihe.chengguang

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
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
    val pdfUrl: String = "", // 讲章附件（PDF 优先，也可是 PPT）：音频/视频详情页里查看和下载；为空表示没有
    val duration: String = "",
    val size: String = "",
    val description: String = "",
    val content: String = "", // 书籍/诗歌正文，多段用 \n 分隔；音视频为空
    val status: String = "published",
    val uploadedBy: String = "",
    val createdAt: String = ""
)

val CATEGORIES = listOf("属灵书籍", "敬拜赞美", "主日信息", "灵修信息", "专题讲座")

/**
 * 旧栏目名兼容：服务器目录里已存的诗还是“生命诗歌”，读进来就改成“敬拜赞美”，
 * 不用改服务器数据；新上传的已经是新名字，不受影响。
 */
private fun List<CatalogItem>.normalizedCategories(): List<CatalogItem> =
    map { if (it.category == "生命诗歌") it.copy(category = "敬拜赞美") else it }

/**
 * 管理后台的目录地址（自定义域名，国内可访问）：
 *   https://admin.maihe334.ccwu.cc/catalog.json
 * 为空表示后台还没部署，App 只用内置目录。
 */
const val REMOTE_CATALOG_URL = "https://admin.maihe334.ccwu.cc/catalog.json"

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
        catalogJson.decodeFromString<List<CatalogItem>>(text).normalizedCategories()
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * 把拉到的远程目录存一份到手机里（catalog-cache.json）。
 * 下次打开时如果没网、超时拉不到，就用这份，不用再回退到自带旧目录——
 * 这样只要成功拉过一次，主日信息这些内容就不会因为一次网络不好而整个消失。
 */
fun saveRemoteCatalog(context: Context, items: List<CatalogItem>) {
    try {
        context.openFileOutput("catalog-cache.json", Context.MODE_PRIVATE).use {
            it.write(catalogJson.encodeToString(items).toByteArray(Charsets.UTF_8))
        }
    } catch (_: Exception) {
        // 存不下也不影响使用，下次再拉就是了
    }
}

/** 读上次存下的远程目录；没有或坏了返回 null，调用方再用内置目录。 */
fun loadCachedCatalog(context: Context): List<CatalogItem>? {
    return try {
        val text = context.openFileInput("catalog-cache.json").bufferedReader().use { it.readText() }
        val items = catalogJson.decodeFromString<List<CatalogItem>>(text).normalizedCategories()
        if (items.isEmpty()) null else items
    } catch (_: Exception) {
        null
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
            connectTimeout = 10000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val items = catalogJson.decodeFromString<List<CatalogItem>>(text)
        items.filter { it.status.isBlank() || it.status == "published" }.normalizedCategories()
    } catch (_: IOException) {
        // 没网、超时、连接被关：慢网络下的正常情况，静默用内置目录，不上报 Sentry
        null
    } catch (e: Exception) {
        reportError("catalog", e)
        null
    } finally {
        conn?.disconnect()
    }
}
