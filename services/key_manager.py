"""API key management — add, list, remove, and failover for per-user keys."""

import logging

from services.encryption import encrypt_key, decrypt_key
from services.firebase_service import (
    add_api_key,
    get_user_api_keys,
    remove_api_key,
)

logger = logging.getLogger(__name__)


async def add_user_ollama_key(user_id: int, plain_key: str) -> None:
    encrypted = encrypt_key(plain_key)
    await add_api_key(user_id, "ollama_keys", encrypted)
    logger.info(f"Added Ollama key for user {user_id}")


async def add_user_elevenlabs_key(user_id: int, plain_key: str) -> None:
    encrypted = encrypt_key(plain_key)
    await add_api_key(user_id, "elevenlabs_keys", encrypted)
    logger.info(f"Added ElevenLabs key for user {user_id}")


async def list_user_keys(user_id: int) -> dict[str, list[str]]:
    """Return masked versions of user keys for display."""
    keys_data = await get_user_api_keys(user_id)
    result = {"ollama_keys": [], "elevenlabs_keys": []}

    for key_type in ("ollama_keys", "elevenlabs_keys"):
        for i, encrypted_key in enumerate(keys_data.get(key_type, [])):
            try:
                plain = decrypt_key(encrypted_key)
                masked = plain[:4] + "****" + plain[-4:] if len(plain) > 8 else "****"
                result[key_type].append(f"[{i}] {masked}")
            except Exception:
                result[key_type].append(f"[{i}] <corrupted>")

    return result


async def remove_user_key(user_id: int, key_type: str, index: int) -> bool:
    """Remove a key by type ('ollama_keys' or 'elevenlabs_keys') and index."""
    return await remove_api_key(user_id, key_type, index)


async def user_has_ollama_keys(user_id: int) -> bool:
    keys_data = await get_user_api_keys(user_id)
    return len(keys_data.get("ollama_keys", [])) > 0


async def user_has_elevenlabs_keys(user_id: int) -> bool:
    keys_data = await get_user_api_keys(user_id)
    return len(keys_data.get("elevenlabs_keys", [])) > 0
