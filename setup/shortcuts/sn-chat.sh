#!/data/data/com.termux/files/usr/bin/bash
#
# Home screen: open a terminal already in conversation with sn.
#
# This one lives outside tasks/, so Termux:Widget opens a real terminal — which
# means confirmation prompts work and the agent can send messages and place
# calls.

set -euo pipefail

# Keep the CPU awake for the length of the conversation, so a long answer does
# not stall when the screen goes off.
termux-wake-lock
trap 'termux-wake-unlock' EXIT

exec sn chat
