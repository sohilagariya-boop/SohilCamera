package com.sohil.camera

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
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

        findViewById<View>(R.id.captureBtn).setOnClickListener { v ->
            v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }.start()
            takePhoto()
        }
        thumb.setOnClickListener {
            val u = lastUri ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, "Gallery nahi khuli", Toast.LENGTH_SHORT).show()
            }
        }

        if (allGranted()) startAll() else ActivityCompat.requestPermissions(this, neededPerms(), 10)
    }

    private fun neededPerms(): Array<String> {
        val l = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT <= 28) l.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return l.toTypedArray()
    }

    private fun allGranted() = neededPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

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
            val provider = future.get()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            imageCapture = ImageCapture.Builder().build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, imageCapture)
        }, ContextCompat.getMainExecutor(this))
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
                                Toast.makeText(this@MainActivity, "Save ho gayi: Pictures/Sohil", Toast.LENGTH_SHORT).show()
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
        val name = "SOHIL_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sohil")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return uri
    }

    // ---------- last photo thumbnail ----------
    private fun loadLastThumb() {
        io.execute {
            try {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("SOHIL_%"),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cur ->
                    if (cur.moveToFirst()) {
                        showThumb(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0)))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun showThumb(uri: Uri) {
        val bmp = decodeSampled(uri, 200) ?: return
        runOnUiThread { thumb.setImageBitmap(bmp); lastUri = uri }
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
