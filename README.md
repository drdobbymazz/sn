# sn

A personal agent that runs *on* a Samsung Galaxy S23 Ultra, not in a data centre.

The model lives on your laptop under Ollama and is reached over Tailscale. The
agent itself — the loop, the tools, the conversation history — runs on the phone
inside Termux, so it can read your messages, check your calendar, look through
your files and take a photo. Nothing about your phone leaves the tailnet.

```
  phone (Termux)                          laptop
  ┌────────────────────────┐              ┌──────────────┐
  │ sn                     │   Tailscale  │              │
  │  agent loop  ──────────┼──────────────┤   Ollama     │
  │  tools ── termux-api   │   :11434     │   qwen3:8b   │
  │  sqlite history        │              │              │
  └────────────────────────┘              └──────────────┘
```

## Install

On the laptop, make Ollama listen on the tailnet rather than only on localhost,
and pull a model that can call tools:

```sh
ollama pull qwen3:8b
OLLAMA_HOST=0.0.0.0:11434 ollama serve
```

On the phone, install [Termux](https://f-droid.org/packages/com.termux/),
**Termux:API** and **Termux:Widget** from F-Droid — all three from the same
source, or Android will not let them talk to each other. Then:

```sh
pkg install git
git clone <this repo> ~/sn
cd ~/sn && ./setup/install.sh
```

The installer asks for your laptop's Tailscale name, installs the home screen
shortcuts, and finishes by running `sn doctor`. Grant the Android permission
prompts as they appear.

```sh
sn doctor     # check the phone, the tailnet and the model
sn            # start talking
```

## Using it

```sh
sn                          # conversation, resumes where you left off
sn ask "when am I free on thursday?"
sn ask -q "battery?" --notify   # answer as a notification, for scripts
sn tools                    # what it can do
sn sessions                 # recent conversations
```

Inside a conversation: `/new` for a fresh thread, `/tools`, `/quit`.

### From the home screen

The installer sets up four Termux:Widget shortcuts. Long-press the home screen
→ Widgets → Termux:Widget to place them.

| Shortcut | What it does |
| --- | --- |
| `sn-chat` | Opens a terminal already in conversation. The only one that can send messages or place calls, because it can ask you first. |
| `sn-ask` | Type a question in a dialog, get the answer as a notification. |
| `sn-clipboard` | Acts on whatever you copied, and leaves the reply on the clipboard. |
| `sn-briefing` | Notifications, calendar and messages since yesterday, in five lines. |

## What it can do

| | Tools |
| --- | --- |
| **messaging** | `sms_list` `sms_send` `contacts_find` `call_log` `call_place` |
| **device** | `battery_status` `network_status` `clipboard_get` `clipboard_set` `notifications_list` `notification_send` `notification_remove` `vibrate` |
| **calendar** | `calendar_list_events` `calendar_create_event` |
| **files** | `files_list` `files_find` `files_read` |
| **sensing** | `location_get` `camera_info` `camera_photo` |

## Safety

The agent can text people from your number, so a few things are deliberate:

- **Consequential tools ask first.** `sms_send`, `call_place`, `camera_photo`,
  `calendar_create_event` and `notification_send` show you the exact arguments
  and wait for a `y`. The list is `tools.confirm` in the config.
- **Declining is not an error.** The model is told you said no and answers
  around it, rather than retrying.
- **No terminal means no confirmation means no.** Widget-launched and piped runs
  cannot prompt, so anything gated is refused automatically. Use `sn` in a
  terminal, or `--yes` if you really mean it.
- **File access is confined** to `tools.file_roots`. Paths that escape through
  `..` or a symlink are refused, not silently followed.
- **Everything is logged.** Every tool call, its arguments and its outcome go to
  `~/.local/state/sn/audit.jsonl`. `tail -f` it while you get used to the thing.
- **Calendar writes go through the calendar app**, prefilled, for you to save.
  Nothing lands in your calendar unseen.
- **There is no shell tool.** The agent cannot run arbitrary commands on the
  phone. Each capability is a named, bounded tool.

## Configuration

`~/.config/sn/config.toml`, documented in
[`setup/config.example.toml`](setup/config.example.toml). The one value you must
set is `ollama.host`. `SN_OLLAMA_HOST`, `SN_MODEL` and `SN_CONFIG` override it
for a single run.

Any tool-capable model works. `qwen3:8b` is a good default; `qwen3:14b` has
noticeably better judgement about which tool to reach for if the laptop can hold
it. A model without tool support can only chat — `sn doctor` will tell you.

## Documentation

- [docs/permissions.md](docs/permissions.md) — the Android permissions, including
  the calendar one that needs ADB
- [docs/automation.md](docs/automation.md) — scheduled briefings, wake locks,
  running on boot
- [docs/architecture.md](docs/architecture.md) — how it fits together, and how to
  add a tool

## Development

```sh
pip install -e ".[dev]"
pytest
```

The tests run off-device: `SN_TERMUX_STUB` points the termux-api layer at a
directory of canned JSON, so the tool code is exercised on a laptop.
