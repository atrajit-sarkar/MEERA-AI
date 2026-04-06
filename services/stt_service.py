"""ElevenLabs Speech-to-Text (Scribe v2) service for voice message transcription."""

import asyncio
import logging

from services.encryption import decrypt_key
from services.firebase_service import get_user_api_keys

logger = logging.getLogger(__name__)


async def transcribe_voice(ogg_file_path: str, user_id: int) -> str | None:
    """
    Transcribe voice file using ElevenLabs Scribe v2.
    Uses the user's own ElevenLabs API key with failover.
    Returns transcribed text or None on failure.
    """
    keys_data = await get_user_api_keys(user_id)
    elevenlabs_keys = keys_data.get("elevenlabs_keys", [])

    if not elevenlabs_keys:
        logger.warning(f"No ElevenLabs keys for STT, user {user_id}")
        return None

    last_error = None
    for encrypted_key in elevenlabs_keys:
        try:
            decrypted_key = decrypt_key(encrypted_key)
            transcript = await _elevenlabs_stt(decrypted_key, ogg_file_path)
            return transcript
        except Exception as e:
            last_error = e
            logger.warning(f"ElevenLabs STT key failed for user {user_id}: {e}")
            continue

    logger.error(f"All ElevenLabs STT keys failed for user {user_id}: {last_error}")
    return None


async def _elevenlabs_stt(api_key: str, audio_path: str) -> str | None:
    """Transcribe audio file using ElevenLabs Scribe v2."""
    loop = asyncio.get_event_loop()

    def _transcribe():
        from elevenlabs.client import ElevenLabs

        client = ElevenLabs(api_key=api_key)

        with open(audio_path, "rb") as f:
            transcription = client.speech_to_text.convert(
                file=f,
                model_id="scribe_v2",
                tag_audio_events=False,
                diarize=False,
            )

        text = transcription.text if hasattr(transcription, 'text') else str(transcription)
        return text.strip() if text and text.strip() else None

    return await loop.run_in_executor(None, _transcribe)
