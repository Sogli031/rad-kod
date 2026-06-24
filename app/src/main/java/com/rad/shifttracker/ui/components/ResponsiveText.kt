package com.rad.shifttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ResponsiveInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelWeight: Float = 0.35f,
    valueWeight: Float = 0.65f,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    valueStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueFontWeight: FontWeight = FontWeight.Medium,
    valueMaxLines: Int = 1
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(labelWeight),
            style = labelStyle,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            modifier = Modifier.weight(valueWeight),
            style = valueStyle.copy(fontWeight = valueFontWeight, textAlign = TextAlign.End),
            color = valueColor,
            maxLines = valueMaxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    maxFontSize: TextUnit = 24.sp,
    minFontSize: TextUnit = 12.sp,
    maxLines: Int = 1,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    val configuration = LocalConfiguration.current
    var fontSize by remember(
        text,
        maxFontSize,
        minFontSize,
        maxLines,
        configuration.screenWidthDp,
        configuration.fontScale
    ) { mutableStateOf(maxFontSize) }
    var readyToDraw by remember(
        text,
        maxFontSize,
        minFontSize,
        maxLines,
        configuration.screenWidthDp,
        configuration.fontScale
    ) { mutableStateOf(false) }

    Text(
        text = text,
        modifier = modifier.drawWithContent {
            if (readyToDraw) drawContent()
        },
        color = color,
        fontSize = fontSize,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = style,
        softWrap = false,
        onTextLayout = { result ->
            if (result.didOverflowWidth || result.didOverflowHeight) {
                val newSize = fontSize * 0.9f
                if (newSize >= minFontSize) {
                    fontSize = newSize
                } else {
                    readyToDraw = true
                }
            } else {
                readyToDraw = true
            }
        }
    )
}
