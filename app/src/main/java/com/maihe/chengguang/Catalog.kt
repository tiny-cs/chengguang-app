package com.maihe.chengguang

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 目录记录。正式版把 catalog.json 换成 R2 上的同一份文件即可，字段不变。
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
    val content: String = "" // 书籍/诗歌正文，多段用 \n 分隔；音视频为空
)

val CATEGORIES = listOf("属灵书籍", "生命诗歌", "讲道音频", "讲道视频")

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

/** 从 assets/catalog.json 读目录；读不到时返回空列表，界面会显示提示。 */
fun loadCatalog(context: Context): List<CatalogItem> {
    return try {
        val text = context.assets.open("catalog.json").bufferedReader().use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString<List<CatalogItem>>(text)
    } catch (_: Exception) {
        emptyList()
    }
}
