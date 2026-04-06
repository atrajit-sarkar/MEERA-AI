"""Generate a Fernet encryption key for initial setup."""

from cryptography.fernet import Fernet

key = Fernet.generate_key().decode()
print(f"Your encryption key (put this in .env as ENCRYPTION_KEY):\n\n{key}")
