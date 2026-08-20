"""Camera and location."""

from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import Any

from .base import ToolError, integer, schema, string, tool
from .termux import run, run_json

# GPS can sit there for a while before it gets a fix.
LOCATION_TIMEOUT = 75.0
CAMERA_TIMEOUT = 60.0


def _photo_dir() -> Path:
    shared = Path.home() / "storage" / "shared"
    base = shared / "Pictures" / "sn" if shared.is_dir() else Path.home() / "sn-photos"
    base.mkdir(parents=True, exist_ok=True)
    return base


@tool(
    name="location_get",
    description=(
        "Get the phone's current location as latitude and longitude. Prefer the "
        "'last' request first — it is instant and usually accurate enough. Use 'once' "
        "only when a fresh fix genuinely matters, as it can take up to a minute."
    ),
    parameters=schema(
        {
            "provider": string(
                "Which location source to use.",
                enum=["gps", "network", "passive"],
                default="gps",
            ),
            "request": string(
                "'last' returns the last known fix immediately; 'once' waits for a new one.",
                enum=["last", "once"],
                default="last",
            ),
        }
    ),
    category="sensing",
)
def location_get(provider: str = "gps", request: str = "last") -> dict[str, Any]:
    if provider not in {"gps", "network", "passive"}:
        raise ToolError(f"Unknown provider {provider!r}.")
    if request not in {"last", "once"}:
        raise ToolError(f"Unknown request type {request!r}.")

    result = run_json(
        ["termux-location", "-p", provider, "-r", request],
        default={},
        timeout=LOCATION_TIMEOUT,
    )
    if not result:
        raise ToolError(
            f"No {provider} fix available. Try provider='network', or request='once' "
            "if the phone has not moved recently and has no cached fix."
        )
    if isinstance(result, dict) and result.get("API_ERROR"):
        raise ToolError(f"Location unavailable: {result['API_ERROR']}")
    return result


@tool(
    name="camera_info",
    description="List the phone's cameras and their ids, for use with camera_photo.",
    category="sensing",
)
def camera_info() -> dict[str, Any]:
    cameras = run_json(["termux-camera-info"], default=[]) or []
    summary = [
        {
            "id": c.get("id"),
            "facing": c.get("facing"),
            "resolutions": (c.get("jpeg_output_sizes") or [])[:1],
        }
        for c in cameras
        if isinstance(c, dict)
    ]
    return {"count": len(summary), "cameras": summary}


@tool(
    name="camera_photo",
    description=(
        "Take a photo with the phone's camera and save it. Returns the file path. "
        "The user confirms before the shutter fires."
    ),
    parameters=schema(
        {
            "camera": integer("Camera id: 0 is the rear camera, 1 the front.", default=0),
            "name": string("Optional file name. A timestamped name is used otherwise."),
        }
    ),
    category="sensing",
    consequential=True,
)
def camera_photo(camera: int = 0, name: str = "") -> dict[str, Any]:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    filename = (name or f"sn-{stamp}.jpg").strip()
    if "/" in filename or filename.startswith("."):
        raise ToolError("Give a plain file name, without a path.")
    if not filename.lower().endswith(".jpg"):
        filename += ".jpg"

    target = _photo_dir() / filename
    run(["termux-camera-photo", "-c", str(int(camera)), str(target)], timeout=CAMERA_TIMEOUT)

    if not target.exists():
        raise ToolError(
            "The camera reported success but no file was written. Another app may be "
            "holding the camera — close it and try again."
        )
    return {"saved": str(target), "bytes": target.stat().st_size, "camera": int(camera)}
