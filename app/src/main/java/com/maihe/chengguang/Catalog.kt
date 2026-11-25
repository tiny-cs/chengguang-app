package com.maihe.chengguang

import android.content.Context
import androidx.compose.ui.graphics.Color
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
    val imageUrl: String = "", // 歌谱图片（敬拜赞美）：一首歌一张，走 /file/ 代理；为空表示没有
    val mustRead: Boolean = false, // 必读书籍：标题在 App 里显示红色（默认 false，老目录里没有这个字段也能解出来）
    val duration: String = "",
    val size: String = "",
    val description: String = "",
    val content: String = "", // 书籍/诗歌正文，多段用 \n 分隔；音视频为空
    val status: String = "published",
    val uploadedBy: String = "",
    val createdAt: String = ""
)

val CATEGORIES = listOf("属灵书籍", "敬拜赞美", "主日信息", "灵修信息", "专题讲座")

/** 必读书籍的标题红：深红，白底列表和详情页上对比约 5.6:1；护眼米色底上约 4.5:1。夜间阅读器不用它（太暗），见 ReaderTheme.mustRead。 */
val MustReadRed = Color(0xFFC62828)

/**
 * 旧栏目名兼容：服务器目录里已存的诗还是“生命诗歌”，读进来就改成“敬拜赞美”，
 * 不用改服务器数据；新上传的已经是新名字，不受影响。
 */
private fun List<CatalogItem>.normalizedCategories(): List<CatalogItem> =
    map { if (it.category == "生命诗歌") it.copy(category = "敬拜赞美") else it }

/**
 * 管理后台的精简目录地址（自定义域名，国内可访问）：
 *   https://admin.maihe334.ccwu.cc/catalog-lite.json
 * 精简目录不带正文，只有十几 KB，慢网也能下下来；点开一本书 / 一首诗时
 * 再按需取正文（见 fetchItemContent）。旧的 /catalog.json 带全文（约 4MB），
 * 0.4K/s 的网下不下来，已不再用。
 * 为空表示后台还没部署，App 只用内置目录。
 */
const val REMOTE_CATALOG_URL = "https://admin.maihe334.ccwu.cc/catalog-lite.json"

/** 成员按需取单条全文：GET /api/member/items/<id>，返回 {ok, item}（item 含 content）。 */
private const val MEMBER_ITEM_URL = "https://admin.maihe334.ccwu.cc/api/member/items/"

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

/** 拉远程目录的结果：成功、账号失效（401）、其它失败（没网、超时、内容不对）。 */
sealed interface CatalogResult {
    data class Ok(val items: List<CatalogItem>) : CatalogResult
    /** token 失效或账号被停用：调用方要回到登录页并清掉下载 */
    data object Unauthorized : CatalogResult
    /** 没网、超时、内容不对：调用方继续用缓存或内置目录 */
    data object Failed : CatalogResult
}

/**
 * 从管理后台拉远程目录。成功返回已过滤的列表（只留 published）；
 * 地址为空、没网、超时、内容不对都返回 Failed，调用方继续用内置目录；
 * 401 返回 Unauthorized（token 失效或账号被停用）。
 * 注意：这里只传回“拉取失败”这个事实，不把 URL 发给任何地方。
 */
fun fetchRemoteCatalog(token: String? = null): CatalogResult {
    if (REMOTE_CATALOG_URL.isBlank()) return CatalogResult.Failed
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(REMOTE_CATALOG_URL).openConnection() as HttpURLConnection).apply {
            // 慢网下拉目录也容易超时：连接 30 秒，读取 60 秒。
            connectTimeout = 30000
            readTimeout = 60000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        when (conn.responseCode) {
            HttpURLConnection.HTTP_OK -> {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val items = catalogJson.decodeFromString<List<CatalogItem>>(text)
                CatalogResult.Ok(items.filter { it.status.isBlank() || it.status == "published" }.normalizedCategories())
            }
            HttpURLConnection.HTTP_UNAUTHORIZED -> CatalogResult.Unauthorized
            else -> CatalogResult.Failed
        }
    } catch (_: IOException) {
        // 没网、超时、连接被关：慢网络下的正常情况，静默用内置目录，不上报 Sentry
        CatalogResult.Failed
    } catch (e: Exception) {
        reportError("catalog", e)
        CatalogResult.Failed
    } finally {
        conn?.disconnect()
    }
}

@Serializable
private data class ItemResponse(val ok: Boolean = false, val item: CatalogItem? = null)

/**
 * 按需取一条内容的全文（点开书 / 诗时用）。精简目录里 content 是空的，
 * 这里单独取回来。成功返回正文（可能为空字符串——扫描版书本来就没正文）；
 * 没网、超时、401、内容不对都返回 null，调用方显示重试。
 * 注意：大书正文几百 KB，0.4K/s 的网要下十几分钟，这里读取放宽到 5 分钟；
 * 下不完就返回 null，用户换个快点的网再点开就行，下过的会存到本地目录缓存里。
 */
fun fetchItemContent(itemId: String, token: String? = null): String? {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(MEMBER_ITEM_URL + itemId).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 300000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val resp = catalogJson.decodeFromString<ItemResponse>(text)
        if (!resp.ok) null else resp.item?.content
    } catch (_: IOException) {
        null
    } catch (e: Exception) {
        reportError("item-content", e)
        null
    } finally {
        conn?.disconnect()
    }
}

/**
 * 把按需取回的正文写进本地目录缓存（catalog-cache.json），下次点开同一本书
 * 直接读本地，不用再下。缓存坏了也不影响使用，下次重拉目录就是了。
 */
fun updateCachedItemContent(context: Context, itemId: String, content: String) {
    try {
        val cached = loadCachedCatalog(context) ?: return
        val updated = cached.map { if (it.id == itemId) it.copy(content = content) else it }
        saveRemoteCatalog(context, updated)
    } catch (_: Exception) {
        // 写不进缓存也不影响阅读，这次显示的已经有了
    }
}
