package com.maihe.chengguang

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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

private val Navy = Color(0xFF1F2A44)
private val Gold = Color(0xFF9A7B2D)
private val Paper = Color(0xFFFAF7F0)
private val Teal = Color(0xFF2E7D6B)
private val Brick = Color(0xFF8E4A2F)

fun categoryColor(category: String): Color = when (category) {
    "属灵书籍" -> Gold
    "生命诗歌" -> Navy
    "讲道音频" -> Teal
    "讲道视频" -> Brick
    else -> Navy
}

sealed interface Route {
    data object Home : Route
    data object All : Route
    data object Mine : Route
    data class Category(val name: String) : Route
    data class Detail(val id: String) : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Gold,
                    onPrimary = Color.White,
                    secondary = Navy,
                    background = Paper,
                    surface = Color.White
                )
            ) {
                ChengguangApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChengguangApp() {
    val context = LocalContext.current
    val items = remember { loadCatalog(context) }
    val downloads = remember { DownloadStore(context) }
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val current = stack.last()

    BackHandler(enabled = stack.size > 1) {
        stack.removeAt(stack.lastIndex)
    }

    fun go(route: Route) {
        if (stack.last() != route) stack.add(route)
    }
    fun goTop(route: Route) {
        stack.clear()
        stack.add(route)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val r = current) {
                            is Route.Detail -> items.find { it.id == r.id }?.title ?: "城光"
                            is Route.Category -> r.name
                            Route.All -> "全部内容"
                            Route.Mine -> "我的"
                            Route.Home -> "城光"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (stack.size > 1) {
                        IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Navy,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            if (current is Route.Home || current is Route.All || current is Route.Mine) {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(
                        selected = current is Route.Home,
                        onClick = { goTop(Route.Home) },
                        icon = { Icon(Icons.Default.Home, contentDescription = "首页") },
                        label = { Text("首页") }
                    )
                    NavigationBarItem(
                        selected = current is Route.All,
                        onClick = { goTop(Route.All) },
                        icon = { Icon(Icons.Default.List, contentDescription = "全部") },
                        label = { Text("全部") }
                    )
                    NavigationBarItem(
                        selected = current is Route.Mine,
                        onClick = { goTop(Route.Mine) },
                        icon = { Icon(Icons.Default.Person, contentDescription = "我的") },
                        label = { Text("我的") }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Paper)
        ) {
            when (val r = current) {
                Route.Home -> HomeScreen(items, downloads, onCategory = { go(Route.Category(it)) }, onItem = { go(Route.Detail(it.id)) })
                Route.All -> AllScreen(items, downloads, onItem = { go(Route.Detail(it.id)) })
                Route.Mine -> MineScreen(items, downloads, onItem = { go(Route.Detail(it.id)) })
                is Route.Category -> CategoryScreen(r.name, items, downloads, onItem = { go(Route.Detail(it.id)) })
                is Route.Detail -> {
                    val item = items.find { it.id == r.id }
                    if (item != null) DetailScreen(item, downloads) else Text("内容不存在", modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    items: List<CatalogItem>,
    downloads: DownloadStore,
    onCategory: (String) -> Unit,
    onItem: (CatalogItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索书名、诗歌、讲道…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
                singleLine = true
            )
        }
        if (query.isBlank()) {
            item {
                Text("栏目", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CATEGORIES.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { name ->
                                CategoryCard(
                                    name = name,
                                    count = items.count { it.category == name },
                                    modifier = Modifier.weight(1f),
                                    onClick = { onCategory(name) }
                                )
                            }
                            if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                Text("今日推荐", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
            val featured = CATEGORIES.mapNotNull { name -> items.firstOrNull { it.category == name } }
            items(featured) { item -> ItemRow(item, downloads, onClick = { onItem(item) }) }
        } else {
            val results = items.filter {
                it.title.contains(query) || it.description.contains(query)
            }
            item { Text("找到 ${results.size} 条", color = Color.Gray) }
            if (results.isEmpty()) {
                item { Text("没有找到相关内容，换个关键词试试。", modifier = Modifier.padding(top = 8.dp)) }
            }
            items(results) { item -> ItemRow(item, downloads, onClick = { onItem(item) }) }
        }
        if (items.isEmpty()) {
            item { Text("目录为空：请检查 catalog.json。", modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
fun CategoryCard(name: String, count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(categoryColor(name), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (name) {
                        "属灵书籍" -> "书"
                        "生命诗歌" -> "诗"
                        "讲道音频" -> "音"
                        "讲道视频" -> "视"
                        else -> "城"
                    },
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("$count 篇", fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllScreen(items: List<CatalogItem>, downloads: DownloadStore, onItem: (CatalogItem) -> Unit) {
    var selected by remember { mutableStateOf("全部") }
    val shown = if (selected == "全部") items else items.filter { it.category == selected }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selected == "全部",
                    onClick = { selected = "全部" },
                    label = { Text("全部") }
                )
                CATEGORIES.forEach { name ->
                    FilterChip(
                        selected = selected == name,
                        onClick = { selected = name },
                        label = { Text(name) }
                    )
                }
            }
        }
        items(shown) { item -> ItemRow(item, downloads, onClick = { onItem(item) }) }
        if (shown.isEmpty()) {
            item { Text("这个栏目还没有内容。", modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
fun CategoryScreen(name: String, items: List<CatalogItem>, downloads: DownloadStore, onItem: (CatalogItem) -> Unit) {
    val shown = items.filter { it.category == name }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(shown) { item -> ItemRow(item, downloads, onClick = { onItem(item) }) }
        if (shown.isEmpty()) {
            item { Text("这个栏目还没有内容。", modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
fun ItemRow(item: CatalogItem, downloads: DownloadStore, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(categoryColor(item.category), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(typeChar(item.type), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(2.dp))
                val meta = buildString {
                    append(item.category)
                    if (item.duration.isNotEmpty()) append(" · ${item.duration}")
                    if (item.size.isNotEmpty()) append(" · ${item.size}")
                    if (downloads.isDownloaded(item)) append(" · 已下载")
                }
                Text(meta, fontSize = 13.sp, color = Color.Gray)
                if (item.description.isNotEmpty()) {
                    Text(item.description, fontSize = 13.sp, color = Color.DarkGray, maxLines = 2)
                }
            }
            Text("›", fontSize = 22.sp, color = Color.Gray)
        }
    }
}

@Composable
fun MineScreen(items: List<CatalogItem>, downloads: DownloadStore, onItem: (CatalogItem) -> Unit) {
    val downloaded = downloads.downloadedItems(items)
    val totalBytes = downloads.totalDownloadedBytes(items)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Navy),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("城光", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "城造在山上，是不能隐藏的。",
                        color = Color(0xFFF5D98B),
                        fontSize = 14.sp
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("已下载（${downloaded.size} 个 · 共 ${formatBytes(totalBytes)}）", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("已下载的音频和视频断网也能播放。点一条打开播放，删掉可释放手机空间。书籍和诗歌正文已随 App 自带，离线也能读，无需下载。", fontSize = 14.sp)
                }
            }
        }
        if (downloaded.isEmpty()) {
            item {
                Text("还没有下载的内容。在音频 / 视频详情页点“下载到手机”即可离线收听。", fontSize = 14.sp, color = Color.Gray)
            }
        } else {
            items(downloaded) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).clickable { onItem(item) }) {
                            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "${item.category} · ${formatBytes(downloads.localFile(item).length())}",
                                fontSize = 13.sp, color = Color.Gray
                            )
                        }
                        OutlinedButton(onClick = { downloads.delete(item) }) { Text("删除") }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("仅在 WiFi 下下载", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("打开后，用流量时点下载会提示连 WiFi 再试", fontSize = 13.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = downloads.wifiOnly,
                        onCheckedChange = { downloads.wifiOnly = it }
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("关于", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("城光是属灵内容 App：属灵书籍、生命诗歌、讲道音频、讲道视频。内容由管理员统一上传，读者只管阅读收听。", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("版本：1.1.0（离线下载版）", fontSize = 13.sp, color = Color.Gray)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("存储说明", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("视频和音频以后存放在 Cloudflare R2 对象存储，App 里只存标题和播放链接。本原型用本地 catalog.json 做目录，示例音视频为公开示例文件。下载的文件存在 App 专属目录，随 App 卸载一起删除。", fontSize = 14.sp)
                }
            }
        }
    }
}
