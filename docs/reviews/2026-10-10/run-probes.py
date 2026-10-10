#!/usr/bin/env python3
"""Run the isolated TNT code-review witnesses, never a production server."""
import argparse
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--minecraft-jar', type=Path, default=Path.home() / '.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar')
parser.add_argument('--libraries', type=Path, default=root.parent / 'Rustmi/stand/server/libraries')
parser.add_argument('--java', default='java')
parser.add_argument('--javac', default='javac')
args = parser.parse_args()
if not args.minecraft_jar.is_file() or not args.libraries.is_dir():
    parser.error('Minecraft 26.3 jar and its library directory are required')
classpath = ':'.join(str(p.resolve()) for p in [args.minecraft_jar, *sorted(args.libraries.rglob('*.jar'))])
sources = sorted((Path(__file__).parent / 'probes').glob('*.java'))
with tempfile.TemporaryDirectory(prefix='fabrolia-tnt-review-') as temp:
    subprocess.run([args.javac, '-cp', classpath, '-d', temp, *map(str, sources)],
                   cwd=temp, check=True)
    for source in sources:
        print(f'=== {source.stem} ===', flush=True)
        subprocess.run([args.java, '-Xmx256m', '-cp', temp + ':' + classpath, source.stem],
                       cwd=temp, check=True, timeout=90)
