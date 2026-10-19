package com.sohil.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridOverlay @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val p = Paint().apply { color = Color.argb(150, 255, 255, 255); strokeWidth = 2f }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            c.drawLine(w * i / 3, 0f, w * i / 3, h, p)
            c.drawLine(0f, h * i / 3, w, h * i / 3, p)
        }
    }
}
