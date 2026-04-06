"""Telegram message handlers — text and voice message processing."""

import asyncio
import logging
import os
import random
import uuid

from aiogram import Bot, Router, F
from aiogram.types import Message, FSInputFile, ReactionTypeEmoji

from config import Config
from services.ollama_service import get_ai_response, should_reply_with_voice
from services.elevenlabs_service import text_to_speech, cleanup_audio_file
from services.stt_service import transcribe_voice
from services.firebase_service import (
    get_chat_history,
    save_message,
    get_user_profile,
    get_user,
)
from services.error_messages import get_friendly_error
from services.key_manager import user_has_ollama_keys, user_has_elevenlabs_keys

logger = logging.getLogger(__name__)
router = Router()

# Reaction emojis mapped to message vibes
_REACTIONS_POSITIVE = ["❤", "😂", "🔥", "👍", "😍", "🎉", "💯"]
_REACTIONS_THINKING = ["🤔", "👀", "🫡"]
_REACTIONS_EMPATHY = ["❤", "😢", "🥺"]
_REACTIONS_FUN = ["😂", "🤣", "💀", "😭"]
_REACTIONS_VOICE = ["🎤", "🔥", "❤", "😍"]


async def _react_to_message(bot: Bot, message: Message, vibe: str = "positive", chat_history_len: int = 0) -> None:
    """Add an emoji reaction — frequency increases as Meera gets comfortable."""
    # Comfort-based reaction probability
    if chat_history_len < 5:
        # New user — rarely react (~10%), still shy
        react_chance = 0.10
    elif chat_history_len < 15:
        # Getting comfortable — react sometimes (~25%)
        react_chance = 0.25
    elif chat_history_len < 30:
        # Friends now — react often (~50%)
        react_chance = 0.50
    else:
        # Close — react frequently (~70%), like a real bestie
        react_chance = 0.70

    if random.random() > react_chance:
        return

    reaction_map = {
        "positive": _REACTIONS_POSITIVE,
        "thinking": _REACTIONS_THINKING,
        "empathy": _REACTIONS_EMPATHY,
        "fun": _REACTIONS_FUN,
        "voice": _REACTIONS_VOICE,
    }
    emojis = reaction_map.get(vibe, _REACTIONS_POSITIVE)
    emoji = random.choice(emojis)

    try:
        await bot.set_message_reaction(
            chat_id=message.chat.id,
            message_id=message.message_id,
            reaction=[ReactionTypeEmoji(emoji=emoji)],
        )
    except Exception as e:
        # Reactions might not be supported in all chats — silently ignore
        logger.debug(f"Could not set reaction: {e}")


def _guess_vibe(text: str) -> str:
    """Guess the emotional vibe of a message for reaction selection."""
    text_lower = text.lower()
    sad_words = ["sad", "upset", "hurt", "crying", "miss", "lonely", "depressed", "sorry", "😢", "😭", "💔"]
    fun_words = ["lol", "lmao", "haha", "rofl", "😂", "🤣", "funny", "joke", "💀"]
    question_words = ["what", "how", "why", "when", "where", "?", "explain", "tell me"]

    if any(w in text_lower for w in sad_words):
        return "empathy"
    if any(w in text_lower for w in fun_words):
        return "fun"
    if any(w in text_lower for w in question_words):
        return "thinking"
    return "positive"


async def _simulate_typing(bot: Bot, chat_id: int, text_length: int = 50) -> None:
    """Simulate typing delay like a real person."""
    await bot.send_chat_action(chat_id, "typing")
    # Dynamic delay based on response length
    base = random.uniform(Config.TYPING_DELAY_MIN, Config.TYPING_DELAY_MAX)
    extra = min(text_length / 200, 2.0)  # Up to 2 extra seconds for long messages
    await asyncio.sleep(base + extra)


def _clean_text_for_tts(text: str) -> str:
    """Strip emojis, markdown, prefixes, labels, and anything that sounds robotic in speech."""
    import re
    # Remove ALL label/prefix patterns (with or without markdown formatting)
    # Catches: "Voice message:", "*(Voice message)*:", "(Voice message):", "*Voice message*:", etc.
    text = re.sub(
        r'[\*_]*\(?\s*(voice\s*message|text\s*message|voice|response|reply|answer|meera|audio)'
        r'\s*\)?[\*_]*\s*[:：\-—]\s*',
        '', text, flags=re.IGNORECASE
    )
    # Remove "Here's my voice/response" type openers
    text = re.sub(
        r'^\s*here[\'\u2019]?s?\s*(my|a|the)?\s*(voice|response|reply|answer|message)[:：\-—\s]*',
        '', text, flags=re.IGNORECASE
    )
    # Remove roleplay asterisks: *laughs*, *sends voice*, *smiles* etc.
    text = re.sub(r'\*[^*]+\*', '', text)
    # Remove parenthetical stage directions: (laughs), (sighs), (voice message)
    text = re.sub(r'\([^)]*\)', '', text)
    # Remove emojis
    text = re.sub(
        r'[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF'
        r'\U0001F1E0-\U0001F1FF\U00002702-\U000027B0\U000024C2-\U0001F251'
        r'\U0001F900-\U0001F9FF\U0001FA00-\U0001FA6F\U0001FA70-\U0001FAFF'
        r'\U00002600-\U000026FF\U00002700-\U000027BF]+', '', text)
    # Remove remaining markdown formatting (`code`, ~~strike~~)
    text = re.sub(r'[`~]', '', text)
    # Remove leading/trailing quotes if the whole thing is wrapped in them
    text = re.sub(r'^["\u201c\u201d\'\u2018\u2019]+|["\u201c\u201d\'\u2018\u2019]+$', '', text.strip())
    # Collapse extra whitespace
    text = re.sub(r'\s+', ' ', text).strip()
    return text


async def _send_voice_reply(bot: Bot, message: Message, user_id: int, ai_text: str) -> bool:
    """Try to send a voice reply. Returns True if successful."""
    try:
        await bot.send_chat_action(message.chat.id, "record_voice")
        clean_text = _clean_text_for_tts(ai_text)
        audio_path = await text_to_speech(user_id, clean_text)
        if audio_path and os.path.exists(audio_path):
            voice_file = FSInputFile(audio_path)
            # Reply to the original message ~50% of the time (human-like)
            if random.random() < 0.5:
                await message.reply_voice(voice_file)
            else:
                await message.answer_voice(voice_file)
            cleanup_audio_file(audio_path)
            return True
    except Exception as e:
        logger.error(f"Voice reply failed for user {user_id}: {e}")
    return False


# ─── Text Message Handler ─────────────────────────────────────────

async def _send_text_reply(message: Message, text: str) -> None:
    """Send text — reply to the original message ~40% of the time for natural feel."""
    if random.random() < 0.4:
        await message.reply(text)
    else:
        await message.answer(text)


@router.message(F.text)
async def handle_text_message(message: Message, bot: Bot) -> None:
    user_id = message.from_user.id
    user_text = message.text

    # Check if user has keys
    if not await user_has_ollama_keys(user_id):
        await message.answer(await get_friendly_error("no_keys"))
        return

    try:
        # Show typing while AI is thinking
        await bot.send_chat_action(message.chat.id, "typing")

        # Get context
        chat_history = await get_chat_history(user_id)
        user_profile = await get_user_profile(user_id)

        # React based on comfort level (how many messages exchanged)
        vibe = _guess_vibe(user_text)
        await _react_to_message(bot, message, vibe, len(chat_history))

        # Decide voice or text BEFORE generating response (so we show the right indicator)
        user_data = await get_user(user_id)
        voice_only = user_data.get("voice_only", False) if user_data else False
        use_voice = voice_only or await should_reply_with_voice(
            user_id, user_text, "text", chat_history
        )

        # Get AI response
        ai_response = await get_ai_response(user_id, user_text, chat_history, user_profile)

        # Save messages to history
        await save_message(user_id, "user", user_text)
        await save_message(user_id, "assistant", ai_response)

        if use_voice:
            # Switch to record_voice indicator + natural delay
            await bot.send_chat_action(message.chat.id, "record_voice")
            await asyncio.sleep(random.uniform(1.0, 2.0))
            sent = await _send_voice_reply(bot, message, user_id, ai_response)
            if not sent:
                # Fallback to text if voice fails
                await _simulate_typing(bot, message.chat.id, len(ai_response))
                await _send_text_reply(message, ai_response)
        else:
            # Text reply with typing indicator
            await _simulate_typing(bot, message.chat.id, len(ai_response))
            await _send_text_reply(message, ai_response)

    except ValueError as e:
        error_category = str(e)
        friendly_msg = await get_friendly_error(error_category)
        await message.answer(friendly_msg)
    except Exception as e:
        logger.error(f"Error handling text message for user {user_id}: {e}", exc_info=True)
        friendly_msg = await get_friendly_error("unknown_error")
        await message.answer(friendly_msg)


# ─── Voice Message Handler ────────────────────────────────────────

@router.message(F.voice)
async def handle_voice_message(message: Message, bot: Bot) -> None:
    user_id = message.from_user.id

    # Check if user has keys (need both Ollama for AI + ElevenLabs for STT)
    if not await user_has_ollama_keys(user_id):
        await message.answer(await get_friendly_error("no_keys"))
        return
    if not await user_has_elevenlabs_keys(user_id):
        await message.answer(
            "I need your ElevenLabs API key to listen to voice messages! 🎤\n"
            "Use /add_elevenlabs_key to add one."
        )
        return

    ogg_path = None
    try:
        # Show record_voice while processing
        await bot.send_chat_action(message.chat.id, "record_voice")

        # Download voice file
        os.makedirs(Config.TEMP_DIR, exist_ok=True)
        ogg_path = os.path.join(Config.TEMP_DIR, f"voice_{user_id}_{uuid.uuid4().hex}.ogg")

        file = await bot.get_file(message.voice.file_id)
        await bot.download_file(file.file_path, ogg_path)

        # Transcribe voice to text using ElevenLabs Scribe
        transcript = await transcribe_voice(ogg_path, user_id)
        if not transcript:
            friendly_msg = await get_friendly_error("stt_error")
            await message.answer(friendly_msg)
            return

        # Get context
        chat_history = await get_chat_history(user_id)
        user_profile = await get_user_profile(user_id)

        # React to voice based on comfort level
        await _react_to_message(bot, message, "voice", len(chat_history))

        # Send to AI (just the transcript, no robotic prefix)
        ai_response = await get_ai_response(
            user_id,
            transcript,
            chat_history,
            user_profile,
        )

        # Save to history
        await save_message(user_id, "user", transcript)
        await save_message(user_id, "assistant", ai_response)

        # For voice messages, almost always reply with voice (human girl behavior)
        use_voice = await should_reply_with_voice(user_id, transcript, "voice", chat_history)

        if use_voice:
            # Show record_voice indicator + natural delay
            await bot.send_chat_action(message.chat.id, "record_voice")
            await asyncio.sleep(random.uniform(1.0, 2.5))

            sent = await _send_voice_reply(bot, message, user_id, ai_response)
            if not sent:
                # Voice failed — fallback to text with typing indicator
                await _simulate_typing(bot, message.chat.id, len(ai_response))
                tts_error = await get_friendly_error("tts_error")
                await _send_text_reply(message, f"{tts_error}\n\n{ai_response}")
        else:
            # Text reply — show typing indicator
            await _simulate_typing(bot, message.chat.id, len(ai_response))
            await _send_text_reply(message, ai_response)

    except ValueError as e:
        error_category = str(e)
        friendly_msg = await get_friendly_error(error_category)
        await message.answer(friendly_msg)
    except Exception as e:
        logger.error(f"Error handling voice for user {user_id}: {e}", exc_info=True)
        friendly_msg = await get_friendly_error("unknown_error")
        await message.answer(friendly_msg)
    finally:
        # Clean up downloaded OGG
        if ogg_path and os.path.exists(ogg_path):
            try:
                os.remove(ogg_path)
            except OSError:
                pass
