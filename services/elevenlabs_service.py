"""ElevenLabs Text-to-Speech service with per-user API key failover."""

import asyncio
import logging
import os
import uuid

from config import Config
from services.encryption import decrypt_key
from services.firebase_service import get_user_api_keys

logger = logging.getLogger(__name__)


async def text_to_speech(user_id: int, text: str) -> str | None:
    """Convert text to speech using ElevenLabs. Returns path to mp3 file or None."""
    keys_data = await get_user_api_keys(user_id)
    elevenlabs_keys = keys_data.get("elevenlabs_keys", [])

    if not elevenlabs_keys:
        logger.warning(f"No ElevenLabs keys for user {user_id}")
        return None

    os.makedirs(Config.TEMP_DIR, exist_ok=True)
    output_path = os.path.join(Config.TEMP_DIR, f"tts_{user_id}_{uuid.uuid4().hex}.mp3")

    last_error = None
    for encrypted_key in elevenlabs_keys:
        try:
            decrypted_key = decrypt_key(encrypted_key)
            audio_path = await _generate_audio(decrypted_key, text, output_path)
            return audio_path
        except Exception as e:
            last_error = e
            logger.warning(f"ElevenLabs key failed for user {user_id}: {e}")
            continue

    logger.error(f"All ElevenLabs keys failed for user {user_id}: {last_error}")
    return None


async def _generate_audio(api_key: str, text: str, output_path: str) -> str:
    """Generate audio file using ElevenLabs SDK in a thread executor."""
    loop = asyncio.get_event_loop()

    def _sync_generate():
        from elevenlabs.client import ElevenLabs

        client = ElevenLabs(api_key=api_key)

        audio_generator = client.text_to_speech.convert(
            text=text,
            voice_id=Config.ELEVENLABS_DEFAULT_VOICE_ID,
            model_id="eleven_multilingual_v2",
            output_format="mp3_44100_128",
        )

        # audio_generator yields chunks — write them to file
        with open(output_path, "wb") as f:
            for chunk in audio_generator:
                f.write(chunk)

        return output_path

    return await loop.run_in_executor(None, _sync_generate)


def cleanup_audio_file(file_path: str) -> None:
    """Remove a temporary audio file."""
    try:
        if file_path and os.path.exists(file_path):
            os.remove(file_path)
    except OSError as e:
        logger.debug(f"Failed to clean up audio file: {e}")
