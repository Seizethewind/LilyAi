# LilyAi - Current Architecture

This file supersedes `projectnotesforLilyAi.md` where they differ. The notes remain the product vision.

## Decisions

- **Kotlin-only Android app.** No Python layer and no Python-to-Kotlin bridge.
- **Local model:** Qwen3-4B (GGUF, Q4_K_M) through llama.cpp via JNI. It is downloaded inside the app after install and is never bundled in the APK. 64-bit (`arm64-v8a`) only. llama.cpp is built from a pinned tag by CMake.
- **Cloud model:** Groq through a key the user enters. Model IDs are settings, not hardcoded. Other external services use the same API registry (`api/`).
- **Engine modes:** Auto (local first, Groq if local fails), Local only, Groq only.
- **Keys and tokens:** entered at runtime and stored encrypted (Android Keystore with encrypted preferences). Never in the APK or the repo.
- **Games:** moves come from an engine (chess) or game rules (turn-based), not from the LLM. One adapter per game.
- **Learning:** teach mode records a demonstration, which becomes a saved workflow that can be replayed and re-planned. The model itself is never retrained.

## Memory

- Memories are created only on request: "remember ..." in chat, the model's `remember` tool (which asks for approval), or the Memories screen. Nothing is inferred or saved silently.
- Stored in a private SQLite database on the phone, which is the source of truth.
- The local model always sees memories. Groq sees them only if the user turns on "Share memories with Groq" (off by default). Tool results that read memories are blocked from the cloud model in the same way.
- **Turso, two databases:** one syncs memories (last write wins, deletions kept as tombstones), the other holds encrypted backups (10 newest kept). The app talks to Turso's SQL-over-HTTP API.
- **Encryption:** everything is encrypted on the phone (AES-256-GCM) with a key derived from a user passphrase (PBKDF2). Turso only stores ciphertext. The salt and a passphrase check value live in each database's `meta` table, so a new phone with the same passphrase can restore. A lost passphrase cannot be recovered.

## Tools and safety

- Every tool declares a permission level: `SAFE` (runs at once), `CONFIRM` (the user approves each time in a dialog; no answer in a minute means no), or `BLOCKED`.
- `SafetyPolicy` decides before any tool runs and caps tool calls per request.
- **Stop:** the in-app Stop button, the floating button over other apps, and the notification action all call one emergency stop. It halts the safety policy (no further actions), denies pending approvals and ends local generation or abandons a cloud request.
- Tool calls use a plain text protocol (`TOOL: {...}`) that works with the small local model; tool replies are never shown as chat text.
- A foreground service (with notification and Stop action) starts only when a request takes more than about two seconds.

## Agent loop

Observe, Understand, Plan, Act, Verify, Recover, Respond. Every plan step carries an explicit expected result that the verifier checks. Today the agent chats and runs tools; planning and device control arrive in later phases.

## Package layout

All code is in `android/app/src/main/kotlin/com/seizethewind/lilyai/`:

| Package | Role |
|---|---|
| `ui` | Screens (chat, settings, memories, cloud), overlay Stop button |
| `core` | Agent, state |
| `prompt` | Prompt building |
| `memory` | Local memory, memory manager, cloud sync, encryption |
| `planner`, `perception` | Planning and screen reading (later phases) |
| `tools` | Tool registry, executor and tools |
| `safety` | Policy, permission levels, confirmations |
| `accessibility`, `screen`, `actions` | Android body: service, capture, touch (later phases) |
| `permissions`, `notifications`, `service` | Special permissions, notification, foreground service |
| `settings` | App settings |
| `api` | API registry, key store, Groq and Turso providers |
| `inference`, `model` | llama.cpp engine and in-app model download |
| `games` | Game adapters, chess engine wrapper, board reader (later phases) |
| `learning` | Demonstration recorder, workflow store and replayer (later phases) |

## Permissions

Internet, notifications, foreground service, and display over other apps (for the floating Stop button; optional). Later: accessibility service, battery-optimization exemption and microphone. No broad storage access: model and databases live in app-private storage.

## Build

Android project root is `android/`. Gradle 8.9, Android Gradle Plugin 8.5.2, Kotlin 1.9.24, JDK 17, NDK 26.1, compileSdk 34, minSdk 26. CI builds a debug APK on every push.

## Signing

Release signing reads `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` from the environment, set by a release workflow from repository secrets. Debug builds use the default debug key.
