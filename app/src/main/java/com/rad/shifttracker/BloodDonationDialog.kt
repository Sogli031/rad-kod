package com.rad.shifttracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
    val savedDate = repo.lastBloodDonationDate
    val leaveStartDate = repo.bloodDonationLeaveStartDate

    PopupDialog(
        title = BLOOD_DONATION_OPTION_LABEL,
        onDismiss = onDismiss,
        widthRatio = 0.85f,
        scrollable = true,
        accent = THEME.danger
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

    PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.danger) {
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
            modifier = Modifier.fillMaxWidth()
        )
        errorMessage?.let { BloodDonationErrorText(it) }
        RoundedButton(
            text = "SAČUVAJ",
            modifier = Modifier
                .fillMaxWidth()
                .height(UI.saveButtonHeight.dp),
            bgColor = THEME.danger,
            textColor = THEME.textPrimary,
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
            bgColor = THEME.bgCardElevated,
            textColor = THEME.textPrimary,
            borderColor = THEME.danger,
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
            history = repo.bloodDonationHistory,
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
    PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.danger) {
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
                horizontalArrangement = Arrangement.spacedBy(UI.defaultSpacing.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundedButton(
                    text = "Otkaži",
                    modifier = Modifier.weight(1f),
                    bgColor = THEME.bgCardElevated,
                    textColor = THEME.textPrimary,
                    borderColor = THEME.glassBorder,
                    textStyle = MaterialTheme.typography.labelLarge,
                    bold = true,
                    onClick = onCancelDelete
                )
                RoundedButton(
                    text = "Obriši",
                    modifier = Modifier.weight(1f),
                    bgColor = THEME.danger,
                    textColor = THEME.textPrimary,
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
            .height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(UI.defaultSpacing.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = bloodDonationHistoryDateText(date),
            color = THEME.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        RoundedButton(
            text = "X",
            modifier = Modifier.size(44.dp),
            bgColor = THEME.bgCardElevated,
            textColor = THEME.textPrimary,
            borderColor = THEME.danger,
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

    PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.danger) {
        SectionLabel(text = "Dani odsustva po osnovu davanja krvi")
        BloodDonationInfoText(text = bloodDonationLeaveLastUsageText(leaveStartDate))
        BloodDonationInfoText(text = bloodDonationLeaveNextUsageText(leaveStartDate))

        SectionLabel(text = "Prvi dan odsustva")
        DatePartField(
            value = leaveDayInput,
            onValueChange = {
                leaveDayInput = filterBloodDonationDateDigits(it).take(2)
                leaveErrorMessage = null
            },
            hintText = "Dan",
            modifier = Modifier.fillMaxWidth()
        )
        leaveErrorMessage?.let { BloodDonationErrorText(it) }
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
        bgColor = THEME.danger,
        textColor = THEME.textPrimary,
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
        color = THEME.textSecondary,
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
    modifier: Modifier = Modifier
) {
    ModernTextField(
        value = value,
        onValueChange = onValueChange,
        hintText = hintText,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun BloodDonationErrorText(message: String) {
    Text(
        text = message,
        color = THEME.danger,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

private fun filterBloodDonationDateDigits(value: String): String {
    return value.filter { it.isDigit() }
}
