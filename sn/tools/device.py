"""Device state: clipboard, battery, network, notifications."""

from __future__ import annotations

from typing import Any

from ..config import ToolsConfig
from .base import ToolError, integer, schema, string, tool
from .termux import run, run_json, truncate

_config = ToolsConfig()


def configure(cfg: ToolsConfig) -> None:
    global _config
    _config = cfg


@tool(
    name="clipboard_get",
    description="Read the phone's current clipboard contents.",
    category="device",
)
def clipboard_get() -> dict[str, Any]:
    text = run(["termux-clipboard-get"])
    return {"text": text, "empty": not text.strip()}


@tool(
    name="clipboard_set",
    description=(
        "Replace the phone's clipboard contents, so the user can paste the result "
        "into another app."
    ),
    parameters=schema({"text": string("Text to place on the clipboard.")}, required=["text"]),
    category="device",
)
def clipboard_set(text: str) -> dict[str, Any]:
    run(["termux-clipboard-set"], stdin=text)
    return {"copied": True, "length": len(text)}


@tool(
    name="battery_status",
    description=(
        "Battery percentage, charging state, health and temperature. Temperature is "
        "in degrees Celsius."
    ),
    category="device",
)
def battery_status() -> dict[str, Any]:
    return run_json(["termux-battery-status"], default={})


@tool(
    name="network_status",
    description=(
        "Current Wi-Fi connection details: SSID, signal strength, link speed and local "
        "IP address. Useful for checking whether the phone is on the same network as "
        "the laptop."
    ),
    category="device",
)
def network_status() -> dict[str, Any]:
    info = run_json(["termux-wifi-connectioninfo"], default={}) or {}
    if info.get("supplicant_state") == "DISCONNECTED" or not info:
        return {"wifi": False, "detail": info}
    return {"wifi": True, **info}


@tool(
    name="notifications_list",
    description=(
        "List the notifications currently in the phone's status bar, with the app that "
        "posted each one. Use this to answer 'what have I missed'."
    ),
    parameters=schema(
        {
            "limit": integer("How many notifications to return (default 25).", default=25),
            "app": string("Optional filter on the posting app's package name."),
        }
    ),
    category="device",
)
def notifications_list(limit: int = 25, app: str = "") -> dict[str, Any]:
    try:
        rows = run_json(["termux-notification-list"], default=[]) or []
    except ToolError as exc:
        raise ToolError(
            f"{exc} Reading notifications also needs notification access: Android "
            "Settings > Notifications > Device & app notifications > Termux:API."
        ) from exc

    if app:
        needle = app.casefold()
        rows = [r for r in rows if needle in str(r.get("packageName", "")).casefold()]
    limit = max(1, min(int(limit), _config.max_rows))
    shown, clipped = truncate(rows, limit)
    return {"count": len(rows), "truncated": clipped, "notifications": shown}


@tool(
    name="notification_send",
    description=(
        "Post a notification to the phone's status bar. Use this to leave the user a "
        "reminder or a result they will see later, away from the terminal."
    ),
    parameters=schema(
        {
            "title": string("Notification title."),
            "content": string("Notification body text."),
            "id": string(
                "Optional stable id. Reusing an id replaces that notification "
                "instead of stacking a new one."
            ),
        },
        required=["title", "content"],
    ),
    category="device",
    consequential=True,
)
def notification_send(title: str, content: str, id: str = "") -> dict[str, Any]:
    argv = ["termux-notification", "--title", title, "--content", content]
    if id:
        argv += ["--id", id]
    run(argv)
    return {"posted": True, "title": title, "id": id or None}


@tool(
    name="notification_remove",
    description="Dismiss a notification that sn posted earlier, by its id.",
    parameters=schema({"id": string("The id used when the notification was posted.")}, required=["id"]),
    category="device",
)
def notification_remove(id: str) -> dict[str, Any]:
    run(["termux-notification-remove", id])
    return {"removed": True, "id": id}


@tool(
    name="vibrate",
    description="Vibrate the phone briefly, to get the user's attention.",
    parameters=schema(
        {"milliseconds": integer("Duration in milliseconds (default 400).", default=400)}
    ),
    category="device",
)
def vibrate(milliseconds: int = 400) -> dict[str, Any]:
    duration = max(1, min(int(milliseconds), 5000))
    run(["termux-vibrate", "-d", str(duration), "-f"])
    return {"vibrated": duration}
