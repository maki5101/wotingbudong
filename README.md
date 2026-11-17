# Wotingbudong — a voice keyboard for Android with no keyboard

[中文说明](README.zh.md) · MIT License

An Android input method that is just a draggable floating orb: tap it, talk, and the text appears in the focused
field. Recognition is not done on the phone — audio goes to whatever OpenAI-compatible speech-to-text service you
configure: your own computer running [Shuohuaa](https://github.com/maki5101/shuohuaa) (recommended; adds
punctuation across segments, filler removal and live text), or a cloud service such as OpenAI, Groq or SiliconFlow.

## Features

- **No keyboard, only an orb.** The IME window covers the screen but is transparent and touch-through; the app
  underneath is not pushed up. The orb snaps to the left / right / centre and doubles as a status light
  (idle / recording / transcribing / error).
- **Tap to toggle, hold to talk.** Speech is cut at pauses, each segment is uploaded at once and committed in
  order; optional live text shows what you are saying while you speak.
- **What you said comes first.** Pressing Enter while recording stops it, waits until everything spoken is on
  screen, then sends. Undo removes the last sentence and lets you say it again.
- **Any OpenAI-compatible backend**: provider + URL + API key + model for a primary and a fallback service, with
  retries and automatic fallback.
- **Phone as your computer's microphone**: stream the phone mic to Shuohuaa over the network, with a
  quick-settings tile, auto-off when the computer stops using it, and clear errors when it cannot connect.
- **Automation**: a Tasker / Locale plugin (start / stop / toggle), an optional accessibility service to switch
  to this keyboard from another one, and — when no text field is focused — dictation on the computer using the
  phone as the mic.
- **9 interface languages**, settings in tabs with undo.

## Build

Needs the Android SDK (`ANDROID_HOME`); JDK 17 is downloaded into `~/.gradle` on first run.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a signed release build, create a keystore, copy `keystore.properties.example` to `keystore.properties`,
fill it in and run `./gradlew assembleRelease`.

## Set up on the phone

Open the app and follow the **Getting started** tab: enable the keyboard, switch to it, grant the microphone
permission. Then in the **Service** tab choose a provider:

- **Shuohuaa (your computer)** — URL is the "Phone API" line of `./shuohuaa status`
  (e.g. `http://100.x.y.z:9797`); model can stay empty.
- **OpenAI / Groq / SiliconFlow / other** — pick it (URL and default model are filled in) and enter your API key.

## Protocol

Plain OpenAI speech-to-text: `POST /v1/audio/transcriptions` (multipart `file`, `model`, `response_format=json`,
16 kHz mono WAV) and `GET /v1/models` for the connection test. Only when the provider is Shuohuaa, a few
extension parameters are added to the query string (`keep_punct`, `fillers`, `partial`, `sid`, `seg`).

## Layout

```
app/src/main/kotlin/dev/shuohuaa/ime/
  VoiceInputService.kt   the input method: orb, gestures, recording session
  Recorder.kt            AudioRecord + pause-based segmentation
  Transcriber.kt         uploads (OpenAI-compatible; primary first, fallback second)
  SegmentJoin.kt         spacing / punctuation / capitalisation between segments
  MicStreamService.kt    phone-as-microphone foreground service
  TaskerPlugin.kt, ExternalControl.kt   automation entry points
  SettingsActivity.kt    settings (5 tabs, undo)
```
