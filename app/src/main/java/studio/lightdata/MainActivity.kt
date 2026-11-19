package studio.lightdata

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import kotlin.math.*

data class Sample(
    val frame: Frame,
    val hist: IntArray,
    val missing: Boolean,
    val scan: ScanGuide?,
    val still: Boolean = false,
    val captureWidth: Int = frame.w,
    val captureHeight: Int = frame.h,
    val live: LiveCapture? = null,
)

data class Spot(val id: Int, val x: Double, val y: Double, val ev: Double)

fun isoLabel(value: Double): String =
    java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

class MainActivity : ComponentActivity() {
    private lateinit var reportSession: ReportSession
    val reportEnabled get() = reportSession.enabled
    val reportLocationEnabled get() = reportSession.locationEnabled
    fun setReportCaptureEnabled(enabled: Boolean) = reportSession.enable(enabled)
    fun changeReportLocationEnabled(enabled: Boolean) = reportSession.enableLocation(enabled)
    fun requestArchiveStorage() = reportSession.requestStorage()
    fun archiveFinder() = reportSession.capture()

    internal val framingArchive by lazy { FramingArchive(java.io.File(filesDir,"framing")) }
    internal var overlayShot by mutableStateOf<FramingShot?>(null)
    internal var overlayBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    internal var overlayOpacity by mutableFloatStateOf(.4f)
    var toolsPage by mutableStateOf("")
    var soundEnabled by mutableStateOf(true)
    internal lateinit var soundFeedback: SoundFeedback
        private set
    private var importCue = FeedbackCue.METER
    private val soundPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) soundFeedback.import(importCue, uri)
    }
    internal fun selectSound(cue: FeedbackCue) {
        importCue = cue
        soundPicker.launch(arrayOf("audio/*"))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("soundImportCue", importCue.name)
        super.onSaveInstanceState(outState)
    }
    fun toggleMeterSound() {
        soundEnabled = !soundEnabled
        prefs.edit().putBoolean("meterSound", soundEnabled).apply()
        if (soundEnabled) playMeterSound() else soundFeedback.stop()
    }
    fun playMeterSound() = soundFeedback.play(FeedbackCue.METER)

    internal val fullFinder by lazy { FullFinderSettings(prefs) }
    val prefs by lazy { getSharedPreferences("meter", MODE_PRIVATE) }
    private val profiles by lazy { ProfileStore(prefs) }

    fun saved(k: String, v: Double) = prefs.getString(k, null)?.toDoubleOrNull() ?: v

    fun put(k: String, v: Any) {
        prefs.edit().putString(k, v.toString()).apply()
    }

    lateinit var pose: Pose
    lateinit var rig: Rig
    var lenses by mutableStateOf(emptyList<Lens>())
    var current by mutableStateOf<Lens?>(null)
    var parallax by mutableStateOf(ParallaxConfig())
        private set
    var cameraChoice by mutableStateOf("自动")
    var format by mutableStateOf(formats[4])
    var focal by mutableDoubleStateOf(35.0)
    var equivalentAxes by mutableStateOf(setOf(FocalAxis.DIAGONAL))
        private set
    fun toggleEquivalentAxis(axis: FocalAxis) {
        equivalentAxes = if (axis in equivalentAxes) equivalentAxes - axis else equivalentAxes + axis
        prefs.edit().putStringSet("equivalentAxes", equivalentAxes.map { it.name }.toSet()).apply()
    }
    var mode by mutableStateOf("A")
    var manual by mutableStateOf(Exposure(1.0 / 125, 8.0, 100.0))
    var film by mutableStateOf(films.first())
        private set
    var filmIso by mutableDoubleStateOf(filmRecords.first().iso.toDouble())
        private set
    var isoLocked by mutableStateOf(true)
        private set
    var style by mutableIntStateOf(0)
    var centerHistogramOnZone by mutableStateOf(true)
    var compensationEv by mutableDoubleStateOf(0.0)
        private set
    var preview by mutableStateOf(false)
    var useLut by mutableStateOf(false)
    var previewScale by mutableDoubleStateOf(.5)
    var scanAlgorithm by mutableStateOf("自动")
    fun chooseScanAlgorithm(value: String) {
        cancelScan()
        scanAlgorithm = value
        prefs.edit().putString("scanAlgorithm",value).apply()
    }
    var recordScan by mutableStateOf(false)
    private data class ScanShot(val jpeg: ByteArray,val meta: String,val accepted: Boolean)
    private var recordedAccepted = 0
    private val scanShots = ScanHistory<ScanShot>(
        (Runtime.getRuntime().maxMemory()/8).coerceIn(8L*1024*1024,64L*1024*1024))
    private fun recordShot(f: Frame, src: Field, gate: FinderGate, axes: FloatArray?, exposureMs: Double,
                           time: Long, guide: ScanGuide, turn: Int, cameraMeta: String?,
                           decision: String, checks: List<String>) {

        val bitmap = android.graphics.Bitmap.createBitmap(f.pixels,f.w,f.h,android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream()
        try { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,bytes) }
        finally { bitmap.recycle() }
        val meta = org.json.JSONObject().put("camera",f.id).put("cameraEV",f.cameraEv)
            .put("registrationDecision",decision).put("overlapChecks",org.json.JSONArray(checks))
            .put("captureResult",cameraMeta?.let { org.json.JSONObject(it) })
            .put("source",org.json.JSONArray(listOf(src.x,src.y))).put("target",org.json.JSONArray(listOf(gate.field.x,gate.field.y)))
            .put("gateOffset",org.json.JSONArray(listOf(gate.right,gate.up)))
            .put("axes",axes?.let { org.json.JSONArray(it.toList()) }).put("exposureMs",exposureMs)
            .put("timeMs",time).put("displayTurn",turn).put("captures",guide.captures).put("coverage",guide.coverage)
        synchronized(scanShots) {
            val accepted = guide.captures > recordedAccepted
            recordedAccepted = maxOf(recordedAccepted,guide.captures)
            val shot = ScanShot(bytes.toByteArray(),meta.put("algorithm",scanAlgorithm)
                .put("accepted",accepted).toString(),accepted)
            val wasTruncated = scanShots.truncated
            val stored = scanShots.add(shot,shot.jpeg.size.toLong()+shot.meta.toByteArray().size+256,accepted)
            if (!stored && !wasTruncated) runOnUiThread {
                android.widget.Toast.makeText(this,"拼接诊断记录已达容量上限，已保留开头的连续记录",1).show()
            }
        }
    }
    private val saveScan = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            val snapshot = synchronized(scanShots) { scanShots.snapshot() }
            val shots = snapshot.shots
            Thread {
                runCatching {
                    java.util.zip.ZipOutputStream(requireNotNull(contentResolver.openOutputStream(uri))).use { zip ->
                        zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                        zip.write(org.json.JSONObject().put("formatVersion",3)
                            .put("droppedRejectedAttempts",snapshot.droppedRejected)
                            .put("droppedAcceptedFrames",snapshot.droppedAccepted)
                            .put("totalCandidates",snapshot.attempts)
                            .put("completeCandidateHistory",snapshot.complete)
                            .put("retentionPolicy","contiguous-prefix")
                            .put("acceptedFrames",shots.count { it.accepted })
                            .put("completeAcceptedHistory",snapshot.droppedAccepted == 0).toString().toByteArray())
                        zip.closeEntry()
                        for ((i,shot) in shots.withIndex()) for ((ext,data) in listOf("jpg" to shot.jpeg,"json" to shot.meta.toByteArray())) {
                            zip.putNextEntry(java.util.zip.ZipEntry("${i+1}.$ext")); zip.write(data); zip.closeEntry()
                        }
                    }
                }.onFailure { runOnUiThread { android.widget.Toast.makeText(this,"导出失败",0).show() } }
            }.start()
        }
    }
    fun exportScan() {
        if (synchronized(scanShots) { scanShots.isEmpty() }) android.widget.Toast.makeText(this,"请先开启记录并进行拼接",0).show()
        else saveScan.launch("LightData-panorama.zip")
    }
    var showFps by mutableStateOf(false)
    var showLevel by mutableStateOf(true)
    var renderedFps by mutableIntStateOf(0)
    var previewView = java.lang.ref.WeakReference<Preview>(null)
    var latestLive: LiveCapture? = null
        private set
    private val frameTimes = java.util.ArrayDeque<Long>()
    fun markRendered() {
        synchronized(frameTimes) {
            frameTimes.addLast(android.os.SystemClock.elapsedRealtimeNanos())
            while (frameTimes.size > 240) frameTimes.removeFirst()
        }
    }
    fun updateFps() {
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        renderedFps = synchronized(frameTimes) {
            while (frameTimes.isNotEmpty() && now - frameTimes.first > 1_000_000_000L) frameTimes.removeFirst()
            if (frozen) 0 else frameTimes.size
        }
    }
    private val meterWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val measuring = java.util.concurrent.atomic.AtomicBoolean()
    private data class Measurement(val epoch: Int, val turn: Int, val frame: Frame, val hist: IntArray)
    @Volatile private var lastMeasure: Measurement? = null
    private var lastMeasureAt = 0L
    internal val lutAssets by lazy { LutAssets(assets) }
    var lutEntries by mutableStateOf(emptyList<LutEntry>())
    var lutCatalogStatus by mutableStateOf("正在读取 LUT 目录")
    val chosenLuts = mutableStateMapOf<String, String>()
    val variants get() = if (selectedPresetId == null) lutEntries.filter { it.film == film.name } else emptyList()
    val variant: LutEntry? get() {
        val options = variants
        val file = chosenLuts[film.name] ?: prefs.getString("lutVariant:" + film.name, null)
        return options.find { it.file == file }
            ?: options.find { it.preferred && it.iso.toDouble() == filmIso }
            ?: options.find { it.preferred } ?: options.firstOrNull()
    }
    var activeAtlas by mutableStateOf<LutAtlas?>(null)
    var failedLut by mutableStateOf<String?>(null)
    val lutStatus get() = when {
        lutCatalogStatus.isNotEmpty() -> lutCatalogStatus
        variant == null -> "无对应 LUT · 使用亮度模拟"
        variant?.file == failedLut -> "LUT 加载失败 · 使用亮度模拟"
        activeAtlas?.entry?.file != variant?.file -> "正在加载 LUT · 使用亮度模拟"
        else -> "LUT 预览"
    }
    private val stillDetector = StillDetector()
    var frozen by mutableStateOf(false)
    var single by mutableStateOf(false)
    var held by mutableStateOf<Double?>(null)
    var smoothHistogram by mutableStateOf(true)
    var logCounts by mutableStateOf(true)
    var settings by mutableStateOf(false)
    var resizing by mutableStateOf(false)
    var sample by mutableStateOf<Sample?>(null)
    var cal by mutableStateOf<Calibration?>(null)
    val spots = mutableStateListOf<Spot>()
    private var number = 1
    var message by mutableStateOf("")
    internal var lastGroundMeasurement by mutableStateOf<GroundSpotMeasurement?>(null)
    var runningCal by mutableStateOf(false)
    var allowed by mutableStateOf(false)
    private class ScanRequest(val rotation: Int) {
        // Owned by the camera thread. Cancelling replaces the request itself.
        var session: ScanSession? = null
        @Volatile var reanchor = false
    }
    private var scanRequest by mutableStateOf<ScanRequest?>(null)
    val scanActive get() = scanRequest != null
    internal var frozenAxes: FloatArray? = null
    internal var frozenLevel: LevelReading? = null
    private var frozenExposure: Exposure? = null
    @Volatile private var epoch = 0
    private var active = false
    var customPresets by mutableStateOf(emptyList<CustomPreset>())
        private set
    var selectedPresetId by mutableStateOf<String?>(null)
        private set
    val layout = mutableStateMapOf<String, Float>()
    val response
        get() = cal?.responseFor(sample?.frame?.encoding ?: YuvEncoding.default) ?: defaultResponse

    var faceMeterEnabled by mutableStateOf(false)
        private set
    var skinLiftEnabled by mutableStateOf(true)
        private set
    var skinLiftEv by mutableDoubleStateOf(1.0)
        private set
    private var heldFace by mutableStateOf(false)
    private val liveFaceHistogram get() =
        if (faceMeterEnabled && !scanActive && !runningCal) sample?.frame?.faceHistogram else null
    val appliedSkinEv get() = if (skinLiftEnabled && faceMeterEnabled && !scanActive && !runningCal &&
        placedSpotId == null && (if (held != null) heldFace else liveFaceHistogram != null)) skinLiftEv else 0.0
    val meterMiddleTarget get() = (held ?: reading)?.minus(appliedSkinEv)

    fun configureSkinLift(enabled: Boolean, ev: Double) {
        if (!ev.isFinite() || ev !in -5.0..5.0) return
        skinLiftEnabled = enabled
        skinLiftEv = ev
        prefs.edit().putBoolean("skinLiftEnabled", enabled).putString("skinLiftEv", ev.toString()).apply()
        if (frozen) frozenExposure = meteredExposure()
    }

    private fun holdReading() {
        heldFace = liveFaceHistogram != null
        held = reading
    }

    private var latestFaces by mutableStateOf<List<FaceBox>?>(null)
    val facesForOverlay get() = if (frozen) sample?.frame?.faces else latestFaces
    val faceMeterStatus get() = when {
        !faceMeterEnabled -> "关闭"
        scanActive -> "拼接中 · 全画面测光"
        current == null -> "等待相机"
        current?.let { it.globalFaceMode || it.physicalFaceMode } != true -> "镜头不支持 · 全画面测光"
        sample?.frame?.faces == null -> "未收到镜头人脸数据 · 全画面测光"
        sample?.frame?.faceHistogram == null -> "未检测到人脸 · 全画面测光"
        else -> "人脸重点测光"
    }
    fun toggleFaceMeter() {
        faceMeterEnabled = !faceMeterEnabled
        latestFaces = null
        prefs.edit().putBoolean("faceMeter",faceMeterEnabled).apply()
        rig.setFaceDetection(faceMeterEnabled)
    }

    private var meteredHist: IntArray? = null
    private var meteredLut: DoubleArray? = null
    private var meteredOffset: Double? = null
    val reading: Double? get() {
        val s = sample ?: return null
        val lut = response
        val histogram = liveFaceHistogram ?: s.hist
        if (meteredHist !== histogram || meteredLut !== lut) {
            meteredOffset = if (histogram.any { it > 0 }) meter(histogram,lut) else null
            meteredHist=histogram
            meteredLut=lut
        }
        return meteredOffset?.plus(s.frame.cameraEv)
    }

    var filterStops by mutableDoubleStateOf(0.0)
        private set
    var bellowsDrawMm by mutableDoubleStateOf(0.0)
        private set
    val geometricLoss get() = opticalLossStops(filterStops, bellowsDrawMm, focal)
    var measuredOpticalLoss by mutableStateOf<Double?>(null)
        private set
    var useMeasuredLoss by mutableStateOf(false)
        private set
    var pendingGroundEv by mutableStateOf<Double?>(null)
        private set
    val opticalLoss get() = if (useMeasuredLoss) measuredOpticalLoss ?: geometricLoss else geometricLoss

    fun beginGroundComparison(screenEv100: Double) {
        if (!screenEv100.isFinite()) return
        if (frozen) toggleFreeze()
        cancelScan()
        settings = false
        pendingGroundEv = screenEv100
    }

    fun cancelGroundComparison() { pendingGroundEv = null }

    fun chooseOpticalSource(measured: Boolean) {
        useMeasuredLoss = measured && measuredOpticalLoss != null
        prefs.edit().putBoolean("useMeasuredLoss", useMeasuredLoss).apply()
        if (frozen) frozenExposure = meteredExposure()
    }

    fun compareGroundPoint(x: Double, y: Double) {
        val screen = pendingGroundEv ?: return
        val s = sample ?: return
        if (s.scan != null) return
        val source = s.live?.let { live ->
            previewView.get()?.renderedLive?.takeIf { it.id == live.id && it.turn == live.turn &&
                it.width == live.width && it.height == live.height } ?: latestLive ?: live
        }?.fullFrame() ?: return
        if (source.sample(x,y) ushr 24 == 0) {
            notifyUser("请选择有效取景区域内的物点")
            return
        }
        val subject = source.spot(x,y,response)
        val loss = measuredTransmissionLoss(subject,screen)
        val text = "物点 " + n(subject) + " EV100 · 毛玻璃 " + n(screen) + " EV100"
        val dialog = AlertDialog.Builder(this).setTitle("同点光损实测")
        if (loss == null) {
            dialog.setMessage(text + "\n毛玻璃读数高于物点，请核对校准光圈、光照和所选物点。")
        } else {
            dialog.setMessage(text + "\n使用实测 +" + n(loss) + " EV，替代当前滤镜与皮腔补偿。")
                .setPositiveButton("应用实测值") { _,_ ->
                    measuredOpticalLoss = loss
                    put("measuredOpticalLoss",loss)
                    chooseOpticalSource(true)
                    pendingGroundEv = null
                }
        }
        dialog.setNegativeButton("重选物点",null).setNeutralButton("取消比对") { _,_ -> cancelGroundComparison() }.show()
    }
    val effectiveMiddleEv get() = exposure.ev + opticalLoss
    var placedSpotId by mutableStateOf<Int?>(null)
        private set
    var placedZone by mutableIntStateOf(3)
        private set

    private fun meteredExposure(): Exposure {
        val anchor = if (frozen) spots.find { it.id == placedSpotId } else null
        val middle = anchor?.let { zoneMiddleEv(it.ev, placedZone) } ?: meterMiddleTarget ?: manual.ev
        return shootingExposure(mode, manual, middle, opticalLoss, compensationEv, anchor != null)
    }

    fun placeSpot(point: Spot, zone: Int) {
        if (!frozen || point !in spots || zone !in 0..10) return
        placedSpotId = point.id
        placedZone = zone
        frozenExposure = meteredExposure()
    }

    fun clearPlacement() {
        placedSpotId = null
        if (frozen) frozenExposure = meteredExposure()
    }

    fun adjustOptics(stops: Double, draw: Double) {
        filterStops = stops
        bellowsDrawMm = draw
        put("filterStops", stops)
        put("bellowsDrawMm", draw)
        if (frozen) frozenExposure = meteredExposure()
    }

    val exposure: Exposure
        get() {
            if (frozen) return frozenExposure ?: manual
            return meteredExposure()
        }

    fun selectPair(pair: Exposure) {
        if (frozen) return
        when (mode) {

            "A" -> setValues(manual.copy(a = pair.a))
            "S" -> setValues(manual.copy(t = pair.t))
            else -> setValues(pair)
        }
    }

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            allowed = it
            if (it) start()
            else {
                prefs.edit().putBoolean("denied", true).apply()
                message = "需要相机权限才能取景"
            }
        }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        reportSession = ReportSession(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        importCue = FeedbackCue.entries.find { it.name == b?.getString("soundImportCue") } ?: FeedbackCue.METER
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        soundFeedback = SoundFeedback(this)
        soundEnabled = prefs.getBoolean("meterSound",true)
        pose = Pose(this)
        rig =
            Rig(
                this,
                ::process,
                { msg ->
                    runOnUiThread {
                        message = msg
                        runningCal = msg.startsWith("校准 ")
                    }
                },
                { l, c ->
                    runOnUiThread {
                        profiles.save(l.id, c)
                        if (current?.id == l.id) cal = c
                        runningCal = false
                        message = "已保存镜头 " + l.id + " 校准 · " + n(c.stops) + " EV"
                    }
                },
                { !frozen },
            )
        skinLiftEnabled = prefs.getBoolean("skinLiftEnabled", true)
        skinLiftEv = saved("skinLiftEv", 1.0).takeIf { it in -5.0..5.0 } ?: 1.0
        faceMeterEnabled = prefs.getBoolean("faceMeter", false)
        rig.setFaceDetection(faceMeterEnabled)
        measuredOpticalLoss = prefs.getString("measuredOpticalLoss", null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        useMeasuredLoss = measuredOpticalLoss != null && prefs.getBoolean("useMeasuredLoss", false)
        filterStops = saved("filterStops", 0.0).coerceIn(0.0, 24.0)
        bellowsDrawMm = saved("bellowsDrawMm", 0.0).coerceIn(0.0, 4000.0)
        focal = saved("focal", 35.0)
        val selectedAxes = prefs.getStringSet("equivalentAxes", null)
        if (selectedAxes != null) equivalentAxes = FocalAxis.entries.filter { it.name in selectedAxes }.toSet()
        parallax = ParallaxConfig(prefs.getBoolean("parallaxEnabled", false),
            saved("parallaxX", 0.0), saved("parallaxY", 0.0), saved("parallaxZ", 0.0),
            saved("parallaxDistance", 1000.0)).takeIf { it.valid(focal) } ?: ParallaxConfig()
        cameraChoice = prefs.getString("camera", "自动")!!
        val name = prefs.getString("format", "全幅")
        format =
            formats.find { it.name == name }
                ?: Format(name ?: "自定义", saved("width", 36.0), saved("height", 24.0))
        mode = storedMode(prefs.getString("mode", null))
        put("mode", mode)
        centerHistogramOnZone = prefs.getBoolean("centerHistogramOnZone", true)
        compensationEv = saved("compensationEv", prefs.getInt("compensationThirds", 0) / 3.0).coerceIn(-5.0, 5.0)
        manual = Exposure(saved("t", 1.0 / 125), saved("a", 8.0), saved("iso", 400.0))
        style = saved("style", 0.0).toInt()
        useLut = prefs.getBoolean("useLut", false)
        previewScale = saved("previewScale", .5).coerceIn(.25, 1.0)
        scanAlgorithm = prefs.getString("scanAlgorithm","自动") ?: "自动"
        showFps = prefs.getBoolean("showFps", false)
        showLevel = prefs.getBoolean("showLevel", true)
        restoreFilm()
        smoothHistogram = prefs.getBoolean("smoothHistogram", true)
        logCounts = prefs.getBoolean("logCounts", true)
        for (prefix in listOf("p", "l", "squarep", "squarel")) {
            for (i in 1..2) {
                val key = prefix + i
                if (prefs.contains(key)) layout[key] = saved(key, .5).toFloat().coerceIn(.22f, .78f)
            }
        }
        setContent { Instrument(this) }
        allowed =
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!allowed) permission.launch(Manifest.permission.CAMERA)
    }

    fun requestCamera() {
        if (allowed) {
            selectCamera(true)
            return
        }
        if (
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) &&
                prefs.getBoolean("denied", false)
        ) {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + packageName),
                )
            )
        } else permission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        allowed =
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        active = true
        reportSession.resume()
        pose.start()
        if (allowed) start()
    }

    override fun onPause() {
        active = false
        reportSession.pause()
        epoch++
        pose.stop()
        rig.stop()
        scanRequest?.reanchor = true
        runningCal = false
        super.onPause()
    }

    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        if (!active || !allowed || runningCal) return
        val prior = current ?: return
        val available = runCatching { cameras(this) }.getOrNull() ?: return
        val next = available.find { it.id == prior.id } ?: return
        if (next.size != prior.size) {
            epoch++
            lenses = available
            current = next
            scanRequest?.reanchor = true
            rig.open(next)
        }
    }

    override fun onDestroy() {
        cancelScan()
        rig.destroy()
        soundFeedback.close()
        meterWorker.shutdownNow()
        super.onDestroy()
    }

    private fun start() {
        if (!active) return
        runCatching {
                lenses = cameras(this)
                selectCamera(true, resumeScan = scanActive)
            }
            .onFailure { message = "摄像头枚举失败：" + it.localizedMessage }
    }

    fun target() = field(format.w, format.h, focal)

    fun updateParallax(config: ParallaxConfig): Boolean {
        if (!config.valid(focal)) return false
        if (config == parallax) return true
        parallax = config
        prefs.edit().putBoolean("parallaxEnabled", config.enabled)
            .putString("parallaxX", config.xMm.toString()).putString("parallaxY", config.yMm.toString())
            .putString("parallaxZ", config.zMm.toString()).putString("parallaxDistance", config.distanceMm.toString()).apply()
        latestLive = null
        placedSpotId = null
        selectCamera(keepPreview = true)
        return true
    }

    fun selectCamera(force: Boolean = false, resumeScan: Boolean = false, keepPreview: Boolean = false) {
        val goal = target()
        val next =
            lenses.find { it.id == cameraChoice }
                ?: lenses.filter { lens ->
                    val rotation = windowManager.defaultDisplay.rotation
                    val portrait = Math.floorMod(lens.angle - rotation * 90, 180) != 0
                    parallax.project(if (portrait) goal.swap() else goal, focal, rotation)
                        .coveredBy(if (portrait) lens.view.swap() else lens.view)
                }.minByOrNull { it.view.x }
                ?: lenses.firstOrNull()
        if (next == null) {
            message = "未找到可访问的后置摄像头"
            return
        }
        if (resumeScan && next.id == current?.id) {
            current = next
            rig.open(next)
            return
        }
        cancelScan()
        epoch++
        frozen = false
        spots.clear()
        held = null
        if (!keepPreview || force || current != next) sample = null
        if (force || current != next) {
            current = next
            cal = profiles.load(next.id)
            message = "正在连接 " + next.label
            rig.open(next)
        } else message = ""
    }

    private var lastScanView: Frame? = null
    private var lastScanTurn = 0
    private var lastScanMeasurement: Measurement? = null

    private fun process(packet: Rig.Packet, ev: Double, timestamp: Long, l: Lens) {
        val token = epoch
        if (frozen || l.id != current?.id) return
        val rot = windowManager.defaultDisplay.rotation
        val request = scanRequest
        // A preview packet queued before the scan lock cannot anchor a new panorama.
        if (request != null && packet.scanMetadata == null) return
        val scanRotation = request?.rotation ?: rot
        val turn = (l.angle - scanRotation * 90 + 360) % 360
        val transpose = turn % 180 != 0
        val src = if (transpose) l.view.swap() else l.view
        val gate = parallax.project(if (transpose) target().swap() else target(), focal, scanRotation)
        val goal = gate.field
        val live = LiveCapture(packet,ev,l.id,timestamp,turn,src,goal,gate.right,gate.up)
        val now = android.os.SystemClock.elapsedRealtime()
        val displayTurn = Math.floorMod(scanRotation - rot, 4) * 90
        val scan = request?.let {
            val frame by lazy(LazyThreadSafetyMode.NONE) { live.panoramaFrame() }
            val axes = pose.axes(scanRotation, packet.poseTimestampNs)
            val session = it.session ?: ScanSession(frame, goal, axes, response, OpenCvScanAligner.forMode(scanAlgorithm),
                axes?.let(::levelReading) ?: pose.level(scanRotation), gate.right, gate.up).also { s -> it.session = s }
            if (it.reanchor) { session.resumeTracking(); it.reanchor = false }
            val time = if (timestamp > 0) timestamp / 1_000_000 else now
            var candidate: Frame? = null
            val shown = session.capture(src,axes,time,live::trackingImage,packet.exposureMs) { frame.also { candidate = it } }
            val guide = session.guide(displayTurn).copy(lowLight = packet.panoramaLowLight)
            if (recordScan) candidate?.let { recordShot(it,src,gate,axes,packet.exposureMs,time,guide,displayTurn,packet.scanMetadata,
                session.lastCandidateDecision,session.lastCandidateChecks) }
            shown to guide
        }
        fun measure(): Measurement {
            val full = live.fullFrame()
            return Measurement(token,turn,full,full.distribution())
        }
        val measurement = if (scan != null) {
            val cached = lastScanMeasurement
            if (cached != null && lastScanView === scan.first && lastScanTurn == displayTurn) cached
            else {
                val displayed = upright(scan.first,displayTurn)
                Measurement(token,turn,displayed,displayed.distribution()).also {
                    lastScanView = scan.first
                    lastScanTurn = displayTurn
                    lastScanMeasurement = it
                }
            }
        } else {
            lastScanMeasurement = null
            lastScanView = null
            val cached = lastMeasure?.takeIf { it.epoch == token && it.turn == turn &&
                it.frame.id == l.id && it.frame.w == live.width && it.frame.h == live.height &&
                it.frame.encoding == packet.encoding }
            if (cached == null) {
                lastMeasureAt = now
                measure().also { lastMeasure = it }
            } else {
                if (now - lastMeasureAt >= 200 && !meterWorker.isShutdown && measuring.compareAndSet(false,true)) {
                    lastMeasureAt = now
                    try {
                        meterWorker.execute {
                            try { if (epoch == token) lastMeasure = measure() }
                            finally { measuring.set(false) }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) { measuring.set(false) }
                }
                cached
            }
        }
        val out = measurement.frame
        val still = if (scan != null) stillDetector.observe(out,pose.matrix,now)
            else stillDetector.observe(live.signature(),Triple(l.id,live.width,live.height),pose.matrix,now)
        val result = Sample(out, measurement.hist, !gate.coveredBy(src), scan?.second,
            still, packet.w, packet.h, if (scan == null) live else null)
        runOnUiThread {
            if (!active || token != epoch || frozen) return@runOnUiThread
            if (message.startsWith("正在连接")) {
                message = ""
                android.util.Log.i(
                    "LightData",
                    "Frame from " + l.label + " via " + l.owner + " " + out.w + "x" + out.h,
                )
            }
            latestFaces = if (faceMeterEnabled) result.live?.faces else null
            latestLive = result.live
            val old = sample
            val view = previewView.get()
            if (result.live != null && old?.frame === result.frame && old.still == result.still &&
                view != null && view.isAttachedToWindow) {
                // Only the camera texture changed. Sliders and histogram follow the
                // independent full-frame measurement, not the sensor callback rate.
                view.advance(result.live,effectiveMiddleEv)
            } else sample = result
            if (single && held == null) {
                holdReading()
            }
            if (cal == null && !prefs.getBoolean("warned", false) && !isFinishing) {
                prefs.edit().putBoolean("warned", true).apply()
                AlertDialog.Builder(this)
                    .setTitle("当前镜头尚未校准")
                    .setMessage("曝光与分区使用估算值。可在设置中使用灰卡或阶梯卡校准；每个镜头独立保存。")
                    .setPositiveButton("继续取景", null)
                    .show()
            }
        }
    }

    fun cancelScan() {
        val previous = scanRequest
        scanRequest = null
        if (previous != null) {
            rig.releaseScan { previous.session?.close() }
            epoch++
            rig.setPanoramaLock(false)
        }
    }

    fun toggleScan() {
        if (scanActive) {
            cancelScan()
            return
        }
        if (sample?.missing != true || frozen || runningCal) return
        if (recordScan) synchronized(scanShots) {
            scanShots.clear()
            recordedAccepted = 0
        }
        rig.setPanoramaLock(true)
        scanRequest = ScanRequest(windowManager.defaultDisplay.rotation)
        epoch++
    }

    fun toggleFreeze() {
        val current = sample ?: return
        if (!frozen) {
            frozenExposure = exposure
            val rotation = windowManager.defaultDisplay.rotation
            frozenAxes = pose.axes(rotation)
            frozenLevel = pose.level(rotation)
            current.live?.let { original ->
                val it = previewView.get()?.renderedLive?.takeIf { capture ->
                    capture.id == original.id && capture.width == original.width &&
                        capture.height == original.height && capture.turn == original.turn && capture.gate.contentEquals(original.gate)
                } ?: latestLive ?: original
                frozenAxes = pose.axes(rotation,it.image.poseTimestampNs)
                frozenLevel = frozenAxes?.let(::levelReading) ?: frozenLevel
                val full = it.fullFrame()
                sample = current.copy(frame = full, hist = full.distribution(), live = null)
            }
            latestLive = null
        }
        placedSpotId = null
        frozen = !frozen
        spots.clear()
        number = 1
    }

    fun point(x: Double, y: Double) {
        val f = sample?.frame ?: return
        if (!frozen || f.sample(x, y) ushr 24 == 0) return
        val old = spots.find { hypot(it.x - x, it.y - y) < .045 }
        if (old != null) {
            soundFeedback.play(FeedbackCue.REMOVE)
            spots.remove(old)
            if (old.id == placedSpotId) clearPlacement()
        }
        else {
            soundFeedback.play(FeedbackCue.ADD)
            spots += Spot(number++, x, y, f.spot(x, y, response))
        }
    }

    fun setValues(e: Exposure) {
        manual = if (isoLocked) e.copy(iso = filmIso) else e
        prefs.edit().putString("t", manual.t.toString()).putString("a", manual.a.toString())
            .putString("iso", manual.iso.toString()).apply()
    }

    fun toggleIsoLock() {
        if (frozen) return
        isoLocked = !isoLocked
        if (isoLocked) manual = manual.copy(iso = filmIso)
        prefs.edit().putBoolean("filmIsoLocked", isoLocked).putString("iso", manual.iso.toString()).apply()
    }

    fun adjustCompensation(ev: Double) {
        if (frozen || mode == "M") return
        compensationEv = ev.coerceIn(-5.0, 5.0)
        put("compensationEv", compensationEv)
    }

    fun meterOnce() {
        if (frozen) return
        playMeterSound()
        single = true
        holdReading()
    }

    fun modeTo(v: String) {
        setValues(exposure)

        mode = v
        put("mode", v)
    }

    fun applyFormat(v: Format) {
        format = v
        put("format", v.name)
        put("width", v.w)
        put("height", v.h)
        selectCamera()
    }

    private fun restoreFilm() {
        customPresets = CustomPresets.decode(prefs.getString("customPresets", null)
            ?: CustomPresets.encode(emptyList()))
        val previous = prefs.getString("film", films.first().name)
        var preset = customPresets.find { it.id == prefs.getString("customPresetId", null) }
        if (preset == null && previous in listOf("自定义胶片", "自定义数码")) {
            preset = CustomPreset.create(previous!!, previous == "自定义数码", saved("total", 12.0),
                saved("above", 4.0), manual.iso)
            customPresets = customPresets + preset
            prefs.edit().putString("customPresets", CustomPresets.encode(customPresets))
                .putString("customPresetId", preset.id).apply()
        }
        val record = filmRecords.find { it.film.name == previous } ?: filmRecords.first()
        selectedPresetId = preset?.id
        film = preset?.toFilm() ?: record.film
        filmIso = preset?.iso ?: record.iso.toDouble()
        // Keep an existing user's deliberate EI override when upgrading an older installation.
        isoLocked = prefs.getBoolean("filmIsoLocked", !prefs.contains("iso") || manual.iso == filmIso)
        if (isoLocked) manual = manual.copy(iso = filmIso)
        prefs.edit().putBoolean("filmIsoLocked", isoLocked).apply()
    }

    private fun applyFilm(value: Film, speed: Double, id: String?, lock: Boolean) {
        film = value
        filmIso = speed
        selectedPresetId = id
        isoLocked = lock
        if (lock) manual = manual.copy(iso = speed)
        if (frozen) frozenExposure = meteredExposure()
        prefs.edit().putString("film", value.name).putString("customPresetId", id)
            .putBoolean("filmIsoLocked", lock).putString("iso", manual.iso.toString()).apply()
    }

    fun selectFilm(record: FilmRecord) = applyFilm(record.film, record.iso.toDouble(), null, true)
    fun selectPreset(preset: CustomPreset) = applyFilm(preset.toFilm(), preset.iso, preset.id, true)

    fun savePreset(preset: CustomPreset) {
        customPresets = CustomPresets.merge(customPresets, listOf(preset))
        prefs.edit().putString("customPresets", CustomPresets.encode(customPresets)).apply()
        selectPreset(preset)
    }

    private fun importPresets(incoming: List<CustomPreset>) {
        val merged = CustomPresets.merge(customPresets, incoming)
        val selected = merged.find { it.id == selectedPresetId }
        val changed = merged.count { it !in customPresets }
        customPresets = merged
        prefs.edit().putString("customPresets", CustomPresets.encode(merged)).apply()
        if (selected != null && incoming.any { it.id == selected.id })
            applyFilm(selected.toFilm(), selected.iso, selected.id, isoLocked)
        notifyUser("已读取 ${incoming.size} 个预设，新增或更新 ${changed} 个")
    }

    private val openPresets = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::readPresetDocument)
    }

    internal fun readPresetDocument(uri: Uri) = presetIO({
        requireNotNull(contentResolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8)
            .use { CustomPresets.decode(it) }
    }, ::importPresets)

    private val writePresets = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(::writePresetDocument)
    }

    internal fun writePresetDocument(uri: Uri) {
        val json = CustomPresets.encode(customPresets)
        presetIO({
            requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8)
                .use { it.write(json) }
        }, { notifyUser("自定义预设已导出") })
    }

    // Document providers can classify plain JSON as text, binary, or a vendor type.
    fun pickPresetFile() = openPresets.launch(arrayOf("*/*"))
    fun exportPresetFile() = writePresets.launch("LightData-presets.json")

    private fun <T> presetIO(work: () -> T, complete: (T) -> Unit) {
        Thread {
            val result = runCatching(work)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) runCatching { complete(result.getOrThrow()) }
                    .onFailure { notifyUser("预设文件操作失败：" + it.localizedMessage) }
            }
        }.start()
    }

    fun notifyUser(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    fun choose(title: String, items: List<String>, action: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, i -> action(i) }
            .show()
    }

    fun input(
        title: String,
        initial: Double,
        range: ClosedFloatingPointRange<Double>,
        action: (Double) -> Unit,
    ) {
        val keyboard = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
            (if (range.start < 0) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
        inputValue(title, isoLabel(initial), keyboard, "请输入 ${n(range.start)} 至 ${n(range.endInclusive)}",
            { it.toDoubleOrNull()?.takeIf { n -> n.isFinite() && n in range } }, action)
    }

    internal fun inputValue(title: String, initial: String, keyboard: Int, errorText: String,
                            parse: (String) -> Double?, action: (Double) -> Unit) {
        val edit =
            EditText(this).apply {
                inputType = keyboard
                setSingleLine()
                setText(initial)
                selectAll()
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(edit)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val v = parse(edit.text.toString().trim())
                if (v == null) edit.error = errorText
                else {
                    dialog.dismiss()
                    action(v)
                }
            }
        }
        dialog.show()
    }

    fun formatDialog() = selectFormatCategory()

    fun customIsoDialog() {
        if (frozen) { notifyUser("请先退出点测光"); return }
        input(if (isoLocked) "自定义 ISO（应用后解锁）" else "自定义 ISO", manual.iso, .001..10000000.0) {
            if (isoLocked) toggleIsoLock()
            setValues(manual.copy(iso = it))
        }
    }

    fun changeFocal() {
        input("镜头实际焦距（mm）", focal, 1.0..5000.0) {
            focal = it
            put("focal", it)
            if (!parallax.valid(focal)) updateParallax(parallax.copy(distanceMm = Double.POSITIVE_INFINITY))
            else selectCamera()
        }
    }

    fun calibrationDialog() {
        choose("逐镜头校准", listOf("均匀灰卡（推荐）", "九级阶梯卡（推荐）", "现场估测")) { i ->
            val method = listOf("灰卡", "阶梯卡", "现场")[i]
            fun confirm(step: Double) {
                AlertDialog.Builder(this)
                    .setTitle(method + "校准")
                    .setMessage(
                        listOf(
                            "固定手机，在均匀稳定的光照下让灰卡充满镜头。",
                            "横持手机，将九级灰阶从暗至亮横向排列，对准传感器中央 80% 宽度。",
                            "固定手机，对准同时有阴影和亮部的静止场景。",
                        )[i] + " 采集期间请保持不动。"
                    )
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始采集") { _, _ ->
                        settings = false
                        frozen = false
                        spots.clear()
                        cancelScan()
                        runningCal = true
                        message = "校准 准备中"
                        rig.calibrate(method, step)
                    }
                    .show()
            }
            if (i == 1) input("相邻灰阶间隔（EV）", 1.0, .1..3.0, ::confirm) else confirm(1.0)
        }
    }
}
