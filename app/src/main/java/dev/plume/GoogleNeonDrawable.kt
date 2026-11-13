package dev.plume

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * A glowing Google-style rainbow neon outline (Gemini / Google AI gradient:
 * Blue -> Purple -> Red -> Yellow -> Green -> Blue).
 * Animates a smooth rotation, breathing pulse, or elegant static glow while active/focused.
 */
internal class GoogleNeonDrawable(
    private val radius: Float = 0f,
    private val isCircle: Boolean = false,
    private val strokeWidthDp: Float = 2.5f,
    private val density: Float = 2f,
    private val overrideTheme: String? = null,
    private val overrideAnimMode: String? = null,
    private val overrideThickness: String? = null
) : Drawable(), Animatable {

    companion object {
        val GOOGLE_COLORS = intArrayOf(
            0xFF4285F4.toInt(), // Google Blue
            0xFF9B72CB.toInt(), // Gemini Purple / Violet
            0xFFEA4335.toInt(), // Google Red
            0xFFFBBC05.toInt(), // Google Yellow
            0xFF34A853.toInt(), // Google Green
            0xFF4285F4.toInt()  // Loop back to Blue
        )
        val POSITIONS = floatArrayOf(0f, 0.2f, 0.42f, 0.65f, 0.85f, 1f)

        /**
         * An animated glow redraws the whole screen on every frame (wallpaper, glass and all): ~60% of a core on a
         * TV box. So it animates only while someone is using the remote ([poke]), settles after [IDLE_MS] without a
         * key, and draws at most one frame per [FRAME_MS], which a slow pulse or spin doesn't need more than.
         */
        const val IDLE_MS = 10_000L
        const val FRAME_MS = 30L // every 2nd frame at 60 Hz, with room for jitter
        private var lastPoke = 0L
        private val live = java.util.Collections.newSetFromMap(java.util.WeakHashMap<GoogleNeonDrawable, Boolean>())

        /** A key was pressed: settled glows that are still on screen animate again. Main thread only. */
        fun poke() {
            lastPoke = android.os.SystemClock.uptimeMillis()
            live.toList().forEach { it.wake() }
        }

        /** The glow in use (a key of [GLOWS], or "accent"), and the wallpaper's accent colour for "accent". */
        var theme = "google"
        var accent = 0xFF4DA3FF.toInt()

        /** Glow animation mode: "spin", "pulse", "static" */
        var animMode = "spin"

        /** Glow thickness: "subtle" (1.8dp), "standard" (2.5dp), "bold" (3.6dp) */
        var glowThickness = "standard"

        /** Glow theme -> its colours around the ring; the first and last match so the loop is seamless. */
        val GLOWS = linkedMapOf(
            "google" to GOOGLE_COLORS,
            "aurora" to intArrayOf(0xFF00E5A0.toInt(), 0xFF00B8FF.toInt(), 0xFF7B5CFF.toInt(), 0xFF00E5A0.toInt()),
            "sunset" to intArrayOf(0xFFFF9A3C.toInt(), 0xFFFF4F81.toInt(), 0xFF8E54E9.toInt(), 0xFFFF9A3C.toInt()),
            "ocean" to intArrayOf(0xFF00D4FF.toInt(), 0xFF2979FF.toInt(), 0xFF00E5C8.toInt(), 0xFF00D4FF.toInt()),
            "fire" to intArrayOf(0xFFFF3B1F.toInt(), 0xFFFF9F1C.toInt(), 0xFFFFE066.toInt(), 0xFFFF9F1C.toInt(), 0xFFFF3B1F.toInt()),
            "cyber" to intArrayOf(0xFFFF2BD6.toInt(), 0xFF00F0FF.toInt(), 0xFF7A2BFF.toInt(), 0xFFFF2BD6.toInt()),
            "white" to intArrayOf(0xFFFFFFFF.toInt(), 0xFFB8C4D6.toInt(), 0xFFFFFFFF.toInt(), 0xFFB8C4D6.toInt(), 0xFFFFFFFF.toInt()),
        )

        fun colors(t: String = theme): IntArray {
            if (t != "accent") return GLOWS[t] ?: GOOGLE_COLORS
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(accent, hsv)
            hsv[1] = (hsv[1] * 0.45f).coerceIn(0f, 1f) // a paler shade of the same hue
            val light = android.graphics.Color.HSVToColor(hsv)
            return intArrayOf(accent, light, accent)
        }
    }

    private val activeTheme = overrideTheme ?: theme
    private val activeMode = overrideAnimMode ?: animMode
    private val activeThickness = overrideThickness ?: glowThickness

    private val colorsNow = colors(activeTheme)

    private val effectiveStrokeDp = when (activeThickness) {
        "subtle" -> 1.8f
        "bold" -> 3.6f
        else -> strokeWidthDp
    }

    private val strokePx = effectiveStrokeDp * density
    private val glowPx = strokePx * 2.2f

    private val neonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = glowPx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 95
    }

    private var shader: SweepGradient? = null
    private val matrix = Matrix()
    private val rect = RectF()
    private var rotationAngle = 0f
    private var animator: ValueAnimator? = null

    init {
        start()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val inset = strokePx / 2f
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        shader = SweepGradient(cx, cy, colorsNow, if (activeTheme == "google") POSITIONS else null)
        updateMatrix()
        neonPaint.shader = shader
        glowPaint.shader = shader
    }

    private fun updateMatrix() {
        val s = shader ?: return
        val b = bounds
        matrix.setRotate(rotationAngle, b.exactCenterX(), b.exactCenterY())
        s.setLocalMatrix(matrix)
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        if (isCircle) {
            val r = (minOf(rect.width(), rect.height())) / 2f
            val cx = rect.centerX()
            val cy = rect.centerY()
            canvas.drawCircle(cx, cy, r, glowPaint)
            canvas.drawCircle(cx, cy, r, neonPaint)
        } else {
            val r = radius.coerceAtLeast(4f * density)
            canvas.drawRoundRect(rect, r, r, glowPaint)
            canvas.drawRoundRect(rect, r, r, neonPaint)
        }
    }

    private var sleeping = false
    private var startedAt = 0L
    private var lastFrame = 0L

    /**
     * Per animation frame: false when this one is skipped. A glow that has been idle [IDLE_MS] settles at full
     * brightness and waits for [poke]; one no longer on a view (its view lost focus or was dropped) stops for good.
     */
    private fun frame(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (callback == null) {
            stop()
            return false
        }
        if (now - maxOf(lastPoke, startedAt) > IDLE_MS) {
            animator?.cancel()
            animator = null
            sleeping = true
            neonPaint.alpha = 255
            glowPaint.alpha = 95
            invalidateSelf()
            return false
        }
        if (now - lastFrame < FRAME_MS) return false
        lastFrame = now
        return true
    }

    private fun wake() {
        if (!sleeping) return
        sleeping = false
        if (callback == null) live.remove(this) else start()
    }

    override fun start() {
        if (animator?.isRunning == true) return
        sleeping = false
        startedAt = android.os.SystemClock.uptimeMillis()
        if (activeMode != "static") live.add(this)
        when (activeMode) {
            "pulse" -> {
                rotationAngle = 45f
                updateMatrix()
                animator = ValueAnimator.ofFloat(0.35f, 1f).apply {
                    duration = 1800L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        if (!frame()) return@addUpdateListener
                        val factor = it.animatedValue as Float
                        neonPaint.alpha = (255 * factor).toInt()
                        glowPaint.alpha = (95 * factor).toInt()
                        invalidateSelf()
                    }
                    start()
                }
            }
            "static" -> {
                rotationAngle = 45f
                updateMatrix()
                neonPaint.alpha = 255
                glowPaint.alpha = 95
                invalidateSelf()
            }
            else -> { // "spin"
                animator = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 3500L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        if (!frame()) return@addUpdateListener
                        rotationAngle = it.animatedValue as Float
                        updateMatrix()
                        invalidateSelf()
                    }
                    start()
                }
            }
        }
    }

    override fun stop() {
        animator?.cancel()
        animator = null
        sleeping = false
        live.remove(this)
    }

    override fun isRunning(): Boolean = animator?.isRunning == true

    override fun setAlpha(alpha: Int) {
        neonPaint.alpha = alpha
        glowPaint.alpha = (alpha * 0.38f).toInt()
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

