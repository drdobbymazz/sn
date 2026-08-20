#!/data/data/com.termux/files/usr/bin/bash
#
# Home screen: what have I missed, and what is coming up.
#
# A good candidate for termux-job-scheduler if you want it to run itself every
# morning — see docs/automation.md.

set -euo pipefail

termux-notification --id sn-brief --title "sn · briefing" --content "checking…" --ongoing

PROMPT="Give me a short briefing. Check my unread notifications, my calendar for
the next 24 hours, and any SMS from the last day. Summarise only what actually
needs my attention, in at most five short lines. If nothing needs attention, say
so in one line."

if ANSWER="$(sn ask --quiet --new "$PROMPT" 2>&1)"; then
    termux-notification --id sn-brief --title "sn · briefing" --content "$ANSWER"
else
    termux-notification --id sn-brief --title "sn briefing failed" --content "$ANSWER"
fi
