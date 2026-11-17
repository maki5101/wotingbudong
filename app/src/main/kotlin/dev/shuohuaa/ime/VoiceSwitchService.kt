package dev.shuohuaa.ime

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings as SysSettings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * "外挂话筒键"：别的键盘（Gboard/三星键盘…）弹出来时，在它顶上右角放一个小话筒；
 * 点一下把当前输入法换成我们的圆球。这是普通 App 唯一被允许替用户切输入法的途径
 * （无障碍服务的 softKeyboardController.switchToInputMethod，Android 11+）。
 *
 * 只订阅窗口变化事件，不读屏幕内容、不拦按键。切之前记住原来的键盘，
 * 圆球旁的"键盘"钮切不回去时会广播过来让这里切回。
 */
class VoiceSwitchService : AccessibilityService() {

    private var bubble: ImageButton? = null
    private var pcBadge: android.widget.TextView? = null
    private lateinit var wm: WindowManager
    private lateinit var settings: Settings

    private val switchBack = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prev = settings.previousIme
            if (prev.isNotEmpty() && prev != OUR_IME) {
                switchIme(prev)
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settings = Settings(this)
        val filter = IntentFilter(ACTION_SWITCH_BACK)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(switchBack, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(switchBack, filter)
        // 导航栏上的无障碍按钮（系统画的，不悬浮）：点一下来回切
        accessibilityButtonController.registerAccessibilityButtonCallback(object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) { toggle() }
            override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
                Log.d(TAG, "导航栏无障碍按钮可用: $available")
            }
        })
        Log.d(TAG, "无障碍服务已连接")
        refresh()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        try { unregisterReceiver(switchBack) } catch (_: Exception) {}
        hideBubble()
        showPcRecording(false)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) { refresh() }
    override fun onInterrupt() {}

    /** 看一眼当前有没有别人的键盘窗口；有就在它右上角贴话筒，没有就收起 */
    private fun refresh() {
        val imeWindow = try { windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } } catch (_: Exception) { null }
        val current = SysSettings.Secure.getString(contentResolver, SysSettings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        if (imeWindow == null || current == OUR_IME || !settings.bubble) { hideBubble(); return }
        val bounds = Rect(); imeWindow.getBoundsInScreen(bounds)
        if (bounds.isEmpty || bounds.height() < dp(80)) { hideBubble(); return }   // 太矮的不是真键盘（比如只剩工具条）
        showBubble(bounds)
    }

    private fun showBubble(kb: Rect) {
        val size = dp(40)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = kb.right - size - dp(12)
            y = kb.top - size / 2          // 骑在键盘上边缘：一半在键盘上一半在应用上
        }
        val b = bubble
        if (b == null) {
            val v = ImageButton(this).apply {
                setImageResource(R.drawable.ic_mic)
                background = ContextCompat.getDrawable(context, R.drawable.bubble_bg)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = getString(R.string.a11y_label)
                setOnClickListener { switchToUs() }
            }
            wm.addView(v, lp); bubble = v
        } else {
            wm.updateViewLayout(b, lp)
        }
    }

    /** 电脑正在用手机当话筒录音：屏幕顶上挂一个小条"● 电脑录音中"，停了收起（2026-10-30 用户：录着的时候屏幕上得看得到）。
     *  不挡操作（不可触摸）。主线程调 */
    fun showPcRecording(on: Boolean) {
        if (!on) {
            pcBadge?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            pcBadge = null
            return
        }
        if (pcBadge != null || !::wm.isInitialized) return
        val v = android.widget.TextView(this).apply {
            text = getString(R.string.pc_recording_badge)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat(); setColor(0xE6D93025.toInt())
            }
        }
        val statusBar = resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it > 0) resources.getDimensionPixelSize(it) else dp(24) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBar + dp(4)
        }
        try { wm.addView(v, lp); pcBadge = v } catch (e: Exception) { Log.w(TAG, "电脑录音提示挂不上：$e") }
    }

    private fun hideBubble() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    /** 无障碍服务切输入法要 Android 11（API 30）；更老的系统上快速切换不可用，返回 false */
    private fun switchIme(id: String): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) softKeyboardController.switchToInputMethod(id) else false

    /** 导航栏按钮：我们的输入法在用就切回上一个，否则切到我们 */
    private fun toggle() {
        val current = SysSettings.Secure.getString(contentResolver, SysSettings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        if (current == OUR_IME) {
            val prev = settings.previousIme
            if (prev.isNotEmpty() && prev != OUR_IME) switchIme(prev)
            else softKeyboardController.showMode = android.accessibilityservice.AccessibilityService.SHOW_MODE_AUTO
        } else {
            switchToUs()
        }
    }

    private fun switchToUs(): Boolean {
        val current = SysSettings.Secure.getString(contentResolver, SysSettings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        if (current.isNotEmpty() && current != OUR_IME) settings.previousIme = current
        VoiceInputService.lastActivatedElapsed = android.os.SystemClock.elapsedRealtime()
        val ok = switchIme(OUR_IME)
        Log.d(TAG, "切到我听不懂: $ok（原来是 $current）")
        if (ok) hideBubble()
        return ok
    }

    /** 外部调用（Tasker 等）：在输入框里就切到本输入法并开录。返回 null = 切过去了；否则是给用户的提示
     *  （不在输入框里 / 系统太旧切不了 / 本输入法没在系统里启用）。
     *  主要看屏幕上有没有键盘窗口（在输入框里别的键盘就是弹着的）——曾经只看"输入焦点那个节点能不能编辑"，
     *  微信、网页这类不报，用户明明在输入框里也一直提示"不在输入框里"（2026-10-05）。焦点节点留作补充。
     *  只看这两样，不读屏幕上别的内容 */
    fun startVoiceIfEditing(): Int? {
        val keyboard = try { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } } catch (_: Exception) { false }
        val focus = try { rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
        val editable = focus?.isEditable == true
        Log.d(TAG, "外部调用：键盘窗口 $keyboard，焦点可编辑 $editable")
        if (!keyboard && !editable) return R.string.hint_external_no_field
        if (android.os.Build.VERSION.SDK_INT < 30) return R.string.hint_external_old_android
        VoiceInputService.externalStartAt = android.os.SystemClock.elapsedRealtime()
        if (switchToUs()) return null
        VoiceInputService.externalStartAt = 0L   // 没切过去（本输入法没在系统里启用）：别把开录的要求留着
        return R.string.hint_external_switch_failed
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "shuohuaa.a11y"
        const val OUR_IME = "dev.shuohuaa.ime/.VoiceInputService"
        const val ACTION_SWITCH_BACK = "dev.shuohuaa.ime.SWITCH_BACK"
        @Volatile var instance: VoiceSwitchService? = null

        /** 无障碍服务开没开（设置页显示用） */
        fun isEnabled(context: Context): Boolean {
            val enabled = SysSettings.Secure.getString(context.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return enabled.split(':').any { it.equals("dev.shuohuaa.ime/.VoiceSwitchService", true) || it.equals("dev.shuohuaa.ime/dev.shuohuaa.ime.VoiceSwitchService", true) }
        }
    }
}
