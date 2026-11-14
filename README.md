# Murmur

A free, open-source read-aloud app for Pixel (and other Android 10+) phones, using **on-device neural voices**. No accounts, ads, cloud or subscriptions. Nothing you read leaves the phone.

## Features

- **Share → it starts talking.** Share an article, a text selection, a PDF or an EPUB to *Read aloud now*. Speech starts within a second or two, you stay in the app you shared from, and there's no play button to tap. Use the media notification, lock screen or headset to pause or skip.
- **"Read aloud" in every text-selection menu** (`ACTION_PROCESS_TEXT`).
- **Neural voices that run on the phone**, via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx):
  - **Kokoro**: the most natural-sounding option, with 11 English speakers (v0.19) or 53 multilingual speakers (v1.0).
  - **Piper**: small, fast voices in many languages.
- **Imports**: pasted text, web links (article extraction with Mozilla Readability), PDF (header, footer and hyphenation cleanup), EPUB (spine order, chapters) and plain or HTML files.
- **Reader**: highlights the sentence being read and follows along. Tap any sentence to jump there. Includes chapter and sentence skip, 0.5–3× speed (pitch preserved), a sleep timer, a queue, and progress saved per document.
- **Background playback** through a Media3 `MediaSessionService`, with lock-screen, Bluetooth and Android Auto controls, audio-focus handling, and a pause when headphones are unplugged.
- **System TTS engine**: choose Murmur under *Settings › Accessibility › Text-to-speech output* and TalkBack, Chrome, e-book readers and navigation apps will use these voices too.

## Build

```bash
./scripts/fetch-sherpa-onnx.sh   # downloads the sherpa-onnx AAR (version in gradle.properties)
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:text:test        # pure-JVM unit tests for the text pipeline
```

Requirements: JDK 17+ and the Android SDK (API 35). CI (`.github/workflows/ci.yml`) builds debug and release APKs and uploads them as artifacts.

Voices are not bundled. They're downloaded on demand (30–330 MB) from sherpa-onnx's `tts-models` GitHub release, which is listed in `app/src/main/assets/voices.json`.

## Layout

```
core/text/   Pure Kotlin/JVM: Document model, sentence segmentation, speech normalization,
             web/EPUB/PDF/plain-text extraction, voice catalog, PCM helpers (unit-tested)
app/
  voice/     VoiceRepository (catalog + installed), VoiceDownloadWorker, Synthesizer (sherpa-onnx)
  library/   LibraryStore (JSON files + progress), Importer (URIs, URLs, PDF via pdfbox-android)
  playback/  Reader (synthesize-ahead → AudioTrack pipeline), SpeechPlayer (Media3), PlaybackService
  share/     ShareToSpeakActivity: the headless "share → speak now" entry point
  system/    MurmurTtsService (android.speech.tts engine) + engine check/sample activities
  ui/        Compose screens: Library, Reader, Voices, Settings
```

### How "share → speak" works

`ShareToSpeakActivity` is translucent and never draws. In `onCreate` it reads the shared payload and calls `startForegroundService(PlaybackService, ACTION_SPEAK)`. This is allowed because the app is briefly in the foreground. It then shows a toast and calls `finish()`. The service immediately posts a "Preparing…" notification (`mediaPlayback` type), shows a placeholder in the media session, fetches and extracts the content, saves it to the library, and starts reading. To keep time-to-first-word low, the first sentence is split at its first clause, so the model only has to synthesize a few words before audio begins.

### Synthesis pipeline

`Reader` runs a producer coroutine that synthesizes up to four segments ahead on a dedicated TTS thread, and a consumer that writes float PCM to a streaming `AudioTrack` in roughly 100 ms slices. Each pause or seek starts a fresh track, so stale audio never plays. Speeds above 1.6× are produced by the model at 1.6× and then time-stretched by `AudioTrack` with pitch preserved.

## Licenses

Murmur is GPL-3.0 because the sherpa-onnx voice frontends include espeak-ng (GPL-3.0). Each voice model has its own license, which is shown on the Voices screen. Some Piper voices are non-commercial only.
