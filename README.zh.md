# 我听不懂（wotingbudong）

[English](README.md) · MIT 协议

安卓语音输入法：没有键盘，只有一个悬浮圆球，说话就上屏。识别不在手机上做，录音发给你在设置里填的 OpenAI 兼容
语音识别服务：可以是电脑上跑着的 [说话啊（shuohuaa）](https://github.com/maki5101/shuohuaa)（通过 Tailscale，推荐；保留句末标点、两段合认、
去语气词这些增强只有它有），也可以直接用 OpenAI / Groq / 硅基流动等云服务，没电脑也能用。

- 没有键盘，切过来之后屏幕上只有一个**可拖动的悬浮圆球**，应用不被顶起，圆球以外照常可点；
  松手贴靠到左边 / 右边 / 中线，贴边距离可调（避开系统的边缘手势）
- 点一下开录、再点停；按住说话、松手停；录音中再按 = 停；切到这个输入法就自动开录
- 录音按静音切段，每段立刻传给识别服务、按顺序上屏（边说边出字）；连续 15 秒没人说话自动停（可调）
- 圆球就是状态灯：待机色跟随系统主题（也可以自选）/ 红呼吸录音中 / 黄识别中 / 灰出错；大小、透明度可调
- 圆球旁四颗小卫星：切回上一个键盘、撤销、编辑键（铅笔：展开/收起一排 ← → 退格 空格 标点 回车）、设置。
  **撤销随时可点**：录音中点它 = 刚上屏的那句删掉、正在说的这段丢掉、录音继续——说错了直接重说。
  录音中也能切回键盘（打完这句再切）
- **回车 = 说完了**：录音中按编辑条的回车/发送，先停录，说过的话全部识别、上屏完再回车（最多等 8 秒）
- 输入框失去焦点（应用收起输入法 / 换了输入框）→ 停录并丢弃没打出去的，宁可丢也不打错框
- 录音期间拿音频焦点，正在外放的 App 会暂停/让路，停录后它们自己恢复
- **快速切换（无障碍服务，可选）**：设置"开始使用"页的开关。拨开会跳到系统里这个服务的详情页去打开
  （三星侧载 App 先在"应用信息"里允许受限设置），拨掉直接关。开了之后可以把系统导航栏的"无障碍按钮"指向它，
  点一下在我听不懂和上一个键盘之间来回切；也可以开"别的键盘上方显示小话筒"；Tasker 插件从别的键盘切过来也靠它。
  这是普通 App 唯一被允许替用户切输入法的途径。它只看键盘窗口的出现/消失、当前焦点是不是输入框，不读屏幕上的文字、
  不拦按键。注意：个别银行类 App 检测到有无障碍服务在跑会提示或拒绝运行。
- **降级**：主服务（电脑也好、云服务也好）重试 3 次还连不上，就走设置里的备用服务（同样是 服务商 + 地址 + API Key +
  模型，可选）；主服务失败后 30 秒内后续段直接走备用，不每段都等超时；都不行圆球变灰报错
- **手机当电脑的麦克风**：设置"电脑麦克风"页填电脑地址（如 `100.x.y.z`，说话啊 `./shuohuaa status` 里"手机麦克风"那行）
  和口令（电脑 `.env` 的 `SHUOHUAA_API_TOKEN`，没设留空），打开"麦克风模式"，手机就把麦克风持续推给电脑，远程用电脑时
  靠它说话。这里的地址、口令和识别服务分开填。通知栏能静音/停止。电脑 10 分钟没用它录音就自动关（电脑判断，可调，0 = 不关），省电。
- **下拉快捷开关"电脑麦克风"**：加进通知栏的快捷设置，点一下开、再点停。没授权麦克风、没填电脑地址时会先打开设置页。
- **Tasker 插件**：Tasker 里选 动作 → 插件 → 我听不懂：语音识别，再选 切换 / 开始 / 停止，什么都不用填（比如长按音量键
  开始、再长按停止）。不在输入框里：第一次提示"再按一次让电脑开始录音"，3 秒内再按就用手机当话筒在电脑上听写，再按停；之后直接一按开、一按停，麦克风模式关了（10 分钟没人说话会自动关）才重新要确认。
  电脑录着的时候屏幕顶上有"● 电脑录音中"（要开快速切换）。当前是别的键盘时，切到本输入法开录；停下、说过的话都上屏后再切回原键盘。
  这要开"快速切换"，没开会发一条通知，点一下去开（配插件时会问一次通知权限）。
- **设置页分 5 个标签页**：开始使用 / 识别服务 / 说话与出字 / 外观 / 电脑麦克风。改了任何一项，底部会弹"已修改 · 撤销"；
  "说话与出字""外观"页底有"恢复本页默认设置"，恢复后也能撤销。

## 编译

需要 Android SDK（`ANDROID_HOME`）；首次会自动下载 JDK 17 到 `~/.gradle`。

```bash
./gradlew assembleDebug        # 调试包：app/build/outputs/apk/debug/app-debug.apk
adb -s <设备> install -r app/build/outputs/apk/debug/app-debug.apk
```

发给别的手机用正式包：先生成一次签名密钥（保存好，丢了就没法给已装的手机升级），
把 `keystore.properties.example` 复制成 `keystore.properties` 填上密码，然后

```bash
keytool -genkeypair -v -keystore wotingbudong.jks -alias wotingbudong -keyalg RSA -keysize 2048 -validity 36500
./gradlew assembleRelease      # 产物 app/build/outputs/apk/release/app-release.apk
```

没有 `keystore.properties` 时正式包用 debug 密钥签，也能装。注意正式包和调试包签名不同，
手机上已经装了调试包的要先卸载（设置会丢）才能装正式包。

## 手机上

打开「我听不懂」App，在"开始使用"页按顺序点：在系统里启用这个输入法 → 切换到这个输入法 → 麦克风权限 →
快速切换（无障碍，可选）。然后在"识别服务"页选服务商：

- 用电脑：选"说话啊（自己电脑）"，地址填说话啊那边 `./shuohuaa status` 里"手机接口"那行（如 `http://100.x.y.z:9797`）。
  电脑 `.env` 里设了 `SHUOHUAA_API_TOKEN` 的话，"密钥 / 口令"填同样的值。模型不用填。
- 用云服务：选 OpenAI / Groq / SiliconFlow（会自动填地址和默认模型），或"其他"自己填地址，再填它的 API Key。

"测试连接"显示"主服务：连得上"就行。

没连 Tailscale 的手机（比如 USB 上的开发机）可以走 adb 桥：电脑上
`socat TCP-LISTEN:9797,bind=127.0.0.1,fork,reuseaddr TCP:<电脑tailscale地址>:9797` + `adb reverse tcp:9797 tcp:9797`，
手机里地址填 `http://127.0.0.1:9797`。

## 协议

只说 OpenAI 协议：主服务和备用都是 `POST /v1/audio/transcriptions`（multipart 字段 `file`、`model`、`response_format=json`，
16k 单声道 WAV）；"测试连接"问 `GET /v1/models`。服务商选"说话啊"时，网址后面额外带 `keep_punct` / `fillers` / `partial` /
`sid` / `seg`，接缝标点读回复头 `X-Junction` 或 JSON 的 `junction`；别家只发标准字段。任何 OpenAI 兼容的语音识别服务都能
当后端。地址写到哪一层都行：`http://电脑:9797`、`https://…/v1`，或完整的 `…/v1/audio/transcriptions`。

## 目录

```
app/src/main/kotlin/dev/shuohuaa/ime/
  VoiceInputService.kt   输入法本体：圆球、卫星、编辑条、手势、贴靠、录音会话
  OrbView.kt             圆球的绘制与动画
  Recorder.kt            AudioRecord + 静音切段 + 闲置自动停
  Transcriber.kt         上传识别（OpenAI 兼容；主服务优先、备用降级）
  Providers.kt           服务商（说话啊 / OpenAI / Groq / 硅基流动 / 其他）的默认地址和模型
  SegmentJoin.kt         段与段怎么接（空格、句间标点、大小写）
  VoiceSwitchService.kt  无障碍服务：快速切换
  MicStreamService.kt    手机当电脑的麦克风（前台服务）
  MicTileService.kt      下拉快捷开关"电脑麦克风"
  TaskerPlugin.kt / ExternalControl.kt   Tasker 插件：开始/停止/切换识别
  A11yPrompt.kt          快速切换没开时的提醒通知
  SettingsActivity.kt / Settings.kt   设置页（5 个标签页、撤销，res/xml/prefs_*.xml）与设置项
```

包名仍是 `dev.shuohuaa.ime`（改包名等于换一个 App，已装的手机要卸载重装、设置全丢）。
