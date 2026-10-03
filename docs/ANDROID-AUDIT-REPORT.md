# Android audit — Rad (android-audit skill, bez dizajna)

Datum: 2026-10-03 · verzija 5.0 (versionCode 41) · metod: `build_audit_context.py` + `score.py` + 8 specijalista
(arhitektura, bezbednost, performanse, kompatibilnost, build, testiranje, pristupačnost, Play preflight).
Sve je **statička** provera izvora; ništa nije mereno na uređaju. Sve tvrdnje označene ✔ proverio sam i ručno.

## Ocena

| Kategorija | Težina | Ocena | Težinski |
|---|---|---|---|
| Arhitektura | 15 | 40 | 6.0 |
| Performanse | 15 | 50 | 7.5 |
| Bezbednost | 15 | 70 | 10.5 |
| Kompatibilnost | 10 | 80 | 8.0 |
| Dizajn sistem (M3 audit, ne android-design agent) | 10 | 60 | 6.0 |
| Pristupačnost | 10 | 65 | 6.5 |
| Testiranje | 10 | 65 | 6.5 |
| Build | 10 | 55 | 5.5 |
| Play preflight (nije relevantno za sideload) | 5 | 40 | 2.0 |
| **Zbir** | | | **58.5** |

**Završna ocena skill-a: 40/100** jer važi gornja granica za kritični gate.

Gate-ovi koji su okinuli:

| Gate | Ozbiljnost | Stvaran? |
|---|---|---|
| C1 izvezena komponenta bez dozvole | critical | **Lažan** — `MainActivity` je LAUNCHER. Gate se tako ne može zadovoljiti ni u jednoj aplikaciji sa ikonom, pa granica 40 ostaje i posle svih popravki |
| C7 release bez shrink/obfuskacije | critical | Stvaran |
| H2 nema benchmark/Baseline Profile | high | Stvaran |
| H3 nizak pouzdan rizik dodirne mete | high | Delimično (heuristika, 2 mesta) |
| H6 nema network security config | high | Stvaran, nizak rizik (targetSdk 35 blokira cleartext) |

Tri „high" gate-a daju granicu 60. Bez C1 (lažnog) i uz popravljen C7, H2 i H6, granica nestaje.
Gore: C1 se ne može popraviti, pa je realna „čista" ocena **58.5**, a cilj plana je ≥ 80.

## Nalazi po kategorijama (najvažniji, sa lokacijom)

### Bezbednost (7/10) — nema Critical
- **Medium ✔ Restore briše sve na „praznom" JSON-u.** `ShiftRepository.kt:422-429`: `importFromJson` dekodira pa briše
  stanje, a `ShiftRepositoryJsonCodec.decode` (`:53-59`) prihvata svaki validan `JSONObject` (`optX` sa
  podrazumevanim vrednostima). `{"x":1}` ostavlja 0 zapisa i resetuje podešavanja. Lokalna zaštita postoji samo za `"{}"`
  (`:2425`). Ublaženo: sigurnosna kopija pre restore-a i `BackupAnomalyGuard` za backup.
- **Medium Javni crash log.** `CrashLogStorage.kt:60-92` piše ceo stack trace u `Documents/crash_log.txt` (MediaStore, raste
  bez rotacije); poruke izuzetaka mogu da sadrže delove ulaza.
- **Medium `MANAGE_EXTERNAL_STORAGE`** samo zbog skeniranja foldera sa PDF-ovima (`PayslipFolder.kt:60-62,179-184`); SAF dozvola
  se uzima kao string putanje, bez `takePersistableUriPermission`.
- **Low** Zajednički potpisni ključ za više aplikacija; `GmailPayslipCache`/`PayslipSnapshotStore` čuvaju imena priloga i
  naziva obustava (u `filesDir` i u Drive backup-u); `MAPS_API_KEY` u `local.properties` se ne koristi.
- Dobro: `allowBackup=false`, samo launcher je exported, `FLAG_IMMUTABLE`, PDF ograničenja (10 MB, 10 strana, OOM uhvaćen),
  minimalni OAuth opsezi, `AtomicFile` + karantin oštećenog fajla, izvozi bez JMBG/računa.

### Arhitektura (4/10)
- **High ✔ Nema ViewModel-a.** `ShiftTrackerScreen.kt:279-347`: ~17 upita nad repo-om u `remember(repoTick…)` na glavnoj niti.
- **High God-class.** `ShiftRepository.kt` 2462 linije / 162 funkcije, bez interfejsa; UI i receiver zavise od konkretne klase.
- **Medium** `ShiftRepository()` se pravi na 3 mesta (`RadApplication.kt:51`, `BloodDonationReminderReceiver.kt:10` ✔ rezervna
  instanca, `PayslipMonthFill.kt:316`); nema DI-ja.
- **Medium** `THEME`/`CONFIG` globalni singleton (444 upotrebe); `DialogState` sa 21 stanjem kao ručna navigacija; globalno
  stanje u `GmailImportRunner` i `SideRunMessages`; 5× `collectAsState` bez lifecycle-a.
- **Napomena:** projekat je jedan modul; „multi-module" u skeneru je lažno pozitivan (koren ima samo `apply false`).

### Performanse (5/10) — sve statički, merenja traže uređaj
- **High** Release bez R8 (APK 25 MB); nema Baseline Profile-a.
- **High ✔ Dupli upit:** `getMonthlyOvertimeNetImpact(currentYear,currentMonth)` se računa dvaput (linije ~283 i ~299); podaci za
  dijaloge (`payDetails`, prosečne neto plate) se računaju i kad nijedan dijalog nije otvoren.
- **High** Kalendar: `key(repoTick)` pravi sve iznova, a `repo.get` po ćeliji uzima `synchronized(lock)` na glavnoj niti
  (`ShiftCalendar.kt`, `ShiftRepository.kt:588`).
- **High** `AutoSizeText` (`ResponsiveText.kt:70-137`) piše stanje iz `onTextLayout` (×0.9 po koraku, sakriven dok se ne smiri);
  ~60+ instanci po mesecu.
- **Medium** `onStop` čeka `flush` do 2 s na glavnoj niti (`RadApplication.kt:139`); `initializeServices` i `loadFromLocal` pre
  `setContent`; restore parsira JSON na Main niti; `buildCloudSnapshot` drži `lock` tokom decode+merge.
- **Low** Ceo JSON se piše na svaku izmenu (debounce postoji); `GmailPayslipCache` piše bez `AtomicFile`.

### Kompatibilnost (8/10)
- Edge-to-edge ručno preko deprecated API-ja (`MainActivity.kt:27-35`) → `enableEdgeToEdge()`.
- Sheet-ovi `fillMaxWidth` bez `widthIn` (razvlače se na ≥600dp); ručni `WindowManager.LayoutParams` u `DialogEdgeToEdgeWindow`
  (kruto vezano za Compose `Dialog`).
- Podsetnik za krv: receiver radi `loadFromLocal` sinhrono u `onReceive` (bez `goAsync`); alarm se ponovo zakazuje samo na
  `BOOT_COMPLETED` i `onResume` (nema `MY_PACKAGE_REPLACED`/`TIME_SET`/`TIMEZONE_CHANGED`).
- Lokalni formateri bez `Locale` (`BackupVersionsDialog.kt:370`, `CloudBackupDialog.kt:222`, `BloodDonationReminder.kt:13-15`).
- Dobro: nema exact-alarm dozvola, `POST_NOTIFICATIONS` kontekstualno, `FLAG_IMMUTABLE`, WorkManager, nema nativnog koda (16 KB ok,
  potvrditi na APK-u).

### Build (5.5/10)
- **High** Potpis zavisi od `../keystore/signing.gradle` van repoa (`app/build.gradle.kts:9,33`); ako fajla nema, ide debug ključ **bez
  greške**; CI zato pravi debug-potpisan APK. `ReleaseSigningConfigurationTest` samo traži stringove u izvoru.
- **High** R8 isključen, a postojeća `-keep` pravila ga skoro poništavaju; posle uključivanja obavezno testirati refleksiju
  (Gson/Google API klijenti, pdfbox).
- **Medium** Nema version catalog-a (18 inline verzija), nema dependency verification-a, `gradle.properties` bez caching/parallel,
  verzija ručno u `defaultConfig`, README zastareo za potpis, CI korak `sed … org.gradle.java.home` ne radi ništa.
- **Low** Mrtve `androidTestImplementation` zavisnosti (nema `androidTest`); stari: play-services-auth 21.0.0, pdfbox-android 2.0.27.
- Dobro: usklađeni AGP/Gradle/Kotlin/Compose, samo `google()`+`mavenCentral()`, nema KAPT/KSP/SNAPSHOT.

### Testiranje (6.5/10)
- 1028 testova (potvrđeno), svi zeleni; **0** UI/instrumentacionih.
- **✔ MockK i coroutines-test su deklarisani a ne koriste se nigde** (0 `import io.mockk`, 0 `runTest`) — async putanje
  (`BackupWorker`, persistence scheduler) nisu testirane.
- **✔ 14 testova čita izvorni .kt tekst** (ranije sam rekao 3 — pogrešno): brittle, padaju na refaktor, prolaze kad UI ne radi.
- **High** `CloudArchiveMerger` (172 linije, tombstone/granice) nema direktan test; **High** golden listići postoje samo za
  parser, ne za poređenje izračunatog bruto/neto sa listićem; `PayslipPdfText` (PDFBox) nema test sa pravim PDF-om.
- **Medium** `isReturnDefaultValues=true` skriva Android pozive; nema Robolectric-a (`LocalShiftStorage`, `GoogleDriveBackup`);
  vreme: `LocalDate.now()` u testovima i legacy `years_of_service` migracija zavisna od dana; `Thread.sleep(20)` u scheduler testu;
  `ShiftRepositoryTest` 4413 linija.
- Dobro: seed-ovani fuzz za parser, `HolidaysTest` (2025–2035), `BackupLocaleTest`, regresioni testovi sa konkretnim iznosima.

### Pristupačnost (6.5/10)
- **High** Switch/Checkbox redovi nisu `toggleable` (0 upotreba): `OptionsDialog.kt:134-163`, `SeniorityDialog.kt:354-378`,
  `AnnualLeaveEntitlementCard.kt:113-128`, `DayEditDialog.kt:606-616`, `HolidayManagementDialog.kt:145-162`.
- **High** Greške u formama (`FoodAllowanceDialog.kt:54-61`, `AbsenceDialog.kt:235-243`, `BloodDonationDialog.kt:350-358`…) su običan
  `Text` bez `liveRegion`/`isError`.
- **Medium** `ModernTextField` ima samo placeholder (nestaje kad se unese vrednost); 0 `ImeAction`/`FocusRequester`; scrim lista je
  neimenovan `clickable` (nema `paneTitle`); dugme „X" (`BloodDonationDialog.kt:228-238`) bez opisa; kalendar max font 1.15 i ćelije <48dp;
  nema poštovanja „ukloni animacije".
- Dobro: `radClickable` + `Role.Button`, opis dana u kalendaru, `heading()`, custom action „Zatvori", `selectable(RadioButton)`.

### Play preflight (4/10, za sideload irelevantno)
Blokiralo bi Play: `MANAGE_EXTERNAL_STORAGE`, nema AAB, debug/zajednički ključ, nema politike privatnosti i Data safety
forme, `gmail.readonly` je restricted opseg (OAuth verifikacija). Za Obtainium nije potrebno.

## Šta ovaj audit NE pokriva (traži uređaj)
Jank/start (Perfetto, Macrobenchmark), stvarni kontrast, TalkBack redosled, font 200%, ponašanje na tabletu i API 35/36, veličina APK-a
posle R8, ponašanje alarma posle update-a i promene zone.
