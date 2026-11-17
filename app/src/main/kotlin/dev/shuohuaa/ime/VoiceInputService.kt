package dev.shuohuaa.ime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import android.content.res.ColorStateList
import android.graphics.drawable.InsetDrawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 输入法本体。没有键盘：输入法窗口盖住整屏但完全透明，只有圆球那一小块接收触摸
 * （onComputeInsets 里声明可触区域），应用不会被顶起、其他地方照常可点——
 * 和 Gboard 外接键盘时的浮动模式同一个机制。
 *
 * 交互和电脑端一致：点一下开录、再点一下停；按住说话、松手停（holdMs 判定）。
 * 输入框没了（应用收起输入法 / 换了输入框）→ 停录并丢弃还没打出去的段：安卓输入法打不进已经离开的框，
 * 不丢就会打进新的框。这点和电脑端不同——电脑端开录时认定了输入框（BeginTarget），换框不丢、回去补打。
 *
 * 编辑条：不做键盘（重活交给 Gboard，一键切换），只带一行最小编辑键——
 * 光标左右 / 退格（按住连删）/ 空格 / ，。？ / 发送。发送键按输入框声明的动作来
 * （聊天框是发送，普通框是回车）。发送 = 说完了：停录不丢，等说过的话全部上屏再执行（最多 ENTER_WAIT_MS）。
 * 点卫星里的铅笔展开/收起，记住。
 */
class VoiceInputService : InputMethodService() {

    private lateinit var settings: Settings
    private lateinit var themed: Context      // 带系统动态取色的 context
    private lateinit var root: FrameLayout
    private lateinit var orb: OrbView
    private lateinit var keyboardBtn: ImageButton
    private lateinit var settingsBtn: ImageButton
    private lateinit var undoBtn: ImageButton
    private lateinit var editBtn: ImageButton
    private lateinit var strip: LinearLayout     // 编辑条
    private lateinit var enterKey: TextView      // 编辑条里的发送/回车键，文字按输入框的动作变
    private var punctKeys: List<TextView> = emptyList()   // 编辑条里的逗号/句号/问号，文字跟光标前的字变
    private var lastCommitted: String? = null   // 撤销上一句用
    private var switchWhenIdle = false          // 录音中点了切键盘：等手里的段打完再切，不丢字
    private var focusRequest: AudioFocusRequest? = null   // 录音期间压住别的 App 的声音
    private lateinit var hint: TextView
    private lateinit var transcriber: Transcriber

    private var recorder: Recorder? = null
    private var recording = false
    private var pending = 0            // 已提交、还没回来的段数
    private var seq = 0                // 段序号；撤销时把在途的作废
    private var discardUpToSeq = -1
    private var session = 0            // 录音会话号，焦点丢失时用来丢弃迟到的结果
    private var discardSession = -1

    // ── 边说边出字 ── 段的键 = (录音会话 shl 32) or 段编号，跨会话不撞
    private var streamMode = "off"                      // 这次录音的出字方式（开录时取设置）：off / live / confirm
    private val closedSegs = HashSet<Long>()            // 已结束的段（交出去或丢了）：它的临时结果晚到就不显示
    private val pendingFinals = HashSet<Long>()         // 已交出、最终结果还没回来的段
    private var composingSeg = -1L                      // live：输入框里正显示带下划线临时文字的段
    private var composingBefore: Char? = null           // 临时文字开头前面那个字（决定补不补空格）
    private var composingText = ""                       // live：输入框里正显示的临时文字（按回车时原样定下来）
    private var composingSpanSeen = false                // 编辑器报告过临时文字的位置（不报告的编辑器判断不了用户挪没挪光标）
    private val acceptedPrefix = HashMap<Long, String>()  // 用户挪光标时编辑器已收下的那部分：这段之后只出它后面的内容
    private var composingBody = ""                       // live：正显示的那段的完整识别正文（收下时记这个）
    private lateinit var preview: TextView              // confirm：圆球上方的预览气泡，点了上屏
    private val previewParts = ArrayList<Pair<String, String?>>()   // confirm：已定下的段和它的接缝标点（换输入框也保留，点了才上屏）
    private var previewLive = ""                        // confirm：正在说的这段的临时文字
    private var previewSeg = -1L
    private fun segKey(sess: Int, seg: Int) = (sess.toLong() shl 32) or seg.toLong()
    private val instanceTag = java.util.UUID.randomUUID().toString().take(8)   // 录音会话编号的前缀：电脑按它区分会话

    // ── 段与段怎么接（SegmentJoin）：末尾句号逗号先扣着，接着说才放回去 ──
    private var held = ""                    // 上一句扣着的末尾标点
    private var lastOutEnd: Char? = null     // 我们上屏的最后一个字：光标前还是它，扣着的标点才放回去
    private var composingHeld = ""           // live：临时文字开头放回去的标点
    private var lastConsumedHeld = ""        // 上一句开头放回去的标点：撤销上一句时还原成扣着

    /** 光标前还是我们上屏的最后一个字，扣着的标点才放回去（挪了光标、自己打了字就不放） */
    private fun heldFor(before: Char?) = if (before != null && before == lastOutEnd) held else ""

    private fun noteCommitted(out: String, usedHeld: String, tail: String) {
        lastCommitted = out; lastConsumedHeld = usedHeld; held = tail; lastOutEnd = out.lastOrNull()
    }

    // 触摸：区分点一下 / 按住 / 拖动
    private var downX = 0f; private var downY = 0f; private var downAt = 0L
    private var orbStartX = 0f; private var orbStartY = 0f
    private var dragging = false
    private var pressStartedRecording = false
    private var firstShow = true      // onCreateInputView 后第一次显示 = 刚切过来

    // 尺寸都从"圆球大小"设置推出来（onCreateInputView 里算），改了设置整套视图重建
    private var builtOrbDp = 0
    private var orbSizeDp = 0              // 球 View 的直径（可见球 + 扩散圈余量）
    private val orbPadDp = OrbView.PAD_DP  // View 边到可见球边的留白
    private var satDp = 36                 // 卫星触摸区直径；可见的小圆更小
    private var satVisibleDp = 22
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        transcriber = Transcriber(settings)
        instance = this
    }

    // ── 视图 ──────────────────────────────────────────────────
    override fun onCreateInputView(): View {
        themed = DynamicColors.wrapContextIfAvailable(this)
        firstShow = true
        root = FrameLayout(this)
        root.setBackgroundColor(0)   // 全透明
        // 高到盖住整屏；真正能点的区域由 onComputeInsets 决定
        root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.displayMetrics.heightPixels)
        root.minimumHeight = resources.displayMetrics.heightPixels

        builtOrbDp = settings.orbSizeDp
        orb = OrbView(themed, builtOrbDp)
        orbSizeDp = orb.viewDp
        satVisibleDp = (22f * builtOrbDp / OrbView.DEFAULT_ORB_DP).roundToInt()   // 卫星跟着球一起缩放
        satDp = satVisibleDp + 14
        applyOrbStyle()
        root.addView(orb, FrameLayout.LayoutParams(dp(orbSizeDp), dp(orbSizeDp)))
        orb.setOnTouchListener { _, ev -> onOrbTouch(ev) }
        // 读屏的"双击激活"不走触摸事件，走 performClick：接成切换录音，不然读屏用户开不了录音
        orb.setOnClickListener { if (recording) stopRecording(discard = false) else startRecording() }

        // 三颗小卫星：切键盘 / 撤销 / 设置，环绕在球周围、朝屏幕内侧展开
        keyboardBtn = satellite(R.drawable.ic_keyboard, R.string.cd_keyboard) { switchToKeyboard() }
        undoBtn = satellite(R.drawable.ic_undo, R.string.cd_undo) { undoLast() }
        editBtn = satellite(R.drawable.ic_edit, R.string.cd_edit) { toggleStrip() }
        settingsBtn = satellite(R.drawable.ic_settings, R.string.cd_settings) { openSettings() }
        buildStrip()
        applyOrbStyle()

        hint = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 12f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = ContextCompat.getDrawable(context, R.drawable.hint_bg)
            visibility = View.GONE
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        preview = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = ContextCompat.getDrawable(context, R.drawable.hint_bg)
            contentDescription = getString(R.string.cd_preview_insert)
            visibility = View.GONE
            setOnClickListener { buzz(); commitPreview() }
        }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.viewTreeObserver.addOnGlobalLayoutListener { layoutOrb() }
        return root
    }

    private fun idleColorFromSettings(): Int = when (settings.orbColor) {
        "blue" -> 0xFF3D5AFE.toInt()
        "green" -> 0xFF2E7D32.toInt()
        "purple" -> 0xFF7B1FA2.toInt()
        "orange" -> 0xFFEF6C00.toInt()
        "pink" -> 0xFFC2185B.toInt()
        else -> MaterialColors.getColor(themed, com.google.android.material.R.attr.colorPrimary,
            ContextCompat.getColor(this, R.color.orb_idle))   // 跟随系统
    }

    private fun applyOrbStyle() {
        orb.idleColor = idleColorFromSettings()
        orb.overallAlpha = settings.orbAlpha
        if (!::settingsBtn.isInitialized) return
        // 卫星配色：取球的待机色的补色，调成低饱和、偏浅的粉嫩色；图标用同色系的深色
        val hsl = FloatArray(3); ColorUtils.colorToHSL(orb.idleColor, hsl)
        val h = (hsl[0] + 180f) % 360f
        val bg = ColorUtils.HSLToColor(floatArrayOf(h, 0.55f, 0.86f))
        val fg = ColorUtils.HSLToColor(floatArrayOf(h, 0.45f, 0.30f))
        for (v in satellites) {
            v.backgroundTintList = ColorStateList.valueOf(bg)
            v.imageTintList = ColorStateList.valueOf(fg)
        }
        strip.backgroundTintList = ColorStateList.valueOf(bg)
        for (i in 0 until strip.childCount) (strip.getChildAt(i) as TextView).setTextColor(fg)
        updateEnterLabel()   // 回车键是图标，颜色要跟着重新着
    }

    // ── 编辑条 ────────────────────────────────────────────────
    private fun keyDp() = (builtOrbDp * 0.64f).roundToInt()          // 每个键的边长，跟球一起缩放

    private fun buildStrip() {
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(context, R.drawable.strip_bg)
            alpha = minOf(0.85f, settings.orbAlpha + 0.1f)
            setPadding(dp(4), dp(3), dp(4), dp(3))
            visibility = View.GONE
        }
        fun key(label: String, desc: Int = 0, repeat: Boolean = false, onTap: () -> Unit): TextView {
            val k = TextView(this).apply {
                text = label; gravity = Gravity.CENTER
                if (desc != 0) contentDescription = getString(desc)   // 箭头符号读屏念不出意思
                textSize = (keyDp() * 0.42f)
                isClickable = true; isFocusable = false
            }
            if (repeat) {
                attachRepeat(k, onTap)
                k.setOnClickListener { onTap() }   // 触摸由连发接管；这个只给读屏的双击激活用
            } else k.setOnClickListener { buzz(); onTap() }
            strip.addView(k, LinearLayout.LayoutParams(dp(keyDp()), dp(keyDp())))
            return k
        }
        key("←", R.string.cd_left) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT) }
        key("→", R.string.cd_right) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT) }
        key("⌫", R.string.cd_delete, repeat = true) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }   // 硬件退格：选区、emoji、网页都由应用处理
        key("␣", R.string.cd_space) { currentInputConnection?.commitText(" ", 1) }
        // 标点跟光标前一个字走（cjkContext）：点一下打当前合适的那种，长按打另一种
        punctKeys = listOf(0, 1, 2).map { i ->
            key("") { commitPunct(i, cjkContext()) }.apply {
                setOnLongClickListener { buzz(); commitPunct(i, !cjkContext()); true }
            }
        }
        refreshPunctLabels()
        enterKey = key("", R.string.cd_enter) { pressEnter() }   // 图标在 updateEnterLabel 里按输入框动作换
        root.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 第 i 个标点（逗号/句号/问号）。全角：日语逗号用「、」；西文的从资源取（各语言习惯不同，带空格） */
    private fun punct(i: Int, cjk: Boolean): String =
        if (cjk) listOf(if (jaContext()) "、" else "，", "。", "？")[i]
        else getString(intArrayOf(R.string.punct_comma, R.string.punct_period, R.string.punct_question)[i])

    private fun commitPunct(i: Int, cjk: Boolean) {
        currentInputConnection?.commitText(punct(i, cjk), 1)
        refreshPunctLabels()
    }

    private fun uiLang() = resources.configuration.locales[0].language

    private fun lastCharBeforeCursor(): Char? =
        currentInputConnection?.getTextBeforeCursor(1, 0)?.lastOrNull()

    private fun isCjk(c: Char): Boolean {
        val sc = Character.UnicodeScript.of(c.code)
        return sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA ||
            sc == Character.UnicodeScript.KATAKANA || c in '\u3000'..'\u303F' || c in '\uFF00'..'\uFFEF'
    }

    /** 光标前是汉字/假名/全角标点 → 全角；英文、数字、韩文、空格 → 西文；行首/框里没字 → 按界面语言 */
    private fun cjkContext(): Boolean {
        val c = lastCharBeforeCursor()
        if (c == null || c == '\n') return uiLang() == "zh" || uiLang() == "ja"
        return isCjk(c)
    }

    /** 全角逗号用「、」还是「，」：前面是假名，或界面是日语 */
    private fun jaContext(): Boolean {
        val c = lastCharBeforeCursor()
        val kana = c != null && Character.UnicodeScript.of(c.code).let {
            it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA }
        return kana || uiLang() == "ja"
    }

    /** 按钮上显示点一下会打出的那个（去掉空格） */
    private fun refreshPunctLabels() {
        if (!::strip.isInitialized || punctKeys.isEmpty() || strip.visibility != View.VISIBLE && !settings.stripOpen) return
        val cjk = cjkContext()
        punctKeys.forEachIndexed { i, k -> k.text = punct(i, cjk).trim() }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshPunctLabels()
        // 显示临时文字期间用户点了框里别处（2026-10-03 用户反馈：临时文字被当成已确认留下，最终结果又在新光标处
        // 打一遍，重复了）。只认两种：临时文字还在、光标跑到它外面；或者编辑器自己把临时文字收掉了。我们每 0.7 秒
        // 改写临时文字也会触发这里——那时光标正好在临时文字末尾，不算。编辑器不报告临时文字位置的，判断不了，不动。
        if (composingSeg == -1L) return
        if (candidatesStart != -1) {
            composingSpanSeen = true
            if (newSelStart != newSelEnd || newSelEnd != candidatesEnd) acceptShownText(stillComposing = true)
        } else if (composingSpanSeen) {
            acceptShownText(stillComposing = false)
        }
    }

    /** 用户挪了光标 = 认可眼前的临时文字：原样留下，这段的最终结果不再打，正在说的这段从这里切开，
     *  下一句从新光标处接着出、不接上一句的标点和空格 */
    private fun acceptShownText(stillComposing: Boolean) {
        val key = composingSeg
        if (stillComposing) currentInputConnection?.finishComposingText()
        // 音频不扔（扔了会吞掉"说了还没显示出来"的字，2026-10-03 电脑端用户实测）：记住收下了哪些，
        // 这段后面的临时结果和最终结果只出它后面的内容
        acceptedPrefix[key] = composingBody
        composingSeg = -1L; composingText = ""; composingSpanSeen = false
        lastOutEnd = null; held = ""
        Log.d(TAG, "用户挪了光标，临时文字算已确认（段 ${key and 0xffffffffL}），之后只出后面的内容")
    }

    /** 按住连发：先等 400ms，之后每 60ms 一次 */
    private fun attachRepeat(k: TextView, onTap: () -> Unit) {
        var repeating = false
        val tick = object : Runnable { override fun run() { if (repeating) { onTap(); k.postDelayed(this, 60) } } }
        k.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { buzz(); onTap(); repeating = true; k.postDelayed(tick, 400); v.isPressed = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { repeating = false; k.removeCallbacks(tick); v.isPressed = false }
            }
            true
        }
    }

    /**
     * 回车 = 说完了。说出来的话优先（2026-10-04 用户，电脑端同一条："每一个输入进来的录音流都要处理完，你才能干别的"）：
     * 停录不丢，残余段照常交出去；等所有段的最终结果都回来、上屏完（pending 归零）再执行回车。
     * 曾经：带下划线的临时文字原样定下来，还在路上的段丢掉，马上回车。最多等 ENTER_WAIT_MS，网络卡住也不会一直不发。
     * 只等按回车之前的段（enterUpToSeq）：回车后马上又开录说的是下一条，不能等它、更不能跟这条一起发出去。
     * 等的期间输入框换了（onFinishInput）就不回车了：不能对别的框执行发送。
     */
    private fun pressEnter() {
        if (enterAfterFinish) return
        if (recording) stopRecording(discard = false)
        enterAfterFinish = true
        enterUpToSeq = Int.MAX_VALUE
        // 录音线程交出的残余段是 post 到主线程的，排在它后面再记序号，才算得上它
        root.post {
            if (!enterAfterFinish) return@post
            enterUpToSeq = seq
            if (doneSeq >= enterUpToSeq) finishEnter()
        }
        root.postDelayed(enterTimeout, ENTER_WAIT_MS)
    }

    private var enterAfterFinish = false
    private var enterUpToSeq = 0       // 按回车时最后一段的序号：它和它之前的都回来了才回车
    private var doneSeq = 0            // 最近回来的段序号。识别单线、按提交顺序回来，所以 ≤ 它的都回来了

    private fun cancelEnter() {
        if (!enterAfterFinish) return     // 按过回车才有 root（onFinishInput 可能在输入法界面还没建时就来）
        enterAfterFinish = false
        root.removeCallbacks(enterTimeout)
    }
    private val enterTimeout = Runnable {
        if (enterAfterFinish) { Log.w(TAG, "回车等最终结果超时，先发出去"); finishEnter() }
    }

    private fun finishEnter() {
        enterAfterFinish = false
        root.removeCallbacks(enterTimeout)
        // 还没被最终结果替换的临时文字（超时、或这段认出来是空的）原样定下来，不擦
        if (composingSeg != -1L) {
            currentInputConnection?.finishComposingText()
            if (composingText.isNotEmpty()) noteCommitted(composingText, composingHeld, "")
            composingSeg = -1L; composingText = ""
        }
        if (streamMode == "confirm" && previewLive.isNotEmpty()) {
            previewParts += previewLive to null; previewLive = ""; previewSeg = -1L
        }
        commitPreview()     // 预览模式：攒在气泡里的先上屏
        val info = currentInputEditorInfo
        val action = editorAction(info)
        val ic = currentInputConnection ?: return
        if (action != null) ic.performEditorAction(action) else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun editorAction(info: android.view.inputmethod.EditorInfo?): Int? {
        if (info == null) return null
        if (info.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        val a = info.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
        return if (a == android.view.inputmethod.EditorInfo.IME_ACTION_NONE || a == android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) null else a
    }

    /** 回车键按输入框声明的动作换图标（和 Gboard 一样）：文字要翻九种语言、长词放不进小方块，图标不用。
     *  读屏念对应的字。图标是键边长的一半，跟球一起缩放；颜色跟编辑条文字一致，阿拉伯语下箭头自动翻转。 */
    private fun updateEnterLabel() {
        if (!::enterKey.isInitialized) return
        val (icon, desc) = when (editorAction(currentInputEditorInfo)) {
            android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> R.drawable.ic_key_send to R.string.key_send
            android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_key_search to R.string.key_search
            android.view.inputmethod.EditorInfo.IME_ACTION_GO -> R.drawable.ic_key_go to R.string.key_go
            android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> R.drawable.ic_key_done to R.string.key_done
            android.view.inputmethod.EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_key_next to R.string.key_next
            else -> R.drawable.ic_key_return to R.string.cd_enter
        }
        val d = ContextCompat.getDrawable(this, icon)!!.mutate().apply { setTint(enterKey.currentTextColor) }
        val size = dp((keyDp() * 0.5f).roundToInt())
        enterKey.text = ""
        enterKey.foreground = android.graphics.drawable.LayerDrawable(arrayOf(d)).apply {
            setLayerSize(0, size, size); setLayerGravity(0, Gravity.CENTER)
        }
        enterKey.contentDescription = getString(desc)
    }

    private fun toggleStrip() {
        settings.stripOpen = !settings.stripOpen
        refreshPunctLabels(); buzz(); setOrbState(); layoutOrb()
    }

    /** 编辑条的位置：球和卫星的下面、水平居中于球，放不下就放到它们上面。
     *  要躲开卫星：贴边时卫星排成一段弧，最下面那颗（切回键盘）比球底还低，只躲球会把它盖住。 */
    private fun stripTarget(x: Int, y: Int): Pair<Float, Float> {
        strip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = strip.measuredWidth; val h = strip.measuredHeight
        val size = dp(orbSizeDp); val cx = x + size / 2; val cy = y + size / 2
        val r = orb.orbRadius; val gap = dp(8)
        val sats = satelliteTargets(x, y)
        val half = dp(satDp) / 2f; val visHalf = dp(satVisibleDp) / 2f   // 卫星 View 比看得见的小圆大一圈
        val lowest = maxOf(cy + r, sats.maxOf { it.second + half + visHalf })
        val highest = minOf(cy - r, sats.minOf { it.second + half - visHalf })
        var sy = lowest + gap
        if (sy + h > root.height - dp(8)) sy = highest - gap - h
        val sx = (cx - w / 2).coerceIn(dp(4), maxOf(dp(4), root.width - w - dp(4)))
        return sx.toFloat() to sy.coerceAtLeast(0f)
    }

    private fun satellite(icon: Int, desc: Int, onClick: () -> Unit): ImageButton {
        val inset = dp((satDp - satVisibleDp) / 2)
        val bg = MaterialColors.getColor(themed, com.google.android.material.R.attr.colorSecondaryContainer, 0xFF424242.toInt())
        val fg = MaterialColors.getColor(themed, com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFFFFFFFF.toInt())
        val b = ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = getString(desc)   // 读屏念的名字
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER   // 默认 CENTER 不缩放，内边距白设
            imageTintList = ColorStateList.valueOf(fg)
            background = InsetDrawable(ContextCompat.getDrawable(context, R.drawable.side_btn_bg), inset)
            backgroundTintList = ColorStateList.valueOf(bg)
            alpha = minOf(0.7f, settings.orbAlpha)
            val p = inset + dp(6 * satVisibleDp / 22)   // 可见小圆里放约一半大的图标
            setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        }
        root.addView(b, FrameLayout.LayoutParams(dp(satDp), dp(satDp)))
        return b
    }

    /** 球贴在哪边：决定卫星往哪个方向展开 */
    private enum class Side { LEFT, RIGHT, CENTER }
    private fun sideOf(centerX: Int): Side = when {
        centerX < root.width / 3 -> Side.LEFT
        centerX > root.width * 2 / 3 -> Side.RIGHT
        else -> Side.CENTER
    }

    /** 贴靠目标：左边、右边、屏幕中线三者里离得最近的（球心 x） */
    private fun snapTargets(): List<Int> {
        val r = orb.orbRadius.roundToInt(); val m = dp(settings.edgeMarginDp)
        return listOf(m + r, root.width / 2, root.width - m - r)
    }

    /** 圆球放到记住的位置（第一次贴右边靠下），卫星环绕它，提示条在它上面 */
    private fun layoutOrb() {
        if (root.width == 0 || dragging) return
        val size = dp(orbSizeDp)
        var x = settings.orbX; var y = settings.orbY
        val pad = dp(orbPadDp)
        if (!settings.hasOrbPos) {
            x = snapTargets().last() - size / 2
            y = root.height - size + pad - dp(140)
        }
        x = nearestSnapX(x + size / 2) - size / 2          // 贴边距离改了、折叠屏展开了，都重新贴
        x = x.coerceIn(-pad, root.width - size + pad); y = y.coerceIn(-pad, root.height - size + pad)
        orb.x = x.toFloat(); orb.y = y.toFloat()
        placeSatellites(x, y)

        // 球上方从下往上：（居中贴靠时先让过卫星）预览气泡、提示
        var top = y + pad
        if (sideOf(x + size / 2) == Side.CENTER) top -= dp(satDp) + dp(6)
        if (::preview.isInitialized && preview.visibility == View.VISIBLE) {
            preview.measure(View.MeasureSpec.makeMeasureSpec(root.width - dp(24), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
            preview.x = (x + size / 2 - preview.measuredWidth / 2).coerceIn(dp(12), maxOf(dp(12), root.width - preview.measuredWidth - dp(12))).toFloat()
            preview.y = (top - preview.measuredHeight - dp(8)).coerceAtLeast(0).toFloat()
            top = preview.y.toInt()
        }
        hint.measure(0, 0)
        hint.x = (x + size / 2 - hint.measuredWidth / 2).coerceIn(0, root.width - hint.measuredWidth).toFloat()
        hint.y = (top - hint.measuredHeight - dp(8)).coerceAtLeast(0).toFloat()
        root.requestLayout()
    }

    private fun nearestSnapX(centerX: Int): Int = snapTargets().minBy { abs(it - centerX) }

    /** 显隐带淡入淡出；alphaOn 是可见时的透明度。
     *  "该不该隐藏"记在 tag 里，淡出的收尾只在此刻仍该隐藏时才真的设 GONE——
     *  否则淡出没完又淡入（点下开录 → 马上拖动退出录音）时，旧的收尾会把它设没了。 */
    private fun fadeTo(v: View, visible: Boolean, alphaOn: Float) {
        v.tag = visible
        if (visible) {
            if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.visibility = View.VISIBLE }
            v.animate().alpha(alphaOn).setDuration(150).withEndAction(null).start()
        } else if (v.visibility == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(150).withEndAction { if (v.tag == false) v.visibility = View.GONE }.start()
        }
    }

    private val satellites get() = listOf(keyboardBtn, undoBtn, editBtn, settingsBtn)

    /** 四颗卫星在球周围的一段弧上：贴左边就往右展开，贴右边往左，居中往上。返回各自的左上角坐标 */
    private fun satelliteTargets(x: Int, y: Int): List<Pair<Float, Float>> {
        val size = dp(orbSizeDp)
        val cx = x + size / 2; val cy = y + size / 2
        val orbit = orb.orbRadius + dp(satVisibleDp / 2) + dp(10)
        val center = when (sideOf(cx)) { Side.LEFT -> 0.0; Side.RIGHT -> 180.0; Side.CENTER -> -90.0 }   // 度，0=右，-90=上
        return listOf(center - 60, center - 20, center + 20, center + 60).map { deg ->
            val a = Math.toRadians(deg)
            (cx + orbit * cos(a) - dp(satDp) / 2).toFloat() to (cy + orbit * sin(a) - dp(satDp) / 2).toFloat()
        }
    }

    /** 跟着球走的东西：卫星 + 编辑条，和它们各自的目标位置 */
    private fun followers(x: Int, y: Int): List<Pair<View, Pair<Float, Float>>> =
        satellites.zip(satelliteTargets(x, y)) + listOf(strip to stripTarget(x, y))

    private fun placeSatellites(x: Int, y: Int) {
        for ((v, p) in followers(x, y)) { v.x = p.first; v.y = p.second }
    }

    /**
     * 拖完松手：球和三颗卫星一起滑到贴靠位，记住。
     * 用一个 ValueAnimator 每帧直接写四个 View 的坐标，不用各 View 自己的属性动画——
     * 那套和淡入淡出共用一个 animator，互相打断过（卫星不滑、设置钮消失都是它）。
     */
    private var snapAnim: ValueAnimator? = null
    private fun snapOrb() {
        val size = dp(orbSizeDp)
        val toX = (nearestSnapX((orb.x + size / 2).roundToInt()) - size / 2).toFloat()
        val toY = orb.y
        settings.orbX = toX.roundToInt(); settings.orbY = toY.roundToInt()
        val f = followers(toX.roundToInt(), toY.roundToInt())
        val views = f.map { it.first } + orb
        val from = views.map { it.x to it.y }
        val to = f.map { it.second } + (toX to toY)
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = android.view.animation.OvershootInterpolator(1.1f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                for ((i, v) in views.withIndex()) {
                    v.x = from[i].first + (to[i].first - from[i].first) * t
                    v.y = from[i].second + (to[i].second - from[i].second) * t
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    snapAnim = null; dragging = false          // 拖动结束才恢复"只有球和卫星可点"
                    layoutOrb(); updateInputViewShown()
                }
            })
            start()
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::root.isInitialized) return   // 系统可能在 onCreateInputView 之前就来问一次
        val h = root.height
        outInsets.contentTopInsets = h          // 应用不要为我们让位置
        outInsets.visibleTopInsets = h
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()
        if (dragging) { outInsets.touchableRegion.set(0, 0, root.width, root.height); return }
        for (v in listOfNotNull(orb, hint, strip, if (::preview.isInitialized) preview else null) + satellites) {
            if (v.visibility != View.VISIBLE) continue
            val r = Rect(); v.getHitRect(r); r.inset(-dp(6), -dp(6))
            outInsets.touchableRegion.union(r)
        }
    }

    override fun onEvaluateFullscreenMode() = false
    @android.annotation.SuppressLint("MissingSuperCall")   // 故意不调 super：它在接了物理键盘时返回 false
    override fun onEvaluateInputViewShown() = true     // 接了物理键盘也要显示圆球

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 外部调用替用户切过来的要求只认刚发的（3 秒内）、读一次就清：下面几条提前返回的路（密码框、没麦克风权限）
        // 也不能把它留着，不然以后用户自己切过来，"自动开始"关着也开录
        val external = SystemClock.elapsedRealtime() - externalStartAt < 3000
        externalStartAt = 0L
        if (settings.orbSizeDp != builtOrbDp && !recording && pending == 0) {
            // 圆球大小改了：整套视图按新尺寸重建（firstShow 保住，别把这次当成刚切过来）
            val fs = firstShow
            setInputView(onCreateInputView())
            firstShow = fs
        }
        applyOrbStyle(); updateEnterLabel(); refreshPunctLabels(); setOrbState(); layoutOrb()
        if (voiceUnsuitable(info)) {
            // 密码会被念出来发到电脑、数字框收不了汉字：不录，直接交回上一个键盘
            firstShow = false
            if (recording) stopRecording(discard = true)
            handBackToPreviousKeyboard(hintIfStuck = true)
            return
        }
        if (restarting) return

        // 刚切到本输入法（onCreateInputView 之后第一次显示，或无障碍按钮刚点过）= 想说话
        val justActivated = firstShow ||
            (SystemClock.elapsedRealtime() - lastActivatedElapsed < 3000)
        firstShow = false

        if (justActivated) {
            if (!hasMic()) { showHint(getString(R.string.hint_no_mic), sticky = true); return }
            // 外部调用替用户切过来的：不管"自动开始"开没开都开录
            if ((settings.autoStart || external) && !recording && startRecording() && external) switchedForExternal = true
            return
        }
        // 不是刚切过来，而是本输入法还是当前默认、又打开了输入框：交回上一个键盘
        if (settings.handBackOnReopen && !recording) handBackToPreviousKeyboard()
    }

    private fun handBackToPreviousKeyboard(hintIfStuck: Boolean = false) {
        root.post {
            if (switchToPreviousInputMethod()) return@post
            if (settings.previousIme.isNotEmpty() && VoiceSwitchService.isEnabled(this)) {
                sendBroadcast(Intent(VoiceSwitchService.ACTION_SWITCH_BACK).setPackage(packageName))
            } else if (hintIfStuck) {
                showHint(getString(R.string.hint_field_unsuitable), sticky = true)
            }
        }
    }

    /** 不适合语音的输入框：密码（各种）、纯数字、电话、日期时间 */
    private fun voiceUnsuitable(info: android.view.inputmethod.EditorInfo?): Boolean {
        val t = info?.inputType ?: return false
        val cls = t and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) return true
        val v = t and InputType.TYPE_MASK_VARIATION
        return cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
    }

    /** 输入框没了（应用收起输入法 / 焦点换了）：停录并丢弃还没打出去的——包括停录后还在识别的段，
     *  回来时打进的是新的框（预览模式除外：结果只进气泡）；等着的回车也不执行了（不能对别的框发送）。restartInput 不走这里 */
    override fun onFinishInput() {
        super.onFinishInput()
        if (recording) stopRecording(discard = true)
        if (pending > 0 && streamMode != "confirm") discardUpToSeq = seq   // 预览模式的结果只进气泡、点了才上屏，不会打错框，留着
        cancelEnter()
        clearComposing()    // 带下划线的临时文字不能留在旧输入框里（宁可丢也不打错框）；预览气泡里的保留，点了才上屏
    }

    override fun onDestroy() {
        if (recording) stopRecording(discard = true)
        holdScreen(false)
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ── 外部调用（Tasker 等，ExternalControl）────────────────────
    // 本输入法已经是当前输入法时：只管开始、停止，不切输入法（用户自己切过来的，别打断）。
    // 是外部调用替用户从别的键盘切过来的（switchedForExternal）：停下后等说过的话都上屏，再切回原来的键盘。
    private var switchedForExternal = false
    private var handBackAfterFinish = false
    private val handBackTimeout = Runnable { if (handBackAfterFinish && !recording) finishHandBack() }

    fun external(cmd: String) {
        if (!recording && !handBackAfterFinish && MicStreamService.instance?.pcRecording == true) {
            ExternalControl.remote(this, cmd); return       // 电脑正在用手机当话筒听写：这一下是停它
        }
        val stop = cmd == ExternalControl.STOP || (cmd == ExternalControl.TOGGLE && (recording || handBackAfterFinish))
        if (stop) {
            if (recording) stopRecording(discard = false)
            if (switchedForExternal && !handBackAfterFinish) {
                handBackAfterFinish = true
                // 残余段是录音线程 post 到主线程的，排在它后面再看 pending
                root.post { if (handBackAfterFinish && pending == 0) finishHandBack() }
                root.postDelayed(handBackTimeout, ENTER_WAIT_MS)
            }
            return
        }
        if (recording) return
        if (!isInputViewShown || currentInputConnection == null) {
            ExternalControl.remote(this, cmd); return       // 不在输入框：让电脑听写（先确认一次，防误触）
        }
        startRecording()   // 用户自己切过来的 switchedForExternal 本来就是 false（新实例），外部切过来的留着，停下后切回
    }

    private fun finishHandBack() {
        handBackAfterFinish = false
        switchedForExternal = false
        root.removeCallbacks(handBackTimeout)
        handBackToPreviousKeyboard()
    }

    // ── 触摸：点 / 按住 / 拖 ───────────────────────────────────
    private fun onOrbTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnim?.cancel()                     // 贴靠还没滑完又抓住：先落到位再从那里拖
                downX = ev.rawX; downY = ev.rawY; downAt = SystemClock.uptimeMillis()
                orbStartX = orb.x; orbStartY = orb.y
                dragging = false
                if (recording) {                       // 录音中再按 = 立刻停
                    pressStartedRecording = false
                    stopRecording(discard = false)
                } else {
                    pressStartedRecording = startRecording()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                val slop = ViewConfiguration.get(this).scaledTouchSlop * 2
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    if (pressStartedRecording) { stopRecording(discard = true); pressStartedRecording = false }
                    updateInputViewShown()   // 触发 onComputeInsets → 整窗可触，MOVE 不再被裁
                }
                if (dragging) {
                    val pad = dp(orbPadDp).toFloat()
                    orb.x = (orbStartX + dx).coerceIn(-pad, root.width - orb.width + pad)
                    orb.y = (orbStartY + dy).coerceIn(-pad, root.height - orb.height + pad)
                    placeSatellites(orb.x.roundToInt(), orb.y.roundToInt())
                    updateInputViewShown()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) { snapOrb(); return true }
                val held = SystemClock.uptimeMillis() - downAt >= settings.holdMs
                if (pressStartedRecording && held && recording) stopRecording(discard = false)   // 按住说话：松手即停
                pressStartedRecording = false
                return true
            }
        }
        return false
    }

    // ── 录音 ──────────────────────────────────────────────────
    private fun startRecording(): Boolean {
        if (voiceUnsuitable(currentInputEditorInfo)) { showHint(getString(R.string.hint_field_unsuitable)); return false }
        if (!hasMic()) { showHint(getString(R.string.hint_no_mic), sticky = true); openSettings(); return false }
        // 外部调用停下、正等话上屏后切回原键盘，这时又开录了：先不切（不然录到一半输入法被切走、话全丢），
        // switchedForExternal 留着，下次停下再切回
        if (handBackAfterFinish) { handBackAfterFinish = false; root.removeCallbacks(handBackTimeout) }
        session++
        val mySession = session
        streamMode = settings.streamMode
        // 边说边出字时停顿满 STREAM_SILENCE_MS 才切段：带下划线的字已经在给反馈，不用靠快切；
        // 切得勤，句中一顿就成了"一句话"，模型补句号（"它应该是有。空格的"，用户实测）
        val silence = if (streamMode == "off") settings.silenceMs else maxOf(settings.silenceMs, STREAM_SILENCE_MS)
        recorder = Recorder(silence, settings.maxSegmentMs, settings.speechRms, settings.idleStopMs,
            onSegment = { seg, wav, forced -> root.post { onSegment(wav, mySession, seg, forced) } },
            partialMs = if (streamMode == "off") 0 else PARTIAL_MS,
            onPartial = { seg, wav -> root.post { onPartialAudio(wav, mySession, seg) } },
            onDropped = { seg, quiet -> root.post {
                val k = segKey(mySession, seg); closedSegs += k
                if (quiet) keepShownText(k) else dropSegText(k)   // 太安静：用户看到的字留着；撤销、丢弃：擦
            } },
            onIdle = { root.post { if (recording && session == mySession) { stopRecording(discard = false); showHint(getString(R.string.hint_idle_stopped)) } } },
        ).also { it.start() }
        recording = true
        MicStreamService.pauseForIme(this, true)   // 麦克风模式让开，不然两路采集互相抢
        grabAudioFocus()
        buzz(); hideHint(); setOrbState()
        return true
    }

    /** 录音期间让正在外放的 App 暂停/让路（和系统语音输入一样的做法），停录后它们自己恢复 */
    private fun grabAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        am.requestAudioFocus(req); focusRequest = req
    }

    private fun releaseAudioFocus() {
        focusRequest?.let { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun stopRecording(discard: Boolean) {
        recording = false
        if (discard) { discardSession = session; clearComposing(); clearPreviewLive() }
        recorder?.stop(discard = discard); recorder = null
        MicStreamService.pauseForIme(this, false)
        releaseAudioFocus()
        buzz(); setOrbState()
    }

    private fun onSegment(wav: ByteArray, mySession: Int, seg: Int, forced: Boolean = false) {
        if (mySession == discardSession) return
        val key = segKey(mySession, seg)
        closedSegs += key; pendingFinals += key
        val mySeq = ++seq
        pending++; setOrbState()
        transcriber.submit(wav, Transcriber.SegId("$instanceTag-$mySession", seg)) { r ->
            pending--
            doneSeq = mySeq
            pendingFinals -= key
            if (mySession != discardSession && mySeq > discardUpToSeq) {
                if (r.text != null) {
                    // 硬切的段：模型只听到半句，末尾补的句号逗号不可信，丢掉不扣着
                    deliverFinal(key, if (forced) SegmentJoin.splitTrailing(r.text).first else r.text, r.junction)
                    if (r.via == "备用") showHint(getString(R.string.hint_fallback_used))
                } else {
                    dropSegText(key)
                    if (r.error != null) showHint(r.error, sticky = true)
                }
            } else dropSegText(key)
            setOrbState()
            if (enterAfterFinish && doneSeq >= enterUpToSeq) finishEnter()   // 按过回车：那之前说的都上屏了，现在回车
            if (handBackAfterFinish && pending == 0 && !recording) finishHandBack()   // 外部调用停的：话都上屏了，切回原键盘
        }
    }

    /** 一段的最终结果：confirm 进预览；live 替换掉这段的临时文字；off 直接上屏。
     *  看前一个字决定补不补空格（"worldHow"、"中文English"）；补的空格算进这一句，撤销时一起删。 */
    /** junction：电脑两段合认定的接缝标点。光标还停在我们上一句末尾时，用它替换扣着的段末标点 */
    private fun deliverFinal(key: Long, raw: String, junction: String? = null) {
        var raw = raw
        var acceptedLead: String? = null           // 这段开头编辑器已收下：只打后面的内容，标点用它后面紧跟的（无条件）
        acceptedPrefix.remove(key)?.let { acc ->
            val (lead, rest) = SegmentJoin.afterAccepted(raw, acc)
            if (rest.isEmpty()) return
            raw = rest; acceptedLead = lead
        }
        if (SegmentJoin.splitTrailing(raw).first.isEmpty()) {
            keepShownText(key)      // 最终结果是空：用户已经看到的临时文字别凭空消失（2026-10-03 用户反馈）
            return
        }
        if (streamMode == "confirm") {
            if (previewSeg == key) { previewLive = ""; previewSeg = -1L }
            if (raw.isNotBlank()) previewParts += raw to (acceptedLead ?: junction)   // 带着末尾标点存，拼的时候再定放不放
            updatePreview(); return
        }
        val (body, tail) = SegmentJoin.splitTrailing(raw)
        val ic = currentInputConnection
        if (composingSeg == key) {
            val h = acceptedLead ?: if (junction != null && composingBefore != null && composingBefore == lastOutEnd) junction else composingHeld
            val out = if (body.isEmpty()) "" else SegmentJoin.joinSegment(composingBefore, h, body)
            ic?.commitText(out, 1)          // 替换掉带下划线的临时文字；空字符串 = 擦掉
            composingSeg = -1L; composingText = ""
            if (out.isNotEmpty()) noteCommitted(out, h, tail)
            return
        }
        if (body.isEmpty()) return
        val before = ic?.getTextBeforeCursor(1, 0)?.lastOrNull()
        val h = acceptedLead ?: if (junction != null && before != null && before == lastOutEnd) junction else heldFor(before)
        val out = SegmentJoin.joinSegment(before, h, body)
        ic?.commitText(out, 1)
        noteCommitted(out, h, tail)
    }

    // ── 边说边出字：临时结果 ───────────────────────────────────
    /** 说话中录音器每 ~0.7 秒交来一份"这段到目前为止"的音频：送去识别，回来时还该显示就显示 */
    private fun onPartialAudio(wav: ByteArray, mySession: Int, seg: Int) {
        val key = segKey(mySession, seg)
        if (!partialShowable(key, mySession)) return
        transcriber.partial(wav) { t ->
            if (!t.isNullOrEmpty() && partialShowable(key, mySession)) showPartial(key, t)
        }
    }

    /** 这段的临时结果现在能不能显示：还在录、段没结束、没被丢弃，且前面的段都定下来了（保证顺序） */
    private fun partialShowable(key: Long, mySession: Int) =
        recording && mySession == session && mySession != discardSession && key !in closedSegs && pendingFinals.isEmpty()

    private fun showPartial(key: Long, t: String) {
        val full = SegmentJoin.splitTrailing(t).first      // 临时结果末尾的标点不显示，定下来再说
        if (full.isEmpty()) return
        var body = full; var lead: String? = null
        acceptedPrefix[key]?.let { acc ->                  // 这段开头编辑器已收下：只显示后面的内容
            val (l, rest) = SegmentJoin.afterAccepted(full, acc)
            if (rest.isEmpty()) return
            body = rest; lead = l
        }
        if (streamMode == "confirm") { previewSeg = key; previewLive = body; updatePreview(); return }
        val ic = currentInputConnection ?: return
        if (composingSeg != key) {
            if (composingSeg != -1L) clearComposing()
            composingBefore = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
            composingHeld = lead ?: heldFor(composingBefore); composingSeg = key; composingSpanSeen = false
        }
        composingBody = full
        // 带下划线，后面整段改写；开头放回上一句扣着的标点、补空格、改大小写，和定下来时一样
        composingText = SegmentJoin.joinSegment(composingBefore, composingHeld, body)
        ic.setComposingText(composingText, 1)
    }

    /** 这段最终没认出字（空结果、残余太安静），但用户已经看到它的临时文字：原样定下来，不擦 */
    private fun keepShownText(key: Long) {
        if (composingSeg == key && composingText.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            noteCommitted(composingText, composingHeld, "")
            composingSeg = -1L; composingText = ""
        } else if (previewSeg == key && previewLive.isNotEmpty()) {
            previewParts += previewLive to null; previewLive = ""; previewSeg = -1L; updatePreview()
        } else dropSegText(key)
    }

    /** 这段作废了（撤销、识别失败、丢弃）：把它显示出来的临时文字收掉 */
    private fun dropSegText(key: Long) {
        if (composingSeg == key) clearComposing()
        if (previewSeg == key) clearPreviewLive()
    }

    private fun clearComposing() {
        if (composingSeg == -1L) return
        currentInputConnection?.let { it.setComposingText("", 1); it.finishComposingText() }
        composingSeg = -1L; composingText = ""; composingSpanSeen = false
    }

    // ── 边说边出字：预览模式（confirm）───────────────────────────
    private fun clearPreviewLive() {
        if (previewLive.isEmpty() && previewSeg == -1L) return
        previewLive = ""; previewSeg = -1L; updatePreview()
    }

    /** 预览里的几段按同样规则接起来；返回 (文字, 最后一段扣着的标点)。每段有合认定的接缝标点就用它，
     *  第一段只有在接着我们上一句时（prevIsOurs）才用 */
    private fun joinParts(parts: List<Pair<String, String?>>, prev: Char? = null, prevHeld: String = "",
                          prevIsOurs: Boolean = false): Pair<String, String> {
        val sb = StringBuilder(); var h = prevHeld; var p = prev; var first = true
        for ((raw, junction) in parts) {
            val (b, t) = SegmentJoin.splitTrailing(raw)
            if (b.isEmpty()) continue
            if (junction != null && (!first || prevIsOurs)) h = junction
            sb.append(SegmentJoin.joinSegment(p, h, b)); h = t; p = b.last(); first = false
        }
        return sb.toString() to h
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return
        val (done, doneHeld) = joinParts(previewParts)
        if (done.isEmpty() && previewLive.isEmpty()) {
            fadeTo(preview, false, 1f); layoutOrb(); updateInputViewShown(); return
        }
        val sb = android.text.SpannableStringBuilder(done)
        if (previewLive.isNotEmpty()) {     // 还没定下来的部分加下划线（前面放回的标点和空格不加）
            val live = SegmentJoin.joinSegment(done.lastOrNull(), doneHeld, previewLive)
            sb.append(live)
            sb.setSpan(android.text.style.UnderlineSpan(), sb.length - previewLive.length, sb.length, 0)
        }
        val shown = if (sb.length > PREVIEW_MAX_CHARS)
            android.text.SpannableStringBuilder("…").append(sb.subSequence(sb.length - PREVIEW_MAX_CHARS, sb.length)) else sb
        shown.append("  ✓")
        preview.text = shown
        fadeTo(preview, true, 1f); layoutOrb(); updateInputViewShown()
    }

    /** 点预览气泡：已定下的文字上屏（正在说的那段等它定下来再进预览） */
    private fun commitPreview() {
        if (previewParts.isEmpty()) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
        val h = heldFor(before)
        val (out, tail) = joinParts(previewParts, before, h, before != null && before == lastOutEnd)   // 最后一段的末尾标点扣着
        if (out.isNotEmpty()) { ic.commitText(out, 1); noteCommitted(out, h, tail) }
        previewParts.clear(); updatePreview()
    }

    // ── 状态与反馈 ────────────────────────────────────────────
    private fun setOrbState() {
        if (!::orb.isInitialized) return
        orb.state = when {
            recording -> OrbView.State.RECORDING
            pending > 0 -> OrbView.State.BUSY
            !hasMic() -> OrbView.State.ERROR
            else -> OrbView.State.IDLE
        }
        val idle = !recording && pending == 0
        orb.contentDescription = getString(if (recording) R.string.cd_orb_stop else R.string.cd_orb_start)
        val a = minOf(0.7f, settings.orbAlpha)
        fadeTo(keyboardBtn, true, a)    // 常驻：录音中也能一键切回键盘
        fadeTo(settingsBtn, idle, a)
        fadeTo(undoBtn, true, a)        // 常驻：录音中也能撤销
        fadeTo(editBtn, true, a)
        fadeTo(strip, settings.stripOpen, minOf(0.85f, settings.orbAlpha + 0.1f))
        if (idle && switchWhenIdle) { switchWhenIdle = false; doSwitchToKeyboard() }
        // 录音中、还有段在识别时不息屏：一口气说很久时屏幕灭了，输入框收起，录音就被停了
        root.keepScreenOn = !idle
        holdScreen(!idle)
        updateInputViewShown()
    }

    // 输入法窗口上的 keepScreenOn 在三星（Fold，安卓 17）上没生效：说到一半照样灭屏（2026-11-03 用户）。
    // 再拿一把亮屏锁，不依赖窗口；录完、认完就放。每次状态刷新续 10 分钟，忘了放也会自己放
    private var screenLock: android.os.PowerManager.WakeLock? = null
    private fun holdScreen(on: Boolean) {
        if (on) {
            val lock = screenLock ?: run {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                @Suppress("DEPRECATION")   // 推荐的窗口 FLAG_KEEP_SCREEN_ON 在输入法窗口上不管用
                pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ON_AFTER_RELEASE,
                    "shuohuaa:dictating").also { it.setReferenceCounted(false); screenLock = it }
            }
            lock.acquire(10 * 60_000L)
        } else {
            screenLock?.let { if (it.isHeld) it.release() }
        }
    }

    private val hideHintRunnable = Runnable { hideHint() }
    private fun showHint(text: String, sticky: Boolean = false) {
        hint.text = text; fadeTo(hint, true, 1f); layoutOrb()
        hint.removeCallbacks(hideHintRunnable)
        if (!sticky) hint.postDelayed(hideHintRunnable, 3000)
        updateInputViewShown()
    }
    private fun hideHint() { fadeTo(hint, false, 1f); updateInputViewShown() }

    private fun buzz() {
        if (!settings.haptic) return
        val v = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                  else VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * 撤销 = 快速修正：正在识别的作废、正在说的这段丢掉、刚上屏的那句删掉。
     * 录音不停，可以接着重说。删上屏文字只在光标前确实还是那句时才删，
     * 免得删掉用户后来自己打的字。
     */
    private fun undoLast() {
        var did = false
        if (pending > 0) { discardUpToSeq = seq; did = true }
        if (recording) {
            recorder?.let { closedSegs += segKey(session, it.currentSeg); it.dropCurrent() }   // 在路上的临时结果别再冒出来
            did = true
        }
        // 眼前有带下划线的临时文字：这次撤销只撤它（用户看到的"最后一句"就是它），再按一次才撤上一句
        val hadLive = composingSeg != -1L || previewLive.isNotEmpty()
        clearComposing(); clearPreviewLive()        // 先擦临时文字，不然下面比对光标前的文字会对不上
        val last = lastCommitted
        val ic = currentInputConnection
        if (hadLive) {
            did = true
        } else if (streamMode == "confirm" && previewParts.isNotEmpty()) {
            previewParts.removeAt(previewParts.size - 1); updatePreview(); did = true   // 预览里撤最后一段
        } else if (last != null && ic != null) {
            val before = ic.getTextBeforeCursor(last.length, 0)?.toString()
            if (before == last) {
                ic.deleteSurroundingText(last.length, 0); did = true
                held = lastConsumedHeld; lastConsumedHeld = ""        // 这句开头放回去的标点重新扣着
                lastOutEnd = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
            }
            else showHint(getString(R.string.hint_undo_mismatch))
            lastCommitted = null
        }
        if (did) { buzz(); showHint(getString(if (recording) R.string.hint_undone_continue else R.string.hint_undone)) }
        else showHint(getString(R.string.hint_nothing_to_undo))
        setOrbState()
    }

    // ── 切键盘 / 设置 ─────────────────────────────────────────
    private fun switchToKeyboard() {
        if (recording || pending > 0) {
            // 先停录，让残余段识别、上屏，打完（setOrbState 里检测到空闲）再切
            switchWhenIdle = true
            if (recording) stopRecording(discard = false)
            showHint(getString(R.string.hint_switch_after))
            return
        }
        doSwitchToKeyboard()
    }

    private fun doSwitchToKeyboard() {
        commitPreview()     // 预览里攒的别因为切键盘丢了
        if (switchToPreviousInputMethod()) return
        if (settings.previousIme.isNotEmpty() && VoiceSwitchService.isEnabled(this)) {
            sendBroadcast(Intent(VoiceSwitchService.ACTION_SWITCH_BACK).setPackage(packageName))
            return
        }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        /** 边说边出字：说话中每隔这么久送一份临时音频去识别（实测 8 秒音频识别 0.42 秒，显卡扛得住） */
        private const val PARTIAL_MS = 700
        /** 边说边出字时，停顿满这么久才切段（设置里的"静音多久切一段"更长就用设置的） */
        private const val STREAM_SILENCE_MS = 1000
        private const val PREVIEW_MAX_CHARS = 140   // 预览气泡太长只显示末尾
        private const val TAG = "shuohuaa.ime"
        // 按了回车最多等这么久让在路上的段回来（正常 1 秒内），网络卡住也不会一直不发
        private const val ENTER_WAIT_MS = 8000L
        // 无障碍服务把我们切成当前输入法时记一下，onStartInputView 据此判定"刚激活"
        @Volatile var lastActivatedElapsed = 0L
        /** 正在运行的输入法（外部调用找它）；系统没把我们拉起来时是 null */
        @Volatile var instance: VoiceInputService? = null
        /** 外部调用替用户切到本输入法、要求一切过来就开录（不管"自动开始"）：记下时刻（elapsedRealtime），只认 3 秒内的 */
        @Volatile var externalStartAt = 0L
    }
}
