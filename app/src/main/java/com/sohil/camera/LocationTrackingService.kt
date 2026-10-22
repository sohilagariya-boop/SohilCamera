package com.sohil.camera

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foreground service that shares this device's location with the office, only while
 * - the employee has agreed on the StaffConsentActivity screen, AND
 * - Staff Location Sharing is switched ON in Settings.
 *
 * A notification stays visible the whole time this is running (Android requires this,
 * and it is also how the employee can always see that sharing is active and stop it).
 */
class LocationTrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "ms_staff_location"
        const val NOTIF_ID = 5501
        const val ACTION_STOP = "com.sohil.camera.STOP_SHARING"
        const val PREFS = "ms_staff"

        fun isEnabled(ctx: Context) =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("sharing_on", false)

        fun employeeName(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getString("employee_name", "") ?: ""

        fun employeeId(ctx: Context): String {
            val p = ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
            var id = p.getString("employee_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString().take(8)
                p.edit().putString("employee_id", id).apply()
            }
            return id
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, LocationTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LocationTrackingService::class.java))
        }
    }

    private lateinit var fused: FusedLocationProviderClient
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var lastSent = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val loc = r.lastLocation ?: return
            push(loc)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("sharing_on", false).apply()
            markPaused()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        beginUpdates()
        return START_STICKY
    }

    private fun beginUpdates() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) { stopSelf(); return }
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5 * 60 * 1000L)
            .setMinUpdateIntervalMillis(2 * 60 * 1000L)
            .build()
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
            fused.lastLocation.addOnSuccessListener { it?.let { l -> push(l) } }
        } catch (_: SecurityException) { stopSelf() }
    }

    private fun push(loc: Location) {
        val now = System.currentTimeMillis()
        if (now - lastSent < 60_000) return
        lastSent = now
        val id = employeeId(this)
        val data = hashMapOf(
            "name" to employeeName(this),
            "lat" to loc.latitude,
            "lng" to loc.longitude,
            "accuracy" to loc.accuracy,
            "sharing" to true,
            "updatedAt" to now,
            "updatedAtText" to SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault()).format(Date(now))
        )
        db.collection("staff_locations").document(id).set(data, SetOptions.merge())
    }

    private fun markPaused() {
        val id = employeeId(this)
        db.collection("staff_locations").document(id)
            .set(hashMapOf("sharing" to false, "pausedAt" to System.currentTimeMillis()), SetOptions.merge())
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LocationTrackingService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, SettingsActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MS Camera · Location sharing on")
            .setContentText("Aapki location office ke saath share ho rahi hai")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "Stop sharing", stopPending)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Staff location sharing", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
