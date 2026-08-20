"""Tool packages.

Importing a tool module is what registers its tools, so `load_all` is the list
of everything sn can do on the phone. Add a module here to add a capability.
"""

from __future__ import annotations

from ..config import ToolsConfig
from .base import REGISTRY, Tool, Toolbox, ToolError

_MODULES = ("messaging", "device", "calendar", "files", "sensing")

_loaded = False


def load_all() -> None:
    """Import every tool module once, registering its tools."""
    global _loaded
    if _loaded:
        return
    from importlib import import_module  # noqa: PLC0415

    for name in _MODULES:
        import_module(f"{__name__}.{name}")
    _loaded = True


def configure(cfg: ToolsConfig) -> None:
    """Push config (row limits, file roots) into the tool modules."""
    load_all()
    from importlib import import_module  # noqa: PLC0415

    for name in _MODULES:
        module = import_module(f"{__name__}.{name}")
        if hasattr(module, "configure"):
            module.configure(cfg)


__all__ = ["REGISTRY", "Tool", "ToolError", "Toolbox", "configure", "load_all"]
