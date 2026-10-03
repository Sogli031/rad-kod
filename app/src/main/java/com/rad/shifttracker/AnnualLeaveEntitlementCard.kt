package com.rad.shifttracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Za sada se godišnji podešava samo ručno; `true` vraća automatski obračun po KU u dijalog. */
internal const val SHOW_ANNUAL_LEAVE_ENTITLEMENT_CARD = false

/**
 * Pravo na godišnji po KU čl. 29: staž i smene aplikacija zna sama, ručno su samo prekidači.
 * Broj sa rešenja ima prednost — [onUseTotal] ga samo predlaže u polje „Ukupno“.
 */
@Composable
internal fun AnnualLeaveEntitlementCard(
    year: Int,
    repo: ShiftRepository,
    enteredTotal: String,
    onUseTotal: (String) -> Unit
) {
    var entitlement by remember(year) { mutableStateOf(repo.getAnnualLeaveEntitlement(year)) }
    val total = entitlement.breakdown.totalDays

    fun update(change: (AnnualLeaveCalculationInput) -> AnnualLeaveCalculationInput) {
        val input = change(entitlement.input)
        repo.setAnnualLeaveCalculation(input, calculateAnnualLeaveDays(input).totalDays)
        entitlement = repo.getAnnualLeaveEntitlement(year)
    }

    PopupCard {
        SectionLabel(text = "Po kolektivnom ugovoru")
        annualLeaveBreakdownRows(entitlement).forEach { (label, days) -> EntitlementRow(label, days) }
        EntitlementRow("Ukupno", "$total dana", bold = true)
        EntitlementNote(annualLeaveSeniorityNote(entitlement))

        val input = entitlement.input
        EntitlementSwitch("Povećani rizik ili beneficirani staž", input.hasIncreasedRiskOrBenefitedSeniority) { on ->
            update { it.copy(hasIncreasedRiskOrBenefitedSeniority = on) }
        }
        EntitlementSwitch("Radno mesto traži bar III stepen", input.requiresAtLeastThirdDegree) { on ->
            update { it.copy(requiresAtLeastThirdDegree = on) }
        }
        EntitlementSwitch("Invalid rada (povreda na radu, prof. bolest)", input.isWorkInvalidFromWorkInjuryOrDisease) { on ->
            update { it.copy(isWorkInvalidFromWorkInjuryOrDisease = on) }
        }
        EntitlementSwitch("Ratni vojni invalid", input.isWarMilitaryInvalid) { on ->
            update { it.copy(isWarMilitaryInvalid = on) }
        }
        EntitlementSwitch("Samohrani roditelj deteta do 14 god.", input.isSingleParentOfChildUnder14) { on ->
            update { it.copy(isSingleParentOfChildUnder14 = on) }
        }
        EntitlementSwitch("Troje ili više dece do 14 god.", input.hasThreeOrMoreChildrenUnder14) { on ->
            update { it.copy(hasThreeOrMoreChildrenUnder14 = on) }
        }

        if (enteredTotal.toDoubleOrNull() != total.toDouble()) {
            if (enteredTotal.isNotBlank()) {
                EntitlementNote("U „Ukupno“ je $enteredTotal. Ako je to broj sa rešenja, proveri prekidače iznad.")
            }
            RoundedButton(
                text = "Upiši $total u „Ukupno“",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = UI.buttonHeight.dp),
                bgColor = THEME.bgCardElevated,
                textColor = THEME.textPrimary,
                borderColor = THEME.glassBorder,
                textStyle = MaterialTheme.typography.labelMedium,
                onClick = { onUseTotal(total.toString()) }
            )
        }
    }
}

@Composable
private fun EntitlementRow(label: String, value: String, bold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            color = THEME.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = if (bold) THEME.vacation else THEME.textPrimary,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
            maxLines = 1
        )
    }
}

@Composable
private fun EntitlementNote(text: String) {
    Text(text = text, color = THEME.textMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun EntitlementSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    LabeledSwitchRow(
        title = label,
        checked = checked,
        onCheckedChange = onCheckedChange,
        titleStyle = MaterialTheme.typography.bodyMedium
    )
}
