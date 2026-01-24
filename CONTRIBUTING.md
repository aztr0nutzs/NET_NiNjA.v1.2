# Contributing to NET_NiNjA.v1.2

Thank you for contributing! This document explains how to set up a development environment, tests, and the preferred workflow.

1. Quick start (local)
- Prereqs: JDK 17+, Gradle wrapper (./gradlew), Docker (optional), Node 18+ (optional for UI tooling).
- Start server locally:
  - Build: ./gradlew :server:build
  - Run (local dev): ./gradlew :server:run
  - Or use docker-compose: docker-compose up --build
- Open UI: http://localhost:8787/ui/ninja_dash.html

2. Branching & PRs
- Branch from `main` using descriptive names: `feature/<short-desc>` or `fix/<short-desc>`.
- Open PRs against `main`. Use the provided PR template.

3. Tests & CI
- Add unit tests for server code under server/src/test.
- Client-side JS tests under web-ui/tests as applicable.
- Ensure CI passes: lint -> build -> tests.

4. Commits
- Use conventional commit messages:
  - feat: new feature
  - fix: bug fix
  - docs: documentation only changes
  - chore: build/system changes
- Include a short body if needed.

5. Code reviews
- Assign at least one reviewer.
- Ensure new dependencies are justified and minimal.

6. Security/Disclosure
- If you find a vulnerability, follow our SECURITY.md process.
