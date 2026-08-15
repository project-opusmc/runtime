#!/usr/bin/env bash
set -euo pipefail

# Preview work must not create a second launcher or game instance.
opus_processes() {
  opus_ignored_pids=""
  opus_cursor="$$"
  while [[ "${opus_cursor}" =~ ^[0-9]+$ && "${opus_cursor}" -gt 1 ]]; do
    opus_ignored_pids="${opus_ignored_pids} ${opus_cursor} "
    opus_cursor="$(ps -o ppid= -p "${opus_cursor}" | tr -d '[:space:]')"
  done

  ps -axo pid=,command= | awk -v ignored="${opus_ignored_pids}" '
    {
      line = $0
      sub(/^[[:space:]]*/, "", line)
      pid = line
      sub(/[[:space:]].*$/, "", pid)
      if (index(ignored, " " pid " ") != 0) {
        next
      }
      command = line
      sub(/^[0-9]+[[:space:]]+/, "", command)
      if (command ~ /\/Applications\/Opus Launcher( QA)?\.app\/Contents\/MacOS\/opus-launcher/ \
          || command ~ /opus\.ui\.preview\.control\.file=/ \
          || command ~ /\.opus-launcher-ui-preview\/game/) {
        print pid "\t" command
      }
    }
  '
}

print_processes() {
  local opus_found
  opus_found="$(opus_processes)"
  if [[ -z "${opus_found}" ]]; then
    echo "Opus process guard: idle"
    return 0
  fi
  echo "Opus process guard: active launcher processes:"
  printf '%s\n' "${opus_found}"
  return 1
}

case "${1:-status}" in
  status)
    print_processes || true
    ;;
  assert-idle)
    if ! print_processes; then
      echo "Refusing to launch another Opus process. Stop the listed process first." >&2
      exit 1
    fi
    ;;
  stop)
    opus_found="$(opus_processes)"
    if [[ -z "${opus_found}" ]]; then
      echo "Opus process guard: idle"
      exit 0
    fi
    printf '%s\n' "${opus_found}" | while IFS=$'\t' read -r opus_pid _; do
      kill -TERM "${opus_pid}"
    done
    for _ in $(seq 1 10); do
      if [[ -z "$(opus_processes)" ]]; then
        echo "Opus process guard: stopped"
        exit 0
      fi
      sleep 1
    done
    echo "Opus process guard: a process did not stop gracefully:" >&2
    opus_processes >&2
    exit 1
    ;;
  *)
    echo "Usage: $0 {status|assert-idle|stop}" >&2
    exit 64
    ;;
esac
