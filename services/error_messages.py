"""Humanized error message system — fetches friendly errors from Firebase."""

import logging
import random

from cachetools import TTLCache

from services.firebase_service import get_error_messages

logger = logging.getLogger(__name__)

# Cache error messages for 10 minutes to avoid repeated Firestore reads
_cache: TTLCache = TTLCache(maxsize=50, ttl=600)

# Fallback messages if Firebase is unavailable
_FALLBACK = {
    "quota_exceeded": "I'm a bit overwhelmed right now 😅 Try again in a moment?",
    "invalid_key": "Hmm, I can't connect properly. Check your API key? 🔑",
    "network_error": "Connection issue on my end 😕 Try again?",
    "unknown_error": "Something went wrong 😅 Try again?",
    "stt_error": "Couldn't understand that voice message 🎤 Try again?",
    "tts_error": "My voice isn't working right now 😅 Here's text instead!",
    "no_keys": "You haven't added any API keys yet! Use /add_ollama_key to get started 🔑",
}


async def get_friendly_error(category: str) -> str:
    """Get a random humanized error message for the given category."""
    # Check cache first
    if category in _cache:
        messages = _cache[category]
    else:
        try:
            messages = await get_error_messages(category)
            if messages:
                _cache[category] = messages
        except Exception as e:
            logger.error(f"Failed to fetch error messages from Firebase: {e}")
            messages = []

    if messages:
        return random.choice(messages)

    return _FALLBACK.get(category, _FALLBACK["unknown_error"])
