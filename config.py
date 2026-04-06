import os
from dotenv import load_dotenv

load_dotenv()


class Config:
    # Telegram
    TELEGRAM_BOT_TOKEN: str = os.getenv("TELEGRAM_BOT_TOKEN", "")

    # Firebase — supports either a file path or base64-encoded JSON in env var
    FIREBASE_CREDENTIALS_PATH: str = os.getenv("FIREBASE_CREDENTIALS_PATH", "")
    FIREBASE_CREDENTIALS_JSON: str = os.getenv("FIREBASE_CREDENTIALS_JSON", "")
    FIREBASE_DATABASE_ID: str = os.getenv("FIREBASE_DATABASE_ID", "(default)")

    # Encryption
    ENCRYPTION_KEY: str = os.getenv("ENCRYPTION_KEY", "")

    # ElevenLabs
    ELEVENLABS_DEFAULT_VOICE_ID: str = os.getenv("ELEVENLABS_DEFAULT_VOICE_ID", "JBFqnCBsd6RMkjVDRZzb")

    # Ollama
    OLLAMA_HOST: str = os.getenv("OLLAMA_HOST", "https://your-ollama-cloud-endpoint")
    OLLAMA_MODEL: str = "gemini-3-flash-preview:cloud"

    # Bot behavior
    MAX_CHAT_HISTORY: int = int(os.getenv("MAX_CHAT_HISTORY", "20"))
    TYPING_DELAY_MIN: float = float(os.getenv("TYPING_DELAY_MIN", "1.0"))
    TYPING_DELAY_MAX: float = float(os.getenv("TYPING_DELAY_MAX", "3.0"))
    DEBUG_REACTIONS: bool = os.getenv("DEBUG_REACTIONS", "false").lower() == "true"

    # Paths
    TEMP_DIR: str = os.path.join(os.path.dirname(__file__), "temp")

    @classmethod
    def validate(cls) -> list[str]:
        errors = []
        if not cls.TELEGRAM_BOT_TOKEN:
            errors.append("TELEGRAM_BOT_TOKEN is required")
        if not cls.FIREBASE_CREDENTIALS_PATH and not cls.FIREBASE_CREDENTIALS_JSON:
            errors.append("Either FIREBASE_CREDENTIALS_PATH or FIREBASE_CREDENTIALS_JSON is required")
        if not cls.ENCRYPTION_KEY:
            errors.append("ENCRYPTION_KEY is required")
        return errors
