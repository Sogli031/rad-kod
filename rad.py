#!/usr/bin/env python3
"""
Shift Tracker Application - Modern UI Version 6.0

A beautifully designed work shift tracking app built with Kivy.
Features glassmorphism effects, smooth animations, and modern aesthetics.

Version 6.0 Changes:
- Added VACATION (godišnji odmor) - 100% paid, no food allowance
- Improved COMP TIME (earned hours) system:
  - Auto-deficit when working less than 8h on workdays
  - Negative balance resets to 0 at month end (doesn't carry over)
  - Only positive balance carries to next month
  - Separate from paid overtime
"""

from __future__ import annotations

import json
import calendar
import os
import sys
from datetime import date, datetime, timedelta
from pathlib import Path
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple, Set, Callable, Any, TYPE_CHECKING
from enum import IntEnum
from collections import defaultdict
from functools import cached_property

# Kivy Imports
import kivy
kivy.require('2.0.0')
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.textinput import TextInput
from kivy.uix.scrollview import ScrollView
from kivy.uix.spinner import Spinner
from kivy.uix.widget import Widget
from kivy.uix.checkbox import CheckBox
from kivy.uix.togglebutton import ToggleButton
from kivy.core.window import Window
from kivy.utils import get_color_from_hex
from kivy.metrics import dp, sp
from kivy.graphics import Color, RoundedRectangle, Rectangle, Line, Ellipse
from kivy.clock import Clock
from kivy.animation import Animation
from kivy.properties import NumericProperty


# =============================================================================
# PLATFORM DETECTION AND PATHS
# =============================================================================

def is_android() -> bool:
    """Check if running on Android."""
    try:
        import android
        return True
    except ImportError:
        return False


def get_data_directory() -> Path:
    """Get the appropriate data directory for the current platform."""
    if is_android():
        try:
            from android.storage import app_storage_path
            return Path(app_storage_path())
        except ImportError:
            return Path("/sdcard/ShiftTracker")
    else:
        return Path(__file__).resolve().parent


# =============================================================================
# UI DIMENSIONS CONSTANTS
# =============================================================================

@dataclass(frozen=True)
class UIDimensions:
    """Centralized UI dimension constants."""
    button_height: int = 46
    icon_button_size: int = 44
    save_button_height: int = 55
    card_radius: int = 16
    card_padding: int = 12
    header_height: int = 60
    stat_card_height: int = 80
    total_card_height: int = 70
    comp_time_bar_height: int = 50
    weekday_header_height: int = 32
    day_button_height: int = 68
    bottom_bar_height: int = 70
    popup_width_ratio: float = 0.95
    day_popup_height: int = 620
    auto_popup_height_ratio: float = 0.45
    sick_popup_height_ratio: float = 0.55
    delete_popup_height: int = 280
    hours_popup_height: int = 200
    default_spacing: int = 8
    large_spacing: int = 12
    small_spacing: int = 4
    title_font: int = 22
    large_font: int = 18
    normal_font: int = 16
    small_font: int = 14
    tiny_font: int = 11
    day_font: int = 12


UI = UIDimensions()


# =============================================================================
# MODERN THEME CONFIGURATION
# =============================================================================

@dataclass(frozen=True)
class ModernTheme:
    """Modern graphite theme with soft neon accents."""
    bg_dark: str = "#0B0F14"
    bg_primary: str = "#101826"
    bg_secondary: str = "#0E1622"
    bg_card: str = "#121A26"
    bg_card_elevated: str = "#182234"
    glass_bg: str = "#121A26"
    glass_border: str = "#2A3446"
    accent_primary: str = "#2DE2E6"
    accent_secondary: str = "#FCA311"
    success: str = "#00C853"
    warning: str = "#FFB300"
    danger: str = "#FF5252"
    info: str = "#40C4FF"
    text_primary: str = "#F8FAFC"
    text_secondary: str = "#C7CEDB"
    text_muted: str = "#7F8AA3"
    text_dark: str = "#0B0F14"
    shadow: str = "#000000"
    shift_1: str = "#00C2FF"
    shift_2: str = "#7AE582"
    shift_3: str = "#FF7AB6"
    sick: str = "#FF5252"
    vacation: str = "#FF8F00"
    off: str = "#424242"
    empty: str = "#2A3347"
    double_shift: str = "#FF9100"
    comp_time: str = "#00BFA5"
    comp_time_used: str = "#FF6E40"
    comp_time_deficit: str = "#FF5252"  # Red for deficit
    weekend_ot: str = "#FFB300"
    sunday: str = "#FF9100"


@dataclass(frozen=True)
class PayConfig:
    """Pay calculation configuration."""
    default_base_pay: float = 300.0
    default_hours: int = 8
    shift_3_premium: float = 1.41
    overtime_premium: float = 1.41
    sunday_bonus: float = 0.25
    sick_pay_rate: float = 0.65
    vacation_pay_rate: float = 1.0  # 100% for vacation
    holiday_bonus: float = 1.23  # +123% for working on holidays
    currency: str = "RSD"
    food_per_day: float = 280.0
    minuli_rad_per_year: float = 0.005  # 0.5% per year of service


@dataclass(frozen=True)
class AppConfig:
    """Main application configuration."""
    data_file: Path = field(default_factory=lambda: get_data_directory() / "work_shifts.json")
    theme: ModernTheme = field(default_factory=ModernTheme)
    pay: PayConfig = field(default_factory=PayConfig)
    weekly_shift_cycle: Tuple[int, ...] = (3, 2, 1)
    autosave_delay: float = 0.3


WEEKDAY_NAMES: Tuple[str, ...] = ("PON", "UTO", "SRE", "ČET", "PET", "SUB", "NED")
MONTH_NAMES: Tuple[str, ...] = (
    "", "Januar", "Februar", "Mart", "April", "Maj", "Jun",
    "Jul", "Avgust", "Septembar", "Oktobar", "Novembar", "Decembar"
)

# Serbian public holidays (fixed dates) - names kept ASCII for compatibility
FIXED_HOLIDAYS: Tuple[Tuple[int, int, str], ...] = (
    (1, 1, "Nova godina"),
    (1, 2, "Nova godina"),
    (1, 7, "Bozic"),
    (2, 15, "Dan drzavnosti"),
    (2, 16, "Dan drzavnosti"),
    (2, 17, "Dan drzavnosti"),
    (5, 1, "Praznik rada"),
    (5, 2, "Praznik rada"),
    (11, 11, "Dan primirja"),
)

# Cache for per-year holiday calculations
_HOLIDAY_CACHE: Dict[int, List[Tuple[int, int, str]]] = {}


def _orthodox_easter_sunday(year: int) -> date:
    """Calculate Orthodox Easter Sunday for a given year (Gregorian)."""
    a = year % 4
    b = year % 7
    c = year % 19
    d = (19 * c + 15) % 30
    e = (2 * a + 4 * b - d + 34) % 7
    month = (d + e + 114) // 31
    day = ((d + e + 114) % 31) + 1
    julian_easter = date(year, month, day)
    # Convert from Julian to Gregorian (valid for modern dates)
    return julian_easter + timedelta(days=13)


def get_serbian_holidays(year: int) -> List[Tuple[int, int, str]]:
    """Generate Serbian public holidays for any year."""
    if year in _HOLIDAY_CACHE:
        return _HOLIDAY_CACHE[year]

    holidays: List[Tuple[int, int, str]] = list(FIXED_HOLIDAYS)

    easter = _orthodox_easter_sunday(year)
    easter_related = [
        (easter - timedelta(days=2), "Veliki petak"),
        (easter - timedelta(days=1), "Velika subota"),
        (easter, "Uskrs"),
        (easter + timedelta(days=1), "Uskrsnji ponedeljak"),
    ]

    for easter_date, name in easter_related:
        holidays.append((easter_date.month, easter_date.day, name))

    merged: Dict[Tuple[int, int], List[str]] = defaultdict(list)
    for month, day, name in holidays:
        key = (month, day)
        if name not in merged[key]:
            merged[key].append(name)

    combined = [
        (m, d, " / ".join(names))
        for (m, d), names in merged.items()
    ]
    combined.sort(key=lambda item: (item[0], item[1]))
    _HOLIDAY_CACHE[year] = combined
    return combined


def is_serbian_holiday(year: int, month: int, day: int) -> bool:
    """Check if a date is a Serbian public holiday."""
    holidays = get_serbian_holidays(year)
    return any(m == month and d == day for m, d, _ in holidays)


def get_holiday_name(year: int, month: int, day: int) -> Optional[str]:
    """Get the name of the holiday for a date, or None if not a holiday."""
    holidays = get_serbian_holidays(year)
    for m, d, name in holidays:
        if m == month and d == day:
            return name
    return None

CONFIG = AppConfig()
THEME = CONFIG.theme


# =============================================================================
# ENUMS AND TYPES
# =============================================================================

class Shift(IntEnum):
    """Shift type enumeration."""
    OFF = 0
    FIRST = 1
    SECOND = 2
    THIRD = 3
    SICK = 4
    VACATION = 5  # Godišnji odmor

    @property
    def display_name(self) -> str:
        names = {
            Shift.OFF: "OFF",
            Shift.FIRST: "S1",
            Shift.SECOND: "S2",
            Shift.THIRD: "S3",
            Shift.SICK: "BOL",
            Shift.VACATION: "GO"
        }
        return names[self]

    @property
    def full_name(self) -> str:
        names = {
            Shift.OFF: "Slobodan dan",
            Shift.FIRST: "Prva smena",
            Shift.SECOND: "Druga smena",
            Shift.THIRD: "Treća smena",
            Shift.SICK: "Bolovanje",
            Shift.VACATION: "Godišnji odmor"
        }
        return names[self]

    @property
    def color(self) -> str:
        colors = {
            Shift.OFF: THEME.off,
            Shift.FIRST: THEME.shift_1,
            Shift.SECOND: THEME.shift_2,
            Shift.THIRD: THEME.shift_3,
            Shift.SICK: THEME.sick,
            Shift.VACATION: THEME.vacation
        }
        return colors[self]

    @property
    def text_color(self) -> str:
        dark_text = {Shift.FIRST, Shift.SECOND, Shift.VACATION}
        return THEME.text_dark if self in dark_text else THEME.text_primary

    @property
    def icon(self) -> str:
        return self.display_name

    @classmethod
    def work_shifts(cls) -> List['Shift']:
        """Return list of actual work shifts (not OFF, SICK, or VACATION)."""
        return [cls.FIRST, cls.SECOND, cls.THIRD]

    @classmethod
    def paid_non_work(cls) -> List['Shift']:
        """Return list of paid but non-work shifts."""
        return [cls.SICK, cls.VACATION]


class OvertimeType(IntEnum):
    """Type of overtime compensation."""
    PAID = 0      # Paid overtime - get money
    COMP_TIME = 1  # Comp time - earn hours for later


class Weekday(IntEnum):
    """Weekday enumeration."""
    MONDAY = 0
    TUESDAY = 1
    WEDNESDAY = 2
    THURSDAY = 3
    FRIDAY = 4
    SATURDAY = 5
    SUNDAY = 6

    @property
    def is_weekend(self) -> bool:
        return self >= Weekday.SATURDAY

    @property
    def is_workday(self) -> bool:
        return self <= Weekday.FRIDAY


DayKey = Tuple[int, int, int]
MonthKey = Tuple[int, int]


# =============================================================================
# DOMAIN MODELS
# =============================================================================

@dataclass
class DayRecord:
    """
    Record for a single day's work.
    
    Comp time logic:
    - hours_deficit: hours under 8 on a workday with work shift = auto deficit
    - comp_time_earned: only from double shift with COMP_TIME type
    - comp_hours_used: manually marked hours used from comp bank
    """
    year: int
    month: int
    day: int
    shift: Shift
    hours: int
    shift2: Optional[Shift] = None
    hours2: int = 0
    overtime_type: OvertimeType = OvertimeType.PAID
    comp_hours_used: int = 0
    holiday_work_override: Optional[bool] = None
    holiday_override: Optional[bool] = None
    sick_pay_rate: Optional[float] = None
    _weekday: Optional[Weekday] = field(default=None, repr=False, compare=False)
    _date: Optional[date] = field(default=None, repr=False, compare=False)

    def __post_init__(self) -> None:
        if isinstance(self.shift, int):
            self.shift = Shift(self.shift)
        if self.shift2 is not None and isinstance(self.shift2, int):
            self.shift2 = Shift(self.shift2)
        if isinstance(self.overtime_type, int):
            self.overtime_type = OvertimeType(self.overtime_type)

        self.hours = max(0, min(24, int(self.hours)))
        self.hours2 = max(0, min(24, int(self.hours2)))
        self.comp_hours_used = max(0, min(24, int(self.comp_hours_used)))
        if self.holiday_work_override is not None:
            self.holiday_work_override = bool(self.holiday_work_override)
        if self.holiday_override is not None:
            self.holiday_override = bool(self.holiday_override)

        if self.sick_pay_rate is not None:
            try:
                self.sick_pay_rate = float(self.sick_pay_rate)
            except (TypeError, ValueError):
                self.sick_pay_rate = None
            else:
                if self.sick_pay_rate <= 0:
                    self.sick_pay_rate = None
                elif self.sick_pay_rate > 1:
                    self.sick_pay_rate = 1.0

        if self.shift != Shift.SICK:
            self.sick_pay_rate = None

        self._compute_cache()

    def _compute_cache(self) -> None:
        self._date = date(self.year, self.month, self.day)
        self._weekday = Weekday(self._date.weekday())

    @property
    def date_obj(self) -> date:
        if self._date is None:
            self._compute_cache()
        return self._date

    @property
    def weekday(self) -> Weekday:
        if self._weekday is None:
            self._compute_cache()
        return self._weekday

    @property
    def key(self) -> DayKey:
        return (self.year, self.month, self.day)

    @property
    def month_key(self) -> MonthKey:
        return (self.year, self.month)

    @property
    def is_work_day(self) -> bool:
        return self.shift in Shift.work_shifts() and self.hours > 0

    @property
    def is_double_shift(self) -> bool:
        return (
            self.shift2 is not None
            and self.shift2 in Shift.work_shifts()
            and self.hours2 > 0
        )

    @property
    def is_comp_time_overtime(self) -> bool:
        return self.is_double_shift and self.overtime_type == OvertimeType.COMP_TIME

    @property
    def total_hours(self) -> int:
        """Total hours worked including second shift."""
        return self.hours + (self.hours2 if self.is_double_shift else 0)

    @property
    def comp_time_earned(self) -> int:
        """
        Hours earned as comp time.
        Only from second shift when overtime_type is COMP_TIME.
        """
        if self.is_comp_time_overtime:
            return self.hours2
        return 0

    @property
    def hours_deficit(self) -> int:
        """
        Hours deficit for comp time calculation.
        
        On a WORKDAY (Mon-Fri) with a WORK SHIFT:
        - If primary shift hours < 8, that's a deficit
        - This is AUTOMATIC - no need to manually set
        
        Weekend work doesn't count toward regular hours.
        VACATION and SICK don't create deficit (they're fully covered).
        """
        # Only regular workdays (Mon-Fri) with work shifts count
        if not self.weekday.is_workday:
            return 0
        
        # Only work shifts create deficit
        if self.shift not in Shift.work_shifts():
            return 0
        
        # Calculate deficit from first shift only
        # (second shift is either paid OT or comp time earned)
        regular_expected = CONFIG.pay.default_hours  # 8
        if self.hours < regular_expected:
            return regular_expected - self.hours
        
        return 0

    @property
    def net_comp_change(self) -> int:
        """
        Net change to comp time balance for this day.
        = earned - deficit - used
        
        This can be negative (deficit) or positive (earned).
        """
        return self.comp_time_earned - self.hours_deficit - self.comp_hours_used

    @property
    def is_holiday(self) -> bool:
        """Check if this day is a Serbian public holiday (with manual override)."""
        if self.holiday_override is not None:
            return self.holiday_override
        return is_serbian_holiday(self.year, self.month, self.day)

    @property
    def holiday_name(self) -> Optional[str]:
        """Get the holiday name, respecting manual override."""
        base_name = get_holiday_name(self.year, self.month, self.day)

        if self.holiday_override is False:
            return None
        if self.holiday_override is True and not base_name:
            return "Rucno unet praznik"
        return base_name

    @property
    def worked_on_holiday(self) -> bool:
        """
        Flag indicating the day is treated as work on a holiday.
        Uses manual override when set, otherwise derives from date + work shift.
        """
        if not self.is_holiday:
            return False
        if self.holiday_work_override is not None:
            return self.holiday_work_override
        return (
            self.shift in Shift.work_shifts()
            and self.total_hours > 0
        )

    def to_dict(self) -> dict:
        data = {"shift": self.shift.value, "hours": self.hours}
        if self.is_double_shift:
            data["shift2"] = self.shift2.value
            data["hours2"] = self.hours2
            data["overtime_type"] = self.overtime_type.value
        if self.comp_hours_used > 0:
            data["comp_hours_used"] = self.comp_hours_used
        if self.holiday_work_override is not None:
            data["holiday_work_override"] = self.holiday_work_override
        if self.holiday_override is not None:
            data["holiday_override"] = self.holiday_override
        if self.sick_pay_rate is not None:
            data["sick_pay_rate"] = self.sick_pay_rate
        return data

    @classmethod
    def from_dict(cls, year: int, month: int, day: int, data: dict) -> 'DayRecord':
        shift2 = data.get("shift2")
        if shift2 is not None:
            shift2 = Shift(shift2)
        overtime_type = OvertimeType(data.get("overtime_type", 0))
        return cls(
            year=year, month=month, day=day,
            shift=data.get("shift", 0),
            hours=data.get("hours", 0),
            shift2=shift2,
            hours2=data.get("hours2", 0),
            overtime_type=overtime_type,
            comp_hours_used=data.get("comp_hours_used", 0),
            holiday_work_override=data.get("holiday_work_override"),
            holiday_override=data.get("holiday_override"),
            sick_pay_rate=data.get("sick_pay_rate")
        )


@dataclass
class MonthSummary:
    """Cached monthly summary statistics."""
    year: int
    month: int
    work_hours: int = 0
    overtime_hours: int = 0
    sick_hours: int = 0
    vacation_hours: int = 0
    sunday_hours: int = 0
    night_hours: int = 0
    total_pay: float = 0.0
    work_days: int = 0
    double_shift_days: int = 0
    food_allowance: float = 0.0
    # Comp time breakdown
    comp_time_earned: int = 0    # Hours earned from comp time OT
    comp_time_deficit: int = 0   # Hours under 8 on workdays
    comp_time_used: int = 0      # Hours manually used
    # Net for month (before carry-over logic)
    comp_time_net: int = 0
    # Starting balance for this month (from previous months)
    comp_time_starting: int = 0
    # Ending balance for this month
    comp_time_ending: int = 0
    # Minuli rad (years of service bonus)
    minuli_rad_bonus: float = 0.0
    
    @property
    def total_with_minuli(self) -> float:
        """Total pay including minuli rad bonus."""
        return self.total_pay + self.minuli_rad_bonus

    @property
    def display_name(self) -> str:
        if 1 <= self.month <= 12:
            return f"{MONTH_NAMES[self.month]} {self.year}"
        return f"Mesec {self.month} {self.year}"


# =============================================================================
# PAY CALCULATOR SERVICE
# =============================================================================

class PayCalculator:
    """Service for calculating pay with double shift and comp time support."""

    def __init__(self, config: Optional[PayConfig] = None):
        self.config = config or CONFIG.pay

    def get_sunday_hours_for_shift(
        self, weekday: Weekday, shift: Shift, hours: int
    ) -> int:
        if hours <= 0 or shift in (Shift.SICK, Shift.VACATION):
            return 0

        if weekday == Weekday.SATURDAY:
            if shift == Shift.THIRD:
                return max(0, hours - 2)
            return 0

        if weekday == Weekday.SUNDAY:
            if shift in (Shift.FIRST, Shift.SECOND):
                return hours
            if shift == Shift.THIRD:
                return min(hours, 2)

        return 0

    def get_sunday_hours(self, record: DayRecord) -> int:
        total = self.get_sunday_hours_for_shift(
            record.weekday, record.shift, record.hours
        )

        if record.is_double_shift and not record.is_comp_time_overtime:
            total += self.get_sunday_hours_for_shift(
                record.weekday, record.shift2, record.hours2
            )

        return total

    def get_overtime_hours(self, record: DayRecord) -> int:
        """
        Calculate PAID overtime hours only.
        Comp time overtime doesn't count as paid.
        """
        if record.shift in (Shift.SICK, Shift.OFF, Shift.VACATION):
            return 0

        # For comp time overtime, second shift doesn't count as paid
        if record.is_double_shift and record.is_comp_time_overtime:
            hours = record.hours
        else:
            hours = record.total_hours

        if hours <= 0:
            return 0

        # Weekend: all hours are overtime
        if record.weekday.is_weekend:
            return hours

        # Workday: hours over 8 are overtime
        return max(0, hours - self.config.default_hours)

    def get_food_allowance(self, record: DayRecord) -> float:
        """
        Calculate food allowance.
        - 280 RSD per work day
        - 560 RSD if 12+ hours
        - NO food for SICK or VACATION (not at work)
        """
        if record.shift in (Shift.SICK, Shift.OFF, Shift.VACATION):
            return 0.0

        total_hours = record.total_hours
        if total_hours <= 0:
            return 0.0

        if total_hours >= 12:
            return self.config.food_per_day * 2
        return self.config.food_per_day

    def get_night_hours(self, record: DayRecord) -> int:
        night = 0
        if record.shift == Shift.THIRD:
            night += record.hours
        if record.is_double_shift and record.shift2 == Shift.THIRD:
            night += record.hours2
        return night

    def calculate_daily_pay(self, record: DayRecord, base_rate: float) -> float:
        """Calculate total daily pay."""
        weekday = record.weekday
        
        # Check if it's a paid holiday when not working
        if record.shift == Shift.OFF:
            # Holiday on a workday (Mon-Fri) = paid 100%
            if record.is_holiday and weekday.is_workday:
                return self.config.default_hours * base_rate
            return 0.0
        
        if record.hours <= 0:
            return 0.0

        # Handle sick leave - ONLY on workdays
        if record.shift == Shift.SICK:
            if weekday.is_workday:
                sick_rate = (
                    record.sick_pay_rate
                    if record.sick_pay_rate is not None
                    else self.config.sick_pay_rate
                )
                return record.hours * base_rate * sick_rate
            return 0.0

        # Handle vacation - 100% paid, only on workdays
        if record.shift == Shift.VACATION:
            if weekday.is_workday:
                return record.hours * base_rate * self.config.vacation_pay_rate
            return 0.0

        # Work shifts
        shifts_data: List[Tuple[Shift, int]] = [(record.shift, record.hours)]

        # Only add second shift if PAID overtime
        if record.is_double_shift and not record.is_comp_time_overtime:
            shifts_data.append((record.shift2, record.hours2))

        total_pay = 0.0

        if weekday.is_weekend:
            for shift, hours in shifts_data:
                shift_mult = (
                    self.config.shift_3_premium if shift == Shift.THIRD else 1.0
                )
                pay = hours * base_rate * shift_mult * self.config.overtime_premium
                sunday_hours = self.get_sunday_hours_for_shift(weekday, shift, hours)
                if sunday_hours > 0:
                    pay += (
                        sunday_hours * base_rate * shift_mult
                        * self.config.overtime_premium * self.config.sunday_bonus
                    )
                total_pay += pay
        else:
            regular_hours_remaining = self.config.default_hours

            for shift, hours in shifts_data:
                shift_mult = (
                    self.config.shift_3_premium if shift == Shift.THIRD else 1.0
                )
                regular_hours = min(hours, regular_hours_remaining)
                overtime_hours = hours - regular_hours
                regular_hours_remaining -= regular_hours

                pay = regular_hours * base_rate * shift_mult
                pay += (
                    overtime_hours * base_rate * shift_mult
                    * self.config.overtime_premium
                )
                total_pay += pay

        # Add holiday bonus (+123%) if working on a holiday
        if record.is_holiday:
            total_pay += total_pay * self.config.holiday_bonus

        return total_pay

    def calculate_month_summary(
        self, records: List[DayRecord], base_rate: float,
        starting_comp_balance: int = 0,
        years_of_service: int = 0
    ) -> MonthSummary:
        """Calculate summary statistics for a month."""
        if not records:
            return MonthSummary(year=0, month=0)

        summary = MonthSummary(year=records[0].year, month=records[0].month)
        summary.comp_time_starting = starting_comp_balance

        for record in records:
            if record.shift == Shift.SICK:
                if record.weekday.is_workday:
                    summary.sick_hours += record.hours
                    summary.total_pay += self.calculate_daily_pay(record, base_rate)
            elif record.shift == Shift.VACATION:
                if record.weekday.is_workday:
                    summary.vacation_hours += record.hours
                    summary.total_pay += self.calculate_daily_pay(record, base_rate)
                    # No food for vacation
            elif record.shift == Shift.OFF:
                # Check for paid holiday (OFF on a workday holiday)
                if record.is_holiday and record.weekday.is_workday:
                    summary.total_pay += self.calculate_daily_pay(record, base_rate)
            elif record.shift in Shift.work_shifts() and record.hours > 0:
                summary.work_hours += record.total_hours
                summary.work_days += 1
                summary.overtime_hours += self.get_overtime_hours(record)
                summary.sunday_hours += self.get_sunday_hours(record)
                summary.night_hours += self.get_night_hours(record)
                summary.total_pay += self.calculate_daily_pay(record, base_rate)
                summary.food_allowance += self.get_food_allowance(record)

                if record.is_double_shift:
                    summary.double_shift_days += 1

                # Comp time tracking
                summary.comp_time_earned += record.comp_time_earned
                summary.comp_time_deficit += record.hours_deficit
                summary.comp_time_used += record.comp_hours_used

        # Calculate net comp time change for the month
        summary.comp_time_net = (
            summary.comp_time_earned 
            - summary.comp_time_deficit 
            - summary.comp_time_used
        )

        # Calculate ending balance
        # Starting + net, but if negative, don't carry negative to next month
        summary.comp_time_ending = summary.comp_time_starting + summary.comp_time_net

        # Calculate minuli rad bonus (0.5% per year of service)
        if years_of_service > 0:
            minuli_rate = years_of_service * self.config.minuli_rad_per_year
            summary.minuli_rad_bonus = summary.total_pay * minuli_rate

        return summary


# =============================================================================
# DATA REPOSITORY
# =============================================================================

class ShiftRepository:
    """Repository for shift data persistence and queries."""

    BASE_PAY_KEY = "config_base_pay_hourly"
    INITIAL_COMP_BALANCE_KEY = "initial_comp_balance"
    YEARS_OF_SERVICE_KEY = "years_of_service"

    def __init__(self, filepath: Path, config: AppConfig = CONFIG):
        self.filepath = filepath
        self.config = config
        self.pay_calculator = PayCalculator(config.pay)

        self._records: Dict[DayKey, DayRecord] = {}
        self._by_month: Dict[MonthKey, Set[DayKey]] = defaultdict(set)
        self._base_pay: float = config.pay.default_base_pay
        self._initial_comp_balance: int = 0  # Starting balance before any records
        self._years_of_service: int = 0  # Years of service for minuli rad

        self._summaries: Dict[MonthKey, MonthSummary] = {}
        self._dirty_months: Set[MonthKey] = set()
        self._comp_balance_cache: Dict[MonthKey, int] = {}

        self._save_scheduled = False
        self._dirty = False
        self._on_change_callbacks: List[Callable[[], None]] = []

        self._load()

    def on_change(self, callback: Callable[[], None]) -> None:
        self._on_change_callbacks.append(callback)

    def _notify_change(self) -> None:
        for cb in self._on_change_callbacks:
            try:
                cb()
            except Exception as e:
                print(f"[ShiftRepository] Error in change callback: {e}")

    def _load(self) -> None:
        if not self.filepath.exists():
            return

        try:
            with open(self.filepath, "r", encoding="utf-8") as f:
                data = json.load(f)

            if not isinstance(data, dict):
                return

            self._base_pay = float(
                data.get(self.BASE_PAY_KEY, self.config.pay.default_base_pay)
            )
            self._initial_comp_balance = int(
                data.get(self.INITIAL_COMP_BALANCE_KEY, 0)
            )
            self._years_of_service = int(
                data.get(self.YEARS_OF_SERVICE_KEY, 0)
            )

            for year_str, months in data.items():
                if year_str in (self.BASE_PAY_KEY, self.INITIAL_COMP_BALANCE_KEY, "comp_time_balance"):
                    continue
                if not isinstance(months, dict):
                    continue

                try:
                    year = int(year_str)
                except ValueError:
                    continue

                for month_str, days in months.items():
                    if not isinstance(days, dict):
                        continue
                    try:
                        month = int(month_str)
                    except ValueError:
                        continue

                    for day_str, rec_data in days.items():
                        if not isinstance(rec_data, dict):
                            continue
                        try:
                            day = int(day_str)
                            record = DayRecord.from_dict(year, month, day, rec_data)
                            self._add_to_index(record)
                        except Exception as e:
                            print(f"[ShiftRepository] Skipping invalid record "
                                  f"{year}-{month}-{day}: {e}")

        except json.JSONDecodeError as e:
            print(f"[ShiftRepository] JSON parse error: {e}")
        except Exception as e:
            print(f"[ShiftRepository] Error loading data: {e}")

    def _save_now(self) -> None:
        if not self._dirty:
            return

        try:
            self.filepath.parent.mkdir(parents=True, exist_ok=True)

            data: Dict[str, Any] = {
                self.BASE_PAY_KEY: self._base_pay,
                self.INITIAL_COMP_BALANCE_KEY: self._initial_comp_balance,
                self.YEARS_OF_SERVICE_KEY: self._years_of_service
            }

            for key, record in self._records.items():
                year_str = str(record.year)
                month_str = f"{record.month:02d}"
                day_str = f"{record.day:02d}"

                if year_str not in data:
                    data[year_str] = {}
                if month_str not in data[year_str]:
                    data[year_str][month_str] = {}

                data[year_str][month_str][day_str] = record.to_dict()

            tmp_file = self.filepath.with_suffix(".tmp")
            with open(tmp_file, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2, sort_keys=True, ensure_ascii=False)

            os.replace(tmp_file, self.filepath)
            self._dirty = False
        except Exception as e:
            print(f"[ShiftRepository] Error saving data: {e}")

    def _schedule_save(self) -> None:
        self._dirty = True
        if not self._save_scheduled:
            self._save_scheduled = True
            Clock.schedule_once(
                lambda dt: self._do_scheduled_save(),
                self.config.autosave_delay
            )

    def _do_scheduled_save(self) -> None:
        self._save_scheduled = False
        self._save_now()

    def save(self) -> None:
        self._save_now()

    def _add_to_index(self, record: DayRecord) -> None:
        self._records[record.key] = record
        self._by_month[record.month_key].add(record.key)
        self._invalidate_summary(record.month_key)

    def _remove_from_index(self, record: DayRecord) -> None:
        if record.key in self._records:
            del self._records[record.key]
        self._by_month[record.month_key].discard(record.key)
        if not self._by_month[record.month_key]:
            del self._by_month[record.month_key]
        self._invalidate_summary(record.month_key)

    def _invalidate_summary(self, month_key: MonthKey) -> None:
        """Invalidate this month and all following months' caches."""
        self._dirty_months.add(month_key)
        if month_key in self._summaries:
            del self._summaries[month_key]
        # Clear comp balance cache for this and future months
        self._comp_balance_cache = {
            k: v for k, v in self._comp_balance_cache.items()
            if k < month_key
        }

    def get(self, year: int, month: int, day: int) -> Optional[DayRecord]:
        return self._records.get((year, month, day))

    def set(
        self, year: int, month: int, day: int,
        shift: Shift, hours: int,
        shift2: Optional[Shift] = None, hours2: int = 0,
        overtime_type: OvertimeType = OvertimeType.PAID,
        comp_hours_used: int = 0,
        holiday_work_override: Optional[bool] = None,
        holiday_override: Optional[bool] = None,
        sick_pay_rate: Optional[float] = None
    ) -> bool:
        key = (year, month, day)

        if key in self._records:
            self._remove_from_index(self._records[key])

        if shift is not None:
            if shift != Shift.SICK:
                sick_pay_rate = None
            elif sick_pay_rate is None:
                sick_pay_rate = self.config.pay.sick_pay_rate
            record = DayRecord(
                year, month, day, shift, hours,
                shift2, hours2, overtime_type, comp_hours_used,
                holiday_work_override, holiday_override,
                sick_pay_rate=sick_pay_rate
            )
            self._add_to_index(record)

        self._schedule_save()
        self._notify_change()
        return True

    def delete(self, year: int, month: int, day: int) -> bool:
        key = (year, month, day)
        record = self._records.get(key)
        if record:
            self._remove_from_index(record)
            self._schedule_save()
            self._notify_change()
            return True
        return False

    def delete_month(self, year: int, month: int) -> int:
        month_key = (year, month)
        keys_to_delete = list(self._by_month.get(month_key, []))

        for key in keys_to_delete:
            record = self._records.get(key)
            if record:
                self._remove_from_index(record)

        if keys_to_delete:
            self._schedule_save()
            self._notify_change()

        return len(keys_to_delete)

    def set_batch(
        self,
        records: List[Tuple[int, int, int, Shift, int, Optional[float]]]
    ) -> None:
        for rec in records:
            if len(rec) == 5:
                year, month, day, shift, hours = rec
                sick_pay_rate = None
            else:
                year, month, day, shift, hours, sick_pay_rate = rec
            key = (year, month, day)
            if key in self._records:
                self._remove_from_index(self._records[key])
            if shift is not None:
                if shift != Shift.SICK:
                    sick_pay_rate = None
                elif sick_pay_rate is None:
                    sick_pay_rate = self.config.pay.sick_pay_rate
                record = DayRecord(
                    year, month, day, shift, hours,
                    sick_pay_rate=sick_pay_rate
                )
                self._add_to_index(record)

        self._schedule_save()
        self._notify_change()

    def get_month_records(self, year: int, month: int) -> List[DayRecord]:
        month_key = (year, month)
        keys = self._by_month.get(month_key, set())
        return [self._records[k] for k in keys]

    def get_all_months_sorted(self) -> List[MonthKey]:
        """Get all months that have data, sorted chronologically."""
        return sorted(self._by_month.keys())

    def get_comp_balance_at_month_start(self, year: int, month: int) -> int:
        """
        Calculate comp time balance at the start of a given month.
        
        Logic:
        - Start with initial_comp_balance
        - Go through each month chronologically
        - Add month's net comp change (earned - deficit - used)
        - If balance goes negative, reset to 0 for next month
        - Only positive balances carry forward
        """
        target = (year, month)
        
        # Check cache
        if target in self._comp_balance_cache:
            return self._comp_balance_cache[target]
        
        all_months = self.get_all_months_sorted()
        
        # Filter to months before target
        prior_months = [m for m in all_months if m < target]
        
        balance = self._initial_comp_balance
        
        for m_key in prior_months:
            m_year, m_month = m_key
            records = self.get_month_records(m_year, m_month)
            
            # Calculate net change for this month
            earned = sum(r.comp_time_earned for r in records)
            deficit = sum(r.hours_deficit for r in records)
            used = sum(r.comp_hours_used for r in records)
            
            net = earned - deficit - used
            balance += net
            
            # If negative at month end, reset to 0 for next month
            if balance < 0:
                balance = 0
        
        self._comp_balance_cache[target] = balance
        return balance

    def get_summary(self, year: int, month: int) -> MonthSummary:
        month_key = (year, month)

        if month_key in self._summaries:
            return self._summaries[month_key]

        # Get starting balance for this month
        starting_balance = self.get_comp_balance_at_month_start(year, month)

        records = self.get_month_records(year, month)
        summary = self.pay_calculator.calculate_month_summary(
            records, self._base_pay, starting_balance, self._years_of_service
        )
        summary.year = year
        summary.month = month

        self._summaries[month_key] = summary
        self._dirty_months.discard(month_key)

        return summary

    def get_current_comp_balance(self, year: int, month: int) -> int:
        """Get comp time balance at the end of the given month."""
        summary = self.get_summary(year, month)
        return summary.comp_time_ending

    @property
    def base_pay(self) -> float:
        return self._base_pay

    @base_pay.setter
    def base_pay(self, value: float) -> None:
        if value >= 0:
            self._base_pay = value
            self._summaries.clear()
            self._schedule_save()
            self._notify_change()

    @property
    def initial_comp_balance(self) -> int:
        return self._initial_comp_balance

    @initial_comp_balance.setter
    def initial_comp_balance(self, value: int) -> None:
        self._initial_comp_balance = max(0, value)
        self._summaries.clear()
        self._comp_balance_cache.clear()
        self._schedule_save()
        self._notify_change()

    @property
    def years_of_service(self) -> int:
        return self._years_of_service

    @years_of_service.setter
    def years_of_service(self, value: int) -> None:
        self._years_of_service = max(0, value)
        self._summaries.clear()
        self._schedule_save()
        self._notify_change()


# =============================================================================
# SHIFT SCHEDULER SERVICE
# =============================================================================

class ShiftScheduler:
    """Service for auto-populating shifts."""

    def __init__(
        self,
        cycle: Optional[Tuple[int, ...]] = None,
        default_hours: int = 8
    ):
        self.cycle = cycle or CONFIG.weekly_shift_cycle
        self.default_hours = default_hours

    def get_shift_for_date(
        self, target_date: date, reference_date: date, reference_shift: Shift
    ) -> Optional[Shift]:
        if target_date.weekday() > 4:
            return None

        try:
            ref_idx = list(self.cycle).index(reference_shift.value)
        except ValueError:
            return None

        ref_iso = reference_date.isocalendar()
        target_iso = target_date.isocalendar()

        ref_monday = date.fromisocalendar(ref_iso[0], ref_iso[1], 1)
        target_monday = date.fromisocalendar(target_iso[0], target_iso[1], 1)

        weeks_diff = (target_monday - ref_monday).days // 7
        cycle_idx = (ref_idx + weeks_diff) % len(self.cycle)

        return Shift(self.cycle[cycle_idx])

    def populate_month(
        self, repo: ShiftRepository, year: int, month: int, start_shift: Shift
    ) -> int:
        _, num_days = calendar.monthrange(year, month)

        first_workday = None
        for d in range(1, num_days + 1):
            if date(year, month, d).weekday() <= 4:
                first_workday = date(year, month, d)
                break

        if not first_workday:
            return 0

        records = []
        for d in range(1, num_days + 1):
            target = date(year, month, d)
            shift = self.get_shift_for_date(target, first_workday, start_shift)
            if shift:
                records.append((year, month, d, shift, self.default_hours))

        repo.set_batch(records)
        return len(records)

    def populate_year(
        self, repo: ShiftRepository, year: int, start_shift: Shift
    ) -> int:
        first_workday = None
        for d in range(1, 8):
            dt = date(year, 1, d)
            if dt.weekday() <= 4:
                first_workday = dt
                break

        if not first_workday:
            return 0

        records = []
        for month in range(1, 13):
            _, num_days = calendar.monthrange(year, month)
            for d in range(1, num_days + 1):
                target = date(year, month, d)
                shift = self.get_shift_for_date(target, first_workday, start_shift)
                if shift:
                    records.append((year, month, d, shift, self.default_hours))

        repo.set_batch(records)
        return len(records)

    def apply_sick_range(
        self, repo: ShiftRepository,
        year: int, month: int, start_day: int, end_day: int,
        sick_pay_rate: Optional[float] = None
    ) -> int:
        if start_day > end_day:
            start_day, end_day = end_day, start_day

        _, num_days = calendar.monthrange(year, month)
        records = []

        for d in range(start_day, end_day + 1):
            if 1 <= d <= num_days:
                if date(year, month, d).weekday() <= 4:
                    records.append(
                        (year, month, d, Shift.SICK, self.default_hours, sick_pay_rate)
                    )

        if records:
            repo.set_batch(records)

        return len(records)

    def apply_vacation_range(
        self, repo: ShiftRepository,
        year: int, month: int, start_day: int, end_day: int
    ) -> int:
        """Apply vacation to a range of workdays."""
        if start_day > end_day:
            start_day, end_day = end_day, start_day

        _, num_days = calendar.monthrange(year, month)
        records = []

        for d in range(start_day, end_day + 1):
            if 1 <= d <= num_days:
                if date(year, month, d).weekday() <= 4:
                    records.append((year, month, d, Shift.VACATION, self.default_hours))

        if records:
            repo.set_batch(records)

        return len(records)


# =============================================================================
# MODERN UI COMPONENTS
# =============================================================================

class BackgroundLayer(Widget):
    """Soft gradient background with accent glows."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)

        with self.canvas.before:
            self._base_color = Color(rgba=get_color_from_hex(THEME.bg_dark))
            self._base_rect = Rectangle()
            self._glow_1_color = Color(
                rgba=get_color_from_hex(THEME.accent_primary + "22")
            )
            self._glow_1 = Ellipse()
            self._glow_2_color = Color(
                rgba=get_color_from_hex(THEME.accent_secondary + "1A")
            )
            self._glow_2 = Ellipse()

        self.bind(pos=self._update, size=self._update)

    def _update(self, *args) -> None:
        self._base_rect.pos = self.pos
        self._base_rect.size = self.size

        width, height = self.size
        self._glow_1.size = (width * 1.1, height * 0.7)
        self._glow_1.pos = (self.x - width * 0.25, self.y + height * 0.35)
        self._glow_2.size = (width * 1.0, height * 0.8)
        self._glow_2.pos = (self.x + width * 0.15, self.y - height * 0.2)


class GlassCard(BoxLayout):
    """Modern glass-morphism card with gradient border."""

    def __init__(
        self,
        bg_color: Optional[str] = None,
        border_color: Optional[str] = None,
        radius: int = 16,
        **kwargs
    ):
        self.bg_color = bg_color or THEME.bg_card
        self.border_color = border_color or THEME.glass_border
        self.radius = radius
        super().__init__(**kwargs)

        with self.canvas.before:
            self._shadow_color = Color(
                rgba=get_color_from_hex(THEME.shadow + "55")
            )
            self._shadow = RoundedRectangle(radius=[dp(self.radius + 4)] * 4)
            self._bg_color = Color(rgba=get_color_from_hex(self.bg_color + "E6"))
            self._bg_rect = RoundedRectangle(radius=[dp(self.radius)] * 4)
            self._border_color = Color(rgba=get_color_from_hex(self.border_color + "40"))
            self._border = Line(
                rounded_rectangle=[0, 0, 0, 0, dp(self.radius)],
                width=1.2
            )

        self.bind(pos=self._update, size=self._update)

    def _update(self, *args) -> None:
        shadow_pad = dp(6)
        shadow_offset = dp(2)
        self._shadow.pos = (
            self.x - shadow_pad,
            self.y - shadow_pad - shadow_offset
        )
        self._shadow.size = (
            self.width + shadow_pad * 2,
            self.height + shadow_pad * 2
        )
        self._bg_rect.pos = self.pos
        self._bg_rect.size = self.size
        self._border.rounded_rectangle = [
            self.x, self.y, self.width, self.height, dp(self.radius)
        ]


class GlowButton(Button):
    """Modern glowing button with press animation."""

    _glow_alpha = NumericProperty(0)

    def __init__(
        self,
        bg_color: Optional[str] = None,
        text_color: Optional[str] = None,
        glow_color: Optional[str] = None,
        **kwargs
    ):
        self.bg_hex = bg_color or THEME.accent_primary
        self.text_hex = text_color or THEME.text_dark
        self.glow_hex = glow_color or self.bg_hex
        super().__init__(**kwargs)

        self.background_normal = ''
        self.background_color = (0, 0, 0, 0)
        self.color = get_color_from_hex(self.text_hex)
        self.font_size = sp(UI.small_font)
        self.bold = True

        with self.canvas.before:
            self._glow_color = Color(rgba=get_color_from_hex(self.glow_hex + "00"))
            self._glow = RoundedRectangle(radius=[dp(14)] * 4)
            self._main_color = Color(rgba=get_color_from_hex(self.bg_hex))
            self._main = RoundedRectangle(radius=[dp(12)] * 4)

        self.bind(pos=self._update, size=self._update, _glow_alpha=self._update_glow)
        self.bind(on_press=self._on_press, on_release=self._on_release)

    def _update(self, *args) -> None:
        glow_padding = dp(4)
        self._glow.pos = (self.x - glow_padding, self.y - glow_padding)
        self._glow.size = (self.width + glow_padding * 2, self.height + glow_padding * 2)
        self._main.pos = self.pos
        self._main.size = self.size

    def _update_glow(self, *args) -> None:
        rgba = list(get_color_from_hex(self.glow_hex))
        rgba[3] = self._glow_alpha * 0.4
        self._glow_color.rgba = rgba

    def _on_press(self, *args) -> None:
        Animation(_glow_alpha=1, duration=0.1).start(self)

    def _on_release(self, *args) -> None:
        Animation(_glow_alpha=0, duration=0.3).start(self)


class IconButton(Button):
    """Circular icon button with glow effect."""

    _glow_alpha = NumericProperty(0)

    def __init__(
        self,
        icon_text: str = "",
        bg_color: Optional[str] = None,
        text_color: Optional[str] = None,
        glow_color: Optional[str] = None,
        **kwargs
    ):
        self.bg_hex = bg_color or THEME.accent_primary
        self.text_hex = text_color or THEME.text_dark
        self.glow_hex = glow_color or self.bg_hex

        super().__init__(**kwargs)

        self.text = icon_text
        self.font_size = sp(20)
        self.background_normal = ''
        self.background_color = (0, 0, 0, 0)
        self.color = get_color_from_hex(self.text_hex)
        self.bold = True

        with self.canvas.before:
            self._glow_color = Color(rgba=get_color_from_hex(self.glow_hex + "00"))
            self._glow = Ellipse()
            self._main_color = Color(rgba=get_color_from_hex(self.bg_hex))
            self._main = Ellipse()

        self.bind(pos=self._update, size=self._update, _glow_alpha=self._update_glow)
        self.bind(on_press=self._on_press, on_release=self._on_release)

    def _update(self, *args) -> None:
        glow_padding = dp(4)
        self._glow.pos = (self.x - glow_padding, self.y - glow_padding)
        self._glow.size = (self.width + glow_padding * 2, self.height + glow_padding * 2)
        self._main.pos = self.pos
        self._main.size = self.size

    def _update_glow(self, *args) -> None:
        rgba = list(get_color_from_hex(self.glow_hex))
        rgba[3] = self._glow_alpha * 0.4
        self._glow_color.rgba = rgba

    def _on_press(self, *args) -> None:
        Animation(_glow_alpha=1, duration=0.1).start(self)

    def _on_release(self, *args) -> None:
        Animation(_glow_alpha=0, duration=0.3).start(self)


class RoundedButton(Button):
    """Rounded button with custom background and border."""

    def __init__(
        self,
        bg_color: Optional[str] = None,
        text_color: Optional[str] = None,
        border_color: Optional[str] = None,
        radius: int = 12,
        **kwargs
    ):
        self.bg_hex = bg_color or THEME.bg_card_elevated
        self.text_hex = text_color or THEME.text_primary
        self.border_hex = border_color or THEME.glass_border
        self._corner_radius = dp(radius)
        super().__init__(**kwargs)

        self.background_normal = ''
        self.background_color = (0, 0, 0, 0)
        self.color = get_color_from_hex(self.text_hex)

        with self.canvas.before:
            self._bg_color = Color(rgba=get_color_from_hex(self.bg_hex))
            self._bg_rect = RoundedRectangle(radius=[self._corner_radius] * 4)
            self._border_color = Color(
                rgba=get_color_from_hex(self.border_hex + "80")
            )
            self._border = Line(
                rounded_rectangle=[0, 0, 0, 0, self._corner_radius],
                width=1
            )

        self.bind(pos=self._update, size=self._update)
        self._update()

    def _update(self, *args) -> None:
        self._bg_rect.pos = self.pos
        self._bg_rect.size = self.size
        self._border.rounded_rectangle = [
            self.x, self.y, self.width, self.height, self._corner_radius
        ]

    def set_bg_color(self, hex_color: str) -> None:
        self.bg_hex = hex_color
        self._bg_color.rgba = get_color_from_hex(self.bg_hex)

    def set_text_color(self, hex_color: str) -> None:
        self.text_hex = hex_color
        self.color = get_color_from_hex(self.text_hex)

    def set_border_color(self, hex_color: str) -> None:
        self.border_hex = hex_color
        self._border_color.rgba = get_color_from_hex(self.border_hex + "80")


class ModernDayButton(Button):
    """Modern calendar day button with shift indicators."""

    _highlight = NumericProperty(0)

    def __init__(
        self, day: int, record: Optional[DayRecord], is_today: bool = False,
        year: int = 0, month: int = 0, **kwargs
    ):
        super().__init__(**kwargs)
        self.day = day
        self.record = record
        self.is_today = is_today
        self._year = year
        self._month = month

        self.background_normal = ''
        self.background_color = (0, 0, 0, 0)
        self.size_hint_y = None
        self.height = dp(UI.day_button_height)
        self.font_size = sp(UI.day_font)
        self.markup = True
        self.halign = 'center'
        self.valign = 'middle'

        with self.canvas.before:
            # Today border (bright, thick)
            if is_today:
                self._today_color = Color(rgba=get_color_from_hex(THEME.accent_primary))
                self._today_border = RoundedRectangle(radius=[dp(14)] * 4)

            self._bg_color = Color()
            self._bg = RoundedRectangle(radius=[dp(12)] * 4)
            self._double_indicator_color = Color(rgba=(0, 0, 0, 0))
            self._double_indicator = RoundedRectangle(radius=[dp(4)] * 4)
            self._comp_indicator_color = Color(rgba=(0, 0, 0, 0))
            self._comp_indicator = Ellipse()
            
            # Holiday indicator (top-left corner star/dot)
            self._holiday_indicator_color = Color(rgba=(0, 0, 0, 0))
            self._holiday_indicator = Ellipse()
            
            self._hl_color = Color(rgba=(1, 1, 1, 0))
            self._hl = RoundedRectangle(radius=[dp(12)] * 4)

        self.bind(pos=self._update, size=self._update, _highlight=self._update_highlight)
        self.bind(on_press=self._on_press, on_release=self._on_release)

        self.update_visuals()

    def _update(self, *args) -> None:
        # Today border - slightly larger than background
        if self.is_today:
            border_padding = dp(1)
            self._today_border.pos = (self.x + border_padding, self.y + border_padding)
            self._today_border.size = (self.width - border_padding * 2, self.height - border_padding * 2)
        
        padding = dp(4)  # Inner padding for the actual background
        self._bg.pos = (self.x + padding, self.y + padding)
        self._bg.size = (self.width - padding * 2, self.height - padding * 2)
        self._hl.pos = self._bg.pos
        self._hl.size = self._bg.size

        indicator_height = dp(4)
        self._double_indicator.pos = (self.x + padding + dp(4), self.y + padding + dp(2))
        self._double_indicator.size = (self.width - padding * 2 - dp(8), indicator_height)

        # Comp time indicator (top-right corner dot)
        dot_size = dp(8)
        self._comp_indicator.pos = (
            self.x + self.width - padding - dot_size - dp(4),
            self.y + self.height - padding - dot_size - dp(4)
        )
        self._comp_indicator.size = (dot_size, dot_size)
        
        # Holiday indicator (top-left corner dot)
        self._holiday_indicator.pos = (
            self.x + padding + dp(4),
            self.y + self.height - padding - dot_size - dp(4)
        )
        self._holiday_indicator.size = (dot_size, dot_size)

    def _update_highlight(self, *args) -> None:
        self._hl_color.rgba = (1, 1, 1, self._highlight * 0.15)

    def _on_press(self, *args) -> None:
        if self.day != 0:
            Animation(_highlight=1, duration=0.1).start(self)

    def _on_release(self, *args) -> None:
        Animation(_highlight=0, duration=0.2).start(self)

    def update_visuals(self) -> None:
        if self.day == 0:
            self.text = ""
            self._bg_color.rgba = (0, 0, 0, 0)
            self._double_indicator_color.rgba = (0, 0, 0, 0)
            self._comp_indicator_color.rgba = (0, 0, 0, 0)
            self._holiday_indicator_color.rgba = (0, 0, 0, 0)
            self.disabled = True
            return

        # Check if this day is a holiday (manual override takes precedence)
        is_holiday_day = False
        if self.record and self.record.holiday_override is not None:
            is_holiday_day = self.record.holiday_override
        elif hasattr(self, '_year') and hasattr(self, '_month'):
            is_holiday_day = is_serbian_holiday(self._year, self._month, self.day)

        if self.record:
            shift = self.record.shift
            hours = self.record.hours
            worked_holiday = self.record.worked_on_holiday

            self._bg_color.rgba = get_color_from_hex(shift.color)
            self.color = get_color_from_hex(shift.text_color)

            if shift == Shift.OFF:
                detail = "Praznik" if (is_holiday_day or worked_holiday) else "OFF"
                self._double_indicator_color.rgba = (0, 0, 0, 0)
                self._comp_indicator_color.rgba = (0, 0, 0, 0)
            elif shift == Shift.SICK:
                detail = "BOL"
                self._double_indicator_color.rgba = (0, 0, 0, 0)
                self._comp_indicator_color.rgba = (0, 0, 0, 0)
            elif shift == Shift.VACATION:
                detail = "GO"
                self._double_indicator_color.rgba = (0, 0, 0, 0)
                self._comp_indicator_color.rgba = (0, 0, 0, 0)
            else:
                detail = f"{shift.icon}"
                if self.record.is_double_shift:
                    detail += f"+{self.record.shift2.icon}"
                    total_h = self.record.total_hours
                    detail += f"\n{total_h}h"
                    if self.record.is_comp_time_overtime:
                        self._double_indicator_color.rgba = get_color_from_hex(THEME.comp_time)
                    else:
                        self._double_indicator_color.rgba = get_color_from_hex(
                            self.record.shift2.color
                        )
                else:
                    detail += f"\n{hours}h"
                    self._double_indicator_color.rgba = (0, 0, 0, 0)

                # Comp time indicator (top-right)
                # Red dot for deficit, teal for earned, orange for used
                if self.record.hours_deficit > 0:
                    self._comp_indicator_color.rgba = get_color_from_hex(THEME.comp_time_deficit)
                elif self.record.comp_hours_used > 0:
                    self._comp_indicator_color.rgba = get_color_from_hex(THEME.comp_time_used)
                elif self.record.is_comp_time_overtime:
                    self._comp_indicator_color.rgba = get_color_from_hex(THEME.comp_time)
                else:
                    self._comp_indicator_color.rgba = (0, 0, 0, 0)

                if worked_holiday:
                    detail += "\nPraznik"

            # Holiday indicator (top-left) - yellow dot - based on date or manual flag
            show_holiday_indicator = is_holiday_day or worked_holiday
            if show_holiday_indicator:
                indicator_hex = THEME.warning if is_holiday_day else THEME.info
                self._holiday_indicator_color.rgba = get_color_from_hex(indicator_hex)
            else:
                self._holiday_indicator_color.rgba = (0, 0, 0, 0)

            self.text = f"[b]{self.day}[/b]\n[size=10sp]{detail}[/size]"
        else:
            self._bg_color.rgba = get_color_from_hex(THEME.empty)
            self._double_indicator_color.rgba = (0, 0, 0, 0)
            self._comp_indicator_color.rgba = (0, 0, 0, 0)
            
            # Show holiday indicator even without record
            if is_holiday_day:
                self._holiday_indicator_color.rgba = get_color_from_hex(THEME.warning)
            else:
                self._holiday_indicator_color.rgba = (0, 0, 0, 0)
            
            self.color = get_color_from_hex(THEME.text_muted)
            self.text = f"[b]{self.day}[/b]\n[size=10sp]-[/size]"


class StatCard(GlassCard):
    """Statistics display card."""

    def __init__(
        self, label: str, value: str, color: Optional[str] = None, **kwargs
    ):
        super().__init__(
            orientation='vertical',
            padding=[dp(UI.card_padding), dp(8)],
            spacing=dp(UI.small_spacing),
            size_hint_y=None,
            height=dp(UI.stat_card_height),
            **kwargs
        )

        color = color or THEME.text_primary

        value_lbl = Label(
            text=f"[b]{value}[/b]",
            markup=True,
            font_size=sp(UI.title_font),
            color=get_color_from_hex(color),
            size_hint_y=0.6,
            halign='center',
            valign='middle'
        )
        value_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        label_lbl = Label(
            text=label,
            font_size=sp(UI.tiny_font),
            color=get_color_from_hex(THEME.text_muted),
            size_hint_y=0.4,
            halign='center'
        )
        label_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        self.add_widget(value_lbl)
        self.add_widget(label_lbl)


class CompTimeCard(GlassCard):
    """Card showing comp time balance - simple centered display."""

    def __init__(self, summary: MonthSummary, **kwargs):
        balance = summary.comp_time_ending
        
        # Determine color based on balance
        if balance > 0:
            main_color = THEME.comp_time
        elif balance < 0:
            main_color = THEME.danger
        else:
            main_color = THEME.text_muted

        super().__init__(
            orientation='vertical',
            padding=[dp(UI.card_padding), dp(8)],
            spacing=dp(2),
            size_hint_y=None,
            height=dp(UI.comp_time_bar_height),
            bg_color=main_color + "30",
            border_color=main_color,
            **kwargs
        )

        # Balance value - centered
        sign = "+" if balance > 0 else ""
        balance_lbl = Label(
            text=f"[b]{sign}{balance}h[/b]",
            markup=True,
            font_size=sp(UI.title_font),
            color=get_color_from_hex(main_color),
            size_hint_y=0.6,
            halign='center',
            valign='middle'
        )
        balance_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))
        
        # Label below
        title_lbl = Label(
            text="Zarađeni sati",
            font_size=sp(10),
            color=get_color_from_hex(THEME.text_muted),
            size_hint_y=0.4,
            halign='center'
        )
        title_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))
        
        self.add_widget(balance_lbl)
        self.add_widget(title_lbl)


class ModernPopup(Popup):
    """Modern styled popup."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.background = ''
        self.background_color = get_color_from_hex(THEME.bg_dark + "F0")
        self.separator_color = get_color_from_hex(THEME.accent_primary)
        self.separator_height = dp(2)
        self.title_color = get_color_from_hex(THEME.text_primary)
        self.title_size = sp(UI.large_font)


class ModernSpinner(Spinner):
    """Modern styled spinner."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.background_normal = ''
        self.background_color = (0, 0, 0, 0)
        self.color = get_color_from_hex(THEME.text_primary)
        self.font_size = sp(UI.normal_font)
        self.option_cls = self._create_option_cls()
        self._corner_radius = dp(12)

        with self.canvas.before:
            self._bg_color = Color(
                rgba=get_color_from_hex(THEME.bg_card_elevated)
            )
            self._bg_rect = RoundedRectangle(radius=[self._corner_radius] * 4)
            self._border_color = Color(
                rgba=get_color_from_hex(THEME.glass_border + "80")
            )
            self._border = Line(
                rounded_rectangle=[0, 0, 0, 0, self._corner_radius],
                width=1
            )

        self.bind(pos=self._update_bg, size=self._update_bg)
        self._update_bg()

    def _create_option_cls(self):
        from kivy.uix.spinner import SpinnerOption

        class ModernSpinnerOption(SpinnerOption):
            def __init__(self, **kwargs):
                super().__init__(**kwargs)
                self.background_normal = ''
                self.background_color = get_color_from_hex(THEME.bg_card)       
                self.color = get_color_from_hex(THEME.text_primary)

        return ModernSpinnerOption

    def _update_bg(self, *args) -> None:
        self._bg_rect.pos = self.pos
        self._bg_rect.size = self.size
        self._border.rounded_rectangle = [
            self.x, self.y, self.width, self.height, self._corner_radius
        ]


class ModernTextInput(TextInput):
    """Modern styled text input."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.background_normal = ''
        self.background_active = ''
        self.background_color = (0, 0, 0, 0)
        self.foreground_color = get_color_from_hex(THEME.text_primary)
        self.cursor_color = get_color_from_hex(THEME.accent_primary)
        self.hint_text_color = get_color_from_hex(THEME.text_muted)
        self.font_size = sp(UI.normal_font)
        self.padding = [dp(12), dp(10)]
        self._corner_radius = dp(12)

        with self.canvas.before:
            self._bg_color = Color(
                rgba=get_color_from_hex(THEME.bg_card_elevated)
            )
            self._bg_rect = RoundedRectangle(radius=[self._corner_radius] * 4)
            self._border_color = Color(
                rgba=get_color_from_hex(THEME.glass_border + "80")
            )
            self._border = Line(
                rounded_rectangle=[0, 0, 0, 0, self._corner_radius],
                width=1
            )

        self.bind(
            pos=self._update_bg,
            size=self._update_bg,
            focus=self._update_focus
        )
        self._update_bg()
        self._update_focus()

    def _update_bg(self, *args) -> None:
        self._bg_rect.pos = self.pos
        self._bg_rect.size = self.size
        self._border.rounded_rectangle = [
            self.x, self.y, self.width, self.height, self._corner_radius
        ]

    def _update_focus(self, *args) -> None:
        border_hex = THEME.accent_primary if self.focus else THEME.glass_border
        self._border_color.rgba = get_color_from_hex(border_hex + "A0")


# =============================================================================
# POPUP FACTORY
# =============================================================================

class PopupFactory:
    """Factory for creating application popups."""

    def __init__(self, app: 'ShiftTrackerApp'):
        self.app = app

    def create_hours_picker_popup(
        self,
        title: str,
        current_hours: int,
        on_select: Callable[[int], None],
        accent_color: str = None
    ) -> Popup:
        accent = accent_color or THEME.accent_primary

        popup_content = GridLayout(
            cols=4, spacing=dp(10), padding=dp(15),
            size_hint_y=None, height=dp(120)
        )

        popup = Popup(
            title=title, content=popup_content,
            size_hint=(0.85, None), height=dp(UI.hours_popup_height),
            background='', background_color=get_color_from_hex(THEME.bg_dark + "F5"),
            separator_color=get_color_from_hex(accent),
            title_color=get_color_from_hex(THEME.text_primary),
            title_size=sp(UI.normal_font)
        )

        for h in range(1, 9):
            is_selected = h == current_hours
            h_btn = RoundedButton(
                text=str(h), font_size=sp(22), bold=True,
                bg_color=accent if is_selected else THEME.bg_card,
                text_color=THEME.text_dark if is_selected else THEME.text_primary
            )

            def make_handler(hour: int):
                def handler(btn):
                    on_select(hour)
                    popup.dismiss()
                return handler

            h_btn.bind(on_release=make_handler(h))
            popup_content.add_widget(h_btn)

        return popup


# =============================================================================
# MAIN APPLICATION
# =============================================================================

class ShiftTrackerApp(App):
    """Modern Shift Tracker Application."""

    def build(self) -> Widget:
        self.title = "Shift Tracker"
        Window.clearcolor = get_color_from_hex(THEME.bg_dark)

        if is_android():
            try:
                Window.softinput_mode = 'below_target'
            except Exception:
                pass

        self.repo = ShiftRepository(CONFIG.data_file)
        self.repo.on_change(self._on_data_change)
        self.scheduler = ShiftScheduler()
        self.popup_factory = PopupFactory(self)

        self._current_year = datetime.now().year
        self._current_month = datetime.now().month
        self._active_popups: List[Popup] = []

        root = FloatLayout()
        root.add_widget(BackgroundLayer(size_hint=(1, 1)))

        main_content = BoxLayout(
            orientation='vertical',
            padding=[dp(UI.card_padding), dp(16), dp(UI.card_padding), dp(UI.bottom_bar_height + 10)],
            spacing=dp(UI.large_spacing)
        )

        main_content.add_widget(self._build_header())
        main_content.add_widget(self._build_comp_time_bar())
        main_content.add_widget(self._build_weekday_header())
        main_content.add_widget(self._build_calendar())
        main_content.add_widget(self._build_stats())
        main_content.add_widget(self._build_total_card())

        root.add_widget(main_content)
        root.add_widget(self._build_bottom_bar())

        Window.bind(on_keyboard=self._on_keyboard)

        self.refresh_ui()
        return root

    @property
    def today(self) -> date:
        return date.today()

    @property
    def current_year(self) -> int:
        return self._current_year

    @current_year.setter
    def current_year(self, value: int) -> None:
        self._current_year = value

    @property
    def current_month(self) -> int:
        return self._current_month

    @current_month.setter
    def current_month(self, value: int) -> None:
        self._current_month = value

    def _on_keyboard(self, window, key, scancode, codepoint, modifier) -> bool:
        if key == 27:
            if self._active_popups:
                popup = self._active_popups.pop()
                popup.dismiss()
                return True
        return False

    def _track_popup(self, popup: Popup) -> None:
        self._active_popups.append(popup)
        popup.bind(on_dismiss=lambda p: self._active_popups.remove(p) if p in self._active_popups else None)

    def _build_header(self) -> GlassCard:
        panel = GlassCard(
            size_hint_y=None,
            height=dp(UI.header_height),
            padding=[dp(8), 0]
        )

        box = BoxLayout(orientation='horizontal', spacing=dp(UI.default_spacing))

        btn_prev = IconButton(
            icon_text="<",
            bg_color=THEME.bg_card_elevated,
            text_color=THEME.text_primary,
            size_hint=(None, 1),
            size=(dp(UI.icon_button_size), dp(UI.icon_button_size))
        )
        btn_prev.bind(on_press=self.prev_month)

        self.lbl_date = Label(
            text="",
            font_size=sp(UI.title_font),
            bold=True,
            color=get_color_from_hex(THEME.text_primary),
            size_hint_x=1
        )

        btn_next = IconButton(
            icon_text=">",
            bg_color=THEME.bg_card_elevated,
            text_color=THEME.text_primary,
            size_hint=(None, 1),
            size=(dp(UI.icon_button_size), dp(UI.icon_button_size))
        )
        btn_next.bind(on_press=self.next_month)

        box.add_widget(btn_prev)
        box.add_widget(self.lbl_date)
        box.add_widget(btn_next)

        panel.add_widget(box)
        return panel

    def _build_comp_time_bar(self) -> BoxLayout:
        self.comp_time_container = BoxLayout(
            size_hint_y=None,
            height=dp(UI.comp_time_bar_height)
        )
        return self.comp_time_container

    def _build_weekday_header(self) -> BoxLayout:
        box = BoxLayout(size_hint_y=None, height=dp(UI.weekday_header_height))
        for i, name in enumerate(WEEKDAY_NAMES):
            color = THEME.weekend_ot if i >= 5 else THEME.text_muted
            lbl = Label(
                text=name,
                bold=True,
                font_size=sp(UI.tiny_font),
                color=get_color_from_hex(color)
            )
            box.add_widget(lbl)
        return box

    def _build_calendar(self) -> GlassCard:
        panel = GlassCard(padding=dp(6))

        self.scroll_view = ScrollView(size_hint=(1, 1), do_scroll_x=False)
        self.calendar_grid = GridLayout(
            cols=7,
            spacing=dp(UI.small_spacing),
            size_hint_y=None,
            padding=[dp(2), dp(UI.small_spacing)]
        )
        self.calendar_grid.bind(minimum_height=self.calendar_grid.setter('height'))

        self.scroll_view.add_widget(self.calendar_grid)
        panel.add_widget(self.scroll_view)
        return panel

    def _build_stats(self) -> BoxLayout:
        self.stats_box = BoxLayout(
            orientation='horizontal',
            size_hint_y=None,
            height=dp(UI.stat_card_height),
            spacing=dp(UI.default_spacing)
        )
        return self.stats_box

    def _build_total_card(self) -> GlassCard:
        panel = GlassCard(
            size_hint_y=None,
            height=dp(UI.total_card_height),
            padding=[dp(16), dp(UI.card_padding)],
            bg_color=THEME.bg_card_elevated,
            border_color=THEME.accent_primary
        )

        box = BoxLayout(orientation='horizontal')

        left = BoxLayout(orientation='vertical', size_hint_x=0.4)
        lbl = Label(
            text="Ukupna plata",
            font_size=sp(UI.small_font),
            color=get_color_from_hex(THEME.text_muted),
            halign='left',
            valign='middle'
        )
        lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))
        left.add_widget(lbl)

        self.total_label = Label(
            text="0 RSD",
            font_size=sp(28),
            bold=True,
            color=get_color_from_hex(THEME.accent_primary),
            halign='center',
            valign='middle',
            size_hint_x=0.6
        )
        self.total_label.bind(size=lambda i, s: setattr(i, 'text_size', s))

        box.add_widget(left)
        box.add_widget(self.total_label)
        panel.add_widget(box)
        return panel

    def _build_bottom_bar(self) -> BoxLayout:
        bar = BoxLayout(
            orientation='horizontal',
            size_hint=(1, None),
            height=dp(UI.bottom_bar_height),
            pos_hint={'x': 0, 'y': 0},
            padding=[dp(UI.card_padding), dp(10)],
            spacing=dp(UI.default_spacing)
        )

        with bar.canvas.before:
            Color(rgba=get_color_from_hex(THEME.bg_secondary + "F0"))
            self._bar_bg = Rectangle()
        bar.bind(pos=lambda w, p: setattr(self._bar_bg, 'pos', p))
        bar.bind(size=lambda w, s: setattr(self._bar_bg, 'size', s))

        buttons = [
            ("M", "Mesec", THEME.accent_secondary, self.popup_auto_month),
            ("B", "Bol/GO", THEME.info, self.popup_sick_vacation),
            ("S", "Satnica", THEME.success, self.popup_base_pay),
            ("MR", "Minuli", THEME.comp_time, self.popup_minuli_rad),
            ("G", "Godina", THEME.warning, self.popup_auto_year),
            ("X", "Briši", THEME.danger, self.popup_delete_month),
        ]

        for icon, label, color, callback in buttons:
            btn_box = BoxLayout(orientation='vertical', spacing=dp(2))

            btn = GlowButton(
                text=icon,
                bg_color=color,
                text_color=THEME.text_primary,
                size_hint_y=None,
                height=dp(40)
            )
            btn.font_size = sp(UI.large_font)
            btn.bind(on_press=callback)

            lbl = Label(
                text=label,
                font_size=sp(9),
                color=get_color_from_hex(THEME.text_muted),
                size_hint_y=None,
                height=dp(14)
            )

            btn_box.add_widget(btn)
            btn_box.add_widget(lbl)
            bar.add_widget(btn_box)

        return bar

    def _on_data_change(self) -> None:
        self.refresh_ui()

    def refresh_ui(self, *args) -> None:
        self.lbl_date.text = f"{MONTH_NAMES[self.current_month]} {self.current_year}"
        self._refresh_comp_time_bar()
        self._refresh_calendar()
        self._refresh_stats()

    def _refresh_comp_time_bar(self) -> None:
        self.comp_time_container.clear_widgets()
        summary = self.repo.get_summary(self.current_year, self.current_month)
        self.comp_time_container.add_widget(CompTimeCard(summary))

    def _refresh_calendar(self) -> None:
        self.calendar_grid.clear_widgets()
        cal_data = calendar.monthcalendar(self.current_year, self.current_month)
        today = self.today

        for week in cal_data:
            for day in week:
                record = (
                    self.repo.get(self.current_year, self.current_month, day)
                    if day else None
                )
                is_today = (
                    day == today.day
                    and self.current_month == today.month
                    and self.current_year == today.year
                )
                btn = ModernDayButton(
                    day, record, is_today,
                    year=self.current_year, month=self.current_month
                )
                if day != 0:
                    btn.bind(on_press=self.on_day_click)
                self.calendar_grid.add_widget(btn)

    def _refresh_stats(self) -> None:
        summary = self.repo.get_summary(self.current_year, self.current_month)
        cfg = CONFIG.pay

        self.stats_box.clear_widgets()

        stats = [
            (
                "Sati rada",
                str(summary.work_hours),
                THEME.text_primary
            ),
            (
                "Prekovr.",
                str(summary.overtime_hours),
                THEME.weekend_ot if summary.overtime_hours else THEME.text_muted
            ),
            (
                "Hrana",
                f"{summary.food_allowance:,.0f}".replace(",", "."),
                THEME.success if summary.food_allowance else THEME.text_muted
            ),
        ]
        
        # Add minuli rad if configured
        if self.repo.years_of_service > 0:
            stats.append((
                f"Minuli ({self.repo.years_of_service}g)",
                f"{summary.minuli_rad_bonus:,.0f}".replace(",", "."),
                THEME.comp_time if summary.minuli_rad_bonus else THEME.text_muted
            ))

        for label, value, color in stats:
            self.stats_box.add_widget(StatCard(label, value, color))

        # Total includes minuli rad
        total = summary.total_with_minuli
        self.total_label.text = (
            f"{total:,.0f} {cfg.currency}".replace(",", ".")
        )

    def _recalculate_worked_on_holiday(self, state: dict) -> None:
        """Derive worked_on_holiday flag from current holiday/shift state."""
        if not state.get('is_holiday_effective'):
            state['worked_on_holiday'] = False
            return

        if state.get('holiday_work_override') is not None:
            state['worked_on_holiday'] = bool(state['holiday_work_override'])
            return

        state['worked_on_holiday'] = (
            state.get('shift1') in Shift.work_shifts()
            and state.get('hours1', 0) > 0
        )

    def prev_month(self, instance) -> None:
        if self.current_month == 1:
            self.current_month = 12
            self.current_year -= 1
        else:
            self.current_month -= 1
        self.refresh_ui()

    def next_month(self, instance) -> None:
        if self.current_month == 12:
            self.current_month = 1
            self.current_year += 1
        else:
            self.current_month += 1
        self.refresh_ui()

    def on_day_click(self, instance) -> None:
        """Handle day button click - open day edit popup."""
        day = instance.day
        record = instance.record

        sick_rate = CONFIG.pay.sick_pay_rate
        if record and record.shift == Shift.SICK and record.sick_pay_rate is not None:
            sick_rate = record.sick_pay_rate

        state = {
            'shift1': record.shift if record else Shift.OFF,
            'hours1': record.hours if record else 8,
            'shift2': record.shift2 if record and record.shift2 else Shift.THIRD,
            'hours2': record.hours2 if record and record.hours2 else 8,
            'has_double': record.is_double_shift if record else False,
            'overtime_type': record.overtime_type if record else OvertimeType.PAID,
            'comp_used': record.comp_hours_used if record else 0,
            'sick_pay_rate': sick_rate,
        }
        auto_holiday = is_serbian_holiday(self.current_year, self.current_month, day)
        holiday_override = record.holiday_override if record else None
        state['holiday_override'] = holiday_override
        state['is_holiday_effective'] = (
            holiday_override if holiday_override is not None else auto_holiday
        )
        state['holiday_work_override'] = (
            record.holiday_work_override if record else None
        )
        if record:
            state['worked_on_holiday'] = record.worked_on_holiday
        else:
            self._recalculate_worked_on_holiday(state)
        state['updating_holiday_toggle'] = False
        state['holiday_toggle'] = None

        day_date = date(self.current_year, self.current_month, day)

        content = BoxLayout(
            orientation='vertical',
            spacing=dp(UI.large_spacing),
            padding=[dp(UI.card_padding), dp(16), dp(UI.card_padding), dp(UI.card_padding)],
            size_hint_y=None
        )
        content.bind(minimum_height=content.setter('height'))

        scroll = ScrollView(do_scroll_x=False)
        scroll.add_widget(content)

        header_card = GlassCard(
            orientation='horizontal',
            spacing=dp(12),
            padding=[dp(14), dp(12)],
            size_hint_y=None,
            bg_color=THEME.bg_card_elevated
        )
        header_card.bind(minimum_height=header_card.setter('height'))

        day_lbl = Label(
            text=f"{day:02d}",
            font_size=sp(32),
            bold=True,
            color=get_color_from_hex(THEME.accent_primary),
            size_hint=(None, None),
            size=(dp(64), dp(64)),
            halign='center', valign='middle'
        )
        day_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        date_box = BoxLayout(orientation='vertical', spacing=dp(2))
        month_lbl = Label(
            text=f"{MONTH_NAMES[self.current_month]} {self.current_year}",
            color=get_color_from_hex(THEME.text_primary),
            font_size=sp(UI.normal_font),
            bold=True,
            halign='left', valign='middle',
            size_hint_y=None, height=dp(22)
        )
        month_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        weekday_lbl = Label(
            text=(
                f"{WEEKDAY_NAMES[day_date.weekday()]} | "
                f"{day_date.strftime('%d.%m.%Y')}"
            ),
            color=get_color_from_hex(THEME.text_secondary),
            font_size=sp(UI.small_font),
            halign='left', valign='middle',
            size_hint_y=None, height=dp(18)
        )
        weekday_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        summary_lbl = Label(
            text="",
            color=get_color_from_hex(THEME.text_muted),
            font_size=sp(UI.tiny_font),
            halign='left', valign='top',
            size_hint_y=None, height=dp(32)
        )
        summary_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        date_box.add_widget(month_lbl)
        date_box.add_widget(weekday_lbl)
        date_box.add_widget(summary_lbl)

        header_card.add_widget(day_lbl)
        header_card.add_widget(date_box)
        content.add_widget(header_card)

        def build_day_summary() -> str:
            shift1 = state['shift1']
            if shift1 in Shift.work_shifts():
                summary = f"{shift1.display_name} {state['hours1']}h"
            else:
                summary = shift1.display_name
            if state.get('has_double'):
                shift2 = state['shift2']
                summary += f" + {shift2.display_name} {state['hours2']}h"
            if state.get('is_holiday_effective'):
                summary += " | Praznik"
            return summary

        def refresh_header_summary() -> None:
            summary_lbl.text = build_day_summary()

        state['refresh_header_summary'] = refresh_header_summary
        refresh_header_summary()

        def resolve_holiday_name() -> Optional[str]:
            base_name = get_holiday_name(self.current_year, self.current_month, day)
            if state.get('holiday_override') is False:
                return None
            if state.get('holiday_override') is True and not base_name:
                return "Rucno unet praznik"
            return base_name

        def build_holiday_text() -> Optional[str]:
            if not state.get('is_holiday_effective'):
                return None
            name = resolve_holiday_name()
            if not name:
                return None
            is_workday = day_date.weekday() < 5
            if state['shift1'] in Shift.work_shifts():
                return f"Praznik: {name} (rad +123%)"
            if is_workday:
                return f"Praznik: {name} (neradni, 100%)"
            return f"Praznik: {name} (vikend)"

        holiday_box = BoxLayout(
            size_hint_y=None, height=0, padding=[dp(8), dp(4)], opacity=0
        )
        with holiday_box.canvas.before:
            Color(rgba=get_color_from_hex(THEME.warning + "40"))
            holiday_box._bg = RoundedRectangle(radius=[dp(8)] * 4)
        holiday_box.bind(
            pos=lambda w, p: setattr(w._bg, 'pos', p),
            size=lambda w, s: setattr(w._bg, 'size', s)
        )

        holiday_lbl = Label(
            text="",
            color=get_color_from_hex(THEME.warning),
            font_size=sp(UI.small_font),
            bold=True,
            halign='center', valign='middle'
        )
        holiday_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))
        holiday_box.add_widget(holiday_lbl)

        def refresh_holiday_banner() -> None:
            holiday_text = build_holiday_text()
            if holiday_text:
                holiday_lbl.text = holiday_text
                holiday_box.height = dp(36)
                holiday_box.opacity = 1
            else:
                holiday_lbl.text = ""
                holiday_box.height = 0
                holiday_box.opacity = 0
            refresh_header = state.get('refresh_header_summary')
            if callable(refresh_header):
                refresh_header()

        state['refresh_holiday_banner'] = refresh_holiday_banner
        refresh_holiday_banner()

        # Manual holiday work selector
        toggle_row = BoxLayout(
            size_hint_y=None,
            height=dp(56),
            spacing=dp(10),
            padding=[dp(6), dp(4)]
        )
        holiday_toggle = CheckBox(
            active=state['is_holiday_effective'],
            size_hint=(None, None),
            size=(dp(28), dp(28))
        )
        state['holiday_toggle'] = holiday_toggle

        def on_holiday_toggle(instance, value):
            if state.get('updating_holiday_toggle'):
                return
            previous_override = state.get('holiday_override')
            state['holiday_override'] = value
            state['is_holiday_effective'] = value
            if not value:
                state['holiday_work_override'] = False
            else:
                if previous_override is False or state.get('holiday_work_override') is False:
                    state['holiday_work_override'] = None
            self._recalculate_worked_on_holiday(state)
            refresh = state.get('refresh_holiday_banner')
            if callable(refresh):
                refresh()

        holiday_toggle.bind(active=on_holiday_toggle)

        toggle_labels = BoxLayout(orientation='vertical', spacing=dp(2))
        main_toggle_lbl = Label(
            text="Tretiraj kao praznik",
            color=get_color_from_hex(THEME.text_primary),
            font_size=sp(UI.normal_font),
            halign='left', valign='middle',
            size_hint_y=0.55
        )
        main_toggle_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        sub_toggle_lbl = Label(
            text="Iskljuci ako firma ne racuna ovaj datum, ukljuci za rucni praznik",
            color=get_color_from_hex(THEME.text_secondary),
            font_size=sp(UI.tiny_font),
            halign='left', valign='middle',
            size_hint_y=0.45
        )
        sub_toggle_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        toggle_labels.add_widget(main_toggle_lbl)
        toggle_labels.add_widget(sub_toggle_lbl)

        toggle_row.add_widget(holiday_toggle)
        toggle_row.add_widget(toggle_labels)

        holiday_card = GlassCard(
            orientation='vertical',
            spacing=dp(6),
            padding=[dp(12), dp(10)],
            size_hint_y=None
        )
        holiday_card.bind(minimum_height=holiday_card.setter('height'))
        holiday_card.add_widget(self._create_section_label("Praznik"))
        holiday_card.add_widget(holiday_box)
        holiday_card.add_widget(toggle_row)
        content.add_widget(holiday_card)

        # Daily earnings and comp time info display
        if record:
            content.add_widget(self._create_earnings_display(record))

        # Shift 1 section
        shift_card = GlassCard(
            orientation='vertical',
            spacing=dp(8),
            padding=[dp(12), dp(10)],
            size_hint_y=None
        )
        shift_card.bind(minimum_height=shift_card.setter('height'))
        shift_card.add_widget(self._create_section_label("Smena"))
        shift1_widgets = self._create_shift1_section(state)
        for w in shift1_widgets:
            shift_card.add_widget(w)
        content.add_widget(shift_card)

        # Comp time used section (only if balance available)
        summary = self.repo.get_summary(self.current_year, self.current_month)
        available_comp = summary.comp_time_starting + state.get('temp_earned', 0)
        
        show_comp_section = state['comp_used'] > 0 or available_comp > 0
        if show_comp_section:
            comp_card = GlassCard(
                orientation='vertical',
                spacing=dp(8),
                padding=[dp(12), dp(10)],
                size_hint_y=None
            )
            comp_card.bind(minimum_height=comp_card.setter('height'))
            comp_card.add_widget(self._create_section_label("Kompenzacija"))
            comp_widgets = self._create_comp_used_section(
                state, record, available_comp
            )
            for w in comp_widgets:
                comp_card.add_widget(w)
            content.add_widget(comp_card)

        # Double shift section
        shift2_container, add_btn = self._create_shift2_section(state)
        double_card = GlassCard(
            orientation='vertical',
            spacing=dp(8),
            padding=[dp(12), dp(10)],
            size_hint_y=None
        )
        double_card.bind(minimum_height=double_card.setter('height'))
        double_card.add_widget(add_btn)
        double_card.add_widget(shift2_container)
        content.add_widget(double_card)

        # Save button
        save_btn = self._create_save_button()
        content.add_widget(save_btn)

        popup = ModernPopup(
            title=f"{day}. {MONTH_NAMES[self.current_month]}",
            content=scroll,
            size_hint=(UI.popup_width_ratio, None),
            height=dp(UI.day_popup_height)
        )

        def save(btn):
            self.repo.set(
                self.current_year, self.current_month, day,
                state['shift1'], state['hours1'],
                state['shift2'] if state['has_double'] else None,
                state['hours2'] if state['has_double'] else 0,
                state['overtime_type'] if state['has_double'] else OvertimeType.PAID,
                state['comp_used'],
                state.get('holiday_work_override'),
                state.get('holiday_override'),
                state['sick_pay_rate'] if state['shift1'] == Shift.SICK else None
            )
            popup.dismiss()

        save_btn.bind(on_release=save)
        self._track_popup(popup)
        popup.open()

    def _create_section_label(self, text: str) -> Label:
        label = Label(
            text=text,
            color=get_color_from_hex(THEME.text_secondary),
            font_size=sp(UI.small_font),
            bold=True,
            size_hint_y=None,
            height=dp(20),
            halign='left', valign='middle'
        )
        label.bind(size=lambda i, s: setattr(i, 'text_size', s))
        return label

    def _create_popup_card(self, **kwargs) -> GlassCard:
        spacing = kwargs.pop('spacing', dp(8))
        padding = kwargs.pop('padding', [dp(12), dp(10)])
        card = GlassCard(
            orientation='vertical',
            spacing=spacing,
            padding=padding,
            size_hint_y=None,
            **kwargs
        )
        card.bind(minimum_height=card.setter('height'))
        return card

    def _create_earnings_display(self, record: DayRecord) -> BoxLayout:
        daily_pay = self.repo.pay_calculator.calculate_daily_pay(
            record, self.repo.base_pay
        )

        # Don't show if no pay
        if daily_pay <= 0:
            return BoxLayout(size_hint_y=None, height=0)

        card = GlassCard(
            orientation='vertical',
            padding=[dp(12), dp(10)],
            spacing=dp(6),
            size_hint_y=None,
            bg_color=THEME.bg_card_elevated,
            border_color=THEME.success
        )
        card.bind(minimum_height=card.setter('height'))

        title_lbl = Label(
            text="Zarada",
            color=get_color_from_hex(THEME.text_secondary),
            font_size=sp(UI.tiny_font),
            size_hint_y=None,
            height=dp(16),
            halign='left', valign='middle'
        )
        title_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))
        card.add_widget(title_lbl)

        pay_row = BoxLayout(size_hint_y=None, height=dp(32))
        earn_lbl = Label(
            text="Ukupno:",
            color=get_color_from_hex(THEME.text_secondary),
            font_size=sp(UI.small_font),
            halign='left', valign='middle',
            size_hint_x=0.4
        )
        earn_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        earn_val = Label(
            text=f"{daily_pay:,.0f} {CONFIG.pay.currency}".replace(",", "."),
            color=get_color_from_hex(THEME.success),
            font_size=sp(UI.normal_font),
            bold=True,
            halign='right', valign='middle',
            size_hint_x=0.6
        )
        earn_val.bind(size=lambda i, s: setattr(i, 'text_size', s))

        pay_row.add_widget(earn_lbl)
        pay_row.add_widget(earn_val)
        card.add_widget(pay_row)

        if record.worked_on_holiday:
            holiday_badge = BoxLayout(
                size_hint_y=None,
                height=dp(22),
                padding=[dp(8), dp(2)]
            )
            with holiday_badge.canvas.before:
                Color(rgba=get_color_from_hex(THEME.warning + "33"))
                holiday_badge._bg = RoundedRectangle(radius=[dp(10)] * 4)
            holiday_badge.bind(
                pos=lambda w, p: setattr(w._bg, 'pos', p),
                size=lambda w, s: setattr(w._bg, 'size', s)
            )

            holiday_lbl = Label(
                text="Rad na praznik",
                color=get_color_from_hex(THEME.warning),
                font_size=sp(UI.tiny_font),
                bold=True,
                halign='center', valign='middle'
            )
            holiday_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))
            holiday_badge.add_widget(holiday_lbl)
            card.add_widget(holiday_badge)

        # Comp time info box - only for work shifts
        if record.shift in Shift.work_shifts():
            if record.hours_deficit > 0:
                comp_text = f"Minus sati: -{record.hours_deficit}h"
                comp_color = THEME.danger
            elif record.comp_time_earned > 0:
                comp_text = f"Zaradjeno sati: +{record.comp_time_earned}h"
                comp_color = THEME.comp_time
            else:
                comp_text = "Pun dan (8h)"
                comp_color = THEME.text_muted

            comp_box = BoxLayout(
                size_hint_y=None,
                height=dp(24),
                padding=[dp(8), dp(2)]
            )
            with comp_box.canvas.before:
                Color(rgba=get_color_from_hex(comp_color + "26"))
                comp_box._bg = RoundedRectangle(radius=[dp(8)] * 4)
            comp_box.bind(
                pos=lambda w, p: setattr(w._bg, 'pos', p),
                size=lambda w, s: setattr(w._bg, 'size', s)
            )

            comp_lbl = Label(
                text=comp_text,
                color=get_color_from_hex(comp_color),
                font_size=sp(UI.tiny_font),
                halign='center', valign='middle'
            )
            comp_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))
            comp_box.add_widget(comp_lbl)
            card.add_widget(comp_box)

        return card

    def _create_shift1_section(self, state: dict) -> List[Widget]:
        widgets = []

        shift_grid = BoxLayout(
            spacing=dp(6),
            size_hint_y=None,
            height=dp(UI.button_height)
        )
        shift_btns = []

        # Include VACATION in shift options
        all_shifts = [Shift.FIRST, Shift.SECOND, Shift.THIRD, Shift.SICK, Shift.VACATION, Shift.OFF]

        for s in all_shifts:
            is_selected = s == state['shift1']
            btn = RoundedButton(
                text=s.display_name, font_size=sp(UI.small_font), bold=True,
                bg_color=s.color if is_selected else s.color + "33",
                text_color=s.text_color,
                radius=14
            )
            btn.shift_value = s
            shift_btns.append(btn)
            shift_grid.add_widget(btn)

        def update_holiday_flag() -> None:
            self._recalculate_worked_on_holiday(state)
            refresh = state.get('refresh_holiday_banner')
            if callable(refresh):
                refresh()

        def on_shift_click(btn):
            state['shift1'] = btn.shift_value
            for b in shift_btns:
                is_sel = b.shift_value == state['shift1']
                b.set_bg_color(
                    b.shift_value.color if is_sel else b.shift_value.color + "33"
                )
            update_holiday_flag()
            refresh_sick_rate_visibility()

        for btn in shift_btns:
            btn.bind(on_release=on_shift_click)

        widgets.append(shift_grid)

        # Hours button
        hours_btn = RoundedButton(
            text=f"Sati: {state['hours1']}",
            font_size=sp(UI.normal_font), bold=True,
            size_hint_y=None, height=dp(UI.button_height),
            bg_color=THEME.bg_card_elevated,
            text_color=THEME.text_primary,
            radius=14
        )

        def show_hours_popup(instance):
            def on_select(hour):
                state['hours1'] = hour
                hours_btn.text = f"Sati: {hour}"
                update_holiday_flag()

            popup = self.popup_factory.create_hours_picker_popup(
                "Izaberi sate", state['hours1'], on_select
            )
            self._track_popup(popup)
            popup.open()

        hours_btn.bind(on_release=show_hours_popup)
        widgets.append(hours_btn)

        sick_rate_box = BoxLayout(
            orientation='vertical',
            spacing=dp(6),
            size_hint_y=None,
            height=0,
            opacity=0
        )

        sick_title = Label(
            text="Isplata bolovanja",
            color=get_color_from_hex(THEME.text_secondary),
            font_size=sp(UI.tiny_font),
            size_hint_y=None,
            height=dp(16),
            halign='left', valign='middle'
        )
        sick_title.bind(size=lambda i, s: setattr(i, 'text_size', s))

        rate_row = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(6))
        rate_btns = []

        for rate, label in ((0.65, "65%"), (1.0, "100%")):
            is_selected = abs(state['sick_pay_rate'] - rate) < 0.01
            btn = RoundedButton(
                text=label, font_size=sp(UI.small_font), bold=True,
                bg_color=THEME.sick if is_selected else THEME.bg_card_elevated,
                text_color=THEME.text_primary,
                radius=14
            )
            btn.rate_value = rate
            rate_btns.append(btn)
            rate_row.add_widget(btn)

        sick_rate_box.add_widget(sick_title)
        sick_rate_box.add_widget(rate_row)

        def update_sick_rate_buttons() -> None:
            for b in rate_btns:
                is_sel = abs(state['sick_pay_rate'] - b.rate_value) < 0.01
                b.set_bg_color(
                    THEME.sick if is_sel else THEME.bg_card_elevated
                )
                b.set_text_color(THEME.text_primary)

        def set_sick_rate(btn):
            state['sick_pay_rate'] = btn.rate_value
            update_sick_rate_buttons()

        for btn in rate_btns:
            btn.bind(on_release=set_sick_rate)

        def refresh_sick_rate_visibility() -> None:
            show = state['shift1'] == Shift.SICK
            sick_rate_box.height = dp(70) if show else 0
            sick_rate_box.opacity = 1 if show else 0
            sick_rate_box.disabled = not show

        refresh_sick_rate_visibility()
        widgets.append(sick_rate_box)

        return widgets

    def _create_comp_used_section(
        self, state: dict, record: Optional[DayRecord], available_comp: int
    ) -> List[Widget]:
        widgets = []

        balance_lbl = Label(
            text=f"Bilans: {available_comp}h",
            color=get_color_from_hex(THEME.text_muted),
            font_size=sp(UI.tiny_font),
            size_hint_y=None,
            height=dp(18),
            halign='left', valign='middle'
        )
        balance_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))
        widgets.append(balance_lbl)

        comp_btn = RoundedButton(
            text=f"Iskoristi sate: {state['comp_used']}h",
            font_size=sp(UI.small_font), bold=True,
            size_hint_y=None,
            height=dp(44),
            bg_color=THEME.comp_time_used + "66",
            text_color=THEME.text_primary,
            border_color=THEME.comp_time_used,
            radius=14
        )

        def show_comp_popup(instance):
            popup_content = BoxLayout(
                orientation='vertical', spacing=dp(10), padding=dp(15)
            )

            popup_content.add_widget(Label(
                text=f"Dostupno: {available_comp}h",
                color=get_color_from_hex(THEME.comp_time),
                font_size=sp(UI.normal_font), bold=True,
                size_hint_y=None, height=dp(30)
            ))

            popup = Popup(
                title="Iskoristi zarađene sate",
                content=popup_content,
                size_hint=(0.85, None), height=dp(250),
                background='',
                background_color=get_color_from_hex(THEME.bg_dark + "F5"),
                separator_color=get_color_from_hex(THEME.comp_time_used),
                title_color=get_color_from_hex(THEME.text_primary),
                title_size=sp(UI.normal_font)
            )

            hours_grid = GridLayout(cols=5, spacing=dp(8), size_hint_y=None, height=dp(100))
            max_usable = min(8, available_comp)
            
            for h in range(0, max_usable + 1):
                is_selected = h == state['comp_used']
                h_btn = RoundedButton(
                    text=str(h), font_size=sp(20), bold=True,
                    bg_color=THEME.comp_time_used if is_selected else THEME.bg_card,
                    text_color=THEME.text_primary
                )

                def make_handler(hour):
                    def handler(btn):
                        state['comp_used'] = hour
                        comp_btn.text = f"Iskoristi sate: {hour}h"
                        popup.dismiss()
                    return handler

                h_btn.bind(on_release=make_handler(h))
                hours_grid.add_widget(h_btn)

            popup_content.add_widget(hours_grid)
            self._track_popup(popup)
            popup.open()

        comp_btn.bind(on_release=show_comp_popup)
        widgets.append(comp_btn)

        return widgets

    def _create_shift2_section(self, state: dict) -> Tuple[BoxLayout, Button]:
        has_double = state['has_double']

        container = BoxLayout(
            orientation='vertical',
            spacing=dp(8),
            size_hint_y=None
        )
        container.bind(minimum_height=container.setter('height'))

        header = BoxLayout(
            size_hint_y=None,
            height=dp(24) if has_double else 0,
            spacing=dp(8)
        )
        header.opacity = 1 if has_double else 0

        header_label = Label(
            text="Druga smena",
            color=get_color_from_hex(THEME.double_shift),
            font_size=sp(UI.small_font),
            bold=True,
            halign='left', valign='middle',
            size_hint_x=0.7
        )
        header_label.bind(size=lambda i, s: setattr(i, 'text_size', s))

        remove_btn = RoundedButton(
            text="UKLONI", font_size=sp(11), bold=True,
            size_hint=(None, 1), width=dp(70),
            bg_color=THEME.danger,
            text_color=THEME.text_primary
        )

        header.add_widget(header_label)
        header.add_widget(remove_btn)

        shift2_grid = BoxLayout(
            spacing=dp(6), size_hint_y=None,
            height=dp(UI.button_height) if has_double else 0
        )
        shift2_grid.opacity = 1 if has_double else 0
        shift2_btns = []

        for s in [Shift.FIRST, Shift.SECOND, Shift.THIRD]:
            is_selected = s == state['shift2']
            btn = RoundedButton(
                text=s.display_name, font_size=sp(UI.small_font), bold=True,
                bg_color=s.color if is_selected else s.color + "33",
                text_color=s.text_color,
                radius=14
            )
            btn.shift_value = s
            shift2_btns.append(btn)
            shift2_grid.add_widget(btn)

        def refresh_header() -> None:
            refresh = state.get('refresh_header_summary')
            if callable(refresh):
                refresh()

        def on_shift2_click(btn):
            state['shift2'] = btn.shift_value
            for b in shift2_btns:
                is_sel = b.shift_value == state['shift2']
                b.set_bg_color(
                    b.shift_value.color if is_sel else b.shift_value.color + "33"
                )
            refresh_header()

        for btn in shift2_btns:
            btn.bind(on_release=on_shift2_click)

        hours2_btn = RoundedButton(
            text=f"Sati: {state['hours2']}",
            font_size=sp(UI.normal_font), bold=True,
            size_hint_y=None,
            height=dp(UI.button_height) if has_double else 0,
            bg_color=THEME.bg_card_elevated,
            text_color=THEME.text_primary,
            radius=14
        )
        hours2_btn.opacity = 1 if has_double else 0

        def show_hours2_popup(instance):
            def on_select(hour):
                state['hours2'] = hour
                hours2_btn.text = f"Sati: {hour}"
                refresh_header()

            popup = self.popup_factory.create_hours_picker_popup(
                "Izaberi sate (druga smena)",
                state['hours2'], on_select,
                THEME.double_shift
            )
            self._track_popup(popup)
            popup.open()

        hours2_btn.bind(on_release=show_hours2_popup)

        ot_box = BoxLayout(
            size_hint_y=None,
            height=dp(44) if has_double else 0,
            spacing=dp(8)
        )
        ot_box.opacity = 1 if has_double else 0

        paid_btn = RoundedButton(
            text="PLAĆENO", font_size=sp(13), bold=True,
            bg_color=THEME.success if state['overtime_type'] == OvertimeType.PAID else THEME.bg_card,
            text_color=THEME.text_dark if state['overtime_type'] == OvertimeType.PAID else THEME.text_primary,
            radius=14
        )

        comp_btn = RoundedButton(
            text="ZA SATE", font_size=sp(13), bold=True,
            bg_color=THEME.comp_time if state['overtime_type'] == OvertimeType.COMP_TIME else THEME.bg_card,
            text_color=THEME.text_dark if state['overtime_type'] == OvertimeType.COMP_TIME else THEME.text_primary,
            radius=14
        )

        def set_paid(btn):
            state['overtime_type'] = OvertimeType.PAID
            paid_btn.set_bg_color(THEME.success)
            paid_btn.set_text_color(THEME.text_dark)
            comp_btn.set_bg_color(THEME.bg_card)
            comp_btn.set_text_color(THEME.text_primary)

        def set_comp(btn):
            state['overtime_type'] = OvertimeType.COMP_TIME
            comp_btn.set_bg_color(THEME.comp_time)
            comp_btn.set_text_color(THEME.text_dark)
            paid_btn.set_bg_color(THEME.bg_card)
            paid_btn.set_text_color(THEME.text_primary)

        paid_btn.bind(on_release=set_paid)
        comp_btn.bind(on_release=set_comp)

        ot_box.add_widget(paid_btn)
        ot_box.add_widget(comp_btn)

        container.add_widget(header)
        container.add_widget(shift2_grid)
        container.add_widget(hours2_btn)
        container.add_widget(ot_box)

        add_btn = RoundedButton(
            text="+ DODAJ DRUGU SMENU",
            font_size=sp(13), bold=True,
            size_hint_y=None,
            height=0 if has_double else dp(44),
            bg_color=THEME.double_shift,
            text_color=THEME.text_primary,
            radius=14
        )
        add_btn.opacity = 0 if has_double else 1
        add_btn.disabled = has_double

        def show_shift2(instance):
            state['has_double'] = True
            header.height = dp(24)
            header.opacity = 1
            shift2_grid.height = dp(UI.button_height)
            shift2_grid.opacity = 1
            hours2_btn.height = dp(UI.button_height)
            hours2_btn.opacity = 1
            ot_box.height = dp(44)
            ot_box.opacity = 1
            add_btn.height = 0
            add_btn.opacity = 0
            add_btn.disabled = True
            refresh_header()

        def hide_shift2(btn):
            state['has_double'] = False
            header.height = 0
            header.opacity = 0
            shift2_grid.height = 0
            shift2_grid.opacity = 0
            hours2_btn.height = 0
            hours2_btn.opacity = 0
            ot_box.height = 0
            ot_box.opacity = 0
            add_btn.height = dp(44)
            add_btn.opacity = 1
            add_btn.disabled = False
            refresh_header()

        add_btn.bind(on_release=show_shift2)
        remove_btn.bind(on_release=hide_shift2)

        return container, add_btn

    def _create_save_button(self) -> Button:
        btn = GlowButton(
            text="SAČUVAJ",
            bg_color=THEME.success,
            text_color=THEME.text_dark,
            size_hint_y=None,
            height=dp(UI.save_button_height)
        )
        btn.font_size = sp(UI.normal_font)
        return btn

    def popup_auto_month(self, instance) -> None:
        _, num_days = calendar.monthrange(self.current_year, self.current_month)

        first_workday = None
        for d in range(1, num_days + 1):
            if date(self.current_year, self.current_month, d).weekday() <= 4:
                first_workday = d
                break

        content = BoxLayout(
            orientation='vertical',
            spacing=dp(UI.large_spacing),
            padding=[dp(16), dp(16), dp(16), dp(16)],
            size_hint_y=None
        )
        content.bind(minimum_height=content.setter('height'))

        info_card = self._create_popup_card(
            bg_color=THEME.bg_card_elevated,
            border_color=THEME.accent_secondary,
            spacing=dp(6)
        )
        title_lbl = Label(
            text=f"{MONTH_NAMES[self.current_month]} {self.current_year}",
            color=get_color_from_hex(THEME.text_primary),
            font_size=sp(UI.large_font),
            bold=True,
            size_hint_y=None,
            height=dp(26),
            halign='left', valign='middle'
        )
        title_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        detail_text = (
            f"Prvi radni dan: {first_workday}. {MONTH_NAMES[self.current_month]}"
            if first_workday else "Nema radnih dana u mesecu."
        )
        detail_lbl = Label(
            text=detail_text,
            color=get_color_from_hex(THEME.text_secondary),
            font_size=sp(UI.small_font),
            size_hint_y=None,
            height=dp(22),
            halign='left', valign='middle'
        )
        detail_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        info_card.add_widget(title_lbl)
        info_card.add_widget(detail_lbl)
        content.add_widget(info_card)

        state = {'start_shift': Shift.FIRST}

        if first_workday:
            shift_card = self._create_popup_card()
            shift_card.add_widget(self._create_section_label("Pocetna smena"))

            shift_row = BoxLayout(
                size_hint_y=None,
                height=dp(44),
                spacing=dp(6)
            )
            shift_btns = []
            for s in [Shift.FIRST, Shift.SECOND, Shift.THIRD]:
                is_selected = s == state['start_shift']
                btn = RoundedButton(
                    text=s.display_name, font_size=sp(UI.small_font), bold=True,
                    bg_color=s.color if is_selected else s.color + "33",
                    text_color=s.text_color,
                    radius=14
                )
                btn.shift_value = s
                shift_btns.append(btn)
                shift_row.add_widget(btn)

            def on_shift_click(btn):
                state['start_shift'] = btn.shift_value
                for b in shift_btns:
                    is_sel = b.shift_value == state['start_shift']
                    b.set_bg_color(
                        b.shift_value.color if is_sel else b.shift_value.color + "33"
                    )

            for btn in shift_btns:
                btn.bind(on_release=on_shift_click)

            shift_card.add_widget(shift_row)
            content.add_widget(shift_card)

        popup = ModernPopup(
            title="Auto-popuna meseca",
            content=content,
            size_hint=(0.85, UI.auto_popup_height_ratio)
        )

        btn = GlowButton(
            text="Popuni mesec" if first_workday else "Zatvori",
            bg_color=THEME.accent_secondary if first_workday else THEME.bg_card_elevated,
            text_color=THEME.text_primary,
            size_hint_y=None,
            height=dp(50)
        )

        def run_auto(inst):
            if first_workday:
                start_shift = state['start_shift']
                self.scheduler.populate_month(
                    self.repo, self.current_year, self.current_month, start_shift
                )
            popup.dismiss()

        btn.bind(on_press=run_auto)
        content.add_widget(btn)

        self._track_popup(popup)
        popup.open()

    def popup_sick_vacation(self, instance) -> None:
        """Show sick leave / vacation popup with tabs."""
        content = BoxLayout(
            orientation='vertical',
            spacing=dp(UI.large_spacing),
            padding=[dp(16), dp(20), dp(16), dp(16)],
            size_hint_y=None
        )
        content.bind(minimum_height=content.setter('height'))

        info_card = self._create_popup_card(
            bg_color=THEME.bg_card_elevated,
            border_color=THEME.info,
            spacing=dp(6)
        )
        title_lbl = Label(
            text="Unos odsustva",
            color=get_color_from_hex(THEME.text_primary),
            font_size=sp(UI.large_font),
            bold=True,
            size_hint_y=None,
            height=dp(24),
            halign='left', valign='middle'
        )
        title_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        month_lbl = Label(
            text=f"{MONTH_NAMES[self.current_month]} {self.current_year}",
            color=get_color_from_hex(THEME.text_secondary),
            font_size=sp(UI.small_font),
            size_hint_y=None,
            height=dp(18),
            halign='left', valign='middle'
        )
        month_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        info_card.add_widget(title_lbl)
        info_card.add_widget(month_lbl)
        content.add_widget(info_card)

        # Type selector
        type_card = self._create_popup_card()
        type_card.add_widget(self._create_section_label("Tip odsustva"))
        type_box = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))

        state = {
            'type': 'sick',
            'sick_pay_rate': CONFIG.pay.sick_pay_rate
        }

        sick_btn = RoundedButton(
            text="BOLOVANJE", font_size=sp(13), bold=True,
            bg_color=THEME.sick,
            text_color=THEME.text_primary,
            radius=14
        )

        vacation_btn = RoundedButton(
            text="GODIŠNJI", font_size=sp(13), bold=True,
            bg_color=THEME.bg_card_elevated,
            text_color=THEME.text_primary,
            radius=14
        )

        rate_box = BoxLayout(
            orientation='vertical',
            spacing=dp(6),
            size_hint_y=None,
            height=dp(70),
            opacity=1
        )
        rate_label = Label(
            text="Isplata bolovanja",
            color=get_color_from_hex(THEME.text_secondary),
            font_size=sp(UI.tiny_font),
            size_hint_y=None,
            height=dp(16),
            halign='left', valign='middle'
        )
        rate_label.bind(size=lambda i, s: setattr(i, 'text_size', s))

        rate_row = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        rate_btns = []

        for rate, label in ((0.65, "65%"), (1.0, "100%")):
            is_selected = abs(state['sick_pay_rate'] - rate) < 0.01
            btn = RoundedButton(
                text=label, font_size=sp(UI.small_font), bold=True,
                bg_color=THEME.sick if is_selected else THEME.bg_card_elevated,
                text_color=THEME.text_primary,
                radius=14
            )
            btn.rate_value = rate
            rate_btns.append(btn)
            rate_row.add_widget(btn)

        rate_box.add_widget(rate_label)
        rate_box.add_widget(rate_row)

        def update_sick_rate_buttons() -> None:
            is_sick = state['type'] == 'sick'
            rate_box.disabled = not is_sick
            rate_label.color = get_color_from_hex(
                THEME.text_secondary if is_sick else THEME.text_muted
            )
            for b in rate_btns:
                if is_sick:
                    is_sel = abs(state['sick_pay_rate'] - b.rate_value) < 0.01
                    b.set_bg_color(
                        THEME.sick if is_sel else THEME.bg_card_elevated
                    )
                    b.set_text_color(THEME.text_primary)
                else:
                    b.set_bg_color(THEME.bg_card)
                    b.set_text_color(THEME.text_muted)
                b.disabled = not is_sick

        def set_sick_rate(btn):
            state['sick_pay_rate'] = btn.rate_value
            update_sick_rate_buttons()

        for btn in rate_btns:
            btn.bind(on_release=set_sick_rate)

        def update_type_buttons():
            if state['type'] == 'sick':
                sick_btn.set_bg_color(THEME.sick)
                vacation_btn.set_bg_color(THEME.bg_card_elevated)
            else:
                vacation_btn.set_bg_color(THEME.vacation)
                sick_btn.set_bg_color(THEME.bg_card_elevated)
            update_sick_rate_buttons()

        def set_sick(btn):
            state['type'] = 'sick'
            update_type_buttons()

        def set_vacation(btn):
            state['type'] = 'vacation'
            update_type_buttons()

        sick_btn.bind(on_release=set_sick)
        vacation_btn.bind(on_release=set_vacation)

        type_box.add_widget(sick_btn)
        type_box.add_widget(vacation_btn)
        type_card.add_widget(type_box)
        type_card.add_widget(rate_box)
        update_type_buttons()
        content.add_widget(type_card)

        range_card = self._create_popup_card()
        range_card.add_widget(self._create_section_label("Opseg dana"))

        range_row = BoxLayout(spacing=dp(8), size_hint_y=None, height=dp(72))
        start_box = BoxLayout(orientation='vertical', spacing=dp(4), size_hint_x=0.5)
        end_box = BoxLayout(orientation='vertical', spacing=dp(4), size_hint_x=0.5)

        start_lbl = Label(
            text="Od",
            color=get_color_from_hex(THEME.text_secondary),
            font_size=sp(UI.tiny_font),
            size_hint_y=None,
            height=dp(16),
            halign='left', valign='middle'
        )
        start_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        start_input = ModernTextInput(
            input_filter='int',
            multiline=False,
            size_hint_y=None,
            height=dp(48),
            hint_text="Pocetak"
        )

        end_lbl = Label(
            text="Do",
            color=get_color_from_hex(THEME.text_secondary),
            font_size=sp(UI.tiny_font),
            size_hint_y=None,
            height=dp(16),
            halign='left', valign='middle'
        )
        end_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        end_input = ModernTextInput(
            input_filter='int',
            multiline=False,
            size_hint_y=None,
            height=dp(48),
            hint_text="Kraj"
        )

        start_box.add_widget(start_lbl)
        start_box.add_widget(start_input)
        end_box.add_widget(end_lbl)
        end_box.add_widget(end_input)

        range_row.add_widget(start_box)
        range_row.add_widget(end_box)
        range_card.add_widget(range_row)
        content.add_widget(range_card)

        scroll = ScrollView(do_scroll_x=False)
        scroll.add_widget(content)

        popup = ModernPopup(
            title="Bolovanje / Godišnji",
            content=scroll,
            size_hint=(0.85, 0.6)
        )

        btn = GlowButton(
            text="Upiši",
            bg_color=THEME.info,
            text_color=THEME.text_primary,
            size_hint_y=None,
            height=dp(50)
        )

        def apply(inst):
            try:
                start = int(start_input.text)
                end = int(end_input.text)
                if state['type'] == 'sick':
                    self.scheduler.apply_sick_range(
                        self.repo, self.current_year, self.current_month, start, end,
                        state['sick_pay_rate']
                    )
                else:
                    self.scheduler.apply_vacation_range(
                        self.repo, self.current_year, self.current_month, start, end
                    )
                popup.dismiss()
            except ValueError:
                pass

        btn.bind(on_press=apply)
        content.add_widget(btn)

        self._track_popup(popup)
        popup.open()

    def popup_base_pay(self, instance) -> None:
        content = BoxLayout(
            orientation='vertical',
            spacing=dp(UI.large_spacing),
            padding=[dp(16), dp(16), dp(16), dp(16)]
        )

        curr_pay = self.repo.base_pay

        summary_card = self._create_popup_card(
            bg_color=THEME.bg_card_elevated,
            border_color=THEME.success,
            spacing=dp(6)
        )
        summary_card.add_widget(self._create_section_label("Trenutna satnica"))
        value_lbl = Label(
            text=f"{curr_pay:.0f} {CONFIG.pay.currency}",
            color=get_color_from_hex(THEME.success),
            font_size=sp(UI.large_font),
            bold=True,
            size_hint_y=None,
            height=dp(28),
            halign='left', valign='middle'
        )
        value_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))
        summary_card.add_widget(value_lbl)
        content.add_widget(summary_card)

        pay_input = ModernTextInput(
            text=str(int(curr_pay)),
            input_filter='float',
            multiline=False,
            size_hint_y=None,
            height=dp(48),
            hint_text="Nova satnica..."
        )
        input_card = self._create_popup_card()
        input_card.add_widget(self._create_section_label("Nova satnica"))
        input_card.add_widget(pay_input)
        content.add_widget(input_card)

        popup = ModernPopup(
            title="Izmena satnice",
            content=content,
            size_hint=(0.85, UI.auto_popup_height_ratio)
        )

        btn = GlowButton(
            text="Sačuvaj",
            bg_color=THEME.success,
            text_color=THEME.text_dark,
            size_hint_y=None,
            height=dp(50)
        )

        def save(inst):
            try:
                new_val = float(pay_input.text)
                if new_val >= 0:
                    self.repo.base_pay = new_val
                popup.dismiss()
            except ValueError:
                pass

        btn.bind(on_press=save)
        content.add_widget(btn)

        self._track_popup(popup)
        popup.open()

    def popup_minuli_rad(self, instance) -> None:
        """Show minuli rad (years of service) popup."""
        content = BoxLayout(
            orientation='vertical',
            spacing=dp(UI.large_spacing),
            padding=[dp(16), dp(16), dp(16), dp(16)]
        )

        curr_years = self.repo.years_of_service
        curr_percent = curr_years * 0.5

        summary_card = self._create_popup_card(
            bg_color=THEME.bg_card_elevated,
            border_color=THEME.comp_time,
            spacing=dp(6)
        )
        summary_card.add_widget(self._create_section_label("Trenutno"))
        summary_lbl = Label(
            text=f"{curr_years} god. = {curr_percent:.1f}%",
            color=get_color_from_hex(THEME.comp_time),
            font_size=sp(UI.normal_font),
            bold=True,
            size_hint_y=None,
            height=dp(24),
            halign='left', valign='middle'
        )
        summary_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))
        summary_card.add_widget(summary_lbl)
        hint_lbl = Label(
            text="Za svaku godinu staza: +0.5%",
            color=get_color_from_hex(THEME.text_secondary),
            font_size=sp(UI.tiny_font),
            size_hint_y=None,
            height=dp(18),
            halign='left', valign='middle'
        )
        hint_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))
        summary_card.add_widget(hint_lbl)
        content.add_widget(summary_card)

        years_input = ModernTextInput(
            text=str(curr_years),
            input_filter='int',
            multiline=False,
            size_hint_y=None,
            height=dp(48),
            hint_text="Godine radnog staza..."
        )

        input_card = self._create_popup_card()
        input_card.add_widget(self._create_section_label("Godine staza"))
        input_card.add_widget(years_input)

        init_color = THEME.comp_time if curr_years > 0 else THEME.text_muted
        preview_box = BoxLayout(
            size_hint_y=None,
            height=dp(24),
            padding=[dp(8), dp(2)]
        )
        with preview_box.canvas.before:
            preview_box._bg_color = Color(
                rgba=get_color_from_hex(init_color + "26")
            )
            preview_box._bg = RoundedRectangle(radius=[dp(8)] * 4)
        preview_box.bind(
            pos=lambda w, p: setattr(w._bg, 'pos', p),
            size=lambda w, s: setattr(w._bg, 'size', s)
        )

        preview_label = Label(
            text=f"Bonus: {curr_percent:.1f}%",
            color=get_color_from_hex(init_color),
            font_size=sp(UI.small_font),
            halign='center', valign='middle'
        )
        preview_label.bind(size=lambda i, s: setattr(i, 'text_size', s))
        preview_box.add_widget(preview_label)
        input_card.add_widget(preview_box)
        content.add_widget(input_card)
        
        # Update preview on text change
        def update_preview(instance, value):
            try:
                years = int(value) if value else 0
                percent = years * 0.5
                preview_label.text = f"Bonus: {percent:.1f}%"
                color_hex = THEME.comp_time if years > 0 else THEME.text_muted
                preview_label.color = get_color_from_hex(color_hex)
                preview_box._bg_color.rgba = get_color_from_hex(color_hex + "26")
            except ValueError:
                preview_label.text = "Bonus: 0%"
        
        years_input.bind(text=update_preview)

        popup = ModernPopup(
            title="Minuli rad",
            content=content,
            size_hint=(0.85, 0.5)
        )

        btn = GlowButton(
            text="Sačuvaj",
            bg_color=THEME.comp_time,
            text_color=THEME.text_dark,
            size_hint_y=None,
            height=dp(50)
        )

        def save(inst):
            try:
                new_val = int(years_input.text) if years_input.text else 0
                if new_val >= 0:
                    self.repo.years_of_service = new_val
                popup.dismiss()
            except ValueError:
                pass

        btn.bind(on_press=save)
        content.add_widget(btn)

        self._track_popup(popup)
        popup.open()

    def popup_auto_year(self, instance) -> None:
        first_wd = None
        for d in range(1, 8):
            dt = date(self.current_year, 1, d)
            if dt.weekday() <= 4:
                first_wd = dt
                break

        if not first_wd:
            return

        content = BoxLayout(
            orientation='vertical',
            spacing=dp(UI.large_spacing),
            padding=[dp(16), dp(16), dp(16), dp(16)]
        )

        info_card = self._create_popup_card(
            bg_color=THEME.bg_card_elevated,
            border_color=THEME.warning,
            spacing=dp(6)
        )
        title_lbl = Label(
            text=f"Godina: {self.current_year}",
            color=get_color_from_hex(THEME.text_primary),
            font_size=sp(UI.large_font),
            bold=True,
            size_hint_y=None,
            height=dp(26),
            halign='left', valign='middle'
        )
        title_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        detail_lbl = Label(
            text=f"Prvi radni dan: {first_wd.strftime('%d.%m.')}",
            color=get_color_from_hex(THEME.text_secondary),
            font_size=sp(UI.small_font),
            size_hint_y=None,
            height=dp(20),
            halign='left', valign='middle'
        )
        detail_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        info_card.add_widget(title_lbl)
        info_card.add_widget(detail_lbl)
        content.add_widget(info_card)

        state = {'start_shift': Shift.FIRST}

        shift_card = self._create_popup_card()
        shift_card.add_widget(self._create_section_label("Pocetna smena"))

        shift_row = BoxLayout(
            size_hint_y=None,
            height=dp(44),
            spacing=dp(6)
        )
        shift_btns = []
        for s in [Shift.FIRST, Shift.SECOND, Shift.THIRD]:
            is_selected = s == state['start_shift']
            btn = RoundedButton(
                text=s.display_name, font_size=sp(UI.small_font), bold=True,
                bg_color=s.color if is_selected else s.color + "33",
                text_color=s.text_color,
                radius=14
            )
            btn.shift_value = s
            shift_btns.append(btn)
            shift_row.add_widget(btn)

        def on_shift_click(btn):
            state['start_shift'] = btn.shift_value
            for b in shift_btns:
                is_sel = b.shift_value == state['start_shift']
                b.set_bg_color(
                    b.shift_value.color if is_sel else b.shift_value.color + "33"
                )

        for btn in shift_btns:
            btn.bind(on_release=on_shift_click)

        shift_card.add_widget(shift_row)
        content.add_widget(shift_card)

        popup = ModernPopup(
            title="Auto-popuna godine",
            content=content,
            size_hint=(0.85, UI.sick_popup_height_ratio)
        )

        btn = GlowButton(
            text="Popuni celu godinu",
            bg_color=THEME.warning,
            text_color=THEME.text_dark,
            size_hint_y=None,
            height=dp(50)
        )

        def run_year(inst):
            start_shift = state['start_shift']
            self.scheduler.populate_year(self.repo, self.current_year, start_shift)
            self.current_month = 1
            popup.dismiss()

        btn.bind(on_press=run_year)
        content.add_widget(btn)

        self._track_popup(popup)
        popup.open()

    def popup_delete_month(self, instance) -> None:
        content = BoxLayout(
            orientation='vertical',
            spacing=dp(UI.large_spacing),
            padding=[dp(20), dp(20), dp(20), dp(20)]
        )

        alert_card = self._create_popup_card(
            bg_color=THEME.bg_card_elevated,
            border_color=THEME.danger,
            spacing=dp(8),
            padding=[dp(14), dp(12)]
        )
        icon_lbl = Label(
            text="!",
            font_size=sp(44),
            color=get_color_from_hex(THEME.danger),
            size_hint_y=None,
            height=dp(44),
            halign='center',
            valign='middle'
        )
        icon_lbl.bind(size=lambda i, s: setattr(i, 'text_size', s))

        msg_label = Label(
            text=f"Da li ste sigurni da zelite da obrisete sve podatke za {MONTH_NAMES[self.current_month]}?",
            color=get_color_from_hex(THEME.text_primary),
            font_size=sp(UI.small_font),
            halign='center',
            valign='middle',
            size_hint_y=None,
            height=dp(64),
            text_size=(dp(250), None)
        )
        msg_label.bind(size=lambda i, s: setattr(i, 'text_size', s))

        alert_card.add_widget(icon_lbl)
        alert_card.add_widget(msg_label)
        content.add_widget(alert_card)

        btn_row = BoxLayout(
            spacing=dp(UI.large_spacing),
            size_hint_y=None,
            height=dp(48)
        )

        cancel_btn = RoundedButton(
            text="Otkazi",
            font_size=sp(UI.small_font),
            bold=True,
            size_hint_y=None,
            height=dp(48),
            bg_color=THEME.bg_card_elevated,
            text_color=THEME.text_primary,
            border_color=THEME.glass_border,
            radius=14
        )

        confirm_btn = GlowButton(
            text="Obrisi",
            bg_color=THEME.danger,
            text_color=THEME.text_primary,
            size_hint_y=None,
            height=dp(48)
        )

        btn_row.add_widget(cancel_btn)
        btn_row.add_widget(confirm_btn)
        content.add_widget(btn_row)

        popup = ModernPopup(
            title="Potvrda brisanja",
            content=content,
            size_hint=(0.85, None),
            height=dp(UI.delete_popup_height)
        )

        cancel_btn.bind(on_press=lambda x: popup.dismiss())

        def confirm(inst):
            self.repo.delete_month(self.current_year, self.current_month)
            popup.dismiss()

        confirm_btn.bind(on_press=confirm)

        self._track_popup(popup)
        popup.open()

    def on_stop(self) -> None:
        self.repo.save()


# =============================================================================
# ENTRY POINT
# =============================================================================

def main():
    ShiftTrackerApp().run()


if __name__ == "__main__":
    main()
