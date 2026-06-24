# Cross-App Exact Notifications Design

## Scope

Improve notification timing in four local Android apps under `C:\Users\PC\Desktop\programi`: `ciklus`, `rad`, `kalorije`, and `zaduzenja`.

The goal is to make scheduled reminders as close as Android allows to their intended wall-clock time on devices such as Google Pixel 9. A few minutes of drift is acceptable; broad or silent drift is not.

## Non-Goals

- No server-side scheduling.
- No redesign of settings screens or notification flows.
- No changes to payroll, food, calorie, cycle, dose, article, backup, Room, or JSON semantics.
- No promise of second-level delivery accuracy, because Android battery/idle policy can still intervene.

## Design

Each app should use the same scheduling policy where reminders are time-sensitive:

1. Prefer `AlarmManager.setExactAndAllowWhileIdle(...)` when exact alarms are available.
2. On older APIs that do not support idle exact alarms, use exact `AlarmManager` scheduling where applicable.
3. If exact alarms are unavailable, use a narrow bounded `setWindow(...)` fallback and keep the existing delivery guards.
4. After a reminder fires, reschedule the next reminder from persisted app state.
5. Reschedule after boot, app update, time change, and timezone change where the app already owns a receiver or can safely extend one.

## App-Specific Plan

- `rad`: keep existing `FoodReceiptReminderScheduler` exact-alarm behavior; harden `BloodDonationReminderScheduler`, which currently uses a six-hour window.
- `zaduzenja`: keep the strict 08:00-11:00 delivery guard and use exact scheduling for the next check when exact alarms are available.
- `kalorije`: keep the daily weight reminder flow; replace unbounded fallback with a bounded fallback and reschedule on update/time/timezone changes.
- `ciklus`: keep injection/oral/PCT behavior intact; centralize alarm scheduling so exact alarms are preferred and fallback drift is bounded.

## Failure-Mode Check

- Exact-alarm permission may be denied by the system or user. Severity: minor and expected; the fallback is bounded and UI/status copy can warn where already available.
- A delayed fallback could still fire after the useful window. Severity: critical for `zaduzenja`; the existing 08:00-11:00 delivery guard remains mandatory.
- Repeating alarms can accumulate stale schedules after restart or time changes. Severity: critical; receivers and app-start rescheduling must rebuild from persisted state instead of relying on old alarms.

## Testing Strategy

- Add unit tests for pure scheduling policy and bounded fallback values before production changes.
- Run focused unit tests for the changed app.
- Run each app's normal Gradle verification gate.
- Build `assembleRelease` for each app and copy the release APKs to `C:\Users\PC\Desktop\python skriptovi`.
