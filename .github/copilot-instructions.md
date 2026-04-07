# Project Guidelines

## Overview

MEERA-AI is a Telegram bot with a human-like AI persona ("Meera") plus an Android control panel app. Both share Firestore as the source of truth and use the same Fernet encryption for API keys.

## Architecture

```
MEERA-AI/
├── Python Backend (Telegram bot via aiogram 3.x, deployed on Railway)
│   ├── main.py          — Entry point, polling loop, lifecycle hooks
│   ├── config.py        — Env var loading + validation
│   ├── bot/             — Telegram command handlers + message routing
│   └── services/        — Firebase, Ollama, ElevenLabs, encryption, STT, proactive messaging
└── MEERAAPP/            — Android Jetpack Compose app (Kotlin, min SDK 26)
    └── app/src/main/java/com/example/meeraai/
        ├── service/     — Bot engine mirror, Firebase REST, encryption, DNS
        ├── viewmodel/   — StateFlow-based state management
        ├── ui/screens/  — Login, Home, Settings, Logs
        └── data/        — Models + DataStore preferences
```

See [README.md](../README.md) for full setup guide, environment variables, and troubleshooting.

## Build and Test

### Python Backend

```bash
# Setup
python -m venv venv
venv\Scripts\activate        # Windows
pip install -r requirements.txt

# Run
python main.py

# Generate encryption key
python generate_key.py
```

No test suite exists yet. The bot uses long-polling (not webhooks).

### Android App

```bash
cd MEERAAPP
./gradlew assembleDebug      # Build debug APK
./gradlew assembleRelease    # Build release APK
```

- AGP 9.1.0, Kotlin 2.2.10, Compose BOM 2026.02.01
- Version catalog in `MEERAAPP/gradle/libs.versions.toml`

## Code Style

### Python

- **Async everywhere**: All I/O through `asyncio`/`await` (aiogram, Firebase, Ollama, file ops)
- **f-strings** for string formatting; **HTML parse mode** for Telegram messages
- **Logging**: Per-module loggers, dual output (stdout + `meera_bot.log`), library noise filtered to WARNING
- **Error handling**: Raise `ValueError` with category strings (`"no_keys"`, `"quota_exceeded"`) → humanized via `error_messages.py`

### Kotlin (Android)

- **Jetpack Compose** with Material 3 for all UI
- **Coroutines** with `viewModelScope` and `Dispatchers.IO` for blocking work
- **StateFlow** for reactive UI binding
- **Firestore REST API** (not Firebase SDK) with manual JWT/OAuth2 auth

## Conventions

### Secrets & Credentials

- **User API keys** (Ollama, ElevenLabs): Fernet-encrypted in Firestore, per-user isolation
- **Backend secrets**: `.env` file (never committed). Firebase credentials support base64 encoding for cloud deploy
- **Android secrets**: DataStore for tokens; `firebase-credentials.json` in assets
- **Never log or display full API keys** — mask to first 4 + last 4 chars

### Firestore Collections

| Collection | Purpose |
|------------|---------|
| `users/{user_id}` | Profile, encrypted keys, settings |
| `chats/{user_id}/messages/{msg_id}` | Chat history (timestamped) |
| `error_messages/{category}` | Humanized error variants |
| `app_config/bot` | Live bot name + system prompt |

### Personality System

Message count drives a **comfort tier** (`stranger` → `acquaintance` → `comfortable` → `close`) that affects:
- System prompt overlay and reply style
- Voice reply probability
- Sticker sending probability
- Proactive messaging frequency

### Multi-Key Failover

Both Ollama and ElevenLabs support multiple API keys per user. On rate-limit or auth failure, the system auto-rotates to the next key. If all fail, degrade gracefully (voice → text fallback).

### FSM Pattern

Bot commands use aiogram's FSM (Finite State Machine) with `MemoryStorage` for multi-step interactions (key add, profile edit, voice selection).

## Deployment

- **Platform**: Railway (Nixpacks builder)
- **Config**: `railway.toml` — restart on failure, max 3 retries
- **Process**: `python main.py` (single worker, long-polling)
