package com.sohanreddy.sevak.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin

/**
 * Canvas-based audio waveform visualisation ported from the HTML reference.
 *
 * @param isActive    Whether the waveform should respond to amplitude (LISTENING / SPEAKING)
 * @param isStatic    Whether the waveform should freeze (PROCESSING)
 * @param amplitude   Normalised audio amplitude [0..1]
 */
@Composable
fun WaveformCanvas(
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    isStatic: Boolean = false,
    amplitude: Float = 0f
) {
    // Smoothed amplitude for fluid motion
    var smoothedAmp by remember { mutableFloatStateOf(0f) }
    // Phase accumulator for animation
    var phase by remember { mutableFloatStateOf(0f) }
    // Timestamp for idle animation
    var frameTime by remember { mutableLongStateOf(0L) }

    // Animate continuously
    LaunchedEffect(isStatic) {
        if (isStatic) return@LaunchedEffect
        while (true) {
            withFrameMillis { millis ->
                frameTime = millis
                if (!isStatic) {
                    phase += if (isActive) 0.04f else 0.012f
                }
                // Smooth amplitude transition
                val target = if (isActive) amplitude else 0f
                smoothedAmp += (target - smoothedAmp) * 0.08f
            }
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (isActive) {
            drawLiveWaveform(w, h, smoothedAmp * 3.5f, phase)
        } else {
            drawIdleWaveform(w, h, frameTime)
        }
    }
}

// ── Waveform line descriptor ────────────────────────────────────────
private data class WaveLine(
    val ampScale: Float,
    val freq: Float,
    val offset: Float,
    val color: Color,
    val width: Float
)

// ── Idle ambient animation ──────────────────────────────────────────
private fun DrawScope.drawIdleWaveform(w: Float, h: Float, frameTime: Long) {
    val t = frameTime * 0.001f

    val lines = listOf(
        WaveLine(6f,  1.8f, 0f,   Color(0x2EFFFFFF), 1.5f),
        WaveLine(10f, 1.4f, 0.8f, Color(0x5950A0FF), 1.8f),
        WaveLine(14f, 1.1f, 1.6f, Color(0x8C64B4FF), 2f),
        WaveLine(10f, 1.4f, 2.4f, Color(0x5950A0FF), 1.8f),
        WaveLine(6f,  1.8f, 3.2f, Color(0x2EFFFFFF), 1.5f)
    )

    lines.forEach { line ->
        val path = Path()
        val steps = w.toInt().coerceAtLeast(1)
        for (x in 0..steps) {
            val xf = x.toFloat()
            val norm = xf / w
            val envelope = sin(norm * PI.toFloat())
            val y = h / 2f + sin(norm * PI.toFloat() * 2f * line.freq + t * 1.2f + line.offset) *
                    line.ampScale * envelope
            if (x == 0) path.moveTo(xf, y) else path.lineTo(xf, y)
        }
        drawPath(
            path = path,
            color = line.color,
            style = Stroke(width = line.width, cap = StrokeCap.Round)
        )
    }
}

// ── Live audio-reactive waveform ────────────────────────────────────
private fun DrawScope.drawLiveWaveform(w: Float, h: Float, amp: Float, phase: Float) {
    val lines = listOf(
        WaveLine(amp * 0.4f,  1.8f, 0f,   Color(0x2EFFFFFF), 1.5f),
        WaveLine(amp * 0.65f, 1.4f, 0.8f, Color(0x6150A0FF), 1.8f),
        WaveLine(amp * 1.0f,  1.1f, 1.6f, Color(0xB878BEFF), 2.2f),
        WaveLine(amp * 0.65f, 1.4f, 2.4f, Color(0x6150A0FF), 1.8f),
        WaveLine(amp * 0.4f,  1.8f, 3.2f, Color(0x2EFFFFFF), 1.5f)
    )

    lines.forEach { line ->
        val path = Path()
        val steps = w.toInt().coerceAtLeast(1)
        for (x in 0..steps) {
            val xf = x.toFloat()
            val norm = xf / w
            val envelope = sin(norm * PI.toFloat())
            val y = h / 2f + sin(norm * PI.toFloat() * 2f * line.freq + phase * 1.8f + line.offset) *
                    line.ampScale * h * envelope
            if (x == 0) path.moveTo(xf, y) else path.lineTo(xf, y)
        }
        drawPath(
            path = path,
            color = line.color,
            style = Stroke(width = line.width, cap = StrokeCap.Round)
        )
    }
}
