package com.sohanreddy.sevak.screenshare

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.sohanreddy.sevak.ui.main.AssistantState
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class VoiceBubbleOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val closeOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 5, 10, 24)
    }
    private val closeButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val closeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 8, 16, 38)
        textAlign = Paint.Align.CENTER
        textSize = dp(18f)
        isFakeBoldText = true
    }

    private var currentState: AssistantState = AssistantState.IDLE
    private var targetAmplitude = 0f
    private var smoothedAmplitude = 0f
    private var phase = 0f
    private var showCloseButton = false

    private val frameTicker = object : Runnable {
        override fun run() {
            phase += if (isWaveActive()) 0.09f else 0.025f
            smoothedAmplitude += (targetAmplitude - smoothedAmplitude) * 0.12f
            invalidate()
            postOnAnimation(this)
        }
    }

    fun updateAudioState(state: AssistantState, amplitude: Float) {
        currentState = state
        targetAmplitude = amplitude.coerceIn(0f, 1f)
    }

    fun toggleCloseButton() {
        showCloseButton = !showCloseButton
        invalidate()
    }

    fun hideCloseButton() {
        if (!showCloseButton) return
        showCloseButton = false
        invalidate()
    }

    fun isCloseButtonVisible(): Boolean = showCloseButton

    fun isPointOnCloseButton(x: Float, y: Float): Boolean {
        if (!showCloseButton) return false
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.19f
        val dx = x - cx
        val dy = y - cy
        return (dx * dx + dy * dy) <= radius * radius
    }

    private fun isWaveActive(): Boolean {
        return currentState == AssistantState.LISTENING || currentState == AssistantState.SPEAKING
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(frameTicker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameTicker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f

        bgPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(Color.parseColor("#1D5CBF"), Color.parseColor("#091B3F")),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, bgPaint)

        glowPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(Color.argb(90, 110, 190, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, glowPaint)

        glassPaint.shader = LinearGradient(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius,
            Color.argb(38, 255, 255, 255),
            Color.argb(14, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 0.96f, glassPaint)

        val sheenRect = RectF(
            cx - radius * 0.72f,
            cy - radius * 0.86f,
            cx + radius * 0.72f,
            cy - radius * 0.06f
        )
        sheenPaint.shader = LinearGradient(
            sheenRect.left,
            sheenRect.top,
            sheenRect.right,
            sheenRect.bottom,
            Color.argb(110, 255, 255, 255),
            Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(sheenRect, sheenPaint)

        rimPaint.shader = LinearGradient(
            0f,
            0f,
            w,
            h,
            Color.argb(155, 225, 242, 255),
            Color.argb(72, 225, 242, 255),
            Shader.TileMode.CLAMP
        )
        rimPaint.strokeWidth = dp(1.5f)
        canvas.drawOval(RectF(dp(0.9f), dp(0.9f), w - dp(0.9f), h - dp(0.9f)), rimPaint)
        rimPaint.shader = null

        val clipPath = Path().apply { addCircle(cx, cy, radius * 0.98f, Path.Direction.CW) }
        val save = canvas.save()
        canvas.clipPath(clipPath)
        drawWaveLines(canvas, w, h)
        canvas.restoreToCount(save)

        if (showCloseButton) {
            canvas.drawCircle(cx, cy, radius * 0.98f, closeOverlayPaint)
            val closeRadius = radius * 0.38f
            canvas.drawCircle(cx, cy, closeRadius, closeButtonPaint)
            val textBaseline = cy - (closeTextPaint.descent() + closeTextPaint.ascent()) / 2f
            canvas.drawText("X", cx, textBaseline, closeTextPaint)
        }
    }

    private fun drawWaveLines(canvas: Canvas, w: Float, h: Float) {
        val lines = listOf(
            WaveLine(0.35f, 1.8f, 0f, Color.argb(120, 255, 255, 255), dp(1.4f)),
            WaveLine(0.62f, 1.4f, 0.8f, Color.argb(190, 90, 166, 255), dp(1.8f)),
            WaveLine(1.0f, 1.1f, 1.6f, Color.argb(225, 140, 202, 255), dp(2.2f)),
            WaveLine(0.62f, 1.4f, 2.4f, Color.argb(190, 90, 166, 255), dp(1.8f)),
            WaveLine(0.35f, 1.8f, 3.2f, Color.argb(120, 255, 255, 255), dp(1.4f))
        )

        val effectiveAmp = if (isWaveActive()) smoothedAmplitude * 0.58f else 0.05f
        val dynamicPhase = if (isWaveActive()) phase else phase * 0.35f

        for (line in lines) {
            linePaint.color = line.color
            linePaint.strokeWidth = line.stroke
            val path = Path()
            val steps = 70
            for (i in 0..steps) {
                val x = (i / steps.toFloat()) * w
                val norm = x / w
                val envelope = sin(norm * PI).toFloat()
                val wave = sin((norm * PI * 2.0 * line.frequency + dynamicPhase + line.offset)).toFloat()
                val y = h / 2f + wave * effectiveAmp * h * line.ampScale * envelope
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
        }

        // Subtle ring to maintain visibility on bright backgrounds.
        linePaint.shader = LinearGradient(0f, 0f, w, h, Color.argb(130, 150, 210, 255), Color.argb(40, 150, 210, 255), Shader.TileMode.CLAMP)
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = dp(1.1f)
        canvas.drawOval(RectF(dp(1f), dp(1f), w - dp(1f), h - dp(1f)), linePaint)
        linePaint.shader = null
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private data class WaveLine(
        val ampScale: Float,
        val frequency: Float,
        val offset: Float,
        val color: Int,
        val stroke: Float
    )
}
