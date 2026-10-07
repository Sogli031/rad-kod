package com.rad.shifttracker.ui.dialozi

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import com.rad.shifttracker.osnova.UI
import com.rad.shifttracker.krv.BLOOD_DONATION_HISTORY_EMPTY_TEXT
import com.rad.shifttracker.krv.BLOOD_DONATION_OPTION_LABEL
import com.rad.shifttracker.krv.BloodDonationReminderScheduleStatus
import com.rad.shifttracker.krv.bloodDonationCountText
import com.rad.shifttracker.krv.bloodDonationDeleteConfirmationText
import com.rad.shifttracker.krv.bloodDonationHistoryDateText
import com.rad.shifttracker.krv.bloodDonationLastDateText
import com.rad.shifttracker.krv.bloodDonationNextDateText
import com.rad.shifttracker.krv.bloodDonationReminderStatusText
import com.rad.shifttracker.krv.parseBloodDonationDateInSelectedMonth
import com.rad.shifttracker.krv.validateBloodDonationLeaveStartDate
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.applyBloodDonationLeave
import com.rad.shifttracker.repo.bloodDonationHistory
import com.rad.shifttracker.repo.bloodDonationLeaveStartDate
import com.rad.shifttracker.repo.deleteBloodDonation
import com.rad.shifttracker.repo.lastBloodDonationDate
import com.rad.shifttracker.repo.recordBloodDonation
import com.rad.shifttracker.ui.rememberRepoValue
import com.rad.shifttracker.ui.FormErrorText
import com.rad.shifttracker.ui.MIN_TOUCH_TARGET
import com.rad.shifttracker.ui.ModernTextField
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.PopupDialog
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.SectionLabel
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.extendedColors
import com.rad.shifttracker.krv.bloodDonationLeaveNextText
import com.rad.shifttracker.krv.bloodDonationLeaveUsageText
import com.rad.shifttracker.repo.lastBloodDonationLeaveUsage

@Composable
internal fun BloodDonationDialog(
    repo: ShiftRepository,
    selectedYear: Int,
    selectedMonth: Int,
    today: LocalDate,
    status: BloodDonationReminderScheduleStatus,
    onDateSaved: () -> Unit,
    onHistoryChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val savedDate = rememberRepoValue(repo) { lastBloodDonationDate }
    val leaveStartDate = rememberRepoValue(repo) { bloodDonationLeaveStartDate }

    PopupDialog(
        title = BLOOD_DONATION_OPTION_LABEL,
        onDismiss = onDismiss,
        scrollable = true
    ) {
        BloodDonationStatusSection(
            repo = repo,
            selectedYear = selectedYear,
            selectedMonth = selectedMonth,
            today = today,
            savedDate = savedDate,
            status = status,
            onDateSaved = onDateSaved,
            onHistoryChanged = onHistoryChanged
        )
        BloodDonationLeaveSection(
            repo = repo,
            selectedYear = selectedYear,
            selectedMonth = selectedMonth,
            leaveStartDate = leaveStartDate
        )
    }
}

@Composable
private fun BloodDonationStatusSection(
    repo: ShiftRepository,
    selectedYear: Int,
    selectedMonth: Int,
    today: LocalDate,
    savedDate: LocalDate?,
    status: BloodDonationReminderScheduleStatus,
    onDateSaved: () -> Unit,
    onHistoryChanged: () -> Unit
) {
    var dayInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var pendingDeleteDate by remember { mutableStateOf<LocalDate?>(null) }

    PopupCard {
        SectionLabel(text = "Status")
        BloodDonationInfoText(text = bloodDonationLastDateText(savedDate))
        BloodDonationInfoText(text = bloodDonationNextDateText(savedDate))
        BloodDonationInfoText(text = bloodDonationCountText(status.donationCount))
        BloodDonationInfoText(text = bloodDonationReminderStatusText(status))

        SectionLabel(text = "Unos datuma")
        DatePartField(
            value = dayInput,
            onValueChange = {
                dayInput = filterBloodDonationDateDigits(it).take(2)
                errorMessage = null
            },
            hintText = "Dan",
            label = "Dan u mesecu",
            isError = errorMessage != null,
            modifier = Modifier.fillMaxWidth()
        )
        errorMessage?.let { FormErrorText(message = it, style = MaterialTheme.typography.bodySmall) }
        RoundedButton(
            text = "SAČUVAJ",
            modifier = Modifier
                .fillMaxWidth()
                .height(UI.saveButtonHeight.dp),
            bgColor = MaterialTheme.colorScheme.error,
            textColor = MaterialTheme.colorScheme.onSurface,
            textStyle = MaterialTheme.typography.labelLarge,
            bold = true,
            onClick = {
                val parsedDate = parseBloodDonationDateInSelectedMonth(
                    dayInput = dayInput,
                    selectedYear = selectedYear,
                    selectedMonth = selectedMonth
                )
                when {
                    parsedDate == null -> errorMessage = "Unesi ispravan datum davanja krvi."
                    parsedDate.isAfter(today) -> errorMessage = "Datum ne može biti u budućnosti."
                    else -> {
                        repo.recordBloodDonation(parsedDate)
                        dayInput = ""
                        errorMessage = null
                        onDateSaved()
                    }
                }
            }
        )
        RoundedButton(
            text = "Istorija",
            modifier = Modifier
                .fillMaxWidth()
                .height(UI.saveButtonHeight.dp),
            bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            textColor = MaterialTheme.colorScheme.onSurface,
            borderColor = MaterialTheme.colorScheme.error,
            textStyle = MaterialTheme.typography.labelLarge,
            bold = true,
            onClick = {
                showHistory = !showHistory
                pendingDeleteDate = null
            }
        )
    }
    if (showHistory) {
        BloodDonationHistorySection(
            history = rememberRepoValue(repo) { bloodDonationHistory },
            pendingDeleteDate = pendingDeleteDate,
            onDeleteClick = { pendingDeleteDate = it },
            onCancelDelete = { pendingDeleteDate = null },
            onConfirmDelete = { date ->
                if (repo.deleteBloodDonation(date)) {
                    pendingDeleteDate = null
                    onHistoryChanged()
                }
            }
        )
    }
}

@Composable
private fun BloodDonationHistorySection(
    history: List<LocalDate>,
    pendingDeleteDate: LocalDate?,
    onDeleteClick: (LocalDate) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: (LocalDate) -> Unit
) {
    PopupCard {
        SectionLabel(text = "Istorija davanja")
        if (history.isEmpty()) {
            BloodDonationInfoText(text = BLOOD_DONATION_HISTORY_EMPTY_TEXT)
        } else {
            history.forEach { date ->
                BloodDonationHistoryRow(
                    date = date,
                    onDeleteClick = { onDeleteClick(date) }
                )
            }
        }
        pendingDeleteDate?.let { date ->
            BloodDonationInfoText(text = bloodDonationDeleteConfirmationText(date))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(UI.buttonHeight.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundedButton(
                    text = "Otkaži",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    onClick = onCancelDelete
                )
                RoundedButton(
                    text = "Obriši",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    bgColor = MaterialTheme.colorScheme.error,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    onClick = { onConfirmDelete(date) }
                )
            }
        }
    }
}

@Composable
private fun BloodDonationHistoryRow(
    date: LocalDate,
    onDeleteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MIN_TOUCH_TARGET),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = bloodDonationHistoryDateText(date),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        RoundedButton(
            text = "X",
            contentDescription = "Ukloni davanje ${bloodDonationHistoryDateText(date)}",
            modifier = Modifier.size(MIN_TOUCH_TARGET),
            bgColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            textColor = MaterialTheme.colorScheme.onSurface,
            borderColor = MaterialTheme.colorScheme.error,
            textStyle = MaterialTheme.typography.labelLarge,
            bold = true,
            onClick = onDeleteClick
        )
    }
}

@Composable
private fun BloodDonationLeaveSection(
    repo: ShiftRepository,
    selectedYear: Int,
    selectedMonth: Int,
    leaveStartDate: LocalDate?
) {
    var leaveDayInput by remember { mutableStateOf("") }
    var leaveErrorMessage by remember { mutableStateOf<String?>(null) }

    PopupCard {
        SectionLabel(text = "Dani odsustva po osnovu davanja krvi")
        val usage = rememberRepoValue(repo) { lastBloodDonationLeaveUsage }
        BloodDonationInfoText(text = bloodDonationLeaveUsageText(usage))
        BloodDonationInfoText(text = bloodDonationLeaveNextText(usage))

        SectionLabel(text = "Prvi dan odsustva")
        DatePartField(
            value = leaveDayInput,
            onValueChange = {
                leaveDayInput = filterBloodDonationDateDigits(it).take(2)
                leaveErrorMessage = null
            },
            hintText = "Dan",
            label = "Dan u mesecu",
            isError = leaveErrorMessage != null,
            modifier = Modifier.fillMaxWidth()
        )
        leaveErrorMessage?.let { FormErrorText(message = it, style = MaterialTheme.typography.bodySmall) }
        BloodDonationLeaveActions(
            repo = repo,
            selectedYear = selectedYear,
            selectedMonth = selectedMonth,
            leaveStartDate = leaveStartDate,
            dayInput = leaveDayInput,
            onError = { leaveErrorMessage = it },
            onClearInputs = {
                leaveDayInput = ""
                leaveErrorMessage = null
            }
        )
    }
}

@Composable
private fun BloodDonationLeaveActions(
    repo: ShiftRepository,
    selectedYear: Int,
    selectedMonth: Int,
    leaveStartDate: LocalDate?,
    dayInput: String,
    onError: (String?) -> Unit,
    onClearInputs: () -> Unit
) {
    RoundedButton(
        text = "UPIŠI DANE",
        modifier = Modifier
            .fillMaxWidth()
            .height(UI.saveButtonHeight.dp),
        bgColor = MaterialTheme.colorScheme.error,
        textColor = MaterialTheme.colorScheme.onSurface,
        textStyle = MaterialTheme.typography.labelLarge,
        bold = true,
        onClick = {
            val parsedDate = parseBloodDonationDateInSelectedMonth(
                dayInput = dayInput,
                selectedYear = selectedYear,
                selectedMonth = selectedMonth
            )
            val validationError = validateBloodDonationLeaveStartDate(
                firstDay = parsedDate,
                previousLeaveStartDate = leaveStartDate
            )
            if (validationError != null) {
                onError(validationError)
            } else if (parsedDate != null) {
                repo.applyBloodDonationLeave(parsedDate)
                onClearInputs()
            }
        }
    )
}

@Composable
private fun BloodDonationInfoText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.extendedColors.textSecondary,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun DatePartField(
    value: String,
    onValueChange: (String) -> Unit,
    hintText: String,
    label: String,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    ModernTextField(
        value = value,
        onValueChange = onValueChange,
        hintText = hintText,
        label = label,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = modifier
    )
}

private fun filterBloodDonationDateDigits(value: String): String {
    return value.filter { it.isDigit() }
}
