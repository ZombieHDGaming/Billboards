#!/usr/bin/env python3
"""Regenerates src/client/resources/assets/billboards/lang/en_us.json from the UI text in the code.

UI text is written in English and wrapped in Lang.tr("...") (see me.zhdg.billboards.Lang); enum display names in the
model and the viewport tools are included too. Run from the repository root after changing UI text:

    python3 scripts/update_lang.py

Translations go in their own language files (e.g. de_de.json) with the same keys.
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
SOURCES = os.path.join(ROOT, 'src/client/java/me/zhdg/billboards')
OUTPUT = os.path.join(ROOT, 'src/client/resources/assets/billboards/lang/en_us.json')


def key(english):
    """Must match Lang.key in Java."""
    slug = english.lower().replace('%s', 'x')
    slug = re.sub(r'[^a-z0-9]+', '_', slug).strip('_')
    if len(slug) > 64:
        slug = slug[:64].rstrip('_')
    return 'billboards.' + (slug or 'blank')


def unescape(literal):
    return literal.replace('\\"', '"').replace('\\n', '\n').replace('\\\\', '\\')


def main():
    entries = {}
    collisions = []

    def add(text):
        text = text.split('##')[0]
        if not re.search(r'[A-Za-z]', text):
            return
        k = key(text)
        if k in entries and entries[k] != text:
            collisions.append((k, entries[k], text))
        entries.setdefault(k, text)

    for path in glob.glob(os.path.join(SOURCES, '**/*.java'), recursive=True):
        with open(path, encoding='utf-8') as f:
            source = f.read()
        for match in re.finditer(r'Lang\.tr\("((?:[^"\\]|\\.)*)"', source):
            add(unescape(match.group(1)))
    for path in glob.glob(os.path.join(SOURCES, 'model/*.java')) + [os.path.join(SOURCES, 'editor/ViewportTools.java')]:
        with open(path, encoding='utf-8') as f:
            for match in re.finditer(r'^\s*[A-Z_]+\("([^"]+)"', f.read(), re.M):
                add(match.group(1))

    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
    with open(OUTPUT, 'w', encoding='utf-8') as f:
        json.dump(dict(sorted(entries.items())), f, indent='\t', ensure_ascii=False)
        f.write('\n')
    print(f'{len(entries)} keys written to {os.path.relpath(OUTPUT, ROOT)}')
    for k, a, b in collisions:
        print(f'Key collision: {k} is used for both {a!r} and {b!r}; reword one of them', file=sys.stderr)
    return 1 if collisions else 0


if __name__ == '__main__':
    sys.exit(main())
