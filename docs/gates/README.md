# Quality Gates

## Gate 0: Bootstrap sanity
- Wrapper bootstrap scripts work
- `./gradlew tasks` runs

## Gate 1: Build matrix
- Desktop runs: `:app-ui:run`
- Android builds and launches

## Gate 2: Core correctness
- Merge rules have unit tests
- Scan is off main thread
- Events recorded for scans

## Gate 3: UX safety
- Destructive actions confirm + undo window
- Empty states are informative

## Gate 4: Platform degradation
- Android limitations are explained in UI
