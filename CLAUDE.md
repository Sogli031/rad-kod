# Pravila rada (i za Claude cloud sesije)

Aplikacija „rad“ (Android, Kotlin/Compose), paket `com.rad.shifttracker`. Odgovaraj na srpskom.

## Posle svake završene izmene
- Podigni verziju u `app/build.gradle.kts`: `versionName` +0.1, `versionCode` +1.
- Dodaj odeljak `## [X.Y] - GGGG-MM-DD` na vrh `CHANGELOG.md` (kratko, na srpskom, šta korisnik vidi).
- Commit i push na `main` bez pitanja. Pre toga proveri diff: bez ključeva, lozinki, tokena,
  `local.properties`, `keystore.properties`, `*.jks`, listi (`liste/`), `work_shifts.json` i ličnih
  podataka (imena, JMBG, brojevi računa, mejlovi).

## Release
- Release se pravi **lokalno**: podigni verziju, commit + push na `main`, pa `.\release.ps1`.
  Skripta pušta testove, detekt, lint i `assembleRelease`, proverava potpis (SHA-1 `1ba8c0aa…5b06b55`)
  i 16 KB poravnanje, objavljuje `rad.apk` na javnom `Sogli031/rad` sa beleškama iz CHANGELOG-a i
  na kraju push-uje tag `vX.Y` (isti kao `versionName`).
- `.github/workflows/release.yml` je rezerva za cloud sesije bez ključa (samo push taga `vX.Y`): kad je
  verzija već objavljena lokalno, preskače se (koristio se dok se radilo iz clouda).
  Ključevi su u GitHub Secrets i `..\keystore`; nikad ih ne štampaj ni ne traži.
- Aplikacija se ažurira tiho, pa se potpisni ključ ne menja i release ide samo preko `release.ps1`
  (ili workflow-a).

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
