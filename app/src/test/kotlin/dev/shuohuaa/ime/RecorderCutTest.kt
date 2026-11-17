package dev.shuohuaa.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 超长段只切在停顿上（Recorder.pauseCutFrame），和电脑端 daemon._pause_cut_index 同一套 */
class RecorderCutTest {
    private val speech = 0.01
    private fun frames(vararg runs: Pair<Int, Double>) = runs.flatMap { (n, v) -> List(n) { v } }

    @Test fun cutsInMiddleOfLastPause() {
        // 说 100 帧、停 6 帧（100..105）、说 200 帧、停 2 帧（太短不算）、说 30 帧
        val lv = frames(100 to 0.02, 6 to 0.001, 200 to 0.02, 2 to 0.001, 30 to 0.02)
        assertEquals(103, Recorder.pauseCutFrame(lv, speech, 50, 5))
    }

    @Test fun noPauseNoCut() {
        assertNull(Recorder.pauseCutFrame(frames(400 to 0.02), speech, 50, 5))
    }

    @Test fun pauseInHeadIgnored() {
        // 停顿在开头 1 秒（50 帧）里：切下来太短，不算
        assertNull(Recorder.pauseCutFrame(frames(10 to 0.02, 8 to 0.001, 300 to 0.02), speech, 50, 5))
    }

    @Test fun trailingPauseCounts() {
        val lv = frames(300 to 0.02, 7 to 0.001)
        assertEquals(303, Recorder.pauseCutFrame(lv, speech, 50, 5))
    }
}
