# RAD History Retention Implementation Plan

> **For agentic workers:** Implement inline with TDD. This change deletes old payroll records, so preserve calculation anchors before removing data.

**Goal:** Keep the app fast and fresh by automatically pruning irrelevant old history while preserving the calculations still needed by the retained months.
**Architecture:** `ShiftRepository` remains the public facade. A narrow repository-level retention policy computes a retained window from the latest populated month, materializes required payroll anchors, then removes old records and month-scoped data. Storage and backup keep using the existing JSON codec.
**Tech Stack:** Android/Kotlin, in-memory repository, JSON local storage, JUnit4 tests.
**Assumptions:** The retained window is the latest populated month minus 24 months through the latest populated month, inclusive, which keeps 12 months of rolling-base history, 12 future months, and one extra previous payroll month. This assumes the latest populated month is the intended horizon and will not work as an archival system for arbitrary old payroll lookup.

---

## Scope

- Add automatic repository history pruning after data mutations/import.
- Preserve future calculations by writing compact anchors before deleting old data.
- Keep existing UI unchanged.
- Keep backup/export schema compatible.

## Non-Goals

- No Room/database migration.
- No UI settings for changing the retention window.
- No cloud backup behavior change beyond exporting the pruned repository state.
- No deletion of global user settings such as tax settings, seniority, brigade type, blood donation reminder state, or known-base anchor.

## Retention Rules

- Determine latest populated month from `ShiftRecordStore`.
- If fewer than 25 retained months would be needed, do nothing.
- Cutoff month is `latestPopulatedMonth - 24 months`.
- Keep records from cutoff through latest.
- Delete records older than cutoff.
- Preserve `initialCompBalanceMinutes` as the comp-time balance at cutoff before deletion.
- If an older hourly-value change controls cutoff, write a cutoff-month hourly-value anchor before removing older hourly-value changes.
- Materialize resolved monthly pay bases for retained work months whose source-month calculation would depend on deleted data.
- Keep month-scoped gross additions and net adjustments only for retained months.
- Keep vacation year balances only for years represented in retained records.

## Verification

- `.\gradlew.bat testDebugUnitTest --tests "com.rad.shifttracker.ShiftRepositoryTest"`
- `.\gradlew.bat testDebugUnitTest lint detekt`

