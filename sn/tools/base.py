"""Tool registry.

A tool is a plain Python function plus a JSON schema. Registration is by
decorator; the agent turns the registry into Ollama's tool format and dispatches
calls back by name.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable


class ToolError(Exception):
    """A tool failed in a way the model should see and reason about.

    Raise this for expected failures — missing permission, no such contact, a
    path outside the sandbox. The message goes back to the model as the tool
    result, so write it for that reader.
    """


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    parameters: dict
    func: Callable[..., Any]
    category: str = "misc"
    # True for tools with a side effect on the world outside the phone, or one
    # the user cannot undo. These default into the confirm list.
    consequential: bool = False

    def spec(self) -> dict:
        """This tool in the shape Ollama's /api/chat `tools` field wants."""
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            },
        }

    def __call__(self, **kwargs: Any) -> Any:
        return self.func(**kwargs)


REGISTRY: dict[str, Tool] = {}


def tool(
    *,
    name: str,
    description: str,
    parameters: dict | None = None,
    category: str = "misc",
    consequential: bool = False,
) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """Register a function as a tool. Returns the function unchanged."""

    def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
        if name in REGISTRY:
            raise RuntimeError(f"duplicate tool name: {name}")
        REGISTRY[name] = Tool(
            name=name,
            description=description.strip(),
            parameters=parameters or schema({}),
            func=func,
            category=category,
            consequential=consequential,
        )
        return func

    return decorator


def schema(
    properties: dict[str, dict], required: list[str] | None = None
) -> dict:
    """Small helper so tool definitions stay readable."""
    return {
        "type": "object",
        "properties": properties,
        "required": required or [],
    }


def string(description: str, **extra: Any) -> dict:
    return {"type": "string", "description": description, **extra}


def integer(description: str, **extra: Any) -> dict:
    return {"type": "integer", "description": description, **extra}


def boolean(description: str, **extra: Any) -> dict:
    return {"type": "boolean", "description": description, **extra}


@dataclass
class Toolbox:
    """The subset of the registry a given agent run is allowed to use."""

    tools: dict[str, Tool] = field(default_factory=dict)

    @classmethod
    def build(cls, disabled: tuple[str, ...] = ()) -> "Toolbox":
        from . import load_all  # noqa: PLC0415 — avoids an import cycle at module load

        load_all()
        blocked = set(disabled)
        return cls({n: t for n, t in REGISTRY.items() if n not in blocked})

    def specs(self) -> list[dict]:
        return [t.spec() for t in self.tools.values()]

    def get(self, name: str) -> Tool:
        if name not in self.tools:
            known = ", ".join(sorted(self.tools)) or "none"
            raise ToolError(f"No tool named {name!r}. Available tools: {known}")
        return self.tools[name]

    def by_category(self) -> dict[str, list[Tool]]:
        out: dict[str, list[Tool]] = {}
        for t in self.tools.values():
            out.setdefault(t.category, []).append(t)
        for tools in out.values():
            tools.sort(key=lambda t: t.name)
        return dict(sorted(out.items()))
