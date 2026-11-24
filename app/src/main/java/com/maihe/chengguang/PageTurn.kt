package com.maihe.chengguang

import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import kotlin.math.abs

// ---------- 翻页方式（存在 "chengguang_reader"，和 Reader.kt 的 READER_PREFS 同一个文件） ----------

enum class PageTurnMode(val key: String, val label: String) {
    SCROLL("scroll", "滚动"),
    PAGE("page", "仿真")
}

private const val PAGE_MODE_KEY = "page_mode_idx"

fun loadPageTurnMode(context: Context): PageTurnMode {
    val idx = context.getSharedPreferences("chengguang_reader", Context.MODE_PRIVATE)
        .getInt(PAGE_MODE_KEY, 0).coerceIn(0, PageTurnMode.values().size - 1)
    return PageTurnMode.values()[idx]
}

fun savePageTurnMode(context: Context, mode: PageTurnMode) {
    context.getSharedPreferences("chengguang_reader", Context.MODE_PRIVATE)
        .edit().putInt(PAGE_MODE_KEY, mode.ordinal).apply()
}

// ---------- 分页 ----------

/** 页内一块：一段正文的切片（或页眉/页尾）。paraIdx < 0 是特殊块。 */
const val HEADER_PARA = -1
const val TAIL_PARA = -2

data class PageChunk(val paraIdx: Int, val startChar: Int, val endChar: Int)

data class Page(val chunks: List<PageChunk>)

/**
 * 按实测尺寸分页：每段先用 TextMeasurer 量出整段的行信息，再按行装箱；
 * 单段比一页还长时按行切开（行边界用 getLineStart 取，保证不断字、不丢字）。
 * 段落下标不变——页只是段落的显示方式，书签/批注/搜索/听书都还认段落下标。
 *
 * 块与块之间固定留 gapPx：分页按“每块前面留 gap（页内第一块不留）”算，
 * 渲染时每块前面（除页内第一块）也留一个 gap，两边一致才不会溢出或留白。
 * 每块渲染成独立的 Text 时，高度 = 行高之和 + 首行顶部偏移（getLineTop(0)，
 * 只和字号/行距有关，和内容无关），所以每块都要加上这一项。
 * 有批注的段落，批注行（13sp，最多 2 行）只出现在段落的最后一块下面，
 * 高度由 noteHeightPx 给，也算进最后一块里。
 */
fun paginateBook(
    paragraphs: List<String>,
    measurer: TextMeasurer,
    textStyle: TextStyle,
    headerHeightPx: Float,
    tailHeightPx: Float,
    noteHeightPx: (paraIdx: Int) -> Float,
    textWidthPx: Int,
    pageHeightPx: Float,
    gapPx: Float
): List<Page> {
    if (textWidthPx <= 0 || pageHeightPx <= 0f) return emptyList()
    val constraints = Constraints(maxWidth = textWidthPx)
    val layouts = paragraphs.map { p ->
        if (p.isBlank()) null
        else measurer.measure(AnnotatedString(p), textStyle, constraints)
    }
    val pages = mutableListOf<Page>()
    var cur = mutableListOf<PageChunk>()
    var used = 0f

    fun flush() {
        if (cur.isNotEmpty()) {
            pages.add(Page(cur.toList()))
            cur = mutableListOf()
            used = 0f
        }
    }

    // 页眉：只在第一页
    cur.add(PageChunk(HEADER_PARA, 0, 0))
    used += headerHeightPx

    paragraphs.forEachIndexed { idx, p ->
        if (p.isBlank()) {
            // 空段就是一段空白：页内有内容才占地方，页首的空段直接跳过（反正看不见）
            if (cur.isNotEmpty() && used + gapPx + gapPx <= pageHeightPx + 0.5f) {
                used += gapPx + gapPx
                cur.add(PageChunk(idx, 0, 0))
            }
            return@forEachIndexed
        }
        val layout = layouts[idx]!!
        val firstLineTop = layout.getLineTop(0)
        val noteH = noteHeightPx(idx)
        var line = 0
        while (line < layout.lineCount) {
            val gapCost = if (cur.isEmpty()) 0f else gapPx
            val avail = pageHeightPx - used - gapCost
            var lastFit = line - 1
            var h = 0f
            var l = line
            while (l < layout.lineCount) {
                var lh = layout.getLineBottom(l) - layout.getLineTop(l)
                // 段落最后一行下面还挂着批注行，高度一起算
                if (l == layout.lineCount - 1) lh += noteH
                if (h + lh <= avail + 0.5f) {
                    h += lh
                    lastFit = l
                    l++
                } else break
            }
            if (lastFit < line) {
                if (cur.isEmpty()) {
                    // 单行比一页还高（理论上不会）：硬放一行，避免死循环
                    lastFit = line
                    h = layout.getLineBottom(line) - layout.getLineTop(line) +
                        if (line == layout.lineCount - 1) noteH else 0f
                } else {
                    flush()
                    continue
                }
            }
            val startChar = layout.getLineStart(line)
            // 下一块从下一行的行首开始切，中间一个字都不会丢
            val endChar = if (lastFit + 1 < layout.lineCount) layout.getLineStart(lastFit + 1) else p.length
            cur.add(PageChunk(idx, startChar, endChar))
            used += gapCost + firstLineTop + h
            line = lastFit + 1
            if (line < layout.lineCount) flush()
        }
    }

    // 页尾：放不下就另起一页
    if (cur.isNotEmpty() && used + gapPx + tailHeightPx > pageHeightPx + 0.5f) flush()
    val tailGap = if (cur.isEmpty()) 0f else gapPx
    cur.add(PageChunk(TAIL_PARA, 0, 0))
    used += tailGap + tailHeightPx
    flush()
    if (pages.isEmpty()) pages.add(Page(listOf(PageChunk(HEADER_PARA, 0, 0))))
    return pages
}

/** 段落下标 -> 它出现的第一页（跳书签/搜索/目录/听书用）。 */
fun pageOfPara(pages: List<Page>, paraIdx: Int): Int {
    pages.forEachIndexed { i, page ->
        if (page.chunks.any { it.paraIdx == paraIdx }) return i
    }
    return (pages.size - 1).coerceAtLeast(0)
}

// ---------- 翻页器 ----------

/**
 * 仿真翻页：一页一屏，左右滑翻页（HorizontalPager 自带拖拽、惯性和回弹）。
 * 翻页动画是“倾斜 + 阴影”（v1）：正在翻的页面绕 Y 轴转过去，边缘压一层暗色；
 * 不是真卷曲——卷曲要逐像素算，v1 先做稳的。
 * 点按翻页：页面空白处（页眉/页尾/段间）点一下按左右半边翻页；
 * 段落上的点按由段落自己处理（有批注先看批注，没批注再按左右半边翻页）。
 */
@Composable
fun PageTurner(
    pages: List<Page>,
    pagerState: PagerState,
    onPageTap: (tapX: Float, width: Float) -> Unit,
    pageContent: @Composable (page: Page, pageIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 还没量出尺寸、分页还没算出来：先留空，等一帧
    if (pages.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val density = LocalDensity.current.density
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        beyondViewportPageCount = 1
    ) { pageIndex ->
        val page = pages[pageIndex]
        val pageOffset = (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
        val shadowAlpha = abs(pageOffset).coerceIn(0f, 1f) * 0.3f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = pageOffset * 20f
                    cameraDistance = 12f * density
                }
                .drawBehind {
                    if (shadowAlpha > 0.01f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = shadowAlpha),
                                    Color.Transparent
                                ),
                                startX = 0f,
                                endX = size.width * 0.45f
                            )
                        )
                    }
                }
                .pointerInput(pageIndex) {
                    detectTapGestures(onTap = { offset ->
                        onPageTap(offset.x, size.width.toFloat())
                    })
                }
        ) {
            pageContent(page, pageIndex)
        }
    }
}
