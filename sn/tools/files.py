"""Read-only access to files on the phone, confined to configured roots.

Every path is resolved (following symlinks — `~/storage/shared` is itself one)
and checked against the roots before anything is opened, so `../..` and a
symlink pointing out of the sandbox are both refused.
"""

from __future__ import annotations

import fnmatch
from datetime import datetime
from pathlib import Path
from typing import Any

from ..config import ToolsConfig
from .base import ToolError, boolean, integer, schema, string, tool

_config = ToolsConfig()

MAX_WALK_ENTRIES = 20_000


def configure(cfg: ToolsConfig) -> None:
    global _config
    _config = cfg


def _roots() -> list[Path]:
    roots = []
    for raw in _config.file_roots:
        path = Path(raw).expanduser()
        try:
            roots.append(path.resolve())
        except OSError:
            continue
    return roots


def _resolve(path: str) -> Path:
    if not (path or "").strip():
        raise ToolError("No path given.")
    candidate = Path(path).expanduser()
    if not candidate.is_absolute():
        candidate = Path.home() / candidate
    try:
        real = candidate.resolve()
    except OSError as exc:
        raise ToolError(f"Cannot resolve {path!r}: {exc}") from exc

    roots = _roots()
    if any(real == root or root in real.parents for root in roots):
        return real

    allowed = ", ".join(str(r) for r in roots) or "(none configured)"
    raise ToolError(
        f"{path!r} is outside the directories sn may read. Allowed roots: {allowed}. "
        "Add more under [tools] file_roots in ~/.config/sn/config.toml."
    )


def _describe(path: Path) -> dict[str, Any]:
    try:
        stat = path.stat()
    except OSError as exc:
        return {"name": path.name, "error": str(exc)}
    return {
        "name": path.name,
        "path": str(path),
        "type": "dir" if path.is_dir() else "file",
        "size": stat.st_size,
        "modified": datetime.fromtimestamp(stat.st_mtime).isoformat(timespec="minutes"),
    }


@tool(
    name="files_list",
    description=(
        "List the contents of a directory on the phone. Shared storage is at "
        "~/storage/shared (Downloads, DCIM, Documents and so on live under it)."
    ),
    parameters=schema(
        {
            "path": string("Directory to list.", default="~/storage/shared"),
            "pattern": string("Optional glob to filter names, e.g. '*.pdf'."),
        }
    ),
    category="files",
)
def files_list(path: str = "~/storage/shared", pattern: str = "") -> dict[str, Any]:
    target = _resolve(path)
    if not target.is_dir():
        raise ToolError(f"{target} is not a directory.")
    try:
        entries = sorted(target.iterdir(), key=lambda p: (p.is_file(), p.name.casefold()))
    except PermissionError as exc:
        raise ToolError(
            f"Android denied access to {target}. If this is shared storage, run "
            "termux-setup-storage once and grant the permission."
        ) from exc

    if pattern:
        entries = [e for e in entries if fnmatch.fnmatch(e.name, pattern)]
    total = len(entries)
    entries = entries[: _config.max_rows]
    return {
        "path": str(target),
        "count": total,
        "truncated": total > len(entries),
        "entries": [_describe(e) for e in entries],
    }


@tool(
    name="files_find",
    description=(
        "Search for files by name below a directory. Use this when the user refers to "
        "a file but does not know where it is."
    ),
    parameters=schema(
        {
            "pattern": string("Glob to match against file names, e.g. '*invoice*.pdf'."),
            "path": string("Directory to search below.", default="~/storage/shared"),
            "limit": integer("Maximum matches to return (default 25).", default=25),
        },
        required=["pattern"],
    ),
    category="files",
)
def files_find(pattern: str, path: str = "~/storage/shared", limit: int = 25) -> dict[str, Any]:
    root = _resolve(path)
    if not root.is_dir():
        raise ToolError(f"{root} is not a directory.")
    limit = max(1, min(int(limit), _config.max_rows))

    needle = pattern if any(c in pattern for c in "*?[") else f"*{pattern}*"
    matches: list[Path] = []
    scanned = 0
    truncated = False
    for entry in root.rglob("*"):
        scanned += 1
        if scanned > MAX_WALK_ENTRIES:
            truncated = True
            break
        try:
            if entry.is_file() and fnmatch.fnmatch(entry.name.casefold(), needle.casefold()):
                matches.append(entry)
                if len(matches) >= limit:
                    truncated = True
                    break
        except OSError:
            continue

    return {
        "pattern": needle,
        "searched": str(root),
        "count": len(matches),
        "truncated": truncated,
        "matches": [_describe(m) for m in matches],
    }


@tool(
    name="files_read",
    description=(
        "Read a text file from the phone. Returns the beginning of the file if it is "
        "large. Binary files are reported rather than dumped."
    ),
    parameters=schema(
        {
            "path": string("File to read."),
            "max_bytes": integer("How much to read (default from config)."),
            "tail": boolean("Read the end of the file instead of the start.", default=False),
        },
        required=["path"],
    ),
    category="files",
)
def files_read(path: str, max_bytes: int = 0, tail: bool = False) -> dict[str, Any]:
    target = _resolve(path)
    if target.is_dir():
        raise ToolError(f"{target} is a directory — use files_list.")
    if not target.exists():
        raise ToolError(f"No such file: {target}")

    cap = int(max_bytes) if max_bytes else _config.max_file_bytes
    cap = max(1, min(cap, _config.max_file_bytes))
    size = target.stat().st_size

    try:
        with target.open("rb") as fh:
            if tail and size > cap:
                fh.seek(size - cap)
            raw = fh.read(cap)
    except PermissionError as exc:
        raise ToolError(f"Android denied access to {target}.") from exc

    if b"\x00" in raw[:4096]:
        return {
            "path": str(target),
            "binary": True,
            "size": size,
            "note": "Binary file — not decoded. Ask the user what to do with it.",
        }

    return {
        "path": str(target),
        "size": size,
        "truncated": size > len(raw),
        "read_from": "end" if tail else "start",
        "text": raw.decode("utf-8", errors="replace"),
    }
