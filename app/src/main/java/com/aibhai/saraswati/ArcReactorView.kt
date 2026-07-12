package com.aibhai.saraswati

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator

class ArcReactorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var state = "idle"
    private var rotation1 = 0f
    private var rotation2 = 0f
    private var rotation3 = 0f
    private var glowAlpha = 180
    private var glowGrowing = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val colors = mapOf(
        "idle"      to Triple(0xFF00D4FF.toInt(), 0xFF0055BB.toInt(), 0x2200D4FF.toInt()),
        "listening" to Triple(0xFFFF3A3A.toInt(), 0xFFAA0000.toInt(), 0x33FF3A3A.toInt()),
        "thinking"  to Triple(0xFFFFAA00.toInt(), 0xFFBB6600.toInt(), 0x33FFAA00.toInt()),
        "speaking"  to Triple(0xFF00FF88.toInt(), 0xFF00884D.toInt(), 0x3300FF88.toInt())
    )

    private val animators = mutableListOf<ValueAnimator>()

    init {
        startAnimations()
    }

    private fun startAnimations() {
        // Ring rotation 1 (clockwise)
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 4000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { rotation1 = it.animatedValue as Float; invalidate() }
            start()
            animators.add(this)
        }
        // Ring rotation 2 (counter-clockwise)
        ValueAnimator.ofFloat(360f, 0f).apply {
            duration = 6000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { rotation2 = it.animatedValue as Float }
            start()
            animators.add(this)
        }
        // Ring rotation 3 (clockwise, slower)
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 9000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { rotation3 = it.animatedValue as Float }
            start()
            animators.add(this)
        }
        // Glow pulse
        ValueAnimator.ofInt(80, 220).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { glowAlpha = it.animatedValue as Int; invalidate() }
            start()
            animators.add(this)
        }
    }

    fun setState(newState: String) {
        state = when (newState) {
            "listening", "thinking", "speaking" -> newState
            else -> "idle"
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - 8f

        val (coreColor, ringColor, glowColor) = colors[state] ?: colors["idle"]!!

        // Glow
        paint.style = Paint.Style.FILL
        paint.color = glowColor
        paint.alpha = if (state == "idle") 60 else glowAlpha
        canvas.drawCircle(cx, cy, radius + 12f, paint)

        // Three spinning rings
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.alpha = 255

        val ringRadii = floatArrayOf(radius, radius * 0.78f, radius * 0.58f)
        val rotations = floatArrayOf(rotation1, rotation2, rotation3)

        for (i in 0..2) {
            paint.color = ringColor
            paint.alpha = (100 + i * 55)
            canvas.save()
            canvas.rotate(rotations[i], cx, cy)
            // Draw dashed ring
            val oval = RectF(cx - ringRadii[i], cy - ringRadii[i], cx + ringRadii[i], cy + ringRadii[i])
            paint.pathEffect = DashPathEffect(floatArrayOf(20f, 12f), 0f)
            canvas.drawArc(oval, 0f, 360f, false, paint)
            paint.pathEffect = null
            canvas.restore()
        }

        // Core glow gradient
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        val coreRadius = radius * 0.35f
        val gradient = RadialGradient(
            cx - coreRadius * 0.3f, cy - coreRadius * 0.3f, coreRadius,
            intArrayOf(Color.WHITE, coreColor, ringColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        canvas.drawCircle(cx, cy, coreRadius, paint)
        paint.shader = null

        // Core inner white dot
        paint.color = Color.WHITE
        paint.alpha = 200
        canvas.drawCircle(cx - coreRadius * 0.25f, cy - coreRadius * 0.25f, coreRadius * 0.25f, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animators.forEach { it.cancel() }
    }
}
