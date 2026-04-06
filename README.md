# 🤖 Meera AI — Telegram Bot

A production-ready Telegram bot with a human-like AI persona named "Meera". Features text chat, voice messages, per-user API key isolation, and natural conversational AI.

## Architecture

```
User (Telegram) → aiogram Bot → Ollama Cloud (Gemini) → Response
                              → Google STT (voice input)
                              → ElevenLabs (voice output)
                              → Firebase (storage)
```

## Project Structure

```
MEERA-AI/
├── main.py                  # Entry point
├── config.py                # Configuration
├── generate_key.py          # Encryption key generator
├── requirements.txt         # Dependencies
├── .env.example             # Environment template
├── bot/
│   ├── commands.py          # /start, /profile, /add_ollama_key, etc.
│   └── handlers.py          # Text & voice message handlers
└── services/
    ├── firebase_service.py  # Firestore CRUD
    ├── ollama_service.py    # AI with persona + failover
    ├── elevenlabs_service.py # Text-to-Speech
    ├── stt_service.py       # Speech-to-Text (Google)
    ├── key_manager.py       # Per-user API key management
    ├── encryption.py        # Fernet encryption for keys
    └── error_messages.py    # Humanized error responses
```

---

## 🛠 Manual Setup Guide

### Step 1: Python Environment

```bash
cd MEERA-AI
python -m venv venv

# Windows
venv\Scripts\activate

# Linux/Mac
source venv/bin/activate

pip install -r requirements.txt
```

### Step 2: Create Telegram Bot

1. Open Telegram, search for **@BotFather**
2. Send `/newbot`
3. Follow prompts — choose a name and username
4. Copy the **bot token** you receive

### Step 3: Firebase Setup

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Create a new project (or use existing)
3. Go to **Project Settings → Service Accounts**
4. Click **"Generate new private key"**
5. Save the JSON file as `firebase-credentials.json` in the project root
6. Go to **Firestore Database** → Click **"Create database"**
7. Choose **production mode** and select a region

#### Firestore Security Rules (set in Firebase Console → Firestore → Rules):

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Only server (admin SDK) can access — no client access
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

This is safe because we use Firebase Admin SDK (server-side), which bypasses rules.

### Step 4: Google Cloud Speech-to-Text

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a project (or use existing)
3. Enable the **Cloud Speech-to-Text API**:
   - Navigation menu → APIs & Services → Library
   - Search "Speech-to-Text" → Enable
4. Create a service account:
   - Go to IAM & Admin → Service Accounts
   - Create account → give it a name
   - Grant role: **Cloud Speech Client**
   - Create key → JSON → Download
5. Save the JSON file as `google-stt-credentials.json` in project root

### Step 5: Install ffmpeg (for voice conversion)

**Windows:**
```bash
# Using chocolatey
choco install ffmpeg

# Or download from https://ffmpeg.org/download.html and add to PATH
```

**Linux:**
```bash
sudo apt install ffmpeg
```

**Mac:**
```bash
brew install ffmpeg
```

### Step 6: Generate Encryption Key

```bash
python generate_key.py
```

Copy the output key.

### Step 7: Configure Environment

```bash
# Copy the example env file
copy .env.example .env    # Windows
cp .env.example .env      # Linux/Mac
```

Edit `.env` with your values:

```env
TELEGRAM_BOT_TOKEN=123456:ABC-DEF...your-bot-token
FIREBASE_CREDENTIALS_PATH=firebase-credentials.json
ENCRYPTION_KEY=your-generated-fernet-key
GOOGLE_APPLICATION_CREDENTIALS=google-stt-credentials.json
ELEVENLABS_DEFAULT_VOICE_ID=JBFqnCBsd6RMkjVDRZzb
OLLAMA_HOST=https://your-ollama-cloud-endpoint
MAX_CHAT_HISTORY=20
TYPING_DELAY_MIN=1.0
TYPING_DELAY_MAX=3.0
```

### Step 8: Run the Bot

```bash
python main.py
```

---

## 📱 User Commands (in Telegram)

| Command | Description |
|---------|-------------|
| `/start` | Start and see setup instructions |
| `/help` | List all commands |
| `/add_ollama_key` | Add your Ollama API key |
| `/add_elevenlabs_key` | Add ElevenLabs API key |
| `/list_keys` | View saved keys (masked) |
| `/remove_key` | Remove a specific key |
| `/profile` | View your profile |
| `/setname` | Set your display name |
| `/setbio` | Set your bio |
| `/tone` | Configure tone preferences |
| `/talk` | Toggle voice-only reply mode |

---

## 🔑 Per-User API Key Isolation

Every user adds their **own** API keys:
- Ollama keys for AI chat
- ElevenLabs keys for voice replies

Keys are **encrypted with Fernet** before storage in Firestore. No user can access another user's keys. If a key fails (quota/invalid), the system automatically rotates to the next key.

---

## 🗣 Voice Behavior

Meera decides whether to reply with voice or text based on:
- **User sent voice** → 90% chance of voice reply (like a real person)
- **Casual text** → 15% random voice (feels human)
- **Contextual** → AI model decides based on conversation mood
- **`/talk` mode ON** → always voice
- If TTS fails → graceful text fallback

---

## 🧠 Memory & Personalization

- Chat history (last 20 messages) sent as context with every AI call
- User profile (name, bio, tone, reply length) shapes Meera's personality
- Meera adapts: formal/casual, short/long, empathetic/playful

---

## ⚠️ Troubleshooting

| Issue | Fix |
|-------|-----|
| Bot doesn't respond | Check `TELEGRAM_BOT_TOKEN` in `.env` |
| "No API keys" error | User must run `/add_ollama_key` first |
| Voice not working | Check ffmpeg is installed, ElevenLabs key is valid |
| STT fails | Verify `google-stt-credentials.json` and API is enabled |
| Firebase error | Verify `firebase-credentials.json` path and Firestore is created |
