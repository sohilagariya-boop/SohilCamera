package com.sohil.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.location.Geocoder
import android.location.Location
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

class MainActivity : AppCompatActivity() {

    // views
    private lateinit var preview: PreviewView
    private lateinit var locText: TextView
    private lateinit var thumb: ImageView
    private lateinit var flashChip: TextView
    private lateinit var timerChip: TextView
    private lateinit var burstChip: TextView
    private lateinit var gridChip: TextView
    private lateinit var proChip: TextView
    private lateinit var setChip: TextView
    private lateinit var flipBtn: TextView
    private lateinit var recTimer: TextView
    private lateinit var photoTab: TextView
    private lateinit var videoTab: TextView
    private lateinit var captureBtn: View
    private lateinit var grid: View
    private lateinit var focusRing: View
    private lateinit var countdown: TextView
    private lateinit var zoomText: TextView
    private lateinit var proPanel: View
    private lateinit var evLabel: TextView
    private lateinit var evSeek: SeekBar
    private lateinit var isoRow: LinearLayout
    private lateinit var wbRow: LinearLayout

    // camera
    private lateinit var fused: FusedLocationProviderClient
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var videoMode = false
    private var saving = false

    // options
    private var flashState = 0                  // 0 off, 1 auto, 2 on, 3 torch
    private val timerOpts = listOf(0, 3, 5, 10)
    private var timerIdx = 0
    private val burstOpts = listOf(1, 3, 5, 10)
    private var burstIdx = 0
    private var burstLeft = 0
    private var gridOn = false
    private var isoIdx = 0
    private var wbIdx = 0
    private val isoVals = listOf(0, 100, 200, 400, 800, 1600, 3200)
    private val wbNames = listOf("Auto", "Daylight", "Cloudy", "Fluorescent", "Incandescent")
    private val wbModes = listOf(
        CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
    )
    private var evRange: Range<Int>? = null
    private var evStep = 0f
    private var countdownRunnable: Runnable? = null

    // saved settings
    private val prefs by lazy { getSharedPreferences("ms_camera", MODE_PRIVATE) }
    private val photoSizes = listOf("High (12 MP)", "Medium (5 MP)", "Low (2 MP)")
    private val photoSizeVals = listOf(Size(4000, 3000), Size(2560, 1920), Size(1600, 1200))
    private val jpegNames = listOf("Best (95%)", "Good (85%)", "Small (70%)")
    private val jpegVals = listOf(95, 85, 70)
    private val videoNames = listOf("4K (UHD)", "Full HD 1080p", "HD 720p", "SD 480p")
    private val videoVals = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)

    // location / address / map
    @Volatile private var lastLocation: Location? = null
    @Volatile private var addrTitle: String? = null
    @Volatile private var addrFull: String? = null
    @Volatile private var geoFor: Location? = null
    @Volatile private var comp: Bitmap? = null
    @Volatile private var compKey: String? = null
    @Volatile private var compPx = 256f
    @Volatile private var compPy = 256f
    @Volatile private var busy = false
    private var lastUri: Uri? = null
    private var lastMime = "image/jpeg"

    private val locCallback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            lastLocation = l
            updateLocText()
            refreshExtras(l)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        locText = findViewById(R.id.locText)
        thumb = findViewById(R.id.thumb)
        flashChip = findViewById(R.id.flashChip)
        timerChip = findViewById(R.id.timerChip)
        burstChip = findViewById(R.id.burstChip)
        gridChip = findViewById(R.id.gridChip)
        proChip = findViewById(R.id.proChip)
        setChip = findViewById(R.id.setChip)
        flipBtn = findViewById(R.id.flipBtn)
        recTimer = findViewById(R.id.recTimer)
        photoTab = findViewById(R.id.photoTab)
        videoTab = findViewById(R.id.videoTab)
        captureBtn = findViewById(R.id.captureBtn)
        grid = findViewById(R.id.grid)
        focusRing = findViewById(R.id.focusRing)
        countdown = findViewById(R.id.countdown)
        zoomText = findViewById(R.id.zoomText)
        proPanel = findViewById(R.id.proPanel)
        evLabel = findViewById(R.id.evLabel)
        evSeek = findViewById(R.id.evSeek)
        isoRow = findViewById(R.id.isoRow)
        wbRow = findViewById(R.id.wbRow)

        photoSizeIdx = prefs.getInt("psize", 0)
        jpegIdx = prefs.getInt("jpeg", 0)
        videoIdx = prefs.getInt("video", 1)

        captureBtn.setOnClickListener { v ->
            v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }.start()
            onShutter()
        }
        flipBtn.setOnClickListener { flipCamera() }
        photoTab.setOnClickListener { setMode(false) }
        videoTab.setOnClickListener { setMode(true) }
        flashChip.setOnClickListener { cycleFlash() }
        timerChip.setOnClickListener {
            timerIdx = (timerIdx + 1) % timerOpts.size
            timerChip.text = if (timerOpts[timerIdx] == 0) "⏱ Off" else "⏱ ${timerOpts[timerIdx]}s"
            setChipOn(timerChip, timerIdx != 0)
        }
        burstChip.setOnClickListener {
            if (videoMode) { toast("Burst sirf photo mode mein"); return@setOnClickListener }
            burstIdx = (burstIdx + 1) % burstOpts.size
            burstChip.text = "🔁 ${burstOpts[burstIdx]}x"
            setChipOn(burstChip, burstIdx != 0)
        }
        gridChip.setOnClickListener {
            gridOn = !gridOn
            grid.visibility = if (gridOn) View.VISIBLE else View.GONE
            setChipOn(gridChip, gridOn)
        }
        proChip.setOnClickListener {
            val show = proPanel.visibility != View.VISIBLE
            proPanel.visibility = if (show) View.VISIBLE else View.GONE
            setChipOn(proChip, show)
        }
        setChip.setOnClickListener { showSettings() }
        thumb.setOnClickListener {
            val u = lastUri ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, lastMime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) { toast("Gallery nahi khuli") }
        }
        for (c in listOf(flashChip, timerChip, burstChip, gridChip, proChip, setChip)) setChipOn(c, false)

        setupGestures()
        setupProPanel()

        if (allGranted()) startAll() else ActivityCompat.requestPermissions(this, askPerms(), 10)
    }

    private var photoSizeIdx = 0
    private var jpegIdx = 0
    private var videoIdx = 1

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun setChipOn(tv: TextView, on: Boolean) {
        tv.setBackgroundResource(if (on) R.drawable.chip_bg_on else R.drawable.chip_bg)
        tv.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    // ---------------------------------------------------------------- permissions
    private fun askPerms(): Array<String> {
        val l = mutableListOf(
            Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT <= 28) l.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return l.toTypedArray()
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun allGranted() = has(Manifest.permission.CAMERA) &&
        has(Manifest.permission.ACCESS_FINE_LOCATION) &&
        (Build.VERSION.SDK_INT > 28 || has(Manifest.permission.WRITE_EXTERNAL_STORAGE))

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (allGranted()) startAll()
        else Toast.makeText(this, "Camera aur Location permission zaroori hai", Toast.LENGTH_LONG).show()
    }

    private fun startAll() { startCamera(); startLocation(); loadLastThumb() }

    private fun startLocation() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()
        try { fused = LocationServices.getFusedLocationProviderClient(this)
            fused.requestLocationUpdates(req, locCallback, Looper.getMainLooper()) }
        catch (_: SecurityException) {}
    }

    // ---------------------------------------------------------------- gestures
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val zs = cam.cameraInfo.zoomState.value ?: return true
                val ratio = (zs.zoomRatio * d.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                cam.cameraControl.setZoomRatio(ratio)
                zoomText.text = String.format(Locale.US, "%.1fx", ratio)
                zoomText.visibility = View.VISIBLE
                zoomText.removeCallbacks(hideZoom)
                zoomText.postDelayed(hideZoom, 1200)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { focusAt(e.x, e.y); return true }
        })
        preview.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) tap.onTouchEvent(ev)
            true
        }
    }

    private val hideZoom = Runnable { zoomText.visibility = View.INVISIBLE }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        try {
            val pt = preview.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS).build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (_: Exception) {}
        focusRing.x = x - focusRing.width / 2f
        focusRing.y = y - focusRing.height / 2f
        focusRing.animate().cancel()
        focusRing.alpha = 1f
        focusRing.scaleX = 1.3f; focusRing.scaleY = 1.3f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(800).setDuration(300).withEndAction {
                focusRing.visibility = View.INVISIBLE
            }.start()
        }.start()
    }

    // ---------------------------------------------------------------- camera binding
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val p = provider ?: return
        val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val sel = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        p.unbindAll()
        try {
            camera = if (videoMode) {
                val rec = Recorder.Builder().setQualitySelector(
                    QualitySelector.from(videoVals[videoIdx], FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                ).build()
                val vc = VideoCapture.withOutput(rec)
                videoCapture = vc
                p.bindToLifecycle(this, sel, pv, vc)
            } else {
                val rs = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(photoSizeVals[photoSizeIdx], ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()
                val ic = ImageCapture.Builder()
                    .setCaptureMode(
                        if (photoSizeIdx == 0) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                        else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    )
                    .setResolutionSelector(rs).build()
                imageCapture = ic
                p.bindToLifecycle(this, sel, pv, ic)
            }
            if (camera?.cameraInfo?.hasFlashUnit() != true) flashState = 0
            applyFlash()
            setupExposure()
            applyCam2()
        } catch (e: Exception) {
            Toast.makeText(this, "Camera nahi khuli: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun flipCamera() {
        if (recording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bindUseCases()
    }

    private fun setMode(video: Boolean) {
        if (recording != null || video == videoMode) return
        videoMode = video
        if (video && (flashState == 1 || flashState == 2)) flashState = 0
        photoTab.setTextColor(if (video) Color.WHITE else Color.parseColor("#FFC107"))
        videoTab.setTextColor(if (video) Color.parseColor("#FFC107") else Color.WHITE)
        captureBtn.setBackgroundResource(if (video) R.drawable.capture_video else R.drawable.capture_button)
        bindUseCases()
    }

    // ---------------------------------------------------------------- flash / torch
    private fun cycleFlash() {
        if (camera?.cameraInfo?.hasFlashUnit() != true) { toast("Is camera mein flash nahi hai"); return }
        flashState = if (videoMode) (if (flashState == 3) 0 else 3) else (flashState + 1) % 4
        applyFlash()
    }

    private fun applyFlash() {
        camera?.cameraControl?.enableTorch(flashState == 3)
        imageCapture?.flashMode = when (flashState) {
            1 -> ImageCapture.FLASH_MODE_AUTO
            2 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        flashChip.text = listOf("⚡ Off", "⚡ Auto", "⚡ On", "🔦 Torch")[flashState]
        setChipOn(flashChip, flashState != 0)
    }

    // ---------------------------------------------------------------- pro panel (EV / ISO / WB)
    private fun setupProPanel() {
        buildChips(isoRow, isoVals.map { if (it == 0) "Auto" else it.toString() }, { isoIdx }) {
            isoIdx = it; applyCam2()
        }
        buildChips(wbRow, wbNames, { wbIdx }) { wbIdx = it; applyCam2() }
        evSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val r = evRange ?: return
                if (!fromUser) return
                val idx = r.lower + p
                camera?.cameraControl?.setExposureCompensationIndex(idx)
                evLabel.text = String.format(Locale.US, "Exposure: %+.1f", idx * evStep)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun buildChips(row: LinearLayout, labels: List<String>, selected: () -> Int, onPick: (Int) -> Unit) {
        row.removeAllViews()
        val views = labels.map { l ->
            TextView(this).apply {
                text = l; textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
            }
        }
        fun refresh() = views.forEachIndexed { i, v -> setChipOn(v, i == selected()) }
        views.forEachIndexed { i, v -> v.setOnClickListener { onPick(i); refresh() } }
        views.forEach { row.addView(it) }
        refresh()
    }

    private fun setupExposure() {
        val st = camera?.cameraInfo?.exposureState
        if (st != null && st.isExposureCompensationSupported) {
            val r = st.exposureCompensationRange
            evRange = r
            evStep = st.exposureCompensationStep.toFloat()
            evSeek.max = r.upper - r.lower
            evSeek.progress = -r.lower
            evSeek.isEnabled = true
            evLabel.text = "Exposure: 0.0"
        } else {
            evRange = null
            evSeek.isEnabled = false
            evLabel.text = "Exposure: (is camera mein nahi)"
        }
    }

    private fun applyCam2() {
        val cam = camera ?: return
        try {
            val b = CaptureRequestOptions.Builder()
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wbModes[wbIdx])
            if (isoIdx > 0) {
                val range = Camera2CameraInfo.from(cam.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val iso = isoVals[isoIdx].coerceIn(range?.lower ?: 100, range?.upper ?: 3200)
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, 16_666_666L)
            }
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (e: Exception) {
            toast("ISO/WB is phone mein support nahi")
        }
    }

    // ---------------------------------------------------------------- settings dialog
    private fun showSettings() {
        val items = arrayOf(
            "Photo size: ${photoSizes[photoSizeIdx]}",
            "Photo quality: ${jpegNames[jpegIdx]}",
            "Video quality: ${videoNames[videoIdx]}"
        )
        AlertDialog.Builder(this).setTitle("Settings").setItems(items) { _, which ->
            when (which) {
                0 -> pick("Photo size", photoSizes, photoSizeIdx) { photoSizeIdx = it; prefs.edit().putInt("psize", it).apply(); bindUseCases() }
                1 -> pick("Photo quality", jpegNames, jpegIdx) { jpegIdx = it; prefs.edit().putInt("jpeg", it).apply() }
                2 -> pick("Video quality", videoNames, videoIdx) { videoIdx = it; prefs.edit().putInt("video", it).apply(); bindUseCases() }
            }
        }.show()
    }

    private fun pick(title: String, names: List<String>, cur: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(names.toTypedArray(), cur) { d, i -> onPick(i); d.dismiss() }.show()
    }

    // ---------------------------------------------------------------- shutter, timer, burst
    private fun onShutter() {
        if (countdownRunnable != null) { cancelCountdown(); return }
        if (recording != null) { recording?.stop(); return }
        withTimer { if (videoMode) toggleRecording() else startBurst() }
    }

    private fun withTimer(action: () -> Unit) {
        var left = timerOpts[timerIdx]
        if (left == 0) { action(); return }
        val r = object : Runnable {
            override fun run() {
                if (left > 0) {
                    countdown.text = left.toString()
                    countdown.visibility = View.VISIBLE
                    left--
                    handler.postDelayed(this, 1000)
                } else {
                    countdown.visibility = View.INVISIBLE
                    countdownRunnable = null
                    action()
                }
            }
        }
        countdownRunnable = r
        handler.post(r)
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        countdown.visibility = View.INVISIBLE
    }

    private fun startBurst() { burstLeft = burstOpts[burstIdx]; shootNext() }

    private fun shootNext() {
        if (burstLeft <= 0) return
        burstLeft--
        takePhoto { if (burstLeft > 0) handler.postDelayed({ shootNext() }, 300) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------- video
    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        val vc = videoCapture ?: return
        val name = "MS_VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MS Camera")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()
        var pr = vc.output.prepareRecording(this, opts)
        if (has(Manifest.permission.RECORD_AUDIO)) pr = pr.withAudioEnabled()
        recording = pr.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> setRecordingUi(true)
                is VideoRecordEvent.Status -> {
                    val sec = ev.recordingStats.recordedDurationNanos / 1_000_000_000L
                    recTimer.text = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    setRecordingUi(false)
                    if (!ev.hasError()) {
                        val u = ev.outputResults.outputUri
                        io.execute { showVideoThumb(u) }
                        toast("Video save ho gayi: Movies/MS Camera")
                    } else {
                        Toast.makeText(this, "Video error: ${ev.error}", Toast.LENGTH_LONG).show()
                    }
                }
                else -> {}
            }
        }
    }

    private fun setRecordingUi(on: Boolean) {
        recTimer.visibility = if (on) View.VISIBLE else View.GONE
        if (on) recTimer.text = "00:00"
        captureBtn.setBackgroundResource(if (on) R.drawable.capture_stop else R.drawable.capture_video)
        flipBtn.alpha = if (on) 0.4f else 1f
        photoTab.alpha = if (on) 0.4f else 1f
        videoTab.alpha = if (on) 0.4f else 1f
    }

    // ---------------------------------------------------------------- address + map
    private fun updateLocText() {
        val l = lastLocation ?: return
        val sb = StringBuilder()
        addrTitle?.let { sb.append(it).append("\n") }
        addrFull?.let { sb.append(it).append("\n") }
        sb.append(String.format(Locale.US, "Lat %.6f°  Long %.6f°", l.latitude, l.longitude))
        locText.text = sb.toString()
    }

    private fun refreshExtras(loc: Location) {
        if (busy) return
        val g = geoFor
        if (g != null && g.distanceTo(loc) < 25f && addrFull != null && comp != null) return
        busy = true
        io.execute {
            try { fetchAddress(loc) } catch (_: Exception) {}
            try { fetchMap(loc) } catch (_: Exception) {}
            if (addrFull != null) geoFor = loc
            busy = false
            runOnUiThread { updateLocText() }
        }
    }

    @Suppress("DEPRECATION")
    private fun fetchAddress(loc: Location) {
        if (!Geocoder.isPresent()) return
        val list = Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
        val a = list?.firstOrNull() ?: return
        val city = a.locality ?: a.subAdminArea
        addrTitle = listOfNotNull(city, a.adminArea, a.countryName).joinToString(", ")
        addrFull = a.getAddressLine(0)
    }

    private fun fetchMap(loc: Location) {
        val z = 17
        val n = (1 shl z).toDouble()
        val lat = Math.toRadians(loc.latitude)
        val xf = (loc.longitude + 180.0) / 360.0 * n
        val yf = (1.0 - ln(tan(lat) + 1.0 / cos(lat)) / Math.PI) / 2.0 * n
        val x0 = floor(xf - 0.5).toInt()
        val y0 = floor(yf - 0.5).toInt()
        compPx = ((xf - x0) * 256).toFloat()
        compPy = ((yf - y0) * 256).toFloat()
        val key = "$x0/$y0"
        if (key == compKey && comp != null) return
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        for (dx in 0..1) for (dy in 0..1) {
            val conn = URL("https://tile.openstreetmap.org/$z/${x0 + dx}/${y0 + dy}.png")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "MSCamera/1.0 (personal app)")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val b = conn.inputStream.use { BitmapFactory.decodeStream(it) } ?: return
            c.drawBitmap(b, dx * 256f, dy * 256f, null)
        }
        comp = out
        compKey = key
    }

    // ---------------------------------------------------------------- photo
    private fun takePhoto(onDone: () -> Unit) {
        val ic = imageCapture
        val loc = lastLocation
        if (ic == null) { burstLeft = 0; onDone(); return }
        if (loc == null) {
            toast("GPS location abhi nahi mili, thoda ruko")
            burstLeft = 0; onDone(); return
        }
        if (saving) { onDone(); return }
        saving = true
        ic.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = image.toBitmap()
                val rot = image.imageInfo.rotationDegrees
                image.close()
                io.execute {
                    try {
                        val m = Matrix().apply { postRotate(rot.toFloat()) }
                        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                        val uri = save(stamp(rotated, loc))
                        if (uri != null) {
                            showThumb(uri)
                            if (burstLeft == 0) runOnUiThread { toast("Save ho gayi: Pictures/MS Camera") }
                        }
                    } catch (e: Exception) {
                        burstLeft = 0
                        runOnUiThread { Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
                    } finally {
                        saving = false
                        runOnUiThread(onDone)
                    }
                }
            }
            override fun onError(e: ImageCaptureException) {
                saving = false
                burstLeft = 0
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                onDone()
            }
        })
    }

    private fun stamp(src: Bitmap, loc: Location): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val margin = w * 0.03f
        val pad = w * 0.02f
        val gap = w * 0.008f
        val mapSize = w * 0.26f

        val map = comp
        val px = compPx
        val py = compPy
        val title = addrTitle
        val full = addrFull

        val textLeft = margin + pad + (if (map != null) mapSize + pad else 0f)
        val textW = (w - margin - pad - textLeft).toInt()

        val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = w * 0.045f; typeface = Typeface.DEFAULT_BOLD
        }
        val bodyP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = w * 0.03f }
        fun lay(t: String, p: TextPaint): StaticLayout =
            StaticLayout.Builder.obtain(t, 0, t.length, p, textW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        val now = Date()
        val off = TimeZone.getDefault().getOffset(now.time) / 60000
        val offStr = String.format(Locale.US, "%s%02d:%02d", if (off < 0) "-" else "+", abs(off) / 60, abs(off) % 60)
        val dateStr = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a", Locale.getDefault()).format(now) + " GMT " + offStr

        val parts = mutableListOf<StaticLayout>()
        if (!title.isNullOrBlank()) parts += lay(title, titleP)
        if (!full.isNullOrBlank()) parts += lay(full, bodyP)
        parts += lay(String.format(Locale.US, "Lat %.6f°  Long %.6f°", loc.latitude, loc.longitude), bodyP)
        parts += lay(dateStr, bodyP)

        val textH = parts.sumOf { it.height } + gap * (parts.size - 1)
        val contentH = max(textH, if (map != null) mapSize else 0f)
        val panelH = contentH + 2 * pad
        val top = h - margin - panelH

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        c.drawRoundRect(RectF(margin, top, w - margin, h - margin), pad, pad, bg)

        if (map != null) {
            val dst = RectF(margin + pad, top + pad, margin + pad + mapSize, top + pad + mapSize)
            val sx = (px - 128f).coerceIn(0f, 256f).toInt()
            val sy = (py - 128f).coerceIn(0f, 256f).toInt()
            val srcR = Rect(sx, sy, sx + 256, sy + 256)
            c.save()
            val clip = Path().apply { addRoundRect(dst, pad * 0.6f, pad * 0.6f, Path.Direction.CW) }
            c.clipPath(clip)
            c.drawBitmap(map, srcR, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            val pxPin = dst.left + (px - sx) / 256f * mapSize
            val pyPin = dst.top + (py - sy) / 256f * mapSize
            val r = mapSize * 0.06f
            c.drawCircle(pxPin, pyPin, r * 1.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawCircle(pxPin, pyPin, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 30, 30) })
            val at = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = mapSize * 0.07f }
            c.drawText("© OpenStreetMap", dst.left + 6f, dst.bottom - 6f, at)
            c.restore()
        }

        var y = top + (panelH - textH) / 2f
        for (l in parts) {
            c.save(); c.translate(textLeft, y); l.draw(c); c.restore()
            y += l.height + gap
        }
        return out
    }

    private fun save(bmp: Bitmap): Uri? {
        val name = "MS_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MS Camera")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, jpegVals[jpegIdx], it) }
        return uri
    }

    // ---------------------------------------------------------------- last photo thumbnail
    private fun latest(coll: Uri): Pair<Uri, Long>? {
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        return contentResolver.query(
            coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
            sel, arrayOf("MS_%", "SOHIL_%"), "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cur ->
            if (cur.moveToFirst()) Pair(ContentUris.withAppendedId(coll, cur.getLong(0)), cur.getLong(1)) else null
        }
    }

    private fun loadLastThumb() {
        io.execute {
            try {
                val img = latest(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                val vid = latest(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                if (vid != null && (img == null || vid.second > img.second)) showVideoThumb(vid.first)
                else if (img != null) showThumb(img.first)
            } catch (_: Exception) {}
        }
    }

    private fun showVideoThumb(uri: Uri) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            val f = r.getFrameAtTime(0) ?: return
            runOnUiThread { thumb.setImageBitmap(f); lastUri = uri; lastMime = "video/mp4" }
        } catch (_: Exception) {
        } finally { r.release() }
    }

    private fun showThumb(uri: Uri) {
        val bmp = decodeSampled(uri, 200) ?: return
        runOnUiThread { thumb.setImageBitmap(bmp); lastUri = uri; lastMime = "image/jpeg" }
    }

    private fun decodeSampled(uri: Uri, target: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { fused.removeLocationUpdates(locCallback) } catch (_: Exception) {}
        io.shutdown()
    }
}
