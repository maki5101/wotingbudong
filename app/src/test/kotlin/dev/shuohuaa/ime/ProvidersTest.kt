package dev.shuohuaa.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 服务商：只有说话啊发扩展参数；旧版"备用地址"升级时按域名认服务商 */
class ProvidersTest {
    @Test fun onlyShuohuaaGetsExtensions() {
        assertTrue(Providers.info(Providers.SHUOHUAA).extensions)
        for (p in listOf(Providers.OPENAI, Providers.GROQ, Providers.SILICONFLOW, Providers.CUSTOM, "unknown"))
            assertFalse(p, Providers.info(p).extensions)
    }

    @Test fun guessFromOldFallbackUrl() {
        assertEquals(Providers.OPENAI, Providers.guess("https://api.openai.com/v1/audio/transcriptions"))
        assertEquals(Providers.GROQ, Providers.guess("https://api.groq.com/openai/v1"))
        assertEquals(Providers.SILICONFLOW, Providers.guess("https://api.siliconflow.cn/v1"))
        assertEquals(Providers.CUSTOM, Providers.guess("https://example.com/v1"))
        assertEquals(Providers.CUSTOM, Providers.guess(""))
    }
}
