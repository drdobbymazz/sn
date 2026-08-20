"""The agent loop: ask the model, run the tools it asks for, repeat.

Emits events as it goes so the CLI can stream text and show tool activity
without the loop knowing anything about terminals.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Callable, Iterator

from .config import Config
from .llm import LLMError, Message, OllamaClient
from .session import Store
from .tools import Toolbox
from .tools.base import Tool, ToolError

# Asked whether a consequential tool may run. Returning False declines it and
# tells the model so, which is different from the tool failing.
Confirmer = Callable[[Tool, dict], bool]


def allow_all(tool: Tool, arguments: dict) -> bool:
    return True


def deny_all(tool: Tool, arguments: dict) -> bool:
    return False


@dataclass
class Event:
    kind: str  # content | tool_start | tool_result | tool_denied | final | error
    text: str = ""
    tool: str = ""
    arguments: dict = field(default_factory=dict)
    result: Any = None


class Agent:
    def __init__(
        self,
        config: Config,
        client: OllamaClient,
        toolbox: Toolbox,
        store: Store | None = None,
        confirm: Confirmer = allow_all,
    ):
        self.config = config
        self.client = client
        self.toolbox = toolbox
        self.store = store
        self.confirm = confirm

    # -- helpers ----------------------------------------------------------

    def _needs_confirmation(self, tool: Tool) -> bool:
        return tool.name in self.config.tools.confirm or tool.consequential

    @staticmethod
    def _arguments(call: dict) -> dict:
        """Pull arguments out of a tool call, whether dict or JSON string."""
        raw = (call.get("function") or {}).get("arguments", {})
        if isinstance(raw, str):
            try:
                raw = json.loads(raw or "{}")
            except json.JSONDecodeError:
                return {}
        return raw if isinstance(raw, dict) else {}

    def _execute(self, name: str, arguments: dict) -> tuple[Any, str | None]:
        """Run one tool. Returns (result, error_message)."""
        try:
            tool = self.toolbox.get(name)
        except ToolError as exc:
            return None, str(exc)

        # Drop arguments the tool does not accept rather than crashing on a
        # model that invents one.
        accepted = set(tool.parameters.get("properties", {}))
        cleaned = {k: v for k, v in arguments.items() if k in accepted}

        try:
            return tool(**cleaned), None
        except ToolError as exc:
            return None, str(exc)
        except TypeError as exc:
            return None, f"Wrong arguments for {name}: {exc}"
        except Exception as exc:  # noqa: BLE001 — a tool must never kill the loop
            return None, f"{name} failed unexpectedly: {type(exc).__name__}: {exc}"

    def _record(self, session_id: int | None, message: dict) -> None:
        if self.store and session_id is not None:
            self.store.append(session_id, message)

    # -- the loop ---------------------------------------------------------

    def run(self, prompt: str, session_id: int | None = None) -> Iterator[Event]:
        history: list[dict] = []
        if self.store and session_id is not None:
            history = self.store.history(session_id, self.config.agent.history_turns * 2)

        user_message = {"role": "user", "content": prompt}
        self._record(session_id, user_message)

        messages: list[dict] = [
            {"role": "system", "content": self.config.agent.system_prompt},
            *history,
            user_message,
        ]
        specs = self.toolbox.specs()

        for step in range(self.config.agent.max_steps):
            reply = Message()
            try:
                for kind, value in self.client.stream_chat(messages, specs):
                    if kind == "content":
                        yield Event("content", text=value)
                    else:
                        reply = value
            except LLMError as exc:
                yield Event("error", text=str(exc))
                return

            assistant_message = reply.to_dict()
            messages.append(assistant_message)
            self._record(session_id, assistant_message)

            if not reply.tool_calls:
                yield Event("final", text=reply.content)
                return

            for call in reply.tool_calls:
                name = (call.get("function") or {}).get("name", "")
                arguments = self._arguments(call)
                tool = self.toolbox.tools.get(name)

                if tool is not None and self._needs_confirmation(tool):
                    if not self.confirm(tool, arguments):
                        yield Event("tool_denied", tool=name, arguments=arguments)
                        note = (
                            "The user declined to run this tool. Do not try it again. "
                            "Acknowledge briefly and ask what they would prefer."
                        )
                        self._append_tool_result(messages, session_id, name, note)
                        if self.store and session_id is not None:
                            self.store.audit(
                                session_id=session_id,
                                tool=name,
                                arguments=arguments,
                                decision="denied",
                            )
                        continue

                yield Event("tool_start", tool=name, arguments=arguments)
                result, error = self._execute(name, arguments)

                if self.store and session_id is not None:
                    self.store.audit(
                        session_id=session_id,
                        tool=name,
                        arguments=arguments,
                        decision="ran",
                        result=result,
                        error=error,
                    )

                payload = f"Error: {error}" if error else json.dumps(result, default=str)
                self._append_tool_result(messages, session_id, name, payload)
                yield Event(
                    "tool_result", tool=name, arguments=arguments, result=error or result
                )

        yield Event(
            "error",
            text=(
                f"Stopped after {self.config.agent.max_steps} tool steps without a final "
                "answer. Raise agent.max_steps in the config, or ask something narrower."
            ),
        )

    def _append_tool_result(
        self, messages: list[dict], session_id: int | None, name: str, content: str
    ) -> None:
        message = {"role": "tool", "tool_name": name, "content": content}
        messages.append(message)
        self._record(session_id, message)
