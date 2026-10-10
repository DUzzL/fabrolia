#!/usr/bin/env python3
"""Read or bump the release version; gradle.properties is the source of truth."""
import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)-alpha")


def coordinates(version: str) -> tuple[int, int, int]:
    match = VERSION.fullmatch(version)
    if not match:
        raise ValueError(f"Expected X.Y.Z-alpha, got {version!r}")
    return tuple(map(int, match.groups()))


def read_version(properties: Path) -> str:
    text = properties.read_bytes().decode('utf-8')
    matches = list(re.finditer(r"^version[ \t]*=[ \t]*([^\r\n]+)", text, re.M))
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one version property in {properties}")
    version = matches[0][1].strip()
    coordinates(version)
    return version


def artifact_name(version: str) -> str:
    major, minor, patch = coordinates(version)
    return f"fabrolia-alpha_{major}.{minor}.{patch}.jar"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['show', 'minor', 'patch'], nargs='?', default='show')
    parser.add_argument('--dry-run', action='store_true', help='show the bump without writing it')
    parser.add_argument('--properties', type=Path, default=ROOT / 'gradle.properties')
    args = parser.parse_args()
    try:
        old = read_version(args.properties)
        major, minor, patch = coordinates(old)
        if args.action == 'minor':
            minor, patch = minor + 1, 0
        elif args.action == 'patch':
            patch += 1
        new = f"{major}.{minor}.{patch}-alpha"
        if args.action != 'show' and not args.dry_run:
            text = args.properties.read_bytes().decode('utf-8')
            text = re.sub(r"^(version[ \t]*=[ \t]*)[^\r\n]+", lambda m: m[1] + new, text, count=1, flags=re.M)
            args.properties.write_bytes(text.encode('utf-8'))
        print(new)
        print(artifact_name(new))
    except (OSError, ValueError) as error:
        parser.exit(1, f"release version: {error}\n")


if __name__ == '__main__':
    main()
