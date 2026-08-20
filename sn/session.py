"""Conversation persistence and the tool audit log.

Two stores, on purpose:

* SQLite holds conversations, so `sn` can be closed and reopened mid-thought.
* A JSONL audit log records every tool call and its outcome. An agent that can
  send messages from your number should leave a trail you can grep without
  opening a database.
"""

from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at TEXT NOT NULL,
    title      TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS messages (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    ts         TEXT NOT NULL,
    role       TEXT NOT NULL,
    payload    TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS messages_session ON messages(session_id, id);
"""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class Store:
    def __init__(self, db_path: Path, audit_path: Path | None = None):
        self.db_path = Path(db_path)
        self.audit_path = Path(audit_path) if audit_path else None
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA)
        self.conn.commit()

    def close(self) -> None:
        self.conn.close()

    # -- sessions ---------------------------------------------------------

    def new_session(self, title: str = "") -> int:
        cur = self.conn.execute(
            "INSERT INTO sessions (started_at, title) VALUES (?, ?)", (_now(), title)
        )
        self.conn.commit()
        return int(cur.lastrowid)

    def latest_session(self) -> int | None:
        row = self.conn.execute("SELECT id FROM sessions ORDER BY id DESC LIMIT 1").fetchone()
        return int(row["id"]) if row else None

    def resume_or_create(self) -> int:
        return self.latest_session() or self.new_session()

    def set_title(self, session_id: int, title: str) -> None:
        self.conn.execute(
            "UPDATE sessions SET title = ? WHERE id = ? AND title = ''", (title[:80], session_id)
        )
        self.conn.commit()

    def sessions(self, limit: int = 20) -> list[dict[str, Any]]:
        rows = self.conn.execute(
            """
            SELECT s.id, s.started_at, s.title, COUNT(m.id) AS messages
            FROM sessions s LEFT JOIN messages m ON m.session_id = s.id
            GROUP BY s.id ORDER BY s.id DESC LIMIT ?
            """,
            (limit,),
        ).fetchall()
        return [dict(r) for r in rows]

    # -- messages ---------------------------------------------------------

    def append(self, session_id: int, message: dict) -> None:
        self.conn.execute(
            "INSERT INTO messages (session_id, ts, role, payload) VALUES (?, ?, ?, ?)",
            (session_id, _now(), message.get("role", "?"), json.dumps(message)),
        )
        self.conn.commit()

    def extend(self, session_id: int, messages: Iterable[dict]) -> None:
        for message in messages:
            self.append(session_id, message)

    def history(self, session_id: int, max_messages: int = 40) -> list[dict]:
        """Return recent messages, oldest first, with both ends left clean.

        A tool exchange spans several messages, and replaying half of one
        confuses the model. Two ways it can be split:

        * the window opens on a tool result whose call was cut off — drop the
          orphaned results;
        * the conversation was interrupted after the model asked for a tool but
          before the result was stored — drop the unanswered call.
        """
        rows = self.conn.execute(
            "SELECT payload FROM messages WHERE session_id = ? ORDER BY id DESC LIMIT ?",
            (session_id, max_messages),
        ).fetchall()
        messages = [json.loads(r["payload"]) for r in reversed(rows)]

        while messages and messages[0].get("role") == "tool":
            messages.pop(0)
        while messages and messages[-1].get("tool_calls"):
            messages.pop()
        return messages

    def clear(self, session_id: int) -> None:
        self.conn.execute("DELETE FROM messages WHERE session_id = ?", (session_id,))
        self.conn.commit()

    # -- audit ------------------------------------------------------------

    def audit(
        self,
        *,
        session_id: int,
        tool: str,
        arguments: dict,
        decision: str,
        result: Any = None,
        error: str | None = None,
    ) -> None:
        """Record one tool call. Never raises — a failed log must not kill a run."""
        if not self.audit_path:
            return
        entry = {
            "ts": _now(),
            "session": session_id,
            "tool": tool,
            "arguments": arguments,
            "decision": decision,
        }
        if error:
            entry["error"] = error
        elif result is not None:
            summary = json.dumps(result, default=str)
            entry["result"] = summary if len(summary) <= 2000 else summary[:2000] + "…"
        try:
            self.audit_path.parent.mkdir(parents=True, exist_ok=True)
            with self.audit_path.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(entry, default=str) + "\n")
        except OSError:
            pass
