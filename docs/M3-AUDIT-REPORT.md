# MD3 Compliance Audit Report

Target: `app/src/main/java/com/rad/shifttracker` (Compose, verzija 5.4)
Date: 2026-10-03 · metod: material-3 skill, statička provera izvora (grep po izvoru, testovi-čuvari) + provera na Pixel 9 (tamna/svetla tema, font 1.0/1.5, 864dp)
Overall Score: **75/100** (prethodno 65 u 5.3; pre toga 60 i 38)

## Scores by Category
| Category       | Score | Status | Ukratko |
|----------------|-------|--------|---------|
| Color tokens   | 9/10  | pass | 0× `Color(0x` van `Theme.kt`; sve iz `colorScheme`/`extendedColors`; `onShiftBright/onShiftDeep` uloge; kontrast obe teme pod testom |
| Typography     | 7/10  | pass | M3 skala + `*Emphasized`, 0× ručni `FontWeight`, tekst u ćeliji min 11sp; ostaje 27× `AutoSizeText` i 12 `sp` literala |
| Shape          | 9/10  | pass | 0× ručni `RoundedCornerShape`, kartice `shapes.medium`, dugmad `CircleShape`, segmentirani izbor |
| Elevation      | 8/10  | pass | Samo tonalni `surfaceContainer*` nivoi, 0 senki |
| Components     | 6/10  | warn | M3: `Button`, `OutlinedCard`, `SingleChoiceSegmentedButtonRow`, `Switch`, `Checkbox`, `OutlinedTextField`, `Snackbar`; ručno: donja traka, sheet u `Dialog`-u, izbor tipa dana (5 dugmadi) |
| Layout         | 7/10  | pass | `Spacing` 4dp, skrol cele strane, ćelije ≥ 48dp, dve kolone od 840dp, max širina 600/1200dp; nema `WindowSizeClass` ni foldable/hinge logike |
| Navigation     | 6/10  | warn | Predictive back uključen, sheet ima nazad-gest; 21 `DialogState` bez back stack-a, bez `NavigationBar`/`NavHost` |
| Motion         | 6/10  | warn | Emphasized easing, shared axis za mesec, „ukloni animacije“ poštovano; nema `MotionScheme`/spring |
| Accessibility  | 8/10  | pass | 48dp mete, `toggleable` redovi, `liveRegion`, pun opis dana, kontrast testiran u obe teme, font prati sistem; TalkBack nije ručno prošao |
| Theming        | 9/10  | pass | Tamna / svetla / sistem + dynamic color (Android 12+) u Opcijama, `ExtendedColors` po temi; nema nivoa kontrasta (medium/high) |

## Critical Issues
Nema (ništa ≤ 3).

## Warnings
1. **Komponente (6):** `BottomBar` je ručni `Column`+`Row` (`BottomAppBar` ima fiksnu visinu 80dp, pa ne raste sa fontom — svesno ostavljeno);
   `PopupDialog` je ručni sheet u `Dialog` prozoru (tastatura i isečak podešeni); tip dana u `DayEditDialog.kt` je 5 dugmadi (`DayEditChoiceButton`),
   a ne segmentirani izbor (5 opcija je previše za jedan red).
2. **Navigacija (6):** `DialogState` (21 stanje) je ručna navigacija; prihvatljivo za jedan ekran sa listovima, ali nema back stack-a ni
   „izabran dan“ kao zasebnog stanja (dvokolonski prikaz ≥ 840dp pokazuje iznose, ne detalje dana).
3. **Pokret (6):** komponente nemaju spring; `MotionScheme` traži material3 1.4 (BOM 2025.05.01 ima 1.3) — Faza 4.6.
4. **Tipografija (7):** `AutoSizeText` (27 mesta) umesto fiksnih stilova; 12 `sp` literala (oznake u ćeliji, `AutoSizeText` granice). Zvanični emphasized stilovi stižu sa 1.4.
5. **Layout (7):** `UI.wideLayoutMinWidth = 840` je ručni prag umesto `WindowSizeClass`; 72 `dp` literala za veličine (visine, ikone), razmaci su u `Spacing`.

## Passing
- Boje, oblici, elevacija, razmaci i težina fonta su pod testovima-čuvarima (`DesignSystemTest`), kontrast boja smene i teksta pod `ThemeContrastTest` (≥ 4.5:1, obe teme).
- Svetla tema pregledana na telefonu: ćelije, kartice, dijalog dana, Opcije; pozadina ide i ispod statusne/navigacione trake.
- Font 1.5 na 360dp: kalendar i lista skrolaju, statistika 2×2, donja traka 3×2, ništa odsečeno osim „Prekovremeno“ u statistici na uskom prikazu 1.0 (skraćeno tačkicama).

## Recommended Fixes (Priority Order)
| # | Stavka | Kategorije | Procena |
|---|--------|------------|---------|
| 1 | **material3 1.4+** (BOM nadogradnja): `MotionScheme` (spring), zvanični emphasized stilovi, `ButtonGroup`/expressive komponente | Motion, Typography, Components | +4 |
| 2 | `WindowSizeClass` umesto ručnog praga; stanje „izabran dan“ i list-detail na ≥ 840dp | Layout, Navigation | +3 |
| 3 | Donja traka u `BottomAppBar`/`NavigationBar` (kad 1.4 dozvoli visinu koja raste sa fontom) | Components, Navigation | +2 |
| 4 | `AutoSizeText` → fiksni M3 stilovi + prelom u dva reda gde može | Typography | +1 |
| 5 | Nivoi kontrasta (medium/high) za šemu | Theming, A11y | +1 |

Posle 1–3: procena **≈ 85/100**; do 90+ traži zamenu ručnog sheet-a (`ModalBottomSheet`) i `NavHost` za dijaloge.
