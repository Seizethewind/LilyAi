# LilyAi

An Android AI assistant written in Kotlin.

- **Local model:** Qwen3-4B (GGUF) running on the phone through llama.cpp, downloaded inside the app after install.
- **Cloud model:** Groq, using an API key you enter in the app. Other external services plug into the same API registry.
- **Memory:** stored on the phone, synced to two Turso databases (memory and backup).
- **Device control:** accessibility service for reading the screen and tapping, with a Stop button and confirmations.
- **Games:** plays chess (engine-driven) and turn-based games through per-game adapters.
- **Learning:** teach mode records what you do and saves it as workflows it can replay.

## Status

Early scaffold. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the current design and [projectnotesforLilyAi.md](projectnotesforLilyAi.md) for the original vision (where the two differ, ARCHITECTURE.md wins).

## Build

Every push builds a debug APK in GitHub Actions (Actions tab, "Android CI", artifact `lilyai-debug-apk`).

Locally (JDK 17 and Gradle 8.9):

```
cd android
gradle assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/`.

## Secrets

No API keys, tokens or keystores are ever committed. Keys are entered in the app and stored encrypted on the device.
