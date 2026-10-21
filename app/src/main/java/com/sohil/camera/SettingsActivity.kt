package com.sohil.camera

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("ms_camera", MODE_PRIVATE) }
    private lateinit var content: LinearLayout

    private val sizeNames = listOf("High (12 MP)", "Medium (5 MP)", "Low (2 MP)")
    private val jpegNames = listOf("Best (95%)", "Good (85%)", "Small (70%)")
    private val videoNames = listOf("4K (UHD)", "Full HD 1080p", "HD 720p", "SD 480p")
    private val volNames = listOf("Shutter", "Zoom", "Off")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(12))
        }
        header.addView(TextView(this).apply {
            text = "←"; textSize = 28f; setTextColor(Color.WHITE)
            setPadding(0, 0, dp(18), 0)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Settings"; textSize = 24f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(header)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(32))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        build()
    }

    private fun build() {
        content.removeAllViews()

        section("Camera")
        card(
            switchRow("Shutter sound", "shutter_sound", true),
            switchRow("Mirror selfie", "mirror_selfie", true),
            switchRow("Add location tags (GPS in photo info)", "loc_tags", true),
            choiceRow("Volume button action", "vol_action", volNames, 0)
        )

        section("Photo")
        card(
            choiceRow("Photo size", "psize", sizeNames, 0),
            choiceRow("Photo quality", "jpeg", jpegNames, 0),
            switchRow("Location stamp on photo", "stamp_photo", true),
            switchRow("Map in stamp", "stamp_map", true)
        )

        section("Video")
        card(
            choiceRow("Video quality", "video", videoNames, 1),
            switchRow("Location stamp on video", "stamp_video", true),
            switchRow("Record sound", "rec_audio", true)
        )

        section("About")
        card(
            infoRow("About MS Camera", "Version 4") {
                AlertDialog.Builder(this).setTitle("MS Camera")
                    .setMessage("Version 4\nPhoto aur video par GPS, address aur date-time stamp.")
                    .setPositiveButton("OK", null).show()
            },
            infoRow("Privacy", "") {
                AlertDialog.Builder(this).setTitle("Privacy")
                    .setMessage("App camera, microphone aur location use karti hai. Photo aur video sirf aapke phone mein save hote hain.\n\nAddress phone ke Geocoder se aata hai aur map OpenStreetMap se (internet chahiye).")
                    .setPositiveButton("OK", null).show()
            }
        )
    }

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title; textSize = 14f; setTextColor(Color.parseColor("#9A9A9A"))
            setPadding(dp(8), dp(18), 0, dp(8))
        })
    }

    private fun card(vararg rows: View) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.settings_card)
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) c.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#2E2E30"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = dp(18); marginEnd = dp(18)
                }
            })
            c.addView(r)
        }
        content.addView(c, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun rowBase(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun titleView(t: String) = TextView(this).apply {
        text = t; textSize = 17f; setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun switchRow(title: String, key: String, def: Boolean): View {
        val r = rowBase()
        r.addView(titleView(title))
        val sw = SwitchCompat(this)
        sw.isChecked = prefs.getBoolean(key, def)
        val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(st, intArrayOf(Color.WHITE, Color.WHITE))
        sw.trackTintList = ColorStateList(st, intArrayOf(Color.parseColor("#FFC107"), Color.parseColor("#5A5A5C")))
        sw.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        return r
    }

    private fun choiceRow(title: String, key: String, names: List<String>, def: Int): View {
        val r = rowBase()
        r.addView(titleView(title))
        val v = TextView(this).apply {
            textSize = 15f; setTextColor(Color.parseColor("#9A9A9A"))
            text = names[prefs.getInt(key, def).coerceIn(0, names.size - 1)] + "  ›"
        }
        r.addView(v)
        r.setOnClickListener {
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(names.toTypedArray(), prefs.getInt(key, def)) { d, i ->
                    prefs.edit().putInt(key, i).apply()
                    v.text = names[i] + "  ›"
                    d.dismiss()
                }.show()
        }
        return r
    }

    private fun infoRow(title: String, value: String, onClick: () -> Unit): View {
        val r = rowBase()
        r.addView(titleView(title))
        r.addView(TextView(this).apply {
            textSize = 15f; setTextColor(Color.parseColor("#9A9A9A")); text = "$value  ›"
        })
        r.setOnClickListener { onClick() }
        return r
    }
}
