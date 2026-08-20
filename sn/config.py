"""Configuration loading.

Config lives at ~/.config/sn/config.toml. Every value has a usable default, so
a missing config file is fine — but you will almost certainly want to set
`ollama.host` to your laptop's Tailscale name.

Environment variables override the file, which is handy for one-off runs and
for the widget scripts:

    SN_OLLAMA_HOST, SN_MODEL, SN_TIMEOUT, SN_CONFIG
"""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field, replace
from pathlib import Path

DEFAULT_CONFIG_PATH = Path(
    os.environ.get("SN_CONFIG")
    or Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "sn" / "config.toml"
)

DEFAULT_STATE_DIR = Path(
    os.environ.get("SN_STATE_DIR")
    or Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state")) / "sn"
)

# Tools that do something the user cannot quietly undo: money, noise, a message
# another human will read, a photo. These prompt before running unless the
# config or the caller says otherwise.
DEFAULT_CONFIRM = (
    "sms_send",
    "call_place",
    "camera_photo",
    "calendar_create_event",
    "notification_send",
)

DEFAULT_SYSTEM_PROMPT = """\
You are sn, a personal assistant running directly on the user's Android phone \
(a Samsung Galaxy S23 Ultra). You reach the phone's messages, contacts, \
calendar, files, camera, location and device state through tools.

How to behave:
- Use tools to find things out. Do not guess at the contents of the phone, and \
never invent a phone number, contact, message, event or file path.
- Prefer one targeted tool call over several broad ones. Read before you write.
- Tools that send messages, place calls or take photos are confirmed by the \
user before they run. Call them normally; do not ask for permission in prose \
first, and do not claim something was sent until a tool result says it was.
- If a tool returns an error, tell the user what actually failed rather than \
retrying the same call unchanged.
- The user is on a phone screen. Answer in a few short sentences. No preamble, \
no restating the question, no markdown tables.
"""


@dataclass(frozen=True)
class OllamaConfig:
    """Where the model lives. Usually a Tailscale MagicDNS name."""

    host: str = "http://127.0.0.1:11434"
    model: str = "qwen3:8b"
    timeout: float = 180.0
    keep_alive: str = "30m"
    # Passed straight through to Ollama's `options` object.
    options: dict = field(default_factory=dict)


@dataclass(frozen=True)
class AgentConfig:
    max_steps: int = 8
    system_prompt: str = DEFAULT_SYSTEM_PROMPT
    # How many past turns to replay into a resumed conversation.
    history_turns: int = 20


@dataclass(frozen=True)
class ToolsConfig:
    # Tool names that require an explicit yes before running.
    confirm: tuple[str, ...] = DEFAULT_CONFIRM
    # Tool names that are switched off entirely.
    disabled: tuple[str, ...] = ()
    # Directories the file tools may read from. Anything outside is refused,
    # including via symlink or `..`.
    file_roots: tuple[str, ...] = ("~/storage/shared", "~/storage/dcim", "~")
    max_file_bytes: int = 200_000
    # Cap on rows returned by list-shaped tools, so one call cannot flood the
    # model's context with 900 SMS messages.
    max_rows: int = 50


@dataclass(frozen=True)
class Config:
    ollama: OllamaConfig = field(default_factory=OllamaConfig)
    agent: AgentConfig = field(default_factory=AgentConfig)
    tools: ToolsConfig = field(default_factory=ToolsConfig)
    state_dir: Path = DEFAULT_STATE_DIR
    source: Path | None = None

    @property
    def db_path(self) -> Path:
        return self.state_dir / "sn.db"

    @property
    def audit_path(self) -> Path:
        return self.state_dir / "audit.jsonl"


def _section(raw: dict, name: str) -> dict:
    value = raw.get(name, {})
    if not isinstance(value, dict):
        raise ConfigError(f"[{name}] must be a table, got {type(value).__name__}")
    return value


class ConfigError(Exception):
    """Raised when a config file exists but cannot be used."""


def load(path: Path | None = None) -> Config:
    """Read the config file, apply env overrides, return a Config.

    A missing file is not an error; a malformed one is.
    """
    path = Path(path) if path else DEFAULT_CONFIG_PATH
    raw: dict = {}
    if path.exists():
        try:
            raw = tomllib.loads(path.read_text(encoding="utf-8"))
        except tomllib.TOMLDecodeError as exc:
            raise ConfigError(f"{path}: {exc}") from exc

    ollama_raw = _section(raw, "ollama")
    agent_raw = _section(raw, "agent")
    tools_raw = _section(raw, "tools")

    ollama = OllamaConfig(
        host=str(ollama_raw.get("host", OllamaConfig.host)).rstrip("/"),
        model=str(ollama_raw.get("model", OllamaConfig.model)),
        timeout=float(ollama_raw.get("timeout", OllamaConfig.timeout)),
        keep_alive=str(ollama_raw.get("keep_alive", OllamaConfig.keep_alive)),
        options=dict(ollama_raw.get("options", {})),
    )
    agent = AgentConfig(
        max_steps=int(agent_raw.get("max_steps", AgentConfig.max_steps)),
        system_prompt=str(agent_raw.get("system_prompt", DEFAULT_SYSTEM_PROMPT)),
        history_turns=int(agent_raw.get("history_turns", AgentConfig.history_turns)),
    )
    tools = ToolsConfig(
        confirm=tuple(tools_raw.get("confirm", DEFAULT_CONFIRM)),
        disabled=tuple(tools_raw.get("disabled", ())),
        file_roots=tuple(tools_raw.get("file_roots", ToolsConfig.file_roots)),
        max_file_bytes=int(tools_raw.get("max_file_bytes", ToolsConfig.max_file_bytes)),
        max_rows=int(tools_raw.get("max_rows", ToolsConfig.max_rows)),
    )

    cfg = Config(
        ollama=ollama,
        agent=agent,
        tools=tools,
        state_dir=Path(raw.get("state_dir", DEFAULT_STATE_DIR)).expanduser(),
        source=path if path.exists() else None,
    )
    return _apply_env(cfg)


def _apply_env(cfg: Config) -> Config:
    host = os.environ.get("SN_OLLAMA_HOST")
    model = os.environ.get("SN_MODEL")
    timeout = os.environ.get("SN_TIMEOUT")
    if not any((host, model, timeout)):
        return cfg
    ollama = replace(
        cfg.ollama,
        host=host.rstrip("/") if host else cfg.ollama.host,
        model=model or cfg.ollama.model,
        timeout=float(timeout) if timeout else cfg.ollama.timeout,
    )
    return replace(cfg, ollama=ollama)
