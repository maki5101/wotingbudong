package dev.shuohuaa.ime

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * 悬浮圆球：本身就是状态灯。待机 / 录音中（呼吸）/ 识别中（转圈）/ 出错（灰）。
 * 待机色和整体透明度由外部（Service 从设置读）传进来；录音红、识别橙是语义色，不跟随。
 */
class OrbView(context: Context, val orbDp: Int = DEFAULT_ORB_DP) : View(context) {
    /** View 直径：可见球 + 两边给扩散圈留的余量 */
    val viewDp = orbDp + 2 * PAD_DP
    enum class State { IDLE, RECORDING, BUSY, ERROR }

    var state: State = State.IDLE
        set(v) { if (field != v) { field = v; restartAnim(); invalidate() } }

    /** 待机时的颜色（设置里"圆球颜色"决定，跟随系统时是系统主色） */
    var idleColor: Int = ContextCompat.getColor(context, R.color.orb_idle)
        set(v) { field = v; invalidate() }

    /** 整体不透明度 0~1 */
    var overallAlpha: Float = 0.82f
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f) }
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_mic)!!.mutate()
    private var phase = 0f
    private var anim: ValueAnimator? = null

    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        const val DEFAULT_ORB_DP = 56   // 可见圆球直径（设置里可调）
        const val PAD_DP = 18           // View 边到可见球边的留白：给扩散圈的
    }

    val orbRadius: Float get() = dp(orbDp / 2f)

    private fun restartAnim() {
        anim?.cancel(); anim = null
        if (state == State.RECORDING || state == State.BUSY) {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (state == State.RECORDING) 1400 else 900
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = orbRadius
        val base = when (state) {
            State.IDLE -> idleColor
            State.RECORDING -> color(R.color.orb_recording)
            State.BUSY -> color(R.color.orb_busy)
            State.ERROR -> color(R.color.orb_error)
        }
        val a = (overallAlpha * 255).toInt()
        fill.color = base
        fill.alpha = a / 4;  c.drawCircle(cx, cy, r + dp(3f), fill)   // 同心淡外圈
        fill.alpha = a;      c.drawCircle(cx, cy, r, fill)

        when (state) {
            State.RECORDING -> {
                ring.color = base; ring.alpha = ((1f - phase) * 160 * overallAlpha).toInt()
                val maxR = minOf(cx, cy) - ring.strokeWidth
                c.drawCircle(cx, cy, r + dp(3f) + phase * (maxR - r - dp(3f)), ring)
            }
            State.BUSY -> {
                ring.color = Color.WHITE; ring.alpha = (220 * overallAlpha).toInt()
                val box = RectF(cx - r + dp(4f), cy - r + dp(4f), cx + r - dp(4f), cy + r - dp(4f))
                c.drawArc(box, phase * 360f, 90f, false, ring)
            }
            else -> {}
        }
        icon.setTint(Color.WHITE); icon.alpha = a
        val s = (r * 1.0f).toInt()
        icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        icon.draw(c)
    }
}
