package com.sohil.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var locText: TextView
    private var imageCapture: ImageCapture? = null
    private var lastLocation: Location? = null
    private lateinit var fused: FusedLocationProviderClient

    private val locCallback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            lastLocation = r.lastLocation
            lastLocation?.let {
                locText.text = "Lat: %.6f\nLon: %.6f".format(it.latitude, it.longitude)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        locText = findViewById(R.id.locText)
        fused = LocationServices.getFusedLocationProviderClient(this)
        findViewById<Button>(R.id.captureBtn).setOnClickListener { takePhoto() }

        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (perms.all { granted(it) }) startAll()
        else ActivityCompat.requestPermissions(this, perms, 10)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (granted(Manifest.permission.CAMERA) && granted(Manifest.permission.ACCESS_FINE_LOCATION)) startAll()
        else Toast.makeText(this, "Camera aur Location permission zaroori hai", Toast.LENGTH_LONG).show()
    }

    private fun startAll() { startCamera(); startLocation() }

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

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val loc = lastLocation
        if (loc == null) {
            Toast.makeText(this, "GPS location abhi nahi mili, thoda ruko", Toast.LENGTH_SHORT).show()
            return
        }
        ic.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = image.toBitmap()
                val rot = image.imageInfo.rotationDegrees
                image.close()
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                save(stamp(rotated, loc))
            }
            override fun onError(e: ImageCaptureException) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun stamp(src: Bitmap, loc: Location): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val time = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val lines = listOf("Lat: %.6f".format(loc.latitude), "Lon: %.6f".format(loc.longitude), time)
        val size = out.width / 22f
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size }
        val bg = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        val pad = size * 0.5f
        val h = lines.size * size * 1.25f + pad * 2
        c.drawRect(0f, out.height - h, out.width.toFloat(), out.height.toFloat(), bg)
        lines.forEachIndexed { i, s ->
            c.drawText(s, pad, out.height - h + pad + size * (i * 1.25f + 1f), text)
        }
        return out
    }

    private fun save(bmp: Bitmap) {
        val name = "SOHIL_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sohil")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        if (uri == null) { Toast.makeText(this, "Save nahi hua", Toast.LENGTH_SHORT).show(); return }
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        Toast.makeText(this, "Gallery mein save: Pictures/Sohil", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        fused.removeLocationUpdates(locCallback)
    }
}
