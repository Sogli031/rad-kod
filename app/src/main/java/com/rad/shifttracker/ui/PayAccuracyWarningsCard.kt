package com.rad.shifttracker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.rad.shifttracker.obracun.PAY_ACCURACY_WARNINGS_TITLE

@Composable
internal fun PayAccuracyWarningsCard(warnings: List<String>) {
    if (warnings.isEmpty()) return
    PopupCard {
        SectionLabel(text = PAY_ACCURACY_WARNINGS_TITLE)
        warnings.forEach { warning ->
            Text(
                text = warning,
                color = MaterialTheme.extendedColors.warning,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
