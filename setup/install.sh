#!/data/data/com.termux/files/usr/bin/bash
#
# Set sn up on the phone. Safe to re-run — it will not overwrite a config you
# have already edited.
#
#   git clone <this repo> ~/sn && cd ~/sn && ./setup/install.sh

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/sn"
CONFIG="$CONFIG_DIR/config.toml"
SHORTCUTS="$HOME/.shortcuts"

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
note() { printf '    %s\n' "$*"; }
warn() { printf '\033[33m    %s\033[0m\n' "$*"; }

if [ -z "${PREFIX:-}" ] || [ "${PREFIX#*com.termux}" = "$PREFIX" ]; then
    warn "This does not look like Termux. Run it on the phone."
    exit 1
fi

say "Installing packages"
pkg update -y
pkg install -y python python-pip termux-api git

say "Setting up shared storage"
if [ -d "$HOME/storage" ]; then
    note "already done"
else
    note "Accept the Android permission prompt that is about to appear."
    termux-setup-storage
    sleep 2
fi

say "Installing sn"
# Never upgrade pip here. Termux ships pip through the python-pip package and
# refuses to let pip replace itself:
#   ERROR: Installing pip is forbidden, this will break the python-pip package
# Use `pkg upgrade python-pip` if pip itself is ever genuinely out of date.
if ! pip install -e "$REPO"; then
    warn "pip could not install sn. Things worth trying, in order:"
    warn "  pkg upgrade python-pip"
    warn "  pip install -e $REPO"
    exit 1
fi

say "Configuration"
mkdir -p "$CONFIG_DIR"
if [ -f "$CONFIG" ]; then
    note "keeping your existing $CONFIG"
else
    cp "$REPO/setup/config.example.toml" "$CONFIG"
    note "wrote $CONFIG"

    printf '\n    Your laptop'\''s Tailscale name (e.g. laptop.tail1234.ts.net)\n'
    printf '    Leave blank to edit the file yourself later.\n'
    printf '    > '
    read -r TS_HOST || TS_HOST=""
    if [ -n "$TS_HOST" ]; then
        TS_HOST="${TS_HOST#http://}"; TS_HOST="${TS_HOST#https://}"
        TS_HOST="${TS_HOST%/}"
        case "$TS_HOST" in
            *:*) ;;                       # port already supplied
            *) TS_HOST="$TS_HOST:11434" ;;
        esac
        python - "$CONFIG" "http://$TS_HOST" <<'PY'
import re, sys
path, host = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
text = re.sub(r'^host = ".*"$', f'host = "{host}"', text, count=1, flags=re.M)
open(path, "w", encoding="utf-8").write(text)
PY
        note "set ollama.host to http://$TS_HOST"
    fi
fi

say "Installing home screen shortcuts"
mkdir -p "$SHORTCUTS/tasks"
chmod 700 "$SHORTCUTS"
for script in "$REPO"/setup/shortcuts/*.sh; do
    name="$(basename "$script")"
    case "$name" in
        # Anything that answers into a notification belongs in tasks/, where
        # Termux:Widget runs it without opening a terminal.
        sn-chat.sh) target="$SHORTCUTS/$name" ;;
        *)          target="$SHORTCUTS/tasks/$name" ;;
    esac
    install -m 700 "$script" "$target"
    note "$(basename "$(dirname "$target")")/$name"
done

say "Checking everything"
note "Grant any Android permission prompts that appear."
sn doctor || true

cat <<'EOF'

Next steps
  1. Install the Termux:API and Termux:Widget apps from F-Droid, if you have
     not already. They must come from the same source as Termux itself.
  2. Long-press the home screen > Widgets > Termux:Widget, to get one-tap
     shortcuts for the scripts just installed.
  3. On the laptop, make sure Ollama listens on the tailnet:
       OLLAMA_HOST=0.0.0.0:11434 ollama serve
  4. Start talking:  sn

EOF
