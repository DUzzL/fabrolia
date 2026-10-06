#!/usr/bin/env bash
# Fabrolia test stand: a fresh production-like 26.3 server with 40 real protocol clients.
#
#   stand.sh perf   NAME JAR [SECONDS] [--scenario items|pistons]... [--heap]   load + spark profile
#   stand.sh verify NAME JAR                                                  functional checks
#   stand.sh export NAME JAR                                                  mixin export (-Dmixin.debug.export)
#   stand.sh build  NAME JAR [build_run.py options]                           build a run only
#   stand.sh run    NAME DRIVER [ARGS...]                                     run a driver on a built run
#
# Environment:
#   FABROLIA_STAND_HOME   stand data dir (template/, datapacks/, fabrolia.toml, extra-mods/, runs/)
#                         default: <repo>/../Fabrolia-stand
#   FABROLIA_STAND_JAVA   JDK 25 java binary (default: java on PATH)
#   FABROLIA_STAND_SERVER_CPUS / FABROLIA_STAND_CLIENT_CPUS   taskset lists (see standenv.py)
#   FABROLIA_STAND_XMX    server heap (default 16G)
#   FABROLIA_STAND_UNSANDBOXED=1   run without the systemd-run sandbox (no passwordless sudo)
#
# One stand at a time per FABROLIA_STAND_HOME: every command holds $FABROLIA_STAND_HOME/stand.lock.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
export FABROLIA_STAND_HOME="$(cd "${FABROLIA_STAND_HOME:-$REPO/../Fabrolia-stand}" && pwd)"
export FABROLIA_STAND_JAVA="${FABROLIA_STAND_JAVA:-$(command -v java || true)}"
[ -n "$FABROLIA_STAND_JAVA" ] || { echo "stand: no java; set FABROLIA_STAND_JAVA (JDK 25)" >&2; exit 2; }
"$FABROLIA_STAND_JAVA" -version 2>&1 | grep -q 'version "25' \
  || echo "stand: warning: $FABROLIA_STAND_JAVA is not JDK 25 (production runs Temurin 25)" >&2
RUNS="$FABROLIA_STAND_HOME/runs"
mkdir -p "$RUNS"

exec 9>"$FABROLIA_STAND_HOME/stand.lock"
flock 9

build() {  # NAME JAR [options]
  local name="$1" jar="$2"; shift 2
  python3 "$HERE/build_run.py" "$name" "$jar" "$@"
}

run_driver() {  # NAME DRIVER [ARGS...]
  local name="$1" driver="$2"; shift 2
  local run="$RUNS/$name" unit="fabrolia-stand-$(echo "$name" | tr -cd 'a-z0-9-' | tail -c 40)"
  [ -f "$run/selected.json" ] || { echo "stand: $run is not a built run" >&2; exit 2; }
  local status=0
  if [ "${FABROLIA_STAND_UNSANDBOXED:-0}" = 1 ]; then
    (cd "$run" && FABROLIA_NATIVE_BURST_ISOLATED=loopback-only HOME="$run" \
       python3 "$run/$driver" "$run" "$@") > "$run.out" 2>&1 || status=$?
  elif sudo -n true 2>/dev/null && command -v systemd-run >/dev/null; then
    sudo -n systemd-run --unit="$unit" --collect --wait --pipe --uid="$(id -u)" --gid="$(id -g)" \
      --working-directory="$run" --property=ProtectSystem=strict --property=ProtectHome=read-only \
      --property=ReadWritePaths="$run" --property=PrivateTmp=yes --property=PrivateNetwork=yes \
      --property=NoNewPrivileges=yes --property=IPAddressDeny=any --property=IPAddressAllow=localhost \
      '--property=RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6' --property=MemoryMax=26G \
      --property=RuntimeMaxSec=3600 --property=TimeoutStopSec=650 --property=KillMode=control-group \
      --setenv=FABROLIA_NATIVE_BURST_ISOLATED=loopback-only --setenv=HOME="$run" \
      --setenv=FABROLIA_STAND_HOME="$FABROLIA_STAND_HOME" --setenv=FABROLIA_STAND_JAVA="$FABROLIA_STAND_JAVA" \
      ${FABROLIA_STAND_SERVER_CPUS+--setenv=FABROLIA_STAND_SERVER_CPUS="$FABROLIA_STAND_SERVER_CPUS"} \
      ${FABROLIA_STAND_CLIENT_CPUS+--setenv=FABROLIA_STAND_CLIENT_CPUS="$FABROLIA_STAND_CLIENT_CPUS"} \
      /usr/bin/python3 "$run/$driver" "$run" "$@" > "$run.out" 2>&1 || status=$?
  else
    echo "stand: the sandbox needs passwordless sudo + systemd-run; or set FABROLIA_STAND_UNSANDBOXED=1" >&2
    exit 2
  fi
  echo "== $name exit=$status"
  grep -E 'EXPORT_RESULT' "$run.out" | cut -c1-400 || true
  if ls "$run/server/crash-reports"/* >/dev/null 2>&1; then echo "CRASH REPORTS:"; ls "$run/server/crash-reports"; fi
  return "$status"
}

summary_verify() {
  python3 - "$RUNS/$1/verify-report.json" <<'EOF'
import json, sys
r = json.load(open(sys.argv[1]))
checks = {k: v.get('result') for k, v in r.get('checks', {}).items() if isinstance(v, dict)}
print('verify', r.get('status'), 'ALL PASS' if checks and all(v == 'PASS' for v in checks.values()) else checks)
EOF
}

cmd="${1:-}"; shift || true
case "$cmd" in
  perf)
    name="$1" jar="$2"; shift 2
    seconds=120
    if [ $# -gt 0 ] && [[ "$1" =~ ^[0-9]+$ ]]; then seconds="$1"; shift; fi
    build "$name" "$jar" "$@"
    run_driver "$name" perf-driver.py "$seconds"
    ls "$RUNS/$name/server/config/spark/"*.sparkprofile 2>/dev/null || true ;;
  verify)
    build "$1" "$2"
    run_driver "$1" verify-driver.py || true
    summary_verify "$1" ;;
  export)
    build "$1" "$2" --export
    run_driver "$1" mixin-export-driver.py
    echo "mixin export: $RUNS/$1/server/.mixin.out" ;;
  build) build "$@" ;;
  run) run_driver "$@" ;;
  *) sed -n '2,20p' "$0"; exit 2 ;;
esac
