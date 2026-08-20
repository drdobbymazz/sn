"""Calendar access.

termux-api has no calendar command, so this talks to Android directly:

* reading goes through the calendar content provider, which needs READ_CALENDAR
  granted to Termux. Android will not show a permission dialog for it, so it is
  granted once over wireless ADB — see docs/permissions.md. `sn doctor` checks it.
* creating goes through an INSERT intent, which needs no permission at all: it
  opens the calendar app with the event filled in and the user taps save. That
  is a feature, not a limitation — nothing lands in the calendar unseen.
"""

from __future__ import annotations

import re
from datetime import datetime, timedelta
from typing import Any

from ..config import ToolsConfig
from .base import ToolError, integer, schema, string, tool
from .termux import android, truncate

_config = ToolsConfig()

INSTANCES_URI = "content://com.android.calendar/instances/when"
PROJECTION = "title:begin:end:eventLocation:allDay:description"

_GRANT_HINT = (
    "Reading the calendar needs READ_CALENDAR granted to Termux, which Android only "
    "allows over ADB:\n"
    "  adb shell pm grant com.termux android.permission.READ_CALENDAR\n"
    "See docs/permissions.md for the wireless ADB steps. Creating events works "
    "without it — calendar_create_event opens the calendar app prefilled."
)


def configure(cfg: ToolsConfig) -> None:
    global _config
    _config = cfg


def _parse_when(value: str, *, fallback: datetime) -> datetime:
    """Accept an ISO date/datetime, or a couple of words people actually type."""
    text = (value or "").strip().lower()
    if not text or text == "now":
        return fallback
    today = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    if text == "today":
        return today
    if text == "tomorrow":
        return today + timedelta(days=1)
    if text == "yesterday":
        return today - timedelta(days=1)
    try:
        return datetime.fromisoformat(value.strip())
    except ValueError as exc:
        raise ToolError(
            f"Could not read {value!r} as a date. Use ISO format, e.g. 2026-08-21 "
            "or 2026-08-21T14:30."
        ) from exc


def _parse_rows(output: str) -> list[dict[str, str]]:
    """Parse `content query` output.

    Rows look like:
        Row: 0 title=Standup, begin=1755782400000, allDay=0

    Values are not quoted or escaped, so a comma inside a title is ambiguous.
    Splitting only where a `key=` clearly begins recovers the common cases.
    """
    rows: list[dict[str, str]] = []
    for line in output.splitlines():
        line = line.strip()
        if not line.startswith("Row:"):
            continue
        body = line.split(" ", 2)[2] if len(line.split(" ", 2)) > 2 else ""
        row: dict[str, str] = {}
        for part in re.split(r",\s+(?=[A-Za-z_]+=)", body):
            key, _, value = part.partition("=")
            if key:
                row[key.strip()] = value.strip()
        if row:
            rows.append(row)
    return rows


def _humanize(row: dict[str, str]) -> dict[str, Any]:
    def when(key: str) -> str | None:
        raw = row.get(key, "")
        if not raw.isdigit():
            return None
        return datetime.fromtimestamp(int(raw) / 1000).isoformat(timespec="minutes")

    out: dict[str, Any] = {
        "title": row.get("title") or "(no title)",
        "start": when("begin"),
        "end": when("end"),
        "all_day": row.get("allDay") == "1",
    }
    for key, name in (("eventLocation", "location"), ("description", "description")):
        value = row.get(key, "")
        if value and value != "NULL":
            out[name] = value
    return out


@tool(
    name="calendar_list_events",
    description=(
        "List calendar events in a time window. Use this for questions about what is "
        "coming up, whether a time is free, or when something is scheduled."
    ),
    parameters=schema(
        {
            "start": string(
                "When the window starts: an ISO date/datetime, or 'today' or "
                "'tomorrow'. Defaults to now.",
                default="now",
            ),
            "days": integer("How many days the window covers (default 7).", default=7),
        }
    ),
    category="calendar",
)
def calendar_list_events(start: str = "now", days: int = 7) -> dict[str, Any]:
    begin = _parse_when(start, fallback=datetime.now())
    days = max(1, min(int(days), 365))
    finish = begin + timedelta(days=days)

    uri = f"{INSTANCES_URI}/{int(begin.timestamp() * 1000)}/{int(finish.timestamp() * 1000)}"
    try:
        raw = android(
            ["content", "query", "--uri", uri, "--projection", PROJECTION, "--sort", "begin ASC"]
        )
    except ToolError as exc:
        if "Permission" in str(exc) or "SecurityException" in str(exc):
            raise ToolError(_GRANT_HINT) from exc
        raise

    if "No result found" in raw:
        return {"count": 0, "events": [], "window": [begin.isoformat(), finish.isoformat()]}

    events = [_humanize(r) for r in _parse_rows(raw)]
    shown, clipped = truncate(events, _config.max_rows)
    return {
        "count": len(events),
        "truncated": clipped,
        "window": [begin.isoformat(timespec="minutes"), finish.isoformat(timespec="minutes")],
        "events": shown,
    }


@tool(
    name="calendar_create_event",
    description=(
        "Create a calendar event. This opens the calendar app with the details filled "
        "in; the user taps save to commit it. Report it as drafted, not saved."
    ),
    parameters=schema(
        {
            "title": string("Event title."),
            "start": string("Start time as an ISO datetime, e.g. 2026-08-21T14:30."),
            "duration_minutes": integer("How long the event lasts (default 60).", default=60),
            "location": string("Optional location."),
            "description": string("Optional notes for the event body."),
        },
        required=["title", "start"],
    ),
    category="calendar",
    consequential=True,
)
def calendar_create_event(
    title: str,
    start: str,
    duration_minutes: int = 60,
    location: str = "",
    description: str = "",
) -> dict[str, Any]:
    begin = _parse_when(start, fallback=datetime.now())
    if not (title or "").strip():
        raise ToolError("An event needs a title.")
    minutes = max(1, min(int(duration_minutes), 60 * 24 * 7))
    finish = begin + timedelta(minutes=minutes)

    argv = [
        "am", "start",
        "-a", "android.intent.action.INSERT",
        "-t", "vnd.android.cursor.item/event",
        "-e", "title", title,
        "--el", "beginTime", str(int(begin.timestamp() * 1000)),
        "--el", "endTime", str(int(finish.timestamp() * 1000)),
    ]
    if location:
        argv += ["-e", "eventLocation", location]
    if description:
        argv += ["-e", "description", description]
    android(argv)

    return {
        "drafted": True,
        "note": "The calendar app is open with this event prefilled. It is not saved until the user taps save.",
        "title": title,
        "start": begin.isoformat(timespec="minutes"),
        "end": finish.isoformat(timespec="minutes"),
    }
