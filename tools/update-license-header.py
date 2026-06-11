#!/usr/bin/env python3

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""Update license headers for tracked source files.

The script is intentionally small and dependency-free. By default it rewrites
headers to the Apache Software Foundation Apache-2.0 header used by this repo.
Use --owner/--year or --header-file when the project needs a different header.
"""

from __future__ import annotations

import argparse
import datetime as dt
import fnmatch
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Iterable


ROOT = Path(__file__).resolve().parents[1]

DEFAULT_APACHE_HEADER = [
    "Licensed to the Apache Software Foundation (ASF) under one or more",
    "contributor license agreements. See the NOTICE file distributed with",
    "this work for additional information regarding copyright ownership.",
    "The ASF licenses this file to You under the Apache License, Version 2.0",
    '(the "License"); you may not use this file except in compliance with',
    "the License. You may obtain a copy of the License at",
    "",
    "   http://www.apache.org/licenses/LICENSE-2.0",
    "",
    "Unless required by applicable law or agreed to in writing, software",
    'distributed under the License is distributed on an "AS IS" BASIS,',
    "WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.",
    "See the License for the specific language governing permissions and",
    "limitations under the License.",
]

GENERIC_APACHE_TEMPLATE = [
    "Copyright {year} {owner}.",
    "",
    "Licensed under the Apache License, Version 2.0 (the \"License\");",
    "you may not use this file except in compliance with the License.",
    "You may obtain a copy of the License at",
    "",
    "   http://www.apache.org/licenses/LICENSE-2.0",
    "",
    "Unless required by applicable law or agreed to in writing, software",
    'distributed under the License is distributed on an "AS IS" BASIS,',
    "WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.",
    "See the License for the specific language governing permissions and",
    "limitations under the License.",
]

STYLE_BY_SUFFIX = {
    ".bash": "hash",
    ".bat": "rem",
    ".c": "block",
    ".cc": "block",
    ".cmd": "rem",
    ".conf": "hash",
    ".cpp": "block",
    ".css": "block",
    ".dockerfile": "hash",
    ".go": "block",
    ".groovy": "block",
    ".h": "block",
    ".hpp": "block",
    ".htm": "xml",
    ".html": "xml",
    ".ini": "hash",
    ".java": "block",
    ".js": "block",
    ".jsx": "block",
    ".kt": "block",
    ".less": "block",
    ".properties": "hash",
    ".proto": "block",
    ".py": "hash",
    ".rdf": "xml",
    ".rs": "block",
    ".scala": "block",
    ".scss": "block",
    ".sh": "hash",
    ".sql": "dash",
    ".txt": "hash",
    ".tsx": "block",
    ".ts": "block",
    ".toml": "hash",
    ".tpl": "hash",
    ".vue": "xml",
    ".xml": "xml",
    ".yaml": "hash",
    ".yml": "hash",
    ".zsh": "hash",
}

EXTENSIONLESS_STYLE_BY_NAME = {
    "Dockerfile": "hash",
    "Makefile": "hash",
    "mvnw": "hash",
}

COMMON_IGNORES = [
    ".git",
    ".mvn",
    "dist",
    "node_modules",
    "target",
    "**/target/**",
    "**/node_modules/**",
    "**/dist/**",
    "**/licenses/**",
    "**/LICENSE*",
    "**/NOTICE",
    "**/*.gif",
    "**/*.ico",
    "**/*.iml",
    "**/*.jar",
    "**/*.jpeg",
    "**/*.jpg",
    "**/*.json",
    "**/*.lock",
    "**/*.md",
    "**/*.png",
    "**/*.svg",
    "**/*.webp",
]

LICENSE_MARKERS = (
    "Licensed to the Apache Software Foundation",
    "Apache Software Foundation (ASF) licenses this file",
    "Apache License, Version 2.0",
    "SPDX-License-Identifier",
    "Copyright",
)

XML_DECLARATION = re.compile(r"^\s*<\?xml\b[^>]*\?>\s*$")


def run_git_ls_files(include_untracked: bool) -> list[Path]:
    args = ["git", "ls-files"]
    if include_untracked:
        args += ["--cached", "--others", "--exclude-standard"]
    result = subprocess.run(
        args,
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    return [ROOT / line for line in result.stdout.splitlines() if line]


def parse_licenserc_ignores() -> list[str]:
    licenserc = ROOT / ".licenserc.yaml"
    if not licenserc.exists():
        return []

    ignores: list[str] = []
    in_paths_ignore = False
    base_indent: int | None = None
    for raw_line in licenserc.read_text(encoding="utf-8").splitlines():
        stripped = raw_line.strip()
        if stripped == "paths-ignore:":
            in_paths_ignore = True
            base_indent = len(raw_line) - len(raw_line.lstrip())
            continue
        if not in_paths_ignore:
            continue
        indent = len(raw_line) - len(raw_line.lstrip())
        if stripped and base_indent is not None and indent <= base_indent:
            break
        if stripped.startswith("- "):
            item = stripped[2:].strip().strip("'\"")
            if item:
                ignores.append(item)
    return ignores


def should_ignore(relative_path: str, patterns: Iterable[str]) -> bool:
    normalized = relative_path.replace(os.sep, "/")
    for pattern in patterns:
        pattern = pattern.replace(os.sep, "/").strip()
        if not pattern:
            continue
        if normalized == pattern or normalized.startswith(pattern.rstrip("/") + "/"):
            return True
        if fnmatch.fnmatch(normalized, pattern):
            return True
    return False


def detect_style(path: Path, text: str) -> str | None:
    if text.startswith("#!"):
        return "hash"
    if text.startswith("{{/*"):
        return "mustache"
    if path.name in EXTENSIONLESS_STYLE_BY_NAME:
        return EXTENSIONLESS_STYLE_BY_NAME[path.name]
    return STYLE_BY_SUFFIX.get(path.suffix.lower())


def load_header(args: argparse.Namespace) -> list[str]:
    if args.header_file:
        return Path(args.header_file).read_text(encoding="utf-8").splitlines()
    if args.owner:
        year = args.year or str(dt.date.today().year)
        return [line.format(year=year, owner=args.owner) for line in GENERIC_APACHE_TEMPLATE]
    return DEFAULT_APACHE_HEADER


def render_header(style: str, header_lines: list[str]) -> list[str]:
    if style == "block":
        lines = ["/*\n"]
        lines.extend(f" * {line}\n" if line else " *\n" for line in header_lines)
        lines.append(" */\n")
        return lines
    if style == "xml":
        lines = ["<!--\n"]
        lines.extend(f"  ~ {line}\n" if line else "  ~\n" for line in header_lines)
        lines.append("  -->\n")
        return lines
    if style == "hash":
        lines = ["#\n"]
        lines.extend(f"# {line}\n" if line else "#\n" for line in header_lines)
        lines.append("#\n")
        return lines
    if style == "dash":
        lines = ["--\n"]
        lines.extend(f"-- {line}\n" if line else "--\n" for line in header_lines)
        lines.append("--\n")
        return lines
    if style == "rem":
        lines = ["@REM\n"]
        lines.extend(f"@REM {line}\n" if line else "@REM\n" for line in header_lines)
        lines.append("@REM\n")
        return lines
    if style == "mustache":
        lines = ["{{/*\n"]
        lines.extend(f" {line}\n" if line else "\n" for line in header_lines)
        lines.append("*/}}\n")
        return lines
    raise ValueError(f"Unsupported comment style: {style}")


def insertion_offset(lines: list[str], style: str) -> int:
    if lines and lines[0].startswith("#!"):
        return 1
    if style == "xml" and lines and XML_DECLARATION.match(lines[0]):
        return 1
    return 0


def skip_blank_lines(lines: list[str], index: int) -> int:
    while index < len(lines) and not lines[index].strip():
        index += 1
    return index


def find_block_header_end(lines: list[str], index: int) -> tuple[int, str] | None:
    if index >= len(lines) or not lines[index].lstrip().startswith("/*"):
        return None
    collected: list[str] = []
    for cursor in range(index, len(lines)):
        collected.append(lines[cursor])
        if "*/" in lines[cursor]:
            return cursor + 1, "".join(collected)
    return None


def find_xml_header_end(lines: list[str], index: int) -> tuple[int, str] | None:
    if index >= len(lines) or not lines[index].lstrip().startswith("<!--"):
        return None
    collected: list[str] = []
    for cursor in range(index, len(lines)):
        collected.append(lines[cursor])
        if "-->" in lines[cursor]:
            return cursor + 1, "".join(collected)
    return None


def find_line_header_end(lines: list[str], index: int, prefix: str) -> tuple[int, str] | None:
    if index >= len(lines) or not lines[index].lstrip().startswith(prefix):
        return None
    cursor = index
    collected: list[str] = []
    while cursor < len(lines):
        stripped = lines[cursor].lstrip()
        if stripped.startswith(prefix):
            collected.append(lines[cursor])
            cursor += 1
            continue
        break
    return cursor, "".join(collected)


def find_rem_header_end(lines: list[str], index: int) -> tuple[int, str] | None:
    if index >= len(lines):
        return None
    first = lines[index].lstrip().upper()
    if not (first.startswith("@REM") or first.startswith("REM")):
        return None

    cursor = index
    collected: list[str] = []
    while cursor < len(lines):
        stripped = lines[cursor].lstrip().upper()
        if stripped.startswith("@REM") or stripped.startswith("REM"):
            collected.append(lines[cursor])
            cursor += 1
            continue
        break
    return cursor, "".join(collected)


def find_mustache_header_end(lines: list[str], index: int) -> tuple[int, str] | None:
    if index >= len(lines) or not lines[index].lstrip().startswith("{{/*"):
        return None
    collected: list[str] = []
    for cursor in range(index, len(lines)):
        collected.append(lines[cursor])
        if "*/}}" in lines[cursor]:
            return cursor + 1, "".join(collected)
    return None


def find_existing_header(lines: list[str], style: str, index: int) -> tuple[int, int] | None:
    comment_start = skip_blank_lines(lines, index)
    if style == "block":
        found = find_block_header_end(lines, comment_start)
    elif style == "xml":
        found = find_xml_header_end(lines, comment_start)
    elif style == "hash":
        found = find_line_header_end(lines, comment_start, "#")
    elif style == "dash":
        found = find_line_header_end(lines, comment_start, "--")
    elif style == "rem":
        found = find_rem_header_end(lines, comment_start)
    elif style == "mustache":
        found = find_mustache_header_end(lines, comment_start)
    else:
        found = None

    if not found:
        return None

    comment_end, comment_text = found
    if not any(marker in comment_text for marker in LICENSE_MARKERS):
        return None

    remove_start = index if comment_start > index else comment_start
    remove_end = comment_end
    while remove_end < len(lines) and not lines[remove_end].strip():
        remove_end += 1
    return remove_start, remove_end


def strip_leading_blanks(lines: list[str]) -> list[str]:
    index = 0
    while index < len(lines) and not lines[index].strip():
        index += 1
    return lines[index:]


def rewrite_text(text: str, style: str, header_lines: list[str]) -> str:
    newline = "\r\n" if "\r\n" in text else "\n"
    normalized = text.replace("\r\n", "\n")
    lines = normalized.splitlines(keepends=True)

    offset = insertion_offset(lines, style)
    existing = find_existing_header(lines, style, offset)
    if existing:
        start, end = existing
        lines = lines[:start] + lines[end:]
        offset = insertion_offset(lines, style)

    before = lines[:offset]
    after = strip_leading_blanks(lines[offset:])
    rendered = render_header(style, header_lines)

    if before and before[0].startswith("#!"):
        rewritten_lines = before + ["\n"] + rendered + ["\n"] + after
    elif style == "xml":
        rewritten_lines = before + rendered + after
    else:
        rewritten_lines = before + rendered + ["\n"] + after

    rewritten = "".join(rewritten_lines)
    if newline == "\r\n":
        rewritten = rewritten.replace("\n", "\r\n")
    return rewritten


def is_probably_binary(path: Path) -> bool:
    try:
        with path.open("rb") as handle:
            chunk = handle.read(4096)
    except OSError:
        return True
    return b"\0" in chunk


def update_file(path: Path, header_lines: list[str], dry_run: bool) -> bool:
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return False

    style = detect_style(path, text)
    if not style:
        return False

    rewritten = rewrite_text(text, style, header_lines)
    if rewritten == text:
        return False

    if not dry_run:
        path.write_text(rewritten, encoding="utf-8", newline="")
    return True


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Batch update source file license headers.",
    )
    parser.add_argument(
        "paths",
        nargs="*",
        help="Optional files or directories to update. Defaults to tracked files.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print files that would change without writing them.",
    )
    parser.add_argument(
        "--include-untracked",
        action="store_true",
        help="Include untracked files from git ls-files --others.",
    )
    parser.add_argument(
        "--owner",
        help="Copyright owner for a generic Apache-2.0 header.",
    )
    parser.add_argument(
        "--year",
        help="Copyright year used with --owner. Defaults to current year.",
    )
    parser.add_argument(
        "--header-file",
        help="Plain-text header body to wrap with each file's comment style.",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Print skipped-file counts.",
    )
    return parser.parse_args()


def expand_requested_paths(paths: list[str], include_untracked: bool) -> list[Path]:
    candidates = run_git_ls_files(include_untracked)
    if not paths:
        return candidates

    requested = [(ROOT / item).resolve() for item in paths]
    selected: list[Path] = []
    for candidate in candidates:
        resolved = candidate.resolve()
        for item in requested:
            if resolved == item or item in resolved.parents:
                selected.append(candidate)
                break
    return selected


def main() -> int:
    args = parse_args()
    header_lines = load_header(args)
    ignore_patterns = COMMON_IGNORES + parse_licenserc_ignores()
    candidates = expand_requested_paths(args.paths, args.include_untracked)

    changed: list[str] = []
    skipped_binary = 0
    skipped_ignored = 0
    skipped_missing = 0

    for path in candidates:
        try:
            relative = path.relative_to(ROOT).as_posix()
        except ValueError:
            continue
        if should_ignore(relative, ignore_patterns):
            skipped_ignored += 1
            continue
        if not path.exists():
            skipped_missing += 1
            continue
        if is_probably_binary(path):
            skipped_binary += 1
            continue
        if update_file(path, header_lines, args.dry_run):
            changed.append(relative)

    action = "Would update" if args.dry_run else "Updated"
    for relative in changed:
        print(relative)
    print(f"{action} {len(changed)} file(s).")

    if args.verbose:
        print(f"Skipped ignored: {skipped_ignored}")
        print(f"Skipped binary: {skipped_binary}")
        print(f"Skipped missing: {skipped_missing}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
