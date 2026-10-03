# Android audit — Rad (posle plana „production grade“)

Datum: 2026-10-04 · verzija koda: 5.5 + izmene za 6.0 (versionCode 46, neobjavljeno) · metod: `android` skill
(`scan_project`, `analyze_*`, `check_r8_config`, `build_audit_context`, `score.py`) nad čistom kopijom commitovanog stanja,
plus ručna provera nalaza. Sve je **statička** provera izvora; ništa nije mereno na uređaju. Prethodni audit (2026-10-03, v5.0) je u git istoriji.

## Ocena

| Kategorija | Težina | Pre (v5.0) | Sada | Težinski |
|---|---|---|---|---|
| Arhitektura | 15 | 40 | 62 | 9.3 |
| Performanse | 15 | 50 | 68 | 10.2 |
| Bezbednost | 15 | 70 | 80 | 12.0 |
| Kompatibilnost | 10 | 80 | 82 | 8.2 |
| Dizajn sistem (M3 audit 77/100) | 10 | 60 | 77 | 7.7 |
| Pristupačnost | 10 | 65 | 72 | 7.2 |
| Testiranje | 10 | 65 | 76 | 7.6 |
| Build | 10 | 55 | 80 | 8.0 |
| Play preflight (sideload, nije relevantno) | 5 | 40 | 50 | 2.5 |
| **Zbir** | | **58.5** | | **72.7** |

**Završna ocena skill-a: 40/100** zbog gornje granice za kritični gate. Taj gate je lažan (vidi dole), pa je stvarna ocena **72.7**.
Cilj plana (≥ 80) nije dostignut; razlog je Arhitektura (62) i Performanse (68), ne dizajn ni bezbednost.

## Gate-ovi

| Gate | Ozbiljnost | Stvaran? |
|---|---|---|
| C1 izvezena komponenta bez dozvole | critical | **Lažan** — `MainActivity` je LAUNCHER; ne može se zadovoljiti ni u jednoj aplikaciji sa ikonom |
| H2 nema benchmark/Baseline Profile | high | **Stvaran** — jedini otvoren pravi gate |
| H3 nizak pouzdan rizik dodirne mete | high | **Lažan** — oba mesta proverena: `DayEditDialog.kt:531` ima `heightIn(min = MIN_TOUCH_TARGET)`, `PopupComponents.kt:166` je ceo providni sloj iza lista |
| C5/C6 Vitals (crash/ANR) | critical | traži spoljne podatke (Play Console); aplikacija se ne distribuira kroz Play |
| ~~C7 release bez shrink~~ | — | **rešeno** (R8 + shrink, APK 27 → 10,5 MB) |
| ~~H6 network security config~~ | — | **rešeno** (`cleartextTrafficPermitted=false`) |

## Šta se promenilo od prošlog audita

- Release: R8 i shrink, potpis samo zajedničkim ključem (build pada bez njega), 16 KB poravnanje proveren, `release.ps1` sa `apksigner`/`zipalign`.
- Dozvole: nema `MANAGE_EXTERNAL_STORAGE` ni READ/WRITE_EXTERNAL; crash log samo u privatnom folderu, bez poruka izuzetaka.
- Podaci: restore guard, `schema_version` + migracije, atomski upis, `Clock`.
- Arhitektura: `MainViewModel`, `ShiftRepository` 2509 → 395 linija, mrtav kod (−205 linija) uklonjen, `collectAsStateWithLifecycle` svuda.
- Build: version catalog (50 unosa, 0 inline verzija), AGP 8.13 / Kotlin 2.1.21 / Gradle 8.13, `targetSdk` 36, Gradle cache/parallel/configuration-cache, nove verzije Google biblioteka.
- Testovi: 1127 testova, Robolectric UI, Roborazzi snimci, Kover prag 90% na obračunu i podacima (92,7%).
- Nalaz ovog audita, popravljen odmah: `BloodDonationReminderReceiver` nije ponovo zakazivao podsetnik posle nadogradnje aplikacije ni promene vremena
  i čitao je lokalni fajl na glavnoj niti → dodati `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` i `goAsync()` sa radnom niti.

## Nalazi po kategorijama

### Arhitektura (62)
- **Medium** Nema DI/kontejnera: `RadApplication` drži `ShiftRepository` i `GoogleDriveBackup` kao singletone, a dijalozi i kalendar primaju `repo` direktno
  (`CalendarSection`, `MainDialogs`, 21 `DialogState` bez back stack-a).
- **Medium** `ShiftRepository` je i dalje jedna fasada: logika je u fajlovima proširenja (`ShiftRepository*.kt`), ali stanje i zaključavanje su zajednički (`internal` polja, `synchronized(lock)`); interfejsi po domenima nisu uvedeni.
- **Low** `MainActivity` pokreće provere (`checkSideRunsOnEntry`) iz aktivnosti, a `GmailImportRunner`/`SideRunMessages` su globalni singletoni.
- Dobro: `MainViewModel` + `StateFlow`, izvedeni podaci van glavne niti, 0 `LiveData`, 0 kapt/ksp.

### Performanse (68)
- **High (gate H2)** Nema Baseline Profile ni Macrobenchmark modula; hladan start nije meren.
- **Medium** 36 data klasa sa kolekcijama; stability config pokriva `java.time`, `BigDecimal`, kolekcije (nestabilnih klasa 107 → 31, preostale su servisi/repo), strong skipping je uključen.
- **Low** `AutoSizeText` (27 mesta) meri tekst u kompoziciji.
- Dobro: kalendar čita mesec jednim upitom, nema `key(repoTick)` koji gradi sve iznova, R8, StrictMode i LeakCanary u debug-u.

### Bezbednost (80)
- **Medium** Podaci u mirovanju nisu šifrovani (lokalni JSON u `filesDir`, JMBG i račun iz listića) — odluka korisnika: Faza 9 se ne radi.
- **Low** `Log.w/i` ispisuju vrednosti podešavanja (koeficijent, osnovica) i dijagnostiku obračuna; vidljivo samo preko adb-a.
- **Low** `R8` širok `-keep` za `com.tom_roush.pdfbox.**` i Google model klase (potrebno zbog refleksije; sužavati tek uz test sa nalogom).
- Dobro: `allowBackup=false`, nema cleartext-a, scope-ovi `drive.appdata` i `gmail.readonly`, nema `exported` komponenti osim launcher-a, crash log bez poruka, restore guard.

### Kompatibilnost (82)
- `compileSdk`/`targetSdk` 36, `minSdk` 26, predictive back uključen, 16 KB poravnanje proveren, `POST_NOTIFICATIONS` traži se u toku rada.
- **Low** Compose BOM 2025.12.01 i `activity-compose` 1.12.4 nisu najnoviji (BOM 2026.09.00 postoji); namerno ne dirano posle provere dizajna.
- **Low** `play-services-auth` 21.6.0 (postoji 22.0.0, veliki skok) — proveriti pre sledećeg većeg izdanja.

### Dizajn sistem (77)
- Preuzeto iz M3 audita (77/100): boje i oblici iz teme, `Spacing` 4dp, M3 komponente; ostaje ručna donja traka i sheet u `Dialog`-u (svesno).

### Pristupačnost (72)
- Dobro: `toggleable` redovi, `liveRegion` za greške, 48dp mete, font prati sistem, kontrast pod testom.
- **Medium** TalkBack nije ručno prošao; nema ATF testa; 143 tvrdo kodirana teksta (nema `strings.xml`, `plurals`).

### Testiranje (76)
- 1127 unit/Robolectric testova, Roborazzi snimci (glavni ekran × font × širina), Kover 92,7% na obračunu i podacima.
- **Medium** Ekrani/dijalozi nemaju pokrivenost (UI sloj ~0–10%); nema instrumentacionih testova.
- **Low** Još oko 10 testova čita izvorni tekst (`OptionsDialogTextTest`, `PayDetailsDialogTextTest`, `BloodDonationLeaveTest` …).
- **Low** `RepositoryPayBaseResolver` je najslabije pokriven deo obračuna.

### Build (80)
- Dobro: version catalog, R8, potpis bez tihog debug ključa, `release.ps1`, CI (nije pokrenut na GitHub-u), Kover u `check`.
- **Low** Nema `verification-metadata.xml` (provera integriteta zavisnosti), nema convention plugina (jedan modul, nije potrebno).
- **Low** CI nikad nije pokrenut sa novim tokom (SDK 36, Kover, `allowDebugSigning`) jer ništa nije pushovano.

### Play preflight (50)
- Distribucija je sideload/Obtainium; AAB, upload ključ, privacy policy i Data safety nisu rađeni (nisu potrebni bez Play-a).

## Preporuke (po prioritetu)

1. **Baseline Profile + Macrobenchmark** (gate H2, jedina pravi granica): traži telefon; izmeri hladan start pre/posle.
2. Ručna provera R8 tokova sa novim bibliotekama: Drive backup/restore, Gmail uvoz, izvoz JSON-a, obaveštenje za krv.
3. Uvesti mali `AppContainer` (ili Hilt) i interfejse po domenima za repo; `ShiftRepositoryUi`/dijalozi da primaju interfejs, ne konkretnu klasu.
4. TalkBack prolaz + ATF test; zatim `strings.xml`.
5. Preostalih ~10 testova koji čitaju izvor zameniti testovima ponašanja; podići pokrivenost `RepositoryPayBaseResolver`.
6. `verification-metadata.xml` i prvi zeleni CI run na GitHub-u pre izdanja 6.0.

Procena: stavke 1, 3 i 4 podižu Performanse (+10), Arhitekturu (+10) i Pristupačnost (+8), tj. zbir na oko **76–77**; za ≥ 80 treba još Testiranje (+8, UI pokrivenost i instrumentacioni testovi) i Build (+5, verifikacija zavisnosti, zeleni CI).
