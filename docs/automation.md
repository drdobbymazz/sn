# Automation

## A briefing every morning

`termux-job-scheduler` hands the job to Android's scheduler, which survives
reboots and battery optimisation better than cron does:

```sh
termux-job-scheduler \
    --script ~/.shortcuts/tasks/sn-briefing.sh \
    --period-ms 86400000 \
    --network unmetered \
    --persisted true
```

Android treats the period as "roughly", not "exactly" — it batches jobs to save
power, so expect the briefing within an hour or so of the same time each day.
List and cancel jobs with:

```sh
termux-job-scheduler --pending
termux-job-scheduler --cancel-all
```

For an exact time, use cron instead:

```sh
pkg install cronie termux-services
sv-enable crond
crontab -e
# 30 7 * * *  ~/.shortcuts/tasks/sn-briefing.sh
```

Cron only runs while Termux is alive, so pair it with `termux-wake-lock` and an
unrestricted battery setting.

## Running when the phone boots

Install **Termux:Boot** from F-Droid, open it once, then:

```sh
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/sn.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
EOF
chmod +x ~/.termux/boot/sn.sh
```

Anything in `~/.termux/boot` runs at startup. Keep it to a wake lock and any
scheduled jobs — there is no long-running daemon to start, since `sn` runs only
while you are talking to it.

## Scripting the agent

`sn ask -q` prints only the final answer, which makes it usable from a pipe:

```sh
sn ask -q "summarise this" < notes.txt
termux-clipboard-get | sn ask -q "reply to this in one line"
sn ask -q --notify "anything urgent in my messages?"
```

Piped runs have no terminal, so confirmation-gated tools are declined
automatically. If a script genuinely needs to send a message, pass `--yes` and
be deliberate about it:

```sh
sn ask --yes "text Ada that I am running ten minutes late"
```

Consider narrowing what an unattended run can reach at all:

```sh
SN_CONFIG=~/.config/sn/unattended.toml sn ask -q --yes "..."
```

with `disabled = ["sms_send", "call_place", "camera_photo"]` in that file.

## Tasker

Tasker can call the shortcuts directly through the *Termux* action (Termux:Tasker
must be installed, and the script must live in `~/.termux/tasker/`):

```sh
mkdir -p ~/.termux/tasker
ln -s ~/.shortcuts/tasks/sn-briefing.sh ~/.termux/tasker/sn-briefing.sh
```

Useful triggers: arriving home, plugging in a charger, or a specific
notification arriving — each one running `sn ask -q` with a prompt shaped for
that moment.

## Watching what it does

```sh
tail -f ~/.local/state/sn/audit.jsonl
```

Every tool call, its arguments, whether it ran or was declined, and what it
returned. Worth leaving open in a second Termux session for the first week.
