package com.amy.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * AmyCanvasView - Custom view rendering a reactive waveform, used as AMY's
 * "listening / speaking / thinking" visual indicator.
 */
class AmyCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State { IDLE, LISTENING, SPEAKING, THINKING }

    private var currentState: State = State.IDLE
    private var animationPhase = 0f

    private val wavePaint = Paint().apply {
        color = Color.parseColor("#00D9FF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val dimWavePaint = Paint().apply {
        color = Color.parseColor("#1A6E80")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private var animator: android.animation.ValueAnimator? = null

    init {
        startAnimationLoop()
    }

    fun setState(state: State) {
        currentState = state
        invalidate()
    }

    private fun startAnimationLoop() {
        animator = android.animation.ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 2000
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                animationPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val centerY = height / 2f
        val amplitude = when (currentState) {
            State.IDLE -> height * 0.05f
            State.LISTENING -> height * 0.25f
            State.SPEAKING -> height * 0.35f
            State.THINKING -> height * 0.15f
        }

        drawWave(canvas, centerY, amplitude, wavePaint, 1.0)
        drawWave(canvas, centerY, amplitude * 0.6f, dimWavePaint, 1.6)
    }

    private fun drawWave(canvas: Canvas, centerY: Float, amplitude: Float, paint: Paint, freqMultiplier: Double) {
        if (width == 0) return
        val path = android.graphics.Path()
        val step = 8
        var first = true
        for (x in 0..width step step) {
            val noise = if (currentState == State.LISTENING) Random.nextFloat() * amplitude * 0.15f else 0f
            val y = centerY + amplitude * sin((x * 0.02 * freqMultiplier) + animationPhase).toFloat() + noise
            if (first) {
                path.moveTo(x.toFloat(), y)
                first = false
            } else {
                path.lineTo(x.toFloat(), y)
            }
        }
        canvas.drawPath(path, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
