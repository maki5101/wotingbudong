package dev.shuohuaa.ime

import android.content.Context
import androidx.preference.PreferenceManager

/** 所有可调项一处读，默认值也在这。键名和 res/xml/prefs_*.xml（设置页 5 个标签页）一致。 */
class Settings(context: Context) {
    /** 取界面文字用（Transcriber 等拿不到 Context 的地方） */
    val res: android.content.res.Resources = context.resources
    private val p = PreferenceManager.getDefaultSharedPreferences(context)

    init {
        // 升级到 0.5 一次性迁移，用专门的标记记做没做过——不能看"有没有 mic_host 这一项"：设置页的选项一显示就会把
        // 默认值存进去，先开设置页再起输入法时迁移就被跳过了，麦克风模式的电脑地址成了空的（2026-10-04 两台手机都中招）
        if (!p.getBoolean(MIGRATED_05, false)) {
            val e = p.edit()
            // 每个识别服务有"服务商"：主服务原来就是电脑 → 说话啊；备用原来是"OpenAI 兼容地址" → 按域名认，认不出算"其他"
            e.putString("provider", Providers.SHUOHUAA).putString("fallback_provider", Providers.guess(fallback))
            // 麦克风模式有自己的电脑地址和口令（以前借用识别服务的；识别服务可以填云服务了，再借会连错、还会把云服务的
            // API Key 发给电脑）。原来识别服务里填的就是电脑，复制一份过去；已经填了的不动
            val host = try { java.net.URI(server).host } catch (x: Exception) { null }
            if (host != null && p.getString("mic_host", "").isNullOrEmpty()) {
                e.putString("mic_host", host).putString("mic_token", token)
            }
            e.putBoolean(MIGRATED_05, true).apply()
        }
    }

    // 识别服务：主服务、备用，都是 OpenAI 兼容接口，每个填 服务商 + 地址 + API Key + 模型（见 Providers、Transcriber）。
    // 键名沿用旧版（server/token 原来是"电脑地址/口令"，fallback* 原来是"备用 OpenAI 地址"），升级不用重填
    val provider: String get() = p.getString("provider", Providers.SHUOHUAA)!!
    val server: String get() = p.getString("server", DEFAULT_SERVER)!!.trim().trimEnd('/')
    val token: String get() = p.getString("token", "")!!.trim()
    val model: String get() = p.getString("model", "")!!.trim()
    val fallbackProvider: String get() = p.getString("fallback_provider", Providers.CUSTOM)!!
    val fallback: String get() = p.getString("fallback", "")!!.trim()
    val fallbackKey: String get() = p.getString("fallback_key", "")!!.trim()
    val fallbackModel: String get() = p.getString("fallback_model", "")!!.trim()
    val holdMs: Long get() = p.getInt("hold_ms", 300).toLong()
    val silenceMs: Int get() = p.getInt("silence_ms", 300)
    /** 说话判定的帧 RMS（float 标度），设置里存的是千分之几 */
    val speechRms: Double get() = p.getInt("rms_threshold", 12) / 1000.0
    /** 一段最长几秒（到了在音量低谷切），和电脑端 MAX_BUFFER_SECONDS 对应 */
    val maxSegmentMs: Int get() = p.getInt("max_segment_s", 8) * 1000
    /** 连续这么久没人说话就自动停录（毫秒），0 = 不停 */
    val idleStopMs: Int get() = p.getInt("idle_stop_s", 15) * 1000
    /** 贴边时球离屏幕边的距离（dp）。系统的返回手势区大约 24dp，比它小就容易误触手势 */
    val edgeMarginDp: Int get() = p.getInt("edge_margin", 6)
    val haptic: Boolean get() = p.getBoolean("haptic", true)
    val autoStart: Boolean get() = p.getBoolean("auto_start", true)
    /** 出字方式：live 边说边出字（带下划线，说完定下）/ confirm 先预览、点了才上屏 / off 一段说完才出字 */
    val streamMode: String get() = p.getString("stream_mode", "live")!!
    /** 去掉语气词（呃、嗯、uh、um）：随请求告诉电脑，默认关（「嗯，好的」里的嗯是有意思的） */
    val removeFillers: Boolean get() = p.getBoolean("remove_fillers", false)
    val handBackOnReopen: Boolean get() = p.getBoolean("hand_back", true)
    val orbColor: String get() = p.getString("orb_color", "follow")!!
    /** 可见圆球直径（dp），卫星按比例跟着缩放 */
    val orbSizeDp: Int get() = p.getInt("orb_size", 56).coerceIn(40, 96)
    val orbAlpha: Float get() = (p.getInt("orb_alpha", 82).coerceIn(30, 100)) / 100f

    // 别的键盘弹出时，要不要在它上方放一个小话筒（默认关：导航栏的无障碍按钮更顺手）
    val bubble: Boolean get() = p.getBoolean("bubble", false)

    // 快速切换前用的是哪个键盘（无障碍服务写，圆球的"键盘"钮切回去用）
    var previousIme: String
        get() = p.getString("previous_ime", "")!!
        set(v) = p.edit().putString("previous_ime", v).apply()

    // 麦克风模式：把手机当电脑的麦克风（MicStreamService）
    var micMode: Boolean
        get() = p.getBoolean("mic_mode", false)
        set(v) = p.edit().putBoolean("mic_mode", v).apply()
    var micMuted: Boolean
        get() = p.getBoolean("mic_muted", false)
        set(v) = p.edit().putBoolean("mic_muted", v).apply()
    val micPort: Int get() = p.getString("mic_port", "9798")!!.trim().toIntOrNull() ?: 9798
    /** 麦克风模式多久没人说话就自动关（分钟，0 = 不关） */
    val micIdleMin: Int get() = p.getInt("mic_idle_min", 10)
    /** 麦克风模式连的电脑：只要主机名/IP；填成 http://100.x.y.z:9797 这样也认，取主机部分 */
    val micHost: String get() {
        val v = p.getString("mic_host", "")!!.trim()
        if (v.isEmpty()) return ""
        return try { java.net.URI(if ("://" in v) v else "tcp://$v").host ?: "" } catch (e: Exception) { "" }
    }
    val micToken: String get() = p.getString("mic_token", "")!!.trim()

    /** Tasker 插件的口令：第一次用时随机生成，插件把它存进 Tasker 的动作里，执行时核对（TaskerPlugin）——
     *  插件的执行入口谁都能发，没口令就等于任何应用都能让我们后台录音。用户看不到也不用管 */
    val externalToken: String get() = p.getString("external_token", null) ?: run {
        val t = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        p.edit().putString("external_token", t).apply()
        t
    }

    // 编辑条开着没有（点卫星切换，记住）
    var stripOpen: Boolean
        get() = p.getBoolean("edit_strip", false)
        set(v) = p.edit().putBoolean("edit_strip", v).apply()

    // 圆球位置（像素）。贴左边时 x 是负的（View 外圈有余量），所以不能拿负数当"没放过"
    val hasOrbPos: Boolean get() = p.contains("orb_x")
    var orbX: Int
        get() = p.getInt("orb_x", 0)
        set(v) = p.edit().putInt("orb_x", v).apply()
    var orbY: Int
        get() = p.getInt("orb_y", 0)
        set(v) = p.edit().putInt("orb_y", v).apply()

    companion object {
        /** 不给默认地址：不内置任何服务（每个人的电脑地址不同，云服务要自己的密钥），留空时识别直接报"还没设地址" */
        const val DEFAULT_SERVER = ""
        private const val MIGRATED_05 = "migrated_0_5"
    }
}
