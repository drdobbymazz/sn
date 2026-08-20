"""Command line entry point.

    sn                     start a conversation (resumes the last one)
    sn ask "..."           one question, one answer
    sn tools               what the agent can do
    sn doctor              check the phone, the tailnet and the model
    sn sessions            recent conversations
    sn config              show the resolved configuration
"""

from __future__ import annotations

import argparse
import sys
from typing import Iterable

from .. import __version__
from ..agent import Agent, Event, allow_all, deny_all
from ..config import Config, ConfigError, load
from ..llm import LLMError, OllamaClient
from ..session import Store
from ..tools import Toolbox, configure as configure_tools
from ..tools.base import Tool
from . import console as c

REPL_HELP = """\
Commands:
  /new      start a fresh conversation
  /tools    list available tools
  /help     this
  /quit     leave (or Ctrl-D)
"""


# -- confirmation ---------------------------------------------------------


def interactive_confirm(tool: Tool, arguments: dict) -> bool:
    """Ask before doing something that leaves the phone."""
    c.out()
    c.out(c.yellow(f"  {tool.name} wants to run:"))
    for key, value in arguments.items():
        rendered = value if isinstance(value, str) else repr(value)
        c.out(f"    {c.bold(key)}: {rendered}")
    try:
        answer = input(c.yellow("  run this? [y/N] ")).strip().lower()
    except (EOFError, KeyboardInterrupt):
        c.out()
        return False
    return answer in {"y", "yes"}


def make_confirmer(assume_yes: bool):
    if assume_yes:
        return allow_all
    if not sys.stdin.isatty():
        # A widget tap or a piped command cannot answer a prompt. Refusing is
        # the only safe reading of silence when the tool sends an SMS.
        return deny_all
    return interactive_confirm


# -- rendering ------------------------------------------------------------


def render(events: Iterable[Event], *, quiet: bool = False) -> str:
    """Print agent events as they arrive; return the final answer text."""
    answer = ""
    streaming = False

    for event in events:
        if event.kind == "content":
            if not quiet:
                print(event.text, end="", flush=True)
                streaming = True
        elif event.kind == "final":
            if streaming:
                c.out()
            answer = event.text
        elif event.kind == "tool_start":
            if streaming:
                c.out()
                streaming = False
            if not quiet:
                args = c.format_arguments(event.arguments)
                c.out(c.dim(f"  · {event.tool}({args})"))
        elif event.kind == "tool_result":
            if not quiet:
                text = c.summarize_result(event.result)
                marker = "!" if str(event.result).startswith("Error") else "→"
                colour = c.red if marker == "!" else c.dim
                c.out(colour(f"    {marker} {text}"))
        elif event.kind == "tool_denied":
            if not quiet:
                c.out(c.yellow(f"    ✗ declined {event.tool}"))
        elif event.kind == "error":
            if streaming:
                c.out()
                streaming = False
            c.err(c.red(f"\n{event.text}"))

    return answer


# -- wiring ---------------------------------------------------------------


def build(config: Config, assume_yes: bool) -> tuple[Agent, Store]:
    configure_tools(config.tools)
    toolbox = Toolbox.build(config.tools.disabled)
    store = Store(config.db_path, config.audit_path)
    client = OllamaClient(config.ollama)
    agent = Agent(config, client, toolbox, store, confirm=make_confirmer(assume_yes))
    return agent, store


def notify(config: Config, title: str, content: str) -> None:
    """Push an answer to the status bar, for widget-launched runs."""
    from ..tools.termux import run  # noqa: PLC0415
    from ..tools.base import ToolError  # noqa: PLC0415

    try:
        run(["termux-notification", "--title", title, "--content", content[:1000], "--id", "sn-answer"])
    except ToolError as exc:
        c.err(c.dim(f"(could not post notification: {exc})"))


# -- commands -------------------------------------------------------------


def cmd_ask(args, config: Config) -> int:
    prompt = " ".join(args.prompt).strip()
    if not prompt and not sys.stdin.isatty():
        prompt = sys.stdin.read().strip()
    if not prompt:
        c.err("Nothing to ask. Pass a question, or pipe one in.")
        return 2

    agent, store = build(config, args.yes)
    try:
        session_id = store.new_session(prompt) if args.new else store.resume_or_create()
        store.set_title(session_id, prompt)
        answer = render(agent.run(prompt, session_id), quiet=args.quiet)
        if args.quiet and answer:
            c.out(answer)
        if args.notify and answer:
            notify(config, "sn", answer)
        return 0 if answer else 1
    finally:
        store.close()


def cmd_chat(args, config: Config) -> int:
    agent, store = build(config, args.yes)
    session_id = store.new_session() if args.new else store.resume_or_create()

    c.out(c.bold(f"sn {__version__}") + c.dim(f" · {config.ollama.model} · session {session_id}"))
    c.out(c.dim("/help for commands, Ctrl-D to leave"))

    try:
        while True:
            try:
                prompt = input(c.cyan("\n› ")).strip()
            except (EOFError, KeyboardInterrupt):
                c.out()
                return 0

            if not prompt:
                continue
            if prompt in {"/quit", "/exit", "/q"}:
                return 0
            if prompt == "/help":
                c.out(REPL_HELP)
                continue
            if prompt == "/new":
                session_id = store.new_session()
                c.out(c.dim(f"new session {session_id}"))
                continue
            if prompt == "/tools":
                print_tools(agent.toolbox, config)
                continue

            store.set_title(session_id, prompt)
            try:
                render(agent.run(prompt, session_id))
            except KeyboardInterrupt:
                c.out(c.dim("\n(interrupted)"))
    finally:
        store.close()


def print_tools(toolbox: Toolbox, config: Config) -> None:
    for category, tools in toolbox.by_category().items():
        c.out(c.bold(f"\n{category}"))
        for tool in tools:
            gated = tool.name in config.tools.confirm or tool.consequential
            mark = c.yellow(" [confirms]") if gated else ""
            first_line = tool.description.split(". ")[0].rstrip(".")
            c.out(f"  {tool.name}{mark}\n    {c.dim(first_line)}")
    c.out()


def cmd_tools(args, config: Config) -> int:
    configure_tools(config.tools)
    print_tools(Toolbox.build(config.tools.disabled), config)
    return 0


def cmd_sessions(args, config: Config) -> int:
    store = Store(config.db_path, config.audit_path)
    try:
        rows = store.sessions(args.limit)
        if not rows:
            c.out("No conversations yet.")
            return 0
        for row in rows:
            title = row["title"] or c.dim("(untitled)")
            c.out(f"{c.bold(str(row['id'])):>6}  {row['started_at']}  {row['messages']:>3} msgs  {title}")
        return 0
    finally:
        store.close()


def cmd_config(args, config: Config) -> int:
    source = config.source or c.dim("(defaults — no config file yet)")
    c.out(f"{c.bold('config'):>14}  {source}")
    c.out(f"{c.bold('state'):>14}  {config.state_dir}")
    c.out(f"{c.bold('ollama'):>14}  {config.ollama.host}")
    c.out(f"{c.bold('model'):>14}  {config.ollama.model}")
    c.out(f"{c.bold('max steps'):>14}  {config.agent.max_steps}")
    c.out(f"{c.bold('confirms'):>14}  {', '.join(config.tools.confirm) or '(nothing)'}")
    c.out(f"{c.bold('disabled'):>14}  {', '.join(config.tools.disabled) or '(nothing)'}")
    c.out(f"{c.bold('file roots'):>14}  {', '.join(config.tools.file_roots)}")
    return 0


def cmd_doctor(args, config: Config) -> int:
    from .doctor import run_checks  # noqa: PLC0415

    return run_checks(config)


# -- argument parsing -----------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="sn",
        description="A local agent that lives on your phone.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--version", action="version", version=f"sn {__version__}")
    parser.add_argument("--config", help="path to config.toml")
    parser.add_argument("--model", help="override the model for this run")
    parser.add_argument("--host", help="override the Ollama host for this run")

    sub = parser.add_subparsers(dest="command")

    ask = sub.add_parser("ask", help="ask one question and exit")
    ask.add_argument("prompt", nargs="*", help="the question")
    ask.add_argument("-y", "--yes", action="store_true", help="skip confirmation prompts")
    ask.add_argument("-n", "--new", action="store_true", help="start a fresh conversation")
    ask.add_argument("-q", "--quiet", action="store_true", help="print only the final answer")
    ask.add_argument("--notify", action="store_true", help="also post the answer as a notification")
    ask.set_defaults(func=cmd_ask)

    chat = sub.add_parser("chat", help="interactive conversation (the default)")
    chat.add_argument("-y", "--yes", action="store_true", help="skip confirmation prompts")
    chat.add_argument("-n", "--new", action="store_true", help="start a fresh conversation")
    chat.set_defaults(func=cmd_chat)

    tools = sub.add_parser("tools", help="list available tools")
    tools.set_defaults(func=cmd_tools)

    doctor = sub.add_parser("doctor", help="check phone, tailnet and model")
    doctor.set_defaults(func=cmd_doctor)

    sessions = sub.add_parser("sessions", help="recent conversations")
    sessions.add_argument("--limit", type=int, default=15)
    sessions.set_defaults(func=cmd_sessions)

    conf = sub.add_parser("config", help="show the resolved configuration")
    conf.set_defaults(func=cmd_config)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    raw = list(argv) if argv is not None else sys.argv[1:]
    args = parser.parse_args(raw)

    # Bare `sn` means `sn chat`. Re-parse rather than fake the namespace, so the
    # global flags in front of it are kept.
    if not args.command:
        args = parser.parse_args([*raw, "chat"])

    try:
        config = load(args.config)
    except ConfigError as exc:
        c.err(c.red(f"Bad config: {exc}"))
        return 2

    if args.model or args.host:
        from dataclasses import replace  # noqa: PLC0415

        config = replace(
            config,
            ollama=replace(
                config.ollama,
                model=args.model or config.ollama.model,
                host=(args.host.rstrip("/") if args.host else config.ollama.host),
            ),
        )

    try:
        return args.func(args, config)
    except LLMError as exc:
        c.err(c.red(str(exc)))
        return 1
    except KeyboardInterrupt:
        c.out()
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
