package dev.shuohuaa.ime

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * 录音 + 按静音切段，和电脑上 daemon 的逻辑对齐：
 * 说话中 → 静音超过 silenceMs 就把这一段交出去；一口气说太久超过 maxSegmentMs 也切——
 * 只切在真停顿上（连续 PAUSE_FRAMES 帧低于 speechRms），切点后面的帧留作下一段开头；没有停顿就接着等，
 * 到两倍时长还没有才退回最近 2 秒的音量低谷（和电脑端 daemon 的 _pause_cut_index / _soft_cut_index 同一套）。
 * 只按音量低谷切会劈在字中间：电脑端 2026-10-04 "请求"成了"请求？球"。
 * 每段回调一个 16k 单声道 PCM16 的 WAV。整段音量太低（没人说话的空段）直接丢。
 * 连续 idleStopMs 没检测到说话就自己停下并回调 onIdle（忘了关录音的兜底；0 = 不停）。
 * 边说边出字：partialMs > 0 时，说话超过 MIN_PARTIAL_MS 后每隔 partialMs 交一份"这段到目前为止"的音频
 * （onPartial）。每段有编号：临时结果、最终结果（onSegment）、被丢掉（onDropped：撤销、停录丢弃、太安静）
 * 都带编号，调用方靠它把乱序回来的结果对上号。
 */
class Recorder(
    private val silenceMs: Int,
    private val maxSegmentMs: Int,
    private val speechRms: Double,
    private val idleStopMs: Int,
    private val onSegment: (seg: Int, wav: ByteArray, forced: Boolean) -> Unit,   // forced = 满时长硬切的
    private val onIdle: () -> Unit = {},
    private val partialMs: Int = 0,
    private val onPartial: (seg: Int, wav: ByteArray) -> Unit = { _, _ -> },
    private val onDropped: (seg: Int, quiet: Boolean) -> Unit = { _, _ -> },   // quiet = 停录时残余太安静（不是用户要丢）
) {
    /** 正在说的这段的编号；交出去或丢掉以后 +1 */
    @Volatile var currentSeg = 0
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")   // 调用方保证已授权
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "recorder").also { it.start() }
    }

    /** 停止并交出手里的残余段（discard=true 时残余丢弃，用于焦点丢失 / 拖动取消）。 */
    fun stop(discard: Boolean = false) {
        discardOnStop = discard
        running = false
        thread?.join(2000)
        thread = null
    }

    @Volatile private var discardOnStop = false
    @Volatile private var dropRequested = false

    /** 录音不停，只把正在说的这段扔掉（撤销用）。 */
    fun dropCurrent() { dropRequested = true }

    @android.annotation.SuppressLint("MissingPermission")   // 调用方（VoiceInputService.startRecording）先查过 hasMic()
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_BYTES * 8),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败"); return
        }
        rec.startRecording()

        Log.d(TAG, "开始录音 sr=$SAMPLE_RATE speechRms=$speechRms silenceMs=$silenceMs")
        var peak = 0.0; var segments = 0
        val frame = ByteArray(FRAME_BYTES)
        val segment = ByteArrayOutputStream()
        val levels = ArrayList<Double>()               // segment 里每一帧的 RMS，找切点用
        val preRoll = ArrayDeque<ByteArray>()          // 说话前 100ms 的帧，避免开头被切掉
        var speaking = false
        var silentMs = 0
        var segmentMs = 0
        var idleMs = 0          // 距上一次听到说话过了多久
        var idleStopped = false
        var sincePartial = 0    // 距上一份临时音频过了多久

        // 到了时长上限：切在最靠后的停顿中间；没有停顿、又没到两倍时长就先不切（返回 false），接着录
        fun softCut(): Boolean {
            val n = levels.size
            var cutFrame = pauseCutFrame(levels, speechRms, 1000 / FRAME_MS, PAUSE_FRAMES)
            var how = "停顿处"
            if (cutFrame == null) {
                if (segmentMs < 2 * maxSegmentMs) return false
                // 一直没停顿：最近 2 秒里找连续 3 帧（60ms）能量最小处
                val start = maxOf(0, n - 2000 / FRAME_MS)
                var bestI = n; var best = Double.MAX_VALUE
                for (i in start..n - 3) {
                    val e = levels[i] + levels[i + 1] + levels[i + 2]
                    if (e < best) { best = e; bestI = i }
                }
                cutFrame = if (bestI >= n) n else bestI + 2          // 切在谷的中间
                how = "音量低谷（一直没停顿）"
            }
            val pcm = segment.toByteArray()
            val off = cutFrame * FRAME_BYTES
            val head = pcm.copyOfRange(0, off)
            segment.reset(); segment.write(pcm, off, pcm.size - off)
            val rest = ArrayList(levels.subList(cutFrame, n)); levels.clear(); levels.addAll(rest)
            segmentMs = rest.size * FRAME_MS
            segments++; Log.d(TAG, "到时长上限，在${how}切段：交出 ${cutFrame * FRAME_MS}ms，留 ${segmentMs}ms 接着录")
            onSegment(currentSeg++, Wav.fromPcm16(head, SAMPLE_RATE), true)
            sincePartial = 0
            return true
        }

        fun maybePartial() {
            if (partialMs <= 0 || !speaking) return
            sincePartial += FRAME_MS
            if (segmentMs >= MIN_PARTIAL_MS && sincePartial >= partialMs) {
                sincePartial = 0
                onPartial(currentSeg, Wav.fromPcm16(segment.toByteArray(), SAMPLE_RATE))
            }
        }

        fun flush(force: Boolean) {
            if (segment.size() == 0) return
            // 停录时的残余段要不要：看里面有几帧真在说话，不看整段平均音量——句尾停顿会把平均拉低，
            // 真话被当成"太安静"丢掉（电脑端 2026-10-03 同类问题：松手时最后一段没了）
            val speechFrames = levels.count { it >= speechRms }
            val pcm = segment.toByteArray(); segment.reset(); levels.clear()
            val ms = segmentMs; speaking = false; silentMs = 0; segmentMs = 0; sincePartial = 0
            val level = rms(pcm)
            if (!force || speechFrames >= MIN_SPEECH_FRAMES) {
                segments++; Log.d(TAG, "切出一段 ${ms}ms rms=${"%.4f".format(level)} 说话帧=$speechFrames")
                onSegment(currentSeg++, Wav.fromPcm16(pcm, SAMPLE_RATE), false)
            } else {
                Log.d(TAG, "残余段没几帧在说话，丢弃 ${ms}ms 说话帧=$speechFrames")
                onDropped(currentSeg++, true)
            }
        }

        try {
            while (running) {
                val n = rec.read(frame, 0, FRAME_BYTES)
                if (n <= 0) continue
                if (dropRequested) {
                    dropRequested = false
                    segment.reset(); levels.clear(); preRoll.clear(); speaking = false; silentMs = 0; segmentMs = 0; sincePartial = 0
                    Log.d(TAG, "丢掉正在说的这段")
                    onDropped(currentSeg++, false)
                }
                val level = rms(frame)
                if (level > peak) peak = level
                val isSpeech = level >= speechRms
                idleMs = if (isSpeech) 0 else idleMs + FRAME_MS
                if (idleStopMs > 0 && idleMs >= idleStopMs) {
                    Log.d(TAG, "连续 ${idleMs}ms 没人说话，自动停")
                    idleStopped = true; running = false; break
                }

                if (isSpeech) {
                    if (!speaking) {                      // 说话开始：把预滚帧补进去
                        speaking = true
                        preRoll.forEach { segment.write(it); levels.add(rms(it)) }; preRoll.clear()
                    }
                    silentMs = 0
                    segment.write(frame, 0, n); levels.add(level); segmentMs += FRAME_MS
                    if (!(segmentMs >= maxSegmentMs && softCut())) maybePartial()
                } else if (speaking) {
                    segment.write(frame, 0, n); levels.add(level); segmentMs += FRAME_MS
                    silentMs += FRAME_MS
                    if (silentMs >= silenceMs) flush(force = false) else maybePartial()
                } else {
                    preRoll.addLast(frame.copyOf(n))
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            if (discardOnStop) { if (segment.size() > 0) onDropped(currentSeg++, false); segment.reset() } else flush(force = true)
            Log.d(TAG, "录音结束 共${segments}段 最响的一帧 rms=${"%.4f".format(peak)}（说话判定阈值 $speechRms）")
            if (idleStopped) onIdle()
        }
    }

    private fun rms(buf: ByteArray, len: Int = buf.size): Double {
        var sum = 0.0; var i = 0; val n = len / 2
        while (i + 1 < len) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble() / 32768.0
            sum += s * s; i += 2
        }
        return if (n == 0) 0.0 else sqrt(sum / n)
    }

    companion object {
        /**
         * 找最靠后的一处停顿（连续 pauseFrames 帧以上音量低于 speechRms），返回切点帧（停顿中间）；没有返回 null。
         * 开头 minHeadFrames 帧里的不算（切下来太短）。
         */
        fun pauseCutFrame(levels: List<Double>, speechRms: Double, minHeadFrames: Int, pauseFrames: Int): Int? {
            var best: Int? = null
            var runEnd = -1
            for (i in levels.size - 1 downTo minHeadFrames) {
                if (levels[i] < speechRms) {
                    if (runEnd < 0) runEnd = i
                    if (runEnd - i + 1 >= pauseFrames) best = (i + runEnd + 1) / 2
                } else {
                    if (best != null) return best
                    runEnd = -1
                }
            }
            return best
        }

        private const val TAG = "shuohuaa.rec"
        const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 20
        private const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * 2
        private const val PRE_ROLL_FRAMES = 5
        // 停录时的残余段至少要这么多帧（20ms 一帧）真在说话才要，少于这个多半是一下咔哒声
        private const val MIN_SPEECH_FRAMES = 3
        // 连续这么多帧（20ms 一帧，100ms）低于 speechRms 才算停顿，超长段只切在停顿上
        private const val PAUSE_FRAMES = 5
        // 一段说到这么长才开始出临时结果：太短的音频模型常常猜错，一闪就变
        private const val MIN_PARTIAL_MS = 600
    }
}
