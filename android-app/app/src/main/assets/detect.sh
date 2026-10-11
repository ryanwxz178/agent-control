set -eu
printf 'ORCA_OS|%s\n' "$(uname -s)"
printf 'ORCA_HOME|%s\n' "$HOME"
probe() {
  id="$1"; kind="$2"; shift 2
  for name in "$@"; do
    p=$(command -v "$name" 2>/dev/null || true)
    if [ -n "$p" ] && [ -f "$p" ]; then printf 'ORCA_AGENT|%s|%s|%s\n' "$id" "$kind" "$p"; return; fi
  done
}
probe codex codex codex
probe claude claude claude
dsh_cli=$(command -v dsh 2>/dev/null || true)
probe deepseek dsh dsh
probe codebuddy buddy codebuddy cbc
if [ "$(uname -s)" = Darwin ]; then
  for root in /Applications "$HOME/Applications"; do
    if [ -d "$root/WorkBuddy.app" ]; then
      cli="$root/WorkBuddy.app/Contents/Resources/app.asar.unpacked/cli/bin/codebuddy"
      node=$(command -v node 2>/dev/null || true)
      if [ -f "$cli" ] && [ -n "$node" ]; then
        printf 'ORCA_AGENT|workbuddy|buddy|%s|%s\n' "$node" "$cli"
      else printf 'ORCA_AGENT|workbuddy|desktop|WorkBuddy\n'; fi
      printf 'ORCA_APP|workbuddy|WorkBuddy\n'
    fi
    if [ -d "$root/TeleAgent.app" ]; then printf 'ORCA_APP|teleagent|TeleAgent\n'; fi
    if [ -d "$root/Doubao.app" ]; then printf 'ORCA_APP|doubao|Doubao\n'; fi
    dsh="$root/DeepSeek Harness.app/Contents/Resources/runtime/cli/bin/dsh"
    if [ -d "$root/DeepSeek Harness.app" ]; then printf 'ORCA_APP|deepseek|DeepSeek Harness\n'; fi
    if [ ! -f "$dsh_cli" ] && [ -x "$dsh" ]; then dsh_cli="$dsh"; printf 'ORCA_AGENT|deepseek|dsh|%s\n' "$dsh"; fi
    if [ -d "$root/Claude.app" ]; then printf 'ORCA_APP|claude|Claude\n'; fi
    if [ -d "$root/Codex.app" ]; then printf 'ORCA_APP|codex|Codex\n'; fi
  done
fi
printf 'ORCA_DETECTION_DONE\n'
