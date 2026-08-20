from __future__ import annotations

from pathlib import Path

import pytest

from conftest import write_stub
from sn.tools import Toolbox
from sn.tools.base import ToolError
from sn.tools import calendar as calendar_tools
from sn.tools import files as file_tools
from sn.tools import messaging


# -- the registry ---------------------------------------------------------


def test_every_tool_has_a_usable_schema(config):
    box = Toolbox.build()
    assert box.tools, "no tools registered"

    for name, tool in box.tools.items():
        spec = tool.spec()["function"]
        assert spec["name"] == name
        assert spec["description"], f"{name} has no description"
        assert spec["parameters"]["type"] == "object"
        for arg, definition in spec["parameters"]["properties"].items():
            assert definition.get("description"), f"{name}.{arg} has no description"
        for required in spec["parameters"]["required"]:
            assert required in spec["parameters"]["properties"]


def test_disabled_tools_are_not_offered(config):
    box = Toolbox.build(disabled=("sms_send", "call_place"))
    assert "sms_send" not in box.tools
    assert "sms_list" in box.tools


def test_tools_that_reach_other_people_are_consequential(config):
    box = Toolbox.build()
    for name in ("sms_send", "call_place", "camera_photo"):
        assert box.tools[name].consequential, f"{name} should require confirmation"


# -- files ----------------------------------------------------------------


@pytest.fixture
def sandbox(config, tmp_path: Path) -> Path:
    root = tmp_path / "sandbox"
    (root / "docs").mkdir()
    (root / "docs" / "notes.txt").write_text("hello from the phone", encoding="utf-8")
    (root / "photo.bin").write_bytes(b"\x89PNG\x00\x00binary")
    (tmp_path / "secret.txt").write_text("not for the agent", encoding="utf-8")
    return root


def test_reads_a_file_inside_the_sandbox(sandbox):
    result = file_tools.files_read(path=str(sandbox / "docs" / "notes.txt"))
    assert result["text"] == "hello from the phone"
    assert result["truncated"] is False


def test_refuses_a_path_outside_the_sandbox(sandbox, tmp_path):
    with pytest.raises(ToolError, match="outside the directories"):
        file_tools.files_read(path=str(tmp_path / "secret.txt"))


def test_refuses_to_climb_out_with_dotdot(sandbox):
    with pytest.raises(ToolError, match="outside the directories"):
        file_tools.files_read(path=str(sandbox / "docs" / ".." / ".." / "secret.txt"))


def test_refuses_a_symlink_pointing_out_of_the_sandbox(sandbox, tmp_path):
    (sandbox / "escape.txt").symlink_to(tmp_path / "secret.txt")
    with pytest.raises(ToolError, match="outside the directories"):
        file_tools.files_read(path=str(sandbox / "escape.txt"))


def test_binary_files_are_reported_not_dumped(sandbox):
    result = file_tools.files_read(path=str(sandbox / "photo.bin"))
    assert result["binary"] is True
    assert "text" not in result


def test_large_files_are_truncated(sandbox):
    big = sandbox / "big.txt"
    big.write_text("x" * 5000, encoding="utf-8")
    result = file_tools.files_read(path=str(big), max_bytes=100)
    assert result["truncated"] is True
    assert len(result["text"]) == 100


def test_tail_reads_the_end(sandbox):
    log = sandbox / "log.txt"
    log.write_text("start" + "." * 1000 + "end", encoding="utf-8")
    result = file_tools.files_read(path=str(log), max_bytes=10, tail=True)
    assert result["text"].endswith("end")


def test_find_matches_a_bare_word_as_a_substring(sandbox):
    result = file_tools.files_find(pattern="notes", path=str(sandbox))
    assert [m["name"] for m in result["matches"]] == ["notes.txt"]


def test_listing_respects_the_row_cap(sandbox, config):
    for i in range(20):
        (sandbox / f"f{i}.txt").touch()
    result = file_tools.files_list(path=str(sandbox))
    assert len(result["entries"]) == config.tools.max_rows
    assert result["truncated"] is True


# -- messaging ------------------------------------------------------------


CONTACTS = [
    {"name": "Ada Lovelace", "number": "+44 7700 900111"},
    {"name": "Alan Turing", "number": "+44 7700 900222"},
    {"name": "Ada Byron", "number": "+44 7700 900333"},
    {"name": "Work", "number": "07700 900444"},
    {"name": "Work", "number": "+447700900444"},
]


def test_a_plain_number_passes_through(config, stub):
    write_stub(stub, "termux-contact-list", CONTACTS)
    assert messaging.resolve_recipient("+44 7700 900999") == "+44 7700 900999"


def test_a_unique_name_resolves_to_a_number(config, stub):
    write_stub(stub, "termux-contact-list", CONTACTS)
    assert messaging.resolve_recipient("Alan") == "+44 7700 900222"


def test_an_ambiguous_name_is_refused_rather_than_guessed(config, stub):
    write_stub(stub, "termux-contact-list", CONTACTS)
    with pytest.raises(ToolError, match="ambiguous"):
        messaging.resolve_recipient("Ada")


def test_one_person_listed_twice_is_not_ambiguous(config, stub):
    """Same human, two spellings of the same number — that is not a choice."""
    write_stub(stub, "termux-contact-list", CONTACTS)
    assert messaging.resolve_recipient("Work").endswith("900444")


def test_an_exact_name_beats_a_partial_one(config, stub):
    write_stub(stub, "termux-contact-list", CONTACTS)
    assert messaging.resolve_recipient("Ada Byron") == "+44 7700 900333"


def test_an_unknown_name_is_refused(config, stub):
    write_stub(stub, "termux-contact-list", CONTACTS)
    with pytest.raises(ToolError, match="No contact matching"):
        messaging.resolve_recipient("Grace Hopper")


def test_refuses_to_send_an_empty_message(config, stub):
    write_stub(stub, "termux-contact-list", CONTACTS)
    with pytest.raises(ToolError, match="empty message"):
        messaging.sms_send(to="Alan", message="   ")


def test_sms_list_filters_by_contact(config, stub):
    write_stub(stub, "termux-contact-list", CONTACTS)
    write_stub(
        stub,
        "termux-sms-list",
        [
            {"number": "+447700900222", "sender": "Alan Turing", "body": "on my way"},
            {"number": "+447700900111", "sender": "Ada Lovelace", "body": "note the sequence"},
        ],
    )
    result = messaging.sms_list(contact="Alan")
    assert [m["body"] for m in result["messages"]] == ["on my way"]


def test_digits_normalises_number_formats():
    assert messaging._digits("+44 7700 900111") == messaging._digits("07700900111")
    assert messaging._digits("(555) 123-4567") == "5551234567"[-9:]


# -- calendar -------------------------------------------------------------


def test_parses_content_query_rows():
    raw = (
        "Row: 0 title=Standup, begin=1755766800000, end=1755768600000, "
        "eventLocation=NULL, allDay=0, description=NULL\n"
        "Row: 1 title=Lunch with Ada, begin=1755777600000, end=1755781200000, "
        "eventLocation=Cafe, allDay=0, description=NULL\n"
    )
    rows = calendar_tools._parse_rows(raw)
    assert [r["title"] for r in rows] == ["Standup", "Lunch with Ada"]
    assert rows[1]["eventLocation"] == "Cafe"


def test_a_comma_inside_a_title_survives_parsing():
    raw = "Row: 0 title=Dinner, then drinks, begin=1755777600000, allDay=0\n"
    rows = calendar_tools._parse_rows(raw)
    assert rows[0]["title"] == "Dinner, then drinks"


def test_null_fields_are_dropped_from_the_result():
    row = {"title": "Standup", "begin": "1755766800000", "eventLocation": "NULL"}
    event = calendar_tools._humanize(row)
    assert "location" not in event
    assert event["title"] == "Standup"


def test_relative_dates_are_understood():
    from datetime import datetime, timedelta

    today = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    assert calendar_tools._parse_when("today", fallback=datetime.now()) == today
    assert calendar_tools._parse_when("tomorrow", fallback=datetime.now()) == today + timedelta(days=1)
    assert calendar_tools._parse_when("2026-08-21T14:30", fallback=datetime.now()).hour == 14


def test_an_unparseable_date_is_refused():
    from datetime import datetime

    with pytest.raises(ToolError, match="Could not read"):
        calendar_tools._parse_when("next thursday-ish", fallback=datetime.now())
