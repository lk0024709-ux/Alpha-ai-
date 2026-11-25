#!/usr/bin/env python3
"""Turn a Gradle/Kotlin failure log into GitHub Actions annotations.

CI job logs are not downloadable from every environment, but check-run
annotations always are (PR "Files changed"/"Checks" UI and the REST API).
This script extracts the lines that actually explain a failure and prints one
`::error::`/`::warning::` command per line so they show up as annotations.

Usage: python3 .github/scripts/report_gradle_errors.py <log-file>
"""
from __future__ import annotations

import re
import sys

MAX_MESSAGES = 40
MAX_CHARS = 900

KOTLIN_ERROR = re.compile(r"^e: ")
KOTLIN_WARNING = re.compile(r"^w: ")
WRONG_HEADER = re.compile(r"^\* What went wrong:")
TASK_FAILED = re.compile(r"^> Task .* FAILED$")
FAILURE_HEADER = re.compile(r"^FAILURE: ")
CAUSED_BY = re.compile(r"^Caused by: ")
RESOLUTION = re.compile(r"^> (Could not|Failed to|No matching|Plugin|Unresolved)")


def escape(text: str) -> str:
    """Escape the message part of a GitHub workflow command.

    Only %, CR and LF need escaping in the message body; ':' and ',' are only
    special in property values, so leaving them alone keeps messages readable.
    """
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def clip(text: str) -> str:
    text = " ".join(text.split())
    return text[:MAX_CHARS]


def collect(lines: list[str]) -> list[tuple[str, str]]:
    found: list[tuple[str, str]] = []
    index = 0
    while index < len(lines):
        line = lines[index]
        if KOTLIN_ERROR.match(line):
            found.append(("error", f"Kotlin error: {line}"))
        elif WRONG_HEADER.match(line):
            # The explanation follows the header, up to the next blank-ish marker.
            block = [line]
            for follow in lines[index + 1 : index + 12]:
                if not follow.strip():
                    break
                block.append(follow)
            found.append(("error", " | ".join(block)))
        elif TASK_FAILED.match(line) or FAILURE_HEADER.match(line):
            found.append(("error", line))
        elif CAUSED_BY.match(line) or RESOLUTION.match(line):
            found.append(("error", line))
        elif KOTLIN_WARNING.match(line):
            found.append(("warning", f"Kotlin warning: {line}"))
        index += 1
    return found


def main() -> int:
    log_path = sys.argv[1] if len(sys.argv) > 1 else "gradle.log"
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except FileNotFoundError:
        print(f"::warning::Gradle log not found at {log_path}")
        return 0

    found = collect(lines)
    emitted = 0
    seen: set[str] = set()
    for level, message in found:
        key = message[:200]
        if key in seen:
            continue
        seen.add(key)
        if emitted >= MAX_MESSAGES:
            break
        print(f"::{level}::{escape(clip(message))}")
        emitted += 1

    if emitted == 0:
        print("::error::Gradle failed but no error pattern matched; dumping log tail")
        tail = [line for line in lines[-25:] if line.strip()]
        for line in tail:
            print(f"::error::{escape(clip(line))}")
    else:
        print(f"::warning::Reported {emitted} Gradle error line(s) as annotations")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
