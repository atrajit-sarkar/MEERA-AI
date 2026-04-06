"""Telegram bot command handlers — /start, /profile, /add_ollama_key, etc."""

import logging

from aiogram import Router, F
from aiogram.filters import Command, CommandStart
from aiogram.types import Message
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup

from services.firebase_service import (
    create_or_update_user,
    get_user_profile,
    update_user_profile,
    update_tone_profile,
)
from services.key_manager import (
    add_user_ollama_key,
    add_user_elevenlabs_key,
    list_user_keys,
    remove_user_key,
)

logger = logging.getLogger(__name__)
router = Router()


# ─── FSM States ────────────────────────────────────────────────────

class ProfileStates(StatesGroup):
    waiting_for_name = State()
    waiting_for_bio = State()


class KeyStates(StatesGroup):
    waiting_for_ollama_key = State()
    waiting_for_elevenlabs_key = State()
    waiting_for_remove_key = State()


# ─── /start ────────────────────────────────────────────────────────

@router.message(CommandStart())
async def cmd_start(message: Message) -> None:
    user_id = message.from_user.id
    username = message.from_user.first_name or "there"

    await create_or_update_user(user_id, {
        "telegram_username": message.from_user.username,
        "first_name": message.from_user.first_name,
    })

    await message.answer(
        f"Hey {username}! 💫 I'm Meera — your AI bestie on Telegram!\n\n"
        f"Before we can chat, you'll need to add your API keys:\n\n"
        f"🔑 /add_ollama_key — Add your Ollama/Gemini API key\n"
        f"🎙 /add_elevenlabs_key — Add ElevenLabs key (for voice)\n"
        f"👤 /profile — Set your name & bio\n"
        f"📋 /list_keys — View your saved keys\n"
        f"🗑 /remove_key — Remove a key\n"
        f"🎭 /tone — Set conversation tone\n"
        f"🗣 /talk — Toggle voice-only replies\n"
        f"❓ /help — See all commands\n\n"
        f"Each user uses their own API keys — your usage is private! 🔒"
    )


# ─── /help ─────────────────────────────────────────────────────────

@router.message(Command("help"))
async def cmd_help(message: Message) -> None:
    await message.answer(
        "✨ **Meera Commands** ✨\n\n"
        "💬 Just send me a message to chat!\n"
        "🎤 Send a voice message — I'll listen and can reply with voice too!\n\n"
        "**Setup:**\n"
        "🔑 /add\\_ollama\\_key — Add Ollama API key\n"
        "🎙 /add\\_elevenlabs\\_key — Add ElevenLabs API key\n"
        "📋 /list\\_keys — View your keys\n"
        "🗑 /remove\\_key — Remove a key\n\n"
        "**Personalization:**\n"
        "👤 /profile — Set your name & bio\n"
        "🎭 /tone — Set formal/casual + short/long\n"
        "🗣 /talk — Toggle voice-only mode\n\n"
        "**Note:** You need your own API keys. "
        "Get Ollama key from your provider and ElevenLabs from elevenlabs.io",
        parse_mode="Markdown",
    )


# ─── /profile ─────────────────────────────────────────────────────

@router.message(Command("profile"))
async def cmd_profile(message: Message) -> None:
    user_id = message.from_user.id
    profile = await get_user_profile(user_id)

    name = profile.get("profile_name") or "Not set"
    bio = profile.get("profile_bio") or "Not set"
    tone = profile.get("tone", "casual")
    reply_len = profile.get("reply_length", "medium")

    await message.answer(
        f"👤 **Your Profile**\n\n"
        f"**Name:** {name}\n"
        f"**Bio:** {bio}\n"
        f"**Tone:** {tone}\n"
        f"**Reply length:** {reply_len}\n\n"
        f"To update:\n"
        f"/setname — Change your name\n"
        f"/setbio — Change your bio",
        parse_mode="Markdown",
    )


# ─── /setname ─────────────────────────────────────────────────────

@router.message(Command("setname"))
async def cmd_setname(message: Message, state: FSMContext) -> None:
    await state.set_state(ProfileStates.waiting_for_name)
    await message.answer("What should I call you? 😊")


@router.message(ProfileStates.waiting_for_name, F.text)
async def process_setname(message: Message, state: FSMContext) -> None:
    name = message.text.strip()[:50]  # Limit length
    await update_user_profile(message.from_user.id, name=name)
    await state.clear()
    await message.answer(f"Got it! I'll call you **{name}** from now on 💫", parse_mode="Markdown")


# ─── /setbio ──────────────────────────────────────────────────────

@router.message(Command("setbio"))
async def cmd_setbio(message: Message, state: FSMContext) -> None:
    await state.set_state(ProfileStates.waiting_for_bio)
    await message.answer("Tell me a bit about yourself! 📝")


@router.message(ProfileStates.waiting_for_bio, F.text)
async def process_setbio(message: Message, state: FSMContext) -> None:
    bio = message.text.strip()[:200]
    await update_user_profile(message.from_user.id, bio=bio)
    await state.clear()
    await message.answer("Updated your bio! I'll keep that in mind when we chat 😊")


# ─── /tone ─────────────────────────────────────────────────────────

@router.message(Command("tone"))
async def cmd_tone(message: Message) -> None:
    await message.answer(
        "🎭 **Set your conversation preferences:**\n\n"
        "/tone\\_casual — Casual, friendly chat\n"
        "/tone\\_formal — More formal responses\n"
        "/replies\\_short — Keep it brief\n"
        "/replies\\_medium — Balanced length\n"
        "/replies\\_long — Detailed responses",
        parse_mode="Markdown",
    )


@router.message(Command("tone_casual"))
async def cmd_tone_casual(message: Message) -> None:
    profile = await get_user_profile(message.from_user.id)
    await update_tone_profile(message.from_user.id, "casual", profile.get("reply_length", "medium"))
    await message.answer("Alright, keeping it chill! 😎")


@router.message(Command("tone_formal"))
async def cmd_tone_formal(message: Message) -> None:
    profile = await get_user_profile(message.from_user.id)
    await update_tone_profile(message.from_user.id, "formal", profile.get("reply_length", "medium"))
    await message.answer("Understood. I'll maintain a more formal tone. 🎩")


@router.message(Command("replies_short"))
async def cmd_replies_short(message: Message) -> None:
    profile = await get_user_profile(message.from_user.id)
    await update_tone_profile(message.from_user.id, profile.get("tone", "casual"), "short")
    await message.answer("Short and sweet it is! ✨")


@router.message(Command("replies_medium"))
async def cmd_replies_medium(message: Message) -> None:
    profile = await get_user_profile(message.from_user.id)
    await update_tone_profile(message.from_user.id, profile.get("tone", "casual"), "medium")
    await message.answer("Balanced replies — got it! 👍")


@router.message(Command("replies_long"))
async def cmd_replies_long(message: Message) -> None:
    profile = await get_user_profile(message.from_user.id)
    await update_tone_profile(message.from_user.id, profile.get("tone", "casual"), "long")
    await message.answer("I'll be more detailed from now on! 📖")


# ─── /talk — Toggle voice-only mode ───────────────────────────────

@router.message(Command("talk"))
async def cmd_talk(message: Message) -> None:
    user_id = message.from_user.id
    profile = await get_user_profile(user_id)
    current = profile.get("voice_only", False) if isinstance(profile, dict) else False

    # Toggle
    new_val = not current
    await create_or_update_user(user_id, {"voice_only": new_val})

    if new_val:
        await message.answer("🗣 Voice-only mode ON — I'll reply with voice whenever possible!")
    else:
        await message.answer("💬 Voice-only mode OFF — back to normal text + occasional voice!")


# ─── /add_ollama_key ───────────────────────────────────────────────

@router.message(Command("add_ollama_key"))
async def cmd_add_ollama_key(message: Message, state: FSMContext) -> None:
    await state.set_state(KeyStates.waiting_for_ollama_key)
    await message.answer(
        "🔑 Send me your Ollama API key.\n\n"
        "⚠️ The key will be encrypted and stored securely.\n"
        "💡 Tip: Delete your message after sending for extra safety!"
    )


@router.message(KeyStates.waiting_for_ollama_key, F.text)
async def process_ollama_key(message: Message, state: FSMContext) -> None:
    key = message.text.strip()
    if len(key) < 8:
        await message.answer("That doesn't look like a valid key. Try again?")
        return

    await add_user_ollama_key(message.from_user.id, key)
    await state.clear()

    # Try to delete the user's message containing the key
    try:
        await message.delete()
    except Exception:
        pass

    await message.answer("✅ Ollama key added successfully! Your message was deleted for safety 🔒")


# ─── /add_elevenlabs_key ──────────────────────────────────────────

@router.message(Command("add_elevenlabs_key"))
async def cmd_add_elevenlabs_key(message: Message, state: FSMContext) -> None:
    await state.set_state(KeyStates.waiting_for_elevenlabs_key)
    await message.answer(
        "🎙 Send me your ElevenLabs API key.\n\n"
        "⚠️ The key will be encrypted and stored securely.\n"
        "Get one at: https://elevenlabs.io/app/settings/api-keys"
    )


@router.message(KeyStates.waiting_for_elevenlabs_key, F.text)
async def process_elevenlabs_key(message: Message, state: FSMContext) -> None:
    key = message.text.strip()
    if len(key) < 8:
        await message.answer("That doesn't look like a valid key. Try again?")
        return

    await add_user_elevenlabs_key(message.from_user.id, key)
    await state.clear()

    try:
        await message.delete()
    except Exception:
        pass

    await message.answer("✅ ElevenLabs key added! Message deleted for safety 🔒")


# ─── /list_keys ────────────────────────────────────────────────────

@router.message(Command("list_keys"))
async def cmd_list_keys(message: Message) -> None:
    keys = await list_user_keys(message.from_user.id)

    ollama_list = "\n".join(keys["ollama_keys"]) if keys["ollama_keys"] else "None"
    el_list = "\n".join(keys["elevenlabs_keys"]) if keys["elevenlabs_keys"] else "None"

    await message.answer(
        f"🔑 **Your API Keys**\n\n"
        f"**Ollama Keys:**\n{ollama_list}\n\n"
        f"**ElevenLabs Keys:**\n{el_list}\n\n"
        f"Use /remove\\_key to remove a key.",
        parse_mode="Markdown",
    )


# ─── /remove_key ──────────────────────────────────────────────────

@router.message(Command("remove_key"))
async def cmd_remove_key(message: Message, state: FSMContext) -> None:
    await state.set_state(KeyStates.waiting_for_remove_key)
    await message.answer(
        "Which key to remove? Reply with:\n\n"
        "`ollama <index>` or `elevenlabs <index>`\n\n"
        "Example: `ollama 0` or `elevenlabs 1`\n"
        "Use /list\\_keys to see indices.",
        parse_mode="Markdown",
    )


@router.message(KeyStates.waiting_for_remove_key, F.text)
async def process_remove_key(message: Message, state: FSMContext) -> None:
    text = message.text.strip().lower()
    parts = text.split()

    if len(parts) != 2:
        await message.answer("Invalid format. Use: `ollama 0` or `elevenlabs 1`", parse_mode="Markdown")
        return

    key_type_map = {"ollama": "ollama_keys", "elevenlabs": "elevenlabs_keys"}
    key_type = key_type_map.get(parts[0])
    if not key_type:
        await message.answer("Use `ollama` or `elevenlabs` as the type.", parse_mode="Markdown")
        return

    try:
        index = int(parts[1])
    except ValueError:
        await message.answer("Index must be a number.")
        return

    success = await remove_user_key(message.from_user.id, key_type, index)
    await state.clear()

    if success:
        await message.answer("✅ Key removed!")
    else:
        await message.answer("❌ Couldn't find that key. Check /list_keys for valid indices.")
