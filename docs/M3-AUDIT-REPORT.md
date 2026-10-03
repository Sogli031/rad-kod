# MD3 Compliance Audit Report

Target: `app/src/main/java/com/rad/shifttracker` (Compose, verzija 5.3)
Date: 2026-10-03 · metod: material-3 skill, statička provera izvora + Roborazzi snimci (360/411/840dp, font 1.0/1.5/2.0)
Overall Score: **65/100** (prethodno 60; pre toga 38)

## Scores by Category
| Category       | Score | Status | Ukratko |
|----------------|-------|--------|---------|
| Color tokens   | 8/10  | pass | Sve boje iz `colorScheme`/`extendedColors`, test zabranjuje `THEME.`/`Color(0x`; jedno uparivanje van uloge (vidi Warnings) |
| Typography     | 6/10  | warn | Skala + `*Emphasized`, bez ručnog `fontWeight`; ali 27× `AutoSizeText` spušta tekst do 9sp i 14 `sp` literala |
| Shape          | 9/10  | pass | 0 ručnih `RoundedCornerShape`, kartice `shapes.medium`, dugmad `CircleShape` (full) |
| Elevation      | 8/10  | pass | Dubina samo kroz tonalne `surfaceContainer*` nivoe, nema senki |
| Components     | 5/10  | warn | Pravi M3: `Button`, `Switch`, `Checkbox`, `OutlinedTextField`, `Snackbar`, progres; ručno: kartica, donja traka, sheet, izbor smene |
| Layout         | 5/10  | warn | `Spacing` na 4dp mreži, max širina 600dp; nema `WindowSizeClass`, font 2.0 lomi raspored |
| Navigation     | 6/10  | warn | Predictive back u manifestu, sheet ima nazad-gest; navigacija je 21 `DialogState` bez back stack-a |
| Motion         | 6/10  | warn | Emphasized easing za sheet i prelaz meseca (shared axis); nema `MotionScheme`/spring, nema „ukloni animacije“ |
| Accessibility  | 7/10  | pass | 48dp mete (test), `toggleable` redovi, `liveRegion`, kontrast testiran; kalendar ograničava font na 1.15 i ćelije < 48dp |
| Theming        | 5/10  | warn | `MaterialTheme` + `ExtendedColors` CompositionLocal (spremno); samo tamna tema, nema dynamic color ni praćenja sistema |

## Critical Issues
Nema (ništa ≤ 3).

## Warnings
1. **Komponente (5):**
   - `GlassCard` (`UiComponents.kt`) je `Box` + `background` + `border` → `OutlinedCard` (isti izgled: `surfaceContainer`, `outlineVariant`, `shapes.medium`).
   - Izbor smene i tipa dana u `DayEditDialog.kt` su `DayEditChoiceButton` → `SingleChoiceSegmentedButtonRow` (smena) / ostaju radio-dugmad (tip dana, 5 izbora).
   - `BottomBar` (`ShiftDashboardCards.kt`) je ručni `Column`+`Row` → `BottomAppBar` (to su radnje, ne destinacije, pa ne `NavigationBar`).
   - `PopupDialog` je ručni sheet u `Dialog` prozoru → `ModalBottomSheet` **tek posle provere tastature na telefonu** (Faza 1).
2. **Layout + tipografija (5/6):** snimak `main_w360_f200` — dani u nedelji se slepe („UTOSREČETPET“), oznake statistike
   odsečene, donja traka „Po…/Op…“; `CALENDAR_MAX_FONT_SCALE = 1.15f` ignoriše sistemski font; `AutoSizeText` min 9–10sp.
   Rešenje je Faza 5.3 (ćelija ≥ 48dp, skrol cele strane, min 11sp).
3. **Theming (5):** nema svetle teme ni dynamic color (Faza 5.2). Infrastruktura postoji (`RadDarkColorScheme`,
   `DarkExtendedColors`), treba `RadLightColorScheme` + `LightExtendedColors` + izbor u Opcijama.
4. **Motion (6):** `MotionScheme` (spring) za komponente; poštovati `Settings.Global.ANIMATOR_DURATION_SCALE == 0`.
5. **Navigation (6):** `DialogState` (21 stanje) je ručna navigacija; prihvatljivo za jedan ekran sa listovima, ali
   dvopanelni prikaz (≥ 840dp) traži stanje „izabran dan“ odvojeno od dijaloga.
6. **Color pairing (8):** `Shift.textColor` koristi `onPrimary` na punoj boji smene (npr. `shift2`, `vacation`) —
   kontrast je dobar, ali uloga nije par; u svetloj temi to puca. Uvesti `onShiftX` u `ExtendedColors`.

## Passing
- Kontrast (tamna tema, izračunato): broj dana u boji smene na toniranoj ćeliji (alpha 0.18 na `surfaceContainer`):
  Prva 5.9, Druga 8.0, Treća 4.9, Bolovanje 4.9, Godišnji 7.2 — sve ≥ 4.5:1; detalj (`textSecondary`) ≥ 7.6.
  `textFaint` 5.7, `onSurfaceVariant` 6.9 na kartici. **Nije još automatski test** (dodati sa 5.2, za obe teme).
- Oblici, elevacija, razmaci i boje su pod testovima-čuvarima u `DesignSystemTest`.

## Recommended Fixes (Priority Order)
| # | Stavka | Kategorije | Procena | Telefon? |
|---|--------|------------|---------|----------|
| 1 | **5.3 kalendar:** ćelija ≥ 48dp, bez `CALENDAR_MAX_FONT_SCALE`, skrol cele strane, min 11sp; zaglavlje dana i statistika koji ne lome na fontu 2.0 | Layout, Typography, A11y | +5 | snimci; skrol 📱 |
| 2 | **5.5 komponente:** `OutlinedCard`, `SingleChoiceSegmentedButtonRow`, `BottomAppBar` | Components | +3 | snimci |
| 3 | **5.2 svetla tema + dynamic color + „prati sistem“**, `onShiftX` uloge, test kontrasta za sve boje × obe teme | Theming, Color | +4 | izbor palete (ti) |
| 4 | **5.8 pokret:** `MotionScheme.standard()`/spring, „ukloni animacije“ | Motion | +2 | 📱 za osećaj |
| 5 | **5.7 adaptivno:** `WindowSizeClass`, ≥ 840dp kalendar + detalji dana | Layout, Navigation | +2 | snimak 840dp |
| 6 | `ModalBottomSheet` (posle Faze 1) | Components | +1 | 📱 |

Posle 1–5: procena **≈ 84–86/100**. Ostatak do 90+ traži material3 1.4+ (zvanični emphasized stilovi, expressive
komponente) — to je Faza 4.6 (nadogradnja BOM-a).
