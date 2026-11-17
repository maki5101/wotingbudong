package dev.shuohuaa.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** 识别服务的地址写到哪一层都行（Transcriber.Endpoint）：电脑根地址、云服务的 /v1、完整的识别地址 */
class EndpointTest {
    private fun e(raw: String) = Transcriber.Endpoint(raw, "", "")

    @Test fun computerRoot() {
        val ep = e("http://100.x.y.z:9797/")
        assertEquals("http://100.x.y.z:9797/v1/audio/transcriptions", ep.url)
        assertEquals("http://100.x.y.z:9797", ep.root)
    }

    @Test fun cloudV1() {
        val ep = e("https://api.groq.com/openai/v1")
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", ep.url)
        assertEquals("https://api.groq.com/openai", ep.root)
        assertEquals("https://api.groq.com/openai/v1/models", ep.modelsUrl)
    }

    @Test fun fullUrlFromOldFallbackSetting() {
        // 旧版"备用 OpenAI 地址"存的是完整地址，升级后照用
        val ep = e("https://api.openai.com/v1/audio/transcriptions")
        assertEquals("https://api.openai.com/v1/audio/transcriptions", ep.url)
        assertEquals("https://api.openai.com", ep.root)
    }

    @Test fun emptyModelDefaults() {
        assertEquals("whisper-1", e("http://h:1").model)
        assertEquals("gpt-4o-mini-transcribe", Transcriber.Endpoint("http://h:1", "", "gpt-4o-mini-transcribe").model)
    }
}
