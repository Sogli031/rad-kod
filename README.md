# Rad — Tracker smena i obračun plate

Android aplikacija za praćenje radnih smena sa obračunom plate po srpskom
radnom zakonu: rotacija brigada, kompenzacioni sati, praznici i noćni rad.
Namenjena radnicima u industriji/proizvodnji.

## Tehnologije

- **Jezik:** Kotlin
- **UI:** Jetpack Compose (Material 3), 100% Compose, bez XML layout-a
- **Backup:** Google Drive API
- **Čuvanje podataka:** lokalni JSON fajl (bez baze)
- **Testovi:** JUnit 4, Robolectric (Compose UI), Roborazzi (snimci ekrana)
- **Lint:** Detekt, Android lint
- **Build:** AGP 8.13, Kotlin 2.1.21, Gradle 8.13, JDK 17 (verzije u `gradle/libs.versions.toml`)
- **SDK:** compileSdk 36, minSdk 26, targetSdk 36
- **Release:** R8 + shrink, potpis zajedničkim ključem

## Pokretanje

1. Otvori projekat u Android Studio-u.
2. Napravi `local.properties` u korenu (ovaj fajl se ne komituje) sa:
   ```
   sdk.dir=PUTANJA_DO_ANDROID_SDK
   ```
3. Sync Gradle i pokreni na uređaju/emulatoru (minSdk 26).

## Release

Potpis dolazi iz zajedničkog ključa u `..\keystore` (`keystore.properties` + `release.jks`,
ne komituju se). Bez njega `assembleRelease` pada, da APK ne bi tiho dobio debug ključ;
CI (nema ključa) koristi `-PallowDebugSigning=true`.

Objava: podigni `versionCode`/`versionName` u `app/build.gradle.kts`, pa
`.\release.ps1` (napomene uzima iz `CHANGELOG.md`, ili `-Notes "..."`). Skripta pokreće testove, detekt, lint i Kover, gradi APK,
proverava `apksigner`, otisak ključa i 16 KB poravnanje, čuva `mapping.txt` u `release-out\` i pravi GitHub release.
`-SkipPublish` samo gradi. Instalacija na telefon: `adb install -r` (isti potpis čuva podatke).

Baseline Profile (brži start, `app/src/main/baseline-prof.txt`): sa telefonom na adb-u
`./gradlew :baselineprofile:connectedBenchmarkAndroidTest`, pa iz
`baselineprofile/build/outputs/connected_android_test_additional_output/` kopiraj `*-startup-prof.txt`
preko `app/src/main/baseline-prof.txt`. Meri se zasebna aplikacija `com.rad.shifttracker.benchmark`
(test je na kraju deinstalira), prava instalacija se ne dira. Profil osvežiti posle većih izmena ekrana.

Provera biblioteka: `gradle/verification-metadata.xml` (SHA-256 svake zavisnosti). Posle dodavanja ili
nadogradnje biblioteke: `./gradlew --write-verification-metadata sha256 --no-configuration-cache testDebugUnitTest
verifyRoborazziDebug detekt lintDebug koverVerifyDebug assembleRelease :app:assembleBenchmark :baselineprofile:assembleBenchmark`
i pregledaj razliku pre commita.

Izveštaj o stabilnosti Compose-a: `./gradlew :app:compileReleaseKotlin --rerun -PcomposeReports`.

## Struktura

- `app/src/main/java/com/rad/shifttracker/` — izvorni kod
- `app/src/test/` — unit testovi
- `docs/` — dokumentacija
- `project-map.md` — mapa projekta

## Obračun plate (srpski radni zakon)

- Noćna smena: premija 1.41×
- Prekovremeni rad: 1.41×
- Nedeljni dodatak: +25%
- Rad na praznik: 123%
- Bolovanje: 65% osnovice (ili po danu)
- Godišnji odmor: 100%
- Topli obrok i minuli rad: konfigurabilno

## Napomena o privatnosti

Lični smenski podaci (`work_shifts.json`) i interni firmini dokumenti
namerno su izostavljeni iz repozitorijuma (vidi `.gitignore`).
