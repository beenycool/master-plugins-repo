#!/usr/bin/env python3
"""Check that plugin.yml commands, aliases and permissions appear in README.md.

This is deliberately small and safe to run from a laptop or CI:

    python3 tools/check-docs.py

It reads every plugins/*/src/main/resources/plugin.yml, extracts command names,
aliases and permissions, and looks for `/command` (or the bare permission) in
README.md. PyYAML is used when available; a tiny built-in parser handles the
flat plugin.yml shape when it is not, so a missing optional dependency never
turns this check into a crash.

Exit status is 0 when every item is documented (or explicitly allowlisted) and
1 when something is missing. It is not wired into tools/verify.sh because a
docs-only omission should not block a build.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
README = ROOT / "README.md"
PLUGIN_GLOB = "plugins/*/src/main/resources/plugin.yml"

# Deliberately undocumented items belong here, with a reason. Add exact names
# (not globs) so an accidental rename still gets caught.
#
#   "aliases": {
#       "gcore": "legacy shortcut; the full /guabcore command is documented",
#   },
ALLOWLIST = {
    "commands": {},
    "aliases": {},
    "permissions": {},
}


def strip_comment(line: str) -> str:
    """Remove an unquoted YAML comment without disturbing '#' inside strings."""
    quote = None
    escaped = False
    for index, char in enumerate(line):
        if escaped:
            escaped = False
            continue
        if char == "\\" and quote == '"':
            escaped = True
            continue
        if quote:
            if char == quote:
                quote = None
            continue
        if char in ("'", '"'):
            quote = char
        elif char == "#":
            return line[:index]
    return line


def unquote(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def split_inline_list(value: str) -> list[str]:
    body = value.strip()
    if not (body.startswith("[") and body.endswith("]")):
        return []
    body = body[1:-1]
    if not body.strip():
        return []
    items: list[str] = []
    current: list[str] = []
    quote = None
    for char in body:
        if quote:
            current.append(char)
            if char == quote:
                quote = None
        elif char in ("'", '"'):
            quote = char
            current.append(char)
        elif char == ",":
            items.append(unquote("".join(current)))
            current = []
        else:
            current.append(char)
    items.append(unquote("".join(current)))
    return [item for item in items if item]


def parse_with_pyyaml(text: str):
    import yaml  # type: ignore

    data = yaml.safe_load(text) or {}
    if not isinstance(data, dict):
        raise ValueError("plugin.yml did not parse to a mapping")
    commands = data.get("commands") or {}
    permissions = data.get("permissions") or {}
    return commands, permissions


def parse_without_pyyaml(text: str):
    """Parse the subset of plugin.yml this check needs, without PyYAML."""
    commands: dict[str, dict] = {}
    permissions: list[str] = []
    section = None
    current_command = None
    aliases_mode = False

    for raw in text.splitlines():
        line = strip_comment(raw).rstrip()
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        content = line.strip()

        if indent == 0:
            key = content.split(":", 1)[0].strip()
            section = key if key in ("commands", "permissions") else None
            current_command = None
            aliases_mode = False
            continue

        if section == "commands":
            if indent == 2 and content.endswith(":"):
                current_command = unquote(content[:-1])
                commands.setdefault(current_command, {"aliases": [], "permission": None})
                aliases_mode = False
                continue
            if current_command is None:
                continue
            if indent >= 4 and content.startswith("- ") and aliases_mode:
                commands[current_command]["aliases"].append(unquote(content[2:]))
                continue
            if indent >= 4 and ":" in content:
                key, value = content.split(":", 1)
                key = key.strip()
                value = value.strip()
                aliases_mode = False
                if key == "aliases":
                    if value.startswith("["):
                        commands[current_command]["aliases"].extend(split_inline_list(value))
                    else:
                        aliases_mode = True
                elif key == "permission" and value:
                    commands[current_command]["permission"] = unquote(value)
            continue

        if section == "permissions" and indent == 2 and content.endswith(":"):
            permissions.append(unquote(content[:-1]))

    return commands, permissions


def load_plugin_yml(path: Path):
    text = path.read_text(encoding="utf-8")
    try:
        return parse_with_pyyaml(text), "PyYAML"
    except ImportError:
        return parse_without_pyyaml(text), "builtin"
    except Exception:
        # A malformed or unexpected file should still be usable by the fallback.
        return parse_without_pyyaml(text), "builtin"


def readme_mentions_command(readme: str, name: str) -> bool:
    pattern = re.compile(r"(?<![\w-])/" + re.escape(name) + r"(?![\w-])", re.IGNORECASE)
    return pattern.search(readme) is not None


def readme_mentions_permission(readme: str, permission: str) -> bool:
    return permission in readme


def main() -> int:
    if not README.is_file():
        print(f"README not found: {README}", file=sys.stderr)
        return 1

    plugin_files = sorted(ROOT.glob(PLUGIN_GLOB))
    if not plugin_files:
        print(f"No plugin.yml files found under {PLUGIN_GLOB}", file=sys.stderr)
        return 1

    readme = README.read_text(encoding="utf-8")
    commands: dict[str, str] = {}
    aliases: dict[str, str] = {}
    permissions: dict[str, str] = {}
    parse_modes: set[str] = set()
    errors: list[str] = []

    for path in plugin_files:
        try:
            (plugin_commands, plugin_permissions), mode = load_plugin_yml(path)
            parse_modes.add(mode)
        except Exception as exc:  # noqa: BLE001 - report the file and keep going
            errors.append(f"{path}: {exc}")
            continue

        label = path.parent.parent.parent.parent.name

        for name, spec in (plugin_commands or {}).items():
            command_name = str(name)
            commands.setdefault(command_name, label)
            if not isinstance(spec, dict):
                continue
            value = spec.get("aliases") or []
            if isinstance(value, str):
                value = [value]
            for alias in value:
                aliases.setdefault(str(alias), label)
            permission = spec.get("permission")
            if permission:
                permissions.setdefault(str(permission), f"{label} command")

        for name in plugin_permissions or {}:
            permissions.setdefault(str(name), label)

    missing_commands = sorted(
        name for name in commands
        if name not in ALLOWLIST["commands"]
        and not readme_mentions_command(readme, name)
    )
    missing_aliases = sorted(
        name for name in aliases
        if name not in ALLOWLIST["aliases"]
        and not readme_mentions_command(readme, name)
    )
    missing_permissions = sorted(
        name for name in permissions
        if name not in ALLOWLIST["permissions"]
        and not readme_mentions_permission(readme, name)
    )

    print(f"Docs check: {len(plugin_files)} plugin.yml file(s) "
          f"({', '.join(sorted(parse_modes)) or 'no parser'})")
    print(f"  {len(commands)} command(s), {len(aliases)} alias(es), "
          f"{len(permissions)} permission(s)")

    if errors:
        for error in errors:
            print(f"  warning: {error}")

    if missing_commands:
        print("\nMissing command names in README.md:")
        for name in missing_commands:
            print(f"  /{name}  ({commands[name]})")
    if missing_aliases:
        print("\nMissing aliases in README.md:")
        for name in missing_aliases:
            print(f"  /{name}  ({aliases[name]})")
    if missing_permissions:
        print("\nMissing permissions in README.md:")
        for name in missing_permissions:
            print(f"  {name}  ({permissions[name]})")

    if missing_commands or missing_aliases or missing_permissions or errors:
        print("\nDocs check failed. Add the item to README.md or the allowlist "
              "in tools/check-docs.py.")
        return 1

    print("Docs check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
