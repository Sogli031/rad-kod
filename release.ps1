# Objavljivanje verzije: build, provera potpisa, kopija mapping.txt, GitHub release.
# Upotreba: .\release.ps1 -Notes "Izmene na srpskom" [-SkipPublish]
# Verziju (versionCode/versionName) podigni u app/build.gradle.kts PRE pokretanja.
param(
    [Parameter(Mandatory = $true)][string]$Notes,
    [switch]$SkipPublish
)
$ErrorActionPreference = 'Stop'
$ExpectedSha1 = '1ba8c0aab7cd5be3af2c1099443d5c6375b06b55'
$Repo = 'Sogli031/rad'
$Gh = 'C:\Program Files\GitHub CLI\gh.exe'
Set-Location $PSScriptRoot

$gradle = Get-Content app\build.gradle.kts -Raw
$version = [regex]::Match($gradle, 'versionName = "([^"]+)"').Groups[1].Value
if (-not $version) { throw 'versionName nije nađen' }

.\gradlew.bat testDebugUnitTest detekt lintDebug koverVerifyDebug assembleRelease --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'Build ili testovi su pali' }

$apk = 'app\build\outputs\apk\release\app-release.apk'
$apksigner = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\apksigner.bat" | Sort-Object FullName | Select-Object -Last 1
$certs = & $apksigner.FullName verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify nije prošao' }
$sha1 = ($certs | Select-String 'SHA-1 digest: (\w+)').Matches[0].Groups[1].Value
if ($sha1 -ne $ExpectedSha1) { throw "Pogrešan potpisni ključ: $sha1" }

New-Item -ItemType Directory -Force release-out | Out-Null
$out = "release-out\smene-app-v$version.apk"
Copy-Item $apk $out -Force
Copy-Item app\build\outputs\mapping\release\mapping.txt "release-out\mapping-v$version.txt" -Force
$sha256 = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
Write-Host "APK: $out  SHA-256: $sha256  potpis SHA-1: $sha1"

if ($SkipPublish) { return }
$notesFile = Join-Path $env:TEMP "rad-notes-$version.md"
Set-Content -Path $notesFile -Value "$Notes`n`nSHA-256 APK-a: ``$sha256```nSHA-1 sertifikata: ``$sha1``" -Encoding UTF8
& $Gh release create "v$version" $out --repo $Repo --title "Verzija $version" --notes-file $notesFile
