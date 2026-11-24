package com.maihe.chengguang

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 书籍/诗歌阅读器（Phase 1）。
 * 排版设置（字号/行距/主题）、章节目录、全文搜索、阅读进度+书签、上一页/下一页翻页。
 * 正文随目录下发、目录已缓存（1.2.13），所以离线也能读，不用再下载。
 *
 * 列表结构（LazyColumn）：第 0 项是标题头，1..N 是正文段落，N+1 是尾部提示。
 * 因此段落下标 p 对应的列表位置是 p+1；保存的阅读位置存的也是列表位置。
 */

// ---------- 排版设置 ----------

private const val READER_PREFS = "chengguang_reader"

private val FONT_SIZES = listOf(15, 17, 20, 24)
private val FONT_SIZE_LABELS = listOf("小", "中", "大", "特大")
private val LINE_MULTIPLIERS = listOf(1.3f, 1.6f, 2.0f)
private val LINE_SPACING_LABELS = listOf("紧密", "适中", "宽松")

private enum class ReaderTheme(
    val key: String,
    val label: String,
    val bg: Color,
    val text: Color,
    val muted: Color
) {
    DAY("day", "日间", Color.White, Color(0xFF1A1A1A), Color.Gray),
    NIGHT("night", "夜间", Color(0xFF121212), Color(0xFFD8D8D8), Color(0xFF9E9E9E)),
    EYE("eye", "护眼", Color(0xFFF5E6C8), Color(0xFF4A3222), Color(0xFF8A6D4B))
}

private fun readerPrefs(context: Context) =
    context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)

private fun loadFontSizeIdx(context: Context) =
    readerPrefs(context).getInt("font_size_idx", 1).coerceIn(0, FONT_SIZES.size - 1)

private fun saveFontSizeIdx(context: Context, idx: Int) {
    readerPrefs(context).edit().putInt("font_size_idx", idx).apply()
}

private fun loadLineSpacingIdx(context: Context) =
    readerPrefs(context).getInt("line_spacing_idx", 1).coerceIn(0, LINE_MULTIPLIERS.size - 1)

private fun saveLineSpacingIdx(context: Context, idx: Int) {
    readerPrefs(context).edit().putInt("line_spacing_idx", idx).apply()
}

private fun loadReaderTheme(context: Context): ReaderTheme {
    val key = readerPrefs(context).getString("theme", ReaderTheme.DAY.key)
    return ReaderTheme.values().find { it.key == key } ?: ReaderTheme.DAY
}

private fun saveReaderTheme(context: Context, theme: ReaderTheme) {
    readerPrefs(context).edit().putString("theme", theme.key).apply()
}

private fun loadLastPosition(context: Context, bookId: String): Int =
    readerPrefs(context).getInt("last_pos_$bookId", 0)

private fun saveLastPosition(context: Context, bookId: String, index: Int) {
    readerPrefs(context).edit().putInt("last_pos_$bookId", index).apply()
}

private fun loadBookmarks(context: Context, bookId: String): Set<Int> {
    val raw = readerPrefs(context).getStringSet("bookmarks_$bookId", emptySet()) ?: emptySet()
    return raw.mapNotNull { it.toIntOrNull() }.toSet()
}

private fun saveBookmarks(context: Context, bookId: String, bookmarks: Set<Int>) {
    readerPrefs(context).edit()
        .putStringSet("bookmarks_$bookId", bookmarks.map { it.toString() }.toSet())
        .apply()
}

// ---------- 章节检测 ----------

/** 章节标题行（按行、去掉首尾空白后判断）。 */
private val CHAPTER_PATTERNS = listOf(
    Regex("^第[一二三四五六七八九十百千0-9]+[章节篇回]"),
    Regex("^(序|前言|后记|附录)"),
    Regex("^Chapter [0-9]+")
)

private fun isChapterHeading(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    return CHAPTER_PATTERNS.any { it.containsMatchIn(trimmed) }
}

// ---------- 搜索高亮 ----------

/** 把段落里所有关键词标上金色底；关键词为空时原样返回。
 * 找位置时先把显示文本和关键词都归一化成简体再比（繁体字也能搜到），
 * 转换是逐字一对一，归一化后的下标和显示文本一一对应，可以直接用来标底色。 */
private fun highlightMatches(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val normText = text.toSimplified()
    val normQuery = query.toSimplified()
    if (normQuery.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        var from = 0
        while (true) {
            val found = normText.indexOf(normQuery, from, ignoreCase = true)
            if (found < 0) break
            addStyle(SpanStyle(background = Color(0xFFFFD54F)), found, found + normQuery.length)
            from = found + normQuery.length
        }
    }
}

/** 跟读高亮：把当前读的句子标上金色底（和搜索高亮一样的色）。 */
private fun highlightTtsSentence(paragraph: String, sentIdx: Int): AnnotatedString {
    val span = splitParagraph(paragraph).getOrNull(sentIdx)
        ?: return AnnotatedString(paragraph)
    return buildAnnotatedString {
        append(paragraph)
        addStyle(SpanStyle(background = Color(0xFFFFD54F)), span.start, span.end)
    }
}

// ---------- 阅读器 ----------

@Composable
fun ReaderScreen(item: CatalogItem, bookshelf: BookshelfStore? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val paragraphs = remember(item.id, item.content) {
        item.content.ifEmpty { "（正文待管理员上传）" }.split("\n")
    }
    // 简繁（持久化，默认简体；只影响正文显示，原文不动）
    var scriptMode by remember { mutableStateOf(loadScriptMode(context)) }
    // 显示用段落：繁体模式下逐字转换；简体模式就是原文。
    // 段落数量和顺序不变，所以书签、目录、阅读进度、听书都按原下标照旧。
    val displayParagraphs = remember(paragraphs, scriptMode) {
        if (scriptMode == ScriptMode.TRADITIONAL) paragraphs.map { it.toTraditional() }
        else paragraphs
    }
    // 章节：段落下标 -> 标题（在原文上检测，因为繁体的“節”不在检测正则里；
    // 显示时再按当前字体转换标题）
    val chapters = remember(paragraphs, scriptMode) {
        paragraphs.mapIndexedNotNull { idx, p ->
            if (isChapterHeading(p)) idx to p.trim().convertScript(scriptMode) else null
        }
    }

    // 排版设置（持久化，默认 17sp、行距 1.6、日间）
    var fontSizeIdx by remember { mutableStateOf(loadFontSizeIdx(context)) }
    var lineSpacingIdx by remember { mutableStateOf(loadLineSpacingIdx(context)) }
    var theme by remember { mutableStateOf(loadReaderTheme(context)) }
    val fontSize = FONT_SIZES[fontSizeIdx]
    val lineHeight = fontSize * LINE_MULTIPLIERS[lineSpacingIdx]

    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var resumedHint by remember { mutableStateOf(false) }
    var showTtsSpeed by remember { mutableStateOf(false) }
    var showTtsVoice by remember { mutableStateOf(false) }
    var showTtsSleep by remember { mutableStateOf(false) }

    // 全文搜索
    var query by remember { mutableStateOf("") }
    var matchIndices by remember { mutableStateOf<List<Int>>(emptyList()) }
    var currentMatch by remember { mutableStateOf(0) }

    // 本本书的书签（段落下标集合）
    var bookmarks by remember { mutableStateOf(loadBookmarks(context, item.id)) }

    // 打开时回到上次读到的地方（列表位置 1..N 才是正文段落，0 是标题头）
    LaunchedEffect(item.id) {
        val saved = loadLastPosition(context, item.id)
        if (saved in 1..paragraphs.size) {
            listState.scrollToItem(saved)
            resumedHint = true
            delay(3000)
            resumedHint = false
        }
    }

    // 离开页面时保存阅读位置
    DisposableEffect(item.id) {
        onDispose { saveLastPosition(context, item.id, listState.firstVisibleItemIndex) }
    }

    // 进阅读器就算读过：书架里的这本书最近阅读时间更新一下（不在书架的不动；诗歌不进书架）
    LaunchedEffect(item.id) {
        if (item.type == "book") bookshelf?.markRead(item.id)
    }

    // 关键词变了：重新找匹配，跳到第一个
    // 搜索时关键词和段落都先归一化成简体再比，所以繁体模式下打繁体字、
    // 简体模式下打简体字都能找到；转换是逐字一对一，下标和显示文本一一对应。
    LaunchedEffect(query) {
        if (query.isBlank()) {
            matchIndices = emptyList()
            currentMatch = 0
        } else {
            val normQuery = query.toSimplified()
            val found = paragraphs.mapIndexedNotNull { idx, p ->
                if (p.toSimplified().contains(normQuery, ignoreCase = true)) idx else null
            }
            matchIndices = found
            currentMatch = 0
            if (found.isNotEmpty()) listState.scrollToItem(found[0] + 1)
        }
    }

    fun jumpToMatch(i: Int) {
        if (matchIndices.isEmpty()) return
        val next = (i % matchIndices.size + matchIndices.size) % matchIndices.size
        currentMatch = next
        scope.launch { listState.scrollToItem(matchIndices[next] + 1) }
    }

    fun toggleBookmark() {
        val paraIdx = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
        val updated = bookmarks.toMutableSet()
        if (updated.contains(paraIdx)) updated.remove(paraIdx) else updated.add(paraIdx)
        bookmarks = updated
        saveBookmarks(context, item.id, updated)
        saveLastPosition(context, item.id, listState.firstVisibleItemIndex)
    }

    val firstParaIdx = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
    val isBookmarked = bookmarks.contains(firstParaIdx)

    // 听书状态（TtsReader 是进程级单例，离开页面再回来还能接着控）
    val ttsActive = TtsReader.isActive && TtsReader.currentBook?.id == item.id
    val ttsPara = TtsReader.currentPara
    val ttsSent = TtsReader.currentSent

    // 跟读：读到哪滚到哪；当前段已经在屏幕上就不动，免得来回跳
    LaunchedEffect(ttsActive, ttsPara, ttsSent) {
        if (ttsActive && ttsPara in paragraphs.indices) {
            val pos = ttsPara + 1
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: 0
            val last = visible.lastOrNull()?.index ?: 0
            if (pos < first || pos > last) {
                listState.scrollToItem(pos)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
    ) {
        // 翻页按一屏的高度滚
        val viewportPx = with(density) { maxHeight.toPx() }
        Column(modifier = Modifier.fillMaxSize()) {
            // 工具栏：目录 / 搜索 / 排版 / 书签 / 我的书签
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showToc = true },
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) { Text("目录", fontSize = 13.sp) }
                OutlinedButton(
                    onClick = {
                        showSearch = !showSearch
                        if (!showSearch) query = ""
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) { Text("搜索", fontSize = 13.sp) }
                OutlinedButton(
                    onClick = { showSettings = true },
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) { Text("排版", fontSize = 13.sp) }
                OutlinedButton(
                    onClick = { toggleBookmark() },
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) { Text(if (isBookmarked) "已加书签" else "书签", fontSize = 13.sp) }
                OutlinedButton(
                    onClick = { showBookmarks = true },
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) { Text("我的书签", fontSize = 13.sp) }
                OutlinedButton(
                    onClick = {
                        if (ttsActive) {
                            TtsReader.togglePause()
                        } else {
                            TtsReader.startReading(context, item, paragraphs, firstParaIdx)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Text(
                        if (ttsActive) {
                            if (TtsReader.isReading) "听书中" else "继续听"
                        } else "听书",
                        fontSize = 13.sp
                    )
                }
                // 加入书架：只对属灵书籍有效，诗歌不进书架
                if (item.type == "book" && bookshelf != null) {
                    val onShelf = bookshelf.isOnShelf(item.id)
                    OutlinedButton(
                        onClick = {
                            if (onShelf) bookshelf.remove(item.id) else bookshelf.add(item)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text(if (onShelf) "已在书架" else "加入书架", fontSize = 13.sp)
                    }
                }
            }

            // 搜索行：输入框 + 上一个/下一个 + 第 x / n 个 + 清除
            if (showSearch) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索书中内容", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { jumpToMatch(currentMatch - 1) },
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text("上一个", fontSize = 13.sp) }
                    TextButton(
                        onClick = { jumpToMatch(currentMatch + 1) },
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text("下一个", fontSize = 13.sp) }
                    Text(
                        when {
                            query.isBlank() -> ""
                            matchIndices.isEmpty() -> "没有找到"
                            else -> "第 ${currentMatch + 1} / ${matchIndices.size} 个"
                        },
                        fontSize = 12.sp,
                        color = theme.muted
                    )
                    TextButton(
                        onClick = { query = ""; showSearch = false },
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text("清除", fontSize = 13.sp) }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (resumedHint) {
                Text(
                    "已回到上次读到的地方",
                    fontSize = 13.sp,
                    color = theme.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            if (TtsReader.noChineseVoice) {
                Text(
                    "系统没有中文语音，请在系统设置里下载中文语音数据",
                    fontSize = 13.sp,
                    color = theme.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            // 正文：标题头 + 一段一项 + 尾部提示
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(item.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = theme.text)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(item.category, fontSize = 13.sp, color = theme.muted)
                        if (item.description.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(item.description, fontSize = 15.sp, color = theme.muted)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider(color = theme.muted)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                items(displayParagraphs.size) { idx ->
                    val paragraph = displayParagraphs[idx]
                    if (paragraph.isBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                    } else {
                        Text(
                            text = when {
                                ttsActive && idx == ttsPara -> highlightTtsSentence(paragraph, ttsSent)
                                else -> highlightMatches(paragraph, if (showSearch) query else "")
                            },
                            fontSize = fontSize.sp,
                            lineHeight = lineHeight.sp,
                            color = theme.text,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Divider(color = theme.muted)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("正文已随 App 自带，离线也能读，无需下载。", fontSize = 12.sp, color = theme.muted)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            // 听书控制条：听书进行中才显示，关闭后恢复正常阅读界面
            if (ttsActive) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { TtsReader.togglePause() },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text(if (TtsReader.isReading) "暂停" else "继续", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { showTtsSpeed = true },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("语速 ${TtsReader.speedLabel()}", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { showTtsVoice = true },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("音色", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { showTtsSleep = true },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text(TtsReader.sleepLabel(), fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { TtsReader.stop() },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("关闭", fontSize = 13.sp) }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(TtsReader.status, fontSize = 12.sp, color = theme.muted)
                }
            }

            // 翻页：只保留滚动模式，按一屏的高度上下滚
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(onClick = {
                    scope.launch { listState.animateScrollBy(-viewportPx) }
                }) { Text("上一页", fontSize = 14.sp) }
                OutlinedButton(onClick = {
                    scope.launch { listState.animateScrollBy(viewportPx) }
                }) { Text("下一页", fontSize = 14.sp) }
            }
        }
    }

    // 排版设置弹窗：字号 / 行距 / 主题
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("完成") }
            },
            title = { Text("排版") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OptionRow(
                        label = "字号",
                        options = FONT_SIZE_LABELS,
                        selected = fontSizeIdx,
                        onSelect = {
                            fontSizeIdx = it
                            saveFontSizeIdx(context, it)
                        }
                    )
                    OptionRow(
                        label = "行距",
                        options = LINE_SPACING_LABELS,
                        selected = lineSpacingIdx,
                        onSelect = {
                            lineSpacingIdx = it
                            saveLineSpacingIdx(context, it)
                        }
                    )
                    OptionRow(
                        label = "主题",
                        options = ReaderTheme.values().map { it.label },
                        selected = theme.ordinal,
                        onSelect = {
                            theme = ReaderTheme.values()[it]
                            saveReaderTheme(context, theme)
                        }
                    )
                    OptionRow(
                        label = "简繁",
                        options = ScriptMode.values().map { it.label },
                        selected = scriptMode.ordinal,
                        onSelect = {
                            scriptMode = ScriptMode.values()[it]
                            saveScriptMode(context, scriptMode)
                        }
                    )
                }
            }
        )
    }

    // 章节目录弹窗
    if (showToc) {
        AlertDialog(
            onDismissRequest = { showToc = false },
            confirmButton = {
                TextButton(onClick = { showToc = false }) { Text("关闭") }
            },
            title = { Text("目录") },
            text = {
                if (chapters.isEmpty()) {
                    Text("本书没有检测到章节标题", fontSize = 14.sp)
                } else {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(chapters) { (paraIdx, title) ->
                            TextButton(
                                onClick = {
                                    showToc = false
                                    scope.launch { listState.scrollToItem(paraIdx + 1) }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    title,
                                    fontSize = 14.sp,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    // 我的书签弹窗：点一行跳过去，每行可删除
    if (showBookmarks) {
        AlertDialog(
            onDismissRequest = { showBookmarks = false },
            confirmButton = {
                TextButton(onClick = { showBookmarks = false }) { Text("关闭") }
            },
            title = { Text("我的书签") },
            text = {
                val sorted = bookmarks.sorted()
                if (sorted.isEmpty()) {
                    Text("还没有书签。在阅读时点“书签”可以加上。", fontSize = 14.sp)
                } else {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(sorted) { paraIdx ->
                            val label = displayParagraphs.getOrNull(paraIdx)?.trim().orEmpty()
                                .take(20).ifEmpty { "第 ${paraIdx + 1} 段" }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        showBookmarks = false
                                        scope.launch { listState.scrollToItem(paraIdx + 1) }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        label,
                                        fontSize = 14.sp,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Start
                                    )
                                }
                                TextButton(onClick = {
                                    val updated = bookmarks.toMutableSet().apply { remove(paraIdx) }
                                    bookmarks = updated
                                    saveBookmarks(context, item.id, updated)
                                }) { Text("删除", fontSize = 13.sp) }
                            }
                        }
                    }
                }
            }
        )
    }

    // 听书：语速选择
    if (showTtsSpeed) {
        AlertDialog(
            onDismissRequest = { showTtsSpeed = false },
            confirmButton = {
                TextButton(onClick = { showTtsSpeed = false }) { Text("完成") }
            },
            title = { Text("语速") },
            text = {
                OptionRow(
                    label = "语速",
                    options = TtsReader.SPEED_LABELS,
                    selected = TtsReader.SPEEDS.indexOf(TtsReader.speed).coerceAtLeast(0),
                    onSelect = { TtsReader.updateSpeed(TtsReader.SPEEDS[it]) }
                )
            }
        )
    }

    // 听书：音色选择
    if (showTtsVoice) {
        AlertDialog(
            onDismissRequest = { showTtsVoice = false },
            confirmButton = {
                TextButton(onClick = { showTtsVoice = false }) { Text("完成") }
            },
            title = { Text("音色") },
            text = {
                if (TtsReader.chineseVoices.isEmpty()) {
                    Text("系统没有中文语音，请在系统设置里下载中文语音数据", fontSize = 14.sp)
                } else {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        item {
                            TextButton(
                                onClick = { TtsReader.setVoice("") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (TtsReader.voiceName.isEmpty()) "✓ 系统默认" else "系统默认",
                                    fontSize = 14.sp,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                        items(TtsReader.chineseVoices) { voice ->
                            TextButton(
                                onClick = { TtsReader.setVoice(voice.name) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (TtsReader.voiceName == voice.name) "✓ ${voice.name}" else voice.name,
                                    fontSize = 14.sp,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    // 听书：定时停止
    if (showTtsSleep) {
        AlertDialog(
            onDismissRequest = { showTtsSleep = false },
            confirmButton = {
                TextButton(onClick = { showTtsSleep = false }) { Text("完成") }
            },
            title = { Text("定时停止") },
            text = {
                OptionRow(
                    label = "定时",
                    options = TtsReader.SLEEP_LABELS,
                    selected = TtsReader.SLEEP_OPTIONS.indexOf(TtsReader.sleepMinutes).coerceAtLeast(0),
                    onSelect = { TtsReader.updateSleepMinutes(TtsReader.SLEEP_OPTIONS[it]) }
                )
            }
        )
    }
}

/** 设置弹窗里的一组选项：选中的是实心按钮，没选中的是描边按钮。 */
@Composable
private fun OptionRow(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { idx, option ->
            if (idx == selected) {
                Button(
                    onClick = { onSelect(idx) },
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) { Text(option, fontSize = 13.sp) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(idx) },
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) { Text(option, fontSize = 13.sp) }
            }
        }
    }
}
