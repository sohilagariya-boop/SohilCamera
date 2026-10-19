package com.sohil.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var locText: TextView
    private lateinit var thumb: ImageView
    private var imageCapture: ImageCapture? = null
    private lateinit var fused: FusedLocationProviderClient
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var lastLocation: Location? = null
    @Volatile private var addrTitle: String? = null
    @Volatile private var addrFull: String? = null
    @Volatile private var geoFor: Location? = null
    @Volatile private var comp: Bitmap? = null      // 512x512 map (2x2 tiles)
    @Volatile private var compKey: String? = null
    @Volatile private var compPx = 256f
    @Volatile private var compPy = 256f
    @Volatile private var busy = false
    private var lastUri: Uri? = null
    private var saving = false
    private var lastMime = "image/jpeg"

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var videoMode = false
    private var torchOn = false
    private lateinit var torchBtn: TextView
    private lateinit var flipBtn: TextView
    private lateinit var recTimer: TextView
    private lateinit var photoTab: TextView
    private lateinit var videoTab: TextView
    private lateinit var captureBtn: View

    private val locCallback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            lastLocation = l
            updateLocText()
            refreshExtras(l)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        locText = findViewById(R.id.locText)
        thumb = findViewById(R.id.thumb)
        fused = LocationServices.getFusedLocationProviderClient(this)

        torchBtn = findViewById(R.id.torchBtn)
        flipBtn = findViewById(R.id.flipBtn)
        recTimer = findViewById(R.id.recTimer)
        photoTab = findViewById(R.id.photoTab)
        videoTab = findViewById(R.id.videoTab)
        captureBtn = findViewById(R.id.captureBtn)

        captureBtn.setOnClickListener { v ->
            v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }.start()
            if (videoMode) toggleRecording() else takePhoto()
        }
        torchBtn.setOnClickListener { toggleTorch() }
        flipBtn.setOnClickListener { flipCamera() }
        photoTab.setOnClickListener { setMode(false) }
        videoTab.setOnClickListener { setMode(true) }
        thumb.setOnClickListener {
            val u = lastUri ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, lastMime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, "Gallery nahi khuli", Toast.LENGTH_SHORT).show()
            }
        }

        if (allGranted()) startAll() else ActivityCompat.requestPermissions(this, askPerms(), 10)
    }

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

    // mic optional: video still records (without sound) if denied
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
        try { fused.requestLocationUpdates(req, locCallback, Looper.getMainLooper()) }
        catch (_: SecurityException) {}
    }

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
                    QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                ).build()
                val vc = VideoCapture.withOutput(rec)
                videoCapture = vc
                p.bindToLifecycle(this, sel, pv, vc)
            } else {
                val ic = ImageCapture.Builder().build()
                imageCapture = ic
                p.bindToLifecycle(this, sel, pv, ic)
            }
            if (torchOn && camera?.cameraInfo?.hasFlashUnit() == true) {
                camera?.cameraControl?.enableTorch(true)
            } else torchOn = false
            updateTorchUi()
        } catch (e: Exception) {
            Toast.makeText(this, "Camera nahi khuli: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateTorchUi() {
        torchBtn.setBackgroundResource(if (torchOn) R.drawable.round_btn_on else R.drawable.round_btn)
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Is camera mein torch nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        updateTorchUi()
    }

    private fun flipCamera() {
        if (recording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) torchOn = false
        bindUseCases()
    }

    private fun setMode(video: Boolean) {
        if (recording != null || video == videoMode) return
        videoMode = video
        photoTab.setTextColor(if (video) Color.WHITE else Color.parseColor("#FFC107"))
        videoTab.setTextColor(if (video) Color.parseColor("#FFC107") else Color.WHITE)
        captureBtn.setBackgroundResource(if (video) R.drawable.capture_video else R.drawable.capture_button)
        bindUseCases()
    }

    // ---------- video ----------
    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        val running = recording
        if (running != null) { running.stop(); return }
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
                        Toast.makeText(this, "Video save ho gayi: Movies/MS Camera", Toast.LENGTH_SHORT).show()
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

    // ---------- address + map ----------
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
            conn.setRequestProperty("User-Agent", "SohilCamera/1.0 (personal app)")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val b = conn.inputStream.use { BitmapFactory.decodeStream(it) } ?: return
            c.drawBitmap(b, dx * 256f, dy * 256f, null)
        }
        comp = out
        compKey = key
    }

    // ---------- capture ----------
    private fun takePhoto() {
        val ic = imageCapture ?: return
        val loc = lastLocation
        if (loc == null) {
            Toast.makeText(this, "GPS location abhi nahi mili, thoda ruko", Toast.LENGTH_SHORT).show()
            return
        }
        if (saving) return
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
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Save ho gayi: Pictures/MS Camera", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } finally { saving = false }
                }
            }
            override fun onError(e: ImageCaptureException) {
                saving = false
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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
            // pin at exact position
            val pxPin = dst.left + (px - sx) / 256f * mapSize
            val pyPin = dst.top + (py - sy) / 256f * mapSize
            val r = mapSize * 0.06f
            c.drawCircle(pxPin, pyPin, r * 1.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawCircle(pxPin, pyPin, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 30, 30) })
            // attribution
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
        val name = "MS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MS Camera")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return uri
    }

    // ---------- last photo thumbnail ----------
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
        fused.removeLocationUpdates(locCallback)
        io.shutdown()
    }
}
