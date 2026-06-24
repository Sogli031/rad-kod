# Cross-App Exact Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-optimized:executing-plans or superpowers-optimized:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make notification scheduling in `ciklus`, `rad`, `kalorije`, and `zaduzenja` prefer exact local alarms and use bounded fallback behavior.
**Architecture:** Keep each app's current receiver, repository, and UI flow. Add small scheduler helper functions inside the existing notification modules, then switch existing call sites to those helpers. Verification ends with release APK creation and copy to `C:\Users\PC\Desktop\python skriptovi`.
**Tech Stack:** Kotlin, Android `AlarmManager`, JUnit4, Gradle wrappers, PowerShell copy operations.
**Assumptions:** Exact-alarm permission may be unavailable on Android 12+; fallback must be bounded and visible where existing status surfaces support it. This will not work as a second-level guarantee under all battery modes.

---

## Files

- Modify `C:\Users\PC\Desktop\programi\rad\app\src\main\java\com\rad\shifttracker\BloodDonationReminderScheduler.kt`
- Modify `C:\Users\PC\Desktop\programi\rad\app\src\main\java\com\rad\shifttracker\BloodDonationReminder.kt`
- Modify `C:\Users\PC\Desktop\programi\rad\app\src\test\java\com\rad\shifttracker\BloodDonationReminderTest.kt`
- Modify `C:\Users\PC\Desktop\programi\zaduzenja\android\app\src\main\java\com\zaduzenja\app\Notifications.kt`
- Modify `C:\Users\PC\Desktop\programi\zaduzenja\android\app\src\main\AndroidManifest.xml`
- Modify `C:\Users\PC\Desktop\programi\zaduzenja\android\app\src\test\java\com\zaduzenja\app\NotificationsTest.kt`
- Modify `C:\Users\PC\Desktop\programi\kalorije\app\src\main\java\com\kalorije\app\notification\NotificationHelper.kt`
- Modify `C:\Users\PC\Desktop\programi\kalorije\app\src\main\java\com\kalorije\app\notification\BootReceiver.kt`
- Modify `C:\Users\PC\Desktop\programi\kalorije\app\src\main\AndroidManifest.xml`
- Create `C:\Users\PC\Desktop\programi\kalorije\app\src\test\java\com\kalorije\app\notification\NotificationHelperTest.kt`
- Modify `C:\Users\PC\Desktop\programi\ciklus\app\src\main\java\com\injekcije\app\notifications\Notifications.kt`
- Modify `C:\Users\PC\Desktop\programi\ciklus\app\src\main\java\com\injekcije\app\notifications\BootReceiver.kt`
- Modify `C:\Users\PC\Desktop\programi\ciklus\app\src\main\AndroidManifest.xml`
- Create `C:\Users\PC\Desktop\programi\ciklus\app\src\test\java\com\injekcije\app\notifications\NotificationSchedulingTest.kt`

## Tasks

### Task 1: Lock Desired Scheduling Policy With Tests

**Security flag:** `security`

**Does NOT cover:** live Android permission grant behavior; tests cover pure scheduling decisions and manifest/source contracts.

- [ ] Add failing tests for bounded fallback and exact-preferred scheduling in each affected app.
- [ ] Run focused tests and confirm expected failures.

### Task 2: Implement App-Local Scheduling Helpers

**Security flag:** `security`

**Does NOT cover:** changing the business rules that decide whether a notification is needed.

- [ ] Add helper functions that choose exact alarm scheduling when possible and bounded `setWindow(...)` fallback otherwise.
- [ ] Keep existing receiver actions and pending-intent request codes stable.

### Task 3: Extend Reschedule Triggers

**Security flag:** `security`

**Does NOT cover:** adding new background services or periodic network work.

- [ ] Add `MY_PACKAGE_REPLACED`, `TIME_SET`, and `TIMEZONE_CHANGED` handling where missing and safe.
- [ ] Keep existing boot/app-start rescheduling behavior.

### Task 4: Verify Each Project

**Security flag:** `none`

- [ ] Run focused tests for changed scheduling files.
- [ ] Run each app's normal Gradle verification command.
- [ ] Run stub scan over modified production files.

### Task 5: Build Release APKs

**Security flag:** `none`

- [ ] Run `assembleRelease` in each project root.
- [ ] Copy newest release APKs to:
  - `C:\Users\PC\Desktop\python skriptovi\ciklus.apk`
  - `C:\Users\PC\Desktop\python skriptovi\rad.apk`
  - `C:\Users\PC\Desktop\python skriptovi\kalorije.apk`
  - `C:\Users\PC\Desktop\python skriptovi\zaduzenja.apk`

## Stop Conditions

- Stop before copying an APK if that app's release build fails.
- Stop and reassess if a focused scheduling test fails twice for different reasons.
- Do not weaken `zaduzenja` 08:00-11:00 delivery guard.
