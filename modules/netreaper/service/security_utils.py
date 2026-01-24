"""
Shared security helpers for NetReaper.

Functions here are intentionally minimal so they can be imported by both GUI
and server code and exercised by tests.
"""

from __future__ import annotations

import re
from typing import Iterable, Sequence, Tuple, Union


CommandLike = Union[str, Sequence[str]]


def sanitize_command_for_display(command: CommandLike) -> str:
    """
    Sanitize a command string or argv sequence for safe display/logging.

    This redacts obvious secret patterns (passwords, tokens, Authorization
    headers) and flattens argv sequences to a single string for logs.
    """
    if isinstance(command, (list, tuple)):
        cmd_text = " ".join(str(part) for part in command)
    else:
        cmd_text = command or ""
    cmd_text = re.sub(r'(-p|--password|--passwd)\s+\S+', r'\1 ***REDACTED***', cmd_text, flags=re.IGNORECASE)
    cmd_text = re.sub(r'(password|passwd|pwd)[=:\s]+\S+', r'\1=***REDACTED***', cmd_text, flags=re.IGNORECASE)
    cmd_text = re.sub(r'(api[_-]?key|token|secret)[=:\s]+\S+', r'\1=***REDACTED***', cmd_text, flags=re.IGNORECASE)
    cmd_text = re.sub(r'(Authorization|Bearer)[:\s]+\S+', r'\1: ***REDACTED***', cmd_text, flags=re.IGNORECASE)
    return cmd_text.strip()


def validate_allowlisted_command(
    command: str,
    allowed_roots: Iterable[str],
    max_length: int = 200,
    max_args: int = 20,
) -> Tuple[bool, Union[str, Sequence[str]]]:
    """
    Validate a command string against an allowlist and injection filters.

    Returns (True, argv list) when valid, otherwise (False, error message).
    """
    if not command or len(command) > max_length:
        return False, "Command is empty or exceeds maximum length."

    try:
        import shlex

        parts = shlex.split(command)
    except ValueError as exc:
        return False, f"Command parsing failed: {exc}"

    if not parts:
        return False, "Command is empty after parsing."

    if len(parts) > max_args:
        return False, "Command has too many arguments."

    root = parts[0]
    if root not in allowed_roots and not any(root.endswith(suffix) for suffix in allowed_roots):
        return False, f"Command not permitted: {root}"

    disallowed = re.compile(r"[;&|`$()<>]")
    if any(disallowed.search(part) for part in parts):
        return False, "Shell metacharacters are not permitted."

    if any(".." in part or "~" in part for part in parts):
        return False, "Path traversal tokens are not permitted."

    return True, parts
