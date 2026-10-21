package com.sohil.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.location.Geocoder
import android.location.Location
import android.media.MediaActionSound
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
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
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.effects.OverlayEffect
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
import androidx.exifinterface.media.ExifInterface
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

    // ---------- views
    private lateinit var previewBox: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var locText: TextView
    private lateinit var thumb: ImageView
    private lateinit var btnTimer: View
    private lateinit var btnBurst: View
    private lateinit var btnGrid: View
    private lateinit var btnFlash: View
    private lateinit var iconTimer: ImageView
    private lateinit var iconBurst: ImageView
    private lateinit var iconGrid: ImageView
    private lateinit var iconFlash: ImageView
    private lateinit var lblTimer: TextView
    private lateinit var lblBurst: TextView
    private lateinit var lblGrid: TextView
    private lateinit var lblFlash: TextView
    private lateinit var flipBtn: ImageView
    private lateinit var recTimer: TextView
    private lateinit var photoTab: TextView
    private lateinit var videoTab: TextView
    private lateinit var proModeTab: TextView
    private lateinit var captureBtn: View
    private lateinit var grid: View
    private lateinit var focusRing: View
    private lateinit var countdown: TextView
    private lateinit var zoomText: TextView
    private lateinit var z1: TextView
    private lateinit var z2: TextView
    private lateinit var z4: TextView
    private lateinit var proPanel: View
    private lateinit var wbScroll: View
    private lateinit var isoScroll: View
    private lateinit var evBox: View
    private lateinit var tabWb: TextView
    private lateinit var tabIso: TextView
    private lateinit var tabEv: TextView
    private lateinit var evLabel: TextView
    private lateinit var evSeek: SeekBar
    private lateinit var isoRow: LinearLayout
    private lateinit var wbRow: LinearLayout

    // ---------- camera
    private lateinit var fused: FusedLocationProviderClient
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val fxThread = HandlerThread("ms_fx").apply { start() }
    private val fxHandler by lazy { Handler(fxThread.looper) }
    private val sound = MediaActionSound()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var overlayEffect: OverlayEffect? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var videoMode = false
    private var saving = false
    @Volatile private var videoRot = 90
    @Volatile private var awbResult = -1

    // ---------- options
    private var flashState = 0                  // 0 off, 1 auto, 2 torch (always on)
    private var uiMode = 0                      // 0 photo, 1 video, 2 pro
    private var proTab = 0
    private var curEv = 0f
    private var curZoom = 1f
    private var minZoom = 1f
    private var maxZoom = 8f
    private var cam2Applied = false
    private var zoomLive: androidx.lifecycle.LiveData<ZoomState>? = null
    private val zoomObs = androidx.lifecycle.Observer<ZoomState> { zs ->
        minZoom = zs.minZoomRatio; maxZoom = zs.maxZoomRatio
    }
    private var refreshIso: () -> Unit = {}
    private var refreshWb: () -> Unit = {}
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
    // software fallback gains (R, G, B) when the phone ignores hardware white balance
    private val wbGains = listOf(
        floatArrayOf(1f, 1f, 1f), floatArrayOf(1.06f, 1f, 0.94f), floatArrayOf(1.14f, 1f, 0.86f),
        floatArrayOf(0.92f, 1f, 1.12f), floatArrayOf(0.80f, 1f, 1.30f)
    )
    private var evRange: Range<Int>? = null
    private var evStep = 0f
    private var countdownRunnable: Runnable? = null

    // ---------- saved settings
    private val prefs by lazy { getSharedPreferences("ms_camera", MODE_PRIVATE) }
    private var photoSizeIdx = 0
    private var jpegIdx = 0
    private var videoIdx = 1
    private var aspectIdx = 1                   // 0 = 1:1, 1 = 4:3, 2 = 16:9, 3 = Full
    private var shutterSound = true
    private var mirrorSelfie = true
    private var locTags = true
    private var volAction = 0                   // 0 shutter, 1 zoom, 2 off
    private var stampPhoto = true
    private var stampMap = true
    private var stampVideo = true
    private var recAudio = true
    private var prefsReady = false
    private val sizes43 = listOf(Size(4000, 3000), Size(2560, 1920), Size(1600, 1200))
    private val sizes169 = listOf(Size(3840, 2160), Size(2560, 1440), Size(1920, 1080))
    private val jpegVals = listOf(95, 85, 70)
    private val videoVals = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)

    // ---------- location / address / map
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

    // video stamp cache
    private var stampKey = ""
    private var stampBmp: Bitmap? = null

    private val yellow = Color.parseColor("#FFC107")

    private val locCallback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            lastLocation = l
            updateLocText()
            refreshExtras(l)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ================================================================ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewBox = findViewById(R.id.previewBox)
        preview = findViewById(R.id.preview)
        preview.scaleType = PreviewView.ScaleType.FILL_CENTER
        locText = findViewById(R.id.locText)
        thumb = findViewById(R.id.thumb)
        btnTimer = findViewById(R.id.btnTimer)
        btnBurst = findViewById(R.id.btnBurst)
        btnGrid = findViewById(R.id.btnGrid)
        btnFlash = findViewById(R.id.btnFlash)
        iconTimer = findViewById(R.id.iconTimer)
        iconBurst = findViewById(R.id.iconBurst)
        iconGrid = findViewById(R.id.iconGrid)
        iconFlash = findViewById(R.id.iconFlash)
        lblTimer = findViewById(R.id.lblTimer)
        lblBurst = findViewById(R.id.lblBurst)
        lblGrid = findViewById(R.id.lblGrid)
        lblFlash = findViewById(R.id.lblFlash)
        flipBtn = findViewById(R.id.flipBtn)
        recTimer = findViewById(R.id.recTimer)
        photoTab = findViewById(R.id.photoTab)
        videoTab = findViewById(R.id.videoTab)
        proModeTab = findViewById(R.id.proTabMode)
        captureBtn = findViewById(R.id.captureBtn)
        grid = findViewById(R.id.grid)
        focusRing = findViewById(R.id.focusRing)
        countdown = findViewById(R.id.countdown)
        zoomText = findViewById(R.id.zoomText)
        z1 = findViewById(R.id.z1); z2 = findViewById(R.id.z2); z4 = findViewById(R.id.z4)
        proPanel = findViewById(R.id.proPanel)
        wbScroll = findViewById(R.id.wbScroll)
        isoScroll = findViewById(R.id.isoScroll)
        evBox = findViewById(R.id.evBox)
        tabWb = findViewById(R.id.tabWb)
        tabIso = findViewById(R.id.tabIso)
        tabEv = findViewById(R.id.tabEv)
        evLabel = findViewById(R.id.evLabel)
        evSeek = findViewById(R.id.evSeek)
        isoRow = findViewById(R.id.isoRow)
        wbRow = findViewById(R.id.wbRow)

        loadPrefs()
        prefsReady = true

        captureBtn.setOnClickListener { v ->
            v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }.start()
            onShutter()
        }
        flipBtn.setOnClickListener { flipCamera() }
        photoTab.setOnClickListener { setMode(0) }
        videoTab.setOnClickListener { setMode(1) }
        proModeTab.setOnClickListener { setMode(2) }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenuSheet() }
        btnFlash.setOnClickListener { cycleFlash() }
        btnTimer.setOnClickListener { timerIdx = (timerIdx + 1) % timerOpts.size; updateTimerUi() }
        btnBurst.setOnClickListener { cycleBurst() }
        btnGrid.setOnClickListener { gridOn = !gridOn; updateGridUi() }
        thumb.setOnClickListener {
            val u = lastUri ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, lastMime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) { toast("Gallery nahi khuli") }
        }
        z1.setOnClickListener { setZoom(1f, true) }
        z2.setOnClickListener { setZoom(2f, true) }
        z4.setOnClickListener { setZoom(4f, true) }
        tabWb.setOnClickListener { showProTab(0) }
        tabIso.setOnClickListener { showProTab(1) }
        tabEv.setOnClickListener { showProTab(2) }
        previewBox.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) applyAspect()
        }

        setupGestures()
        setupProPanel()
        showProTab(0)
        updateModeTabs()
        updateZoomUi()
        updateTimerUi(); updateGridUi(); updateBurstUi()

        if (allGranted()) startAll() else ActivityCompat.requestPermissions(this, askPerms(), 10)
    }

    private fun loadPrefs() {
        photoSizeIdx = prefs.getInt("psize", 0).coerceIn(0, 2)
        jpegIdx = prefs.getInt("jpeg", 0).coerceIn(0, 2)
        videoIdx = prefs.getInt("video", 1).coerceIn(0, 3)
        aspectIdx = prefs.getInt("aspect", 1).coerceIn(0, 3)
        shutterSound = prefs.getBoolean("shutter_sound", true)
        mirrorSelfie = prefs.getBoolean("mirror_selfie", true)
        locTags = prefs.getBoolean("loc_tags", true)
        volAction = prefs.getInt("vol_action", 0)
        stampPhoto = prefs.getBoolean("stamp_photo", true)
        stampMap = prefs.getBoolean("stamp_map", true)
        stampVideo = prefs.getBoolean("stamp_video", true)
        recAudio = prefs.getBoolean("rec_audio", true)
    }

    override fun onResume() {
        super.onResume()
        if (!prefsReady) return
        val before = "$photoSizeIdx|$videoIdx|$stampVideo"
        loadPrefs()
        if (provider != null && recording == null && before != "$photoSizeIdx|$videoIdx|$stampVideo") bindUseCases()
    }

    private fun tint(icon: ImageView, label: TextView, on: Boolean) {
        icon.setColorFilter(if (on) yellow else Color.WHITE)
        label.setTextColor(if (on) yellow else Color.WHITE)
    }

    private fun updateTimerUi() {
        lblTimer.text = if (timerOpts[timerIdx] == 0) "Off" else "${timerOpts[timerIdx]}s"
        tint(iconTimer, lblTimer, timerIdx != 0)
    }

    private fun updateGridUi() {
        grid.visibility = if (gridOn) View.VISIBLE else View.GONE
        lblGrid.text = if (gridOn) "On" else "Off"
        tint(iconGrid, lblGrid, gridOn)
    }

    private fun updateBurstUi() {
        lblBurst.text = "${burstOpts[burstIdx]}x"
        tint(iconBurst, lblBurst, burstIdx != 0)
    }

    private fun cycleBurst() {
        if (videoMode) { toast("Burst sirf photo mode mein"); return }
        burstIdx = (burstIdx + 1) % burstOpts.size
        updateBurstUi()
    }

    private fun setChipOn(tv: TextView, on: Boolean) {
        tv.setBackgroundResource(if (on) R.drawable.chip_bg_on else R.drawable.chip_bg)
        tv.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    // ================================================================ menu sheet (top)
    private fun segmented(row: LinearLayout, labels: List<String>, sel: Int, onPick: (Int) -> Unit) {
        row.removeAllViews()
        val tvs = labels.map { l ->
            TextView(this).apply {
                text = l; gravity = Gravity.CENTER; textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
        }
        fun paint(s: Int) = tvs.forEachIndexed { i, t -> t.setTextColor(if (i == s) yellow else Color.WHITE) }
        tvs.forEachIndexed { i, t -> t.setOnClickListener { paint(i); onPick(i) }; row.addView(t) }
        paint(sel)
    }

    private fun showMenuSheet() {
        val v = layoutInflater.inflate(R.layout.sheet_menu, null)
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(v)
        dlg.window?.apply {
            setGravity(Gravity.TOP)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        v.findViewById<View>(R.id.menuClose).setOnClickListener { dlg.dismiss() }
        segmented(v.findViewById(R.id.aspectRow), listOf("1:1", "4:3", "16:9", "Full"), aspectIdx) {
            aspectIdx = it
            prefs.edit().putInt("aspect", it).apply()
            if (videoMode) toast("Video hamesha 16:9 mein hoti hai")
            applyAspect()
            if (recording == null) bindUseCases()
        }
        segmented(v.findViewById(R.id.timerRow), listOf("Timer: Off", "3 s", "5 s", "10 s"), timerIdx) {
            timerIdx = it; updateTimerUi()
        }
        val gridIcon = v.findViewById<ImageView>(R.id.tileGridIcon)
        val gridLbl = v.findViewById<TextView>(R.id.tileGridLbl)
        val burstIcon = v.findViewById<ImageView>(R.id.tileBurstIcon)
        val burstLbl = v.findViewById<TextView>(R.id.tileBurstLbl)
        fun refreshTiles() {
            tint(gridIcon, gridLbl, gridOn); gridLbl.text = if (gridOn) "Grid: On" else "Grid: Off"
            tint(burstIcon, burstLbl, burstIdx != 0); burstLbl.text = "Burst: ${burstOpts[burstIdx]}x"
        }
        refreshTiles()
        v.findViewById<View>(R.id.tileGrid).setOnClickListener { gridOn = !gridOn; updateGridUi(); refreshTiles() }
        v.findViewById<View>(R.id.tileBurst).setOnClickListener { cycleBurst(); refreshTiles() }
        v.findViewById<View>(R.id.tileSettings).setOnClickListener {
            dlg.dismiss(); startActivity(Intent(this, SettingsActivity::class.java))
        }
        dlg.show()
    }

    // ================================================================ aspect ratio
    private fun targetAspect(): Float {
        if (videoMode) return 9f / 16f
        return when (aspectIdx) {
            0 -> 1f
            1 -> 3f / 4f
            2 -> 9f / 16f
            else -> {
                val bw = previewBox.width.toFloat(); val bh = previewBox.height.toFloat()
                if (bw > 0 && bh > 0) bw / bh else 9f / 16f
            }
        }
    }

    private fun applyAspect() {
        val bw = previewBox.width; val bh = previewBox.height
        if (bw == 0 || bh == 0) return
        val a = targetAspect()
        var w = bw; var h = (bw / a).toInt()
        if (h > bh) { h = bh; w = (bh * a).toInt() }
        preview.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        grid.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
    }

    // ================================================================ permissions
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
        try {
            fused = LocationServices.getFusedLocationProviderClient(this)
            fused.requestLocationUpdates(req, locCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {}
    }

    // ================================================================ gestures / zoom / focus
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(curZoom * d.scaleFactor, false)
                showZoomPopup()
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

    private fun setZoom(r: Float, popup: Boolean) {
        val z = r.coerceIn(minZoom, max(minZoom, maxZoom))
        curZoom = z
        try { camera?.cameraControl?.setZoomRatio(z) } catch (_: Exception) {}
        updateZoomUi()
        if (popup) showZoomPopup()
    }

    private fun showZoomPopup() {
        zoomText.text = String.format(Locale.US, "%.1fx", curZoom)
        zoomText.visibility = View.VISIBLE
        zoomText.removeCallbacks(hideZoom)
        zoomText.postDelayed(hideZoom, 1000)
    }

    private val hideZoom = Runnable { zoomText.visibility = View.INVISIBLE }

    private fun updateZoomUi() {
        setChipOn(z1, abs(curZoom - 1f) < 0.15f)
        setChipOn(z2, abs(curZoom - 2f) < 0.15f)
        setChipOn(z4, abs(curZoom - 4f) < 0.15f)
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        try {
            val pt = preview.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS).build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (_: Exception) {}
        focusRing.x = preview.left + x - focusRing.width / 2f
        focusRing.y = preview.top + y - focusRing.height / 2f
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

    // ================================================================ camera binding
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private val awbCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
        ) {
            awbResult = result.get(CaptureResult.CONTROL_AWB_MODE) ?: -1
        }
    }

    private fun bindUseCases() {
        val p = provider ?: return
        applyAspect()
        val wide = videoMode || targetAspect() < 0.66f      // 16:9 capture for tall/Full/16:9
        val ratio = if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                    else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val pb = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
        Camera2Interop.Extender(pb).setSessionCaptureCallback(awbCallback)
        val pv = pb.build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val sel = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        p.unbindAll()
        overlayEffect?.close(); overlayEffect = null
        try {
            if (videoMode) {
                val rec = Recorder.Builder().setQualitySelector(
                    QualitySelector.from(videoVals[videoIdx], FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                ).build()
                val vc = VideoCapture.withOutput(rec)
                videoCapture = vc
                var bound: Camera? = null
                if (stampVideo) {
                    val fx = makeOverlay()
                    try {
                        val group = UseCaseGroup.Builder().addUseCase(pv).addUseCase(vc).addEffect(fx).build()
                        bound = p.bindToLifecycle(this, sel, group)
                        overlayEffect = fx
                    } catch (e: Exception) {
                        fx.close()
                        p.unbindAll()
                        toast("Is phone par video stamp nahi chal raha")
                    }
                }
                camera = bound ?: p.bindToLifecycle(this, sel, pv, vc)
            } else {
                val sz = if (wide) sizes169[photoSizeIdx] else sizes43[photoSizeIdx]
                val rs = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(ResolutionStrategy(sz, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val ic = ImageCapture.Builder()
                    .setCaptureMode(
                        if (photoSizeIdx == 0) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                        else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    )
                    .setResolutionSelector(rs).build()
                imageCapture = ic
                camera = p.bindToLifecycle(this, sel, pv, ic)
            }
            val cam = camera!!
            videoRot = try { cam.cameraInfo.getSensorRotationDegrees(Surface.ROTATION_0) } catch (_: Exception) { 90 }
            zoomLive?.removeObserver(zoomObs)
            zoomLive = cam.cameraInfo.zoomState
            zoomLive?.observe(this, zoomObs)
            cam.cameraInfo.zoomState.value?.let { minZoom = it.minZoomRatio; maxZoom = it.maxZoomRatio }
            curZoom = 1f
            updateZoomUi()
            if (!cam.cameraInfo.hasFlashUnit()) flashState = 0
            cam2Applied = false
            awbResult = -1
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

    // ================================================================ modes
    private fun setMode(m: Int) {
        if (recording != null || m == uiMode) return
        val wasVideo = videoMode
        val wasPro = uiMode == 2
        uiMode = m
        videoMode = (m == 1)
        proPanel.visibility = if (m == 2) View.VISIBLE else View.GONE
        if (videoMode && flashState == 1) flashState = 0
        captureBtn.setBackgroundResource(if (videoMode) R.drawable.capture_video else R.drawable.capture_button)
        updateModeTabs()
        if (wasPro && m != 2) resetPro()
        if (wasVideo != videoMode) bindUseCases() else applyFlash()
    }

    private fun updateModeTabs() {
        photoTab.setTextColor(if (uiMode == 0) yellow else Color.WHITE)
        videoTab.setTextColor(if (uiMode == 1) yellow else Color.WHITE)
        proModeTab.setTextColor(if (uiMode == 2) yellow else Color.WHITE)
    }

    private fun resetPro() {
        isoIdx = 0; wbIdx = 0; refreshIso(); refreshWb()
        curEv = 0f
        camera?.cameraControl?.setExposureCompensationIndex(0)
        evRange?.let { evSeek.progress = -it.lower }
        evLabel.text = "Exposure: 0.0"
        updateProTabs()
        applyCam2()
    }

    // ================================================================ flash / torch
    private fun cycleFlash() {
        if (camera?.cameraInfo?.hasFlashUnit() != true) { toast("Is camera mein flash/torch nahi hai"); return }
        flashState = if (videoMode) (if (flashState == 2) 0 else 2) else (flashState + 1) % 3
        applyFlash()
    }

    private fun applyFlash() {
        val cam = camera
        if (cam != null && cam.cameraInfo.hasFlashUnit()) {
            val want = flashState == 2
            val f = cam.cameraControl.enableTorch(want)
            f.addListener({
                try { if (!f.isCancelled) f.get() }
                catch (_: Exception) { if (want) toast("Torch on nahi ho saki") }
            }, ContextCompat.getMainExecutor(this))
        }
        imageCapture?.flashMode =
            if (flashState == 1) ImageCapture.FLASH_MODE_AUTO else ImageCapture.FLASH_MODE_OFF
        iconFlash.setImageResource(
            when (flashState) { 1 -> R.drawable.ic_flash_auto; 2 -> R.drawable.ic_flash_on; else -> R.drawable.ic_flash_off }
        )
        lblFlash.text = listOf("Off", "Auto", "On")[flashState]
        tint(iconFlash, lblFlash, flashState != 0)
    }

    // ================================================================ pro panel (WB / ISO / EV)
    private fun setupProPanel() {
        refreshIso = buildChips(isoRow, isoVals.map { if (it == 0) "Auto" else it.toString() }, { isoIdx }) {
            isoIdx = it; updateProTabs(); applyCam2()
        }
        refreshWb = buildChips(wbRow, wbNames, { wbIdx }) {
            wbIdx = it; updateProTabs(); applyCam2(); checkWbLater()
        }
        evSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val r = evRange ?: return
                if (!fromUser) return
                val idx = r.lower + p
                camera?.cameraControl?.setExposureCompensationIndex(idx)
                curEv = idx * evStep
                evLabel.text = String.format(Locale.US, "Exposure: %+.1f", curEv)
                updateProTabs()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // if the phone's camera ignores hardware WB, tell the user it is applied in software on photos
    private fun checkWbLater() {
        if (wbIdx == 0) return
        handler.postDelayed({
            if (wbIdx != 0 && awbResult != wbModes[wbIdx])
                toast("WB is phone mein live nahi badalta, photo par software se lagega")
        }, 1500)
    }

    private fun showProTab(i: Int) {
        proTab = i
        wbScroll.visibility = if (i == 0) View.VISIBLE else View.GONE
        isoScroll.visibility = if (i == 1) View.VISIBLE else View.GONE
        evBox.visibility = if (i == 2) View.VISIBLE else View.GONE
        listOf(tabWb, tabIso, tabEv).forEachIndexed { k, t -> t.setTextColor(if (k == i) yellow else Color.WHITE) }
    }

    private fun updateProTabs() {
        tabWb.text = "WB · ${wbNames[wbIdx]}"
        tabIso.text = "ISO · " + (if (isoIdx == 0) "Auto" else isoVals[isoIdx].toString())
        tabEv.text = String.format(Locale.US, "EV · %+.1f", curEv)
    }

    private fun buildChips(row: LinearLayout, labels: List<String>, selected: () -> Int, onPick: (Int) -> Unit): () -> Unit {
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
        val refresh: () -> Unit = { views.forEachIndexed { i, v -> setChipOn(v, i == selected()) } }
        views.forEachIndexed { i, v -> v.setOnClickListener { onPick(i); refresh() } }
        views.forEach { row.addView(it) }
        refresh()
        return refresh
    }

    private fun setupExposure() {
        val st = camera?.cameraInfo?.exposureState
        curEv = 0f
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
        updateProTabs()
    }

    // Camera2 options only when ISO/WB is changed, so default photo/zoom/torch are never touched
    private fun applyCam2() {
        val cam = camera ?: return
        val isDefault = isoIdx == 0 && wbIdx == 0
        try {
            val ctl = Camera2CameraControl.from(cam.cameraControl)
            if (isDefault) {
                if (cam2Applied) { ctl.clearCaptureRequestOptions(); cam2Applied = false }
                return
            }
            val b = CaptureRequestOptions.Builder()
            b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wbModes[wbIdx])
            if (isoIdx > 0) {
                val range = Camera2CameraInfo.from(cam.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val iso = isoVals[isoIdx].coerceIn(range?.lower ?: 100, range?.upper ?: 3200)
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, 16_666_666L)
            }
            ctl.setCaptureRequestOptions(b.build())
            cam2Applied = true
        } catch (e: Exception) {
            toast("ISO/WB hardware se nahi badal paya")
        }
    }

    // ================================================================ shutter, timer, burst
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
            when (volAction) {
                0 -> { if (event.repeatCount == 0) onShutter(); return true }
                1 -> {
                    val step = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.5f else -0.5f
                    setZoom(curZoom + step, true); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && volAction != 2) return true
        return super.onKeyUp(keyCode, event)
    }

    // ================================================================ video (with live stamp)
    private fun makeOverlay(): OverlayEffect {
        val fx = OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, fxHandler, androidx.core.util.Consumer<Throwable> { })
        fx.setOnDrawListener { frame ->
            val c = frame.overlayCanvas
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val loc = lastLocation
            if (loc != null) {
                val bw = c.width
                val bh = c.height
                val sec = System.currentTimeMillis() / 1000
                val key = "$sec|$bw|$bh|${loc.latitude}|${loc.longitude}|$addrFull|${comp != null}|$videoRot"
                if (key != stampKey || stampBmp == null) {
                    val rot = videoRot
                    val uw = if (rot % 180 == 0) bw else bh
                    val uh = if (rot % 180 == 0) bh else bw
                    val up = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888)
                    drawStampPanel(Canvas(up), uw.toFloat(), uh.toFloat(), loc)
                    val m = Matrix().apply { postRotate(-rot.toFloat()) }
                    stampBmp = Bitmap.createBitmap(up, 0, 0, uw, uh, m, true)
                    stampKey = key
                }
                stampBmp?.let { c.drawBitmap(it, 0f, 0f, null) }
            }
            true
        }
        return fx
    }

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
        if (recAudio && has(Manifest.permission.RECORD_AUDIO)) pr = pr.withAudioEnabled()
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
        proModeTab.alpha = if (on) 0.4f else 1f
    }

    // ================================================================ address + map
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

    // ================================================================ photo
    private fun takePhoto(onDone: () -> Unit) {
        val ic = imageCapture
        val loc = lastLocation
        if (ic == null) { burstLeft = 0; onDone(); return }
        if (loc == null && stampPhoto) {
            toast("GPS location abhi nahi mili, thoda ruko")
            burstLeft = 0; onDone(); return
        }
        if (saving) { onDone(); return }
        saving = true
        val aspect = targetAspect()
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        val wbSoft = if (wbIdx != 0 && awbResult != wbModes[wbIdx]) wbIdx else 0
        if (shutterSound) try { sound.play(MediaActionSound.SHUTTER_CLICK) } catch (_: Exception) {}
        ic.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = image.toBitmap()
                val rot = image.imageInfo.rotationDegrees
                image.close()
                io.execute {
                    try {
                        var b = bmp
                        if (rot != 0) {
                            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height,
                                Matrix().apply { postRotate(rot.toFloat()) }, true)
                            if (r !== b) b.recycle(); b = r
                        }
                        if (front && mirrorSelfie) {
                            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height,
                                Matrix().apply { postScale(-1f, 1f, b.width / 2f, b.height / 2f) }, true)
                            if (r !== b) b.recycle(); b = r
                        }
                        b = cropAspect(b, aspect)
                        if (wbSoft != 0) b = softWb(b, wbSoft)
                        if (!b.isMutable) { val m = b.copy(Bitmap.Config.ARGB_8888, true); b.recycle(); b = m }
                        if (stampPhoto && loc != null) drawStampPanel(Canvas(b), b.width.toFloat(), b.height.toFloat(), loc)
                        val uri = save(b)
                        if (uri != null) {
                            if (locTags && loc != null) writeExif(uri, loc)
                            showThumb(uri)
                            if (burstLeft == 0) runOnUiThread { toast("Save ho gayi: Pictures/MS Camera") }
                        }
                        b.recycle()
                    } catch (e: Throwable) {
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

    private fun cropAspect(src: Bitmap, a: Float): Bitmap {
        val cur = src.width.toFloat() / src.height
        if (abs(cur - a) < 0.01f) return src
        val r = if (cur > a) {
            val nw = (src.height * a).toInt()
            Bitmap.createBitmap(src, (src.width - nw) / 2, 0, nw, src.height)
        } else {
            val nh = (src.width / a).toInt()
            Bitmap.createBitmap(src, 0, (src.height - nh) / 2, src.width, nh)
        }
        if (r !== src) src.recycle()
        return r
    }

    private fun softWb(src: Bitmap, idx: Int): Bitmap {
        val g = wbGains[idx]
        val cm = ColorMatrix().apply { setScale(g[0], g[1], g[2], 1f) }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        src.recycle()
        return out
    }

    // draws the location panel (map + address + lat/long + date) at the bottom of a canvas w x h
    private fun drawStampPanel(c: Canvas, w: Float, h: Float, loc: Location) {
        val margin = w * 0.03f
        val pad = w * 0.02f
        val gap = w * 0.008f
        val mapSize = w * 0.26f

        val map = if (stampMap) comp else null
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
        val dateStr = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm:ss a", Locale.getDefault()).format(now) + " GMT " + offStr

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

    private fun writeExif(uri: Uri, loc: Location) {
        try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val ex = ExifInterface(pfd.fileDescriptor)
                ex.setGpsInfo(loc)
                ex.saveAttributes()
            }
        } catch (_: Exception) {}
    }

    // ================================================================ last photo thumbnail
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
        overlayEffect?.close()
        fxThread.quitSafely()
        io.shutdown()
        sound.release()
    }
}
