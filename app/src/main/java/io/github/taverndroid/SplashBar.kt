package io.github.taverndroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View

/** 开屏上的细进度条：知道进度时从左往右填，不知道时一小段光在轨道上来回扫。颜色是粉到米色的渐变。 */
class SplashBar(context: Context) : View(context) {
    /** 0..1；null = 不知道进度。 */
    var progress: Float? = null
        set(value) {
            field = value?.coerceIn(0f, 1f)
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK }
    private val gradient = LinearGradient(0f, 0f, 1f, 0f, PINK, CREAM, Shader.TileMode.CLAMP)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient }
    private val matrix = Matrix()
    private val rect = RectF()
    private var sweepStart = 0L

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, track)
        val p = progress
        if (p != null) {
            sweepStart = 0L
            if (p <= 0f) return
            drawSegment(canvas, 0f, maxOf(h, w * p), h, radius)
            return
        }
        // 不知道进度：一段占三成宽的光，缓入缓出地从左扫到右
        val now = SystemClock.uptimeMillis()
        if (sweepStart == 0L) sweepStart = now
        val t = ((now - sweepStart) % SWEEP_MS) / SWEEP_MS.toFloat()
        val eased = t * t * (3 - 2 * t)
        val segment = w * 0.32f
        val x = -segment + (w + segment) * eased
        val left = maxOf(0f, x)
        val right = minOf(w, x + segment)
        if (right > left) drawSegment(canvas, left, right, h, radius)
        postInvalidateOnAnimation()
    }

    /** 渐变跟着这一段走：段的左端是粉色，右端是米色。 */
    private fun drawSegment(canvas: Canvas, left: Float, right: Float, h: Float, radius: Float) {
        matrix.setScale(right - left, 1f)
        matrix.postTranslate(left, 0f)
        gradient.setLocalMatrix(matrix)
        rect.set(left, 0f, right, h)
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    companion object {
        private const val SWEEP_MS = 1600L
        val PINK = Color.rgb(0xee, 0x71, 0x94)
        val CREAM = Color.rgb(0xf6, 0xd2, 0xc5)
        private val TRACK = Color.argb(0x3a, 0xf6, 0xd2, 0xc5)
    }
}
