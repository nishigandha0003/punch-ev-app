package com.punchev.dashboard.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class CircularGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress: Float = 0f
    private val strokeWidthVal = 32f
    private val startAngle = 135f
    private val maxSweepAngle = 270f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthVal
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#162032")
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthVal
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthVal + 12f
        strokeCap = Paint.Cap.ROUND
        alpha = 50
    }

    private val arcBounds = RectF()
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = strokeWidthVal + 16f
        arcBounds.set(pad, pad, w.toFloat() - pad, h.toFloat() - pad)

        // Gradient for progress arc: Cyan to Emerald Green
        val shader = LinearGradient(
            0f, h.toFloat(), w.toFloat(), 0f,
            intArrayOf(
                Color.parseColor("#00F2FE"),
                Color.parseColor("#00E676")
            ),
            null,
            Shader.TileMode.CLAMP
        )
        progressPaint.shader = shader
        glowPaint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background track
        canvas.drawArc(arcBounds, startAngle, maxSweepAngle, false, trackPaint)

        // Draw active progress
        val sweep = (progress / 100f) * maxSweepAngle
        if (sweep > 0f) {
            // Glow layer
            canvas.drawArc(arcBounds, startAngle, sweep, false, glowPaint)
            // Sharp foreground arc
            canvas.drawArc(arcBounds, startAngle, sweep, false, progressPaint)
        }
    }

    fun setProgressSmooth(targetProgress: Float) {
        val clamped = targetProgress.coerceIn(0f, 100f)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, clamped).apply {
            duration = 750
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                progress = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}

