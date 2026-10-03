# Rad — plan do „production grade"

Datum: 2026-10-03 · polazna verzija: 5.0 (versionCode 41) · autor: audit (material-3 + android-audit skill)

Svaka stavka ima **proveru** (kako znamo da je gotova). Faze su poređane po riziku i zavisnosti,
ne po veličini. Stavke označene 📱 traže telefon preko USB debug-a.

## Status izvršenja

| Faza | Stanje | Provera |
|---|---|---|
| 3 — ispravnost podataka | **kod gotov, spojen u `rad`** (2026-10-03). Restore guard (`tryImportFromJson` + `SnapshotRejection`), `schema_version` + `SchemaMigrations`, `Clock` u codec-u, `AtomicTextFile` (keš i lokalni fajl), `CloudArchiveMergerTest`, `RestoreGuardTest`, fuzz/round-trip testovi | detekt + lint + 1112 testova zeleno. Golden test obračuna iz listića nije potvrđen — proveriti u `PayslipImportTest`/novim testovima da li fixture dozvoljava reprodukciju meseca |
| 6 — pristupačnost | **kod gotov, spojen u `rad`**. `LabeledSwitchRow`/`LabeledCheckboxRow`, `FormErrorText` (liveRegion), `label`+`ImeAction` na poljima, scrim bez semantike + `paneTitle`, 48dp minimum na dugmadi, 12 Robolectric/Compose testova | isto; 📱 vizuelna provera (plutajući label, izgled redova) i TalkBack |
| 6 — odloženo | automatski fokus na prvo polje, `strings.xml`, kartice plate 41dp, reduced motion, ATF | odluke ili uređaj |
| 0.1 | commit stanja (bez taga v5.0: stablo je već imalo Faze 3 i 6) | `git status` čist |
| 7.2 (deo) | Roborazzi snimci: glavni ekran 360/411/840dp × font 1.0/1.5/2.0, Opcije, dan | `verifyRoborazziDebug` |
| 5.1 | **gotovo**: `THEME` uklonjen, `colorScheme` + `ExtendedColors`; snimci pikselski isti | DesignSystemTest zabranjuje `THEME.`/`Color(0x` |
| 5.6 | **gotovo**: `Spacing` (4dp mreža) umesto literala u razmacima | DesignSystemTest zabranjuje `.dp` u razmaku |
| 5.4 | težina fonta gotova (`*Emphasized`); minimum 11sp čeka 5.3 (kalendar) | DesignSystemTest zabranjuje `FontWeight.` |
| — | **v5.1 objavljena na zahtev korisnika** (međuverzija za proveru preko Obtainium-a) | potpis isti kao v5.0 |
| 5.2, 5.3, 5.5 (deo), 5.7, 5.8 (deo) | **gotovo i proveren na telefonu (2026-10-03)**: kalendar sa skrolom cele strane, svetla tema + izbor, `OutlinedCard`, segmentirani izbor, dvokolonski prikaz ≥ 840dp, „ukloni animacije“ | testovi + detekt + lint zeleni; `ThemeContrastTest` za obe teme |
| 5.5 / 5.8 — svesno nije | `BottomAppBar` (fiksna visina 80dp ne raste sa fontom → ostaje ručna traka), `ModalBottomSheet` (ostaje `Dialog` sa podešenom tastaturom), `MotionScheme`/spring (traži material3 1.4, Faza 4.6) | — |
| 1, 2, 4, 7–10 | nije početo | — |

Snimci na font 2.0 već pokazuju greške za 5.3/5.4: dani u nedelji se slepe, oznake statistike odsečene,
donja traka „Po…"/„Op…".

Napomena: agent Faze 3 je stao bez izveštaja i pre popravke detekta (`ShiftRepositoryJsonCodec` prešao granicu
`LargeClass`/`TooManyFunctions`); popravljeno ciljanim `@Suppress` sa obrazloženjem. Nije objavljen release (jedan ceo v6.0 na kraju).

## Polazno stanje (izmereno)

| Oblast | Nalaz | Izvor |
|---|---|---|
| M3 dizajn | 60/100 (bilo 38) | material-3 audit |
| Release build | `minify=false`, `shrinkResources=false`, APK 25 MB (gate **C7**) | android-audit |
| Profil | nema Baseline Profile / benchmark modula (**H2**) | android-audit |
| Mreža | nema `network_security_config` (**H6**) | android-audit |
| Dozvole | `MANAGE_EXTERNAL_STORAGE` + legacy READ/WRITE_EXTERNAL | manifest |
| Testovi | 1028 unit testova, **0** UI/instrumentacionih; 3 testa čitaju izvorni tekst | kod |
| Arhitektura | nema ViewModel-a; `ShiftTrackerScreen` računa ~17 upita nad repo-om u kompoziciji; `ShiftRepository` 2462 linije / 162 funkcije | kod |
| Compose stabilnost | 36 data klasa sa kolekcijama bez `@Immutable`; 5× `collectAsState` bez lifecycle-a | android-audit |
| Zavisnosti | 18 inline verzija, nema version catalog-a; stari: play-services-auth 21.0.0, pdfbox-android 2.0.27 | android-audit |
| Podaci | `AtomicFile` za upis (dobro), schema verzija samo u backup metapodacima | kod |
| OAuth | Drive `drive.appdata` (minimalno), Gmail `gmail.readonly`; consent screen nije u Testing modu | kod + korisnik |
| Lokalizacija | 143 tvrdo kodirana teksta, 15 `getString`/`stringResource` | kod |

Lažni pozitivi skill-a (ignorisati): **C1** (`MainActivity` je exported jer je LAUNCHER, to je normalno);
koren `.` se broji kao drugi „application" modul. Stare `.claude/worktrees` kopije u projektu udvostruče
nalaze, pa se audit pokreće nad čistom kopijom.

## Dodatak: nove stavke iz android-audit-a (2026-10-03)

Detalji i lokacije su u `ANDROID-AUDIT-REPORT.md`. Ocena skill-a: zbir 58.5, završno 40 (kritični gate). Ovo je ono što
audit dodaje na faze ispod; **ANDROID-ACTION-PLAN = ovaj dokument**.

| Faza | Nova stavka | Ozbiljnost |
|---|---|---|
| 2 | Release potpis: `signingConfigs.release` iz `keystore.properties`/CI secret-a; **build pada ako ključa nema** (danas tiho ide debug ključ). Test = `apksigner verify` gotovog APK-a, ne čitanje izvora | High |
| 2 | Ukloniti mrtve `androidTestImplementation`, mrtav CI `sed` korak, popraviti README o potpisu | Low |
| 2 | Version catalog, `gradle.properties` (caching/parallel/configuration-cache), `verification-metadata.xml`, verzija iz taga | Medium |
| 2 | Podsetnik za krv: `goAsync()` u receiver-u; ponovno zakazivanje na `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` 📱 | Medium |
| 3 | **Restore guard:** `importFromJson` odbija snapshot bez očekivanih ključeva/verzije i snapshot sa 0 zapisa kad lokalno ima podataka; test sa `{"x":1}` | **Medium (gubitak podataka)** |
| 3 | `CloudArchiveMerger` direktni testovi (idempotentnost `merge(merge(a,c),c)`, tombstone na granici, prazan `current`) | High |
| 3 | Golden test: smene meseca iz fixture-a → bruto/neto/doprinosi = listić (tolerancija 0,02 RSD), svih 8 meseci; `PayslipPdfText` test sa pravim PDF-om | High |
| 3 | `Clock` za legacy `years_of_service` migraciju i za sve testove koji prave `ShiftRepository()` | Medium |
| 4 | Upite za dijaloge računati lenjo (tek pri otvaranju); ukloniti dupli `getMonthlyOvertimeNetImpact` | High |
| 4 | Kalendar: jedan `getMonthRecords`, bez `key(repoTick)`; `AutoSizeText` → izračun kroz `TextMeasurer` u `remember` | High |
| 4 | `onStop` ne čeka `flush` na glavnoj niti; `initializeServices` van glavne niti; restore parsiranje na `Dispatchers.Default`; decode+merge van `lock`-a | Medium |
| 4 | Interfejsi po domenima (`ShiftStore`, `PayQueries`, `Persistence`), jedan `AppContainer` (ili Hilt) umesto 3× `ShiftRepository()` | High/Medium |
| 4 | `GmailPayslipCache.save` preko `AtomicFile`; `GmailImportRunner`/`SideRunMessages` singletone preseliti u ViewModel | Low/Medium |
| 5 | `enableEdgeToEdge()` umesto ručnih boja traka; `widthIn(max = 600.dp)` na sheet; `Locale` u formaterima; `lowercase(Locale.ROOT)` | Low |
| 6 | `LabeledSwitchRow` sa `toggleable` (Switch/Checkbox + labela jedna dodirna površina) u svih 5 dijaloga | **High** |
| 6 | Greške u formama: `liveRegion = Assertive` + `isError`/`supportingText` | **High** |
| 6 | `ModernTextField` sa pravim `label`; `ImeAction.Next/Done` + fokus na prvo polje pri otvaranju dijaloga | Medium |
| 6 | Scrim sa `onClickLabel = "Zatvori"`/`clearAndSetSemantics` + `paneTitle`; dugme „X" dobija opis | Medium |
| 7 | Ukloniti nekorišćene MockK/coroutines-test ili ih iskoristiti (`runTest` za `BackupWorker`, persistence scheduler); Robolectric za `LocalShiftStorage` | Medium |
| 7 | **14** testova koji čitaju izvorni tekst zameniti testovima ponašanja (ne 3) | Medium |
| 8 | Crash log samo u `filesDir` (izvoz ručno preko SAF-a), bez `message` izuzetaka ili sa rotacijom | Medium |
| 9 | Ukloniti `MAPS_API_KEY`; imena priloga/obustava u keš-u i Drive backup-u svesti na minimum; poseban potpisni ključ za **nove** aplikacije (rad zadržava postojeći) | Low |

**Lažni gate C1** (launcher je „exported"): ne popravlja se, zato je cilj „zbir ≥ 80", ne završna ocena skill-a.
Play-specifične stavke (AAB, upload ključ, privacy policy, Data safety) rade se samo ako se ikad ide na Play.

---

## Faza 0 — Higijena repoa (pre svega ostalog)

1. **Commit stanja koje je objavljeno.** Radno stablo ima 112 nekomitovanih fajlova pre v5.0.
   Razbiti u logične commit-ove, tagovati `v5.0` na tačnom izvoru APK-a.
   *Provera:* `git status` čist; `git checkout v5.0 && gradlew assembleRelease` daje isti APK (osim potpisa/vremena).
2. **Ukloniti stare worktree-ove i grane** (`.claude/worktrees/*`, `claude/*`) — **tvoja odluka**, ja ih ne brišem.
3. **Jedno mesto za release** — danas je izvor u `Sogli/rad`, a APK u `Sogli031/rad`. Dogovoriti i dokumentovati.
4. **CI zelen** (`testDebugUnitTest detekt lintDebug assembleRelease`) i lokalno i na GitHub-u.
   *Provera:* poslednji workflow run uspešan.

## Faza 1 — Provera v5.0 na telefonu 📱 (večeras)

Instalirati `smene-app-v5.0.apk` preko `adb install -r`, pa proći listu i snimiti ekrane + `adb logcat`:

- Font skala 1.0 / 1.3 / 1.5 / 2.0; „Veličina prikaza" najmanja i najveća.
- Kalendar: mesec od 6 nedelja, prelaz meseca (levo/desno), ćelije, današnji dan.
- Svaki dijalog: otvaranje (animacija), povlačenje nadole, nazad-gest, tap van lista.
- **Tastatura u dijalogu** (DayEdit, Opcije, Akontacija): sheet ne ide pod status bar, polje vidljivo.
- Snackbar: backup poruka u dijalogu (gore) i na glavnom ekranu (iznad donje trake).
- Donja traka: 6 stavki, naziv „Krv"/„Bol/GO" cele pri fontu 1.5.
- TalkBack: pređi kalendar i jedan dijalog; čuje li se dan, smena, sati.
- Pad/ANR u logcat-u.

*Provera:* sve stavke ✓; šta ne valja ide u Fazu 5 kao konkretan bug. **Ništa iz Faze 5 ne počinje pre ovoga.**

## Faza 2 — Release inženjering (najveći rizik za stabilnost)

1. **R8/shrink uključen (gate C7).**
   - `isMinifyEnabled=true`, `isShrinkResources=true`; skinuti širok `-keep class com.rad.shifttracker.** {*;}` i
     `-keep` za `androidx.compose.**`, `kotlinx.coroutines.**`, `com.google.api.**`; ostaviti samo ono što
     refleksija stvarno traži (Google API client/Gson modeli, pdfbox, JSON klase ako se koristi refleksija).
   - Arhivirati `mapping.txt` uz svaki release.
   - *Provera 📱:* na telefonu ručno proći **sve** tokove koji koriste refleksiju: Drive backup/restore,
     Gmail uvoz listića, PDF čitanje, izvoz JSON-a, obaveštenja (krv), boot receiver. Očekivano: APK znatno manji od 25 MB.
2. **Baseline Profile + Macrobenchmark modul (H2) 📱.** Cilj: hladan start izmeren pre/posle.
3. **`network_security_config`** sa `cleartextTrafficPermitted=false` (H6), eksplicitno.
4. **Skripta za release** (`release.ps1`): podigne verziju, `assembleRelease`, `apksigner verify` + poređenje
   SHA-256 sertifikata sa prethodnim, `gh release create` sa `smene-app-vX.Y.apk` i CHANGELOG tekstom.
   Danas se radi ručno — to je izvor grešaka.
5. **Potpisni ključ:** ostaje isti (promena prekida nadogradnju preko Obtainium-a). Napraviti **offline
   rezervnu kopiju** `release.jks` + lozinki (ti, password manager). Ključ je zajednički za sve tvoje aplikacije.
6. **Dozvole:** ukloniti `MANAGE_EXTERNAL_STORAGE` i READ/WRITE_EXTERNAL zamenom za SAF
   (`ACTION_OPEN_DOCUMENT_TREE` + trajna dozvola) za „folder sa listićima". *Provera 📱:* „Lista" i dalje
   sama nalazi PDF listić meseca.

## Faza 3 — Ispravnost podataka (aplikacija računa platu, ovde nema kompromisa)

1. **Golden testovi obračuna** iz stvarnih listića (nastaviti `PayrollCorrectionsRegressionTest`): za svaki
   dostupan mesec očekivani neto/bruto sa listića; svaka razlika > 1 RSD ruši test.
2. **Schema verzija lokalnog JSON-a + migracije** (danas postoji samo u backup metapodacima): verzija u
   fajlu, test za učitavanje svake starije verzije, unapred pravilo „nepoznata novija verzija → ne piši preko".
3. **Fuzz/svojstveni testovi** za `ShiftRepositoryJsonCodec` (oštećen, prazan, delimičan JSON; Unicode).
4. **Vreme:** 17× `LocalDate.now()` → ubrizgan `Clock`; testovi za ponoć, DST, promenu vremenske zone.
5. **Restore ispravnost:** test „backup → obriši → restore → identično" i na jednom stvarnom izvozu.
   *Provera:* testovi zeleni; nijedan test ne koristi `LocalDate.now()` bez Clock-a.

## Faza 4 — Arhitektura i performanse

1. **ViewModel + `StateFlow`**: izvući ~17 upita iz kompozicije u `MainViewModel` (računa na `Dispatchers.Default`,
   izlaže jedan `UiState`). Dobija se: stanje preživljava rotaciju bez `rememberSaveable` zaobilaznica,
   glavna nit slobodna. *Provera:* nema `repo.*` poziva u `@Composable` funkcijama (detekt pravilo).
2. **Kalendar:** ne čitati `repo.get` po ćeliji u kompoziciji; `key(repoTick)` ne sme da pravi ceo kalendar
   iznova — pustiti da `UiState` nosi `Map<Int, DayRecord>` za mesec.
3. **Stabilnost:** `@Immutable`/`kotlinx.collections.immutable` za 36 klasa sa kolekcijama; compose compiler
   reports → 0 nestabilnih parametara u kartama i kalendaru.
4. **`collectAsStateWithLifecycle`** (5 mesta).
5. **Podela `ShiftRepository`** (2462 linije) na manje celine po odgovornosti (već početo u `data/repository`);
   cilj < 800 linija po fajlu, javni API nepromenjen (testovi to čuvaju).
6. **Zavisnosti:** `gradle/libs.versions.toml`; ažurirati AGP/Kotlin/Compose/play-services-auth/pdfbox;
   `compileSdk/targetSdk` 36 (Android 16: predictive back je podrazumevan, a na ekranima ≥600dp se
   ignorišu ograničenja orijentacije i promene veličine — zato je Faza 5.7 važna). Pre prelaska proveriti
   `references/android-16-changes.md` iz android-audit skill-a. *Provera:* `./gradlew dependencyUpdates` bez kritičnih zastarelih.
7. **Debug alati:** `StrictMode` i LeakCanary samo u debug build-u.

## Faza 5 — Dizajn sistem do kraja (ostatak M3 audita 60 → 85+)

Redosled je bitan: 1 pre 2.

1. **Migracija `THEME.*` (444 mesta) na `MaterialTheme.colorScheme` + `ExtendedColors` (`CompositionLocal`)**
   za boje smena/statusa. Mehanički, po fajlu. Zabrana novih `THEME.` u `@Composable` kroz detekt pravilo.
2. **Svetla tema + dynamic color kao izbor u Opcijama** (tek posle 1). Nova paleta boja smena sa
   **test-om kontrasta za svaku kombinaciju** (tekst boje smene na tintovanoj ćeliji, obe teme) ≥ 4.5:1.
3. **Kalendar sa skrolom (odobreno):**
   - Najmanja ćelija **48dp**; skinuti `CALENDAR_MAX_FONT_SCALE` (1.15) pa se poštuje sistemski font.
   - Kad mesec ne staje, **skroluje se cela strana** (zaglavlje + kartice + kalendar + iznosi), a donja traka
     ostaje zakucana; ne skrolovati kalendar unutar male kutije.
   - *Provera 📱:* 360dp, font 1.5, mesec od 6 nedelja — sve se dostiže, ništa odsečeno.
4. **Tipografija:** minimum 11sp svuda (`labelSmall`); ukloniti ručne `fontWeight` preko emphasized stilova.
   Posledica kalendara iz 3.
5. **Komponente:** `GlassCard` → `OutlinedCard`/`Card`; izbor smene → `SingleChoiceSegmentedButtonRow`;
   donja traka → `BottomAppBar`; sheet → `ModalBottomSheet` **samo ako** Faza 1 pokaže da tastatura i
   isečak rade ispravno (inače ostaje trenutni `Dialog`).
6. **Razmak:** `Spacing` objekat na 8dp mreži umesto 189 `.dp` literala u UI-u.
7. **Adaptivno:** `WindowSizeClass`; ≥840dp dvopanelni prikaz (kalendar + detalji dana).
8. **Pokret:** `MotionScheme` (spring za komponente), isti prelazi za preostala 2 sirova `Dialog`-a.

## Faza 6 — Pristupačnost i lokalizacija

1. **TalkBack prolaz** svih ekrana (Faza 1 je prvi uzorak); fokus-redosled u dijalozima; `liveRegion` za greške u formama.
2. **Automatska provera:** Compose UI test `assertTouchHeightIsAtLeast(48.dp)` za sve klikabilne elemente,
   Accessibility Test Framework (ATF) u instrumentacionim testovima.
3. **Reduced motion:** poštovati sistemsku „ukloni animacije".
4. **`strings.xml`:** 143 tvrdo kodirana teksta (+ `č` escape-ovi) → resursi; `plurals` za „dana/sati";
   priprema za `sr-Latn`/`sr-Cyrl`/`en`. *Provera:* detekt/lint `HardcodedText` = 0.

## Faza 7 — Testovi i CI

1. **Compose UI testovi** (Robolectric ili `androidTest`): kalendar → dan → sačuvaj; dijalozi; Snackbar; izbor smene.
2. **Screenshot testovi** (Roborazzi/Paparazzi): ključni ekrani × font 1.0/1.5 × tamna/svetla tema × 360/411dp.
3. **Zameniti testove koji čitaju izvorni tekst** (`ScreenSafeAreaTest`, `DayEditDialogTextTest`,
   `VacationUiTextTest`) testovima ponašanja — danas puknu na svaki refaktor UI-a bez stvarne greške.
4. **Pokrivenost (Kover):** `PayCalculator` i repo ≥ 90%; prag u CI-ju.
5. **CI:** dodati `connectedCheck`/screenshot korak, upload `mapping.txt`, keširanje Gradle-a, obavezni PR check.

## Faza 8 — Posmatranje i podrška

1. Postojeći crash log (`CrashLogStorage`) proširiti: verzija aplikacije/uređaj/Android, poslednji dijalog.
2. **ANR/StrictMode** u debug-u; bez analitike i bez slanja na mrežu (privatnost).
3. 19 `Log.d/i/v` poziva — proveriti da ne ispisuju lične podatke (JMBG, račun, iznosi).

## Faza 9 — Bezbednost i privatnost

1. **OAuth:** potvrđeno da consent screen **nije u „Testing"** (tokeni ne ističu posle 7 dana). Ostaje samo
   da se u dokumentaciji zabeleži status i da opsezi ostanu minimalni (`drive.appdata`, `gmail.readonly`).
2. **Podaci u mirovanju:** listići sadrže JMBG i broj računa; lokalni JSON u `filesDir` (privatno), ali bez
   enkripcije — proceniti potrebu (Android Keystore + `EncryptedFile` je zastareo; alternativa: ključ iz Keystore-a + AES-GCM).
3. **Backup:** potvrditi da `drive.appdata` fajlovi nisu čitljivi van aplikacije; test restore na čistom telefonu.
4. **Uklonjen `MAPS_API_KEY`** iz `local.properties`/README ako se ne koristi (u kodu nema upotrebe).
5. Ažuriranje zavisnosti kroz Dependabot/Renovate; `gradle/verification-metadata.xml` opciono.

## Faza 10 — Distribucija

1. Release napomene automatski iz CHANGELOG-a; SHA-256 sertifikata i APK-a u opisu.
2. README repoa za distribuciju (šta je, kako se instalira preko Obtainium-a).
3. 16 KB page size: APK ima samo `libandroidx.graphics.path.so` — proveriti poravnanje posle Faze 2.

---

## Definicija „production grade" (sve mora da važi)

- [ ] Release APK je R8-skupljen, mapping arhiviran, svi tokovi refleksije ručno provereni 📱
- [ ] Nema `MANAGE_EXTERNAL_STORAGE`; „Lista" radi preko SAF-a
- [ ] Obračun uskladjen sa realnim listićima (golden testovi), schema verzija i migracije testirane
- [ ] Nijedan upit nad repo-om u kompoziciji; ViewModel; glavna nit bez teških proračuna
- [ ] `THEME.*` ne postoji u `@Composable`; tamna + svetla tema; kontrast testiran za svaku boju smene
- [ ] Svi klikabilni elementi ≥ 48dp, tekst ≥ 11sp, font skala 2.0 ne lomi ekran (kalendar skroluje)
- [ ] TalkBack prolaz zabeležen; ATF test zelen
- [ ] UI + screenshot testovi, Kover prag, CI zelen, release skripta
- [x] OAuth: consent screen nije u Testing modu (potvrdio korisnik); opsezi minimalni
- [ ] Rezervna kopija potpisnog ključa postoji (offline)
- [ ] Release potpis pada kad nema ključa; `apksigner verify` u release skripti
- [ ] Restore odbija prazan/nevalidan snapshot (testirano)
- [ ] Svi Switch/Checkbox redovi `toggleable`; greške u formama najavljene čitaču
- [ ] Ponovljen `/android audit` daje zbir ≥ 80 (bez lažnog C1)

## Preporučeni redosled

`Faza 1 (večeras)` → `0` → `2.1, 2.3, 2.4, 2.5` (R8 + skripta + ključ) → `3` → `4.1–4.4` → `5.1` → `5.3` (kalendar) →
`6` → `7` → `5.2` (svetla tema) → ostalo.

**Release:** tokom rada **nema objavljivanja po fazama**. Izmene se grade i proveravaju lokalno
(`testDebugUnitTest`, `detekt`, `assembleRelease`, instalacija na telefon preko adb-a), a **jedan ceo release**
(verzija 6.0, jer menja temu, arhitekturu i dozvole) se objavljuje tek kad je cela „Definicija production grade"
ispunjena. Do tada je v5.0 poslednja javna verzija; v5.0 na telefonu služi samo kao polazna tačka za poređenje.
