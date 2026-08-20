"""SMS, contacts and calls."""

from __future__ import annotations

import re
from typing import Any

from ..config import ToolsConfig
from .base import ToolError, integer, schema, string, tool
from .termux import run, run_json, truncate

_config = ToolsConfig()


def configure(cfg: ToolsConfig) -> None:
    global _config
    _config = cfg


def _digits(value: str) -> str:
    """Reduce a phone number to comparable digits, dropping a country prefix.

    +44 7700 900123, 07700 900123 and 7700900123 should all match each other,
    which is the level of precision contact matching actually needs.
    """
    stripped = re.sub(r"[^\d]", "", value or "")
    return stripped[-9:] if len(stripped) > 9 else stripped


def _contacts() -> list[dict]:
    rows = run_json(["termux-contact-list"], default=[]) or []
    return [r for r in rows if isinstance(r, dict)]


def resolve_recipient(to: str) -> str:
    """Turn a contact name or a raw number into a number to send to.

    Refuses rather than guesses when a name matches more than one contact —
    sending a message to the wrong person is not a recoverable error.
    """
    to = (to or "").strip()
    if not to:
        raise ToolError("No recipient given.")

    # Looks like a phone number already.
    if re.fullmatch(r"[+\d][\d\s\-().]{4,}", to):
        return to

    needle = to.casefold()
    matches = [c for c in _contacts() if needle in str(c.get("name", "")).casefold()]
    if not matches:
        raise ToolError(
            f"No contact matching {to!r}. Use contacts_find to see what names exist, "
            "or pass a full phone number."
        )

    exact = [c for c in matches if str(c.get("name", "")).casefold() == needle]
    candidates = exact or matches

    # One person with several numbers listed identically is not ambiguous.
    distinct = {_digits(str(c.get("number", ""))) for c in candidates}
    if len(distinct) > 1:
        listing = ", ".join(f"{c.get('name')} <{c.get('number')}>" for c in candidates[:8])
        raise ToolError(
            f"{to!r} is ambiguous — it matches {len(candidates)} contacts: {listing}. "
            "Ask the user which one, then pass the exact number."
        )
    return str(candidates[0].get("number", ""))


@tool(
    name="contacts_find",
    description=(
        "Search the phone's contacts by name and return matching names and numbers. "
        "Call this before sending a message or placing a call to someone named."
    ),
    parameters=schema(
        {
            "query": string(
                "Part of a contact's name, case insensitive. Omit to list all contacts."
            ),
        }
    ),
    category="messaging",
)
def contacts_find(query: str = "") -> dict[str, Any]:
    rows = _contacts()
    if query:
        needle = query.casefold()
        rows = [c for c in rows if needle in str(c.get("name", "")).casefold()]
    rows.sort(key=lambda c: str(c.get("name", "")))
    shown, clipped = truncate(rows, _config.max_rows)
    return {"count": len(rows), "truncated": clipped, "contacts": shown}


@tool(
    name="sms_list",
    description=(
        "Read SMS messages from the phone, newest first. Use this to answer questions "
        "about what someone said or whether a message arrived."
    ),
    parameters=schema(
        {
            "limit": integer("How many messages to return (default 10, max 50).", default=10),
            "box": string(
                "Which mailbox to read.",
                enum=["inbox", "sent", "draft", "outbox", "all"],
                default="inbox",
            ),
            "contact": string(
                "Optional contact name or phone number. Only messages to or from this "
                "person are returned."
            ),
        }
    ),
    category="messaging",
)
def sms_list(limit: int = 10, box: str = "inbox", contact: str = "") -> dict[str, Any]:
    limit = max(1, min(int(limit), _config.max_rows))
    if box not in {"inbox", "sent", "draft", "outbox", "all"}:
        raise ToolError(f"Unknown mailbox {box!r}.")

    # Filtering happens here rather than in termux-sms-list, whose -f flag is
    # not present in every build. Over-fetch so the filter has something to
    # work with, but stay bounded.
    fetch = min(limit * 10, 200) if contact else limit
    argv = ["termux-sms-list", "-d", "-n", "-l", str(fetch), "-t", box]
    rows = run_json(argv, default=[]) or []

    if contact:
        needle = _digits(contact) if any(ch.isdigit() for ch in contact) else ""
        name = contact.casefold()
        if not needle:
            try:
                needle = _digits(resolve_recipient(contact))
            except ToolError:
                needle = ""

        def matches(row: dict) -> bool:
            number = _digits(str(row.get("number", "")))
            sender = str(row.get("sender", "")).casefold()
            return bool((needle and number == needle) or (name and name in sender))

        rows = [r for r in rows if matches(r)]

    shown, clipped = truncate(rows, limit)
    return {"count": len(shown), "truncated": clipped, "messages": shown}


@tool(
    name="sms_send",
    description=(
        "Send an SMS. The recipient may be a contact name or a phone number. "
        "The user confirms the exact text before it goes out."
    ),
    parameters=schema(
        {
            "to": string("Contact name or phone number to send to."),
            "message": string("The message body, exactly as it should be sent."),
        },
        required=["to", "message"],
    ),
    category="messaging",
    consequential=True,
)
def sms_send(to: str, message: str) -> dict[str, Any]:
    if not (message or "").strip():
        raise ToolError("Refusing to send an empty message.")
    number = resolve_recipient(to)
    run(["termux-sms-send", "-n", number], stdin=message)
    return {"sent": True, "to": number, "message": message}


@tool(
    name="call_log",
    description="List recent incoming, outgoing and missed calls, newest first.",
    parameters=schema(
        {"limit": integer("How many entries to return (default 10, max 50).", default=10)}
    ),
    category="messaging",
)
def call_log(limit: int = 10) -> dict[str, Any]:
    limit = max(1, min(int(limit), _config.max_rows))
    rows = run_json(["termux-call-log", "-l", str(limit)], default=[]) or []
    return {"count": len(rows), "calls": rows}


@tool(
    name="call_place",
    description=(
        "Place a phone call. The recipient may be a contact name or a number. "
        "The user confirms before the call is dialled."
    ),
    parameters=schema(
        {"to": string("Contact name or phone number to call.")},
        required=["to"],
    ),
    category="messaging",
    consequential=True,
)
def call_place(to: str) -> dict[str, Any]:
    number = resolve_recipient(to)
    run(["termux-telephony-call", number])
    return {"dialing": number}
