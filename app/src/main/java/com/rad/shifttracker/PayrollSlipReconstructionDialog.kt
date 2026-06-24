package com.rad.shifttracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun PayrollSlipReconstructionDialog(
    year: Int,
    month: Int,
    repo: ShiftRepository,
    service: PayrollSlipReconstructionService,
    onDismiss: () -> Unit
) {
    var form by remember(year, month) { mutableStateOf(PayrollSlipFormState.default(year, month)) }
    var result by remember { mutableStateOf<PayrollSlipReconstructionResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun updateField(field: PayrollSlipFormField, rawValue: String) {
        form = form.withField(field, rawValue)
        result = null
        errorMessage = null
    }

    PopupDialog(
        title = PAYROLL_SLIP_DIALOG_TITLE,
        onDismiss = onDismiss,
        widthRatio = UI.popupWidthRatio,
        scrollable = true,
        accent = THEME.accentSecondary
    ) {
        PayrollSlipInputCard("Mesec", PAYROLL_SLIP_MONTH_FIELDS, form, ::updateField)
        PayrollSlipInputCard(PAYROLL_SLIP_HOURS_SECTION, PAYROLL_SLIP_HOUR_FIELDS, form, ::updateField)
        PayrollSlipInputCard(PAYROLL_SLIP_ADDITIONS_SECTION, PAYROLL_SLIP_ADDITION_FIELDS, form, ::updateField)
        result?.let { PayrollSlipPreviewCard(it) }
        errorMessage?.let { PayrollSlipErrorText(it) }
        PayrollSlipDialogActions(
            result = result,
            onPreview = {
                val input = form.toInput(defaultYear = year, defaultMonth = month)
                val errors = input.validationErrors()
                if (errors.isEmpty()) {
                    result = service.reconstruct(repo, input)
                    errorMessage = null
                } else {
                    errorMessage = errors.first()
                }
            },
            onApply = {
                val applied = result?.let { preview -> repo.applyPayrollSlipReconstruction(preview) } ?: 0
                if (applied > 0) {
                    onDismiss()
                } else {
                    errorMessage = "Prvo napravi ispravan predlog."
                }
            },
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun PayrollSlipInputCard(
    title: String,
    fields: List<PayrollSlipFormField>,
    form: PayrollSlipFormState,
    onFieldChange: (PayrollSlipFormField, String) -> Unit
) {
    PopupCard {
        SectionLabel(text = title)
        fields.forEach { field ->
            PayrollSlipNumberInput(
                label = field.label,
                value = form.valueOf(field),
                wholeNumber = field.wholeNumber,
                onValueChange = { raw -> onFieldChange(field, raw) }
            )
        }
    }
}

@Composable
private fun PayrollSlipNumberInput(
    label: String,
    value: String,
    wholeNumber: Boolean,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = THEME.textSecondary,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        ModernTextField(
            value = value,
            onValueChange = onValueChange,
            hintText = "0",
            keyboardOptions = KeyboardOptions(
                keyboardType = if (wholeNumber) KeyboardType.Number else KeyboardType.Decimal
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PayrollSlipPreviewCard(result: PayrollSlipReconstructionResult) {
    PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.accentSecondary) {
        SectionLabel(text = PAYROLL_SLIP_PREVIEW_SECTION)
        result.comparison.rows.forEach { row ->
            PayrollSlipComparisonRowView(row)
        }
        Text(
            text = "$PAYROLL_SLIP_BEFORE_DEDUCTIONS_LABEL: " +
                "${formatMoney2(result.comparison.calculatedNetBeforeDeductions)} ${CONFIG.pay.currency}",
            color = THEME.textPrimary,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        result.warnings.forEach { warning ->
            Text(
                text = warning.message,
                color = THEME.warning,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PayrollSlipComparisonRowView(row: PayrollSlipComparisonRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = row.label,
            color = THEME.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${formatHoursAndMinutes(row.targetMinutes)} / ${formatHoursAndMinutes(row.calculatedMinutes)}",
            color = if (row.isExact) THEME.success else THEME.warning,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PayrollSlipErrorText(message: String) {
    Text(
        text = message,
        color = THEME.danger,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun PayrollSlipDialogActions(
    result: PayrollSlipReconstructionResult?,
    onPreview: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    GlowButton(
        text = PAYROLL_SLIP_CREATE_PREVIEW,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        bgColor = THEME.accentSecondary,
        textColor = THEME.textPrimary,
        onClick = onPreview
    )
    RoundedButton(
        text = PAYROLL_SLIP_APPLY,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        bgColor = if (result == null) THEME.empty else THEME.success,
        textColor = THEME.textPrimary,
        textStyle = MaterialTheme.typography.labelLarge,
        bold = true,
        enabled = result != null && !result.hasBlockingErrors && result.comparison.allHoursExact,
        onClick = onApply
    )
    RoundedButton(
        text = PAYROLL_SLIP_CANCEL,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        bgColor = THEME.bgCardElevated,
        textColor = THEME.textSecondary,
        textStyle = MaterialTheme.typography.labelLarge,
        onClick = onDismiss
    )
}

private data class PayrollSlipFormField(
    val key: PayrollSlipFormKey,
    val label: String,
    val wholeNumber: Boolean = false
)

private enum class PayrollSlipFormKey {
    YEAR,
    MONTH,
    REGULAR,
    NIGHT,
    OVERTIME,
    VACATION,
    SICK,
    HOLIDAY,
    HOLIDAY_REGULAR_WORK,
    SUNDAY,
    HOLIDAY_WORK,
    ABSENCE,
    MEAL,
    REGRESS,
    AWARD,
    KIR,
    OTHER
}

private val PAYROLL_SLIP_MONTH_FIELDS = listOf(
    PayrollSlipFormField(PayrollSlipFormKey.YEAR, "Godina", wholeNumber = true),
    PayrollSlipFormField(PayrollSlipFormKey.MONTH, "Mesec", wholeNumber = true)
)

private val PAYROLL_SLIP_HOUR_FIELDS = listOf(
    PayrollSlipFormField(PayrollSlipFormKey.REGULAR, "Rad u režiji"),
    PayrollSlipFormField(PayrollSlipFormKey.NIGHT, "Noćni rad"),
    PayrollSlipFormField(PayrollSlipFormKey.OVERTIME, "Prekovremeni rad"),
    PayrollSlipFormField(PayrollSlipFormKey.VACATION, "Godišnji"),
    PayrollSlipFormField(PayrollSlipFormKey.SICK, "Bolovanje"),
    PayrollSlipFormField(PayrollSlipFormKey.HOLIDAY, "Državni praznik"),
    PayrollSlipFormField(PayrollSlipFormKey.HOLIDAY_REGULAR_WORK, "Rad u režiji na DP"),
    PayrollSlipFormField(PayrollSlipFormKey.SUNDAY, "Rad nedeljom"),
    PayrollSlipFormField(PayrollSlipFormKey.HOLIDAY_WORK, "Rad na praznik"),
    PayrollSlipFormField(PayrollSlipFormKey.ABSENCE, "Izostanci")
)

private val PAYROLL_SLIP_ADDITION_FIELDS = listOf(
    PayrollSlipFormField(PayrollSlipFormKey.MEAL, "Topli obrok"),
    PayrollSlipFormField(PayrollSlipFormKey.REGRESS, "Regres"),
    PayrollSlipFormField(PayrollSlipFormKey.AWARD, "Godišnja nagrada"),
    PayrollSlipFormField(PayrollSlipFormKey.KIR, "KIR"),
    PayrollSlipFormField(PayrollSlipFormKey.OTHER, "Ostalo bruto")
)

private data class PayrollSlipFormState(
    val year: String,
    val month: String,
    val regular: String = "",
    val night: String = "",
    val overtime: String = "",
    val vacation: String = "",
    val sick: String = "",
    val holiday: String = "",
    val holidayRegularWork: String = "",
    val sunday: String = "",
    val holidayWork: String = "",
    val absence: String = "",
    val meal: String = "",
    val regress: String = "",
    val award: String = "",
    val kir: String = "",
    val other: String = ""
) {
    fun valueOf(field: PayrollSlipFormField): String {
        return when (field.key) {
            PayrollSlipFormKey.YEAR -> year
            PayrollSlipFormKey.MONTH -> month
            PayrollSlipFormKey.REGULAR -> regular
            PayrollSlipFormKey.NIGHT -> night
            PayrollSlipFormKey.OVERTIME -> overtime
            PayrollSlipFormKey.VACATION -> vacation
            PayrollSlipFormKey.SICK -> sick
            PayrollSlipFormKey.HOLIDAY -> holiday
            PayrollSlipFormKey.HOLIDAY_REGULAR_WORK -> holidayRegularWork
            PayrollSlipFormKey.SUNDAY -> sunday
            PayrollSlipFormKey.HOLIDAY_WORK -> holidayWork
            PayrollSlipFormKey.ABSENCE -> absence
            PayrollSlipFormKey.MEAL -> meal
            PayrollSlipFormKey.REGRESS -> regress
            PayrollSlipFormKey.AWARD -> award
            PayrollSlipFormKey.KIR -> kir
            PayrollSlipFormKey.OTHER -> other
        }
    }

    fun withField(field: PayrollSlipFormField, rawValue: String): PayrollSlipFormState {
        val value = rawValue.filterForPayrollNumber(wholeNumber = field.wholeNumber)
        return when (field.key) {
            PayrollSlipFormKey.YEAR -> copy(year = value)
            PayrollSlipFormKey.MONTH -> copy(month = value)
            PayrollSlipFormKey.REGULAR -> copy(regular = value)
            PayrollSlipFormKey.NIGHT -> copy(night = value)
            PayrollSlipFormKey.OVERTIME -> copy(overtime = value)
            PayrollSlipFormKey.VACATION -> copy(vacation = value)
            PayrollSlipFormKey.SICK -> copy(sick = value)
            PayrollSlipFormKey.HOLIDAY -> copy(holiday = value)
            PayrollSlipFormKey.HOLIDAY_REGULAR_WORK -> copy(holidayRegularWork = value)
            PayrollSlipFormKey.SUNDAY -> copy(sunday = value)
            PayrollSlipFormKey.HOLIDAY_WORK -> copy(holidayWork = value)
            PayrollSlipFormKey.ABSENCE -> copy(absence = value)
            PayrollSlipFormKey.MEAL -> copy(meal = value)
            PayrollSlipFormKey.REGRESS -> copy(regress = value)
            PayrollSlipFormKey.AWARD -> copy(award = value)
            PayrollSlipFormKey.KIR -> copy(kir = value)
            PayrollSlipFormKey.OTHER -> copy(other = value)
        }
    }

    fun toInput(defaultYear: Int, defaultMonth: Int): PayrollSlipInput {
        return PayrollSlipInput(
            month = MonthKey(
                year = year.toIntOrNull() ?: defaultYear,
                month = month.toIntOrNull() ?: defaultMonth
            ),
            regularWorkHours = regular.toPayrollSlipDouble(),
            nightHours = night.toPayrollSlipDouble(),
            overtimeHours = overtime.toPayrollSlipDouble(),
            vacationHours = vacation.toPayrollSlipDouble(),
            sickHours = sick.toPayrollSlipDouble(),
            holidayHours = holiday.toPayrollSlipDouble(),
            regularHolidayWorkHours = holidayRegularWork.toPayrollSlipDouble(),
            sundayHours = sunday.toPayrollSlipDouble(),
            holidayWorkHours = holidayWork.toPayrollSlipDouble(),
            absenceHours = absence.toPayrollSlipDouble(),
            mealGross = meal.toPayrollSlipDouble(),
            regressGross = regress.toPayrollSlipDouble(),
            annualAwardGross = award.toPayrollSlipDouble(),
            kirGross = kir.toPayrollSlipDouble(),
            otherGross = other.toPayrollSlipDouble()
        )
    }

    companion object {
        fun default(year: Int, month: Int): PayrollSlipFormState {
            return PayrollSlipFormState(year = year.toString(), month = month.toString())
        }
    }
}

private fun String.filterForPayrollNumber(wholeNumber: Boolean): String {
    return filter { char ->
        char.isDigit() || (!wholeNumber && (char == '.' || char == ','))
    }
}

private fun String.toPayrollSlipDouble(): Double {
    return replace(',', '.').toDoubleOrNull() ?: 0.0
}
