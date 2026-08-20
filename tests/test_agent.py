from __future__ import annotations

import pytest

from conftest import FakeClient, tool_call
from sn.agent import Agent, allow_all, deny_all
from sn.llm import Message
from sn.session import Store
from sn.tools import Toolbox
from sn.tools.base import Tool, schema, string


@pytest.fixture
def toolbox():
    """A toolbox with one harmless tool and one that needs confirmation."""
    calls: list[dict] = []

    def echo(text: str = "") -> dict:
        calls.append({"tool": "echo", "text": text})
        return {"echoed": text}

    def fire(target: str = "") -> dict:
        calls.append({"tool": "fire", "target": target})
        return {"fired": target}

    def broken() -> dict:
        raise RuntimeError("kaboom")

    box = Toolbox(
        {
            "echo": Tool("echo", "Echo text", schema({"text": string("t")}), echo),
            "fire": Tool(
                "fire", "Do something big", schema({"target": string("t")}), fire,
                consequential=True,
            ),
            "broken": Tool("broken", "Always fails", schema({}), broken),
        }
    )
    box.calls = calls  # type: ignore[attr-defined]
    return box


def run(agent, prompt="hello", session_id=None):
    return list(agent.run(prompt, session_id))


def test_plain_answer_needs_no_tools(config, toolbox):
    client = FakeClient([Message(content="the answer")])
    events = run(Agent(config, client, toolbox))

    assert [e.kind for e in events] == ["content", "final"]
    assert events[-1].text == "the answer"


def test_tool_result_is_fed_back_to_the_model(config, toolbox):
    client = FakeClient(
        [
            Message(tool_calls=[tool_call("echo", text="hi")]),
            Message(content="it said hi"),
        ]
    )
    events = run(Agent(config, client, toolbox))

    kinds = [e.kind for e in events]
    assert kinds == ["tool_start", "tool_result", "content", "final"]
    assert toolbox.calls == [{"tool": "echo", "text": "hi"}]

    # The second request must carry the assistant's tool call and its result.
    second = client.calls[1]
    assert second[-2]["role"] == "assistant"
    assert second[-1] == {"role": "tool", "tool_name": "echo", "content": '{"echoed": "hi"}'}


def test_consequential_tool_asks_first(config, toolbox):
    asked: list[tuple[str, dict]] = []

    def confirm(tool, arguments):
        asked.append((tool.name, arguments))
        return True

    client = FakeClient(
        [Message(tool_calls=[tool_call("fire", target="everything")]), Message(content="done")]
    )
    run(Agent(config, client, toolbox, confirm=confirm))

    assert asked == [("fire", {"target": "everything"})]
    assert toolbox.calls == [{"tool": "fire", "target": "everything"}]


def test_declining_stops_the_tool_but_not_the_turn(config, toolbox):
    client = FakeClient(
        [Message(tool_calls=[tool_call("fire", target="everything")]), Message(content="ok, skipped")]
    )
    events = run(Agent(config, client, toolbox, confirm=deny_all))

    assert [e.kind for e in events] == ["tool_denied", "content", "final"]
    assert toolbox.calls == []
    assert "declined" in client.calls[1][-1]["content"]
    assert events[-1].text == "ok, skipped"


def test_confirm_list_in_config_also_gates_a_tool(config, toolbox):
    from dataclasses import replace

    gated = replace(config, tools=replace(config.tools, confirm=("echo",)))
    client = FakeClient([Message(tool_calls=[tool_call("echo", text="hi")]), Message(content="x")])
    events = run(Agent(gated, client, toolbox, confirm=deny_all))

    assert events[0].kind == "tool_denied"
    assert toolbox.calls == []


def test_unknown_tool_is_reported_to_the_model(config, toolbox):
    client = FakeClient(
        [Message(tool_calls=[tool_call("nope")]), Message(content="I cannot do that")]
    )
    events = run(Agent(config, client, toolbox))

    result = next(e for e in events if e.kind == "tool_result")
    assert "No tool named" in str(result.result)
    assert client.calls[1][-1]["content"].startswith("Error:")


def test_a_crashing_tool_does_not_kill_the_loop(config, toolbox):
    client = FakeClient([Message(tool_calls=[tool_call("broken")]), Message(content="recovered")])
    events = run(Agent(config, client, toolbox))

    assert events[-1].text == "recovered"
    assert "kaboom" in client.calls[1][-1]["content"]


def test_unexpected_arguments_are_dropped(config, toolbox):
    client = FakeClient(
        [Message(tool_calls=[tool_call("echo", text="hi", nonsense=1)]), Message(content="ok")]
    )
    run(Agent(config, client, toolbox))

    assert toolbox.calls == [{"tool": "echo", "text": "hi"}]


def test_arguments_may_arrive_as_a_json_string(config, toolbox):
    call = {"function": {"name": "echo", "arguments": '{"text": "from a string"}'}}
    client = FakeClient([Message(tool_calls=[call]), Message(content="ok")])
    run(Agent(config, client, toolbox))

    assert toolbox.calls == [{"tool": "echo", "text": "from a string"}]


def test_runaway_tool_use_stops_at_max_steps(config, toolbox):
    client = FakeClient([Message(tool_calls=[tool_call("echo", text="again")])] * 10)
    events = run(Agent(config, client, toolbox))

    assert events[-1].kind == "error"
    assert "max_steps" in events[-1].text
    assert len(toolbox.calls) == config.agent.max_steps


def test_conversation_is_persisted_and_replayed(config, toolbox):
    store = Store(config.db_path, config.audit_path)
    session = store.new_session()

    agent = Agent(config, FakeClient([Message(content="first")]), toolbox, store)
    run(agent, "remember this", session)

    agent2 = Agent(config, client := FakeClient([Message(content="second")]), toolbox, store)
    run(agent2, "and this", session)

    replayed = [m["content"] for m in client.calls[0] if m["role"] == "user"]
    assert replayed == ["remember this", "and this"]
    store.close()


def test_tool_calls_are_audited(config, toolbox):
    store = Store(config.db_path, config.audit_path)
    session = store.new_session()
    client = FakeClient([Message(tool_calls=[tool_call("echo", text="hi")]), Message(content="ok")])
    run(Agent(config, client, toolbox, store, confirm=allow_all), "go", session)

    lines = config.audit_path.read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 1
    assert '"tool": "echo"' in lines[0]
    assert '"decision": "ran"' in lines[0]
    store.close()
