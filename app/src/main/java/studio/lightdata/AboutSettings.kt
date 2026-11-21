package studio.lightdata

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val projectUrl = "https://github.com/RayforLoy/LightData"
private val tagPattern = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)(?:[.-](\d+))?)?""")

internal data class AppVersion(val major: Int, val minor: Int, val patch: Int, val stage: Int, val revision: Int) :
    Comparable<AppVersion> {
    override fun compareTo(other: AppVersion) = compareValuesBy(this,other,
        AppVersion::major,AppVersion::minor,AppVersion::patch,AppVersion::stage,AppVersion::revision)
    companion object {
        fun parse(tag: String): AppVersion? {
            val (major,minor,patch,stage,revision) = tagPattern.matchEntire(tag)?.destructured ?: return null
            return AppVersion(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null, listOf("alpha","beta","rc","").indexOf(stage),
                if (revision.isEmpty()) 0 else revision.toIntOrNull() ?: return null)
        }
    }
}

private fun newestAndroidRelease(): String {
    val conn = URL("https://api.github.com/repos/RayforLoy/LightData/releases?per_page=100").openConnection() as HttpsURLConnection
    try {
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.setRequestProperty("Accept","application/vnd.github+json")
        conn.setRequestProperty("User-Agent","LightData-Android")
        val releases = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        return (0 until releases.length()).mapNotNull { index ->
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft")) return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: return@mapNotNull null
            if ((0 until assets.length()).none { assets.getJSONObject(it).optString("name").endsWith(".apk") })
                return@mapNotNull null
            val tag = release.getString("tag_name")
            AppVersion.parse(tag)?.let { it to tag }
        }.maxByOrNull { it.first }?.second ?: error("No Android release")
    } finally { conn.disconnect() }
}

@Composable
internal fun AboutSettings(a: MainActivity, open: (String)->Unit) {
    val installed = remember { a.packageManager.getPackageInfo(a.packageName,0).versionName.orEmpty() }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    Setting("关于", "LightData $installed") {
        MeterDialog(a).setTitle("LightData $installed").setMessage("胶片摄影取景器与测光表")
            .setNeutralButton("项目主页") { _,_ -> open(projectUrl) }
            .setPositiveButton("完成",null).show()
    }
    Setting("检查更新", if (checking) "检查中…" else "GitHub Releases") {
        if (!checking) {
            checking = true
            scope.launch {
                try {
                    val tag = withContext(Dispatchers.IO) { newestAndroidRelease() }
                    if (requireNotNull(AppVersion.parse(tag)) > requireNotNull(AppVersion.parse(installed))) {
                        MeterDialog(a).setTitle("发现新版本").setMessage("LightData $installed → $tag")
                            .setPositiveButton("查看新版") { _,_ -> open("$projectUrl/releases/tag/$tag") }
                            .setNegativeButton("取消",null).show()
                    } else a.notifyUser("已是最新版本 · $installed")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    MeterDialog(a).setTitle("检查更新失败").setMessage("暂时无法读取 GitHub 发布信息。")
                        .setPositiveButton("查看发布页") { _,_ -> open("$projectUrl/releases") }
                        .setNegativeButton("关闭",null).show()
                } finally { checking = false }
            }
        }
    }
}
