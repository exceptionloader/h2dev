"""Pure command handling (no I/O) — easy to test."""

import random
import re
from dataclasses import dataclass

HELP = (
    "**NOVA Bot – Befehle**\n"
    "/würfel [NdM] – würfeln, z. B. /würfel 2d6\n"
    "/münze – Kopf oder Zahl\n"
    "/teams Name1 Name2 … – zwei zufällige Teams\n"
    "/sessions – offene Gaming-Sessions auf diesem Server\n"
    "/ping – lebst du noch?"
)

_DICE = re.compile(r"^(\d{1,2})?d(\d{1,3})$", re.IGNORECASE)


@dataclass
class Reply:
    text: str | None = None
    needs_sessions: bool = False  # caller must fetch sessions and call format_sessions


def parse(content: str) -> tuple[str, list[str]] | None:
    content = content.strip()
    if not content.startswith("/") or len(content) < 2:
        return None
    parts = content[1:].split()
    command = parts[0].lower().replace("ü", "ue")
    return command, parts[1:]


def handle(content: str, rng: random.Random | None = None) -> Reply | None:
    parsed = parse(content)
    if parsed is None:
        return None
    command, args = parsed
    rng = rng or random.Random()  # noqa: S311 - games, not crypto

    if command in ("hilfe", "help"):
        return Reply(HELP)
    if command == "ping":
        return Reply("Pong! 🏓")
    if command in ("muenze", "coin"):
        return Reply(f"🪙 {rng.choice(['Kopf', 'Zahl'])}!")
    if command in ("wuerfel", "roll", "dice"):
        spec = args[0] if args else "1d6"
        match = _DICE.match(spec)
        if not match:
            return Reply("Format: /würfel 2d6 (bis 20 Würfel mit bis zu 100 Seiten)")
        count, sides = int(match.group(1) or 1), int(match.group(2))
        if not (1 <= count <= 20 and 2 <= sides <= 100):
            return Reply("Format: /würfel 2d6 (bis 20 Würfel mit bis zu 100 Seiten)")
        rolls = [rng.randint(1, sides) for _ in range(count)]
        detail = f" ({' + '.join(map(str, rolls))})" if count > 1 else ""
        return Reply(f"🎲 {spec.lower()}: **{sum(rolls)}**{detail}")
    if command == "teams":
        names = [a for a in args if a][:20]
        if len(names) < 2:
            return Reply("Nenne mindestens zwei Namen: /teams Anna Ben Chris Dana")
        rng.shuffle(names)
        half = (len(names) + 1) // 2
        return Reply(f"🔵 Team A: {', '.join(names[:half])}\n🔴 Team B: {', '.join(names[half:])}")
    if command == "sessions":
        return Reply(needs_sessions=True)
    return None  # unknown commands are ignored (other bots may handle them)


def format_sessions(sessions: list[dict]) -> str:
    if not sessions:
        return "Gerade keine offenen Sessions. Erstell eine unter „Aktivitäten“! 🎮"
    lines = ["**Offene Sessions**"]
    for s in sessions[:10]:
        free = s["max_participants"] - len(s["participants"])
        slots = "voll" if free <= 0 else f"{free} frei"
        lines.append(f"• {s['game']} – {s['title']} ({slots})")
    return "\n".join(lines)


def welcome(display_name: str) -> str:
    return f"Willkommen, {display_name}! 👋 Tippe /hilfe für meine Befehle."
