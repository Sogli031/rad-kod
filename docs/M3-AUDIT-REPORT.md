# MD3 Compliance Audit Report

Target: `app/src/main/java/com/rad/shifttracker` (Compose, verzija 5.4 + nadogradnja na material3 1.4.0, nije objavljena)
Date: 2026-10-03 · metod: material-3 skill, statička provera izvora (grep po izvoru, testovi-čuvari) + provera na Pixel 9 (tamna/svetla tema, font 1.0/1.5, 864dp)
Overall Score: **77/100** (75 u 5.4; pre toga 65, 60 i 38)

## Scores by Category
| Category       | Score | Status | Ukratko |
|----------------|-------|--------|---------|
| Color tokens   | 9/10  | pass | 0× `Color(0x` van `Theme.kt`; sve iz `colorScheme`/`extendedColors`; `onShiftBright/onShiftDeep` uloge; kontrast obe teme pod testom |
| Typography     | 7/10  | pass | M3 skala + `*Emphasized`, 0× ručni `FontWeight`, tekst u ćeliji min 11sp; ostaje 27× `AutoSizeText` i 12 `sp` literala |
| Shape          | 9/10  | pass | 0× ručni `RoundedCornerShape`, kartice `shapes.medium`, dugmad `CircleShape`, segmentirani izbor |
| Elevation      | 8/10  | pass | Samo tonalni `surfaceContainer*` nivoi, 0 senki |
| Components     | 6/10  | warn | M3: `Button`, `OutlinedCard`, `SingleChoiceSegmentedButtonRow`, `Switch`, `Checkbox`, `OutlinedTextField`, `Snackbar`; ručno: donja traka, sheet u `Dialog`-u, izbor tipa dana (5 dugmadi) |
| Layout         | 8/10  | pass | `Spacing` 4dp, skrol cele strane, ćelije ≥ 48dp, `WindowSizeClass` (adaptive 1.2) → dve kolone i panel izabranog dana od 840dp, max širina 600/1200dp; nema foldable/hinge logike |
| Navigation     | 6/10  | warn | Predictive back uključen, sheet ima nazad-gest; 21 `DialogState` bez back stack-a, bez `NavigationBar`/`NavHost` |
| Motion         | 7/10  | pass | Spring (`RadMotion`, vrednosti `MotionScheme.standard()`) za sheet i prelaz meseca, „ukloni animacije“ poštovano; sam `MotionScheme` je u 1.4 interni |
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
3. **Pokret (7):** spring je u `Motion.kt` sa vrednostima M3 standardne šeme; `MotionScheme` postaje javan tek u material3 1.5 (samo alfa, 1.5.0-alpha29).
4. **Tipografija (7):** `AutoSizeText` (27 mesta) umesto fiksnih stilova; 12 `sp` literala (oznake u ćeliji, `AutoSizeText` granice). Zvanični emphasized stilovi stižu sa 1.4.
5. **Layout (8):** 72 `dp` literala za veličine (visine, ikone), razmaci su u `Spacing`; nema logike za preklopljen uređaj (hinge).

## Passing
- Boje, oblici, elevacija, razmaci i težina fonta su pod testovima-čuvarima (`DesignSystemTest`), kontrast boja smene i teksta pod `ThemeContrastTest` (≥ 4.5:1, obe teme).
- Svetla tema pregledana na telefonu: ćelije, kartice, dijalog dana, Opcije; pozadina ide i ispod statusne/navigacione trake.
- Font 1.5 na 360dp: kalendar i lista skrolaju, statistika 2×2, donja traka 3×2, ništa odsečeno osim „Prekovremeno“ u statistici na uskom prikazu 1.0 (skraćeno tačkicama).

## Recommended Fixes (Priority Order)
Urađeno posle 5.4: material3 1.4.0 (BOM 2025.12.01, compileSdk 36), spring pokret, `WindowSizeClass` + panel izabranog dana.
| # | Stavka | Kategorije | Procena |
|---|--------|------------|---------|
| 1 | **material3 1.5 stabilan** (sada samo alfa): javan `MotionScheme`, expressive komponente (`ButtonGroup`, plutajuća traka sa akcijama) | Motion, Components | +3 |
| 2 | Donja traka kao M3 komponenta — `NavigationBar`/`BottomAppBar` ne staju (6 akcija sa nazivima, fiksna visina), pa tek expressive traka iz 1.5 | Components, Navigation | +2 |
| 3 | Navigacija kroz dijaloge (`NavHost`/back stack umesto 21 `DialogState`) + `ModalBottomSheet` | Navigation, Components | +2 |
| 4 | `AutoSizeText` → fiksni M3 stilovi | Typography | +1 |
| 5 | Nivoi kontrasta (medium/high) za šemu | Theming, A11y | +1 |

Bez 1.5 i bez prepravke navigacije realan plafon je **≈ 80/100**; 85 traži stavke 1–3.
