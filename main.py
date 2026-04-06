"""Meera AI — Telegram Bot Entry Point."""

import asyncio
import logging
import os
import sys

from aiogram import Bot, Dispatcher
from aiogram.fsm.storage.memory import MemoryStorage
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode

from config import Config
from services.firebase_service import init_firebase, seed_error_messages
from services.proactive import proactive_loop
from bot.commands import router as commands_router
from bot.handlers import router as handlers_router

# ─── Logging ───────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler("meera_bot.log", encoding="utf-8"),
    ],
)
logger = logging.getLogger("meera")

# Reduce noise from libraries
logging.getLogger("aiogram").setLevel(logging.WARNING)
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("google").setLevel(logging.WARNING)


async def on_startup(bot: Bot) -> None:
    """Run on bot startup."""
    logger.info("Initializing Firebase...")
    init_firebase()

    logger.info("Seeding error messages...")
    try:
        await seed_error_messages()
    except Exception as e:
        logger.warning(f"Could not seed error messages (Firestore may not be set up yet): {e}")

    # Create temp directory
    os.makedirs(Config.TEMP_DIR, exist_ok=True)

    me = await bot.get_me()
    logger.info(f"Bot started: @{me.username} ({me.full_name})")


async def on_shutdown(bot: Bot) -> None:
    """Run on bot shutdown."""
    logger.info("Shutting down Meera bot...")
    # Clean temp files
    if os.path.exists(Config.TEMP_DIR):
        for f in os.listdir(Config.TEMP_DIR):
            try:
                os.remove(os.path.join(Config.TEMP_DIR, f))
            except OSError:
                pass


async def main() -> None:
    # Validate config
    errors = Config.validate()
    if errors:
        for err in errors:
            logger.error(f"Config error: {err}")
        sys.exit(1)

    # Initialize bot
    bot = Bot(
        token=Config.TELEGRAM_BOT_TOKEN,
        default=DefaultBotProperties(parse_mode=ParseMode.HTML),
    )

    # Initialize dispatcher with FSM storage
    dp = Dispatcher(storage=MemoryStorage())

    # Register routers — commands first (higher priority), then general handlers
    dp.include_router(commands_router)
    dp.include_router(handlers_router)

    # Register lifecycle hooks
    dp.startup.register(on_startup)
    dp.shutdown.register(on_shutdown)

    logger.info("Starting Meera AI Telegram Bot...")

    # Start proactive messaging background task
    asyncio.create_task(proactive_loop(bot))

    await dp.start_polling(bot, allowed_updates=["message", "callback_query"])


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Bot stopped by user")
    except Exception as e:
        logger.critical(f"Fatal error: {e}", exc_info=True)
        sys.exit(1)
