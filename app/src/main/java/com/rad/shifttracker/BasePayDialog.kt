package com.rad.shifttracker

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

// MonthKey nije Bundle-kompatibilan, pa se mape/skupovi za rememberSaveable
// pakuju u liste stringova "yyyy-MM[:vrednost]".
private val hourlyValueChangesSaver = Saver<Map<MonthKey, Double>, ArrayList<String>>(
    save = { map ->
        ArrayList(map.map { (key, value) -> "%04d-%02d:%s".format(key.year, key.month, value.toString()) })
    },
    restore = { saved ->
        saved.mapNotNull { entry ->
            val parts = entry.split(":")
            val month = parseMonthKeyOrNull(parts.getOrNull(0)) ?: return@mapNotNull null
            val value = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            month to value
        }.toMap()
    }
)

private val monthKeySetSaver = Saver<Set<MonthKey>, ArrayList<String>>(
    save = { keys -> ArrayList(keys.map { "%04d-%02d".format(it.year, it.month) }) },
    restore = { saved -> saved.mapNotNull(::parseMonthKeyOrNull).toSet() }
)

private fun parseMonthKeyOrNull(raw: String?): MonthKey? {
    val parts = raw?.split("-") ?: return null
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val month = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return MonthKey(year, month)
}

// Existing pay settings dialog is intentionally grouped; detekt stays active for newly added methods.
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun BasePayDialog(
    year: Int,
    month: Int,
    repo: ShiftRepository,
    onDismiss: () -> Unit
) {
    // rememberSaveable: uneta polja preživljavaju rotaciju/ubijanje procesa.
    var coefficientInput by rememberSaveable { mutableStateOf(formatRateInput(repo.payCoefficientValue)) }
    var hourlyChangeYearInput by rememberSaveable { mutableStateOf(year.toString()) }
    var hourlyChangeMonthInput by rememberSaveable { mutableStateOf(month.toString()) }
    var hourlyChangeValueInput by rememberSaveable {
        mutableStateOf(formatRateInput(repo.getHourlyValueForMonth(year, month)))
    }
    var hourlyChangeDirty by rememberSaveable { mutableStateOf(false) }
    var pendingHourlyValueChanges by rememberSaveable(stateSaver = hourlyValueChangesSaver) {
        mutableStateOf(
            repo.getHourlyValueChanges().associate { change ->
                MonthKey(change.year, change.month) to change.value
            }
        )
    }
    var removedHourlyValueChangeKeys by rememberSaveable(stateSaver = monthKeySetSaver) {
        mutableStateOf(emptySet<MonthKey>())
    }
    val payBaseSourceMonth = remember(year, month) { repo.getPayBaseSourceMonthForWorkMonth(year, month) }
    val monthlyBases = remember(year, month) {
        repo.getMonthlyPayBases(payBaseSourceMonth.year, payBaseSourceMonth.month)
    }
    val defaultPayBaseSelectionMode = remember { repo.defaultPayBaseSelectionModeValue }
    val resolvedBases = remember(year, month) { repo.getResolvedPayBasesForWorkMonth(year, month) }
    val knownPayBaseAnchor = remember { repo.getKnownPayBaseAnchor() }
    val knownPayBaseCalculation = remember(year, month) {
        resolvedBases.sickHourlyBase
            .takeIf { it.source == PayBaseSource.KNOWN_BASE }
            ?.calculation
    }
    val defaultKnownMonth = remember(year, month) { MonthKey(year, month) }
    var payBaseSelectionMode by rememberSaveable {
        mutableStateOf(
            effectivePayBaseSelectionMode(monthlyBases, defaultPayBaseSelectionMode)
                .toVisiblePayBaseSelectionMode(knownPayBaseAnchor)
        )
    }
    val rollingPayBase = remember(year, month, payBaseSelectionMode) {
        if (payBaseSelectionMode == PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS) {
            repo.getRollingPayBaseCalculationForWorkMonth(year, month)
        } else {
            null
        }
    }
    var knownBaseYearInput by rememberSaveable {
        mutableStateOf((knownPayBaseAnchor?.month ?: defaultKnownMonth).year.toString())
    }
    var knownBaseMonthInput by rememberSaveable {
        mutableStateOf((knownPayBaseAnchor?.month ?: defaultKnownMonth).month.toString())
    }
    var knownBaseInput by rememberSaveable {
        mutableStateOf(formatRateInput(knownPayBaseAnchor?.grossHourlyBase ?: resolvedBases.sickHourlyBase.value))
    }
    var contributionsInput by rememberSaveable {
        mutableStateOf(formatRateInput(repo.employeeContributionsPercentValue))
    }
    var taxReliefInput by rememberSaveable { mutableStateOf(formatRateInput(repo.taxReliefValue)) }
    var taxInput by rememberSaveable { mutableStateOf(formatRateInput(repo.taxPercentValue)) }
    var difficultWorkConditionsInput by rememberSaveable {
        mutableStateOf(formatRateInput(repo.difficultWorkConditionsPercentValue))
    }
    var transportDailyAmountInput by rememberSaveable {
        mutableStateOf(formatRateInput(repo.transportDailyAmountValue))
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateSucceeded by remember { mutableStateOf<Boolean?>(null) }
    var isUpdatingTaxParameters by remember { mutableStateOf(false) }
    val taxParameterUpdater = remember { TaxParameterUpdater() }
    val context = LocalContext.current
    val monthlyTaxUpdateStore = remember(context) { MonthlyTaxUpdateStore(context) }
    val scope = rememberCoroutineScope()

    PopupDialog(
        title = "Podešavanja plate",
        onDismiss = onDismiss,
        widthRatio = 0.85f,
        scrollable = true,
        accent = THEME.success
    ) {
        BasePaySectionCard(title = "Unos") {
            LabeledPayInput(
                label = SATNICA_COEFFICIENT_LABEL,
                value = coefficientInput,
                onValueChange = {
                    coefficientInput = filterDecimalInput(it)
                    errorMessage = null
                },
                hintText = "npr. 2.82",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            val coefficientPreview = coefficientInput.toDoubleOrNull() ?: repo.payCoefficientValue
            val hourlyValuePreview = resolveHourlyValueForMonth(year, month, pendingHourlyValueChanges).value
            // Puna simulacija meseca — memoizovano da se ne izvršava na svaki
            // pritisak tastera u bilo kom drugom polju dijaloga.
            val netDifferencePreview = remember(year, month, coefficientPreview, hourlyValuePreview) {
                repo.previewPayCoefficientNetDifference(
                    year = year,
                    month = month,
                    coefficient = coefficientPreview,
                    hourlyValue = hourlyValuePreview
                )
            }
            BasePayPreviewStrip(
                coefficientPreview = coefficientPreview,
                hourlyValuePreview = hourlyValuePreview,
                netDifferencePreview = netDifferencePreview
            )
            LabeledPayInput(
                label = "Doprinosi radnika (%)",
                value = contributionsInput,
                onValueChange = {
                    contributionsInput = filterDecimalInput(it)
                    errorMessage = null
                },
                hintText = "npr. 19.9",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            LabeledPayInput(
                label = "Poreska olakšica",
                value = taxReliefInput,
                onValueChange = {
                    taxReliefInput = filterDecimalInput(it)
                    errorMessage = null
                },
                hintText = "npr. 34221",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            LabeledPayInput(
                label = "Porez (%)",
                value = taxInput,
                onValueChange = {
                    taxInput = filterDecimalInput(it)
                    errorMessage = null
                },
                hintText = "npr. 10",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            LabeledPayInput(
                label = SATNICA_DIFFICULT_WORK_CONDITIONS_LABEL,
                value = difficultWorkConditionsInput,
                onValueChange = {
                    difficultWorkConditionsInput = filterDecimalInput(it)
                    errorMessage = null
                },
                hintText = "npr. 35",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Ako ne postoji, ostavi 0. Računa se kao bruto dodatak na stvarno odrađene sate.",
                color = THEME.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            LabeledPayInput(
                label = SATNICA_TRANSPORT_DAILY_AMOUNT_LABEL,
                value = transportDailyAmountInput,
                onValueChange = {
                    transportDailyAmountInput = filterDecimalInput(it)
                    errorMessage = null
                },
                hintText = SATNICA_TRANSPORT_DAILY_AMOUNT_HINT,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        }

        BasePaySectionCard(title = SATNICA_HOURLY_HISTORY_SECTION_LABEL) {
            Text(
                text = "Unesi godinu i mesec od kog važi nova vrednost sata. Mesec je broj 1-12, a promene se upisuju tek kada pritisneš Sačuvaj.",
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = defaultHourlyHistoryInfoText(),
                color = THEME.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LabeledPayInput(
                    label = SATNICA_HOURLY_HISTORY_YEAR_LABEL,
                    value = hourlyChangeYearInput,
                    onValueChange = {
                        hourlyChangeYearInput = it.filter(Char::isDigit).take(4)
                        hourlyChangeDirty = true
                        errorMessage = null
                    },
                    hintText = "2026",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                LabeledPayInput(
                    label = SATNICA_HOURLY_HISTORY_MONTH_LABEL,
                    value = hourlyChangeMonthInput,
                    onValueChange = {
                        hourlyChangeMonthInput = it.filter(Char::isDigit).take(2)
                        hourlyChangeDirty = true
                        errorMessage = null
                    },
                    hintText = "1",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            LabeledPayInput(
                label = SATNICA_HOURLY_HISTORY_VALUE_LABEL,
                value = hourlyChangeValueInput,
                onValueChange = {
                    hourlyChangeValueInput = filterDecimalInput(it)
                    hourlyChangeDirty = true
                    errorMessage = null
                },
                hintText = "npr. 226",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            RoundedButton(
                text = "Dodaj/promeni",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                bgColor = THEME.bgCardElevated,
                textColor = THEME.textPrimary,
                radius = 12.dp,
                textStyle = MaterialTheme.typography.labelMedium,
                onClick = {
                    val changeYear = hourlyChangeYearInput.toIntOrNull()
                    val changeMonth = hourlyChangeMonthInput.toIntOrNull()
                    val changeValue = hourlyChangeValueInput.toDoubleOrNull()
                    when {
                        changeYear == null || changeYear !in 1900..2200 -> {
                            errorMessage = "Unesi ispravnu godinu promene vrednosti sata."
                        }
                        changeMonth == null || changeMonth !in 1..12 -> {
                            errorMessage = "Unesi ispravan mesec promene vrednosti sata."
                        }
                        changeValue == null || changeValue <= 0.0 -> {
                            errorMessage = "Unesi ispravnu novu vrednost sata."
                        }
                        else -> {
                            val key = MonthKey(changeYear, changeMonth)
                            pendingHourlyValueChanges = pendingHourlyValueChanges + (key to changeValue)
                            removedHourlyValueChangeKeys = removedHourlyValueChangeKeys - key
                            hourlyChangeDirty = false
                            errorMessage = null
                        }
                    }
                }
            )
            val changes = pendingHourlyValueChanges.toSortedMap().map { (key, value) ->
                HourlyValueChange(key.year, key.month, value)
            }
            if (changes.isEmpty()) {
                Text(
                    text = "Nema ručno dodatih promena; koriste se podrazumevane vrednosti 214.5 pre januara 2026. i 226 od januara 2026.",
                    color = THEME.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                for (change in changes) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = satnicaHourlyChangeSummaryText(change),
                            color = THEME.textPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        RoundedButton(
                            text = "Obriši",
                            modifier = Modifier
                                .width(82.dp)
                                .height(34.dp),
                            bgColor = THEME.empty,
                            textColor = THEME.textPrimary,
                            radius = 10.dp,
                            textStyle = MaterialTheme.typography.labelSmall,
                            onClick = {
                                val key = MonthKey(change.year, change.month)
                                pendingHourlyValueChanges = pendingHourlyValueChanges - key
                                removedHourlyValueChangeKeys = removedHourlyValueChangeKeys + key
                                errorMessage = null
                            }
                        )
                    }
                }
            }
        }

        BasePaySectionCard(title = "Osnovice za sve mesece") {
            Text(
                text = "Izbor koji sačuvaš ovde važi za sve mesece, unazad i unapred.",
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            PayBaseVariantSelector(
                selectedMode = payBaseSelectionMode,
                targetYear = payBaseSourceMonth.year,
                targetMonth = payBaseSourceMonth.month,
                calculatedBase = rollingPayBase,
                knownBaseCalculation = knownPayBaseCalculation,
                onKnownClick = {
                    payBaseSelectionMode = PayBaseSelectionMode.KNOWN_BASE
                    errorMessage = null
                },
                onCalculatedClick = {
                    payBaseSelectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
                    errorMessage = null
                }
            )
            if (payBaseSelectionMode == PayBaseSelectionMode.KNOWN_BASE) {
                KnownPayBaseInputSection(
                    knownBaseYearInput = knownBaseYearInput,
                    onKnownBaseYearInputChange = {
                        knownBaseYearInput = it.filter(Char::isDigit).take(4)
                        errorMessage = null
                    },
                    knownBaseMonthInput = knownBaseMonthInput,
                    onKnownBaseMonthInputChange = {
                        knownBaseMonthInput = it.filter(Char::isDigit).take(2)
                        errorMessage = null
                    },
                    knownBaseInput = knownBaseInput,
                    onKnownBaseInputChange = {
                        knownBaseInput = filterDecimalInput(it)
                        errorMessage = null
                    }
                )
            }
            Text(
                text = payBasesSourceSummary(resolvedBases),
                color = THEME.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        BasePaySectionCard(title = "Automatsko ažuriranje") {
            Text(
                text = "Preuzima aktuelne doprinose, poresku olakšicu i porez sa Paragrafa. Koeficijent i vrednost sata ostaju isti.",
                color = THEME.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            RoundedButton(
                text = if (isUpdatingTaxParameters) "Ažuriranje..." else "Ažuriraj",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                bgColor = THEME.success,
                textColor = THEME.textDark,
                radius = 14.dp,
                textStyle = MaterialTheme.typography.labelLarge,
                bold = true,
                enabled = !isUpdatingTaxParameters,
                onClick = {
                    isUpdatingTaxParameters = true
                    updateMessage = "Ažuriranje je u toku..."
                    updateSucceeded = null
                    errorMessage = null
                    scope.launch {
                        val updateDate = LocalDate.now()
                        runCatching { fetchAndApplyLatestTaxParameters(repo, taxParameterUpdater, updateDate) }
                            .onSuccess { parameters ->
                                val contributions = parameters.employeeContributionsPercent
                                contributionsInput = formatRateInput(contributions)
                                taxReliefInput = formatRateInput(parameters.salaryTaxAllowanceRsd)
                                taxInput = formatRateInput(parameters.salaryTaxRatePercent)
                                monthlyTaxUpdateStore.markProcessed(YearMonth.from(updateDate))
                                updateSucceeded = true
                                updateMessage = taxUpdateSuccessMessage(parameters)
                            }
                            .onFailure { error ->
                                updateSucceeded = false
                                updateMessage = "Ažuriranje nije uspelo: ${error.message ?: "proveri internet vezu"}"
                            }
                        isUpdatingTaxParameters = false
                    }
                }
            )
            if (isUpdatingTaxParameters) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = THEME.success,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Skidam najnovije poreske parametre...",
                        color = THEME.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            updateMessage?.let { message ->
                val color = when (updateSucceeded) {
                    true -> THEME.success
                    false -> THEME.danger
                    null -> THEME.textSecondary
                }
                Text(
                    text = message,
                    color = color,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                color = THEME.danger,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        GlowButton(
                text = "Sačuvaj",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                bgColor = THEME.success,
                textColor = THEME.textDark,
                textStyle = MaterialTheme.typography.labelLarge,
                onClick = {
                    val coefficient = coefficientInput.toDoubleOrNull()
                    val changeYear = hourlyChangeYearInput.toIntOrNull()
                    val changeMonth = hourlyChangeMonthInput.toIntOrNull()
                    val changeValue = hourlyChangeValueInput.toDoubleOrNull()
                    val knownBaseYear = knownBaseYearInput.toIntOrNull()
                    val knownBaseMonth = knownBaseMonthInput.toIntOrNull()
                    val knownBase = knownBaseInput.toDoubleOrNull()
                    val contributions = contributionsInput.toDoubleOrNull()
                    val relief = taxReliefInput.toDoubleOrNull()
                    val tax = taxInput.toDoubleOrNull()
                    val difficultWorkConditions = difficultWorkConditionsInput.toDoubleOrNull()
                    val transportDailyAmount = transportDailyAmountInput.toDoubleOrNull()
                    when {
                        coefficient == null || coefficient <= 0.0 -> {
                            errorMessage = "Unesi ispravan koeficijent."
                        }
                        hourlyChangeDirty && (changeYear == null || changeYear !in 1900..2200) -> {
                            errorMessage = "Unesi ispravnu godinu promene vrednosti sata."
                        }
                        hourlyChangeDirty && (changeMonth == null || changeMonth !in 1..12) -> {
                            errorMessage = "Unesi ispravan mesec promene vrednosti sata."
                        }
                        hourlyChangeDirty && (changeValue == null || changeValue <= 0.0) -> {
                            errorMessage = "Unesi ispravnu novu vrednost sata."
                        }
                        payBaseSelectionMode == PayBaseSelectionMode.KNOWN_BASE &&
                            (knownBaseYear == null || knownBaseYear !in 1900..2200) -> {
                            errorMessage = "Unesi ispravnu godinu poznate osnovice."
                        }
                        payBaseSelectionMode == PayBaseSelectionMode.KNOWN_BASE &&
                            (knownBaseMonth == null || knownBaseMonth !in 1..12) -> {
                            errorMessage = "Unesi ispravan mesec poznate osnovice."
                        }
                        payBaseSelectionMode == PayBaseSelectionMode.KNOWN_BASE &&
                            (knownBase == null || knownBase <= 0.0) -> {
                            errorMessage = "Unesi ispravnu poznatu osnovicu naknada."
                        }
                        payBaseSelectionMode == PayBaseSelectionMode.KNOWN_BASE &&
                            knownBaseYear != null &&
                            knownBaseMonth != null &&
                            MonthKey(knownBaseYear, knownBaseMonth) >= MonthKey(year, month) -> {
                            errorMessage = "Mesec poznate osnovice mora biti pre prikazanog meseca."
                        }
                        contributions == null || contributions < 0.0 -> {
                            errorMessage = "Unesi ispravne doprinose."
                        }
                        relief == null || relief < 0.0 -> {
                            errorMessage = "Unesi ispravnu poresku olakšicu."
                        }
                        tax == null || tax < 0.0 -> {
                            errorMessage = "Unesi ispravan porez."
                        }
                        difficultWorkConditions == null ||
                            difficultWorkConditions < 0.0 ||
                            difficultWorkConditions > 100.0 -> {
                            errorMessage = "Unesi procenat otežanih uslova rada od 0 do 100."
                        }
                        transportDailyAmount == null || transportDailyAmount < 0.0 -> {
                            errorMessage = "Unesi ispravan iznos prevoza."
                        }
                        else -> {
                        repo.payCoefficientValue = coefficient
                        val hourlyChangesToSave = if (
                            hourlyChangeDirty &&
                            changeYear != null &&
                            changeMonth != null &&
                            changeValue != null
                        ) {
                            pendingHourlyValueChanges + (MonthKey(changeYear, changeMonth) to changeValue)
                        } else {
                            pendingHourlyValueChanges
                        }
                        for (key in removedHourlyValueChangeKeys) {
                            repo.removeHourlyValueChange(key.year, key.month)
                        }
                        for ((key, value) in hourlyChangesToSave.toSortedMap()) {
                            repo.setHourlyValueChange(key.year, key.month, value)
                        }
                        if (
                            payBaseSelectionMode == PayBaseSelectionMode.KNOWN_BASE &&
                            knownBaseYear != null &&
                            knownBaseMonth != null &&
                            knownBase != null
                        ) {
                            repo.setKnownPayBaseAnchor(knownBaseYear, knownBaseMonth, knownBase)
                        }
                        repo.setDefaultPayBaseSelection(
                            selectionMode = payBaseSelectionMode
                        )
                        repo.employeeContributionsPercentValue = contributions
                        repo.taxReliefValue = relief
                        repo.taxPercentValue = tax
                        repo.difficultWorkConditionsPercentValue = difficultWorkConditions
                        repo.transportDailyAmountValue = transportDailyAmount
                        onDismiss()
                    }
                }
            }
        )
    }
}

@Composable
private fun BasePaySectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    PopupCard(bgColor = THEME.bgCardElevated, borderColor = THEME.success) {
        SectionLabel(text = title)
        content()
    }
}

@Composable
private fun BasePayPreviewStrip(
    coefficientPreview: Double,
    hourlyValuePreview: Double,
    netDifferencePreview: Double
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = THEME.bgCard,
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = satnicaGrossPreviewText(coefficientPreview, hourlyValuePreview),
                color = THEME.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Vrednost sata za prikazani mesec: ${formatRateInput(hourlyValuePreview)} ${CONFIG.pay.currency}",
                color = THEME.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = satnicaNetDifferencePreviewText(netDifferencePreview),
                color = absenceNetDifferenceColor(netDifferencePreview),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun KnownPayBaseInputSection(
    knownBaseYearInput: String,
    onKnownBaseYearInputChange: (String) -> Unit,
    knownBaseMonthInput: String,
    onKnownBaseMonthInputChange: (String) -> Unit,
    knownBaseInput: String,
    onKnownBaseInputChange: (String) -> Unit
) {
    Text(
        text = "Unesi osnovicu sa obračunske liste. Meseci pre sidra se ne skeniraju i ne simuliraju; prazni meseci posle sidra prenose poslednju poznatu osnovicu, a kompletni realni meseci ulaze u dalji obračun.",
        color = THEME.textSecondary,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 5,
        overflow = TextOverflow.Ellipsis
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LabeledPayInput(
            label = "Godina",
            value = knownBaseYearInput,
            onValueChange = onKnownBaseYearInputChange,
            hintText = "2026",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        LabeledPayInput(
            label = "Mesec",
            value = knownBaseMonthInput,
            onValueChange = onKnownBaseMonthInputChange,
            hintText = "1",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
    }
    LabeledPayInput(
        label = "Osnovica naknada bruto/h",
        value = knownBaseInput,
        onValueChange = onKnownBaseInputChange,
        hintText = "npr. 917,81",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
    knownBasePreviewText(knownBaseYearInput, knownBaseMonthInput, knownBaseInput)?.let { preview ->
        Text(
            text = preview,
            color = THEME.textMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LabeledPayInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hintText: String,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
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
            hintText = hintText,
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PayBaseVariantSelector(
    selectedMode: PayBaseSelectionMode,
    targetYear: Int,
    targetMonth: Int,
    calculatedBase: RollingPayBaseCalculation?,
    knownBaseCalculation: RollingPayBaseCalculation?,
    onKnownClick: () -> Unit,
    onCalculatedClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Varijanta osnovice",
            color = THEME.textSecondary,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PayBaseVariantButton(
                label = "Poznata osnovica",
                selected = selectedMode == PayBaseSelectionMode.KNOWN_BASE,
                enabled = true,
                onClick = onKnownClick,
                modifier = Modifier.fillMaxWidth()
            )
            PayBaseVariantButton(
                label = "Realnih + simuliranih",
                selected = selectedMode == PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS,
                enabled = true,
                onClick = onCalculatedClick,
                modifier = Modifier.fillMaxWidth()
            )
        }
        val selectedCalculation = when (selectedMode) {
            PayBaseSelectionMode.KNOWN_BASE -> knownBaseCalculation
            PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS -> calculatedBase
            PayBaseSelectionMode.MANUAL,
            PayBaseSelectionMode.DEFAULT_VALUES -> null
        }
        Text(
            text = calculatedPayBaseForMonthText(targetYear, targetMonth, selectedCalculation?.hourlyBase),
            color = THEME.textPrimary,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = when (selectedMode) {
                PayBaseSelectionMode.KNOWN_BASE -> knownPayBaseSourceDetailsText(knownBaseCalculation)
                PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS -> {
                    if (calculatedBase != null) {
                        "Izračunato iz prethodnih 12 meseci: ${formatMoney2(calculatedBase.hourlyBase)} ${CONFIG.pay.currency}/h"
                    } else {
                        "Proračun iz prethodnih 12 meseci trenutno nije dostupan; koristiće se 918,81 ${CONFIG.pay.currency}/h."
                    }
                }
                PayBaseSelectionMode.MANUAL,
                PayBaseSelectionMode.DEFAULT_VALUES -> ""
            },
            color = THEME.textMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (selectedMode == PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS) {
            Text(
                text = rollingPayBaseSourceDetailsText(calculatedBase),
                color = THEME.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PayBaseVariantButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    RoundedButton(
        text = label,
        modifier = modifier.height(40.dp),
        bgColor = when {
            selected -> THEME.success
            enabled -> THEME.bgCardElevated
            else -> THEME.empty
        },
        textColor = if (selected) THEME.textDark else THEME.textPrimary,
        radius = 12.dp,
        textStyle = MaterialTheme.typography.labelMedium,
        bold = selected,
        enabled = enabled,
        onClick = onClick
    )
}

private fun knownBasePreviewText(yearInput: String, monthInput: String, baseInput: String): String? {
    val year = yearInput.toIntOrNull() ?: return null
    val month = monthInput.toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val base = baseInput.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
    val monthName = MONTH_NAMES.getOrElse(month) { "Mesec" }
    return "Mesec poznate osnovice: $monthName $year; osnovica naknada bruto/h: ${formatPayBaseRateText(base)}"
}

private fun PayBaseSelectionMode.toVisiblePayBaseSelectionMode(anchor: KnownPayBaseAnchor?): PayBaseSelectionMode {
    return when (this) {
        PayBaseSelectionMode.MANUAL -> {
            if (anchor != null) PayBaseSelectionMode.KNOWN_BASE else PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
        }
        PayBaseSelectionMode.DEFAULT_VALUES -> PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
        PayBaseSelectionMode.KNOWN_BASE,
        PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS -> this
    }
}

