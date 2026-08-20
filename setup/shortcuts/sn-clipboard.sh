#!/data/data/com.termux/files/usr/bin/bash
#
# Home screen: act on whatever is on the clipboard.
#
# Copy some text anywhere on the phone, tap this, and the answer comes back as
# a notification and is left on the clipboard ready to paste.

set -euo pipefail

CLIP="$(termux-clipboard-get)"

if [ -z "${CLIP//[[:space:]]/}" ]; then
    termux-notification --id sn-clip --title "sn" --content "Clipboard is empty."
    exit 0
fi

termux-notification --id sn-clip --title "sn" --content "working on the clipboard…" --ongoing

PROMPT="The user copied this text and wants you to act on it. If it is a
question, answer it. If it is a message, draft a short reply. Otherwise explain
what it is in one or two sentences.

---
$CLIP"

if ANSWER="$(sn ask --quiet --new "$PROMPT" 2>&1)"; then
    printf '%s' "$ANSWER" | termux-clipboard-set
    termux-notification --id sn-clip --title "sn · copied to clipboard" --content "$ANSWER"
else
    termux-notification --id sn-clip --title "sn failed" --content "$ANSWER"
fi
