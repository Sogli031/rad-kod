# Android akcioni plan — rad (posle audita 04.10.2026.)

Redosled po odnosu korist/trud. S = mali, M = srednji, L = veliki zahvat.

## P0 — zaštita podataka
1. **Testovi za Drive backup/restore (M).** Interfejs nad Drive pozivima (list/download/create/delete) + fake; testovi: download vrati null → prekid; prune ne briše novu verziju; safety backup ne označava adopciju; sveža instalacija sa verzijama u cloudu → `NEEDS_USER_DECISION`. Dodati `data/backup/**` u Kover pravilo.
2. **Robusnost lokalnog snimanja (S).** Robolectric testovi za `AtomicTextFile` (prekinut upis čuva staro), `LocalShiftStorage` (oštećen JSON → karantin, ne prazno; 0-bajtni fajl).
3. **Šifrovanje podataka u mirovanju (M).** AES-256-GCM ključ u Android Keystore-u za `work_shifts`, Gmail keš listića i snapshot prefs; migracija postojećih fajlova uz rezervnu kopiju.

## P1 — brzi dobici
4. **Smanjiti APK (S).** `packaging.resources.excludes += "org/bouncycastle/pqc/**"` (+ eventualno CJK cmap asseti); prethodno proveriti uvoz svih listića iz `liste/`.
5. **Suziti R8 keep pravila (S).** Ukloniti `proguard-rules.pro:10-11`, pdfbox keep svesti na font/filter/cmap pakete; proveriti dex i parsiranje listića.
6. **Uklanjanje logova u release-u (S).** `-assumenosideeffects class android.util.Log { d, i, v, w }`.
7. **Dependency verification (S).** `gradlew --write-verification-metadata sha256 help`, commit `gradle/verification-metadata.xml`.
8. **`release.ps1` + guard (S).** Provera čistog working tree-a, `git tag v<verzija>`; guard za potpis i na `bundle*Release`.
9. **Edge-to-edge (S).** `enableEdgeToEdge()` umesto ručnog koda u `MainActivity.kt:27-35`; insets u `CrashLogScreen`.

## P2 — glavna nit i arhitektura
10. **`MainViewModel` bez sinhronog `compute` (S).** Početno stanje `null`/Loading, popunjava postojeći `collectLatest`.
11. **`loadFromLocal` van main niti (M).** Splash/loading stanje dok se JSON ne učita.
12. **`DayEditViewModel` i `SeniorityViewModel` (M).** Čitanja u `StateFlow` na `Dispatchers.Default`, snimanja u `viewModelScope`; do tada barem `remember` oko `getSummary`/`calculateEstimated*` u `DayEditDialog`.
13. **`GmailImportRunner` → `CoroutineWorker` (S)**, kao `BackupWorker`; `collectAsStateWithLifecycle()` na `GmailOnboarding.kt:718`.
14. **Thread-safe keševi u repozitorijumu (S)** ili garancija da se repo poziva samo sa jedne niti.
15. **Raspored paketa (M, mehanički commit bez logike):** `domain/pay`, `domain/payslip`, `domain/schedule`, `ui/dialogs`.

## P3 — kvalitet i UI
16. **Baseline profil + Macrobenchmark (M)** za start i otvaranje dijaloga dana.
17. **Kover pravilo** proširiti na solver, breakdown, `VacationTracking`, `RepositoryCompBalanceCalculator`; testovi za `minutesAtMonthStart` preko granice godine.
18. **`verifyRoborazziDebug` u CI (S)** i Compose testovi za tok Popuni → potvrda i restore → potvrda.
19. **Pristupačnost (S):** `heading()` na naslov meseca i sekcije; `Role.Tab` + selected u donjoj traci; `selectableGroup()`; podići/ukloniti font cap 1.15 u kalendaru.
20. **Dizajn (S):** odluka o svetloj temi (pratiti sistem ili obrisati), konstante za ponovljene 44/40/18dp, tipografski stilovi u `CrashLogScreen`.

## Ne raditi
- Hilt, Navigation biblioteka, više modula — trošak bez koristi za jedan ekran + dijaloge.
- `enableOnBackInvokedCallback="false"` — opt-out koji Android 16 ukida.
