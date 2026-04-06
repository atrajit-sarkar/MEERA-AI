"""Firebase Firestore service layer for user data, chats, and error messages."""

import base64
import json
import logging
from typing import Any

import firebase_admin
from firebase_admin import credentials, firestore
from google.cloud import firestore as gcloud_firestore

from config import Config

logger = logging.getLogger(__name__)

_app = None
_db = None


def _get_firebase_credentials():
    """Load Firebase credentials from base64 env var or file path."""
    # Priority 1: Base64-encoded JSON string (for Railway / cloud deploys)
    if Config.FIREBASE_CREDENTIALS_JSON:
        try:
            decoded = base64.b64decode(Config.FIREBASE_CREDENTIALS_JSON)
            return credentials.Certificate(json.loads(decoded))
        except Exception as e:
            logger.error(f"Failed to decode FIREBASE_CREDENTIALS_JSON: {e}")
            raise

    # Priority 2: File path (for local development)
    if Config.FIREBASE_CREDENTIALS_PATH:
        return credentials.Certificate(Config.FIREBASE_CREDENTIALS_PATH)

    raise RuntimeError("No Firebase credentials configured")


def init_firebase():
    """Initialize Firebase and return async Firestore client."""
    global _app, _db
    if _app is None:
        cred = _get_firebase_credentials()
        _app = firebase_admin.initialize_app(cred)
    if _db is None:
        firebase_cred = _app.credential.get_credential()
        project_id = _app.project_id
        _db = gcloud_firestore.AsyncClient(
            project=project_id,
            credentials=firebase_cred,
            database=Config.FIREBASE_DATABASE_ID,
        )
    return _db


def get_db():
    if _db is None:
        return init_firebase()
    return _db


# ─── User Operations ───────────────────────────────────────────────

async def get_user(user_id: int) -> dict | None:
    db = get_db()
    doc = await db.collection("users").document(str(user_id)).get()
    return doc.to_dict() if doc.exists else None


async def create_or_update_user(user_id: int, data: dict[str, Any]) -> None:
    db = get_db()
    await db.collection("users").document(str(user_id)).set(data, merge=True)


async def get_user_api_keys(user_id: int) -> dict:
    """Return {ollama_keys: [...], elevenlabs_keys: [...]} encrypted."""
    user = await get_user(user_id)
    if not user:
        return {"ollama_keys": [], "elevenlabs_keys": []}
    return {
        "ollama_keys": user.get("ollama_keys", []),
        "elevenlabs_keys": user.get("elevenlabs_keys", []),
    }


async def add_api_key(user_id: int, key_type: str, encrypted_key: str) -> None:
    """key_type: 'ollama_keys' or 'elevenlabs_keys'"""
    db = get_db()
    user_ref = db.collection("users").document(str(user_id))
    doc = await user_ref.get()
    keys = []
    if doc.exists:
        keys = doc.to_dict().get(key_type, [])
    keys.append(encrypted_key)
    await user_ref.set({key_type: keys}, merge=True)


async def remove_api_key(user_id: int, key_type: str, index: int) -> bool:
    db = get_db()
    user_ref = db.collection("users").document(str(user_id))
    doc = await user_ref.get()
    if not doc.exists:
        return False
    keys = doc.to_dict().get(key_type, [])
    if 0 <= index < len(keys):
        keys.pop(index)
        await user_ref.set({key_type: keys}, merge=True)
        return True
    return False


# ─── Chat History ──────────────────────────────────────────────────

async def get_chat_history(user_id: int, limit: int | None = None) -> list[dict]:
    db = get_db()
    limit = limit or Config.MAX_CHAT_HISTORY
    docs = (
        db.collection("chats")
        .document(str(user_id))
        .collection("messages")
        .order_by("timestamp", direction=gcloud_firestore.Query.DESCENDING)
        .limit(limit)
    )
    results = []
    async for doc in docs.stream():
        results.append(doc.to_dict())
    results.reverse()
    return results


async def save_message(user_id: int, role: str, content: str) -> None:
    db = get_db()
    await (
        db.collection("chats")
        .document(str(user_id))
        .collection("messages")
        .add({
            "role": role,
            "content": content,
            "timestamp": gcloud_firestore.SERVER_TIMESTAMP,
        })
    )


# ─── Error Messages ───────────────────────────────────────────────

async def get_error_messages(category: str) -> list[str]:
    db = get_db()
    doc = await db.collection("error_messages").document(category).get()
    if doc.exists:
        return doc.to_dict().get("messages", [])
    return []


async def seed_error_messages() -> None:
    """Seed default humanized error messages if they don't exist."""
    db = get_db()
    defaults = {
        "quota_exceeded": [
            "Hey… I think I'm a bit tired right now 😅 Try again soon?",
            "Oops, looks like I've been talking too much today 😄 Give me a moment!",
            "I need a tiny break 💤 My quota ran out, but I'll be back!",
        ],
        "invalid_key": [
            "Something feels off on my side… maybe check your API settings?",
            "Hmm, I can't seem to connect properly. Could you check your API key? 🔑",
            "I'm having trouble with authentication… mind double-checking your key?",
        ],
        "network_error": [
            "I think the internet is playing tricks on us 😕 Try again?",
            "Connection hiccup! Let's try that again in a sec 🌐",
            "Oops, lost my train of thought there… network issue! Try once more?",
        ],
        "unknown_error": [
            "Something unexpected happened 😅 But don't worry, I'll figure it out!",
            "Hmm, that's weird… Let me try again if you send your message once more?",
            "I got a bit confused there 🤔 Could you try again?",
        ],
        "stt_error": [
            "I couldn't quite hear that clearly 🎤 Could you try sending the voice message again?",
            "Hmm, the audio was a bit tricky for me. Mind trying once more?",
            "Sorry, I had trouble understanding that voice message. Try again? 🎧",
        ],
        "tts_error": [
            "I wanted to talk back but my voice is being shy today 😅 Here's text instead!",
            "My voice module hiccupped! Sending you text for now 📝",
        ],
    }
    for category, messages in defaults.items():
        doc_ref = db.collection("error_messages").document(category)
        doc = await doc_ref.get()
        if not doc.exists:
            await doc_ref.set({"messages": messages})
            logger.info(f"Seeded error messages for category: {category}")


# ─── User Profile ─────────────────────────────────────────────────

async def update_user_profile(user_id: int, name: str | None = None, bio: str | None = None) -> None:
    updates = {}
    if name is not None:
        updates["profile_name"] = name
    if bio is not None:
        updates["profile_bio"] = bio
    if updates:
        await create_or_update_user(user_id, updates)


async def get_user_profile(user_id: int) -> dict:
    user = await get_user(user_id)
    if not user:
        return {"profile_name": None, "profile_bio": None, "tone": "casual", "reply_length": "medium"}
    return {
        "profile_name": user.get("profile_name"),
        "profile_bio": user.get("profile_bio"),
        "tone": user.get("tone", "casual"),
        "reply_length": user.get("reply_length", "medium"),
    }


async def update_tone_profile(user_id: int, tone: str, reply_length: str) -> None:
    await create_or_update_user(user_id, {"tone": tone, "reply_length": reply_length})
