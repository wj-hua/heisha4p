package com.heisha.lite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Apple 风格色轮取色器：角度 = 色相，半径 = 饱和度，明度固定 1。
 * 色相排布与原版一致：右侧红色，顺时针依次为品红→蓝→青→绿→黄。
 * 细节：柔和边缘描边，带当前颜色填充与外层白圈阴影的高质感取色点。
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 颜色变化回调；done=true 表示手指抬起（此时才下发命令，避免连发被设备丢弃） */
    var onColorChanged: ((color: Int, done: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val thumbRadius = 13 * density
    private val hsv = floatArrayOf(0f, 1f, 1f)
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x14000000
    }
    private val thumbFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.WHITE
        setShadowLayer(3 * density, 0f, 1.5f * density, 0x40000000)
    }

    val color: Int get() = Color.HSVToColor(hsv)

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        hsv[2] = 1f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - thumbRadius - 4 * density
        huePaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED),
            null
        )
        satPaint.shader = RadialGradient(cx, cy, radius, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        // 色相与饱和度层
        canvas.drawCircle(cx, cy, radius, huePaint)
        canvas.drawCircle(cx, cy, radius, satPaint)
        // 外沿精致描边
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // 取色点位置：屏幕坐标 y 向下，顺时针角度 = 360 - 色相
        val a = Math.toRadians((360 - hsv[0]).toDouble())
        val r = hsv[1] * radius
        val tx = cx + (cos(a) * r).toFloat()
        val ty = cy + (sin(a) * r).toFloat()

        // Apple 风格取色环：内部当前色填充 + 外圈纯白质感立体描边
        thumbFillPaint.color = color
        canvas.drawCircle(tx, ty, thumbRadius - 2f * density, thumbFillPaint)
        canvas.drawCircle(tx, ty, thumbRadius, thumbPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                // 在滚动容器里拖动时不让父布局抢走事件
                parent.requestDisallowInterceptTouchEvent(e.actionMasked != MotionEvent.ACTION_UP)
                val dx = e.x - cx
                val dy = e.y - cy
                val deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                hsv[0] = (360f - deg) % 360f
                hsv[1] = min(hypot(dx, dy) / radius, 1f)
                invalidate()
                onColorChanged?.invoke(color, e.actionMasked == MotionEvent.ACTION_UP)
            }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
