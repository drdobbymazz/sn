"""`sn doctor` — check everything that has to be true for the agent to work.

Ordered from the phone outward: the Termux install, then the permissions
Android grants separately, then the tailnet, then the model. Each failure says
what to do about it rather than just reporting a state.
"""

from __future__ import annotations

import os
import shutil
import sys
from datetime import datetime, timedelta
from pathlib import Path

from ..config import Config
from ..llm import LLMError, OllamaClient
from ..tools.base import ToolError
from ..tools.termux import android, run_json
from . import console as c

# One representative binary per capability, so a partial install is visible.
REQUIRED_BINARIES = {
    "termux-battery-status": "battery and device state",
    "termux-sms-list": "reading messages",
    "termux-sms-send": "sending messages",
    "termux-contact-list": "contacts",
    "termux-telephony-call": "placing calls",
    "termux-clipboard-get": "clipboard",
    "termux-notification": "notifications",
    "termux-location": "location",
    "termux-camera-photo": "camera",
}


class Report:
    def __init__(self) -> None:
        self.failures = 0

    def ok(self, label: str, detail: str = "") -> None:
        c.out(f"  {c.green('✓')} {label}" + (c.dim(f"  {detail}") if detail else ""))

    def warn(self, label: str, hint: str = "") -> None:
        c.out(f"  {c.yellow('~')} {label}")
        if hint:
            c.out(c.dim(f"      {hint}"))

    def fail(self, label: str, hint: str = "") -> None:
        self.failures += 1
        c.out(f"  {c.red('✗')} {label}")
        if hint:
            c.out(c.dim(f"      {hint}"))

    def section(self, title: str) -> None:
        c.out(c.bold(f"\n{title}"))


def check_runtime(r: Report) -> None:
    r.section("runtime")
    version = ".".join(str(v) for v in sys.version_info[:3])
    if sys.version_info >= (3, 11):
        r.ok("python", version)
    else:
        r.fail(f"python {version} is too old", "sn needs 3.11 or newer: pkg install python")

    if "com.termux" in os.environ.get("PREFIX", ""):
        r.ok("running inside Termux")
    else:
        r.warn(
            "not running inside Termux",
            "The phone tools will not work here. This is fine for `sn config` and for "
            "checking the model, but the agent needs to run on the phone.",
        )


def check_termux_api(r: Report) -> None:
    r.section("termux-api")
    missing = [b for b in REQUIRED_BINARIES if shutil.which(b) is None]
    if missing:
        r.fail(
            f"{len(missing)} of {len(REQUIRED_BINARIES)} commands missing",
            "pkg install termux-api — and install the Termux:API app from the same "
            "source as Termux itself (both F-Droid, or both GitHub).\n      missing: "
            + ", ".join(missing),
        )
        return
    r.ok("commands installed", f"{len(REQUIRED_BINARIES)} checked")

    try:
        battery = run_json(["termux-battery-status"], default={}, timeout=15)
    except ToolError as exc:
        r.fail(
            "the Termux:API app is not responding",
            f"{exc}\n      Install the Termux:API app and open it once, so Android "
            "registers it.",
        )
        return
    if battery:
        r.ok("Termux:API app responding", f"battery {battery.get('percentage')}%")
    else:
        r.warn("Termux:API returned nothing", "Try running termux-battery-status by hand.")


def check_permissions(r: Report) -> None:
    r.section("permissions")

    storage = Path.home() / "storage"
    if storage.is_dir():
        r.ok("shared storage", str(storage))
    else:
        r.fail(
            "shared storage not set up",
            "Run termux-setup-storage and accept the Android prompt. Without it the "
            "file tools can only see Termux's own home directory.",
        )

    for label, argv, hint in (
        (
            "contacts",
            ["termux-contact-list"],
            "Grant Contacts to Termux:API in Android app settings.",
        ),
        (
            "SMS",
            ["termux-sms-list", "-l", "1"],
            "Grant SMS to Termux:API in Android app settings.",
        ),
    ):
        try:
            run_json(argv, default=[], timeout=20)
            r.ok(label)
        except ToolError as exc:
            r.fail(f"{label} unavailable", f"{exc}\n      {hint}")

    try:
        run_json(["termux-notification-list"], default=[], timeout=20)
        r.ok("notification access")
    except ToolError:
        r.warn(
            "cannot read notifications",
            "Android Settings > Notifications > Device & app notifications > "
            "Termux:API. Everything else still works without this.",
        )

    now = datetime.now()
    uri = (
        "content://com.android.calendar/instances/when/"
        f"{int(now.timestamp() * 1000)}/{int((now + timedelta(days=1)).timestamp() * 1000)}"
    )
    try:
        android(["content", "query", "--uri", uri, "--projection", "title"], timeout=20)
        r.ok("calendar read")
    except ToolError:
        r.warn(
            "cannot read the calendar",
            "Android will not prompt for this one; grant it over ADB:\n"
            "      adb shell pm grant com.termux android.permission.READ_CALENDAR\n"
            "      See docs/permissions.md. Creating events works without it.",
        )


def check_model(r: Report, config: Config) -> None:
    r.section("model")
    client = OllamaClient(config.ollama)

    try:
        models = client.list_models()
    except LLMError as exc:
        r.fail(f"cannot reach {config.ollama.host}", str(exc))
        return
    r.ok("ollama reachable", f"{config.ollama.host} · {len(models)} models")

    wanted = config.ollama.model
    if wanted in models or f"{wanted}:latest" in models:
        r.ok("model present", wanted)
    else:
        r.fail(
            f"model {wanted!r} is not on the server",
            f"ollama pull {wanted}\n      available: {', '.join(models[:8]) or 'none'}",
        )
        return

    try:
        caps = client.capabilities()
    except LLMError:
        caps = []
    if not caps:
        r.warn(
            "could not read model capabilities",
            "Older Ollama builds do not report them. If tool calls never fire, the "
            "model probably lacks tool support.",
        )
    elif "tools" in caps:
        r.ok("model supports tools", ", ".join(caps))
    else:
        r.fail(
            f"{wanted} cannot call tools",
            "sn needs a tool-capable model — qwen3, llama3.1/3.2, mistral-nemo or "
            "similar. Without tools it can only chat.",
        )


def run_checks(config: Config) -> int:
    c.out(c.bold("sn doctor"))
    c.out(c.dim(f"  config: {config.source or '(defaults)'}"))

    r = Report()
    check_runtime(r)
    check_termux_api(r)
    if "com.termux" in os.environ.get("PREFIX", ""):
        check_permissions(r)
    check_model(r, config)

    c.out()
    if r.failures:
        c.out(c.red(f"{r.failures} problem(s) to fix."))
        return 1
    c.out(c.green("All good."))
    return 0
