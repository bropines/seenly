#!/usr/bin/env python3
"""The notes of a GitHub release.

    python3 scripts/release/notes.py <tag> [output]

The highlights in docs/releases/<tag>.md, when that file exists, then "📋 Changelog" with the
tag's own section of CHANGELOG.md (`## [0.1.0] - date`, without the heading). Run from the
repository's root, as .github/workflows/release.yml does; the output is release_notes.md
unless named.
"""
import os
import re
import sys


def section(changelog: str, version: str) -> str:
    """The text under `## [version]`, up to the next section; empty when there is none."""
    match = re.search(rf'(?s)(##\s*\[{re.escape(version)}\].*?)(?=\n##\s*\[|\Z)', changelog)
    if not match:
        return ''
    lines = match.group(1).strip().splitlines()
    return '\n'.join(lines[1:]).strip()


def read(path: str) -> str:
    if not os.path.exists(path):
        return ''
    with open(path, 'r', encoding='utf-8') as f:
        return f.read().strip()


def notes(tag: str) -> str:
    highlights = read(os.path.join('docs', 'releases', f'{tag}.md'))
    changes = section(read('CHANGELOG.md'), tag.lstrip('v'))
    parts = [highlights]
    if changes:
        parts.append('## 📋 Changelog\n\n' + changes)
    return '\n\n'.join(p for p in parts if p) + '\n'


def main(argv: list) -> int:
    if len(argv) not in (2, 3):
        print(__doc__.strip().splitlines()[2].strip(), file=sys.stderr)
        return 2
    with open(argv[2] if len(argv) == 3 else 'release_notes.md', 'w', encoding='utf-8') as out:
        out.write(notes(argv[1]))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
