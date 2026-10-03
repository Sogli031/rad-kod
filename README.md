# Rad — Tracker smena i obračun plate

Android aplikacija za praćenje radnih smena sa obračunom plate po srpskom
radnom zakonu: rotacija brigada, kompenzacioni sati, praznici i noćni rad.
Namenjena radnicima u industriji/proizvodnji.

## Tehnologije

- **Jezik:** Kotlin
- **UI:** Jetpack Compose (Material 3), 100% Compose, bez XML layout-a
- **Backup:** Google Drive API
- **Čuvanje podataka:** lokalni JSON fajl (bez baze)
- **Testovi:** JUnit 4, MockK
- **Lint:** Detekt
- **Build:** AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, JDK 17
- **SDK:** compileSdk 35, minSdk 26, targetSdk 35

## Pokretanje

1. Otvori projekat u Android Studio-u.
2. Napravi `local.properties` u korenu (ovaj fajl se ne komituje) sa:
   ```
   sdk.dir=PUTANJA_DO_ANDROID_SDK
   MAPS_API_KEY=TVOJ_KLJUC
   ```
3. Sync Gradle i pokreni na uređaju/emulatoru (minSdk 26).

## Release potpis

Za postojeći lokalni tok i direktnu nadogradnju telefona release APK se
podrazumevano potpisuje istim debug ključem kao ranije. Za pravi javni release
kopiraj `keystore.properties.example` kao `keystore.properties` i upiši putanju
i lozinke svog release keystore-a; tada Gradle automatski koristi taj ključ.
`keystore.properties` i keystore fajlovi su ignorisani u Git-u.

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
