"""Telegram bot command handlers — /start, /profile, /add_ollama_key, etc."""

import logging

from aiogram import Router, F, Bot
from aiogram.filters import Command, CommandStart
from aiogram.types import Message
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup

from services.firebase_service import (
    create_or_update_user,
    get_user_profile,
    update_user_profile,
    update_tone_profile,
    clear_chat_history,
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


class VoiceStates(StatesGroup):
    waiting_for_voice_id = State()


class ClearStates(StatesGroup):
    waiting_for_confirm = State()


# ─── /start ────────────────────────────────────────────────────────

@router.message(CommandStart())
async def cmd_start(message: Message) -> None:
    user_id = message.from_user.id
    username = message.from_user.first_name or "there"

    await create_or_update_user(user_id, {
        "telegram_username": message.from_user.username,
        "first_name": message.from_user.first_name,
    })

    # Welcome message
    await message.answer(
        f"Hey {username}! 💫 I'm Meera — your AI bestie on Telegram!\n\n"
        f"Let me help you get set up real quick 👇",
    )

    # Step 1: Ollama key guide
    await message.answer(
        "🔑 **Step 1: Get your AI key (required)**\n\n"
        "I use Ollama to think & chat. Here's how to get a key:\n\n"
        "1️⃣ Go to ollama.com and sign in\n"
        "2️⃣ Click on your profile → **Settings** → **API Keys**\n"
        "3️⃣ Click **Create new key** and copy it\n"
        "4️⃣ Come back here and use /add\\_ollama\\_key\n"
        "5️⃣ Paste your key — I'll encrypt & store it securely\n\n"
        "⚡ You can add multiple keys for automatic rotation!",
        parse_mode="Markdown",
    )

    # Step 2: ElevenLabs key guide
    await message.answer(
        "🎙 **Step 2: Get your voice key (optional but fun!)**\n\n"
        "I use ElevenLabs for both voice replies AND listening to your voice messages.\n"
        "One key powers everything — text-to-speech + speech-to-text!\n\n"
        "1️⃣ Go to elevenlabs.io and create a free account\n"
        "2️⃣ Click your profile icon → **API Keys**\n"
        "3️⃣ Copy your API key\n"
        "4️⃣ Come back here and use /add\\_elevenlabs\\_key\n"
        "5️⃣ Paste your key — done!\n\n"
        "💡 **Bonus:** Want a custom voice? Go to elevenlabs.io → Voices → "
        "Create a voice → Copy the Voice ID → Use /setvoice here\n\n"
        "⚡ Multiple keys supported — auto-rotates if one hits rate limits!",
        parse_mode="Markdown",
    )

    # Step 3: Quick reference
    await message.answer(
        "✅ **That's it! Once keys are added, just text me and we're vibing.**\n\n"
        "📋 **Quick commands:**\n"
        "/add\\_ollama\\_key — Add AI key\n"
        "/add\\_elevenlabs\\_key — Add voice key\n"
        "/profile — Set your name & bio\n"
        "/setvoice — Use custom voice\n"
        "/tone — Set chat style\n"
        "/talk — Voice-only mode\n"
        "/clear — Wipe memory & start fresh\n"
        "/help — All commands\n\n"
        "🔒 Each user uses their own API keys — your data stays yours!",
        parse_mode="Markdown",
    )


# ─── /help ─────────────────────────────────────────────────────────

@router.message(Command("help"))
async def cmd_help(message: Message) -> None:
    await message.answer(
        "✨ **Meera Commands** ✨\n\n"
        "💬 Just send me a message to chat!\n"
        "🎤 Send a voice message — I'll listen & can reply with voice too!\n\n"
        "**🔧 Setup:**\n"
        "🔑 /add\\_ollama\\_key — Add Ollama API key (ollama.com → Settings → API Keys)\n"
        "🎙 /add\\_elevenlabs\\_key — Add ElevenLabs key (elevenlabs.io → API Keys)\n"
        "📋 /list\\_keys — View your saved keys\n"
        "🗑 /remove\\_key — Remove a key\n\n"
        "**🎨 Personalization:**\n"
        "👤 /profile — Set your name & bio\n"
        "🎭 /tone — Set formal/casual + short/long\n"
        "🎤 /setvoice — Use your own ElevenLabs voice\n"
        "🗣 /talk — Toggle voice-only mode\n"
        "🧹 /clear — Wipe chat history & start fresh\n\n"
        "**💡 Tips:**\n"
        "• One ElevenLabs key handles both voice replies AND voice listening\n"
        "• Add multiple keys for automatic rotation if one hits rate limits\n"
        "• Use /start to see the full setup guide again",
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
    voice = profile.get("voice_id") or "Default (Rachel)"

    await message.answer(
        f"👤 **Your Profile**\n\n"
        f"**Name:** {name}\n"
        f"**Bio:** {bio}\n"
        f"**Tone:** {tone}\n"
        f"**Reply length:** {reply_len}\n"
        f"**Voice:** {voice}\n\n"
        f"To update:\n"
        f"/setname — Change your name\n"
        f"/setbio — Change your bio\n"
        f"/setvoice — Change your voice",
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


# ─── /setvoice — Set custom ElevenLabs voice ID ───────────────────

@router.message(Command("setvoice"))
async def cmd_setvoice(message: Message, state: FSMContext) -> None:
    profile = await get_user_profile(message.from_user.id)
    current = profile.get("voice_id") or "Default (Rachel)"

    await state.set_state(VoiceStates.waiting_for_voice_id)
    await message.answer(
        f"🎙 **Current voice:** {current}\n\n"
        f"Send me your ElevenLabs Voice ID to use a custom voice.\n"
        f"You can create one at elevenlabs.io → Voices → Add Voice\n\n"
        f"Send `reset` to go back to the default voice.",
        parse_mode="Markdown",
    )


@router.message(VoiceStates.waiting_for_voice_id, F.text)
async def process_setvoice(message: Message, state: FSMContext) -> None:
    text = message.text.strip()
    await state.clear()

    if text.lower() == "reset":
        await create_or_update_user(message.from_user.id, {"voice_id": None})
        await message.answer("🎙 Voice reset to default (Rachel)! 🔄")
        return

    # Basic validation — ElevenLabs voice IDs are ~20 alphanumeric chars
    if len(text) < 10 or len(text) > 40 or not text.isalnum():
        await message.answer("That doesn't look like a valid voice ID. It should be a 20-character alphanumeric string from ElevenLabs.")
        return

    await create_or_update_user(message.from_user.id, {"voice_id": text})
    await message.answer(f"✅ Voice updated! I'll use voice `{text}` from now on 🎤", parse_mode="Markdown")


# ─── /clear — Wipe chat history and start fresh ───────────────────

@router.message(Command("clear"))
async def cmd_clear(message: Message, state: FSMContext) -> None:
    await state.set_state(ClearStates.waiting_for_confirm)
    await message.answer(
        "🧹 **Are you sure you want to clear our entire chat history?**\n\n"
        "This will:\n"
        "• Delete all messages from memory\n"
        "• Reset our relationship back to strangers\n"
        "• Meera won't remember anything from before\n\n"
        "Type `yes` to confirm or `no` to cancel.",
        parse_mode="Markdown",
    )


@router.message(ClearStates.waiting_for_confirm, F.text)
async def process_clear(message: Message, state: FSMContext, bot: Bot) -> None:
    text = message.text.strip().lower()
    await state.clear()

    if text not in ("yes", "y"):
        await message.answer("Phew! History kept safe 😌")
        return

    user_id = message.from_user.id
    deleted = await clear_chat_history(user_id)

    if deleted == 0:
        await message.answer("Already clean! Nothing to clear 🧹")
        return

    # Visual separator so user knows everything above is forgotten
    await message.answer(
        "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
        f"🧹 Memory cleared — {deleted} messages forgotten\n"
        "Everything above this line, I don't remember.\n"
        "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"
        "Hey! I'm Meera 👋 feels like we're meeting for the first time haha"
    )
