# Android audit — rad (v5.6), 04.10.2026.

Statički audit (skill `android`), dokazi u `generated/audit-context.json`. Bez runtime merenja (vitals, trace, screenshot) — performanse, dizajn i pristupačnost su preflight.

## Ocena

| Oblast | Težina | Ocena |
|---|---|---|
| Arhitektura | 15% | 58 |
| Performanse | 15% | 68 |
| Bezbednost | 15% | 70 |
| Kompatibilnost (Android 15/16) | 10% | 88 |
| Dizajn sistem | 10% | 82 |
| Pristupačnost | 10% | 78 |
| Testovi | 10% | 71 |
| Build | 10% | 78 |
| Play preflight | 5% | n/a (distribucija preko GitHub-a) |
| **Ukupno (renormalizovano na 95%)** | | **≈ 73 / 100** |

Gate C1 („exported komponenta bez dozvole") je **lažno pozitivan**: `MainActivity` ima samo MAIN/LAUNCHER filter i ne čita spoljne Intent podatke → cap od 40 nije primenjen.

## Verifikovani statički nalazi

### Arhitektura (58)
- Composable-i direktno čitaju/pišu `repo` na UI niti tokom kompozicije: `DayEditDialog.kt:321,365,383-384` (`getSummary`, `calculateEstimatedDailyWorkNetPay` bez `remember`), `SeniorityDialog.kt:44-62,489-493`, `MonthlyWorkAndPayExport` (43 poziva), `PayslipImport` (26), `GmailOnboarding` (26).
- `MainViewModel.kt:74` radi sinhroni `compute()` u konstruktoru (~20 upita) na glavnoj niti.
- `GmailImportRunner` (`GmailOnboarding.kt:460-482`) — globalni `object` sa sopstvenim scope-om, van lifecycle-a; `GmailOnboarding.kt:718` `collectAsState()` bez lifecycle-a.
- God-fajlovi: `PayCalculator.kt` 1254, `DayEditDialog.kt` 1022, `ShiftRepositoryJsonCodec.kt` 970, `ShiftTrackerScreen.kt` 923, `GmailOnboarding.kt` 814.
- 107 od 143 fajla u root paketu; domen (parser, kalkulatori, solver) pomešan sa UI.

### Performanse (68)
- Gate H2: nema baseline profila ni Macrobenchmark modula.
- `RadApplication.kt:59` — `loadFromLocal` (čitanje + parsiranje celog JSON-a) na glavnoj niti pre `setContent`.
- `MainActivity.kt:115` → `flushPendingPersistence()` može blokirati `onStop` do 2 s.
- Keševi u repozitorijumu su obični `mutableMapOf` (`RepositoryPayState.kt:61`), a koriste se i sa `Dispatchers.Default` (ViewModel) i sa main niti (dijalozi).

### Bezbednost (70)
- **M9:** platni podaci (JMBG, plata) nešifrovani u `filesDir`: `LocalShiftStorage.kt:20,34`, `GmailPayslipCache.kt:104` (30 meseci listića), `PayslipSnapshotStore.kt:16`.
- `gmail.readonly` scope (`GmailPayslips.kt:45-46`) — restriktivan, čita ceo inbox; token se ne čuva (dobro).
- Nema `gradle/verification-metadata.xml`.
- Logovi sa ličnim podacima nisu uklonjeni u release-u (nema `-assumenosideeffects`): `GmailOnboarding.kt:173`, `ShiftRepositoryPaySettings.kt:22,110,135`.
- Prolazi: cleartext zabranjen, backup isključen, PendingIntent `FLAG_IMMUTABLE`, PDF limit 10 MB / 10 strana, nema praćenih tajni u git-u.

### Build (78)
- APK 10,6 MB (21,5 MB raspakovano): BouncyCastle `org/bouncycastle/pqc/**` ≈ 8,2 MB (`lowmc.properties` 3,6 MB) + pdfbox CJK cmap asseti ≈ 4,6 MB — ne koriste se.
- Preširoki keep-ovi: `proguard-rules.pro:10-11` (Drive/Gmail modeli, redundantno uz liniju 12), `:19/22` ceo `com.tom_roush.pdfbox.**` → `mapping.txt` 58 MB.
- Guard za potpisivanje (`app/build.gradle.kts:18-26`) hvata samo `assemble*Release`, ne `bundleRelease`.
- `kotlinOptions { jvmTarget }` (`:72-74`) deprecated; lifecycle verzija ponovljena 3× u katalogu.
- `release.ps1` ne proverava čist working tree i ne pravi git tag.

### Testovi (71)
- 80 fajlova / 1.121 testova; novac, parser listića, solver, merge backup-a i godišnji dobro pokriveni.
- **Bez testova:** `data/backup/GoogleDriveBackup.kt` (513 linija — `runVersionedBackup`, `autoBackupGate`, `restoreVersion`), `BackupWorker.kt`, `AtomicTextFile.kt`, `LocalShiftStorage.kt`.
- Kover 90% pravilo pokriva samo 7 klasa; van njega su `PayslipMonthFillSolver`, `MonthlyNetPayBreakdownCalculator`, `PayBreakdown`, `VacationTracking`, `data/backup/**`.
- CI ne pokreće `verifyRoborazziDebug` (10 snimaka postoji, ne porede se). `MainViewModelTest` ima 2 testa.

### Pristupačnost (78)
- Oba touch-target upozorenja su lažna (`DayEditDialog.kt:525` ima `heightIn(min=48dp)`, `PopupComponents.kt:166` je scrim).
- Samo jedan `heading()` (`PopupComponents.kt:301`) — naslov meseca i sekcija bez heading semantike.
- `ShiftCalendar.kt:56/63` — `CALENDAR_CELL_MAX_FONT_SCALE = 1.15f` ograničava sistemsku veličinu fonta.
- Donja traka bez `Role.Tab`/selected; radio izbori bez `selectableGroup()`.
- Statusne tačke u kalendaru samo bojom (TalkBack opis postoji).

### Dizajn (82)
- Svih 77 `Color(0x…)` u `Theme.kt`; 270 tipografskih referenci; Spacing tokeni korišćeni.
- `RadTheme` (`Theme.kt:295`) uvek tamna — `RadLightColorScheme` i `LightExtendedColors` mrtav kod.
- ~78 sirovih `.dp` van tokena (najviše `BackupVersionsDialog.kt`), 12 sirovih `.sp`; `CrashLogScreen.kt` zaobilazi sopstvene komponente.

### Kompatibilnost (88)
- `MainActivity.kt:27-35` ručni edge-to-edge + deprecated `statusBarColor`/`navigationBarColor` (no-op na API 35+) → `enableEdgeToEdge()`.
- `CrashLogScreen.kt:33-60` bez insets-a — dno ispod navigacione trake na API 35+.
- Prolazi: neegzaktni alarmi bez `SCHEDULE_EXACT_ALARM`, POST_NOTIFICATIONS u kontekstu, bez FGS, 16 KB poravnanje provereno u `release.ps1`, bez ograničenja orijentacije.

## Preflight upozorenja (zahtevaju potvrdu)
- Stvarni trošak `loadFromLocal` i `getSummary` u kompoziciji — potreban startup/Perfetto trace.
- Kontrast boja (procena ~5–6:1 za `textFaint`) — screenshot provera.
- Predictive back sa otvorenim popup-om i tastaturom — test na API 36 uređaju.
- Širina ćelije kalendara ≥ 48dp na 360dp ekranu.

## Potrebni spoljni dokazi
- Android Vitals (crash/ANR stopa) — gate C5/C6 nerazrešeni.
- Merged manifest (dozvole iz biblioteka), potpis release APK-a (`apksigner verify --print-certs`).
- Status verifikacije OAuth consent ekrana za `gmail.readonly`.
- CVE provera za pdfbox-android 2.0.27.0.
