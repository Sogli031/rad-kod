# Project Map
_Generated: 2026-05-20 15:57 | Staleness: timestamps (no .git repo detected)_

## Directory Structure
`app/` - Android application module for the RAD shift tracker.
`app/src/main/java/com/rad/shifttracker/` - Main Kotlin package with Compose screens, dialogs, domain models, payroll, scheduling, export, notifications, and repository facade.
`app/src/main/java/com/rad/shifttracker/data/repository/` - Repository collaborators for record indexing, mutable payroll state, pay settings, pay queries, comp-balance, vacation tracking, and rolling-base simulation.
`app/src/main/java/com/rad/shifttracker/data/storage/` - JSON writer, repository snapshot/codec, and local file persistence.
`app/src/main/java/com/rad/shifttracker/data/backup/` - Google Drive backup, sign-in state, and Drive file lookup integration.
`app/src/main/java/com/rad/shifttracker/ui/components/` - Shared Compose responsive text, indicator, and icon components.
`app/src/main/res/` - Android resources: strings, themes, fonts, launcher image, backup XML, and notification drawable.
`app/src/test/java/com/rad/shifttracker/` - JUnit4 regression suite for payroll, scheduling, storage, formatting, dialog text, onboarding, export, reminders, and UI helper logic.
`docs/superpowers/specs/` - Approved or paused design specs, currently including the advance-payment/monthly-payout design.
`docs/superpowers/plans/` - Implementation plans currently present for advance payment and payroll transport/payment autofill balance.
`gradle/` - Gradle wrapper files.
Root files - Gradle build scripts, Detekt config, `.gitignore`, sync batch script, session memory files, fonts/images, legacy `rad.py`, root `work_shifts.json`, and the collective-agreement DOCX reference.

## Key Files
`settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle.properties` - Single-module Android/Compose build using AGP 8.7.3, Kotlin 2.0.21, Gradle wrapper, JDK 17, and Detekt.
`detekt.yml` - Static-analysis configuration; refactor quality is not complete unless Detekt stays green.
`session-log.md`, `state.md` - Local cross-session memory; read these before resuming paused payroll-slip or refactor work.
`app/src/main/AndroidManifest.xml` - Main app manifest, launcher activity, backup XML references, notification/backup permissions, and `RadApplication`.
`app/src/main/java/com/rad/shifttracker/MainActivity.kt`, `RadApplication.kt` - Android entry point plus application-level owner for repository and Google Drive backup services.
`app/src/main/java/com/rad/shifttracker/ShiftTrackerScreen.kt` - Main Compose route; owns month state, dialog state, onboarding, Google sign-in launchers, selected-month export, reminder toggles, and monthly tax update effect.
`app/src/main/java/com/rad/shifttracker/MainDialogs.kt`, `OptionsDialog.kt` - Main dialog router and options entry points; keep month-specific payout actions in the pay-details flow when requested.
`app/src/main/java/com/rad/shifttracker/ShiftRepository.kt` - Public facade for records, payroll settings, vacation state, JSON import/export, local storage, summaries, monthly details, reconstruction apply gates, and callbacks.
`app/src/main/java/com/rad/shifttracker/data/repository/RepositoryPayQueries.kt` - Repository-level payroll reads that combine records, pay state, comp-balance references, rolling/known-base logic, and `PayCalculator`.
`app/src/main/java/com/rad/shifttracker/data/repository/RepositoryPayState.kt`, `RepositoryPaySettingsUpdater.kt`, `RepositoryCompBalanceCalculator.kt`, `RepositoryVacationTracker.kt`, `ShiftRecordStore.kt`, `RollingPayBaseMonthSimulator.kt` - Extracted repository collaborators; keep future refactors as narrow verified slices while preserving `ShiftRepository` as facade.
`app/src/main/java/com/rad/shifttracker/data/storage/ShiftRepositoryJsonCodec.kt`, `ShiftRepositorySnapshot.kt`, `LocalShiftStorage.kt`, `JsonWriter.kt` - Backup schema, migration parsing, snapshot shape, and persistence; preserve keys such as `pay_coefficient`, `hourly_value_changes`, and known-base anchor fields.
`app/src/main/java/com/rad/shifttracker/PayCalculator.kt`, `MonthlyNetPayBreakdownCalculator.kt`, `MonthlyPayDetails.kt`, `PayBreakdown.kt`, `PayBaseAverage.kt`, `PayRateHistory.kt` - Core payroll math, gross/net breakdown, detailed monthly pay rows, rolling/known-base average calculation, and hourly value history.
`app/src/main/java/com/rad/shifttracker/BasePayDialog.kt`, `SettingsDialogText.kt` - Satnica UI and visible Serbian payroll copy/provenance text; source-month wording is part of the behavior.
`app/src/main/java/com/rad/shifttracker/MonthlyWorkAndPayExport.kt`, `MonthlyAuditExport.kt`, `DailyPayCategoryBreakdown.kt` - Selected-work-month JSON export, audit formulas/checks, daily category breakdown, and settings/debug snapshots.
`app/src/main/java/com/rad/shifttracker/PayrollSlipModels.kt`, `PayrollSlipReconstructionService.kt`, `PayrollSlipReconstructionDialog.kt`, `PayrollSlipDialogText.kt` - Payroll-slip reconstruction input, exact row comparison, feasibility warnings, preview UI, and copy.
`app/src/main/java/com/rad/shifttracker/AdvancePayment.kt`, `AdvancePaymentDialog.kt`, `PayDetailsDialog.kt`, `PayDetailsDialogText.kt`, `PayDetailsPaymentInputs.kt` - Monthly net adjustments, advance payment, final payout details, and pay-details entry UI.
`app/src/main/java/com/rad/shifttracker/ShiftScheduler.kt`, `AutoFillDialog.kt`, `AutoFillDialogComponents.kt` - Shift auto-fill scheduler and guided fill UI for one-shift, first/second, three-brigade, and four-brigade modes.
`app/src/main/java/com/rad/shifttracker/DayRecord.kt`, `ShiftTypes.kt`, `MonthSummary.kt`, `AppConstants.kt`, `Formatting.kt`, `ShiftRepositoryValidation.kt` - Core domain types, config constants, display formatting, and input sanitizers.
`app/src/main/java/com/rad/shifttracker/DayEditDialog.kt`, `DayEditState.kt`, `PopupComponents.kt`, `InitialCompBalanceDialog.kt` - Day editing, minute shortage/comp-balance UI state, and shared popup components.
`app/src/main/java/com/rad/shifttracker/VacationTracking.kt`, `VacationBalanceDialog.kt`, `AbsenceDialog.kt` - Annual leave balance, carryover/expiry rules, vacation setup, and absence range previews.
`app/src/main/java/com/rad/shifttracker/Holidays.kt`, `HolidayManagementDialog.kt` - Serbian holidays and editable holiday UI. Tax parameters, coefficient and seniority come from the latest payslip (`PayslipImport.kt`); `LegacyCleanup.kt` cancels the removed food-receipt reminder alarm and drops its/tax-update prefs on old installs.
`docs/superpowers/plans/2026-05-13-akontacija-isplata-meseca.md`, `docs/superpowers/plans/2026-05-13-payroll-transport-payment-autofill-balance.md`, `docs/superpowers/specs/2026-05-13-akontacija-isplata-meseca-design.md` - Current docs under `docs/superpowers`; older `2026-05-10-rad-refactor-cleanup.md` is not present on disk.

## Critical Constraints
- Use Windows PowerShell commands from this root; typical Android gates are `.\gradlew.bat testDebugUnitTest`, `.\gradlew.bat lint`, and `.\gradlew.bat detekt`.
- This checkout currently has no `.git` directory; `project-map.md` staleness must use timestamps unless the user explicitly asks to initialize Git.
- User-facing Serbian text must use Serbian ekavica with proper diacritics; preserve exact visible wording unless the task explicitly asks for text changes.
- Refactor/review verdicts in this repo are not complete with tests alone; `testDebugUnitTest`, `lint`, and `detekt` all matter.
- Keep `ShiftRepository` as the public facade; existing callers rely on names like `exportJson()`, `importFromJson()`, `getSummary()`, `getNetPayBreakdown()`, and `getMonthlyPayDetails()`.
- Continue refactors as narrow verified slices. Do not try to finish `ShiftRepository` in one broad pass.
- JSON payroll import precedence is intentional: prefer `pay_coefficient`, then fall back to `config_gross_hourly_rate`; do not revive `config_base_pay_hourly` as coefficient input.
- Satnica for a selected work month uses the previous calendar month as the pay-base source month; answer mapping questions by separating work month from source month.
- `KNOWN_BASE` is a distinct anchor/carry-forward path: empty post-anchor months carry forward, real months replace carry-forward, and fallback simulation stays out of that mode. There can be many anchors (one per payslip, `known_pay_base_anchors`); a month uses the latest anchor not after it (`RepositoryPayState.knownPayBaseAnchorFor`).
- Sick pay has no base of its own: per KU čl. 66 it follows the same resolved base as vacation/holiday; the configured `sickPayBaseHourly` only matters in MANUAL mode.
- Three-brigade weekday extra hours: only two user choices, `PAID` and `COMP_TIME` (banked, unpaid). `PAID` extra first automatically "makes up" whole missed workdays of the same month (shift OFF on a workday, not holiday, minus comp used) as regular pay with night premium; only the rest is overtime; days >12h never make up; shorter days never absorb extra. Fits payslips 06/2026 (6h OT) and 07/2026 (17h OT). Made-up minutes credit the comp balance so the covered-deficit pay doesn't pay them twice (`shortageMinutesCoveredByExtraWork`). Old value 2 ("Odrada") reads as `PAID`.
- Night premium only for full hours of a third-shift segment (`nightPremiumMinutes`). 123% holiday premium is clock-based (`holidayMinutesForShift`): third shift = 2h on start day + rest on next day; start day respects `workedOnHoliday`.
- Payslip code 96 "Izostanci 1" is minute rounding (leftover absence minutes × hourly rate × (1 + seniority)), not a penalty; minute-precise day entries already include it.
- Four-brigade rolling-base completeness is cycle-based and can be sparse: a valid 8-day alignment must have expected worked days, not every calendar workday.
- Four-brigade overtime is monthly-fund based, not daily `> 8h`; `PayCalculator.getOvertimeHours()` returns `0` for `CETVOROBRIGADA`, then month-level overtime is assigned later.
- Export/reporting must target the currently selected work month; do not default to the previous source/pay-base month.
- Payroll-slip reconstruction targets pre-deduction matching. All comparison rows must be exact when feasible, `Rad u režiji na DP` stays separate, and infeasible targets block apply instead of best-effort applying.
- Generated outputs under `build/`, `app/build/`, `.gradle/`, and `.kotlin/` should not be edited directly.

## Hot Files
`ShiftRepository.kt`, `RepositoryPayQueries.kt`, `RepositoryPayState.kt`, `RepositoryPaySettingsUpdater.kt`, `RepositoryCompBalanceCalculator.kt`, `ShiftRepositoryJsonCodec.kt`, `MonthlyNetPayBreakdownCalculator.kt`, `MonthlyPayDetails.kt`, `PayCalculator.kt`, `PayBreakdown.kt`, `PayBaseAverage.kt`, `BasePayDialog.kt`, `SettingsDialogText.kt`, `MonthlyWorkAndPayExport.kt`, `MonthlyAuditExport.kt`, `PayrollSlipReconstructionService.kt`, `PayrollSlipReconstructionDialog.kt`, `PayDetailsDialog.kt`, `ShiftTrackerScreen.kt`, `DayEditDialog.kt`, `VacationBalanceDialog.kt`, `ShiftRepositoryTest.kt`, `PayBreakdownTest.kt`, `PayCalculatorTest.kt`, `PayrollSlipReconstructionServiceTest.kt`, `MonthlyWorkAndPayExportTest.kt`, `SettingsDialogTextTest.kt`, `PayDetailsDialogTextTest.kt`, `ScreenSafeAreaTest.kt`
