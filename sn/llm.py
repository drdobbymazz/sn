"""Minimal Ollama client.

Stdlib only, on purpose: this runs on a phone where a broken pip install is a
genuinely annoying thing to debug over SSH.

Talks to /api/chat with native tool calling. The host is normally a Tailscale
MagicDNS name, e.g. http://oryx.tail1234.ts.net:11434.
"""

from __future__ import annotations

import json
import socket
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Iterator

from .config import OllamaConfig


class LLMError(Exception):
    """Anything that went wrong talking to Ollama, phrased for a human."""


@dataclass
class Message:
    """One assistant turn, reassembled from however many stream chunks."""

    role: str = "assistant"
    content: str = ""
    tool_calls: list[dict] = field(default_factory=list)
    thinking: str = ""

    def to_dict(self) -> dict:
        out: dict[str, Any] = {"role": self.role, "content": self.content}
        if self.tool_calls:
            out["tool_calls"] = self.tool_calls
        return out


class OllamaClient:
    def __init__(self, config: OllamaConfig):
        self.config = config

    # -- plumbing ---------------------------------------------------------

    def _post(self, path: str, payload: dict, *, stream: bool):
        url = f"{self.config.host}{path}"
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=data,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            return urllib.request.urlopen(req, timeout=self.config.timeout)
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", "replace").strip()
            raise LLMError(self._explain_http(exc.code, body)) from exc
        except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as exc:
            raise LLMError(self._explain_network(exc)) from exc

    def _get(self, path: str) -> dict:
        url = f"{self.config.host}{path}"
        try:
            with urllib.request.urlopen(url, timeout=self.config.timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", "replace").strip()
            raise LLMError(self._explain_http(exc.code, body)) from exc
        except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as exc:
            raise LLMError(self._explain_network(exc)) from exc

    def _explain_http(self, code: int, body: str) -> str:
        detail = body
        try:
            detail = json.loads(body).get("error", body)
        except (json.JSONDecodeError, AttributeError):
            pass
        if code == 404 and "model" in detail.lower():
            return (
                f"Ollama has no model named {self.config.model!r}. "
                f"On the laptop: ollama pull {self.config.model}"
            )
        return f"Ollama returned HTTP {code}: {detail}"

    def _explain_network(self, exc: Exception) -> str:
        return (
            f"Cannot reach Ollama at {self.config.host} ({exc}).\n"
            "Check, in order:\n"
            "  1. tailscale status          — is the phone on the tailnet?\n"
            "  2. the laptop is awake and `ollama serve` is running\n"
            "  3. Ollama is listening on the tailnet, not just localhost:\n"
            "     OLLAMA_HOST=0.0.0.0:11434 ollama serve"
        )

    # -- api --------------------------------------------------------------

    def stream_chat(
        self,
        messages: list[dict],
        tools: list[dict] | None = None,
    ) -> Iterator[tuple[str, Any]]:
        """Yield ("content", delta) as text arrives, then ("message", Message).

        Tool calls are accumulated across chunks and delivered on the final
        ("message", ...) event rather than streamed, since a half-parsed tool
        call is of no use to anyone.
        """
        payload: dict[str, Any] = {
            "model": self.config.model,
            "messages": messages,
            "stream": True,
            "keep_alive": self.config.keep_alive,
        }
        if tools:
            payload["tools"] = tools
        if self.config.options:
            payload["options"] = self.config.options

        message = Message()
        resp = self._post("/api/chat", payload, stream=True)
        try:
            for raw in resp:
                line = raw.decode("utf-8").strip()
                if not line:
                    continue
                try:
                    chunk = json.loads(line)
                except json.JSONDecodeError as exc:
                    raise LLMError(f"Malformed response chunk from Ollama: {line[:200]}") from exc
                if err := chunk.get("error"):
                    raise LLMError(f"Ollama error: {err}")

                part = chunk.get("message") or {}
                if delta := part.get("content"):
                    message.content += delta
                    yield "content", delta
                if thinking := part.get("thinking"):
                    message.thinking += thinking
                if calls := part.get("tool_calls"):
                    message.tool_calls.extend(calls)
                if part.get("role"):
                    message.role = part["role"]
        finally:
            resp.close()

        yield "message", message

    def chat(self, messages: list[dict], tools: list[dict] | None = None) -> Message:
        """Non-streaming convenience wrapper around stream_chat."""
        message = Message()
        for kind, value in self.stream_chat(messages, tools):
            if kind == "message":
                message = value
        return message

    def list_models(self) -> list[str]:
        data = self._get("/api/tags")
        return sorted(m["name"] for m in data.get("models", []))

    def capabilities(self, model: str | None = None) -> list[str]:
        """Ask Ollama what the model can do. Used by `sn doctor`.

        Older Ollama builds omit the field; an empty list means "unknown",
        not "no capabilities".
        """
        payload = {"model": model or self.config.model}
        resp = self._post("/api/show", payload, stream=False)
        try:
            data = json.loads(resp.read().decode("utf-8"))
        finally:
            resp.close()
        return list(data.get("capabilities", []))
