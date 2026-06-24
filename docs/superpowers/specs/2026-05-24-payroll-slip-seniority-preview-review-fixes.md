# Payroll Slip Seniority Preview Review Fixes

## Status

Draft spec from code review findings.

## Context

The current review found that payroll-slip reconstruction preview calculates `Neto pre obustava` with live tax, sick-pay base, difficult-work-conditions, additions, and pay-base settings, but it forces `yearsOfService = 0` and `brigadeType = BrigadeType.TROBRIGADA` inside `RepositoryPayQueries.calculatePayrollSlipPreviewNetBeforeDeductionsUnsafe(...)`.

The UI apply path later stores the reconstructed records through `ShiftRepository.applyPayrollSlipReconstruction(...)`, which sets the repository brigade to `TROBRIGADA`. The brigade assumption is therefore consistent with the reconstruction flow. The seniority assumption is not: after apply, normal month calculations use the repository's minuli-rad settings and can include seniority bonus, while the preview shown before apply excludes it.

The review also found one user-visible Serbian copy issue in `SeniorityDialog.kt`: `Beneficirani/uvecani staž` should use the proper diacritic: `Beneficirani/uvećani staž`.

## Goals

- Make payroll-slip preview net before deductions include the same minuli-rad seniority years that the applied reconstructed month will use.
- Keep payroll-slip reconstruction explicitly scoped to trobrigada, matching the existing apply behavior.
- Fix the Serbian microcopy typo without changing dialog layout or minuli-rad rules.
- Add focused regressions so the preview cannot silently drop seniority again.

## Non-Goals

- Do not redesign payroll-slip reconstruction allocation.
- Do not change `applyPayrollSlipReconstruction(...)` replacing selected month data or forcing trobrigada.
- Do not change seniority formulas, benefit/increase factors, JSON keys, import/export behavior, or minuli-rad dialog flow.
- Do not perform broader UI polish in `SeniorityDialog.kt`.

## Required Behavior

### Payroll-Slip Preview

Given:
- `repo.minuliRadStartYearValue` and related seniority settings produce positive years of service for the payroll-slip target month.
- Payroll-slip preview is generated for that target month.

Then:
- `PayrollSlipReconstructionResult.comparison.calculatedNetBeforeDeductions` must include seniority bonus in the same way the reconstructed month summary would include it after applying the preview.
- Existing live settings already used by the preview must remain live: difficult-work-conditions percent, sick-pay base, tax settings, monthly gross additions, and resolved pay bases.
- The calculation must stay read-only: no mutation of repository records, brigade, JSON state, or caches beyond existing accepted read-only behavior.

### Serbian Copy

In `SeniorityDialog.kt`, visible copy must read:

`Beneficirani/uvećani staž se ovde koristi za minuli rad...`

No other text should change unless needed by tests.

## Implementation Notes

- Replace the forced `yearsOfService = 0` in `RepositoryPayQueries.calculatePayrollSlipPreviewNetBeforeDeductionsUnsafe(...)` with `calculateYearsOfServiceUnsafe(month.year, month.month)`.
- Keep `brigadeType = BrigadeType.TROBRIGADA` in that preview path because reconstruction apply currently normalizes the repository to trobrigada.
- Update the existing preview unit test that currently expects `yearsOfService = 0`.
- Add a regression where seniority is configured, a simple payslip preview is generated, and the preview net is greater than an otherwise identical no-seniority preview or equals an expected summary using the calculated seniority years.
- Add or extend a text/source test for the `uvećani` copy.

## Acceptance Tests

Run targeted tests first:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.rad.shifttracker.ShiftRepositoryTest" --tests "com.rad.shifttracker.SeniorityRulesTest" --tests "com.rad.shifttracker.PayDetailsDialogTextTest" --tests "com.rad.shifttracker.DayEditDialogTextTest"
```

Then run the full repo gates:

```powershell
.\gradlew.bat testDebugUnitTest lint detekt
```

Expected result: all commands pass.

## Review Checklist

- Payroll preview includes minuli rad when repository settings say it should.
- No payroll-slip preview mutation is introduced.
- Existing trobrigada reconstruction assumption remains explicit.
- Serbian copy uses `uvećani`.
- No unrelated UI layout, backup, JSON, or payroll formula changes are included.
