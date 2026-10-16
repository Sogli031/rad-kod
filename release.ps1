# Objavljivanje verzije: testovi, build, provera potpisa i 16 KB poravnanja, kopija mapping.txt, GitHub release.
# Upotreba: .\release.ps1 [-Notes "Izmene na srpskom"] [-SkipPublish]
# Bez -Notes uzima se odeljak "## [verzija]" iz CHANGELOG.md.
# Verziju (versionCode/versionName) podigni u app/build.gradle.kts PRE pokretanja.
param(
    [string]$Notes,
    [switch]$SkipPublish
)
$ErrorActionPreference = 'Stop'
$ExpectedSha1 = '1ba8c0aab7cd5be3af2c1099443d5c6375b06b55'
$Repo = 'Sogli031/rad'
$Gh = 'C:\Program Files\GitHub CLI\gh.exe'
Set-Location $PSScriptRoot

# Release se pravi samo iz commitovanog stanja, da APK odgovara tagu.
$Git = (Get-Command git -ErrorAction SilentlyContinue).Source
if (-not $Git) { $Git = 'C:\Program Files\Git\cmd\git.exe' }
$dirty = & $Git status --porcelain --untracked-files=no
if ($dirty) { throw "Ima necommitovanih izmena:`n$dirty" }

$gradle = Get-Content app\build.gradle.kts -Raw
$version = [regex]::Match($gradle, 'versionName = "([^"]+)"').Groups[1].Value
if (-not $version) { throw 'versionName nije nađen' }

if (-not $Notes) {
    $changelog = Get-Content CHANGELOG.md -Raw -Encoding UTF8
    $pattern = '(?ms)^## \[' + [regex]::Escape($version) + '\][^\n]*\n(.*?)(?=^## \[|\z)'
    $m = [regex]::Match($changelog, $pattern)
    if (-not $m.Success) { throw "CHANGELOG.md nema odeljak za verziju $version, a -Notes nije dat" }
    $Notes = $m.Groups[1].Value.Trim()
}

.\gradlew.bat testDebugUnitTest detekt lintDebug assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'Build ili testovi su pali' }

$apk = 'app\build\outputs\apk\release\app-release.apk'
$buildTools = "$env:LOCALAPPDATA\Android\Sdk\build-tools"
$apksigner = Get-ChildItem "$buildTools\*\apksigner.bat" | Sort-Object FullName | Select-Object -Last 1
$certs = & $apksigner.FullName verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify nije prošao' }
$sha1 = ($certs | Select-String 'SHA-1 digest: (\w+)').Matches[0].Groups[1].Value
if ($sha1 -ne $ExpectedSha1) { throw "Pogrešan potpisni ključ: $sha1" }

$zipalign = Get-ChildItem "$buildTools\*\zipalign.exe" | Sort-Object FullName | Select-Object -Last 1
& $zipalign.FullName -c -P 16 4 $apk
if ($LASTEXITCODE -ne 0) { throw 'APK nije poravnat na 16 KB' }

New-Item -ItemType Directory -Force release-out | Out-Null
$out = "release-out\smene-app-v$version.apk"
Copy-Item $apk $out -Force
Copy-Item app\build\outputs\mapping\release\mapping.txt "release-out\mapping-v$version.txt" -Force
$sha256 = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
Write-Host "APK: $out  SHA-256: $sha256  potpis SHA-1: $sha1"

if ($SkipPublish) { return }
$head = & $Git rev-parse HEAD
$tagged = & $Git rev-parse -q --verify "refs/tags/v$version^{commit}"
if ($tagged) {
    if ($tagged -ne $head) { throw "Tag v$version već postoji na drugom commitu" }
} else {
    & $Git tag -a "v$version" -m "Verzija $version"
    if ($LASTEXITCODE -ne 0) { throw "Tag v$version nije napravljen" }
}
$notesFile = Join-Path $env:TEMP "rad-notes-$version.md"
Set-Content -Path $notesFile -Value "$Notes`n`nSHA-256 APK-a: ``$sha256```nSHA-1 sertifikata: ``$sha1``" -Encoding UTF8
# Na GitHub ide pod stalnim imenom rad.apk (jedini APK u release-u, da Obtainium ne pita koji):
# https://github.com/Sogli031/rad/releases/latest/download/rad.apk uvek vodi na najnoviju verziju,
# a provera ažuriranja u aplikaciji traži baš taj fajl.
$publishedApk = Join-Path 'release-out' 'rad.apk'
Copy-Item $out $publishedApk -Force
& $Gh release create "v$version" $publishedApk --repo $Repo --title "Verzija $version" --notes-file $notesFile
if ($LASTEXITCODE -ne 0) { throw "GitHub release v$version nije napravljen" }
# Tag i u rad-kod: release.yml vidi da je verzija već objavljena i ne pravi je ponovo.
& $Git push origin "v$version"
if ($LASTEXITCODE -ne 0) { throw "Tag v$version nije push-ovan" }
