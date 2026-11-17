package dev.shuohuaa.ime

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 把音频段发去识别，按提交顺序把结果回到主线程。
 *
 * 主服务和备用服务是同一种东西：OpenAI 兼容的语音识别接口，填 服务商 + 地址 + API Key + 模型（2026-10-04 用户要求：
 * 手机只说 OpenAI 协议，合规、没电脑也能用，不区分电脑和云服务）。服务商选"说话啊"时多带几个扩展参数（保留句末标点、
 * 语气词、两段合认的段号、临时结果标记，放网址后面，旧版电脑端 0.4.2 也认），别家只发标准字段（见 Providers）。
 * 边说边出字只看"出字方式"开关，对哪家都一样（用按次收费的云服务会多出很多请求，设置页有提醒）。
 *
 * 主服务先重试（共 3 次，间隔 700ms：网络抖一下很常见），都不行才走备用；主服务重试仍失败后 30 秒内后续段
 * 直接走备用/直接报错，免得每段都等好几个超时。
 */
class Transcriber(private val settings: Settings) {
    /** junction：电脑两段合认定的接缝标点（和上一段之间放什么，空串 = 不放）；null = 没有，用段末标点 */
    class Result(val text: String?, val error: String?, val via: String, val junction: String? = null)

    /** 一段的身份：录音会话 + 段号。电脑据此记住上一段的音频，和这段连起来重认，定接缝标点 */
    class SegId(val sid: String, val seg: Int)

    /**
     * 一个识别服务。地址写到哪一层都行：http://电脑:9797、https://api.openai.com/v1、
     * 或者完整的 …/v1/audio/transcriptions，都换算成识别地址（url）和服务根（root，旧版说话啊测试连接问 /health 用）。
     */
    class Endpoint(raw: String, val key: String, model: String, val extensions: Boolean = false) {
        val url: String
        val root: String
        val model: String = model.ifEmpty { DEFAULT_MODEL }

        init {
            val r = raw.trim().trimEnd('/')
            when {
                r.endsWith("/audio/transcriptions") -> {
                    url = r; root = r.removeSuffix("/audio/transcriptions").removeSuffix("/v1")
                }
                r.endsWith("/v1") -> { url = "$r/audio/transcriptions"; root = r.removeSuffix("/v1") }
                else -> { url = "$r/v1/audio/transcriptions"; root = r }
            }
        }

        /** OpenAI 的 GET /v1/models：测试连接时看云服务通不通、密钥对不对 */
        val modelsUrl: String get() = url.removeSuffix("/audio/transcriptions") + "/models"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    private val probeClient = client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    private val queue = Executors.newSingleThreadExecutor { Thread(it, "transcribe") }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var primaryDownUntil = 0L

    // 边说边出字的临时结果：单独一条线、短超时；同一时刻只有一个在路上，忙就丢（下一份音频更新）
    private val partialClient = client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    private val partialQueue = Executors.newSingleThreadExecutor { Thread(it, "partial") }
    private val partialBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    private val primary: Endpoint? get() =
        settings.server.takeIf { it.isNotEmpty() }?.let {
            Endpoint(it, settings.token, settings.model, Providers.info(settings.provider).extensions)
        }
    private val fallback: Endpoint? get() =
        settings.fallback.takeIf { it.isNotEmpty() }?.let {
            Endpoint(it, settings.fallbackKey, settings.fallbackModel, Providers.info(settings.fallbackProvider).extensions)
        }

    /** 临时结果（边说边出字，调用方按"出字方式"开关决定发不发）：只问主服务，不重试，主服务最近连不上就不发。
     *  失败回调 null。说话啊的当前模型不出临时结果时回空，就等一段说完再出字 */
    fun partial(wav: ByteArray, onResult: (String?) -> Unit) {
        val ep = primary ?: return
        if (System.currentTimeMillis() < primaryDownUntil) return
        if (!partialBusy.compareAndSet(false, true)) return
        partialQueue.execute {
            val t = try {
                post(ep, wav, enhance(ep, partial = true), partialClient).first
            } catch (e: Exception) { Log.d(TAG, "临时结果失败：${e.message}"); null }
            partialBusy.set(false)
            main.post { onResult(t) }
        }
    }

    fun submit(wav: ByteArray, id: SegId? = null, onResult: (Result) -> Unit) {
        queue.execute {
            val r = transcribe(wav, id)
            main.post { onResult(r) }
        }
    }

    /** 设置页的"测试连接"用：主线程外调用。主服务、备用（填了的话）各测一下 */
    fun health(): String {
        val p = primary ?: return settings.res.getString(R.string.err_no_server)
        val lines = mutableListOf(settings.res.getString(R.string.test_primary, probe(p)))
        fallback?.let { lines += settings.res.getString(R.string.test_fallback, probe(it)) }
        return lines.joinToString("\n")
    }

    /** 测试连接：问服务的模型列表（OpenAI 标准的 GET /v1/models），哪家都一样。说话啊 0.4.2 还没有 /v1/models
     *  （回 404，识别其实能用）：再问一下它的 /health */
    private fun probe(ep: Endpoint): String {
        return try {
            val req = Request.Builder().url(ep.modelsUrl).apply { auth(ep.key) }.build()
            probeClient.newCall(req).execute().use { resp ->
                if (resp.code == 404 && ep.extensions && oldShuohuaa(ep)) return oldShuohuaaAuth(ep)
                when (resp.code) {
                    200 -> settings.res.getString(R.string.health_openai)
                    401, 403 -> settings.res.getString(R.string.health_auth, resp.code)
                    else -> settings.res.getString(R.string.health_http, resp.code,
                        errorMessage(resp.body?.string().orEmpty()).take(120))
                }
            }
        } catch (e: IllegalArgumentException) {
            settings.res.getString(R.string.health_fail, e.message ?: "")
        } catch (e: IOException) {
            settings.res.getString(R.string.health_fail, e.message ?: "")
        }
    }

    private fun oldShuohuaa(ep: Endpoint): Boolean = try {
        probeClient.newCall(Request.Builder().url("${ep.root}/health").build()).execute().use { it.code == 200 }
    } catch (e: IOException) { false }

    /** 旧版说话啊的 /health 不验口令：发个空的识别请求，它先验口令（错了 401），过了才看请求体（空的回 400） */
    private fun oldShuohuaaAuth(ep: Endpoint): String = try {
        val req = Request.Builder().url(ep.url).post(ByteArray(0).toRequestBody(null)).apply { auth(ep.key) }.build()
        probeClient.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) settings.res.getString(R.string.health_auth, resp.code)
            else settings.res.getString(R.string.health_openai)
        }
    } catch (e: IOException) { settings.res.getString(R.string.health_fail, e.message ?: "") }

    private fun transcribe(wav: ByteArray, id: SegId?): Result {
        val p = primary ?: return Result(null, settings.res.getString(R.string.err_no_server), "无")
        Log.d(TAG, "发送 ${wav.size} 字节 → ${p.url}")
        val now = System.currentTimeMillis()
        var lastErr: String? = null
        if (now >= primaryDownUntil) {
            for (attempt in 1..PRIMARY_ATTEMPTS) {
                try {
                    val (t, junction) = post(p, wav, enhance(p, id = id))
                    Log.d(TAG, "主服务返回: $t（接缝 ${junction ?: "无"}）"); return Result(t, null, "主", junction)
                } catch (e: IOException) {
                    lastErr = e.message
                    Log.w(TAG, "主服务识别失败（第 $attempt/$PRIMARY_ATTEMPTS 次）：${e.message}")
                    if (attempt < PRIMARY_ATTEMPTS) Thread.sleep(700)
                } catch (e: IllegalArgumentException) {
                    return Result(null, settings.res.getString(R.string.err_bad_url, e.message ?: ""), "无")
                }
            }
            primaryDownUntil = System.currentTimeMillis() + 30_000
        }
        val fb = fallback ?: return Result(null, settings.res.getString(R.string.err_pc_down, PRIMARY_ATTEMPTS,
            lastErr ?: settings.res.getString(R.string.err_recent_fail)), "无")
        return try {
            Result(post(fb, wav, enhance(fb)).first, null, "备用")
        } catch (e: Exception) {
            Result(null, settings.res.getString(R.string.err_both_down, e.message ?: ""), "无")
        }
    }

    /** 说话啊的扩展参数（放网址后面：新旧电脑端都认）；服务商不是说话啊就不带。id 带上就做两段合认 */
    private fun enhance(ep: Endpoint, partial: Boolean = false, id: SegId? = null): Map<String, String> {
        if (!ep.extensions) return emptyMap()
        val m = linkedMapOf("keep_punct" to "1", "fillers" to if (settings.removeFillers) "drop" else "keep")
        if (partial) m["partial"] = "1"
        if (id != null) { m["sid"] = id.sid; m["seg"] = id.seg.toString() }
        return m
    }

    /** POST 识别。要 JSON 回复（OpenAI 的 gpt-4o 系列识别模型只回 JSON），读 text；接缝标点看回复头 X-Junction
     *  （旧版电脑端只有这个）或 JSON 的 junction。回的不是 JSON 就当纯文本。 */
    private fun post(ep: Endpoint, wav: ByteArray, ext: Map<String, String>,
                     http: OkHttpClient = client): Pair<String, String?> {
        val query = ext.entries.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "seg.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", ep.model)
            .addFormDataPart("response_format", "json")
            .build()
        val req = Request.Builder().url(if (query.isEmpty()) ep.url else "${ep.url}?$query").post(body)
            .apply { auth(ep.key) }.build()
        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${errorMessage(raw).take(200)}")
            val json = try { JSONObject(raw) } catch (e: Exception) { null }
            val text = json?.optString("text") ?: raw
            val junction = resp.header("X-Junction")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: json?.takeIf { it.has("junction") }?.optString("junction")
            text.trim() to junction
        }
    }

    /** OpenAI 格式的出错 {"error": {"message": …}}、说话啊旧格式 {"error": "…"}，都取出那句话 */
    private fun errorMessage(raw: String): String = try {
        val e = JSONObject(raw).get("error")
        if (e is JSONObject) e.optString("message", raw) else e.toString()
    } catch (x: Exception) { raw }

    private fun Request.Builder.auth(token: String) {
        if (token.isNotEmpty()) header("Authorization", "Bearer $token")
    }

    companion object {
        private const val TAG = "shuohuaa.net"
        private const val PRIMARY_ATTEMPTS = 3
        /** 模型没填时用它：OpenAI 的老牌识别模型名，多数兼容服务也认；说话啊不看这个（用电脑上当前的模型） */
        const val DEFAULT_MODEL = "whisper-1"
    }
}
