Phase 0 — Non-Negotiable Ground Rules (applies to every phase)
Rules
No silent failures. Ever.

Every action produces either:

a Success state (with data), or

a Failure state (with a user-readable reason + dev-readable diagnostics).

One source of truth

UI must only reflect state produced by authoritative backend logic (scan engine + repository).

No “pretend” UI states.

Every async job must be cancelable

Anything scan-related gets a Job/token and must stop cleanly.

Main-thread UI updates only

If it changes UI state, it happens on Main.

R1: Test output (or a documented reason why test is not possible)

R2: Code reference (file path(s) + key method names)

R3: Completion mark ✅ in the checklist

Phase 1 — Permission + Lifecycle Gating (stop the “scan does nothing” problem)
Goal
Scanning can never start unless all required permissions + OS prerequisites are satisfied, and the app shows exactly why.

Step-by-step plan
Build a “Scan Preconditions” gate

Inputs:

Android version

permission status (granted/denied/permanently denied)

Wi-Fi enabled status

Location enabled status (needed for scan visibility on many devices)

Output: Ready OR Blocked(reason + fixAction)

Implement runtime permission flow

Android 13+: request NEARBY_WIFI_DEVICES where applicable.

Android 10–12: request location permission used for Wi-Fi scanning visibility (and explain why).

If denied:

show a clear UI reason + CTA (“Grant permission” / “Open settings”)

If permanently denied:

route to app settings and do not retry blindly.

Lifecycle-aware scan trigger

Scan starts from ViewModel via a lifecycle-aware collector (Fragment/Activity).

Rotation/background/foreground does not “lose” scan state.

Explicit denial handling

Denial is a first-class state: ScanState.Blocked(PermissionDenied) not “nothing happened.”

Acceptance checklist
✅ Scan button pressed with missing permission shows a blocking UI message + action.

✅ Granting permission immediately enables scan without restarting app.

✅ Denying permission produces a clear blocked state (not silent).

✅ Rotation preserves scan state (Idle/Blocked/Running/Complete/Error).

R1: Code refs: ScanPreconditions, PermissionCoordinator, ScanViewModel

Phase 2 — Background Execution Survival (stop the OS from murdering scans)
Goal
Scans don’t get paused/killed the moment the user backgrounds the app.

Step-by-step plan
Classify scan types

Short scan (≤ 15–30 seconds): OK in foreground UI scope.

Long scan (> 30 seconds, multi-step discovery): must run as Foreground Service (visible notification).

Implement Foreground Service for long scans

Requirements:

persistent notification (“Scanning network… Tap to return”)

cancel action from notification

Provide WorkManager fallback

For scheduled/resilient scans (optional):

WorkManager with constraints + backoff.

Still: if real-time discovery is needed, Foreground Service is the correct tool.

OEM defense

Add a “Battery optimization” warning screen when repeated kills detected:

detect via “scan interrupted unexpectedly” patterns

provide allowlisting instructions (device-specific hints if possible)

Acceptance checklist
✅ Start scan → press Home → scan continues and completes.

✅ Notification shows during scanning and can cancel.

✅ Returning to app shows updated results.

✅ Scan job cancels cleanly without leaking threads/coroutines.

Receipts required

R1: Instrumented test: cancel action stops scan

R2: Code refs: ScanForegroundService, NotificationController

Phase 3 — Network Discovery That Actually Works (stop “zero devices found” lies)
Goal
Discovery works across real network conditions and fails informatively when the network blocks it.

Step-by-step plan
Discovery strategy: don’t bet everything on multicast
Use tiered discovery:

Tier A: ARP table / neighbor cache read (where possible)

Tier B: targeted subnet ping sweep (rate-limited)

Tier C: TCP port probing for known services (timeout-limited)

Tier D: multicast discovery (mDNS/SSDP) with MulticastLock

Acquire MulticastLock when using multicast

Hold it only for the discovery window.

Time bounds for every network operation

Every UDP receive: timeout.

Every connect: timeout.

Every scan stage: max duration.

Exception mapping

Convert raw exceptions into a small set of user-visible errors:

NoNetwork

WifiDisabled

PermissionsMissing

NetworkBlocksMulticast

Timeout

PartialResults

Rate limiting

Don’t DDOS the LAN like a confused raccoon.

Cap concurrency and packet rate.

Acceptance checklist
✅ Scan finishes within declared max time (no infinite spinner).

✅ If multicast blocked, UI says “Multicast blocked, using fallback method.”

✅ Partial results show with warning, not a fake “all good.”

✅ Logs show which tiers were attempted and what succeeded.

Receipts required
R1: Log excerpt with per-tier timings and outcomes

R2: Code refs: DiscoveryEngine, MulticastCoordinator, ScanTierRunner

Phase 4 — Threading + Execution Path Integrity (stop “it never runs” and “UI never updates”)
Goal
Scan execution paths are real, lifecycle-safe, cancelable, and UI updates are guaranteed.

Step-by-step plan
Move scan orchestration into a single coordinator

One entrypoint: startScan(request)

One exit: ScanState stream

Use ViewModel-owned coroutine scope

UI triggers ViewModel intent.

ViewModel launches scan in viewModelScope.

UI collects state via StateFlow / LiveData.

Main-thread dispatch enforced

State emission can happen off-main, but UI binding must observe on-main.

Cancellation

Starting a new scan cancels the old one cleanly.

Leaving screen cancels if design says so (or continues if service-based).

Acceptance checklist
✅ Rotate screen mid-scan: scan continues and UI stays correct.

✅ Start scan twice: first cancels, second runs.

✅ Background thread never touches UI directly (no “works on my phone” nonsense).

Receipts required

R1: Unit test: scan coordinator emits ordered states

R2: Code refs: ScanCoordinator, ScanViewModel, ScanStateFlow

Phase 5 — UI Wiring to Real State (kill the “cosmetic UI” disease)
Goal
Every button triggers a real action and the UI reflects real state only.

Step-by-step plan
Inventory every UI control

Make a table:

Control ID

Intended behavior

Current handler

Backend function called

Observable state updated

Define UI intents

OnScanClicked

OnDeviceSelected(deviceId)

OnConnectClicked(deviceId)

etc.

Bind UI to StateFlow

UI shows:

Idle, Blocked, Running(progress), Results(list), Error

No fake data unless explicitly labeled

Mock mode must be gated behind a developer flag and visually marked.

Acceptance checklist
✅ Every button produces an observable state change within 200ms (even if it’s “Blocked”).

✅ Scan results appear from repo pipeline.

✅ No button is “cosmetic only.”

Receipts required
R1: Log: UI_INTENT_RECEIVED for each control

R2: UI test: scan button triggers Running state

R3: Control inventory table stored in repo (docs)

Phase 6 — Backend Contracts + Reliability (stop “endpoint assumed to exist” fantasy)
Goal
All backend/API calls are contract-validated, resilient, and user-visible on failure.

Step-by-step plan
Define API contracts

Explicit request/response models.

Version fields if needed.

Add schema/format validation

Reject malformed responses.

Retries with backoff

Only for retryable failures (timeouts, 5xx).

Never retry 4xx endlessly.

Error mapping to UI

Any 4xx/5xx becomes a user-visible error state with a clear message.

Connectivity awareness

Don’t fire calls when offline.

Show offline state, provide retry.

Acceptance checklist
✅ 404/500 produces visible error, not silent failure.

✅ Timeouts retry within bounds, then fail clearly.

✅ Contract violations are detected and logged with payload snippet.

Receipts required
R1: Logs: API_CALL_FAILED with status/timeout category

R2: Contract tests (mock server) validate parsing + errors

R3: Code refs: ApiClient, ContractModels, ErrorMapper

Phase 7 — AI/Agent Integration That Isn’t Theater
Goal
AI output becomes validated, persisted, and actionable, not “a string in logs.”

Step-by-step plan
Define a strict schema for AI output

Example categories:

recommended action

target device id/ip

parameters

confidence

Validate AI output

If invalid:

show “AI response invalid” + fallback suggestion

Persist AI outputs

Store with:

timestamp

input prompt hash

response

validation result

Action pipeline

AI output can trigger:

UI suggestion

pre-filled action dialog

optional “execute” after user confirmation

Audit trail

Every AI-driven action logs:

input → output → validation → user approval → execution result

Acceptance checklist
✅ AI output displayed in UI in a structured format (not raw blob).

✅ Invalid AI output handled gracefully.

✅ AI can trigger a real action flow (with user confirmation).

✅ Full audit trail exists.

Receipts required
R1: Logs: AI_OUTPUT_VALIDATED, AI_ACTION_EXECUTED

R2: Unit tests: schema validation pass/fail cases

R3: Code refs: AiSchema, AiValidator, AiActionRouter

Phase 8 — Final Verification: “Proof of Life” Test Matrix
Goal
Prove the app works on real devices and hostile conditions.

Required test scenarios (minimum)
Permissions

Deny, grant, permanently deny

Lifecycle

Rotation mid-scan

Background mid-scan

Kill and restore (where applicable)

Network hostility

Multicast blocked

High latency / packet loss simulation

No internet (LAN only)

Backend

500 responses

timeouts

invalid payload

OEM constraints

Battery optimization ON

confirm foreground service behavior

Completion mark
This phase is not “done” until you have:

✅ video proof per scenario class

✅ logs per scenario

✅ test outputs

✅ documented known limitations (if any) with exact devices/OS versions


