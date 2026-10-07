package com.rad.shifttracker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.rad.shifttracker.smene.Shift

/** Boja smene u zadatoj temi (za proračune van kompozicije, npr. ton ćelije kalendara). */
fun Shift.colorIn(colors: ExtendedColors): Color = when (this) {
    Shift.OFF -> colors.off
    Shift.FIRST -> colors.shift1
    Shift.SECOND -> colors.shift2
    Shift.THIRD -> colors.shift3
    Shift.SICK -> colors.sick
    Shift.VACATION -> colors.vacation
}

val Shift.color: Color
    @Composable
    @ReadOnlyComposable
    get() = colorIn(MaterialTheme.extendedColors)

/** Tekst na punoj boji smene: tamni na svetlim smenama, svetli na ostalim. */
val Shift.textColor: Color
    @Composable
    @ReadOnlyComposable
    get() = when (this) {
        Shift.FIRST, Shift.SECOND, Shift.VACATION -> MaterialTheme.extendedColors.onShiftBright
        else -> MaterialTheme.extendedColors.onShiftDeep
    }
