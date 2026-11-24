package com.maihe.chengguang

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 我的书架（读书器二期第 3 块）。
 *
 * 只对属灵书籍（type == book）有效，全部数据只存在这台手机上：
 * 不动服务器、不动目录、不删服务器内容。移出书架也只是本地移除，
 * 书本身还在目录里，随时能再加回来。
 *
 * 作者是本地字段（用户在书架里给每本书填）：管理后台的 PUT 是白名单字段，
 * 端到端加 author 要同时改 App + 后台表单 + Worker 白名单、还要重新部署
 * Worker，代价大；书架本地填作者已经够排序和显示用。
 * 中文书名/作者排序按字符串比较（不是拼音序），这个要知道。
 */

private const val BOOKSHELF_PREFS = "chengguang_bookshelf"
private val shelfJson = Json { ignoreUnknownKeys = true }

@Serializable
data class ShelfEntry(
    val id: String,
    val addedAt: Long = 0L, // 加入书架的时间（毫秒）
    val lastReadAt: Long = 0L, // 最近一次打开阅读的时间（毫秒），0 表示还没读过
    val favorite: Boolean = false,
    val author: String = "", // 本地填的作者，空表示没填
    val tags: List<String> = emptyList()
)

enum class ShelfSortMode(val label: String) {
    RECENT("最近阅读"), TITLE("书名"), AUTHOR("作者")
}

enum class ShelfViewMode(val label: String) {
    LIST("列表"), COVER("封面")
}

class BookshelfStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(BOOKSHELF_PREFS, Context.MODE_PRIVATE)

    /** 每次改动后 version +1，读了 version 的 Composable 会重组并重新读状态。 */
    var version by mutableStateOf(0)
        private set

    fun refresh() {
        version++
    }

    private fun loadEntries(): MutableMap<String, ShelfEntry> {
        val raw = prefs.getString("entries", null) ?: return mutableMapOf()
        return try {
            shelfJson.decodeFromString<Map<String, ShelfEntry>>(raw).toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveEntries(entries: Map<String, ShelfEntry>) {
        prefs.edit().putString("entries", shelfJson.encodeToString(entries)).apply()
        refresh()
    }

    /** 全部书架记录（id -> 记录）。 */
    fun entries(): Map<String, ShelfEntry> {
        @Suppress("UNUSED_EXPRESSION") version
        return loadEntries()
    }

    fun isOnShelf(id: String): Boolean {
        @Suppress("UNUSED_EXPRESSION") version
        return loadEntries().containsKey(id)
    }

    fun entry(id: String): ShelfEntry? {
        @Suppress("UNUSED_EXPRESSION") version
        return loadEntries()[id]
    }

    fun add(item: CatalogItem) {
        val all = loadEntries()
        if (all.containsKey(item.id)) return
        all[item.id] = ShelfEntry(id = item.id, addedAt = System.currentTimeMillis())
        saveEntries(all)
    }

    fun remove(id: String) {
        val all = loadEntries()
        if (all.remove(id) != null) saveEntries(all)
    }

    fun removeAll(ids: Set<String>) {
        val all = loadEntries()
        var changed = false
        ids.forEach { if (all.remove(it) != null) changed = true }
        if (changed) saveEntries(all)
    }

    /** 打开阅读时调用：在书架里的才更新最近阅读时间，不在书架的不动。 */
    fun markRead(id: String) {
        val all = loadEntries()
        val e = all[id] ?: return
        all[id] = e.copy(lastReadAt = System.currentTimeMillis())
        saveEntries(all)
    }

    fun setFavorite(id: String, favorite: Boolean) {
        val all = loadEntries()
        val e = all[id] ?: return
        if (e.favorite != favorite) {
            all[id] = e.copy(favorite = favorite)
            saveEntries(all)
        }
    }

    fun setFavorites(ids: Set<String>, favorite: Boolean) {
        val all = loadEntries()
        var changed = false
        ids.forEach { id ->
            val e = all[id]
            if (e != null && e.favorite != favorite) {
                all[id] = e.copy(favorite = favorite)
                changed = true
            }
        }
        if (changed) saveEntries(all)
    }

    fun updateAuthor(id: String, author: String) {
        val all = loadEntries()
        val e = all[id] ?: return
        if (e.author != author) {
            all[id] = e.copy(author = author)
            saveEntries(all)
        }
    }

    fun setTags(id: String, tags: List<String>) {
        val all = loadEntries()
        val e = all[id] ?: return
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (e.tags != clean) {
            all[id] = e.copy(tags = clean)
            saveEntries(all)
        }
    }

    /** 给一批书加标签（和已有的合并、去重）。 */
    fun addTags(ids: Set<String>, tags: List<String>) {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return
        val all = loadEntries()
        var changed = false
        ids.forEach { id ->
            val e = all[id]
            if (e != null) {
                val merged = (e.tags + clean).distinct()
                if (merged != e.tags) {
                    all[id] = e.copy(tags = merged)
                    changed = true
                }
            }
        }
        if (changed) saveEntries(all)
    }

    fun removeTag(id: String, tag: String) {
        val all = loadEntries()
        val e = all[id] ?: return
        if (e.tags.contains(tag)) {
            all[id] = e.copy(tags = e.tags - tag)
            saveEntries(all)
        }
    }

    /** 书架里出现过的所有标签（排好序，供筛选和加标签时选）。 */
    fun allTags(): List<String> {
        @Suppress("UNUSED_EXPRESSION") version
        return loadEntries().values.flatMap { it.tags }.distinct().sorted()
    }

    /** 当前目录里、在书架上的书。目录里已经删掉的书不显示，
     * 但本地记录还留着（目录拉不到时误删就不好了），以后书回来还会再显示。 */
    fun shelfItems(all: List<CatalogItem>): List<CatalogItem> {
        @Suppress("UNUSED_EXPRESSION") version
        val ids = loadEntries().keys
        return all.filter { it.type == "book" && ids.contains(it.id) }
    }

    var viewMode: ShelfViewMode
        get() = when (prefs.getString("view_mode", ShelfViewMode.LIST.name)) {
            ShelfViewMode.COVER.name -> ShelfViewMode.COVER
            else -> ShelfViewMode.LIST
        }
        set(value) {
            prefs.edit().putString("view_mode", value.name).apply()
            refresh()
        }

    var sortMode: ShelfSortMode
        get() = when (prefs.getString("sort_mode", ShelfSortMode.RECENT.name)) {
            ShelfSortMode.TITLE.name -> ShelfSortMode.TITLE
            ShelfSortMode.AUTHOR.name -> ShelfSortMode.AUTHOR
            else -> ShelfSortMode.RECENT
        }
        set(value) {
            prefs.edit().putString("sort_mode", value.name).apply()
            refresh()
        }
}

/**
 * 阅读进度：返回（已读到的段号，段落总数）。段号 0 表示还没读过。
 * 段号就是阅读器保存的列表位置（0 是标题头，1..N 是正文段落）。
 */
fun readProgress(context: Context, item: CatalogItem): Pair<Int, Int> {
    val total = item.content.split("\n").size.coerceAtLeast(1)
    val pos = context.getSharedPreferences("chengguang_reader", Context.MODE_PRIVATE)
        .getInt("last_pos_${item.id}", 0)
    return pos.coerceIn(0, total) to total
}

fun progressText(context: Context, item: CatalogItem): String {
    val (pos, total) = readProgress(context, item)
    return if (pos <= 0) "还没读过 · 共 $total 段" else "读到第 $pos 段 / 共 $total 段"
}

/**
 * 书架排序：收藏的永远排在前面（再按选的排序排），其余按选的排序排。
 * 最近阅读：读过的按时间倒序，没读过的排后面、按加入时间倒序。
 * 作者：填了作者的按作者排，没填的排后面、再按书名排。
 */
private fun sortShelfPairs(
    pairs: List<Pair<CatalogItem, ShelfEntry>>,
    sort: ShelfSortMode
): List<Pair<CatalogItem, ShelfEntry>> {
    val favFirst = compareByDescending<Pair<CatalogItem, ShelfEntry>> { it.second.favorite }
    return when (sort) {
        ShelfSortMode.RECENT -> pairs.sortedWith(
            favFirst
                .thenByDescending { it.second.lastReadAt }
                .thenByDescending { it.second.addedAt }
        )
        ShelfSortMode.TITLE -> pairs.sortedWith(favFirst.thenBy { it.first.title })
        ShelfSortMode.AUTHOR -> pairs.sortedWith(
            favFirst
                .thenBy { it.second.author.isBlank() }
                .thenBy { it.second.author }
                .thenBy { it.first.title }
        )
    }
}

// ---------- 书架页 ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(
    bookshelf: BookshelfStore,
    items: List<CatalogItem>,
    onItem: (CatalogItem) -> Unit
) {
    val context = LocalContext.current
    val shelf = bookshelf.shelfItems(items)
    val entries = bookshelf.entries()
    val pairs = shelf.mapNotNull { item -> entries[item.id]?.let { item to it } }
    val tags = bookshelf.allTags()

    var tagFilter by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchTag by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Pair<CatalogItem, ShelfEntry>?>(null) }

    val filtered = if (tagFilter == null) pairs else pairs.filter { it.second.tags.contains(tagFilter) }
    val shown = sortShelfPairs(filtered, bookshelf.sortMode)
    val inSelection = selected.isNotEmpty()
    val allSelectedFavorite = selected.isNotEmpty() &&
        selected.all { entries[it]?.favorite == true }

    // 多选时按返回先退出多选，不直接退回上一页
    BackHandler(enabled = inSelection) {
        selected = emptySet()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 控制区：视图切换 + 排序 + 标签筛选
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("共 ${pairs.size} 本", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    ShelfViewMode.values().forEach { mode ->
                        if (bookshelf.viewMode == mode) {
                            Button(
                                onClick = {},
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) { Text(mode.label, fontSize = 13.sp) }
                        } else {
                            OutlinedButton(
                                onClick = { bookshelf.viewMode = mode },
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) { Text(mode.label, fontSize = 13.sp) }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text("排序", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShelfSortMode.values().forEach { mode ->
                        FilterChip(
                            selected = bookshelf.sortMode == mode,
                            onClick = { bookshelf.sortMode = mode },
                            label = { Text(mode.label) }
                        )
                    }
                }
                if (tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("按标签看", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = tagFilter == null,
                            onClick = { tagFilter = null },
                            label = { Text("全部") }
                        )
                        tags.forEach { tag ->
                            FilterChip(
                                selected = tagFilter == tag,
                                onClick = { tagFilter = if (tagFilter == tag) null else tag },
                                label = { Text(tag) }
                            )
                        }
                    }
                }
                Text(
                    "长按一本书可以多选，批量移出书架、加标签、收藏。收藏的书会排在前面。",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }

        // 多选操作条
        if (inSelection) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3D6)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("已选 ${selected.size} 本", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showRemoveConfirm = true },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("移出书架", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { showBatchTag = true },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("加标签", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = {
                                bookshelf.setFavorites(selected, !allSelectedFavorite)
                                selected = emptySet()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text(if (allSelectedFavorite) "取消收藏" else "收藏", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { selected = emptySet() },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("取消", fontSize = 13.sp) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (pairs.isEmpty()) {
            Text(
                "书架还是空的。在书里点“加入书架”就能把书收进来。",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(16.dp)
            )
        } else if (shown.isEmpty()) {
            Text(
                "这个标签下没有书。",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(16.dp)
            )
        } else if (bookshelf.viewMode == ShelfViewMode.LIST) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(shown, key = { it.first.id }) { (item, entry) ->
                    ShelfListRow(
                        item = item,
                        entry = entry,
                        progress = progressText(context, item),
                        selected = selected.contains(item.id),
                        onClick = {
                            if (inSelection) {
                                selected = if (selected.contains(item.id)) selected - item.id
                                else selected + item.id
                            } else {
                                onItem(item)
                            }
                        },
                        onLongClick = {
                            selected = if (selected.contains(item.id)) selected - item.id
                            else selected + item.id
                        },
                        onEdit = { editTarget = item to entry }
                    )
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(shown, key = { it.first.id }) { (item, entry) ->
                    ShelfCoverCard(
                        item = item,
                        entry = entry,
                        progress = progressText(context, item),
                        selected = selected.contains(item.id),
                        onClick = {
                            if (inSelection) {
                                selected = if (selected.contains(item.id)) selected - item.id
                                else selected + item.id
                            } else {
                                onItem(item)
                            }
                        },
                        onLongClick = {
                            selected = if (selected.contains(item.id)) selected - item.id
                            else selected + item.id
                        },
                        onEdit = { editTarget = item to entry }
                    )
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }
        }
    }

    // 批量加标签
    if (showBatchTag) {
        BatchTagDialog(
            allTags = tags,
            onDismiss = { showBatchTag = false },
            onConfirm = { newTags ->
                bookshelf.addTags(selected, newTags)
                selected = emptySet()
            }
        )
    }

    // 批量移出书架：只动本地书架，不删内容
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    bookshelf.removeAll(selected)
                    selected = emptySet()
                    showRemoveConfirm = false
                }) { Text("移出") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("取消") }
            },
            title = { Text("移出书架") },
            text = {
                Text(
                    "把选中的 ${selected.size} 本书移出书架。这只是从你的书架里拿掉，" +
                        "不会删除内容本身，以后还能再加回来。",
                    fontSize = 14.sp
                )
            }
        )
    }

    // 单本编辑：作者 + 标签 + 收藏
    editTarget?.let { (item, entry) ->
        ShelfEditDialog(
            item = item,
            entry = entry,
            allTags = tags,
            onDismiss = { editTarget = null },
            onSave = { author, newTags, favorite ->
                bookshelf.updateAuthor(item.id, author)
                bookshelf.setTags(item.id, newTags)
                bookshelf.setFavorite(item.id, favorite)
            }
        )
    }
}

// ---------- 列表行 / 封面卡片 ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfListRow(
    item: CatalogItem,
    entry: ShelfEntry,
    progress: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFFFF3D6) else Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFF9A7B2D), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    item.title.firstOrNull()?.toString() ?: "书",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    (if (entry.favorite) "★ " else "") + item.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (entry.author.isNotBlank()) "作者：${entry.author}" else "作者未填（点“编辑”可以填）",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                Text(progress, fontSize = 13.sp, color = Color.Gray)
                if (entry.tags.isNotEmpty()) {
                    Text(
                        entry.tags.joinToString(" ") { "#$it" },
                        fontSize = 12.sp,
                        color = Color(0xFF9A7B2D)
                    )
                }
            }
            if (selected) {
                Text("✓", fontSize = 20.sp, color = Color(0xFF9A7B2D))
            } else {
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("编辑", fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCoverCard(
    item: CatalogItem,
    entry: ShelfEntry,
    progress: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFFFF3D6) else Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(Color(0xFF9A7B2D), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    item.title.firstOrNull()?.toString() ?: "书",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                (if (entry.favorite) "★ " else "") + item.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                if (entry.author.isNotBlank()) entry.author else "作者未填",
                fontSize = 12.sp,
                color = Color.Gray,
                maxLines = 1
            )
            Text(progress, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
            if (entry.tags.isNotEmpty()) {
                Text(
                    entry.tags.joinToString(" ") { "#$it" },
                    fontSize = 11.sp,
                    color = Color(0xFF9A7B2D),
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("编辑", fontSize = 13.sp)
            }
        }
    }
}

// ---------- 弹窗 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfEditDialog(
    item: CatalogItem,
    entry: ShelfEntry,
    allTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (author: String, tags: List<String>, favorite: Boolean) -> Unit
) {
    var author by remember { mutableStateOf(entry.author) }
    var tags by remember { mutableStateOf(entry.tags) }
    var newTag by remember { mutableStateOf("") }
    var favorite by remember { mutableStateOf(entry.favorite) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(author.trim(), tags, favorite); onDismiss() }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text(item.title, maxLines = 2) },
        text = {
            Column(
                modifier = Modifier
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("作者（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("标签", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (tags.isEmpty()) {
                        Text("还没打标签", fontSize = 13.sp, color = Color.Gray)
                    } else {
                        tags.forEach { tag ->
                            FilterChip(
                                selected = true,
                                onClick = { tags = tags - tag },
                                label = { Text("$tag ×") }
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        placeholder = { Text("新标签") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        val t = newTag.trim()
                        if (t.isNotEmpty() && !tags.contains(t)) tags = tags + t
                        newTag = ""
                    }) { Text("加上") }
                }
                val others = allTags.filter { !tags.contains(it) }
                if (others.isNotEmpty()) {
                    Text("点一下加上已有标签：", fontSize = 13.sp, color = Color.Gray)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        others.forEach { tag ->
                            FilterChip(
                                selected = false,
                                onClick = { tags = tags + tag },
                                label = { Text(tag) }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("收藏", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = favorite, onCheckedChange = { favorite = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("收藏的书在书架里排前面", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchTagDialog(
    allTags: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var newTag by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val tags = (picked + newTag.trim()).filter { it.isNotEmpty() }
                if (tags.isNotEmpty()) onConfirm(tags)
                onDismiss()
            }) { Text("加上") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("给选中的书加标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    placeholder = { Text("新标签（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (allTags.isEmpty()) {
                    Text("还没有标签，在上面输入一个新标签即可。", fontSize = 13.sp, color = Color.Gray)
                } else {
                    Text("或选已有标签（可多选）：", fontSize = 13.sp, color = Color.Gray)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        allTags.forEach { tag ->
                            FilterChip(
                                selected = picked.contains(tag),
                                onClick = {
                                    picked = if (picked.contains(tag)) picked - tag else picked + tag
                                },
                                label = { Text(tag) }
                            )
                        }
                    }
                }
            }
        }
    )
}
