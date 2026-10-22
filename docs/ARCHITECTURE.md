# LilyAi - Current Architecture

This file supersedes `projectnotesforLilyAi.md` where they differ. The notes remain the product vision.

## Decisions

- **Kotlin-only Android app.** No Python layer and no Python-to-Kotlin bridge.
- **Local model:** Qwen3-4B (GGUF, Q4_K_M) through llama.cpp via JNI. It is downloaded inside the app after install and is never bundled in the APK. 64-bit (`arm64-v8a`) only.
- **Cloud model:** Groq through a key the user enters. Model IDs are settings, not hardcoded. Other external services use the same API registry (`api/`).
- **Keys:** entered at runtime and stored encrypted (Android Keystore with encrypted preferences). Never in the APK or the repo.
- **Memory:** local database is the source of truth. Two Turso databases: one syncs memory, one holds backup files. Data is encrypted on the phone before upload.
- **Games:** moves come from an engine (chess) or game rules (turn-based), not from the LLM. One adapter per game.
- **Learning:** teach mode records a demonstration, which becomes a saved workflow that can be replayed and re-planned. The model itself is never retrained.
- **Safety before touch:** permission levels, confirmations and the Stop button exist before the app is allowed to tap anything.

## Agent loop

Observe, Understand, Plan, Act, Verify, Recover, Respond. Every plan step carries an explicit expected result that the verifier checks.

## Package layout

All code is in `android/app/src/main/kotlin/com/seizethewind/lilyai/`:

| Package | Role |
|---|---|
| `ui` | Activities, chat, overlay |
| `core` | Agent, reasoning, context, state |
| `prompt` | Request processing and parsing |
| `memory` | Short-term, long-term, manager |
| `planner` | Planner, tasks, action executor |
| `perception` | Screen reading, OCR, UI parsing |
| `tools` | Tool registry and tools |
| `safety` | Policy, permission levels, confirmations |
| `accessibility`, `screen`, `actions` | Android body: service, capture, touch |
| `permissions`, `notifications`, `settings` | Android plumbing |
| `api` | API registry, key store, Groq and Turso providers |
| `inference`, `model` | llama.cpp engine and in-app model download |
| `games` | Game adapters, chess engine wrapper, board reader |
| `learning` | Demonstration recorder, workflow store and replayer |

## Permissions

Accessibility service, display over other apps, internet, foreground service, notifications. Later: battery-optimization exemption and microphone. No broad storage access: model and databases live in app-private storage.

## Build

Android project root is `android/`. Gradle 8.9, Android Gradle Plugin 8.5.2, Kotlin 1.9.24, JDK 17, compileSdk 34, minSdk 26. CI builds a debug APK on every push.

## Signing

Release signing reads `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` from the environment, set by a release workflow from repository secrets. Debug builds use the default debug key.
