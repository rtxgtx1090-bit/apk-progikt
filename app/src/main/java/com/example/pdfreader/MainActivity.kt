package com.example.pdfreader

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.DateFormat
import java.util.Date

// ───────────────────────── النموذج ─────────────────────────

data class PdfItem(val name: String, val uri: Uri, val size: Long, val modifiedMs: Long)

// ───────────────────────── النشاط الرئيسي ─────────────────────────

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) { App(resumeTick) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++ // لإعادة فحص الإذن بعد العودة من شاشة الإعدادات
    }
}

@Composable
fun App(resumeTick: Int) {
    var opened by remember { mutableStateOf<PdfItem?>(null) }
    var tab by remember { mutableStateOf(0) }

    val current = opened
    if (current != null) {
        BackHandler { opened = null }
        PdfViewer(current) { opened = null }
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("الكل") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("المجلدات") })
            }
            if (tab == 0) AllTab(resumeTick) { opened = it } else FolderTab { opened = it }
        }
    }
}

// ───────────────────────── القسم الأول: الكل ─────────────────────────

fun hasAllAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

@Composable
fun AllTab(resumeTick: Int, onOpen: (PdfItem) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasAllAccess(ctx)) }
    var items by remember { mutableStateOf<List<PdfItem>?>(null) }

    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(resumeTick) { granted = hasAllAccess(ctx) }
    LaunchedEffect(granted, resumeTick) {
        if (granted) items = queryAllPdfs(ctx)
    }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("للبحث عن جميع ملفات PDF في هاتفك يحتاج التطبيق إلى إذن الوصول للملفات.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    } catch (e: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }) { Text("منح الإذن") }
        }
    } else {
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("لا توجد ملفات PDF") }
            else -> PdfList(list, onOpen)
        }
    }
}

suspend fun queryAllPdfs(ctx: Context): List<PdfItem> = withContext(Dispatchers.IO) {
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_MODIFIED
    )
    val selection =
        "${MediaStore.Files.FileColumns.MIME_TYPE}=? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
    val args = arrayOf("application/pdf", "%.pdf")
    val result = mutableListOf<PdfItem>()
    try {
        ctx.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (c.moveToNext()) {
                result += PdfItem(
                    name = c.getString(nameCol) ?: "بدون اسم",
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    size = c.getLong(sizeCol),
                    modifiedMs = c.getLong(dateCol) * 1000
                )
            }
        }
    } catch (_: Exception) {
    }
    result
}

// ───────────────────────── القسم الثاني: المجلدات ─────────────────────────

@Composable
fun FolderTab(onOpen: (PdfItem) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("pdf_prefs", Context.MODE_PRIVATE) }
    var tree by remember { mutableStateOf(prefs.getString("tree", null)?.let { Uri.parse(it) }) }
    var items by remember { mutableStateOf<List<PdfItem>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            prefs.edit().putString("tree", uri.toString()).apply()
            tree = uri
        }
    }

    LaunchedEffect(tree) {
        val t = tree
        items = if (t == null) emptyList() else withContext(Dispatchers.IO) {
            try { listPdfsInTree(ctx, t) } catch (_: Exception) { emptyList() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { picker.launch(null) }) { Text("اختيار مجلد") }
            Spacer(Modifier.width(12.dp))
            Text(
                text = tree?.let { Uri.decode(it.lastPathSegment ?: "") } ?: "لم يتم اختيار مجلد",
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
        if (tree != null && items.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("لا توجد ملفات PDF في هذا المجلد") }
        } else {
            PdfList(items, onOpen)
        }
    }
}

// يعرض ملفات المجلد المختار فقط (بدون المجلدات الفرعية)
fun listPdfsInTree(ctx: Context, tree: Uri): List<PdfItem> {
    val docId = DocumentsContract.getTreeDocumentId(tree)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )
    val result = mutableListOf<PdfItem>()
    ctx.contentResolver.query(children, projection, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getString(0)
            val name = c.getString(1) ?: continue
            val mime = c.getString(2) ?: ""
            if (mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)) {
                result += PdfItem(
                    name = name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                    size = c.getLong(3),
                    modifiedMs = c.getLong(4)
                )
            }
        }
    }
    return result.sortedBy { it.name.lowercase() }
}

// ───────────────────────── القائمة المشتركة ─────────────────────────

@Composable
fun PdfList(list: List<PdfItem>, onOpen: (PdfItem) -> Unit) {
    val ctx = LocalContext.current
    val df = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.uri.toString() }) { item ->
            ListItem(
                modifier = Modifier.clickable { onOpen(item) },
                headlineContent = { Text(item.name, maxLines = 2) },
                supportingContent = {
                    Text(
                        Formatter.formatShortFileSize(ctx, item.size) +
                                if (item.modifiedMs > 0) "  •  ${df.format(Date(item.modifiedMs))}" else ""
                    )
                }
            )
        }
    }
}

// ───────────────────────── عارض الـ PDF ─────────────────────────

class PdfDoc(ctx: Context, uri: Uri) {
    private val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IOException("Cannot open file")
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close(); throw e
    }
    private val lock = Any()
    private var closed = false
    val pageCount: Int = renderer.pageCount

    // PdfRenderer لا يدعم فتح أكثر من صفحة في نفس الوقت، لذا نستخدم قفلاً
    fun render(index: Int, widthPx: Int): Bitmap? = synchronized(lock) {
        if (closed) return null
        renderer.openPage(index).use { page ->
            val h = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(AColor.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            renderer.close()
            pfd.close()
        }
    }
}

@Composable
fun PdfViewer(item: PdfItem, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var doc by remember(item.uri) { mutableStateOf<PdfDoc?>(null) }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }

    DisposableEffect(item.uri) {
        var d: PdfDoc? = null
        try {
            d = PdfDoc(ctx, item.uri)
            doc = d
        } catch (e: SecurityException) {
            error = "الملف محمي بكلمة مرور ولا يمكن فتحه"
        } catch (e: Exception) {
            error = "تعذّر فتح الملف"
        }
        onDispose { d?.close() }
    }

    val widthPx = remember { (ctx.resources.displayMetrics.widthPixels * 1.5f).toInt() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Text(item.name, maxLines = 1, modifier = Modifier.weight(1f))
        }
        val d = doc
        when {
            error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(error!!) }
            d == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(d.pageCount) { index -> PdfPage(d, index, widthPx) }
            }
        }
    }
}

@Composable
fun PdfPage(doc: PdfDoc, index: Int, widthPx: Int) {
    var bmp by remember(doc, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(doc, index) {
        bmp = withContext(Dispatchers.IO) {
            try { doc.render(index, widthPx) } catch (_: Exception) { null }
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = "صفحة ${index + 1}",
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth
        )
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(0.707f), Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
