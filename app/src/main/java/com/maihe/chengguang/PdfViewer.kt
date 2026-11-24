package com.maihe.chengguang

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * App 内置 PDF 阅读器（讲章用）：系统自带 PdfRenderer，不用再跳外部查看器。
 * 先把 PDF 下到缓存（下过一次的直接打开，离线也能看），再一页页渲染成图竖着滑。
 * PPT/PPTX 不走这里，还是微软在线预览。
 */
@Composable
fun PdfViewerScreen(pdfUrl: String, title: String) {
    val context = LocalContext.current
    var state by remember { mutableStateOf("下载中…") }
    var retry by remember { mutableIntStateOf(0) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var fileDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    val renderMutex = remember { Mutex() }

    // 下载 + 打开 PDF；重进/重试时先关掉旧的渲染器，再开新的；退出页面时也关掉
    LaunchedEffect(pdfUrl, retry) {
        state = "下载中…"
        try { renderer?.close() } catch (_: Exception) { }
        renderer = null
        try { fileDescriptor?.close() } catch (_: Exception) { }
        fileDescriptor = null
        val file = downloadPdf(context, pdfUrl, retry > 0)
        if (file == null) {
            state = "打不开讲章，请检查网络后重试"
            return@LaunchedEffect
        }
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            fileDescriptor = pfd
            renderer = PdfRenderer(pfd)
            state = "已打开"
        } catch (_: Exception) {
            state = "这个 PDF 打不开，可能是文件损坏"
            reportMessage("pdf-open", title)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            try { renderer?.close() } catch (_: Exception) { }
            try { fileDescriptor?.close() } catch (_: Exception) { }
        }
    }

    val r = renderer
    if (r == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (state == "下载中…") CircularProgressIndicator() else {
                    Text(state, fontSize = 15.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { retry++ }) { Text("重试") }
                }
            }
        }
        return
    }

    val pageCount = r.pageCount
    val listState = rememberLazyListState()
    val currentPage = listState.firstVisibleItemIndex + 1
    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Text(
            "共 $pageCount 页 · 正在看第 $currentPage 页",
            fontSize = 13.sp,
            color = Color.Gray,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items((0 until pageCount).toList()) { index ->
                PdfPage(renderer = r, index = index, renderMutex = renderMutex)
            }
        }
    }
}

/** 一页 PDF：需要时才渲染，滑出屏幕就回收位图，免得大讲章把内存吃满。 */
@Composable
private fun PdfPage(renderer: PdfRenderer, index: Int, renderMutex: Mutex) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // 按屏幕宽的 1.5 倍渲染，字更清楚；超过 2000px 就封顶，免得一页占太多内存
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }.toInt()
        .let { (it * 1.5f).toInt().coerceAtMost(2000) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(index) {
        val bmp = withContext(Dispatchers.IO) {
            renderMutex.withLock {
                val page = renderer.openPage(index)
                try {
                    val scale = screenWidthPx.toFloat() / page.width
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val b = Bitmap.createBitmap(screenWidthPx, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(android.graphics.Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                } finally {
                    page.close()
                }
            }
        }
        bitmap = bmp
    }
    DisposableEffect(index) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    val b = bitmap
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = "第 ${index + 1} 页",
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
    }
}

/** 把 PDF 下到缓存（slides 目录），下过一次的直接用，离线也能打开。失败返回 null。 */
private suspend fun downloadPdf(context: android.content.Context, url: String, forceRedownload: Boolean): File? {
    return withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "slides").apply { mkdirs() }
        val file = File(dir, "slides-" + url.hashCode() + ".pdf")
        if (!forceRedownload && file.exists() && file.length() > 0) return@withContext file
        // 已经用“下载讲章”存到手机下载文件夹的，直接打开那份，不用再下一遍
        val fileName = url.substringAfterLast("/").substringBefore("?").ifEmpty { "讲章.pdf" }
        val downloaded = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ), fileName
        )
        if (!forceRedownload && downloaded.exists() && downloaded.length() > 0) {
            return@withContext downloaded
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = "GET"
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            conn.inputStream.use { input ->
                FileOutputStream(file).use { out -> input.copyTo(out) }
            }
            if (file.length() == 0L) {
                file.delete()
                null
            } else file
        } catch (_: Exception) {
            file.delete()
            null
        } finally {
            conn?.disconnect()
        }
    }
}
