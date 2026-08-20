# sn — Android app

The native build. Same idea as the Termux version — the model runs on your
laptop under Ollama over Tailscale, the tools run on the phone — but with the
things a terminal cannot reach: the notification stream, a real chat UI, runtime
permissions, a home screen widget, and background work that survives the screen
going off.

## Building

You need Android Studio (Ladybug or newer) or a local Android SDK. There is no
CI build for this: `dl.google.com` hosts the SDK and AndroidX, so the project
must be built somewhere with ordinary access to it.

```sh
cd android
./gradlew :core:test        # the agent core, no Android SDK needed
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug      # straight onto a connected phone
```

Or open `android/` in Android Studio and press run.

To install without a cable, put the APK on the phone and open it — Android will
ask you to allow installs from that source. The debug build signs with the
standard debug key, which is fine for a personal sideload; a release build needs
your own signing config.

> **This app cannot go on Google Play**, and that is deliberate. `READ_SMS` and
> `SEND_SMS` are restricted permissions that Play only grants to default SMS
> handlers. Sideloading is the only route for an app that reads and sends your
> messages on your behalf.

## Layout

```
core/                   pure Kotlin, no Android — built and tested on any JVM
  Agent.kt              the loop: ask, run tools, repeat
  Ollama.kt             streaming NDJSON client, ChatBackend interface
  Tool.kt               registry, JSON schema DSL, argument coercion
  Protocol.kt           the wire format
  History.kt            trimming a replay window without splitting a tool call
  HostAddress.kt        turning "laptop" into "http://laptop:11434"

app/                    everything that touches Android
  tools/                the 24 device tools
  service/              notification listener, foreground service, triage worker
  ui/                   Compose chat, settings, the confirmation dialog
  data/                 Room database, DataStore settings
  widget/               Glance home screen widget
```

The split is the point. `core` has no Android imports, so the agent loop, the
protocol handling and the argument coercion are all unit tested on a plain JVM —
67 tests, including the full loop against a real HTTP server. `app` is thin
adapters over real Android APIs, which is the part that needs a device to
verify.

## Setup on the phone

1. Install the APK.
2. Open sn → Settings → **Ollama host**: your laptop's Tailscale name, e.g.
   `laptop.tail1234.ts.net`. The port is added for you. Tap **Test connection** —
   it checks the host is reachable, the model is installed, *and* that the model
   supports tool calling, and tells you which of those failed.
3. Grant permissions from the same screen. **Notification access** is a separate
   grant with its own settings page; the button takes you there.
4. On the laptop: `OLLAMA_HOST=0.0.0.0:11434 ollama serve`

Unlike the Termux build, the calendar needs no ADB grant here — a real app can
simply ask for `READ_CALENDAR` at runtime.

## What it can do

| | Tools |
| --- | --- |
| **messaging** | `contacts_find` `sms_list` `sms_send` `call_log` `call_place` |
| **notifications** | `notifications_list` `notification_send` `notification_dismiss` |
| **calendar** | `calendar_list_events` `calendar_create_event` |
| **files** | `files_list` `files_find` `files_read` |
| **device** | `battery_status` `network_status` `clipboard_get` `clipboard_set` `vibrate` `app_launch` `app_list` `alarm_set` |
| **sensing** | `location_get` `camera_info` `camera_photo` |

Every tool can be switched off individually in Settings, and any tool can be put
behind the confirmation prompt.

## Notifications

A `NotificationListenerService` records every notification into a local Room
database. That is what makes "what did I miss" answerable properly — the agent
reads a real backlog with timestamps and app names, not just whatever happens to
be on screen right now.

Nothing is uploaded. Notifications sit in the app's own database and are only
sent to your laptop when you ask a question that needs them, or during the
proactive pass if you enable it.

### Proactive alerts

Off by default. When enabled, a WorkManager job periodically shows the model the
notifications that arrived since the last pass and asks a single question: does
any of this need attention now? If yes, you get one notification summarising it.
If no — which is most of the time — nothing happens.

It is deliberately boxed in:

- **No tools.** Every tool is disabled for the pass, so a model that decides to
  reply to someone at 3am cannot; there is nothing to reply with.
- **The confirmer refuses everything**, as a second independent stop.
- **One step**, so it cannot loop.
- **Quiet hours** are respected, and the laptop being asleep is treated as a
  normal no-op rather than an error worth waking you for.

It reads and it alerts. It never sends anything to anyone.

## Safety

The app can text people from your number, so:

- **Consequential tools confirm first.** `sms_send`, `call_place`,
  `camera_photo`, `calendar_create_event` and `alarm_set` show the exact
  arguments in a dialog and wait. The dialog shows the message text in full,
  unabbreviated — the whole point is seeing what will be sent before it is.
- **Declining is a normal outcome.** The model is told you said no and answers
  around it rather than retrying.
- **Every tool call is audited** to a local table: arguments, decision, outcome.
- **File reads are confined** to shared storage, with paths canonicalised first,
  so `..` and symlinks cannot walk out.
- **No shell tool, and no accessibility service.** The agent cannot run arbitrary
  commands or drive other apps' interfaces. Each capability is a named, bounded
  operation — which is what makes the confirmation list mean anything.

## Cleartext HTTP

Ollama over Tailscale is plain HTTP on port 11434, and Android has blocked
cleartext since Android 9. `res/xml/network_security_config.xml` re-enables it,
which is required or the app cannot reach the model at all. The tailnet is
already encrypted end to end by WireGuard, so this is not the exposure it would
normally be — but it does apply to any host, since the Ollama address is
configurable.

## Adding a tool

Subclass `BaseTool` in `app/tools/`, then add one line to `buildToolRegistry`:

```kotlin
class TorchTool(private val context: Context) : BaseTool(
    name = "torch_set",
    description = "Turn the phone's flashlight on or off.",
    parameters = schema { boolean("on", "True for on.", required = true) },
    category = "device",
) {
    override suspend fun call(arguments: JsonObject): JsonElement {
        // throw ToolException for expected failures — the message goes to the model
        return ok("torch" to if (arguments.boolOr("on", false)) "on" else "off")
    }
}
```

The description is the prompt: it is the only thing telling the model when to
reach for this. Say what it is *for*, not just what it does. Set
`consequential = true` if it touches the world outside the phone.
