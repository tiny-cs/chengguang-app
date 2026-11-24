package com.maihe.chengguang

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 批注笔记（读书器二期第 5 块）。
 *
 * 按段落划线、写批注：长按正文里的一段弹出菜单，选 5 色之一划线，或写一条
 * 文字批注；“我的批注”里看全部、跳过去、删单条、导出 Markdown / TXT。
 *
 * 全部数据只存在这台手机上（SharedPreferences "chengguang_notes"，一本书
 * 一个记录），不动服务器、不动目录、不动原文。只存段号：段号和书签用的是
 * 同一套——正文段落下标（0 是第一段），所以简繁切换、搜索、听书、书签、
 * 阅读进度都不受影响；段落数量和顺序不变，划线和批注就不会错位。
 *
 * 划线底色是半透明的：盖在日间白底、夜间黑底、护眼米黄底上都能看清正文。
 * 搜索命中词和听书跟读句子的金色底是标在字上的（SpanStyle），叠在段落
 * 底色上面，两层不打架——金色词永远在最上面。
 */

/** 划线颜色：5 色。solid 是按钮/色点用的实色，background 是段落底色（半透明）。 */
enum class HighlightColor(val key: String, val label: String, val solid: Color) {
    YELLOW("yellow", "黄色", Color(0xFFFFEB3B)),
    GREEN("green", "绿色", Color(0xFF81C784)),
    BLUE("blue", "蓝色", Color(0xFF64B5F6)),
    PINK("pink", "粉色", Color(0xFFF48FB1)),
    PURPLE("purple", "紫色", Color(0xFFBA68C8));

    /** 段落底色：半透明，日间/夜间/护眼三种主题下正文都可读。 */
    val background: Color get() = solid.copy(alpha = 0.5f)

    companion object {
        fun fromKey(key: String?): HighlightColor? = values().find { it.key == key }
    }
}

/** 一段的划线 + 批注。color 为 null 表示没划线，note 为空表示没写批注。 */
@Serializable
data class NoteEntry(
    val color: String? = null,
    val note: String = ""
)

/** 一本书的全部批注（段号 -> 记录）。 */
@Serializable
data class BookNotes(
    val entries: Map<Int, NoteEntry> = emptyMap()
)

private const val NOTES_PREFS = "chengguang_notes"
private val notesJson = Json { ignoreUnknownKeys = true }

class NotesStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE)

    /** 每次改动后 version +1，读了 version 的 Composable 会重组并重新读状态。 */
    var version by mutableStateOf(0)
        private set

    fun refresh() {
        version++
    }

    private fun loadAll(): MutableMap<String, BookNotes> {
        val raw = prefs.getString("notes", null) ?: return mutableMapOf()
        return try {
            notesJson.decodeFromString<Map<String, BookNotes>>(raw).toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveAll(all: Map<String, BookNotes>) {
        prefs.edit().putString("notes", notesJson.encodeToString(all)).apply()
        refresh()
    }

    /** 这本书的批注（段号 -> 记录）。 */
    fun notesFor(bookId: String): Map<Int, NoteEntry> {
        @Suppress("UNUSED_EXPRESSION") version
        return loadAll()[bookId]?.entries ?: emptyMap()
    }

    /** 这本书有几条批注（划线或文字，算一条）。 */
    fun entryCount(bookId: String): Int {
        @Suppress("UNUSED_EXPRESSION") version
        return loadAll()[bookId]?.entries?.size ?: 0
    }

    private fun updateEntry(bookId: String, paraIdx: Int, transform: (NoteEntry?) -> NoteEntry?) {
        val all = loadAll()
        val book = all[bookId] ?: BookNotes()
        val entries = book.entries.toMutableMap()
        val updated = transform(entries[paraIdx])
        if (updated == null) entries.remove(paraIdx) else entries[paraIdx] = updated
        if (entries.isEmpty()) all.remove(bookId) else all[bookId] = BookNotes(entries)
        saveAll(all)
    }

    /** 划线：color 为 null 表示取消划线（批注还在的话只清颜色）。 */
    fun setHighlight(bookId: String, paraIdx: Int, color: HighlightColor?) {
        updateEntry(bookId, paraIdx) { old ->
            val entry = old ?: NoteEntry()
            if (color == null && entry.note.isBlank()) null
            else entry.copy(color = color?.key)
        }
    }

    /** 写批注：空文字表示删掉批注（划线还在的话只清文字）。 */
    fun setNote(bookId: String, paraIdx: Int, note: String) {
        val trimmed = note.trim()
        updateEntry(bookId, paraIdx) { old ->
            val entry = old ?: NoteEntry()
            if (trimmed.isEmpty() && entry.color == null) null
            else entry.copy(note = trimmed)
        }
    }

    /** 删掉这一段的划线和批注。 */
    fun removeEntry(bookId: String, paraIdx: Int) {
        updateEntry(bookId, paraIdx) { null }
    }
}

// ---------- 导出 ----------

/** 导出 Markdown：书名 + 每条划线段落原文（带颜色名）+ 批注，按段落顺序排。 */
fun buildNotesExportMarkdown(title: String, paragraphs: List<String>, notes: Map<Int, NoteEntry>): String {
    val sb = StringBuilder()
    sb.append("# 《").append(title).append("》批注\n\n")
    sb.append("共 ").append(notes.size).append(" 条\n\n")
    notes.entries.sortedBy { it.key }.forEach { (idx, entry) ->
        sb.append("## 第 ").append(idx + 1).append(" 段")
        val color = HighlightColor.fromKey(entry.color)
        if (color != null) sb.append("（").append(color.label).append("划线）")
        sb.append("\n\n")
        val text = paragraphs.getOrNull(idx)?.trim().orEmpty()
        if (text.isNotEmpty()) sb.append("> ").append(text).append("\n\n")
        if (entry.note.isNotBlank()) sb.append("批注：").append(entry.note).append("\n\n")
        sb.append("---\n\n")
    }
    return sb.toString().trimEnd() + "\n"
}

/** 导出 TXT：和 Markdown 一样的内容，去掉 #、>、--- 这些标记。 */
fun buildNotesExportText(title: String, paragraphs: List<String>, notes: Map<Int, NoteEntry>): String {
    val sb = StringBuilder()
    sb.append("《").append(title).append("》批注（共 ").append(notes.size).append(" 条）\n\n")
    notes.entries.sortedBy { it.key }.forEach { (idx, entry) ->
        sb.append("第 ").append(idx + 1).append(" 段")
        val color = HighlightColor.fromKey(entry.color)
        if (color != null) sb.append("（").append(color.label).append("划线）")
        sb.append("\n")
        val text = paragraphs.getOrNull(idx)?.trim().orEmpty()
        if (text.isNotEmpty()) sb.append(text).append("\n")
        if (entry.note.isNotBlank()) sb.append("批注：").append(entry.note).append("\n")
        sb.append("\n")
    }
    return sb.toString().trimEnd() + "\n"
}

/** 用系统分享把导出的文字发出去（微信、存文件都由用户自己选）。 */
fun shareNotesText(context: Context, text: String, title: String, chooserTitle: String = "分享批注") {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

/** 把导出的文字复制到剪贴板。 */
fun copyNotesText(context: Context, text: String, label: String = "城光批注") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

// ---------- 弹窗 ----------

/** 长按段落弹出的菜单：选划线颜色 / 取消划线 / 写批注 / 从这里听。 */
@Composable
fun ParagraphNoteMenuDialog(
    paraIdx: Int,
    snippet: String,
    entry: NoteEntry?,
    muted: Color,
    onPickColor: (HighlightColor) -> Unit,
    onClearHighlight: () -> Unit,
    onEditNote: () -> Unit,
    onListenFromHere: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("第 ${paraIdx + 1} 段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    snippet.ifEmpty { "（空段落）" },
                    fontSize = 14.sp,
                    color = muted,
                    maxLines = 3
                )
                Text("划线", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HighlightColor.values().forEach { color ->
                        Button(
                            onClick = { onPickColor(color) },
                            colors = ButtonDefaults.buttonColors(containerColor = color.solid),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Text(
                                if (entry?.color == color.key) "✓ ${color.label}" else color.label,
                                fontSize = 13.sp,
                                color = Color.Black
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entry?.color != null) {
                        OutlinedButton(onClick = onClearHighlight) { Text("取消划线", fontSize = 13.sp) }
                    }
                    OutlinedButton(onClick = onEditNote) {
                        Text(if (entry?.note.isNullOrBlank()) "写批注" else "编辑批注", fontSize = 13.sp)
                    }
                }
                OutlinedButton(onClick = onListenFromHere) {
                    Text("从这里听", fontSize = 13.sp)
                }
            }
        }
    )
}

/** 写批注 / 编辑批注：保存空文字等于删掉批注。 */
@Composable
fun NoteEditorDialog(
    paraIdx: Int,
    initialNote: String,
    hasNote: Boolean,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(initialNote) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("第 ${paraIdx + 1} 段批注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("写下你的批注…", fontSize = 14.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    minLines = 4
                )
                if (hasNote) {
                    TextButton(onClick = onDelete) { Text("删除这条批注", fontSize = 13.sp) }
                }
                Text("留空保存就等于删掉批注。", fontSize = 12.sp, color = Color.Gray)
            }
        }
    )
}

/**
 * 我的批注：本本书的划线和批注列表，点一条跳到对应段落，每行可删；
 * 下面是导出：分享 Markdown、分享 TXT、复制全文（复制的是 Markdown 版）。
 */
@Composable
fun MyNotesDialog(
    title: String,
    paragraphs: List<String>,
    displayParagraphs: List<String>,
    notes: Map<Int, NoteEntry>,
    muted: Color,
    onJump: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var copiedHint by remember { mutableStateOf(false) }
    val sorted = notes.entries.sortedBy { it.key }
    val mdText = remember(title, paragraphs, notes) {
        buildNotesExportMarkdown(title, paragraphs, notes)
    }
    val txtText = remember(title, paragraphs, notes) {
        buildNotesExportText(title, paragraphs, notes)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("我的批注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sorted.isEmpty()) {
                    Text("还没有批注。长按正文里的一段可以划线、写批注。", fontSize = 14.sp)
                } else {
                    LazyColumn(modifier = Modifier.height(280.dp)) {
                        items(sorted) { (paraIdx, entry) ->
                            val snippet = displayParagraphs.getOrNull(paraIdx)?.trim().orEmpty()
                                .take(40).ifEmpty { "第 ${paraIdx + 1} 段" }
                            val color = HighlightColor.fromKey(entry.color)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { onJump(paraIdx) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (color != null) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(12.dp)
                                                        .background(color.solid, RoundedCornerShape(6.dp))
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("第 ${paraIdx + 1} 段 · ${color.label}划线", fontSize = 13.sp)
                                            } else {
                                                Text("第 ${paraIdx + 1} 段", fontSize = 13.sp)
                                            }
                                        }
                                        Text(snippet, fontSize = 13.sp, color = muted, maxLines = 2)
                                        if (entry.note.isNotBlank()) {
                                            Text("批注：${entry.note}", fontSize = 13.sp, maxLines = 2)
                                        }
                                    }
                                }
                                TextButton(onClick = { onDelete(paraIdx) }) { Text("删除", fontSize = 13.sp) }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { shareNotesText(context, mdText, "《$title》批注") },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("分享 Markdown", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { shareNotesText(context, txtText, "《$title》批注") },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("分享 TXT", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = {
                                copyNotesText(context, mdText)
                                copiedHint = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("复制全文", fontSize = 12.sp) }
                    }
                    if (copiedHint) {
                        Text("已复制到剪贴板", fontSize = 12.sp, color = muted)
                    }
                }
            }
        }
    )
}
