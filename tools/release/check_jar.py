#!/usr/bin/env python3
"""Verify the release filename, version, mixin entries and Java compatibility."""
import argparse
import json
import hashlib
import struct
import zipfile
from pathlib import Path

from release_version import ROOT, artifact_name, read_version


def check(jar: Path) -> None:
    version = read_version(ROOT / 'gradle.properties')
    if jar.name != artifact_name(version):
        raise ValueError(f"Expected {artifact_name(version)}, got {jar.name}")
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate ZIP entries')
        metadata = json.loads(archive.read('fabric.mod.json'))
        if metadata.get('id') != 'fabrolia' or metadata.get('version') != version:
            raise ValueError('Fabrolia id/version does not match gradle.properties')
        if metadata.get('depends', {}).get('java') != '>=25':
            raise ValueError('Unexpected Java requirement')
        for config in metadata['mixins']:
            config = config if isinstance(config, str) else config['config']
            mixins = json.loads(archive.read(config))
            for group in ['mixins', 'client', 'server']:
                for name in mixins.get(group, []):
                    entry = (mixins['package'] + '.' + name).replace('.', '/') + '.class'
                    if entry not in names:
                        raise ValueError(f'Missing configured mixin: {entry}')
        if metadata['accessWidener'] not in names:
            raise ValueError('Missing access widener')
        classes = 0
        for name in names:
            if name.endswith('.class'):
                with archive.open(name) as entry:
                    header = entry.read(8)
                magic, _, major = struct.unpack('>IHH', header)
                if magic != 0xCAFEBABE or major > 69:
                    raise ValueError(f'{name} requires more than Java 25 or is invalid')
                classes += 1
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    print(f'PASS: {jar.name}, version {version}, {classes} classes, mixins, Java 25')
    print(f'SHA256 {digest}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    args = parser.parse_args()
    try:
        check(args.jar)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, struct.error) as error:
        parser.exit(1, f'release check: {error}\n')
