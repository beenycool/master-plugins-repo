#!/usr/bin/env bash
# Repository verification: build/tests, shell syntax, Python syntax, YAML,
# jar/plugin.yml versions, docker compose config and the rickroll pack.
#
#   tools/verify.sh             full check (runs Gradle)
#   SKIP_BUILD=1 tools/verify.sh  reuse an existing build
#   tools/verify.sh --no-build    same as SKIP_BUILD=1
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SKIP_BUILD="${SKIP_BUILD:-0}"
if [ "${1:-}" = "--no-build" ]; then
  SKIP_BUILD=1
fi

step() {
  printf '\n== %s ==\n' "$1"
}

shopt -s nullglob

if [ "$SKIP_BUILD" != "1" ]; then
  step "Gradle build and tests"
  ./gradlew build --no-daemon --console=plain
else
  step "Gradle build skipped (SKIP_BUILD=1)"
fi

step "Shell syntax (bash -n)"
scripts=(docker/*.sh tools/*.sh)
if [ "${#scripts[@]}" -eq 0 ]; then
  echo "no shell scripts found"
else
  for script in "${scripts[@]}"; do
    bash -n "$script"
    echo "ok: $script"
  done
fi

if command -v shellcheck >/dev/null 2>&1; then
  step "ShellCheck (errors only)"
  shellcheck -S error "${scripts[@]}" || {
    echo "shellcheck reported errors" >&2
    exit 1
  }
else
  step "ShellCheck skipped (not installed)"
fi

step "Python byte-compile"
pycache="$(mktemp -d)"
cleanup_pycache() {
  rm -rf "$pycache"
}
trap cleanup_pycache EXIT
pys=(tools/*.py docker/*.py)
if [ "${#pys[@]}" -eq 0 ]; then
  echo "no Python tools found"
else
  PYTHONPYCACHEPREFIX="$pycache" python3 -m py_compile "${pys[@]}"
  echo "ok: ${pys[*]}"
fi

step "YAML parse"
yaml_files=(.github/workflows/*.yml .github/workflows/*.yaml docker/*.yml docker/*.yaml)
if [ "${#yaml_files[@]}" -eq 0 ]; then
  echo "no YAML files found"
elif python3 -c 'import yaml' >/dev/null 2>&1; then
  python3 - "${yaml_files[@]}" <<'PY'
import sys
import yaml

for path in sys.argv[1:]:
    with open(path, "r", encoding="utf-8") as handle:
        yaml.safe_load(handle)
    print(f"ok: {path}")
PY
elif command -v ruby >/dev/null 2>&1; then
  ruby -e 'require "yaml"; ARGV.each { |f| YAML.load_file(f); puts "ok: #{f}" }' "${yaml_files[@]}"
else
  echo "PyYAML and ruby are unavailable; skipping YAML parse"
fi

step "docker compose config"
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  RCON_PASSWORD="${RCON_PASSWORD:-verify}" docker compose -f docker/docker-compose.yml config -q
  echo "ok: docker/docker-compose.yml"
else
  echo "docker compose is unavailable; skipping compose config"
fi

step "Jar/plugin.yml versions"
expected_version="$(sed -n 's/^[[:space:]]*version = "\(.*\)".*/\1/p' build.gradle.kts | head -n 1)"
expected_version="${expected_version:-0.2.0}"
for dir in plugins/*/; do
  name="$(basename "$dir")"
  if [ ! -d "$dir/src/main" ]; then
    continue
  fi
  jar="$(find "$dir/build/libs" -maxdepth 1 -name '*.jar' -print -quit 2>/dev/null || true)"
  if [ -z "$jar" ]; then
    echo "missing built jar for $name (run the Gradle build first)" >&2
    exit 1
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    echo "unzip is unavailable; cannot inspect $jar" >&2
    exit 1
  fi
  plugin_yml="$(unzip -p "$jar" plugin.yml)"
  version="$(printf '%s\n' "$plugin_yml" | awk '/^version:/{gsub(/["'\'']/, "", $2); print $2; exit}')"
  if [ "$version" != "$expected_version" ]; then
    echo "$jar: plugin.yml version '$version' != expected '$expected_version'" >&2
    exit 1
  fi
  if printf '%s\n' "$plugin_yml" | grep -q '\${version}'; then
    echo "$jar: plugin.yml still contains the unexpanded \${version}" >&2
    exit 1
  fi
  echo "ok: $jar ($version)"
done

step "Rickroll pack (optional)"
pack="${PACK:-/opt/guab-server/resourcepack/rickroll.zip}"
pack_properties="${PACK_PROPERTIES:-/opt/guab-server/data/plugins/GuabRickroll/pack.properties}"
if [ -f "$pack" ] && [ -f "$pack_properties" ]; then
  python3 tools/verify-rickroll-pack.py --pack "$pack" --properties "$pack_properties"
elif [ -f "$pack" ]; then
  echo "pack present but $pack_properties is missing; skipping pack check"
else
  echo "no live pack at $pack; skipping pack check"
fi

step "Summary"
echo "All verification checks passed."
