package dev.shuohuaa.ime

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * 下拉快捷设置里的"电脑麦克风"开关：点一下开始把手机麦克风推给电脑（麦克风模式），再点停下。亮 = 正在推。
 * 用户要的是"电脑那边想用手机当麦克风时，手机上一下就能开"（2026-10-05）；比"电脑发请求、手机弹通知接受"省事：
 * 后者要手机常驻一条连接等电脑召唤（安卓不让应用在后台一直等），通知栏得挂常驻通知、多耗电。
 * 开的时候经过一个透明界面（MicTileActivity）再起前台服务：新版安卓不让后台开麦克风类前台服务，界面在前台就放行。
 */
class MicTileService : TileService() {
    override fun onStartListening() = refresh()

    // 旧接口只在安卓 14 以下走（14 起用 PendingIntent 那个，旧的会抛异常）；lint 不看版本判断，单独压掉
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (MicStreamService.isRunning) {
            MicStreamService.stop(this)
            // 服务是异步停的，这时 isRunning 还是 true：直接把开关灭掉，不然面板里一直亮着，用户再点一下又开了
            qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
        } else {
            val intent = Intent(this, MicTileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(intent)
            }
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_pc_mic)  // 系统会缓存清单里的旧图标
        tile.state = if (MicStreamService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}

/** 快捷开关点开时一闪而过的透明界面：在前台起麦克风模式，马上关掉。没权限或没填电脑地址就去设置页 */
class MicTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic || settings.micHost.isEmpty()) {
            if (hasMic) Toast.makeText(this, R.string.mic_bad_server, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            settings.micMode = true
            MicStreamService.start(this)
            if (intent.getBooleanExtra(EXTRA_START_PC, false)) MicStreamService.sendPc("start")   // 连上电脑就让它开录
        }
        finish()
    }

    companion object {
        /** 外部调用（不在输入框时长按音量键）开的：开完麦克风模式让电脑开始录音 */
        const val EXTRA_START_PC = "dev.shuohuaa.ime.START_PC"
    }
}
