# AGENTS.md

## Purpose
These instructions apply to the entire repository. Follow them for every change.

## Core rules
- Produce buildable, testable, reviewable changes. Do not leave the codebase in a half-working state.
- Do not guess. If information is missing, make the minimum-risk assumptions and clearly document them.
- Prefer small, reversible changes with clear reasoning.
- Keep formatting and linting consistent with existing project conventions.

## Workflow
1. Inspect relevant files, build system, and dependencies before editing.
2. Plan edits before making changes.
3. Ensure any touched files or functions are left in a complete, working state.
4. Validate changes (tests/builds) when feasible, and report what was run or why it could not be run.

## Quality bar
- No dead code, unused imports, or duplicate resources.
- Handle nullability, lifecycle, and error states explicitly.
- For UI changes, include accessibility and state handling.
- For networking changes, include timeouts, error mapping, and security basics.

## Reporting
- Summarize edits by file path.
- Provide a verification checklist with commands and expected success criteria.
