# Payroll Slip Seniority Preview Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-optimized:subagent-driven-development (recommended) or superpowers-optimized:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make payroll-slip reconstruction preview include configured minuli-rad seniority in `Neto pre obustava`, keep the trobrigada reconstruction assumption, and fix the visible Serbian copy typo in the seniority dialog.
**Architecture:** `ShiftRepository` already injects `calculateYearsOfServiceUnsafe(year, month)` into `RepositoryPayQueries`, so the preview path should use that existing dependency instead of forcing `0`. `PayrollSlipReconstructionService` continues delegating preview net calculation to the repository, and `applyPayrollSlipReconstruction(...)` continues normalizing reconstructed records to `BrigadeType.TROBRIGADA`. The UI copy fix stays isolated to the existing `SeniorityDialog.kt` text block and is guarded by a source-text unit test.
**Tech Stack:** Kotlin, Android Jetpack Compose, JUnit4, Gradle wrapper on Windows PowerShell.
**Assumptions:**
- Assumes payroll-slip reconstruction remains trobrigada-only because `applyPayrollSlipReconstruction(...)` already forces `BrigadeType.TROBRIGADA` - will NOT work as a brigade-selection feature.
- Assumes minuli-rad years must come from current repository seniority settings for the target work month - will NOT change seniority formulas, benefit factors, JSON keys, or import/export behavior.
- Assumes the copy change is a typo fix only - will NOT redesign `SeniorityDialog.kt` layout or minuli-rad flow.

---

## Approved Spec Summary

Approved source spec: `docs/superpowers/specs/2026-05-24-payroll-slip-seniority-preview-review-fixes.md`.

Required behavior:
- `PayrollSlipReconstructionResult.comparison.calculatedNetBeforeDeductions` must include configured minuli-rad years for the payroll-slip target month.
- Existing live preview inputs remain live: difficult-work-conditions percent, sick-pay base, tax settings, monthly gross additions, and resolved pay bases.
- Preview calculation remains read-only: no repository record, brigade, JSON, or cache mutation beyond existing accepted read-only behavior.
- `brigadeType = BrigadeType.TROBRIGADA` remains explicit in the preview path because reconstruction apply currently normalizes to trobrigada.
- Visible copy in `SeniorityDialog.kt` must read `Beneficirani/uvećani staž se ovde koristi za minuli rad...`.

## Android UI Design Summary

**Screen Type:** payroll/finance dialog copy and payroll preview calculation surface  
**User Goal:** verify the payslip preview before applying reconstructed records  
**Trust Level:** financial-critical  
**Density:** compact  
**Primary Action:** preview and apply reconstructed month data  
**Visual Hierarchy:** calculated net before deductions first, comparison rows second, explanatory seniority copy third  
**Existing Style:** existing Compose `PopupCard`, `Text`, `THEME`, and source-text test patterns  
**Anti-Patterns to Avoid:** changing payroll formulas beyond seniority inclusion, hiding the trobrigada assumption, broad UI polish, renaming domain terms, or moving dialog layout

## File Structure

- Modify `app/src/test/java/com/rad/shifttracker/ShiftRepositoryTest.kt`: add payroll preview seniority regressions and update the existing live-settings preview expectation to use configured seniority years.
- Modify `app/src/main/java/com/rad/shifttracker/data/repository/RepositoryPayQueries.kt`: replace forced `yearsOfService = 0` in payroll-slip preview summary with the injected target-month seniority calculator.
- Modify `app/src/test/java/com/rad/shifttracker/SeniorityRulesTest.kt`: add a source-text guard for the `Beneficirani/uvećani staž` copy.
- Modify `app/src/main/java/com/rad/shifttracker/SeniorityDialog.kt`: fix only `uvecani` to `uvećani` in the existing info-card string.

## Stop Conditions

- Stop and ask before changing `applyPayrollSlipReconstruction(...)`, brigade normalization, seniority formulas, persistence keys, backup/import code, or dialog layout.
- Stop if the failing preview test shows a different expected seniority year count than `7` for start `2019-02` and target `2026-03`; inspect `calculateKnownStartSeniorityPreview(...)` before changing expectations.
- Stop if Gradle reports unrelated existing failures outside the listed touched files; capture the failure output before continuing.

## Tasks

### Task 1: Add Payroll Preview Seniority Regressions

**Files:**
- Modify: `app/src/test/java/com/rad/shifttracker/ShiftRepositoryTest.kt`

**Security flag:** `none`

**Does NOT cover:** Does not add brigade selection to payroll-slip reconstruction and does not directly test every seniority input mode; it guards the repository preview path by using the existing target-month seniority calculator contract.

- [x] **Step 1: Write failing tests**

In `ShiftRepositoryTest.kt`, update the existing test `payroll slip preview net calculation uses live settings and does not mutate repository state` by adding the seniority settings before `beforeJson` is captured:

```kotlin
repo.minuliRadStartYearValue = 2019
repo.minuliRadStartMonthValue = 2
```

In the same test, change the expected summary argument from:

```kotlin
yearsOfService = 0,
```

to:

```kotlin
yearsOfService = 7,
```

Add this new test near the existing payroll-slip reconstruction tests:

```kotlin
@Test
fun `payroll slip preview net includes configured minuli rad`() {
    repo.grossHourlyRateValue = 1_000.0
    repo.foodPerDayValue = 0.0
    repo.employeeContributionsPercentValue = 0.0
    repo.taxReliefValue = 0.0
    repo.taxPercentValue = 0.0
    val records = listOf(
        DayRecord(2026, 3, 2, Shift.FIRST, 8, holidayOverride = false)
    )
    val additions = MonthlyGrossAdditions(year = 2026, month = 3)

    val withoutSeniority = repo.calculatePayrollSlipPreviewNetBeforeDeductions(
        month = MonthKey(2026, 3),
        records = records,
        additions = additions
    )

    repo.minuliRadStartYearValue = 2019
    repo.minuliRadStartMonthValue = 2
    val withSeniority = repo.calculatePayrollSlipPreviewNetBeforeDeductions(
        month = MonthKey(2026, 3),
        records = records,
        additions = additions
    )

    assertEquals(8_000.0, withoutSeniority, 0.01)
    assertEquals(8_280.0, withSeniority, 0.01)
    assertTrue(withSeniority > withoutSeniority)
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.rad.shifttracker.ShiftRepositoryTest"
```

Expected: FAIL because `calculatePayrollSlipPreviewNetBeforeDeductionsUnsafe(...)` still passes `yearsOfService = 0`, so the preview net does not include minuli rad.

### Task 2: Use Target-Month Seniority In Payroll-Slip Preview

**Files:**
- Modify: `app/src/main/java/com/rad/shifttracker/data/repository/RepositoryPayQueries.kt`
- Test: `app/src/test/java/com/rad/shifttracker/ShiftRepositoryTest.kt`

**Security flag:** `none`

**Does NOT cover:** Does not change `brigadeType = BrigadeType.TROBRIGADA`, monthly gross additions, tax settings, pay-base resolution, repository persistence, or cache invalidation.

- [x] **Step 1: Implement production change**

In `RepositoryPayQueries.calculatePayrollSlipPreviewNetBeforeDeductionsUnsafe(...)`, replace:

```kotlin
yearsOfService = 0,
```

with:

```kotlin
yearsOfService = calculateYearsOfServiceUnsafe(month.year, month.month),
```

Keep this line unchanged:

```kotlin
brigadeType = BrigadeType.TROBRIGADA,
```

- [x] **Step 2: Run focused payroll tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.rad.shifttracker.ShiftRepositoryTest"
```

Expected: PASS. The read-only assertions in `payroll slip preview net calculation uses live settings and does not mutate repository state` must still pass.

### Task 3: Guard And Fix Seniority Dialog Serbian Copy

**Files:**
- Modify: `app/src/test/java/com/rad/shifttracker/SeniorityRulesTest.kt`
- Modify: `app/src/main/java/com/rad/shifttracker/SeniorityDialog.kt`

**Security flag:** `none`

**Does NOT cover:** Does not change seniority rules, dialog layout, switch behavior, inputs, or saved settings.

- [x] **Step 1: Write failing copy test**

In `SeniorityRulesTest.kt`, add this test inside `class SeniorityRulesTest`:

```kotlin
@Test
fun `seniority dialog info copy uses Serbian diacritic`() {
    val source = sourceText("SeniorityDialog.kt")

    assertTrue(source.contains("Beneficirani/uvećani staž se ovde koristi za minuli rad"))
    assertFalse(source.contains("Beneficirani/uvecani staž"))
}
```

Add this helper near the bottom of the same class:

```kotlin
private fun sourceText(fileName: String): String {
    val file = listOf(
        java.io.File("src/main/java/com/rad/shifttracker/$fileName"),
        java.io.File("app/src/main/java/com/rad/shifttracker/$fileName")
    ).firstOrNull { it.exists() }
    return requireNotNull(file) { "Missing source file $fileName" }.readText()
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.rad.shifttracker.SeniorityRulesTest"
```

Expected: FAIL because `SeniorityDialog.kt` still contains `Beneficirani/uvecani staž`.

- [x] **Step 3: Fix only the typo**

In `SeniorityDialog.kt`, change:

```kotlin
text = "Beneficirani/uvecani staž se ovde koristi za minuli rad samo ako tvoja platna lista " +
```

to:

```kotlin
text = "Beneficirani/uvećani staž se ovde koristi za minuli rad samo ako tvoja platna lista " +
```

- [x] **Step 4: Run focused seniority tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.rad.shifttracker.SeniorityRulesTest"
```

Expected: PASS.

### Task 4: Run Acceptance Gates And Review Scope

**Files:**
- Verify: `app/src/test/java/com/rad/shifttracker/ShiftRepositoryTest.kt`
- Verify: `app/src/test/java/com/rad/shifttracker/SeniorityRulesTest.kt`
- Verify: `app/src/test/java/com/rad/shifttracker/PayDetailsDialogTextTest.kt`
- Verify: `app/src/test/java/com/rad/shifttracker/DayEditDialogTextTest.kt`
- Verify: full Gradle gates

**Security flag:** `none`

**Does NOT cover:** Does not perform device/emulator visual inspection; this plan changes one text string and verifies APK build only.

- [x] **Step 1: Run spec-targeted tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.rad.shifttracker.ShiftRepositoryTest" --tests "com.rad.shifttracker.SeniorityRulesTest" --tests "com.rad.shifttracker.PayDetailsDialogTextTest" --tests "com.rad.shifttracker.DayEditDialogTextTest"
```

Expected: PASS.

- [x] **Step 2: Run full repository gates**

Run:

```powershell
.\gradlew.bat testDebugUnitTest lint detekt
```

Expected: PASS.

- [x] **Step 3: Build debug APK**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: PASS and APK produced at `C:\Users\PC\Desktop\programi\rad\app\build\outputs\apk\debug\app-debug.apk`.

- [x] **Step 4: Scope review**

Inspect the touched files and confirm:

```text
RepositoryPayQueries.kt changes only the yearsOfService argument in payroll-slip preview.
ShiftRepositoryTest.kt changes only payroll-slip preview seniority coverage.
SeniorityRulesTest.kt adds only the copy guard.
SeniorityDialog.kt changes only uvecani -> uvećani in the approved text.
No JSON, backup, import/export, seniority formula, brigade apply behavior, or unrelated UI layout files changed.
```

## Expected Final Behavior

- Payroll-slip preview `Neto pre obustava` includes minuli-rad seniority for the target month when repository seniority settings produce positive years.
- Preview remains read-only and keeps the explicit trobrigada reconstruction assumption.
- `SeniorityDialog.kt` displays `Beneficirani/uvećani staž se ovde koristi za minuli rad...`.
- Targeted tests, full Gradle gates, and `assembleDebug` pass.
