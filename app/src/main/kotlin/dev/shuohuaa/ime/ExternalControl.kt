package dev.shuohuaa.ime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 外部调用（Tasker 插件，TaskerPlugin）开始/停止语音识别（2026-10-05 用户：Tasker 里长按音量下键开始、再长按停止）。
 * 三种情况（用户定的）：
 *  - 不在输入框里（多半是误触）：一闪而过的提示，什么都不做；
 *  - 当前是别的键盘、在输入框里：切到本输入法并开录（要"快速切换"无障碍服务，安卓只许它替用户切输入法）；
 *    再调一次停下，等说过的话都上屏后切回原来的键盘；
 *  - 本输入法本来就是当前输入法（用户自己切的）：只管开始、停止，不切输入法，不打断原来的用法。
 * 曾经还有个"发广播 + 手填口令"的入口和设置页一栏说明，有了插件就删了（用户：都没用了）。
 *
 * 不在输入框里时（2026-10-30 用户）：让电脑用手机当话筒听写（remote）——第一次只提示"再按一次让电脑开始录音"，
 * 提示还在（CONFIRM_MS）时再按才开始（防误触）；确认过以后长按直接开停，直到麦克风模式关掉（手动、或没人说话 10 分钟
 * 自动关）才重新要确认（MicStreamService.remoteArmed）。电脑在录时再按就停，不用确认；录着的时候屏幕顶上挂"● 电脑录音中"。电脑在不在录由电脑经麦克风
 * 模式那条连接告诉手机（MicStreamService.pcRecording）。麦克风模式没开：快速切换开着就借透明界面开（系统允许有无障碍
 * 服务的应用从后台拉界面），不然发通知点一下开——安卓 14 起后台起不了麦克风服务。
 */
object ExternalControl {
    private const val TAG = "shuohuaa.ext"
    const val TOGGLE = "toggle"
    const val START = "start"
    const val STOP = "stop"
    private const val QUICK_TOAST_MS = 1200L
    private const val CONFIRM_MS = 3000L          // 长按一次就要将近 1 秒，再短来不及按第二下
    private const val REMOTE_CHANNEL = "remote_mic"
    private const val REMOTE_NOTIF_ID = 12
    private var confirmAt = 0L

    /** 执行一条命令（口令已经核对过） */
    fun run(context: Context, cmd: String) {
        Log.d(TAG, "外部调用：$cmd")
        val current = SysSettings.Secure.getString(context.contentResolver, SysSettings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val ime = VoiceInputService.instance
        if (current == VoiceSwitchService.OUR_IME && ime != null) {
            ime.external(cmd)
            return
        }
        if (MicStreamService.instance?.pcRecording == true) { remote(context, cmd); return }   // 电脑在听写：这一下是停它
        if (cmd == STOP) return                      // 本输入法不在用，就没在录
        val a11y = VoiceSwitchService.instance
        if (a11y == null) { A11yPrompt.show(context); return }   // 快速切换没开：发通知，点一下去开
        when (val hint = a11y.startVoiceIfEditing()) {
            null -> {}
            R.string.hint_external_no_field -> remote(context, cmd)
            else -> quickToast(context, hint)
        }
    }

    /** 手机上不在输入框：让电脑用手机当话筒听写，字打在电脑此刻的输入框里。电脑在录就停 */
    fun remote(context: Context, cmd: String) {
        if (MicStreamService.instance?.pcRecording == true) {
            if (cmd != START) { MicStreamService.sendPc("stop"); quickToast(context, R.string.remote_stopped) }
            return
        }
        if (cmd == STOP) return
        if (!MicStreamService.remoteArmed) {
            val now = SystemClock.elapsedRealtime()
            if (now - confirmAt > CONFIRM_MS) {      // 好久没用（麦克风模式关过）：先确认一次，防误触
                confirmAt = now
                quickToast(context, R.string.remote_confirm, CONFIRM_MS)
                return
            }
            confirmAt = 0L
        }
        if (Settings(context).micHost.isEmpty()) { quickToast(context, R.string.mic_bad_server); return }
        MicStreamService.remoteArmed = true
        if (MicStreamService.isRunning) {
            MicStreamService.sendPc("start")         // 排着，连上电脑就发
            quickToast(context, R.string.remote_started)
            return
        }
        val open = Intent(context, MicTileActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MicTileActivity.EXTRA_START_PC, true)
        val a11y = VoiceSwitchService.instance
        if (a11y != null) {
            try { a11y.startActivity(open); quickToast(context, R.string.remote_started); return }
            catch (e: Exception) { Log.w(TAG, "从后台开电脑麦克风没成：$e") }
        }
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) { quickToast(context, R.string.remote_notify_title); return }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(REMOTE_CHANNEL, context.getString(R.string.tile_mic), NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(context, REMOTE_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.remote_notify_title))
            .setContentIntent(PendingIntent.getActivity(context, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { nm.notify(REMOTE_NOTIF_ID, n) } catch (e: SecurityException) { quickToast(context, R.string.remote_notify_title) }
    }

    /** 一闪而过的小提示（系统最短的 Toast 也要 2 秒，提前收掉）：误触时别挡太久 */
    fun quickToast(ctx: Context, res: Int, ms: Long = QUICK_TOAST_MS) {
        val t = Toast.makeText(ctx.applicationContext, res, if (ms > 2000) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
        t.show()
        Handler(Looper.getMainLooper()).postDelayed({ t.cancel() }, ms)
    }
}
