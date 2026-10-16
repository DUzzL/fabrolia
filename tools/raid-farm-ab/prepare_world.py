"""Extract one variant of Lemon_Iced's self-sustaining raid farm from the TMC world zip.

Usage: python -I prepare_world.py ZIP OUT_DIR [--variant "1 player"] [--version 1.21.2]
The zip ("[1.21+]2.45w....zip" from the TMC catalogue) is not redistributed.
Entries are written only below OUT_DIR; macOS metadata is skipped.
"""
import argparse
import os
import zipfile

p = argparse.ArgumentParser()
p.add_argument('zip')
p.add_argument('out')
p.add_argument('--variant', default='1 player')
p.add_argument('--version', default='1.21.2')
a = p.parse_args()

out = os.path.abspath(a.out)
count = 0
with zipfile.ZipFile(a.zip) as z:
    for info in z.infolist():
        name = info.filename
        if not info.flag_bits & 0x800:
            try:
                name = name.encode('cp437').decode('utf-8')  # macOS zips: UTF-8 without the flag
            except UnicodeError:
                pass
        parts = name.split('/')
        if parts[0] == '__MACOSX' or len(parts) < 4 or '..' in parts:
            continue
        if not parts[1].startswith(a.version) or a.variant not in parts[2]:
            continue
        rel = parts[3:]
        if not rel[-1]:
            continue
        target = os.path.abspath(os.path.join(out, *rel))
        if not target.startswith(out + os.sep):
            raise ValueError('zip entry escapes the output directory: ' + name)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with z.open(info) as src, open(target, 'wb') as dst:
            dst.write(src.read())
        count += 1
for junk in ('session.lock', 'desktop.ini'):
    path = os.path.join(out, junk)
    if os.path.exists(path):
        os.remove(path)
print('extracted', count, 'files to', out)
