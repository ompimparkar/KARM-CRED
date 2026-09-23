package com.example.karmcredapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Trust-network particle background — Android twin of
 * backend/static/js/background.js:
 *
 *  - 28 / 40 / 56 nodes by screen width (small / normal / wide)
 *  - connecting lines capped at 0.15 alpha, skipped entirely under 480dp
 *  - ~30fps throttle (one frame per 33ms)
 *  - pauses whenever the view is not visible (activity stopped/hidden)
 *  - prefers-reduced-motion (system animator scale off): one static frame
 */
class NetworkBgView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Node(
        var x: Float, var y: Float,
        val vx: Float, val vy: Float,
        val r: Float, val accent: Boolean
    )

    private val density = context.resources.displayMetrics.density
    private val random = Random(42)
    private var nodes: List<Node> = emptyList()
    private var running = false

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF38BDF8.toInt()
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0xFF38BDF8.toInt()
    }

    private val frameTick = Runnable { tick() }

    private fun targetCount(): Int {
        val wDp = width / density
        return when {
            wDp < 360f -> 28
            wDp < 720f -> 40
            else -> 56
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        nodes = List(targetCount()) { i ->
            val speed = 6f * density          // dp/s drift
            Node(
                x = random.nextFloat() * w,
                y = random.nextFloat() * h,
                vx = (random.nextFloat() * 2f - 1f) * speed,
                vy = (random.nextFloat() * 2f - 1f) * speed,
                r = (if (i % 5 == 0) 2.8f else 1.8f) * density,
                accent = i % 5 == 0
            )
        }
        if (!Motion.animatorsEnabled(this)) {
            invalidate()                      // single static frame
        } else {
            restartLoop()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) restartLoop() else stopLoop()
    }

    // Window-level visibility (activity stopped / app backgrounded): tick()
    // self-stops via isShown(), and this restarts the loop on return.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) restartLoop() else stopLoop()
    }

    private fun restartLoop() {
        stopLoop()
        if (nodes.isEmpty() || !Motion.animatorsEnabled(this) || !isShown) return
        running = true
        postDelayed(frameTick, 33)
    }

    private fun stopLoop() {
        running = false
        removeCallbacks(frameTick)
    }

    private fun tick() {
        if (!running || !isShown) {
            running = false
            return
        }
        val dt = 33f / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        for (n in nodes) {
            n.x += n.vx * dt
            n.y += n.vy * dt
            if (n.x < 0f) n.x += w else if (n.x > w) n.x -= w
            if (n.y < 0f) n.y += h else if (n.y > h) n.y -= h
        }
        invalidate()
        postDelayed(frameTick, 33)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.isEmpty()) return

        // lines skipped entirely on very narrow screens (web: <480px)
        val drawLines = width / density >= 480f
        if (drawLines) {
            val linkDist = 130f * density
            for (i in nodes.indices) {
                for (j in i + 1 until nodes.size) {
                    val a = nodes[i]
                    val b = nodes[j]
                    val d = hypot(a.x - b.x, a.y - b.y)
                    if (d < linkDist) {
                        // fade with distance, hard cap at 0.15 alpha
                        val alpha = (0.15f * (1f - d / linkDist)).coerceIn(0f, 0.15f)
                        linePaint.alpha = (alpha * 255).toInt()
                        canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
                    }
                }
            }
        }

        for (n in nodes) {
            nodePaint.alpha = if (n.accent) 115 else 77     // ≤ 0.45
            canvas.drawCircle(n.x, n.y, n.r, nodePaint)
        }
    }
}
