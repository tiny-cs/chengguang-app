package com.maihe.chengguang

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 词典翻译 + 生词本（读书器二期第 6 块）。
 *
 * 只在属灵书籍的阅读器里用（诗歌是短文本，不做；音频/视频页不动）。
 * 查词入口是工具栏上的“查词”按钮，不占长按（长按已经是批注菜单了）。
 *
 * 查词走两个免费免 key 接口：
 * - 英文单词：dictionaryapi.dev（音标 + 释义），再附一行 MyMemory 的英→中翻译；
 * - 中文、或英文短语（词典接口只认单个词）：MyMemory 翻译。
 * 生词本只存在这台手机上（SharedPreferences "chengguang_vocab"），不动服务器。
 * 复习模式、导出这次不做。
 */

private const val VOCAB_PREFS = "chengguang_vocab"
private val vocabJson = Json { ignoreUnknownKeys = true }

// ---------- 生词本存储 ----------

@Serializable
data class VocabEntry(
    val word: String,
    val meaning: String, // 查词对话框里显示的那行主要释义/译文
    val bookTitle: String = "",
    val addedAt: Long = 0L // 加入生词本的时间（毫秒）
)

class VocabStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(VOCAB_PREFS, Context.MODE_PRIVATE)

    /** 每次改动后 version +1，读了 version 的 Composable 会重组并重新读状态。 */
    var version by mutableStateOf(0)
        private set

    fun refresh() {
        version++
    }

    // 英文不分大小写去重（Repentance 和 repentance 是同一个词）；中文原样。
    private fun keyOf(word: String) = word.trim().lowercase()

    private fun loadEntries(): MutableMap<String, VocabEntry> {
        val raw = prefs.getString("entries", null) ?: return mutableMapOf()
        return try {
            vocabJson.decodeFromString<Map<String, VocabEntry>>(raw).toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveEntries(entries: Map<String, VocabEntry>) {
        prefs.edit().putString("entries", vocabJson.encodeToString(entries)).apply()
        refresh()
    }

    /** 全部词条，按加入时间倒序。 */
    fun entries(): List<VocabEntry> {
        @Suppress("UNUSED_EXPRESSION") version
        return loadEntries().values.sortedByDescending { it.addedAt }
    }

    fun count(): Int {
        @Suppress("UNUSED_EXPRESSION") version
        return loadEntries().size
    }

    fun contains(word: String): Boolean {
        @Suppress("UNUSED_EXPRESSION") version
        return loadEntries().containsKey(keyOf(word))
    }

    /** 已在生词本里返回 false，不会重复加。 */
    fun add(word: String, meaning: String, bookTitle: String): Boolean {
        val clean = word.trim()
        if (clean.isEmpty()) return false
        val all = loadEntries()
        val key = keyOf(clean)
        if (all.containsKey(key)) return false
        all[key] = VocabEntry(
            word = clean,
            meaning = meaning,
            bookTitle = bookTitle,
            addedAt = System.currentTimeMillis()
        )
        saveEntries(all)
        return true
    }

    fun remove(word: String) {
        val all = loadEntries()
        if (all.remove(keyOf(word)) != null) saveEntries(all)
    }
}

// ---------- 查词 ----------

data class WordMeaning(val partOfSpeech: String, val definition: String)

sealed interface LookupResult {
    data class Word(
        val phonetic: String,
        val meanings: List<WordMeaning>,
        val translation: String // MyMemory 的英→中翻译，拿不到时为空
    ) : LookupResult

    data class Translation(val text: String, val label: String) : LookupResult
    data class Error(val message: String) : LookupResult
}

@Serializable
private data class DictEntry(
    val word: String = "",
    val phonetic: String = "",
    val phonetics: List<DictPhonetic> = emptyList(),
    val meanings: List<DictMeaning> = emptyList()
)

@Serializable
private data class DictPhonetic(val text: String = "")

@Serializable
private data class DictMeaning(
    val partOfSpeech: String = "",
    val definitions: List<DictDefinition> = emptyList()
)

@Serializable
private data class DictDefinition(val definition: String = "")

@Serializable
private data class MyMemoryResponse(
    val responseData: MyMemoryData? = null,
    val responseStatus: Int = 0
)

@Serializable
private data class MyMemoryData(val translatedText: String = "")

private val lookupJson = Json { ignoreUnknownKeys = true }

private fun isChinese(text: String) = text.any { it in '\u4E00'..'\u9FFF' }

/**
 * 查一个词 / 翻一段话。返回结果或中文错误提示（直接显示给用户）。
 * 没网（IOException）不报 Sentry，是慢网络下的正常情况；接口返回怪格式才上报一次。
 */
suspend fun lookupWord(raw: String): LookupResult = withContext(Dispatchers.IO) {
    val word = raw.trim()
    if (word.isEmpty()) return@withContext LookupResult.Error("请先输入要查的词")
    try {
        when {
            isChinese(word) -> {
                // 翻译模式：中文 → 英文
                val text = translateText(word, "zh-CN|en-US")
                    ?: run {
                        reportMessage("dictionary", "mymemory empty response")
                        return@withContext LookupResult.Error("查词服务暂时不可用")
                    }
                LookupResult.Translation(text, "中文 → 英文")
            }
            word.any { it.isWhitespace() } -> {
                // 英文短语：词典接口只认单个词，短语走翻译
                val text = translateText(word, "en-US|zh-CN")
                    ?: run {
                        reportMessage("dictionary", "mymemory empty response")
                        return@withContext LookupResult.Error("查词服务暂时不可用")
                    }
                LookupResult.Translation(text, "英文 → 中文")
            }
            else -> lookupEnglishWord(word)
        }
    } catch (e: IOException) {
        LookupResult.Error("没网，请检查网络后重试")
    } catch (e: Exception) {
        reportMessage("dictionary", "lookup failed: ${e::class.java.simpleName}")
        LookupResult.Error("查词服务暂时不可用")
    }
}

/** 英文单词：dictionaryapi.dev 查释义；MyMemory 的英→中翻译是附加的，拿不到也不影响主结果。 */
private fun lookupEnglishWord(word: String): LookupResult {
    var conn: HttpURLConnection? = null
    try {
        val url = "https://api.dictionaryapi.dev/api/v2/entries/en/" +
            URLEncoder.encode(word, "UTF-8")
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
            return LookupResult.Error("没查到这个词，换个词试试")
        }
        if (code != HttpURLConnection.HTTP_OK) {
            reportMessage("dictionary", "dictionary api http $code")
            return LookupResult.Error("查词服务暂时不可用")
        }
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val entries = lookupJson.decodeFromString<List<DictEntry>>(text)
        val entry = entries.firstOrNull()
        // 最多 2 个词性，每个词性最多 2 条释义
        val meanings = entry?.meanings
            ?.take(2)
            ?.flatMap { m -> m.definitions.take(2).map { WordMeaning(m.partOfSpeech, it.definition) } }
            ?.filter { it.definition.isNotBlank() }
            .orEmpty()
        if (meanings.isEmpty()) return LookupResult.Error("没查到这个词，换个词试试")
        val phonetic = entry?.phonetic?.takeIf { it.isNotBlank() }
            ?: entry?.phonetics?.firstOrNull { it.text.isNotBlank() }?.text.orEmpty()
        // 附加翻译：失败就空着，不影响释义显示
        val translation = try {
            translateText(word, "en-US|zh-CN") ?: ""
        } catch (_: Exception) {
            ""
        }
        return LookupResult.Word(phonetic, meanings, translation)
    } finally {
        conn?.disconnect()
    }
}

/**
 * MyMemory 免费翻译（免 key）。成功返回译文，失败返回 null（调用方再决定提示）。
 * 注意：匿名调用有每日额度，超了会返回空译文或限流提示，都按失败处理。
 */
private fun translateText(text: String, langpair: String): String? {
    var conn: HttpURLConnection? = null
    try {
        val url = "https://api.mymemory.translated.net/get?q=" +
            URLEncoder.encode(text, "UTF-8") + "&langpair=" + langpair
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val resp = lookupJson.decodeFromString<MyMemoryResponse>(body)
        if (resp.responseStatus != 200) return null
        val translated = resp.responseData?.translatedText?.trim().orEmpty()
        if (translated.isEmpty()) return null
        // 免费接口的限流/超长提示不是译文
        if (translated.contains("QUERY LENGTH LIMIT EXCEEDED") ||
            translated.contains("NO QUERY SPECIFIED") ||
            translated.contains("MYMEMORY WARNING")
        ) return null
        return translated
    } finally {
        conn?.disconnect()
    }
}

// ---------- 查词对话框 ----------

private const val MAX_LOOKUP_CHARS = 100

/** 查词对话框：输入框 + 查 + 结果 + 加入生词本。阅读器和生词本页共用。 */
@Composable
fun LookupDialog(
    initialWord: String,
    bookTitle: String,
    vocab: VocabStore,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(initialWord) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LookupResult?>(null) }
    var justAdded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun doLookup() {
        val q = query.trim()
        if (q.isEmpty()) {
            result = LookupResult.Error("请先输入要查的词")
            return
        }
        if (busy) return
        busy = true
        result = null
        scope.launch {
            result = lookupWord(q)
            busy = false
        }
    }

    val inVocab = vocab.contains(query.trim())
    // 加入生词本时存的那行主要释义/译文
    fun primaryMeaning(): String = when (val r = result) {
        is LookupResult.Word -> {
            val first = r.meanings.firstOrNull()?.definition.orEmpty()
            if (r.translation.isNotBlank()) "$first（${r.translation}）" else first
        }
        is LookupResult.Translation -> r.text
        else -> ""
    }.take(200)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("查词") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        if (it.length <= MAX_LOOKUP_CHARS) {
                            query = it
                            result = null
                            justAdded = false
                        }
                    },
                    placeholder = { Text("输入英文单词或中文", fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doLookup() })
                )
                Button(
                    onClick = { doLookup() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "正在查…" else "查") }
                when (val r = result) {
                    null -> {
                        if (!busy) {
                            Text(
                                "英文查释义和翻译，中文翻成英文。",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                        }
                    }
                    is LookupResult.Error -> {
                        Text(r.message, fontSize = 14.sp, color = Color(0xFFB3261E))
                    }
                    is LookupResult.Translation -> {
                        Text(r.label, fontSize = 13.sp, color = Color.Gray)
                        Text(r.text, fontSize = 16.sp)
                    }
                    is LookupResult.Word -> {
                        if (r.phonetic.isNotBlank()) {
                            Text("音标：${r.phonetic}", fontSize = 14.sp, color = Color.Gray)
                        }
                        r.meanings.forEach { m ->
                            Text(
                                (if (m.partOfSpeech.isNotBlank()) "${m.partOfSpeech}：" else "") +
                                    m.definition,
                                fontSize = 15.sp
                            )
                        }
                        if (r.translation.isNotBlank()) {
                            Text("中文：${r.translation}", fontSize = 14.sp, color = Color.Gray)
                        }
                    }
                }
                if (result is LookupResult.Word || result is LookupResult.Translation) {
                    OutlinedButton(
                        onClick = {
                            if (vocab.add(query.trim(), primaryMeaning(), bookTitle)) {
                                justAdded = true
                            }
                        },
                        enabled = !inVocab && !justAdded,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when {
                                justAdded -> "已加入生词本"
                                inVocab -> "已在生词本"
                                else -> "加入生词本"
                            },
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    )
}

// ---------- 生词本页 ----------

/** 生词本页：列表按加入时间倒序，点一条重新查，每条可删。 */
@Composable
fun VocabScreen(vocab: VocabStore) {
    val entries = vocab.entries()
    var lookupTarget by remember { mutableStateOf<VocabEntry?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "只存在这台手机上。",
            fontSize = 13.sp,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        if (entries.isEmpty()) {
            Text(
                "生词本还是空的。在书里点“查词”，查到的词可以收进来。",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(entries, key = { it.word.lowercase() }) { entry ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { lookupTarget = entry }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.word, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(entry.meaning, fontSize = 14.sp, color = Color.DarkGray)
                                if (entry.bookTitle.isNotBlank()) {
                                    Text(
                                        "来自《${entry.bookTitle}》",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                            TextButton(
                                onClick = { vocab.remove(entry.word) },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) { Text("删除", fontSize = 13.sp) }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }
        }
    }
    // 点词条：打开查词对话框并预填这个词，点“查”能再查一次
    lookupTarget?.let { entry ->
        LookupDialog(
            initialWord = entry.word,
            bookTitle = entry.bookTitle,
            vocab = vocab,
            onDismiss = { lookupTarget = null }
        )
    }
}
