"""Proactive messaging — Meera texts first when users go quiet, like a real person."""

import asyncio
import logging
import random
import time

from aiogram import Bot

from services.firebase_service import (
    get_db,
    get_chat_history,
    get_user_profile,
    save_message,
    create_or_update_user,
)
from services.ollama_service import get_ai_response, _get_comfort_tier
from services.elevenlabs_service import text_to_speech, cleanup_audio_file
from services.key_manager import user_has_ollama_keys, user_has_elevenlabs_keys
from services.sticker_service import pick_sticker, get_user_sticker_packs
from services.ollama_service import pick_sticker_emoji
from config import Config

logger = logging.getLogger(__name__)

# Inactivity thresholds (seconds) per comfort tier
# Strangers:   Meera wouldn't text first at all — she barely knows you
# Acquaintance: Maybe after a full day she drops a casual line
# Comfortable:  After ~6 hours she might check in
# Close:        After ~2 hours she's already like "helloooo??"
_INACTIVITY_THRESHOLDS = {
    "stranger": None,         # Never text first — too early
    "acquaintance": 24 * 3600,  # 24 hours
    "comfortable": 6 * 3600,    # 6 hours
    "close": 2 * 3600,          # 2 hours
}

# How often the check loop runs (seconds)
CHECK_INTERVAL = 5 * 60  # every 5 minutes

# Prompts for generating natural "text first" messages per tier
_INITIATE_PROMPTS = {
    "acquaintance": (
        "You haven't heard from this person in a while. "
        "Send them a casual, low-effort message — like you just thought of them. "
        "Keep it super short. One line max. Don't be needy. "
        "Examples of vibe: 'heyy', 'you alive? lol', 'random but I just saw something that reminded me of you'"
    ),
    "comfortable": (
        "It's been a while since this person texted. "
        "Send them something natural — maybe ask about their day, share a random thought, "
        "or tease them for disappearing. Keep it casual and short. "
        "Examples of vibe: 'ok so you just forgot about me huh 😤', "
        "'what are you up to 👀', 'I'm bored entertain me'"
    ),
    "close": (
        "Your close friend hasn't messaged in a while. "
        "Text them like a real bestie would — dramatic, teasing, or sweet. "
        "Can be clingy because you're close. Short and punchy. "
        "Examples of vibe: 'HELLO?? did you die', 'I miss talking to you ngl 🥺', "
        "'excuse me??? remember me???', 'ok fine ignore me then 😭'"
    ),
}


async def _get_inactive_users() -> list[dict]:
    """Find users who haven't interacted recently enough for their comfort tier."""
    db = get_db()
    now = time.time()
    inactive = []

    async for doc in db.collection("users").stream():
        user = doc.to_dict()
        user_id = int(doc.id)
        last_interaction = user.get("last_interaction")
        chat_id = user.get("chat_id")
        already_poked = user.get("proactive_sent", False)

        # Skip users without required data
        if not last_interaction or not chat_id:
            continue

        # Skip if we already sent a proactive message since their last reply
        if already_poked:
            continue

        # Skip users without Ollama keys (can't generate message)
        if not user.get("ollama_keys"):
            continue

        # Get chat history to determine comfort tier
        history = await get_chat_history(user_id)
        tier = _get_comfort_tier(len(history))

        threshold = _INACTIVITY_THRESHOLDS.get(tier)
        if threshold is None:
            continue  # Don't text strangers first

        elapsed = now - last_interaction
        if elapsed >= threshold:
            inactive.append({
                "user_id": user_id,
                "chat_id": chat_id,
                "tier": tier,
                "history": history,
                "has_elevenlabs": bool(user.get("elevenlabs_keys")),
                "voice_id": user.get("voice_id"),
            })

    return inactive


async def _send_proactive_message(bot: Bot, user_data: dict) -> None:
    """Generate and send a proactive message to an inactive user."""
    user_id = user_data["user_id"]
    chat_id = user_data["chat_id"]
    tier = user_data["tier"]
    history = user_data["history"]

    try:
        user_profile = await get_user_profile(user_id)

        # Use the tier-specific initiation prompt as the "user message"
        initiate_prompt = _INITIATE_PROMPTS[tier]

        # Get AI to generate a natural "text first" message
        ai_message = await get_ai_response(
            user_id,
            initiate_prompt,
            history,
            user_profile,
        )

        if not ai_message or not ai_message.strip():
            return

        # Decide: voice or text? (close friends might send a voice note)
        use_voice = False
        if tier == "close" and user_data["has_elevenlabs"]:
            use_voice = random.random() < 0.25  # 25% chance for close friends
        elif tier == "comfortable" and user_data["has_elevenlabs"]:
            use_voice = random.random() < 0.08  # 8% chance

        if use_voice:
            try:
                from bot.handlers import _clean_text_for_tts
                import os
                from aiogram.types import FSInputFile

                clean_text = _clean_text_for_tts(ai_message)
                audio_path = await text_to_speech(user_id, clean_text, user_data.get("voice_id"))
                if audio_path and os.path.exists(audio_path):
                    voice_file = FSInputFile(audio_path)
                    await bot.send_voice(chat_id, voice_file)
                    cleanup_audio_file(audio_path)
                else:
                    # Voice failed, fall back to text
                    await bot.send_message(chat_id, ai_message)
            except Exception as e:
                logger.debug(f"Proactive voice failed for {user_id}, falling back to text: {e}")
                await bot.send_message(chat_id, ai_message)
        else:
            await bot.send_message(chat_id, ai_message)

        # Save to history so Meera remembers she texted first
        await save_message(user_id, "assistant", ai_message)

        # Maybe send a sticker too (close friends only)
        if tier in ("close", "comfortable"):
            sticker_chance = 0.20 if tier == "close" else 0.08
            if random.random() < sticker_chance:
                packs = await get_user_sticker_packs(user_id)
                if packs:
                    try:
                        emoji = await pick_sticker_emoji(user_id, ai_message, history)
                        if emoji:
                            from services.sticker_service import pick_sticker
                            sticker_id = await pick_sticker(bot, user_id, emoji)
                            if sticker_id:
                                await asyncio.sleep(random.uniform(1.0, 3.0))
                                await bot.send_sticker(chat_id, sticker_id)
                    except Exception as e:
                        logger.debug(f"Proactive sticker failed for {user_id}: {e}")

        # Mark that we've sent a proactive message — reset when user replies
        await create_or_update_user(user_id, {"proactive_sent": True})

        logger.info(f"[Proactive] Sent {'voice' if use_voice else 'text'} to user {user_id} (tier: {tier})")

    except Exception as e:
        logger.error(f"[Proactive] Failed for user {user_id}: {e}")


async def proactive_loop(bot: Bot) -> None:
    """Background loop that periodically checks for inactive users and messages them."""
    logger.info("[Proactive] Starting proactive messaging loop")

    # Wait a bit after startup before first check
    await asyncio.sleep(60)

    while True:
        try:
            inactive_users = await _get_inactive_users()

            if inactive_users:
                # Don't spam everyone at once — pick a random subset
                batch = random.sample(inactive_users, min(3, len(inactive_users)))

                for user_data in batch:
                    await _send_proactive_message(bot, user_data)
                    # Small delay between messages to be natural
                    await asyncio.sleep(random.uniform(10, 30))

        except Exception as e:
            logger.error(f"[Proactive] Loop error: {e}", exc_info=True)

        await asyncio.sleep(CHECK_INTERVAL)
