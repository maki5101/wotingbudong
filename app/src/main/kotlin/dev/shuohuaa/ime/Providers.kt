package dev.shuohuaa.ime

/**
 * 识别服务商。每个识别服务（主服务、备用）先选服务商，再填地址、API Key、模型——和别的 AI 应用配模型一个样子
 * （2026-10-04 用户："直接给个选项……有一个模型的名字，有一个模型的地址，然后有个 API"）。
 * 服务商决定：选了填进去的默认地址和模型；发不发说话啊的扩展参数（保留句末标点、语气词、两段合认的段号、临时结果标记）——
 * 只有说话啊认这些，别家只发 OpenAI 标准字段，不会因为不认识的参数报错。别的都一样：边说边出字只看"出字方式"开关。
 * 默认地址、模型 2026-10-04 按各家官方文档核对；改了顺手看一眼还对不对。
 */
object Providers {
    const val SHUOHUAA = "shuohuaa"
    const val OPENAI = "openai"
    const val GROQ = "groq"
    const val SILICONFLOW = "siliconflow"
    const val CUSTOM = "custom"

    class Info(val url: String, val model: String, val extensions: Boolean)

    private val INFO = mapOf(
        SHUOHUAA to Info("", "", true),    // 地址是各人自己的电脑；模型不用填（用电脑上当前的）
        OPENAI to Info("https://api.openai.com/v1", "gpt-4o-mini-transcribe", false),
        GROQ to Info("https://api.groq.com/openai/v1", "whisper-large-v3-turbo", false),
        SILICONFLOW to Info("https://api.siliconflow.cn/v1", "FunAudioLLM/SenseVoiceSmall", false),
        CUSTOM to Info("", "", false),
    )

    fun info(id: String): Info = INFO[id] ?: INFO.getValue(CUSTOM)

    /** 旧版"备用地址"按域名认服务商，认不出算"其他" */
    fun guess(url: String): String = when {
        url.isEmpty() -> CUSTOM
        "api.openai.com" in url -> OPENAI
        "api.groq.com" in url -> GROQ
        "siliconflow" in url -> SILICONFLOW
        else -> CUSTOM
    }
}
