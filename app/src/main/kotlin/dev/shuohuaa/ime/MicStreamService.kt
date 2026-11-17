package dev.shuohuaa.ime

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 麦克风模式：把手机麦克风的声音持续推给电脑，电脑那边（说话啊 core/phonemic.py）把它变成系统里的一个
 * 麦克风「说话啊手机麦克风」。远程用电脑时靠它说话。
 *
 * 两头解耦：这边只管推，不知道电脑那头谁在用、什么时候录；电脑也不能反过来打开手机麦克风——
 * 开关和静音都在手机上。连接常驻（断了自动重连），静音时麦克风关掉、不发音频，只发心跳。
 *
 * 协议见 phonemic.py：一行握手 + [1 字节类型][4 字节大端长度][负载] 的帧，音频是 16k 单声道 PCM16。
 * 前台服务（类型 microphone），通知栏有静音/停止。
 *
 * 电脑多久没用它就自动关（`mic_idle_min`，默认 10 分钟），发一条通知（2026-10-29 用户：开着就一直开着，太费电）。
 * 电脑来判断（握手时把分钟数告诉它，到点它回一行 IDLE）：看电脑有没有在拿这路声音录音，确定的、不受周围声音影响。
 * 曾经手机自己按音量判断"有没有人说话"，2026-10-30 连了 34 分钟没用，周围的声音被当成说话，一直没关（用户："根本就不准"）。
 * 连不上电脑时没人能判断，手机自己从断开那刻计时；圆球在录音（这边让开了）算在用。
 */
class MicStreamService : Service() {
    private lateinit var settings: Settings
    @Volatile private var running = false
    @Volatile private var muted = false
    @Volatile private var pausedByIme = false   // 圆球正在录音：两路麦克风会互相抢，这边先让开
    @Volatile private var status = ""
    @Volatile private var lastActiveAt = 0L        // 连着电脑、或圆球在用的最近时刻（elapsedRealtime）：连不上电脑时从这起计时
    /** 电脑正在录音（电脑经这条连接告诉的；旧版电脑不说，就一直是 false）。变了就更新屏幕顶上的提示和通知 */
    @Volatile var pcRecording = false
        private set(v) {
            if (field == v) return
            field = v
            main.post {
                VoiceSwitchService.instance?.showPcRecording(v)
                if (instance === this) updateNotification()
            }
        }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var failing = false          // 正连不上电脑（连续失败中）：通知标题写"连不上"，恢复时提示一声
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        status = getString(R.string.mic_connecting)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { settings.micMode = false; stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE_MUTE -> { setMuted(!muted); return START_STICKY }
            ACTION_SET_MUTE -> { setMuted(intent.getBooleanExtra("muted", false)); return START_STICKY }
            ACTION_PAUSE -> { pausedByIme = true; updateNotification(); return START_STICKY }
            ACTION_RESUME -> { pausedByIme = false; updateNotification(); return START_STICKY }
        }
        if (running) return START_STICKY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "没有麦克风权限，麦克风模式起不来"); settings.micMode = false; stopSelf(); return START_NOT_STICKY
        }
        muted = settings.micMuted
        lastActiveAt = SystemClock.elapsedRealtime()
        startAsForeground()
        running = true
        worker = thread(name = "mic-stream") { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        pcRecording = false
        remoteArmed = false     // 麦克风模式关了（手动、或没人说话自动关）：下次让电脑听写又要先确认
        worker?.interrupt()
        instance = null
        super.onDestroy()
    }

    private fun setMuted(m: Boolean) {
        muted = m; settings.micMuted = m
        updateNotification()
    }

    // ── 网络 + 采集循环 ───────────────────────────────────────
    private fun loop() {
        var backoff = 1000L
        while (running) {
            if (idleExpired()) { idleStop(); break }
            try {
                stream()
                backoff = 1000L
            } catch (e: Exception) {
                if (!running) break
                val why = reason(e)
                status = getString(R.string.mic_retry, why, (backoff / 1000).toInt())
                Log.w(TAG, "推流中断：$e")
                // 连不上要让人看得到（2026-11-04 用户：手机 Tailscale 掉了，麦克风模式不出声也不提示）：
                // 这一轮失败的第一次弹提示，之后只在通知里更新，不反复弹
                if (!failing) { failing = true; toast(getString(R.string.mic_unreachable, why)) }
                updateNotification()
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                backoff = minOf(backoff * 2, 15_000L)
            }
        }
        status = getString(R.string.mic_stopped)
    }

    @SuppressLint("MissingPermission")
    private fun stream() {
        val host = settings.micHost.ifEmpty { throw IllegalStateException(getString(R.string.mic_bad_server)) }
        val port = settings.micPort
        Socket().use { sock ->
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.connect(InetSocketAddress(host, port), 5000)
            sock.soTimeout = 10_000
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 8192))
            val inp = BufferedInputStream(sock.getInputStream())
            val name = (Build.MODEL ?: "phone").replace(" ", "_")
            val token = settings.micToken      // 麦克风模式自己的口令：识别服务那栏可能是云服务的 API Key，不能发给电脑
            out.write("SHUOHUAA-MIC/1 name=$name${if (token.isNotEmpty()) " token=$token" else ""} idle=${settings.micIdleMin}\n".toByteArray())
            out.flush()
            val reply = readLine(inp)
            if (reply != "OK") throw IllegalStateException(getString(R.string.mic_refused, reply ?: ""))
            status = getString(R.string.mic_connected, host)
            if (failing) { failing = false; toast(getString(R.string.mic_reconnected)) }
            updateNotification()
            sock.soTimeout = 0
            // 电脑往回发 REC 1 / REC 0：它在不在录音，长按音量键据此决定是开还是停
            thread(name = "mic-state", isDaemon = true) {
                try {
                    while (true) {
                        when (readLineOrNull(inp) ?: break) {
                            "REC 1" -> pcRecording = true
                            "REC 0" -> pcRecording = false
                            "IDLE" -> { idleStop(); break }      // 电脑说好久没用了
                        }
                    }
                } catch (_: Exception) {}
                pcRecording = false
            }

            var rec: AudioRecord? = null
            val frame = ByteArray(FRAME_BYTES)
            var lastBeat = System.currentTimeMillis()
            var lastMuteSent: Boolean? = null
            try {
                while (running) {
                    drainControl(out)
                    if (muted != lastMuteSent) {           // 告诉电脑静音状态（只用于显示）
                        sendFrame(out, 2, byteArrayOf(if (muted) 1 else 0)); lastMuteSent = muted
                    }
                    lastActiveAt = SystemClock.elapsedRealtime()   // 连着电脑：用没用由电脑判断（IDLE），这边不计时
                    if (muted || pausedByIme) {
                        if (rec != null) { rec.stop(); rec.release(); rec = null }
                        if (System.currentTimeMillis() - lastBeat > 3000) { sendFrame(out, 3, ByteArray(0)); lastBeat = System.currentTimeMillis() }
                        Thread.sleep(100); continue
                    }
                    if (rec == null) {
                        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_BYTES * 8))
                        if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException(getString(R.string.mic_open_fail))
                        rec.startRecording()
                    }
                    val n = rec.read(frame, 0, FRAME_BYTES)
                    if (n > 0) sendFrame(out, 1, frame, n)
                }
            } finally {
                rec?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
                pcRecording = false
            }
        }
    }

    /** 连不上的原因，说人话、带上该去查什么 */
    private fun reason(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException, is java.net.NoRouteToHostException -> getString(R.string.mic_err_timeout)
        is java.net.ConnectException -> getString(R.string.mic_err_refused)
        is java.net.UnknownHostException -> getString(R.string.mic_bad_server)
        else -> e.message?.take(60) ?: e.javaClass.simpleName
    }

    private fun toast(text: String) {
        main.post { android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_LONG).show() }
    }

    /** 发出排着的"让电脑开始/停止录音"（类型 4）。太久之前排的（连不上电脑那会儿按的）不发了，免得过后突然开录 */
    private fun drainControl(out: DataOutputStream) {
        while (true) {
            val (cmd, at) = pendingControl.poll() ?: return
            if (SystemClock.elapsedRealtime() - at > CONTROL_TTL_MS) continue
            if (cmd == "start") {
                if (muted) setMuted(false)               // 要说话了：静音着的话电脑只录到空白
                lastActiveAt = SystemClock.elapsedRealtime()
            }
            sendFrame(out, 4, cmd.toByteArray())
        }
    }

    private fun idleExpired(): Boolean {
        val min = settings.micIdleMin
        return min > 0 && SystemClock.elapsedRealtime() - lastActiveAt >= min * 60_000L
    }

    /** 太久没用（电脑说的，或连不上电脑太久）：关掉麦克风模式，发一条通知说一声（不然用户以为还开着） */
    private fun idleStop() {
        if (!running) return
        val min = settings.micIdleMin
        Log.i(TAG, "$min 分钟没用，自动关闭麦克风模式")
        running = false
        settings.micMode = false
        val open = PendingIntent.getActivity(this, 1, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.mic_idle_stopped, min))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(IDLE_NOTIF_ID, n) } catch (_: SecurityException) {}
        stopSelf()
    }

    private fun sendFrame(out: DataOutputStream, type: Int, payload: ByteArray, len: Int = payload.size) {
        out.writeByte(type); out.writeInt(len); out.write(payload, 0, len); out.flush()
    }

    private fun readLineOrNull(inp: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return null
            if (c == '\n'.code) break
            sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    private fun readLine(inp: BufferedInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0 || c == '\n'.code) break
            sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    // ── 前台通知 ─────────────────────────────────────────────
    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.mic_channel), NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    private fun buildNotification(): Notification {
        fun pi(action: String) = PendingIntent.getService(this, action.hashCode(),
            Intent(this, MicStreamService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(if (failing) R.string.mic_title_offline else if (muted) R.string.mic_title_muted else if (pausedByIme) R.string.mic_title_paused
                else if (pcRecording) R.string.mic_title_pc_recording else R.string.mic_title_live))
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(if (muted) R.string.mic_unmute else R.string.mic_mute), pi(ACTION_TOGGLE_MUTE))
            .addAction(0, getString(R.string.mic_stop), pi(ACTION_STOP))
            .build()
    }

    private fun updateNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
    }

    companion object {
        private const val TAG = "shuohuaa.mic"
        private const val CHANNEL = "mic_stream"
        private const val NOTIF_ID = 7
        private const val IDLE_NOTIF_ID = 8
        private const val CONTROL_TTL_MS = 15_000L
        private val pendingControl = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Long>>()
        /** 确认过"让电脑听写"了：之后长按音量键直接开停，不再确认；麦克风模式关掉时重置 */
        @Volatile var remoteArmed = false
        /** 让电脑开始/停止录音：排着，连上电脑就发（服务还没起也能先排） */
        fun sendPc(cmd: String) { pendingControl.add(cmd to SystemClock.elapsedRealtime()) }
        const val ACTION_STOP = "dev.shuohuaa.ime.MIC_STOP"
        const val ACTION_TOGGLE_MUTE = "dev.shuohuaa.ime.MIC_TOGGLE_MUTE"
        const val ACTION_SET_MUTE = "dev.shuohuaa.ime.MIC_SET_MUTE"
        const val ACTION_PAUSE = "dev.shuohuaa.ime.MIC_PAUSE"
        const val ACTION_RESUME = "dev.shuohuaa.ime.MIC_RESUME"
        const val SAMPLE_RATE = 16_000
        private const val FRAME_BYTES = SAMPLE_RATE * 20 / 1000 * 2   // 20ms
        @Volatile var instance: MicStreamService? = null
        val isRunning get() = instance != null

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, MicStreamService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, MicStreamService::class.java).setAction(ACTION_STOP))
        fun setMuted(ctx: Context, m: Boolean) = ctx.startService(Intent(ctx, MicStreamService::class.java).setAction(ACTION_SET_MUTE).putExtra("muted", m))
        /** 圆球开始/结束录音时调：同一个 App 里两路 AudioRecord 会互相抢，麦克风模式先让开（连接不断） */
        fun pauseForIme(ctx: Context, pause: Boolean) { if (isRunning) ctx.startService(Intent(ctx, MicStreamService::class.java).setAction(if (pause) ACTION_PAUSE else ACTION_RESUME)) }
    }
}
