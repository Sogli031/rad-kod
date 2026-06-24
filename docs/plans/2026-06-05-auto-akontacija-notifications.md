# Auto Akontacija Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-optimized:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Automatically fill `rad` monthly akontacija from approved Valjaonica bank notifications while preserving manual entries and payroll math.
**Architecture:** Add a pure parser/gate helper, a repository write boundary, an Android notification-listener service, and an app-entry permission dialog. The service writes only through the existing `ShiftRepository.setMonthlyAdvancePaymentNet(...)` path.
**Tech Stack:** Kotlin, Android `NotificationListenerService`, Jetpack Compose, JUnit4, Gradle.
**Assumptions:** Assumes the bank notification includes NLB-like amount/date/place text and `Priliv` for income; it will not import notifications that cannot expose those fields. Assumes akontacija belongs to the notification calendar month; it will not remap July to June or any other payroll month. Assumes approved timing is day 2 through day 10 of that month.

---

## Files

- Create `app/src/main/java/com/rad/shifttracker/RadBankNotificationParser.kt` for text collection, package filtering, parsing, normalization, and import-gate rules.
- Create `app/src/main/java/com/rad/shifttracker/RadBankNotificationListenerService.kt` for Android notification-listener handling and confirmation notification.
- Create `app/src/main/java/com/rad/shifttracker/NotificationAccessStatus.kt` for enabled-listener checks and settings intent.
- Modify `app/src/main/java/com/rad/shifttracker/ShiftRepository.kt` to add an idempotent notification import method.
- Modify `app/src/main/java/com/rad/shifttracker/MainActivity.kt` to show the permission dialog on entry/resume.
- Modify `app/src/main/java/com/rad/shifttracker/OptionsDialog.kt` and `MainDialogs.kt` to add a settings action for notification access.
- Modify `app/src/main/AndroidManifest.xml` to register the notification-listener service.
- Add tests in `app/src/test/java/com/rad/shifttracker/RadBankNotificationParserTest.kt`.
- Add repository tests in `app/src/test/java/com/rad/shifttracker/ShiftRepositoryTest.kt`.
- Add text/source tests in `app/src/test/java/com/rad/shifttracker/PayDetailsDialogTextTest.kt`.

## Tasks

### Task 1: Parser and Gate

Security flag: `security` because this handles notification text input and package filtering.

Does NOT cover: service registration, Android permission UI, repository writes.

- [ ] Write failing tests for Valjaonica income acceptance and rejection rules.
- [ ] Run `.\gradlew.bat testDebugUnitTest --tests "com.rad.shifttracker.RadBankNotificationParserTest"` and confirm missing parser failure.
- [ ] Implement `RadBankNotificationParser.kt`.
- [ ] Rerun the focused parser test and confirm pass.

### Task 2: Repository Boundary

Security flag: `none`.

Does NOT cover: parsing or Android service delivery.

- [ ] Add failing `ShiftRepositoryTest` cases for saving the first notification import and preserving an existing manual advance.
- [ ] Run focused repository tests and confirm missing method failure.
- [ ] Implement `recordAdvancePaymentNotificationImport(...)` in `ShiftRepository`.
- [ ] Rerun focused repository tests and confirm pass.

### Task 3: Android Service and Manifest

Security flag: `security` because this adds notification access.

Does NOT cover: entry permission dialog.

- [ ] Add source/constant tests for manifest service registration and confirmation strings.
- [ ] Run focused tests and confirm failure.
- [ ] Implement `RadBankNotificationListenerService.kt` and manifest registration.
- [ ] Rerun focused tests and confirm pass.

### Task 4: Permission Prompt and Options Entry

Security flag: `security` because this exposes the Android notification-listener permission boundary.

Does NOT cover: changing `POST_NOTIFICATIONS` runtime behavior for existing reminders.

- [ ] Add source/constant tests for the entry dialog and options action.
- [ ] Run focused tests and confirm failure.
- [ ] Implement `NotificationAccessStatus.kt`, `MainActivity` dialog, and options action.
- [ ] Rerun focused tests and confirm pass.

### Task 5: Final Verification and Release

- [ ] Run `.\gradlew.bat testDebugUnitTest`.
- [ ] Run `.\gradlew.bat lint`.
- [ ] Run `.\gradlew.bat detekt`.
- [ ] Run a stub scan over modified Kotlin production files.
- [ ] Run `.\gradlew.bat assembleRelease`.
- [ ] Copy newest release APK to `C:\Users\PC\Desktop\python skriptovi\rad.apk` as the final filesystem write.

## Stop Conditions

- Stop if parser tests reveal the notification text shape is too different to safely parse amount/date/place.
- Stop if the Android manifest rejects the notification-listener service registration.
- Stop if existing manual akontacija would be overwritten by the import path.

## Expected Final Behavior

When `rad` has notification-listener access enabled and receives an NLB-like income notification from day 2 through day 10 of the month with place containing `VALJAONICA BAKRA SEVOJNO`, it saves that positive RSD amount as akontacija for that same month if the month has no existing akontacija. On every app entry/resume, missing notification-listener access is surfaced with a dialog that opens Android notification-access settings.
