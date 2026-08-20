#!/data/data/com.termux/files/usr/bin/bash
#
# Home screen: tap, type a question, get the answer as a notification.
#
# Runs without a terminal, so tools that need confirmation (sending an SMS,
# placing a call, taking a photo) are declined automatically. Use `sn` in a
# terminal for those.

set -euo pipefail

PROMPT="$(termux-dialog text -t "sn" -i "ask me something" 2>/dev/null \
    | python -c 'import json,sys; d=json.load(sys.stdin); print(d.get("text","") if d.get("code")==-1 else "")')"

[ -z "$PROMPT" ] && exit 0

termux-notification --id sn-answer --title "sn" --content "thinking…" --ongoing

if ANSWER="$(sn ask --quiet "$PROMPT" 2>&1)"; then
    termux-notification --id sn-answer --title "sn" --content "$ANSWER"
else
    termux-notification --id sn-answer --title "sn failed" --content "$ANSWER"
fi
