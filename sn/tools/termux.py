"""Thin wrapper around the termux-api command line tools.

Everything the agent knows about the phone comes through here. The failure
modes are specific enough to be worth naming: the `termux-api` package and the
Termux:API *app* are two separate installs, and a missing runtime permission
shows up as a hang rather than an error.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from pathlib import Path
from typing import Any

from .base import ToolError

DEFAULT_TIMEOUT = 30.0

def stub_dir() -> Path | None:
    """Directory of canned responses, for running the agent off-device.

    Set SN_TERMUX_STUB to a directory holding files named after the command,
    e.g. termux-battery-status.json. Read per call rather than at import, so
    tests can set it without reloading the module.
    """
    value = os.environ.get("SN_TERMUX_STUB")
    return Path(value) if value else None

_MISSING = """\
`{cmd}` is not installed. termux-api needs two separate pieces:
  1. pkg install termux-api            (the command line tools)
  2. the Termux:API app from F-Droid   (the part that talks to Android)
Both must be present, and both must come from the same source (F-Droid or \
GitHub) or Android will refuse to let them talk to each other."""


def available(command: str) -> bool:
    if stub := stub_dir():
        return (stub / f"{command}.json").exists()
    return shutil.which(command) is not None


def run(
    argv: list[str],
    *,
    stdin: str | None = None,
    timeout: float = DEFAULT_TIMEOUT,
) -> str:
    """Run a termux-api command, return stdout. Raises ToolError on failure."""
    command = argv[0]

    if root := stub_dir():
        stub = root / f"{command}.json"
        if not stub.exists():
            raise ToolError(f"no stub for {command} in {root}")
        return stub.read_text(encoding="utf-8")

    if shutil.which(command) is None:
        raise ToolError(_MISSING.format(cmd=command))

    try:
        proc = subprocess.run(
            argv,
            input=stdin,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise ToolError(
            f"`{command}` timed out after {timeout:g}s. This usually means Android is "
            "waiting on a permission dialog — open the Termux:API app's permissions in "
            "Android settings and grant the one this tool needs."
        ) from exc
    except FileNotFoundError as exc:
        raise ToolError(_MISSING.format(cmd=command)) from exc

    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip() or f"exit code {proc.returncode}"
        raise ToolError(f"`{command}` failed: {detail}")

    stderr = (proc.stderr or "").strip()
    if stderr and not (proc.stdout or "").strip():
        # termux-api reports some permission failures on stderr with exit 0.
        raise ToolError(f"`{command}`: {stderr}")

    return proc.stdout


def run_json(
    argv: list[str],
    *,
    default: Any = None,
    timeout: float = DEFAULT_TIMEOUT,
) -> Any:
    """Run a termux-api command that emits JSON and parse it."""
    out = run(argv, timeout=timeout).strip()
    if not out:
        return default
    try:
        return json.loads(out)
    except json.JSONDecodeError as exc:
        raise ToolError(f"`{argv[0]}` returned output that is not JSON: {out[:200]}") from exc


def android(argv: list[str], *, timeout: float = DEFAULT_TIMEOUT) -> str:
    """Run a plain Android binary (am, content) rather than a termux-api one.

    Used only where termux-api has no equivalent — notably the calendar, which
    it does not cover at all.
    """
    if root := stub_dir():
        stub = root / f"{argv[0]}.txt"
        if not stub.exists():
            raise ToolError(f"no stub for {argv[0]} in {root}")
        return stub.read_text(encoding="utf-8")

    try:
        proc = subprocess.run(
            argv, capture_output=True, text=True, timeout=timeout, check=False
        )
    except subprocess.TimeoutExpired as exc:
        raise ToolError(f"`{argv[0]}` timed out after {timeout:g}s") from exc
    except FileNotFoundError as exc:
        raise ToolError(
            f"`{argv[0]}` not found. It ships with Android at /system/bin; if it is "
            "missing, `pkg install termux-tools` restores the wrappers."
        ) from exc

    combined = f"{proc.stdout}\n{proc.stderr}".strip()
    if proc.returncode != 0 or "SecurityException" in combined or "Permission Denial" in combined:
        raise ToolError(f"`{argv[0]}` failed: {combined or proc.returncode}")
    return proc.stdout


def truncate(rows: list, limit: int) -> tuple[list, bool]:
    """Cap a result list so one call cannot flood the model's context."""
    if len(rows) <= limit:
        return rows, False
    return rows[:limit], True
