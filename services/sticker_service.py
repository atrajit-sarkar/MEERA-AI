"""Sticker service — AI-driven sticker sending from user's Telegram sticker packs."""

import logging
import random
import time
from collections import defaultdict

from aiogram import Bot

from services.firebase_service import get_user, create_or_update_user

logger = logging.getLogger(__name__)

# In-memory sticker cache: { user_id: { "packs": { pack_name: { emoji: [file_id, ...] } }, "fetched_at": float } }
_sticker_cache: dict[int, dict] = {}

# Cache expiry — re-fetch packs every 2 hours
_CACHE_TTL = 2 * 3600


async def get_user_sticker_packs(user_id: int) -> list[str]:
    """Get the list of sticker pack names a user has added."""
    user = await get_user(user_id)
    if not user:
        return []
    return user.get("sticker_packs", [])


async def add_sticker_pack(user_id: int, pack_name: str) -> None:
    """Add a sticker pack to the user's collection."""
    user = await get_user(user_id)
    packs = user.get("sticker_packs", []) if user else []
    if pack_name not in packs:
        packs.append(pack_name)
        await create_or_update_user(user_id, {"sticker_packs": packs})
    # Invalidate cache
    _sticker_cache.pop(user_id, None)


async def remove_sticker_pack(user_id: int, pack_name: str) -> bool:
    """Remove a sticker pack from the user's collection. Returns True if found."""
    user = await get_user(user_id)
    packs = user.get("sticker_packs", []) if user else []
    if pack_name in packs:
        packs.remove(pack_name)
        await create_or_update_user(user_id, {"sticker_packs": packs})
        _sticker_cache.pop(user_id, None)
        return True
    return False


async def _fetch_and_cache_packs(bot: Bot, user_id: int) -> dict[str, dict[str, list[str]]]:
    """Fetch all sticker packs for a user and build emoji → file_id mapping."""
    packs_data: dict[str, dict[str, list[str]]] = {}
    pack_names = await get_user_sticker_packs(user_id)

    for pack_name in pack_names:
        try:
            sticker_set = await bot.get_sticker_set(pack_name)
            emoji_map: dict[str, list[str]] = defaultdict(list)
            for sticker in sticker_set.stickers:
                if sticker.emoji:
                    emoji_map[sticker.emoji].append(sticker.file_id)
            packs_data[pack_name] = dict(emoji_map)
        except Exception as e:
            logger.debug(f"Could not fetch sticker pack '{pack_name}' for user {user_id}: {e}")

    _sticker_cache[user_id] = {
        "packs": packs_data,
        "fetched_at": time.time(),
    }
    return packs_data


async def _get_cached_packs(bot: Bot, user_id: int) -> dict[str, dict[str, list[str]]]:
    """Get sticker packs from cache, fetching if stale or missing."""
    cached = _sticker_cache.get(user_id)
    if cached and (time.time() - cached["fetched_at"]) < _CACHE_TTL:
        return cached["packs"]
    return await _fetch_and_cache_packs(bot, user_id)


async def pick_sticker(bot: Bot, user_id: int, emoji: str) -> str | None:
    """Find a sticker matching the given emoji from the user's packs.

    Returns a sticker file_id or None if no match found.
    """
    packs = await _get_cached_packs(bot, user_id)
    if not packs:
        return None

    # Collect all stickers matching this emoji across all packs
    matching: list[str] = []
    for pack_data in packs.values():
        matching.extend(pack_data.get(emoji, []))

    if not matching:
        # Try partial match — some stickers have multi-emoji tags
        for pack_data in packs.values():
            for sticker_emoji, file_ids in pack_data.items():
                if emoji in sticker_emoji or sticker_emoji in emoji:
                    matching.extend(file_ids)

    return random.choice(matching) if matching else None


async def pick_random_sticker(bot: Bot, user_id: int) -> str | None:
    """Pick a completely random sticker from the user's packs."""
    packs = await _get_cached_packs(bot, user_id)
    if not packs:
        return None

    all_file_ids: list[str] = []
    for pack_data in packs.values():
        for file_ids in pack_data.values():
            all_file_ids.extend(file_ids)

    return random.choice(all_file_ids) if all_file_ids else None


def should_send_sticker(msg_count: int) -> bool:
    """Decide whether to send a sticker based on comfort tier."""
    if msg_count < 8:
        return False  # Strangers — never
    elif msg_count < 25:
        return random.random() < 0.04  # Acquaintance — 4%
    elif msg_count < 60:
        return random.random() < 0.12  # Comfortable — 12%
    else:
        return random.random() < 0.22  # Close — 22%
