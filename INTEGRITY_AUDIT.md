# INTEGRITY_AUDIT.md

PROJECT: NET_NiNjA.v1.2
DATE: 2026-02-09T01:44:08-05:00

SUMMARY:
- Changes Since Delivery:
  - Added on-device ONVIF WS-Discovery (multicast probe) and unified `/api/v1/onvif/discover` DTO shape across desktop and Android.
  - Moved the GitHub Actions workflow from repo-root `ci.yml` to `.github/workflows/ci.yml`.
  - Updated the server API contract test to stop the scan only after a deterministic device ID is available (reduces flakiness in full gates).
  - `web-ui/openclaw_dash.html` drift is still present relative to other repository copies; see "Action Items" for measured state.
- Rebuild Status: SUCCESS (FV-02)
  - Command: `.\gradlew clean assembleDebug assembleRelease test lint --no-daemon --console=plain --warning-mode all`
  - Exit code: 0 (see `docs/gates/FV-02_gradle_clean_assemble_test_lint.exit.txt`)
  - Log: `docs/gates/FV-02_gradle_clean_assemble_test_lint.log`
- CI Workflow Lint: PASS
  - Tool: `actionlint` (local run)
  - Exit code: 0 (see `docs/gates/FV-02_actionlint.exit.txt`)
  - Log: `docs/gates/FV-02_actionlint.log`
- Security Audit Results: PASS (no hardcoded secrets detected in app/server build sources)
  - Notes: Placeholder token strings exist in example configs/docs under `claw-sync/` and `mission-control/` (expected; not real secrets).
  - No insecure Gradle repository URLs (`http://`) detected in build scripts.
- Documentation Consistency: PARTIAL
  - The FV-01 "delivery truth" documents exist and are being re-issued with updated dates/evidence.
  - `docs/REPOSITORY_ORGANIZATION.md` may need revisiting if any additional build artifacts or verification receipts are added at repo root.

ACTION ITEMS:
- HIGH: Decide disposition of `web-ui/openclaw_dash.html` drift (measured, current state).
  - `web-ui/openclaw_dash.html` SHA256: `D1B3956E0A3606B74029C6C49F54E0C926F83907CEBE18B7AB4EB61B4E334C8C`
  - The following copies match each other but DO NOT match `web-ui/openclaw_dash.html` (all SHA256 `7DB5CC78D85883C59DFEB7552CD909372766354A75B25063A9BE0CE0FFD5848B`):
    - `web-ui/new_assets/openclaw_dash.html`
    - `android-app/openclaw/openclaw-gateway/openclaw_dash.html`
    - `skills/skills-folders/openclaw/openclaw-gateway/openclaw_dash.html`
    - `skills/skills-folders/app/openclaw/openclaw-gateway/openclaw_dash.html`
  - Required corrective action: declare the canonical dashboard copy and either sync or remove the other copies to prevent future untracked drift.
- MEDIUM: Environment continuity on Windows.
  - Root cause: `java` may not be available on PATH in PowerShell; gates were executed with `JAVA_HOME` pointing at a local JDK.
  - Required corrective action: document this explicitly for operators, or ensure Java is discoverable in the intended shells.
- LOW: Reduce warnings for long-term signal-to-noise.
  - Root cause: deprecated Android/Java APIs and one static-analysis always-true condition.
  - Required corrective action: refactor to non-deprecated equivalents and remove the always-true branch condition.

FINAL RECOMMENDATION:
Patch required. Resolve `web-ui/openclaw_dash.html` drift (sync or remove duplicates) and then re-run the FV-02 regression gate to restore a clean continuity baseline. Consider re-audit in 30 days or at the next dependency/toolchain upgrade.

