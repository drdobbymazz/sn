# Architecture

## The shape of it

```
sn/
  config.py     TOML + env, with defaults that work
  llm.py        Ollama client over stdlib urllib
  agent.py      the loop: ask, run tools, repeat
  session.py    SQLite conversation history + JSONL audit log
  tools/
    base.py     registry, JSON schema helpers, Toolbox
    termux.py   the one place that shells out to termux-api
    messaging.py device.py calendar.py files.py sensing.py
  ui/
    cli.py      argparse, the REPL, confirmation prompts
    console.py  colours and formatting
    doctor.py   the preflight checks
```

Two rules keep it navigable: the agent loop knows nothing about terminals — it
emits `Event`s and the CLI decides how to draw them — and only `tools/termux.py`
runs a subprocess.

## No runtime dependencies

Everything is stdlib. This is a phone: a wheel that needs a compiler is a bad
evening, and a broken `pip` in Termux is worse. `urllib.request` streams
Ollama's NDJSON perfectly well.

## The loop

`Agent.run` is a generator that yields events:

| Event | When |
| --- | --- |
| `content` | a chunk of the answer, as it streams |
| `tool_start` / `tool_result` | a tool ran |
| `tool_denied` | you said no |
| `final` | the answer, complete |
| `error` | unreachable model, or out of steps |

Each pass sends the conversation plus every tool schema to `/api/chat`. If the
reply has no `tool_calls`, that is the answer. Otherwise each call is confirmed
if gated, executed, and appended as a `tool` message before going round again,
up to `agent.max_steps`.

Failures are values, not exceptions. A `ToolError` becomes `Error: ...` in the
tool result and the model reads it and adapts — usually by fixing its arguments
or telling you what is wrong. Even an unexpected exception is caught and
described, because a crashing tool should not lose the conversation.

## Why the model is remote and the tools are not

An 8B model on the phone would be slow, hot and much worse at choosing tools.
Running it on the laptop over Tailscale costs a round trip but nothing else —
the tailnet is encrypted and does not touch the public internet.

The tools stay on the phone because that is where the data is. Nothing about
your messages, contacts or files is uploaded anywhere; only the text the model
needs to see crosses the tailnet, and only to your own laptop.

The trade-off is honest: **when the laptop is asleep, the agent cannot think.**
`sn doctor` and the error messages both point at this first, because it is the
failure you will hit most.

## Adding a tool

Write the function, decorate it, list its module in `tools/__init__.py`:

```python
from .base import ToolError, schema, string, tool

@tool(
    name="torch_set",
    description="Turn the phone's flashlight on or off.",
    parameters=schema({"on": {"type": "boolean", "description": "True for on."}},
                      required=["on"]),
    category="device",
)
def torch_set(on: bool) -> dict:
    run(["termux-torch", "on" if on else "off"])
    return {"torch": "on" if on else "off"}
```

Things worth getting right:

- **The description is the prompt.** It is the only thing the model knows about
  when to reach for this. Say what it is for, not just what it does.
- **Describe every argument**, including the enum values. A test enforces this.
- **Raise `ToolError` for expected failures**, with a message written for the
  model to act on: what went wrong and what to do instead.
- **Return something JSON-serializable and small.** Respect
  `config.tools.max_rows` — one tool call should never fill the context window.
- **Set `consequential=True`** if it touches the world outside the phone. That
  puts it behind the confirmation prompt by default.

## Testing without a phone

`SN_TERMUX_STUB=<dir>` makes `tools/termux.py` read `<dir>/termux-sms-list.json`
instead of running the command, so tool logic is testable on a laptop. The
`stub` fixture in `tests/conftest.py` sets this up, and `FakeClient` replays
scripted model turns so the agent loop can be tested without a model at all.

```sh
pytest
```

## What is deliberately missing

- **No shell tool.** The agent cannot run arbitrary commands. Every capability
  is named and bounded, which is what makes the confirmation list meaningful.
- **No file writing.** The file tools read. Adding a write is a small change,
  and a real decision — make it consciously.
- **No notification listening.** `notifications_list` reads what is in the
  status bar when asked; it cannot react to a notification arriving. That needs
  a native Android `NotificationListenerService`, which is the main reason to
  eventually wrap this in a Kotlin app.
