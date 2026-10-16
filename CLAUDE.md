# Pravila rada (i za Claude cloud sesije)

Aplikacija „rad“ (Android, Kotlin/Compose), paket `com.rad.shifttracker`. Odgovaraj na srpskom.

## Posle svake završene izmene
- Podigni verziju u `app/build.gradle.kts`: `versionName` +0.1, `versionCode` +1.
- Dodaj odeljak `## [X.Y] - GGGG-MM-DD` na vrh `CHANGELOG.md` (kratko, na srpskom, šta korisnik vidi).
- Commit i push na `main` bez pitanja. Pre toga proveri diff: bez ključeva, lozinki, tokena,
  `local.properties`, `keystore.properties`, `*.jks`, listi (`liste/`), `work_shifts.json` i ličnih
  podataka (imena, JMBG, brojevi računa, mejlovi).

## Release
- Release = push taga `vX.Y` (isti kao `versionName`), npr. `git tag vX.Y && git push origin vX.Y`.
  Posle svake nove verzije push-uj i njen tag.
- `.github/workflows/release.yml` pravi `assembleRelease` paralelno sa „Android build“-om (testovi, detekt,
  lint, debug build) i objavljuje tek kad on prođe; Roborazzi i kover se u CI-ju ne puštaju. Proverava potpis (SHA-1 `1ba8c0aa…5b06b55`) i 16 KB poravnanje i objavljuje `rad.apk` na javnom
  `Sogli031/rad` sa beleškama iz CHANGELOG-a. Ključevi su u GitHub Secrets; nikad ih ne štampaj ni ne traži.
- Aplikacija se ažurira tiho, pa se potpisni ključ ne menja i release ide samo sa ovog workflow-a
  (ili lokalno `release.ps1`).

## UI
- Bez objašnjavajućih tekstova (hintova, opisa ispod naslova i dugmadi): samo naslovi, vrednosti,
  statusi i prave poruke o greškama.
- U tekstu se kaže „lista“, nikad „listić“.

## AI (paket `ai/`)
- AI ne računa novac ni sate sam: svi iznosi dolaze iz postojećih obračuna aplikacije preko alata
  (`AiTools`), a AI ih samo prepričava. „Šta ako“ računa nad kopijom podataka.
- Upis (smene, slobodni dani, prekovremeno, bolovanje, godišnji) se samo predlaže i upisuje tek kad
  korisnik potvrdi („Upiši“).
- Napolje (Gemini) idu samo brojevi i datumi: bez imena, JMBG-a i brojeva računa.
- Gemini ključ je `GEMINI_API_KEY` u `local.properties` (nije u gitu).
