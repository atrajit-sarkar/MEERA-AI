"""Encryption utilities for API key storage using Fernet symmetric encryption."""

import logging

from cryptography.fernet import Fernet, InvalidToken

from config import Config

logger = logging.getLogger(__name__)

_fernet: Fernet | None = None


def _get_fernet() -> Fernet:
    global _fernet
    if _fernet is None:
        key = Config.ENCRYPTION_KEY
        if not key:
            raise RuntimeError("ENCRYPTION_KEY not configured")
        _fernet = Fernet(key.encode() if isinstance(key, str) else key)
    return _fernet


def encrypt_key(plain_key: str) -> str:
    """Encrypt an API key and return base64-encoded ciphertext."""
    f = _get_fernet()
    return f.encrypt(plain_key.encode()).decode()


def decrypt_key(encrypted_key: str) -> str:
    """Decrypt an encrypted API key."""
    f = _get_fernet()
    try:
        return f.decrypt(encrypted_key.encode()).decode()
    except InvalidToken:
        logger.error("Failed to decrypt key — invalid token or corrupted data")
        raise ValueError("Could not decrypt key. Encryption key may have changed.")


def generate_encryption_key() -> str:
    """Generate a new Fernet encryption key (for initial setup)."""
    return Fernet.generate_key().decode()
