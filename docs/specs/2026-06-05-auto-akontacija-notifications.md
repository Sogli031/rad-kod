# Auto Akontacija Notifications Design

## Scope

Add a narrow notification-import path for the `rad` app so NLB/mKlik income notifications from Valjaonica can automatically fill the existing monthly advance-payment field.

## Approved Behavior

- Read notification text through Android `NotificationListenerService`, using the same notification-access model as `pare`.
- Process only bank notifications from known NLB/mKlik package names or package names containing `nlb`.
- Parse amount, transaction date/time, transaction type, and place/description from the notification payload.
- Import only if all rules match:
  - transaction type is income / `Priliv`;
  - parsed place contains `VALJAONICA BAKRA SEVOJNO` after normalization;
  - parsed notification date is from the 2nd through the 10th of the month, inclusive;
  - amount is a positive RSD amount;
  - the target payment month has no existing advance payment.
- Target month is the notification's calendar month. A notification dated in July writes July's `advancePaymentNet`.
- Existing manual advance payment is never overwritten.
- A successful import calls the existing `ShiftRepository.setMonthlyAdvancePaymentNet(year, month, amount)` path, preserving payroll formulas and storage behavior.
- On app entry/resume, if Android notification-listener access is not enabled for `rad`, show a dialog with an action that opens the system notification-listener settings screen.

## Non-Goals

- Do not add a finance transaction history to `rad`.
- Do not change payroll calculations, final-payment formulas, export semantics, food spending, credit deductions, or Google Drive backup behavior.
- Do not import final salary payments around the 20th.
- Do not overwrite existing manual or automatic advance-payment values.
- Do not depend on notification debug history for this first `rad` implementation.

## Architecture

- `RadBankNotificationParser` is a pure Kotlin parser/gate helper. It collects notification text, parses NLB-like amount/date/place/type, and returns `ParsedRadBankNotification` plus `RadAdvanceImportCandidate` only for approved advance-payment imports.
- `ShiftRepository.recordAdvancePaymentNotificationImport(...)` is the repository boundary. It checks the existing monthly advance value and writes only when the month is still empty.
- `RadBankNotificationListenerService` filters bank packages, parses the notification, initializes application services if needed, records the import, and posts a confirmation notification only when a new advance value was saved.
- `NotificationAccessStatus` centralizes Android enabled-listener checks and settings intent creation for both `MainActivity` and options UI.

## Failure-Mode Check

- A notification without a parseable amount/date/place is ignored. This is acceptable because the app cannot safely infer a month or amount.
- A Valjaonica final salary notification outside days 2-10 is ignored by design; this avoids writing a later salary payment into akontacija.
- If the Android notification-listener permission is disabled, the app cannot read notifications. The entry dialog makes that visible and gives a direct settings action.

## Testing Strategy

- Parser tests cover Valjaonica income in the dynamic akontacija window, outside-window rejection, non-Valjaonica rejection, expense rejection, and package filtering.
- Repository tests cover first import writing the advance and existing manual value blocking overwrite.
- Text/source tests cover the entry permission dialog constants and manifest registration.
- Verification includes focused unit tests, full `testDebugUnitTest`, `lint`, `detekt`, `assembleRelease`, and final APK copy to `C:\Users\PC\Desktop\python skriptovi\rad.apk`.
