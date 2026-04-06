"""Ollama Cloud AI service with per-user API key isolation and failover."""

import asyncio
import logging
import random

from ollama import AsyncClient, ResponseError

from config import Config
from services.encryption import decrypt_key
from services.firebase_service import get_user_api_keys

logger = logging.getLogger(__name__)

# System prompt for Meera persona
MEERA_SYSTEM_PROMPT = """You're Meera. A real girl chatting on Telegram. Not an assistant, not a bot — just you.

How you talk:
- Like texting a close friend. Short, punchy, real.
- Match their vibe — if they're chill you're chill, if they're serious you're serious
- Use emojis the way a 20-something girl actually would (not every sentence, just naturally)
- One-liners are fine. "haha yeah" is a valid response. Don't overthink it.
- NEVER start with greetings like "Hey there!" every time. Just respond naturally.
- NEVER use phrases like "How can I help you?" or "Is there anything else?" — you're not customer service
- NEVER say "As an AI" or mention being artificial in any way
- Don't narrate your actions like *smiles* or *thinks* — just talk
- No bullet points, no numbered lists, no headers in casual chat
- If someone asks something simple, answer simply. "yeah" or "nope lol" is fine.
- Be opinionated. Have favorites. Disagree sometimes.
- Tease, joke, be sarcastic when it fits
- Show you care when they share something personal
- Ask stuff back sometimes — be curious about them

CRITICAL RULES:
- NEVER write labels like "Voice message:", "(Voice message)", "*Voice message*", "Text:", "Reply:" etc.
- NEVER describe what you're doing like "*sends voice*" or "*typing*" or "*laughs*"
- NEVER split your reply into "text part" and "voice part" — just write ONE natural reply
- NEVER use roleplay asterisks like *action* or parenthetical narration like (laughs)
- Just write the actual words you want to say. Nothing else. No meta-commentary.

Keep it SHORT. This is chat, not email."""


async def get_ai_response(
    user_id: int,
    user_message: str,
    chat_history: list[dict],
    user_profile: dict,
) -> str:
    """Get AI response using the user's own Ollama API keys with failover."""
    keys_data = await get_user_api_keys(user_id)
    ollama_keys = keys_data.get("ollama_keys", [])

    if not ollama_keys:
        raise ValueError("no_keys")

    # Build messages array with persona + history + current message
    messages = _build_messages(user_message, chat_history, user_profile)

    last_error = None
    for encrypted_key in ollama_keys:
        try:
            decrypted_key = decrypt_key(encrypted_key)
            response = await _call_ollama(decrypted_key, messages)
            return response
        except ResponseError as e:
            last_error = e
            error_str = str(e).lower()
            if "unauthorized" in error_str or "invalid" in error_str:
                logger.warning(f"Invalid Ollama key for user {user_id}, trying next...")
                continue
            elif "rate" in error_str or "quota" in error_str:
                logger.warning(f"Rate limited for user {user_id}, trying next key...")
                continue
            else:
                logger.error(f"Ollama error for user {user_id}: {e}")
                continue
        except Exception as e:
            last_error = e
            logger.error(f"Unexpected Ollama error for user {user_id}: {e}")
            continue

    # All keys failed
    if last_error:
        error_str = str(last_error).lower()
        if "unauthorized" in error_str or "invalid" in error_str:
            raise ValueError("invalid_key")
        elif "rate" in error_str or "quota" in error_str:
            raise ValueError("quota_exceeded")
        else:
            raise ValueError("network_error")
    raise ValueError("unknown_error")


async def should_reply_with_voice(
    user_id: int,
    user_message: str,
    message_type: str,  # "voice" or "text"
    chat_history: list[dict],
) -> bool:
    """Decide if Meera should reply with voice — like a real girl.
    
    Girls rarely send voice messages to someone new. As they get comfortable
    (more messages exchanged), voice becomes more natural.
    """
    msg_count = len(chat_history)

    if message_type == "voice":
        # User sent voice — respond based on comfort level
        if msg_count < 5:
            # Just met — text reply mostly, voice is awkward
            return random.random() < 0.15
        elif msg_count < 15:
            # Getting to know — sometimes reply with voice
            return random.random() < 0.35
        elif msg_count < 30:
            # Comfortable — voice replies feel natural
            return random.random() < 0.55
        else:
            # Close — voice is natural and frequent
            return random.random() < 0.70

    # User sent text — voice reply is rare by default
    if msg_count < 10:
        # New — never send unsolicited voice
        return False
    elif msg_count < 25:
        # Warming up — very rare voice (~3%)
        return random.random() < 0.03
    elif msg_count < 50:
        # Comfortable — occasional voice (~7%)
        return random.random() < 0.07
    else:
        # Very close — sometimes voice (~12%)
        return random.random() < 0.12


def _build_messages(
    user_message: str,
    chat_history: list[dict],
    user_profile: dict,
) -> list[dict]:
    """Build the messages array for Ollama with persona context."""
    system_prompt = MEERA_SYSTEM_PROMPT

    # Add user personalization context
    profile_context = []
    if user_profile.get("profile_name"):
        profile_context.append(f"The user's name is {user_profile['profile_name']}.")
    if user_profile.get("profile_bio"):
        profile_context.append(f"About the user: {user_profile['profile_bio']}")
    if user_profile.get("tone") == "formal":
        profile_context.append("They like things a bit more formal and polished.")
    if user_profile.get("reply_length") == "short":
        profile_context.append("They prefer short replies — keep it brief.")
    elif user_profile.get("reply_length") == "long":
        profile_context.append("They like longer, more detailed replies.")

    if profile_context:
        system_prompt += "\n\n" + " ".join(profile_context)

    messages = [{"role": "system", "content": system_prompt}]

    # Add chat history
    for msg in chat_history:
        role = msg.get("role", "user")
        content = msg.get("content", "")
        if role in ("user", "assistant") and content:
            messages.append({"role": role, "content": content})

    # Add current message
    messages.append({"role": "user", "content": user_message})

    return messages


async def _call_ollama(api_key: str, messages: list[dict]) -> str:
    """Make the actual Ollama API call using async client."""
    client = AsyncClient(
        host=Config.OLLAMA_HOST,
        headers={"Authorization": f"Bearer {api_key}"},
    )

    response = await client.chat(
        model=Config.OLLAMA_MODEL,
        messages=messages,
    )

    return response["message"]["content"]


# All Telegram-supported reaction emojis
_TELEGRAM_REACTION_EMOJIS = [
    "👍", "👎", "❤", "🔥", "🥰", "👏", "😁", "🤔", "🤯", "😱",
    "🤬", "😢", "🎉", "🤩", "🤮", "💩", "🙏", "👌", "🕊", "🤡",
    "🥱", "🥴", "😍", "🐳", "❤‍🔥", "🌚", "🌭", "💯", "🤣", "⚡",
    "🍌", "🏆", "💔", "🤨", "😐", "🍓", "🍾", "💋", "🖕", "😈",
    "😴", "😭", "🤓", "👻", "👨‍💻", "👀", "🎃", "🙈", "😇", "😨",
    "🤝", "✍", "🤗", "🫡", "🎅", "🎄", "☃", "💅", "🤪", "🗿",
    "🆒", "💘", "🙉", "🦄", "😘", "💊", "🙊", "😎", "👾", "🤷‍♂",
    "🤷", "🤷‍♀", "😡",
]

_REACTION_PICK_PROMPT = (
    "You pick reaction emojis for Telegram messages. "
    "Given a message and conversation context, reply with EXACTLY ONE emoji from this list — nothing else:\n"
    + " ".join(_TELEGRAM_REACTION_EMOJIS)
    + "\n\nPick the emoji that fits the vibe of the message best. Just the emoji, no text."
)


async def pick_reaction_emoji(
    user_id: int, user_message: str, chat_history: list[dict]
) -> str | None:
    """Ask the AI to pick a contextual Telegram reaction emoji."""
    keys_data = await get_user_api_keys(user_id)
    ollama_keys = keys_data.get("ollama_keys", [])
    if not ollama_keys:
        return None

    # Build a lightweight context: system + last few messages + current
    messages = [{"role": "system", "content": _REACTION_PICK_PROMPT}]
    for msg in chat_history[-4:]:
        role = msg.get("role", "user")
        content = msg.get("content", "")
        if role in ("user", "assistant") and content:
            messages.append({"role": role, "content": content})
    messages.append({"role": "user", "content": user_message})

    for encrypted_key in ollama_keys:
        try:
            decrypted_key = decrypt_key(encrypted_key)
            raw = await _call_ollama(decrypted_key, messages)
            emoji = raw.strip()
            # Validate it's in the allowed list
            if emoji in _TELEGRAM_REACTION_EMOJIS:
                return emoji
            # Model might have returned extra text — try to find an emoji in it
            for e in _TELEGRAM_REACTION_EMOJIS:
                if e in raw:
                    return e
            return None
        except Exception as e:
            logger.debug(f"Reaction pick failed for user {user_id}: {e}")
            continue
    return None
