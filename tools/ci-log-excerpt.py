#!/usr/bin/env python3
"""Kürzt ein Actions-Log auf die Stellen, an denen der Build scheitert.

Liest das Log von stdin und gibt die Zeilen um Fehlermarker aus. Findet es
keinen Marker, bleibt der Schluss des Logs — dort steht der Abbruch meistens.
Die Zeitstempel nimmt es ab, weil sie die Zeilen nur breiter machen.
"""

import re
import sys

# `(?<!:)` lässt `::error::` aus Workflow-Skripten stehen: Das ist eine Zeile
# Quelltext, kein Build-Fehler.
MARKER = re.compile(r"(FAILED|FAILURE|BUILD FAILED|(?<!:)error:|Fehler:|Exception|tests completed)")
TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T[^ ]+\s")
CONTEXT_BEFORE = 1
CONTEXT_AFTER = 6
MAX_LINES = 80
FALLBACK_TAIL = 40


def excerpt(lines):
    """Liefert die Zeilen um Fehlermarker, sonst den Schluss des Logs."""
    selected = set()
    for index, line in enumerate(lines):
        if MARKER.search(line):
            start = max(0, index - CONTEXT_BEFORE)
            end = min(len(lines), index + 1 + CONTEXT_AFTER)
            selected.update(range(start, end))
    if selected:
        return [lines[index] for index in sorted(selected)][:MAX_LINES]
    tail = lines[-FALLBACK_TAIL:]
    if not tail:
        return tail
    return ["(kein Fehlermarker im Log — letzte Zeilen)"] + tail


def main():
    lines = [TIMESTAMP.sub("", line) for line in sys.stdin.read().splitlines()]
    print("\n".join(excerpt(lines)))


if __name__ == "__main__":
    main()
