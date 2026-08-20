from __future__ import annotations

import json
from pathlib import Path

import pytest

from sn.config import AgentConfig, Config, OllamaConfig, ToolsConfig
from sn.llm import Message
from sn.tools import configure as configure_tools


class FakeClient:
    """Stands in for OllamaClient. Replays a scripted list of assistant turns."""

    def __init__(self, replies: list[Message]):
        self.replies = list(replies)
        self.calls: list[list[dict]] = []

    def stream_chat(self, messages, tools=None):
        self.calls.append(list(messages))
        reply = self.replies.pop(0) if self.replies else Message(content="(out of replies)")
        if reply.content:
            yield "content", reply.content
        yield "message", reply


def tool_call(name: str, **arguments) -> dict:
    return {"function": {"name": name, "arguments": arguments}}


@pytest.fixture
def config(tmp_path: Path) -> Config:
    cfg = Config(
        ollama=OllamaConfig(host="http://test", model="test-model"),
        agent=AgentConfig(max_steps=4),
        tools=ToolsConfig(file_roots=(str(tmp_path / "sandbox"),), max_rows=5),
        state_dir=tmp_path / "state",
    )
    (tmp_path / "sandbox").mkdir()
    configure_tools(cfg.tools)
    return cfg


@pytest.fixture
def stub(tmp_path: Path, monkeypatch) -> Path:
    """A directory of canned termux-api responses."""
    root = tmp_path / "stub"
    root.mkdir()
    monkeypatch.setenv("SN_TERMUX_STUB", str(root))
    return root


def write_stub(root: Path, command: str, payload) -> None:
    text = payload if isinstance(payload, str) else json.dumps(payload)
    (root / f"{command}.json").write_text(text, encoding="utf-8")
