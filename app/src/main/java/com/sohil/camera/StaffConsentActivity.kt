package com.sohil.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * This screen is shown before any background location sharing ever starts.
 * It explains in plain language what will be tracked, how often, and who sees it,
 * and the employee must explicitly tap "I Agree" before sharing turns on.
 * They can switch it off at any time from Settings or from the notification.
 */
class StaffConsentActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences(LocationTrackingService.PREFS, MODE_PRIVATE) }
    private lateinit var nameInput: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Staff Location Sharing"
            textSize = 24f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            textSize = 15f; setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, dp(18), 0, 0)
            text = "Ye on karne se:\n\n" +
                "• Aapki location office/manager ke saath share hogi, din bhar, har 2-5 minute mein.\n" +
                "• Jab tak ye chalu hai, phone ki notification bar mein hamesha ek notification dikhegi " +
                "(\"Location sharing on\"), taaki aapko hamesha pata rahe.\n" +
                "• Aap isse kabhi bhi band kar sakte hain — Settings se, ya seedha us notification se " +
                "\"Stop sharing\" dabakar.\n" +
                "• Jab band ho, office ko bhi turant dikh jayega ki sharing band hai.\n\n" +
                "Ye tabhi on karein jab aapki company ne aapko ye batakar diya ho."
        })
        nameInput = EditText(this).apply {
            hint = "Apna naam likhein"
            setHintTextColor(Color.parseColor("#888888"))
            setTextColor(Color.WHITE)
            setText(LocationTrackingService.employeeName(this@StaffConsentActivity))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        }
        root.addView(nameInput)

        val agree = Button(this).apply {
            text = "Main samajh gaya/gayi, Sharing Start karein"
            setBackgroundColor(Color.parseColor("#FFC107"))
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(28) }
            setOnClickListener { onAgree() }
        }
        root.addView(agree)

        root.addView(TextView(this).apply {
            text = "Nahi karna"
            textSize = 14f; setTextColor(Color.parseColor("#9A9A9A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            setOnClickListener { finish() }
        })
    }

    private fun onAgree() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) { Toast.makeText(this, "Naam likhna zaroori hai", Toast.LENGTH_SHORT).show(); return }
        prefs.edit()
            .putString("employee_name", name)
            .putBoolean("consent_given", true)
            .putBoolean("sharing_on", true)
            .apply()
        requestPerms()
    }

    private fun requestPerms() {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 77)
        } else {
            requestBackgroundIfNeeded()
        }
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        when (c) {
            77 -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                ) requestBackgroundIfNeeded()
                else {
                    Toast.makeText(this, "Location permission ke bina sharing shuru nahi ho sakti", Toast.LENGTH_LONG).show()
                    prefs.edit().putBoolean("sharing_on", false).apply()
                }
            }
            78 -> finishStart() // granted or denied, either way start (foreground-only if denied)
        }
    }

    private fun requestBackgroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "Agle screen mein 'Allow all the time' chunein, taaki app band hone par bhi location share ho",
                Toast.LENGTH_LONG
            ).show()
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 78
            )
        } else {
            finishStart()
        }
    }

    private fun finishStart() {
        LocationTrackingService.start(this)
        Toast.makeText(this, "Location sharing shuru ho gayi", Toast.LENGTH_SHORT).show()
        finish()
    }
}
