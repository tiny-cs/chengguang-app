package com.maihe.chengguang

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
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
    val muted: Color,
    val mustRead: Color // 必读书籍的标题红：日间/护眼用深红，夜间用亮红（深红在黑底上太暗）
) {
    DAY("day", "日间", Color.White, Color(0xFF1A1A1A), Color.Gray, Color(0xFFC62828)),
    NIGHT("night", "夜间", Color(0xFF121212), Color(0xFFD8D8D8), Color(0xFF9E9E9E), Color(0xFFEF5350)),
    EYE("eye", "护眼", Color(0xFFF5E6C8), Color(0xFF4A3222), Color(0xFF8A6D4B), Color(0xFFC62828))
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

// ---------- 书籍正文合段（EPUB 硬换行） ----------

/**
 * 句末标点：上一行以这些结尾就是真断段，否则是 EPUB 排版留下的硬换行，接回去。
 * 看的是已经拼起来的当前段的最后一个字，也就是上一行的行尾。
 */
private val SENTENCE_END_PUNCT = setOf('。', '！', '？', '；', '…', '」', '』', '）', '”', '’')

/**
 * 闭标点：前面永远不加空格。上一行以数字/拉丁字母结尾（例如脚注号“石頭7”）、
 * 下一行以，。开头时也要直接拼，否则会拼出“7 ，”“14 。”这种错误空格。
 */
private val CLOSING_PUNCT = setOf('，', '。', '！', '？', '；', '：', '、', '」', '』', '）', '”', '’', '…')

/** 中文（或中文标点）：CJK 统一表意文字、扩展 A、部首/符号区、全角形。 */
private fun isCjkChar(c: Char): Boolean =
    (c in '\u2E80'..'\u9FFF') || (c in '\uF900'..'\uFAFF') || (c in '\uFF00'..'\uFFEF')

/** 合段结果：新段落列表 + 旧行号 -> 新段号的映射表（搬家书签/批注/上次位置用）。 */
/* 不加 private：Bookshelf.kt 的阅读进度也要用合段后的段数当总数。 */
data class MergeResult(
    val paragraphs: List<String>,
    val mapping: Map<Int, Int>
)

/**
 * 书籍正文合段：只看纯文本（换行和句末标点），不碰任何排版量——
 * 不用 TextMeasurer，不看字号、行距、页面尺寸。所以段落切分只由文本本身决定：
 * 改字号只改变仿真模式下一页放几行，绝不改变段落结构、映射表、TTS 的句子划分。
 *
 * 规则：
 * - \r\n、\r 统一成 \n；每行先 trim；空行是段落分界，空行本身不成段。
 * - 单换行：上一行以句末标点（。！？；…」』）”’）结尾就断段，否则是硬换行，接回去。
 * - 拼接：换行两边都是中文（或中文标点）就直接拼；下一行以闭标点（，。！？；：、」』）”’）开头也直接拼；
 *   否则拼一个空格（英文单词被断行时不断词，例如 "From\nthe" 变成 "From the"）。
 * - mapping：旧行号 -> 新段号；空行就近映射到它后面那段，没有后面就映射到前面那段。
 * - 只对书籍用；诗一行一段，不合（诗的分段没变，也不用搬家）。
 */
/* 不加 private：Bookshelf.kt 的阅读进度也要用合段后的段数当总数。 */
fun mergeBookParagraphs(content: String): MergeResult {
    val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split("\n")
    val paragraphs = mutableListOf<String>()
    val mapping = mutableMapOf<Int, Int>()
    val blankLines = mutableListOf<Int>()
    var current = ""
    for ((lineIdx, rawLine) in lines.withIndex()) {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            if (current.isNotEmpty()) {
                paragraphs.add(current)
                current = ""
            }
            blankLines.add(lineIdx)
            continue
        }
        if (current.isEmpty()) {
            current = line
            mapping[lineIdx] = paragraphs.size
        } else if (current.last() in SENTENCE_END_PUNCT) {
            paragraphs.add(current)
            current = line
            mapping[lineIdx] = paragraphs.size
        } else {
            // 硬换行：接回去
            val lastChar = current.last()
            val firstChar = line.first()
            current = if ((isCjkChar(lastChar) && isCjkChar(firstChar)) || firstChar in CLOSING_PUNCT) {
                current + line
            } else {
                "$current $line"
            }
            mapping[lineIdx] = paragraphs.size
        }
    }
    if (current.isNotEmpty()) paragraphs.add(current)
    // 空行映射：就近到后面那段，没有后面就到前面那段
    for (blankIdx in blankLines) {
        val next = ((blankIdx + 1)..lines.lastIndex).firstOrNull { it in mapping }
        val prev = ((blankIdx - 1) downTo 0).firstOrNull { it in mapping }
        val target = next?.let { mapping[it] } ?: prev?.let { mapping[it] }
        if (target != null) mapping[blankIdx] = target
    }
    return MergeResult(paragraphs, mapping)
}

private const val PARA_MERGED_KEY_PREFIX = "para_merged_"

/**
 * 搬家：合段之后旧段落下标全变了，把这本书存过的书签、批注、上次位置按映射表改写成新下标。
 * 只搬一次（每本书记一个标记）；搬过的书直接读新的，不再搬。
 * 必须在书签/批注/上次位置第一次被读出来用之前跑——调用点在 ReaderScreen 里
 * paragraphs 的 remember 块里，那几处 state 的初始化都在它后面。
 *
 * 批注搬家分两步走，避免新旧下标重叠时互相覆盖：先删掉所有要搬的旧条目，
 * 再按新下标写回（setHighlight/setNote 只改对应字段，落在同一新段的几条会并成一条，
 * 后写的非空字段为准；只有颜色或只有文字的条目不会互相清掉对方）。
 */
private fun migrateParagraphIndices(
    context: Context,
    bookId: String,
    mapping: Map<Int, Int>,
    notesStore: NotesStore
) {
    val prefs = readerPrefs(context)
    if (prefs.getBoolean(PARA_MERGED_KEY_PREFIX + bookId, false)) return
    // 书签：旧下标 -> 新下标，映射不到的（内容删剩下的脏数据）直接丢掉
    val oldBookmarks = loadBookmarks(context, bookId)
    if (oldBookmarks.isNotEmpty()) {
        val newBookmarks = oldBookmarks.mapNotNull { mapping[it] }.toSet()
        if (newBookmarks != oldBookmarks) saveBookmarks(context, bookId, newBookmarks)
    }
    // 上次位置：存的是列表位置 = 段落下标 + 1
    val oldPos = loadLastPosition(context, bookId)
    if (oldPos > 0) {
        val oldPara = oldPos - 1
        val newPara = mapping[oldPara]
        if (newPara != null && newPara != oldPara) {
            saveLastPosition(context, bookId, newPara + 1)
        }
    }
    // 批注
    val oldNotes = notesStore.notesFor(bookId)
    if (oldNotes.isNotEmpty()) {
        val snapshot = oldNotes.toMap()
        val toMove = snapshot.filterKeys { mapping[it] != it }
        if (toMove.isNotEmpty()) {
            toMove.keys.forEach { notesStore.removeEntry(bookId, it) }
            snapshot.forEach { (oldIdx, entry) ->
                if (mapping[oldIdx] == oldIdx) return@forEach
                val newIdx = mapping[oldIdx] ?: return@forEach
                HighlightColor.fromKey(entry.color)?.let { color ->
                    notesStore.setHighlight(bookId, newIdx, color)
                }
                if (entry.note.isNotBlank()) notesStore.setNote(bookId, newIdx, entry.note)
            }
        }
    }
    prefs.edit().putBoolean(PARA_MERGED_KEY_PREFIX + bookId, true).apply()
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(item: CatalogItem, bookshelf: BookshelfStore? = null, vocab: VocabStore? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 批注存储要放在分段前面：书籍合段后旧下标搬家要用到它
    val notesStore = remember { NotesStore(context) }
    // 分段：书籍先合段（EPUB 硬换行接回去），诗保持一行一段。
    // 合段只看纯文本（换行和句末标点），不看字号/行距/页面尺寸——
    // 所以改字号只改变一页放几行，不改变段落切分和下标映射。
    // 搬家在这里做：书签/批注/上次位置的 state 初始化都在下面，
    // 它们第一次读到的已经是搬家后的新下标，不存在“先读旧的、后搬家”的竞态。
    val paragraphs = remember(item.id, item.content) {
        val raw = item.content.ifEmpty { "（正文待管理员上传）" }
        if (item.type == "book") {
            val result = mergeBookParagraphs(raw)
            migrateParagraphIndices(context, item.id, result.mapping, notesStore)
            result.paragraphs
        } else {
            raw.split("\n")
        }
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

    // 批注笔记（二期第 5 块）：本地存，只影响显示，不动原文
    // （notesStore 在上面分段处就创建好了，合段搬家要用它）
    val notesMap = notesStore.notesFor(item.id)
    var noteTarget by remember { mutableStateOf<Int?>(null) } // 长按弹出的划线/批注菜单
    var editingNote by remember { mutableStateOf<Int?>(null) } // 写批注弹窗
    var showMyNotes by remember { mutableStateOf(false) } // 我的批注列表

    // 词典翻译（二期第 6 块）：只对属灵书籍有效，诗歌是短文本，不做
    var showLookup by remember { mutableStateOf(false) }

    // 仿真翻页（二期第 7 块）：默认滚动，别把现有行为默认改掉
    var pageMode by remember { mutableStateOf(loadPageTurnMode(context)) }
    // 仿真模式下正文区的实际尺寸（分页要按它量）
    var contentWidthPx by remember { mutableStateOf(0) }
    var contentHeightPx by remember { mutableStateOf(0) }
    // 待跳转的段落：分页还没算出来时先记下来，算出来再跳
    var pendingJumpPara by remember { mutableStateOf<Int?>(null) }
    // 锚定段落：字号/简繁一改页数就变，靠它回到读到的地方
    var anchorPara by remember(item.id) { mutableStateOf(0) }

    // ---- 仿真翻页：分页（按当前字号/行距/简繁实测）----
    val textMeasurer = rememberTextMeasurer()
    val textWidthPx = (contentWidthPx - with(density) { 40.dp.toPx() }).toInt().coerceAtLeast(0)
    val measureStyle = TextStyle(fontSize = fontSize.sp, lineHeight = lineHeight.sp, color = theme.text)
    // 页眉高度：标题 22sp + 分类 13sp + 简介 15sp + 各处留白（和下面 ReaderHeader 的结构一致）
    val headerHeightPx = remember(textMeasurer, textWidthPx, item.title, item.description) {
        if (textWidthPx <= 0) 0f
        else {
            val c = Constraints(maxWidth = textWidthPx)
            var h = with(density) { 8.dp.toPx() }
            h += textMeasurer.measure(
                AnnotatedString(item.title),
                TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold), constraints = c
            ).size.height
            h += with(density) { 6.dp.toPx() }
            h += textMeasurer.measure(
                AnnotatedString(item.category), TextStyle(fontSize = 13.sp), constraints = c
            ).size.height
            if (item.description.isNotEmpty()) {
                h += with(density) { 12.dp.toPx() }
                h += textMeasurer.measure(
                    AnnotatedString(item.description), TextStyle(fontSize = 15.sp), constraints = c
                ).size.height
            }
            h += with(density) { 12.dp.toPx() + 1.dp.toPx() + 16.dp.toPx() + 8.dp.toPx() }
            h
        }
    }
    // 页尾高度：和下面 ReaderTail 的结构一致
    val tailHeightPx = remember(textMeasurer, textWidthPx) {
        if (textWidthPx <= 0) 0f
        else {
            val c = Constraints(maxWidth = textWidthPx)
            val textH = textMeasurer.measure(
                AnnotatedString("正文已随 App 自带，离线也能读，无需下载。"),
                TextStyle(fontSize = 12.sp), constraints = c
            ).size.height
            with(density) { 8.dp.toPx() + 16.dp.toPx() + 1.dp.toPx() + 8.dp.toPx() } +
                textH + with(density) { 16.dp.toPx() + 8.dp.toPx() }
        }
    }
    val gapPx = with(density) { 10.dp.toPx() }
    // 批注行高度：只在段落最后一块下面出现（13sp，最多 2 行，上面留 6dp）
    val noteHeightOf: (Int) -> Float = remember(textMeasurer, textWidthPx, notesMap) {
        val cache = mutableMapOf<Int, Float>()
        val fn: (Int) -> Float = { paraIdx: Int ->
            cache.getOrPut(paraIdx) {
                val note = notesMap[paraIdx]?.note.orEmpty()
                if (note.isBlank() || textWidthPx <= 0) 0f
                else {
                    val c = Constraints(maxWidth = textWidthPx)
                    val h = textMeasurer.measure(
                        AnnotatedString("✎ $note"),
                        TextStyle(fontSize = 13.sp), constraints = c, maxLines = 2
                    ).size.height
                    h + with(density) { 6.dp.toPx() }
                }
            }
        }
        fn
    }
    val pages = remember(
        displayParagraphs, textMeasurer, measureStyle, textWidthPx, contentHeightPx,
        headerHeightPx, tailHeightPx, noteHeightOf, pageMode
    ) {
        if (pageMode != PageTurnMode.PAGE || textWidthPx <= 0 || contentHeightPx <= 0) emptyList()
        else paginateBook(
            paragraphs = displayParagraphs,
            measurer = textMeasurer,
            textStyle = measureStyle,
            headerHeightPx = headerHeightPx,
            tailHeightPx = tailHeightPx,
            noteHeightPx = noteHeightOf,
            textWidthPx = textWidthPx,
            pageHeightPx = contentHeightPx.toFloat(),
            gapPx = gapPx
        )
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })

    // 打开时回到上次读到的地方（列表位置 1..N 才是正文段落，0 是标题头）
    LaunchedEffect(item.id) {
        val saved = loadLastPosition(context, item.id)
        if (saved in 1..paragraphs.size) {
            if (pageMode == PageTurnMode.PAGE) {
                // 分页还没算出来，先记下来，算出来再跳（下面的分页 effect 会处理）
                pendingJumpPara = saved - 1
            } else {
                listState.scrollToItem(saved)
            }
            resumedHint = true
            delay(3000)
            resumedHint = false
        }
    }

    // 离开页面时保存阅读位置（存段落下标+1，两种模式通用）
    val anchorParaState = rememberUpdatedState(anchorPara)
    DisposableEffect(item.id, pageMode) {
        onDispose {
            val para = if (pageMode == PageTurnMode.PAGE) anchorParaState.value
                else (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
            saveLastPosition(context, item.id, para + 1)
        }
    }

    // 进阅读器就算读过：书架里的这本书最近阅读时间更新一下（不在书架的不动；诗歌不进书架）
    LaunchedEffect(item.id) {
        if (item.type == "book") bookshelf?.markRead(item.id)
    }

    // 关键词变了：重新找匹配，跳到第一个
    // 搜索时关键词和段落都先归一化成简体再比，所以繁体模式下打繁体字、
    // 简体模式下打简体字都能找到；转换是逐字一对一，下标和显示文本一一对应。
    /** 跳到某一段：滚动模式滚列表，仿真模式翻到那段所在的页。
     * 分页还没算出来时先记下来（pendingJumpPara），算出来再跳。
     * 注意：必须放在搜索 LaunchedEffect 前面——Kotlin 的局部函数只能在声明之后调用。 */
    fun jumpToPara(paraIdx: Int, animate: Boolean = true) {
        val target = paraIdx.coerceIn(0, paragraphs.size - 1)
        if (pageMode == PageTurnMode.PAGE) {
            if (pages.isNotEmpty()) {
                val page = pageOfPara(pages, target)
                scope.launch {
                    if (animate) pagerState.animateScrollToPage(page)
                    else pagerState.scrollToPage(page)
                }
            } else {
                pendingJumpPara = target
            }
        } else {
            scope.launch { listState.scrollToItem(target + 1) }
        }
    }

    fun prevPage() {
        scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) }
    }

    fun nextPage() {
        scope.launch {
            pagerState.animateScrollToPage(
                (pagerState.currentPage + 1).coerceAtMost((pages.size - 1).coerceAtLeast(0))
            )
        }
    }

    /** 点段开始听：先停掉正在读的（清掉 TTS 队列），再从这段重开。
     * 不能直接调 startReading：同一本书暂停中时它会 resume 旧位置、忽略新段落；
     * 正在读时直接调会把新句子追加到队列尾，和正在读的叠起来。 */
    fun startReadingFrom(paraIdx: Int) {
        if (TtsReader.isActive) TtsReader.stop()
        TtsReader.startReading(context, item, paragraphs, paraIdx)
    }

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
            if (found.isNotEmpty()) jumpToPara(found[0], animate = false)
        }
    }

    fun jumpToMatch(i: Int) {
        if (matchIndices.isEmpty()) return
        val next = (i % matchIndices.size + matchIndices.size) % matchIndices.size
        currentMatch = next
        jumpToPara(matchIndices[next])
    }

    // 当前段：滚动模式看列表首项，仿真模式看当前页的第一个段落块
    val currentParaIdx = if (pageMode == PageTurnMode.PAGE) {
        pages.getOrNull(pagerState.currentPage)?.chunks
            ?.firstOrNull { it.paraIdx >= 0 }?.paraIdx ?: 0
    } else {
        (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
    }

    fun toggleBookmark() {
        val paraIdx = currentParaIdx
        val updated = bookmarks.toMutableSet()
        if (updated.contains(paraIdx)) updated.remove(paraIdx) else updated.add(paraIdx)
        bookmarks = updated
        saveBookmarks(context, item.id, updated)
        saveLastPosition(context, item.id, paraIdx + 1)
    }

    val isBookmarked = bookmarks.contains(currentParaIdx)

    // 听书状态（TtsReader 是进程级单例，离开页面再回来还能接着控）
    val ttsActive = TtsReader.isActive && TtsReader.currentBook?.id == item.id
    val ttsPara = TtsReader.currentPara
    val ttsSent = TtsReader.currentSent

    // 跟读：读到哪滚到哪；当前段已经在屏幕上就不动，免得来回跳
    LaunchedEffect(ttsActive, ttsPara, ttsSent, pageMode, pages) {
        if (ttsActive && ttsPara in paragraphs.indices) {
            if (pageMode == PageTurnMode.PAGE) {
                if (pages.isNotEmpty()) {
                    val page = pageOfPara(pages, ttsPara)
                    if (page != pagerState.currentPage) {
                        pagerState.animateScrollToPage(page)
                    }
                }
            } else {
                val pos = ttsPara + 1
                val visible = listState.layoutInfo.visibleItemsInfo
                val first = visible.firstOrNull()?.index ?: 0
                val last = visible.lastOrNull()?.index ?: 0
                if (pos < first || pos > last) {
                    listState.scrollToItem(pos)
                }
            }
        }
    }

    // 锚定段落：仿真模式下翻页时记下来，滚动模式下滚到哪记哪；
    // 字号/简繁一改页数就变，靠它回到读到的地方。
    LaunchedEffect(pagerState.currentPage, pageMode) {
        if (pageMode == PageTurnMode.PAGE) {
            pages.getOrNull(pagerState.currentPage)?.chunks
                ?.firstOrNull { it.paraIdx >= 0 }?.paraIdx?.let { anchorPara = it }
        }
    }
    LaunchedEffect(listState.firstVisibleItemIndex, pageMode) {
        if (pageMode == PageTurnMode.SCROLL) {
            anchorPara = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
        }
    }

    // 分页结果出来/变了：先把待跳转的段落跳过去，否则回到锚定段落所在的页
    LaunchedEffect(pages, pageMode, pendingJumpPara) {
        if (pageMode != PageTurnMode.PAGE || pages.isEmpty()) return@LaunchedEffect
        val target = pendingJumpPara ?: anchorPara
        pendingJumpPara = null
        val page = pageOfPara(pages, target)
        if (page != pagerState.currentPage) pagerState.scrollToPage(page)
    }

    // 切回滚动模式：滚到锚定段落（第一次打开不触发，免得和恢复位置打架）
    var firstModeRun by remember { mutableStateOf(true) }
    LaunchedEffect(pageMode) {
        if (firstModeRun) {
            firstModeRun = false
            return@LaunchedEffect
        }
        if (pageMode == PageTurnMode.SCROLL) {
            listState.scrollToItem((anchorPara + 1).coerceIn(0, paragraphs.size))
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
                    onClick = { showMyNotes = true },
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    val noteCount = notesStore.entryCount(item.id)
                    Text(if (noteCount > 0) "我的批注（$noteCount）" else "我的批注", fontSize = 13.sp)
                }
                // 查词：只对属灵书籍有效（诗歌是短文本，不做）；不占长按，长按已经是批注菜单
                if (item.type == "book" && vocab != null) {
                    OutlinedButton(
                        onClick = { showLookup = true },
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) { Text("查词", fontSize = 13.sp) }
                }
                OutlinedButton(
                    onClick = {
                        if (ttsActive) {
                            TtsReader.togglePause()
                        } else {
                            TtsReader.startReading(context, item, paragraphs, currentParaIdx)
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

            // 正文：滚动模式是 LazyColumn 一段一项；仿真模式是一页一屏
            if (pageMode == PageTurnMode.PAGE) {
                PageTurner(
                    pages = pages,
                    pagerState = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { contentWidthPx = it.width; contentHeightPx = it.height },
                    onPageTap = { x, w -> if (x < w / 2) prevPage() else nextPage() },
                    pageContent = { page, _ ->
                        Column(modifier = Modifier.fillMaxSize()) {
                            page.chunks.forEachIndexed { chunkPos, chunk ->
                                if (chunkPos > 0) Spacer(modifier = Modifier.height(10.dp))
                                when {
                                    chunk.paraIdx == HEADER_PARA -> ReaderHeader(item, theme)
                                    chunk.paraIdx == TAIL_PARA -> ReaderTail(theme)
                                    else -> {
                                        val paraIdx = chunk.paraIdx
                                        val paragraph = displayParagraphs[paraIdx]
                                        if (paragraph.isBlank()) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                        } else {
                                            // 高亮先在整段上算好（搜索/跟读的下标都是整段的），
                                            // 再按块切出来，Span 位置不会错
                                            val fullAnnotated = when {
                                                ttsActive && paraIdx == ttsPara ->
                                                    highlightTtsSentence(paragraph, ttsSent)
                                                else -> highlightMatches(
                                                    paragraph, if (showSearch) query else ""
                                                )
                                            }
                                            val chunkText = fullAnnotated.subSequence(
                                                chunk.startChar, chunk.endChar
                                            )
                                            val entry = notesMap[paraIdx]
                                            ParagraphBlock(
                                                idx = paraIdx,
                                                text = chunkText,
                                                noteColor = HighlightColor.fromKey(entry?.color),
                                                noteText = entry?.note.orEmpty(),
                                                showNoteLine = chunk.endChar >= paragraph.length,
                                                showTrailingGap = false,
                                                fontSize = fontSize,
                                                lineHeight = lineHeight,
                                                textColor = theme.text,
                                                onEditNote = { editingNote = paraIdx },
                                                onLongPress = { noteTarget = paraIdx },
                                                onTapTurn = { x, w ->
                                                    if (x < w / 2) prevPage() else nextPage()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    item { ReaderHeader(item, theme) }
                    items(displayParagraphs.size) { idx ->
                        val paragraph = displayParagraphs[idx]
                        if (paragraph.isBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                        } else {
                            val fullAnnotated = when {
                                ttsActive && idx == ttsPara -> highlightTtsSentence(paragraph, ttsSent)
                                else -> highlightMatches(paragraph, if (showSearch) query else "")
                            }
                            val entry = notesMap[idx]
                            ParagraphBlock(
                                idx = idx,
                                text = fullAnnotated,
                                noteColor = HighlightColor.fromKey(entry?.color),
                                noteText = entry?.note.orEmpty(),
                                showNoteLine = true,
                                showTrailingGap = true,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                textColor = theme.text,
                                onEditNote = { editingNote = idx },
                                onLongPress = { noteTarget = idx },
                                onTapTurn = null,
                                onTapRead = { startReadingFrom(idx) }
                            )
                        }
                    }
                    item { ReaderTail(theme) }
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

            // 翻页：滚动模式按一屏的高度上下滚；仿真模式左右翻页，中间显示第几页
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pageMode == PageTurnMode.PAGE) {
                    OutlinedButton(
                        onClick = { prevPage() },
                        enabled = pagerState.currentPage > 0
                    ) { Text("上一页", fontSize = 14.sp) }
                    Text(
                        "第 ${pagerState.currentPage + 1} / ${pages.size} 页",
                        fontSize = 13.sp,
                        color = theme.muted
                    )
                    OutlinedButton(
                        onClick = { nextPage() },
                        enabled = pagerState.currentPage < pages.size - 1
                    ) { Text("下一页", fontSize = 14.sp) }
                } else {
                    OutlinedButton(onClick = {
                        scope.launch { listState.animateScrollBy(-viewportPx) }
                    }) { Text("上一页", fontSize = 14.sp) }
                    OutlinedButton(onClick = {
                        scope.launch { listState.animateScrollBy(viewportPx) }
                    }) { Text("下一页", fontSize = 14.sp) }
                }
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
                    OptionRow(
                        label = "翻页方式",
                        options = PageTurnMode.values().map { it.label },
                        selected = pageMode.ordinal,
                        onSelect = {
                            pageMode = PageTurnMode.values()[it]
                            savePageTurnMode(context, pageMode)
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
                                    jumpToPara(paraIdx)
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
                                        jumpToPara(paraIdx)
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

    // 长按段落：划线 / 写批注
    noteTarget?.let { targetIdx ->
        ParagraphNoteMenuDialog(
            paraIdx = targetIdx,
            snippet = displayParagraphs.getOrNull(targetIdx)?.trim().orEmpty().take(60),
            entry = notesMap[targetIdx],
            muted = theme.muted,
            onPickColor = { color ->
                notesStore.setHighlight(item.id, targetIdx, color)
                noteTarget = null
            },
            onClearHighlight = {
                notesStore.setHighlight(item.id, targetIdx, null)
                noteTarget = null
            },
            onEditNote = {
                noteTarget = null
                editingNote = targetIdx
            },
            onListenFromHere = {
                noteTarget = null
                startReadingFrom(targetIdx)
            },
            onDismiss = { noteTarget = null }
        )
    }

    // 写批注 / 编辑批注
    editingNote?.let { editIdx ->
        NoteEditorDialog(
            paraIdx = editIdx,
            initialNote = notesMap[editIdx]?.note.orEmpty(),
            hasNote = !notesMap[editIdx]?.note.isNullOrBlank(),
            onSave = { text ->
                notesStore.setNote(item.id, editIdx, text)
                editingNote = null
            },
            onDelete = {
                notesStore.setNote(item.id, editIdx, "")
                editingNote = null
            },
            onDismiss = { editingNote = null }
        )
    }

    // 我的批注：列表 + 导出
    if (showMyNotes) {
        MyNotesDialog(
            title = item.title,
            paragraphs = paragraphs,
            displayParagraphs = displayParagraphs,
            notes = notesMap,
            muted = theme.muted,
            onJump = { paraIdx ->
                showMyNotes = false
                jumpToPara(paraIdx)
            },
            onDelete = { paraIdx ->
                notesStore.removeEntry(item.id, paraIdx)
            },
            onDismiss = { showMyNotes = false }
        )
    }

    // 听书：语速选择（滑杆 0.5x–3.0x，0.1 一格；松手才生效，拖动时不反复重读）
    if (showTtsSpeed) {
        var sliderSpeed by remember { mutableStateOf(TtsReader.speed) }
        AlertDialog(
            onDismissRequest = { showTtsSpeed = false },
            confirmButton = {
                TextButton(onClick = {
                    TtsReader.updateSpeed(sliderSpeed)
                    showTtsSpeed = false
                }) { Text("完成") }
            },
            title = { Text("语速") },
            text = {
                Column {
                    Text(
                        "当前 ${"%.1fx".format(java.util.Locale.US, sliderSpeed)}",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = sliderSpeed,
                        onValueChange = { sliderSpeed = it },
                        onValueChangeFinished = { TtsReader.updateSpeed(sliderSpeed) },
                        valueRange = TtsReader.SPEED_MIN..TtsReader.SPEED_MAX,
                        // 0.5–3.0 以 0.1 为一格是 25 段，中间 24 个停靠点
                        steps = 24,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0.5x", fontSize = 12.sp, color = theme.muted)
                        Text("3.0x", fontSize = 12.sp, color = theme.muted)
                    }
                }
            }
        )
    }

    // 听书：音色选择（每行：点名字选音色，点“试听”用这个音色读一句样例；样例不改已选音色）
    if (showTtsVoice) {
        AlertDialog(
            onDismissRequest = {
                TtsReader.cancelPreview()
                showTtsVoice = false
            },
            confirmButton = {
                TextButton(onClick = {
                    TtsReader.cancelPreview()
                    showTtsVoice = false
                }) { Text("完成") }
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { TtsReader.setVoice(voice.name) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            if (TtsReader.voiceName == voice.name) "✓ ${voice.name}" else voice.name,
                                            fontSize = 14.sp,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Start
                                        )
                                        Text(
                                            TtsReader.voiceLabel(voice),
                                            fontSize = 12.sp,
                                            color = theme.muted,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Start
                                        )
                                    }
                                }
                                TextButton(onClick = { TtsReader.previewVoice(voice) }) {
                                    Text(
                                        if (TtsReader.previewVoiceName == voice.name) "试听中…" else "试听",
                                        fontSize = 13.sp
                                    )
                                }
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

    // 查词对话框：只对属灵书籍有效
    if (showLookup && item.type == "book" && vocab != null) {
        LookupDialog(
            initialWord = "",
            bookTitle = item.title,
            vocab = vocab,
            onDismiss = { showLookup = false }
        )
    }
}

/** 书头：标题 + 分类 + 简介（滚动模式和仿真模式共用；仿真模式下页眉高度按它量）。 */
@Composable
private fun ReaderHeader(item: CatalogItem, theme: ReaderTheme) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(item.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (item.mustRead) theme.mustRead else theme.text)
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

/** 书尾提示（滚动模式和仿真模式共用）。 */
@Composable
private fun ReaderTail(theme: ReaderTheme) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Spacer(modifier = Modifier.height(16.dp))
        Divider(color = theme.muted)
        Spacer(modifier = Modifier.height(8.dp))
        Text("正文已随 App 自带，离线也能读，无需下载。", fontSize = 12.sp, color = theme.muted)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * 一段正文（滚动模式和仿真模式共用）。
 * 批注笔记：整段是划线底色（半透明，盖在主题底色上）；
 * 搜索命中词和听书跟读句子的金色底是标在字上的，叠在段落底色上面。
 * 滚动模式：点按/长按走 combinedClickable（点一段从这段开始听；长按出批注菜单）。
 * 批注编辑不走段落点按，走段落下面那行“✎ 批注文字”（可点）和长按菜单里的“写批注/编辑批注”。
 * 仿真模式：点按分两半翻页（左半边上一页、右半边下一页），长按还是批注菜单；
 * 有批注的段落点按先看批注，不翻页。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParagraphBlock(
    idx: Int,
    text: AnnotatedString,
    noteColor: HighlightColor?,
    noteText: String,
    showNoteLine: Boolean,
    showTrailingGap: Boolean,
    fontSize: Int,
    lineHeight: Float,
    textColor: Color,
    onEditNote: () -> Unit,
    onLongPress: () -> Unit,
    onTapTurn: ((tapX: Float, width: Float) -> Unit)? = null,
    onTapRead: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(noteColor?.background ?: Color.Transparent)
            .then(
                if (onTapTurn != null) {
                    Modifier.pointerInput(idx, text) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (noteText.isNotBlank()) onEditNote()
                                else onTapTurn(offset.x, size.width.toFloat())
                            },
                            onLongPress = { onLongPress() }
                        )
                    }
                } else {
                    Modifier.combinedClickable(
                        onClick = { onTapRead?.invoke() },
                        onLongClick = onLongPress
                    )
                }
            )
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = text,
                fontSize = fontSize.sp,
                lineHeight = lineHeight.sp,
                color = textColor
            )
            // 批注标记：点一下看/改这条批注
            if (showNoteLine && noteText.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "✎ $noteText",
                    fontSize = 13.sp,
                    color = Color(0xFF9A7B2D),
                    maxLines = 2,
                    modifier = Modifier.clickable { onEditNote() }
                )
            }
            if (showTrailingGap) Spacer(modifier = Modifier.height(10.dp))
        }
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
