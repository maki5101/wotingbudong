package dev.shuohuaa.ime

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Tasker 插件（Tasker / Locale 的标准插件约定）：Tasker 里 动作 → 插件 → 我听不懂，选"切换 / 开始 / 停止"就完了，
 * 什么都不用填（2026-10-05 用户：别的应用在 Tasker 里点一下就行，我们要填动作名、包名、口令太麻烦——而且用户照着填了，
 * 广播根本没到我们这）。口令由插件自己存进 Tasker 的动作里（EXTRA_BUNDLE），用户看不到；口令只在应用数据被清掉时会变，
 * 那时旧动作失效，提示用户在 Tasker 里重新点一下。执行逻辑在 ExternalControl.run。
 * 这个界面是导出的、谁都能打开，选完就把口令交给打开它的应用：标题里写上是谁要的（callingPackage），
 * 不是 Tasker 这类自动化应用就别点。安卓 13 起发通知要权限，"快速切换没开"的提醒（A11yPrompt）靠通知，
 * 用户在这里配插件时正好在前台，先问一次。
 */
class TaskerPluginActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Shuohuaa_Translucent)
        super.onCreate(savedInstanceState)
        asking = savedInstanceState?.getBoolean(KEY_ASKING) ?: false
        if (asking) return     // 转屏等重建时权限框还开着：等它的结果再弹选择框，别弹两个
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            asking = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        } else pick()
    }

    private var asking = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ASKING, asking)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) { asking = false; pick() }   // 给不给都接着选：没通知权限只是退回一闪而过的提示
    }

    private fun caller(): String? {
        val pkg = callingPackage?.takeIf { it != packageName } ?: return null
        return try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
        catch (e: PackageManager.NameNotFoundException) { pkg }
    }

    private fun pick() {
        val cmds = arrayOf(ExternalControl.TOGGLE, ExternalControl.START, ExternalControl.STOP)
        val labels = arrayOf(getString(R.string.tasker_toggle), getString(R.string.tasker_start), getString(R.string.tasker_stop))
        MaterialAlertDialogBuilder(this)
            .setTitle(caller()?.let { getString(R.string.tasker_pick_title_caller, it) } ?: getString(R.string.tasker_pick_title))
            .setItems(labels) { _, i ->
                val bundle = Bundle().apply {
                    putString(KEY_CMD, cmds[i])
                    putString(KEY_TOKEN, Settings(this@TaskerPluginActivity).externalToken)
                }
                setResult(RESULT_OK, Intent()
                    .putExtra(EXTRA_BUNDLE, bundle)
                    .putExtra(EXTRA_BLURB, labels[i]))
                finish()
            }
            .setOnCancelListener { setResult(RESULT_CANCELED); finish() }
            .show()
    }

    companion object {
        const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
        const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
        const val KEY_CMD = "dev.shuohuaa.ime.CMD"
        const val KEY_TOKEN = "dev.shuohuaa.ime.TOKEN"   // 字段名不是口令本身  privacy-scan:allow
        private const val REQ_NOTIF = 1
        private const val KEY_ASKING = "asking"
    }
}

/** Tasker 执行插件动作时发来的广播 */
class TaskerPluginReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val b = intent.getBundleExtra(TaskerPluginActivity.EXTRA_BUNDLE) ?: return
        val cmd = b.getString(TaskerPluginActivity.KEY_CMD) ?: return
        if (b.getString(TaskerPluginActivity.KEY_TOKEN) != Settings(context).externalToken) {
            Log.w("shuohuaa.ext", "Tasker 插件的口令和设置里的不一样（换过口令？），不理——在 Tasker 里重新点一下这个动作")
            ExternalControl.quickToast(context, R.string.tasker_token_changed)
            return
        }
        ExternalControl.run(context, cmd)
    }
}
