package io.github.dead4f.burpmcplite.ui

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated rounded-pill toggle switch, ported from the official extension's
 * `ToggleSwitch`. The little sparkle burst on enable tracks Burp's primary
 * accent color so it fits whatever theme the user runs.
 */
class ToggleSwitch(
    private var isOn: Boolean,
    private val onToggle: (Boolean) -> Unit,
) : JComponent() {

    companion object {
        private const val TRACK_WIDTH = 44
        private const val TRACK_HEIGHT = 24
        private const val THUMB_SIZE = 20
        private const val PADDING = 2
        private const val ANIMATION_DURATION = 150
        private const val TIMER_DELAY = 16
        private const val SPARKLE_DURATION = 800
        private const val SPARKLE_COUNT = 8
        private const val SPARKLE_MARGIN = 8
        private const val COMPONENT_WIDTH = TRACK_WIDTH + SPARKLE_MARGIN * 2
        private const val COMPONENT_HEIGHT = TRACK_HEIGHT + SPARKLE_MARGIN * 2
    }

    private var animationProgress: Float = if (isOn) 1f else 0f
    private var animationTimer: Timer? = null
    private val sparkles: MutableList<Sparkle> = mutableListOf()
    private var sparkleTimer: Timer? = null

    private data class Sparkle(
        var x: Float, var y: Float, var size: Float, var opacity: Float,
        var life: Float, val maxLife: Float,
        val velocityX: Float, val velocityY: Float,
    )

    init {
        preferredSize = Dimension(COMPONENT_WIDTH, COMPONENT_HEIGHT)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                if (!isEnabled) return
                val bounds = Rectangle(SPARKLE_MARGIN, SPARKLE_MARGIN, TRACK_WIDTH, TRACK_HEIGHT)
                if (e != null && bounds.contains(e.point)) toggle()
            }
        })
    }

    fun setState(newState: Boolean, animate: Boolean = true) {
        if (isOn == newState) return
        isOn = newState
        if (animate) animateToState() else {
            animationProgress = if (isOn) 1f else 0f
            repaint()
        }
    }

    private fun toggle() {
        isOn = !isOn
        onToggle(isOn)
        animateToState()
    }

    private fun animateToState() {
        animationTimer?.stop()
        val startProgress = animationProgress
        val target = if (isOn) 1f else 0f
        val startTime = System.currentTimeMillis()
        animationTimer = Timer(TIMER_DELAY) { _ ->
            val elapsed = System.currentTimeMillis() - startTime
            val p = (elapsed.toFloat() / ANIMATION_DURATION).coerceIn(0f, 1f)
            animationProgress = startProgress + (target - startProgress) * p
            if (p >= 1f) {
                animationTimer?.stop()
                animationProgress = target
                if (isOn) triggerSparkles()
            }
            repaint()
        }.also { it.start() }
    }

    private fun triggerSparkles() {
        sparkles.clear()
        sparkleTimer?.stop()
        val thumbCx = SPARKLE_MARGIN + PADDING + 1f * (TRACK_WIDTH - THUMB_SIZE - 2 * PADDING) + THUMB_SIZE / 2f
        val thumbCy = SPARKLE_MARGIN + PADDING + THUMB_SIZE / 2f
        val maxSafe = listOf(
            COMPONENT_WIDTH - thumbCx - 7f,
            COMPONENT_HEIGHT - thumbCy - 7f,
            thumbCx - 7f,
            thumbCy - 7f,
        ).min().coerceAtLeast(4f)
        repeat(SPARKLE_COUNT) { i ->
            val angle = i * 360f / SPARKLE_COUNT * Math.PI.toFloat() / 180f
            val distance = 4f + Math.random().toFloat() * (maxSafe - 4f)
            sparkles += Sparkle(
                x = thumbCx + cos(angle) * distance,
                y = thumbCy + sin(angle) * distance,
                size = 2f + Math.random().toFloat() * 3f,
                opacity = 1f,
                life = 0f,
                maxLife = SPARKLE_DURATION + Math.random().toFloat() * 200f,
                velocityX = (Math.random().toFloat() - 0.5f) * 0.5f,
                velocityY = (Math.random().toFloat() - 0.5f) * 0.5f,
            )
        }
        sparkleTimer = Timer(TIMER_DELAY) { _ ->
            var active = false
            for (s in sparkles) {
                s.life += TIMER_DELAY
                s.x += s.velocityX
                s.y += s.velocityY
                s.opacity = (1f - s.life / s.maxLife).coerceIn(0f, 1f)
                s.size *= 0.998f
                if (s.life < s.maxLife) active = true
            }
            if (!active) {
                sparkleTimer?.stop()
                sparkles.clear()
            }
            repaint()
        }.also { it.start() }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val trackX = SPARKLE_MARGIN.toFloat()
        val trackY = SPARKLE_MARGIN.toFloat()

        g2.color = if (isOn) Design.Colors.primary else Design.Colors.outline
        g2.fill(RoundRectangle2D.Float(trackX, trackY, TRACK_WIDTH.toFloat(), TRACK_HEIGHT.toFloat(), TRACK_HEIGHT.toFloat(), TRACK_HEIGHT.toFloat()))

        val thumbX = trackX + PADDING + animationProgress * (TRACK_WIDTH - THUMB_SIZE - 2 * PADDING)
        val thumbY = trackY + PADDING
        g2.color = Color(0, 0, 0, 20)
        g2.fill(RoundRectangle2D.Float(thumbX + 1, thumbY + 1, THUMB_SIZE.toFloat(), THUMB_SIZE.toFloat(), THUMB_SIZE.toFloat(), THUMB_SIZE.toFloat()))
        g2.color = Color.WHITE
        g2.fill(RoundRectangle2D.Float(thumbX, thumbY, THUMB_SIZE.toFloat(), THUMB_SIZE.toFloat(), THUMB_SIZE.toFloat(), THUMB_SIZE.toFloat()))

        for (s in sparkles) {
            if (s.opacity <= 0f) continue
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, s.opacity)
            g2.color = Design.Colors.primary
            g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val ss = s.size
            g2.drawLine((s.x - ss).toInt(), s.y.toInt(), (s.x + ss).toInt(), s.y.toInt())
            g2.drawLine(s.x.toInt(), (s.y - ss).toInt(), s.x.toInt(), (s.y + ss).toInt())
            val d = ss * 0.7f
            g2.drawLine((s.x - d).toInt(), (s.y - d).toInt(), (s.x + d).toInt(), (s.y + d).toInt())
            g2.drawLine((s.x - d).toInt(), (s.y + d).toInt(), (s.x + d).toInt(), (s.y - d).toInt())
        }
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f)
        g2.dispose()
    }

    override fun updateUI() {
        super.updateUI()
        repaint()
    }
}
