from __future__ import annotations

from sn.session import Store


def test_messages_come_back_in_order(tmp_path):
    store = Store(tmp_path / "sn.db")
    session = store.new_session()
    store.extend(
        session,
        [
            {"role": "user", "content": "one"},
            {"role": "assistant", "content": "two"},
            {"role": "user", "content": "three"},
        ],
    )
    assert [m["content"] for m in store.history(session)] == ["one", "two", "three"]
    store.close()


def test_history_never_starts_mid_tool_exchange(tmp_path):
    """A tool result without its call confuses the model, so trim past it."""
    store = Store(tmp_path / "sn.db")
    session = store.new_session()
    store.extend(
        session,
        [
            {"role": "user", "content": "old"},
            {"role": "assistant", "tool_calls": [{"function": {"name": "x"}}], "content": ""},
            {"role": "tool", "tool_name": "x", "content": "{}"},
            {"role": "assistant", "content": "answer"},
        ],
    )
    window = store.history(session, max_messages=2)
    assert [m["role"] for m in window] == ["assistant"]
    store.close()


def test_history_drops_a_tool_call_that_was_never_answered(tmp_path):
    """Ctrl-C between the model asking for a tool and the result being stored."""
    store = Store(tmp_path / "sn.db")
    session = store.new_session()
    store.extend(
        session,
        [
            {"role": "user", "content": "do the thing"},
            {"role": "assistant", "tool_calls": [{"function": {"name": "x"}}], "content": ""},
        ],
    )
    assert [m["role"] for m in store.history(session)] == ["user"]
    store.close()


def test_sessions_are_listed_newest_first(tmp_path):
    store = Store(tmp_path / "sn.db")
    first = store.new_session("older")
    second = store.new_session("newer")
    store.append(first, {"role": "user", "content": "hi"})

    rows = store.sessions()
    assert [r["id"] for r in rows] == [second, first]
    assert rows[1]["messages"] == 1
    store.close()


def test_a_title_is_set_once_and_then_left_alone(tmp_path):
    store = Store(tmp_path / "sn.db")
    session = store.new_session()
    store.set_title(session, "first question")
    store.set_title(session, "second question")

    assert store.sessions()[0]["title"] == "first question"
    store.close()


def test_resume_returns_the_existing_session(tmp_path):
    store = Store(tmp_path / "sn.db")
    session = store.new_session()
    assert store.resume_or_create() == session
    store.close()


def test_audit_records_denials_too(tmp_path):
    audit = tmp_path / "audit.jsonl"
    store = Store(tmp_path / "sn.db", audit)
    session = store.new_session()
    store.audit(session_id=session, tool="sms_send", arguments={"to": "x"}, decision="denied")

    line = audit.read_text(encoding="utf-8").strip()
    assert '"decision": "denied"' in line
    assert '"tool": "sms_send"' in line
    store.close()


def test_a_huge_tool_result_is_clipped_in_the_audit_log(tmp_path):
    audit = tmp_path / "audit.jsonl"
    store = Store(tmp_path / "sn.db", audit)
    session = store.new_session()
    store.audit(
        session_id=session,
        tool="files_read",
        arguments={},
        decision="ran",
        result={"text": "x" * 50_000},
    )
    assert len(audit.read_text(encoding="utf-8")) < 3000
    store.close()
