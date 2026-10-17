package com.rad.shifttracker.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Centralized icon definitions for the Rad Shift Tracker app.
 * Uses Material Icons Extended library.
 */
object AppIcons {
    val today: ImageVector = Icons.Filled.Today
    val ai: ImageVector = Icons.Filled.AutoAwesome
    val sickLeave: ImageVector = Icons.Filled.LocalHospital
    val pay: ImageVector = Icons.Filled.AttachMoney
    val settings: ImageVector = Icons.Filled.Settings
    val delete: ImageVector = Icons.Filled.DeleteForever
    val yearOverview: ImageVector = Icons.Filled.BarChart
    val sick: ImageVector = Icons.Filled.Healing           // Bolovanje
}
