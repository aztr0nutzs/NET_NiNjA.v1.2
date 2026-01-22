# Inspection Checklist

## Build
- [ ] `./gradlew clean build` passes
- [ ] No new critical warnings/lints

## Threading
- [ ] Discovery runs off UI thread
- [ ] UI updated via flows/state only

## Networking safety
- [ ] Scan is rate-limited
- [ ] No privileged ops by default

## Data integrity
- [ ] Merge rules tested
- [ ] Events recorded for scans and actions

## UX
- [ ] Confirm + undo for destructive actions
- [ ] Style Parity screen updated when components change
