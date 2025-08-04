package com.example.phonematetry
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val path = Path()
    private var amplitude = 0f

    fun updateAmplitude(newAmplitude: Float) {
        amplitude = newAmplitude
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        path.reset()
        val width = width.toFloat()
        val height = height.toFloat()
        val midHeight = height / 2

        path.moveTo(0f, midHeight)

        for (x in 0..width.toInt()) {
            val y = (sin(x * 0.05f) * amplitude * midHeight + midHeight).toFloat()
            path.lineTo(x.toFloat(), y)
        }

        canvas.drawPath(path, paint)
    }
}