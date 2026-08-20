from __future__ import annotations

import pytest

from sn.config import ConfigError, load


def test_defaults_apply_when_there_is_no_config_file(tmp_path):
    cfg = load(tmp_path / "missing.toml")
    assert cfg.ollama.model
    assert cfg.source is None
    assert "sms_send" in cfg.tools.confirm


def test_values_are_read_from_the_file(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text(
        """
        [ollama]
        host = "http://laptop.tail1234.ts.net:11434/"
        model = "qwen3:14b"

        [agent]
        max_steps = 3

        [tools]
        confirm = ["sms_send"]
        disabled = ["camera_photo"]
        """,
        encoding="utf-8",
    )
    cfg = load(path)

    assert cfg.ollama.host == "http://laptop.tail1234.ts.net:11434"  # trailing slash trimmed
    assert cfg.ollama.model == "qwen3:14b"
    assert cfg.agent.max_steps == 3
    assert cfg.tools.confirm == ("sms_send",)
    assert cfg.tools.disabled == ("camera_photo",)
    assert cfg.source == path


def test_environment_overrides_the_file(tmp_path, monkeypatch):
    path = tmp_path / "config.toml"
    path.write_text('[ollama]\nhost = "http://from-file:11434"\n', encoding="utf-8")
    monkeypatch.setenv("SN_OLLAMA_HOST", "http://from-env:11434")
    monkeypatch.setenv("SN_MODEL", "llama3.1:8b")

    cfg = load(path)
    assert cfg.ollama.host == "http://from-env:11434"
    assert cfg.ollama.model == "llama3.1:8b"


def test_a_broken_config_is_an_error_not_a_silent_default(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text("[ollama\nhost = ", encoding="utf-8")
    with pytest.raises(ConfigError):
        load(path)


def test_a_section_of_the_wrong_shape_is_rejected(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text('ollama = "http://nope"\n', encoding="utf-8")
    with pytest.raises(ConfigError, match="must be a table"):
        load(path)


def test_state_paths_hang_off_the_state_dir(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text(f'state_dir = "{tmp_path / "state"}"\n', encoding="utf-8")
    cfg = load(path)
    assert cfg.db_path == tmp_path / "state" / "sn.db"
    assert cfg.audit_path == tmp_path / "state" / "audit.jsonl"
