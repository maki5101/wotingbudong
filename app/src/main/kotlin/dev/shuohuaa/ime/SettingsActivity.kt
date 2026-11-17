package dev.shuohuaa.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as SysSettings
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlin.concurrent.thread

/**
 * 设置页：顶部标签分 5 页（开始使用 / 识别服务 / 说话与出字 / 外观 / 电脑麦克风），点标签切换、不能左右滑——
 * 2026-10-05 用户：全挤在一页太长，一滑特别容易点错，改错了还没法撤回。
 * 防误触：任何一项改了，底部弹"已修改：xxx · 撤销"（换服务商连带改地址和模型，算一次，一起撤）；
 * "说话与出字""外观"页底有"恢复本页默认设置"（先确认，恢复后也能撤销）。识别服务、电脑麦克风页不放：
 * 那里是用户自己的地址和密钥，恢复默认等于清空。
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var container: FrameLayout
    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        Settings(this)   // 先做升级迁移，再显示选项（选项一显示就会把默认值存进去）
        // 各页的默认值先一次存好：不然第一次点开某一页时，选项把默认值写进存储，被当成"改了"弹出撤销提示（审查发现）
        for ((_, xml) in PAGES) PreferenceManager.setDefaultValues(this, xml, true)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        snapshot = HashMap(prefs.all)

        val tabs = TabLayout(this).apply {
            tabMode = TabLayout.MODE_SCROLLABLE
            for ((title, _) in PAGES) addTab(newTab().setText(title))
        }
        container = FrameLayout(this).apply { id = R.id.settings_page }   // 固定 id：重建时片段按它找回容器
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // 新系统（Android 15 起，目标版本 35）强制内容铺满到状态栏底下，主题又没有标题栏：最上面的东西压在状态栏下面
        // （2026-10-04 用户 Android 17 实测）。整页留出系统栏和挖孔的位置
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        tab = savedInstanceState?.getInt("tab") ?: 0
        tabs.getTabAt(tab)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { tab = t.position; showTab() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        if (savedInstanceState == null) showTab()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    private fun showTab() {
        supportFragmentManager.beginTransaction()
            .replace(container.id, Page.newInstance(PAGES[tab].second))
            .commit()
    }

    // ── 撤销 ──────────────────────────────────────────────────
    private var snapshot: Map<String, Any?> = emptyMap()   // 每一项上次的值，改了才知道改之前是什么
    private val batch = LinkedHashMap<String, Any?>()      // 这一次（一小段时间内连着改的）每一项改之前的值
    private var batchAt = 0L
    private var batchMessage: String? = null
    private var undoing = false

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == null || key !in UNDOABLE) { snapshot = HashMap(p.all); return@OnSharedPreferenceChangeListener }
        val before = snapshot[key]
        snapshot = HashMap(p.all)
        if (undoing) return@OnSharedPreferenceChangeListener
        val now = SystemClock.elapsedRealtime()
        if (now - batchAt > BATCH_MS) { batch.clear(); batchMessage = null; batchAt = now }
        if (key !in batch) batch[key] = before
        container.removeCallbacks(showUndo)
        container.postDelayed(showUndo, BATCH_MS)
    }

    private val showUndo = Runnable {
        if (batch.isEmpty()) return@Runnable
        val saved = LinkedHashMap(batch)
        val page = supportFragmentManager.findFragmentById(container.id) as? PreferenceFragmentCompat
        val msg = batchMessage ?: if (saved.size == 1) {
            val title = page?.findPreference<Preference>(saved.keys.first())?.title ?: saved.keys.first()
            getString(R.string.changed_one, title)
        } else getString(R.string.changed_many, saved.size)
        Snackbar.make(container, msg, UNDO_SHOW_MS)
            .setAction(R.string.undo) { undo(saved) }
            .show()
    }

    private fun undo(saved: Map<String, Any?>) {
        undoing = true
        val e = prefs.edit()
        for ((k, v) in saved) {
            when (v) {
                null -> e.remove(k)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is String -> e.putString(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
        }
        e.commit()
        undoing = false
        batch.clear()
        showTab()   // 选项不会自己跟着存储变，重画这一页
    }

    /** 恢复本页默认：把本页能改的项都删掉（显示时回到默认值），弹撤销 */
    fun restoreDefaults(page: PreferenceFragmentCompat) {
        val keys = ArrayList<String>()
        fun walk(g: PreferenceGroup) {
            for (i in 0 until g.preferenceCount) {
                val p = g.getPreference(i)
                if (p is PreferenceGroup) walk(p)
                else if (p.key != null && p.key in UNDOABLE) keys += p.key
            }
        }
        walk(page.preferenceScreen)
        batch.clear(); batchAt = SystemClock.elapsedRealtime()
        batchMessage = getString(R.string.restored)
        val e = prefs.edit()
        keys.forEach { e.remove(it) }
        e.commit()
        showTab()
    }

    override fun onResume() { super.onResume(); prefs.registerOnSharedPreferenceChangeListener(listener) }
    override fun onPause() { prefs.unregisterOnSharedPreferenceChangeListener(listener); super.onPause() }

    /** 一个标签页 */
    class Page : PreferenceFragmentCompat() {
        private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshMic() }
        private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(requireArguments().getInt(ARG_XML), rootKey)
            val ctx = requireContext()
            findPreference<Preference>("enable_ime")?.setOnPreferenceClickListener {
                startActivity(Intent(SysSettings.ACTION_INPUT_METHOD_SETTINGS)); true
            }
            findPreference<Preference>("pick_ime")?.setOnPreferenceClickListener {
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker(); true
            }
            findPreference<Preference>("mic")?.setOnPreferenceClickListener {
                askMic.launch(Manifest.permission.RECORD_AUDIO); true
            }
            // 快速切换（无障碍服务）开关：关 = 服务自己关掉（disableSelf，安卓允许）；开 = 安卓不许应用自己打开无障碍服务，
            // 跳到系统里这个服务的详情页让用户开（2026-10-07 用户：设置里关不掉它，曾经只是一行字、点了跳系统总列表）
            findPreference<androidx.preference.SwitchPreferenceCompat>("a11y")?.setOnPreferenceChangeListener { _, v ->
                val svc = VoiceSwitchService.instance
                if (v == false && svc != null) {
                    svc.disableSelf(); refreshA11y(off = true); return@setOnPreferenceChangeListener true
                }
                openAccessibilitySettings(); false
            }
            findPreference<Preference>("a11y_button")?.setOnPreferenceClickListener {
                // 系统没有直达“无障碍按钮”页的公开 Intent，退到无障碍总页
                startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)); true
            }
            findPreference<androidx.preference.SwitchPreferenceCompat>("mic_mode")?.setOnPreferenceChangeListener { _, v ->
                if (v == true) {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(ctx, R.string.toast_grant_mic, Toast.LENGTH_SHORT).show(); askMic.launch(Manifest.permission.RECORD_AUDIO); return@setOnPreferenceChangeListener false
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    MicStreamService.start(ctx)
                } else MicStreamService.stop(ctx)
                true
            }
            findPreference<androidx.preference.SwitchPreferenceCompat>("mic_muted")?.setOnPreferenceChangeListener { _, v ->
                if (MicStreamService.isRunning) MicStreamService.setMuted(ctx, v == true); true
            }
            // 换服务商：填上它的默认地址和模型，API Key 清空——留着的话旧服务商的 key 会发给新的服务商（全局约定：
            // key 只发给它自己的服务商，审查发现）。说话啊、其他没有默认地址：原来填的是别家的默认地址就清空。
            // 选的还是原来那个就什么都不动（不然自己改过的地址、模型被默认值盖掉）。三项一起改，算一次撤销
            for ((provKey, urlKey, modelKey, keyKey) in listOf(listOf("provider", "server", "model", "token"),
                                                        listOf("fallback_provider", "fallback", "fallback_model", "fallback_key"))) {
                findPreference<androidx.preference.ListPreference>(provKey)?.setOnPreferenceChangeListener { pref, v ->
                    if (v == (pref as androidx.preference.ListPreference).value) return@setOnPreferenceChangeListener true
                    findPreference<androidx.preference.EditTextPreference>(keyKey)?.text = ""
                    val info = Providers.info(v as String)
                    val url = findPreference<androidx.preference.EditTextPreference>(urlKey)
                    val model = findPreference<androidx.preference.EditTextPreference>(modelKey)
                    val known = listOf(Providers.OPENAI, Providers.GROQ, Providers.SILICONFLOW).map { Providers.info(it) }
                    if (info.url.isNotEmpty() || known.any { it.url == url?.text }) url?.text = info.url
                    if (info.model.isNotEmpty() || known.any { it.model == model?.text }) model?.text = info.model
                    true
                }
            }
            findPreference<Preference>("test")?.setOnPreferenceClickListener {
                Toast.makeText(ctx, R.string.toast_connecting, Toast.LENGTH_SHORT).show()
                thread {
                    val r = Transcriber(Settings(ctx)).health()
                    activity?.runOnUiThread { Toast.makeText(ctx, r, Toast.LENGTH_LONG).show() }
                }
                true
            }
            findPreference<Preference>("restore_defaults")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.restore_defaults_title)
                    .setMessage(R.string.restore_confirm)
                    .setPositiveButton(R.string.restore_ok) { _, _ -> (activity as? SettingsActivity)?.restoreDefaults(this) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }

        override fun onResume() {
            super.onResume(); refreshMic(); refreshA11y()
            // 通知栏点了"停止"/"静音"后回来，开关要和实际状态对上
            findPreference<androidx.preference.SwitchPreferenceCompat>("mic_mode")?.isChecked = MicStreamService.isRunning
            findPreference<androidx.preference.SwitchPreferenceCompat>("mic_muted")?.isChecked = Settings(requireContext()).micMuted
        }

        private fun refreshA11y(off: Boolean = false) {
            val on = !off && VoiceSwitchService.isEnabled(requireContext())
            val p = findPreference<androidx.preference.SwitchPreferenceCompat>("a11y") ?: return
            p.isChecked = on
            p.summary = getString(if (on) R.string.a11y_on else R.string.a11y_off)
        }

        /** 系统里本服务的详情页（Android 12 起有，直接到开关）；没有就到无障碍总页 */
        private fun openAccessibilitySettings() {
            val ctx = requireContext()
            val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, android.content.ComponentName(ctx, VoiceSwitchService::class.java).flattenToString())
            try { startActivity(detail) } catch (e: Exception) { startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        private fun refreshMic() {
            val ok = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            findPreference<Preference>("mic")?.summary = getString(if (ok) R.string.mic_granted else R.string.mic_denied)
        }

        companion object {
            private const val ARG_XML = "xml"
            fun newInstance(xml: Int) = Page().apply { arguments = Bundle().apply { putInt(ARG_XML, xml) } }
        }
    }

    companion object {
        private val PAGES get() = listOf(
            R.string.tab_start to R.xml.prefs_start,
            R.string.tab_service to R.xml.prefs_service,
            R.string.tab_voice to R.xml.prefs_voice,
            R.string.tab_look to R.xml.prefs_look,
            R.string.tab_mic to R.xml.prefs_mic,
        )
        /** 连着改的算一次：换服务商时地址和模型跟着变，这么久内的改动合成一条撤销 */
        private const val BATCH_MS = 600L
        private const val UNDO_SHOW_MS = 6000
        /** 能撤销、能恢复默认的项：用户在设置页里改的值。麦克风模式的开关、静音不算（它们是在开关服务，撤销只改存储没用） */
        private val UNDOABLE = setOf(
            "bubble",
            "provider", "server", "token", "model", "fallback_provider", "fallback", "fallback_key", "fallback_model",
            "stream_mode", "remove_fillers", "hold_ms", "silence_ms", "rms_threshold", "max_segment_s", "idle_stop_s",
            "auto_start", "hand_back", "haptic",
            "orb_color", "orb_size", "orb_alpha", "edge_margin",
            "mic_host", "mic_token", "mic_port", "mic_idle_min",
        )
    }
}
