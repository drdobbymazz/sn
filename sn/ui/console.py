"""Terminal formatting.

Deliberately small: this runs in Termux, where the terminal is a phone screen
and anything clever tends to wrap badly.
"""

from __future__ import annotations

import json
import os
import sys
from typing import Any

_ENABLED = sys.stdout.isatty() and not os.environ.get("NO_COLOR")

RESET = "\033[0m" if _ENABLED else ""
DIM = "\033[2m" if _ENABLED else ""
BOLD = "\033[1m" if _ENABLED else ""
CYAN = "\033[36m" if _ENABLED else ""
GREEN = "\033[32m" if _ENABLED else ""
YELLOW = "\033[33m" if _ENABLED else ""
RED = "\033[31m" if _ENABLED else ""


def dim(text: str) -> str:
    return f"{DIM}{text}{RESET}"


def bold(text: str) -> str:
    return f"{BOLD}{text}{RESET}"


def cyan(text: str) -> str:
    return f"{CYAN}{text}{RESET}"


def green(text: str) -> str:
    return f"{GREEN}{text}{RESET}"


def yellow(text: str) -> str:
    return f"{YELLOW}{text}{RESET}"


def red(text: str) -> str:
    return f"{RED}{text}{RESET}"


def out(text: str = "") -> None:
    print(text, flush=True)


def err(text: str) -> None:
    print(text, file=sys.stderr, flush=True)


def format_arguments(arguments: dict, *, full: bool = False) -> str:
    """Render tool arguments for display.

    `full` keeps long strings intact — used at the confirmation prompt, where
    the whole point is seeing exactly what is about to be sent.
    """
    if not arguments:
        return ""
    limit = 2000 if full else 60
    parts = []
    for key, value in arguments.items():
        if isinstance(value, str):
            shown = value if len(value) <= limit else value[:limit] + "…"
            parts.append(f"{key}={shown!r}")
        else:
            parts.append(f"{key}={json.dumps(value, default=str)}")
    return " ".join(parts)


def summarize_result(result: Any, width: int = 110) -> str:
    """One line describing what a tool returned."""
    if isinstance(result, str):
        text = result.strip().replace("\n", " ")
    else:
        text = json.dumps(result, default=str)
    return text if len(text) <= width else text[:width] + "…"
