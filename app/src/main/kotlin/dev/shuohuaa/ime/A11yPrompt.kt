package dev.shuohuaa.ime

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SysSettings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * "快速切换"（无障碍服务）没开、又被 Tasker 插件要用的时候：弹一条通知，点一下进系统里本服务的开关页
 * （2026-10-12 用户：没开的时候别只提示，直接带我去开）。不直接跳：插件触发时应用在后台，安卓 10 起不许后台弹界面，
 * 硬跳会被系统拦下、什么都看不到。通知被关了就退回一闪而过的提示。打开无障碍服务只能用户自己点，应用做不到。
 */
object A11yPrompt {
    private const val CHANNEL = "quick_switch"
    private const val NOTIF_ID = 11

    fun show(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) {
            ExternalControl.quickToast(ctx, R.string.hint_external_need_switch); return
        }
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.a11y_prompt_channel), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, OpenA11yActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(ctx.getString(R.string.a11y_prompt_title))
            .setContentText(ctx.getString(R.string.a11y_prompt_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (e: SecurityException) {
            ExternalControl.quickToast(ctx, R.string.hint_external_need_switch)
        }
    }

    /** 系统里本服务的开关页（Android 12 起有）；没有就到无障碍总页 */
    fun openSettings(ctx: Context) {
        val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(ctx, VoiceSwitchService::class.java).flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(detail) } catch (e: Exception) {
            ctx.startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** 点通知时的中转：打开系统里本服务的开关页就关掉自己 */
class OpenA11yActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        A11yPrompt.openSettings(this)
        finish()
    }
}
