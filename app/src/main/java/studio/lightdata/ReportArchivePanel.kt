package studio.lightdata

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
private fun ReportText(label: String, color: Color = Color.White, size: Int = 14, modifier: Modifier = Modifier) {
    BasicText(label, modifier, style = TextStyle(color = color, fontSize = size.sp))
}

@Composable
private fun ReportAction(label: String, click: () -> Unit) {
    Box(Modifier.clickable(role = Role.Button, onClick = click).padding(horizontal = 8.dp, vertical = 12.dp)) {
        ReportText(label, amber)
    }
}

@Composable
private fun ReportSwitch(label: String, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = change)
        .padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ReportText(label, modifier = Modifier.weight(1f))
        ReportText(if (enabled) "开启" else "关闭", if (enabled) amber else Color.LightGray)
    }
}

private fun decodeReport(a: MainActivity, uri: Uri, maxWidth: Int, maxPixels: Long): android.graphics.Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    a.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    var sample = 1
    while (options.outWidth / sample > maxWidth * 2 || options.outWidth.toLong() * options.outHeight / sample / sample > maxPixels) sample *= 2
    options.inSampleSize = sample
    options.inJustDecodeBounds = false
    return a.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

@Composable
private fun ReportImage(a: MainActivity, report: ReportItem, thumbnail: Boolean, modifier: Modifier = Modifier) {
    var failed by remember(report, thumbnail) { mutableStateOf(false) }
    val image by produceState<android.graphics.Bitmap?>(null, report, thumbnail) {
        value = try {
            withContext(Dispatchers.IO) {
                if (thumbnail) decodeReport(a, report.uri, 160, 70000) else decodeReport(a, report.uri, 1600, 6000000)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        failed = value == null
    }
    val bitmap = image
    if (bitmap == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (!thumbnail) ReportText(if (failed) "无法读取这份报告" else "正在读取…", Color.LightGray)
        }
    } else {
        val size = if (thumbnail) modifier else modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height)
        Image(bitmap.asImageBitmap(), contentDescription = if (thumbnail) null else "测光留档报告",
            modifier = size, contentScale = if (thumbnail) ContentScale.Crop else ContentScale.Fit)
    }
}

@Composable
internal fun ReportArchivePanel(a: MainActivity) {
    val store = remember(a) { ReportStore(a) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<ReportItem>>(emptyList()) }
    var selected by remember { mutableStateOf<ReportItem?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM) }
    val legacyAllowed = Build.VERSION.SDK_INT >= 29 || listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE).all { permission ->
        a.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    DisposableEffect(a, legacyAllowed) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { refresh++ }
        }
        val registered = legacyAllowed && runCatching {
            a.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        }.isSuccess
        val lifecycle = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        a.lifecycle.addObserver(lifecycle)
        onDispose {
            if (registered) a.contentResolver.unregisterContentObserver(observer)
            a.lifecycle.removeObserver(lifecycle)
        }
    }
    LaunchedEffect(refresh, legacyAllowed) {
        if (!legacyAllowed) { files = emptyList(); loading = false; return@LaunchedEffect }
        try {
            files = withContext(Dispatchers.IO) { store.list() }
            if (selected != null && files.none { it.uri == selected?.uri }) { selected = null; confirmDelete = false }
            error = ""
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = "无法读取留档：" + e.localizedMessage.orEmpty() }
        finally { loading = false }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ReportSwitch("双击取景器留档", a.reportEnabled, a::setReportCaptureEnabled)
            if (a.reportEnabled) {
                ReportSwitch("将当前位置写入报告", a.reportLocationEnabled, a::changeReportLocationEnabled)
                ReportText("双击取景画面保存报告至系统相册 Pictures/LightData/Reports。", Color.LightGray, 12, Modifier.padding(8.dp))
            }
            if (!legacyAllowed) ReportAction("授权访问存储以查看和保存留档", a::requestArchiveStorage)
            if (error.isNotEmpty()) ReportText(error, amber)
            if (legacyAllowed && files.isEmpty() && error.isEmpty()) {
                ReportText(if (loading) "正在读取…" else "暂无留档", Color.LightGray, modifier = Modifier.padding(8.dp))
            }
        }
        items(files, key = { it.uri.toString() }) { item ->
            Row(Modifier.fillMaxWidth().clickable { selected = item }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReportImage(a, item, true, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    ReportText(formatter.format(Date(item.timeMs)))
                    ReportText(item.name, Color.LightGray, 11)
                }
                ReportText("查看", amber)
            }
        }
    }
    val item = selected ?: return
    Dialog(onDismissRequest = { if (!busy) selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xff151617)).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ReportAction("‹ 返回") { if (!busy) selected = null }
                ReportAction("在相册中打开") {
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, "image/jpeg")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { a.startActivity(view) }.onFailure { a.notifyUser("设备上没有可用的图片查看器") }
                }
                ReportAction("删除") { if (!busy) confirmDelete = true }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                ReportImage(a, item, false, Modifier.fillMaxWidth())
            }
        }
    }
    if (confirmDelete) Dialog(onDismissRequest = { confirmDelete = false }) {
        Column(Modifier.background(Color(0xff242528)).padding(16.dp)) {
            ReportText("从留档和系统相册中删除这份报告？")
            ReportText(formatter.format(Date(item.timeMs)), Color.LightGray, 12, Modifier.padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ReportAction("取消") { confirmDelete = false }
                ReportAction("删除报告") {
                    confirmDelete = false
                    busy = true
                    scope.launch {
                        try {
                            val removed = withContext(Dispatchers.IO) { store.delete(item) }
                            selected = null
                            if (!removed) a.notifyUser("这份报告已移除")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (e: Exception) { a.notifyUser("删除失败：" + e.localizedMessage.orEmpty()) }
                        finally { busy = false; refresh++ }
                    }
                }
            }
        }
    }
}
