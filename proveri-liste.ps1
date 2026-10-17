# Provera skupljenih lista: PDF-ovi sa telefona (Downloads) ili iz foldera se čitaju istim kodom kao u
# aplikaciji (ListeProveraTest), a izveštaj se ispisuje i čuva u liste\provera\izvestaj.txt.
#   .\proveri-liste.ps1                 # povuče liste iz Downloads na telefonu (adb) i proveri
#   .\proveri-liste.ps1 -Folder C:\x    # proveri PDF-ove iz foldera, bez telefona
# Liste ostaju lokalno (liste/ i *.pdf su u .gitignore).
param(
    [string]$Folder
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$work = Join-Path $PSScriptRoot 'liste\provera'
New-Item -ItemType Directory -Force $work | Out-Null

if (-not $Folder) {
    $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { throw 'Nema adb-a u Android SDK-u' }
    # Pravi telefon, ne emulator.
    $device = & $adb devices | Select-Object -Skip 1 |
        Where-Object { $_ -match '\tdevice$' -and $_ -notmatch '^(emulator-|127\.0\.0\.1:)' } |
        ForEach-Object { ($_ -split "`t")[0] } | Select-Object -First 1
    if (-not $device) { throw 'Telefon nije na adb-u' }
    Get-ChildItem $work -Filter *.pdf | Remove-Item -Confirm:$false
    # Imena lista: „081262_230671_Prezime Ime.pdf“, „07126p_229509_…pdf“.
    $files = & $adb -s $device shell 'ls /sdcard/Download/' | ForEach-Object { $_.Trim() } |
        Where-Object { $_ -match '^[0-9]{5}[0-9a-z]_[0-9]+_.*\.pdf$' }
    foreach ($f in $files) { & $adb -s $device pull "/sdcard/Download/$f" (Join-Path $work $f) 2>&1 | Out-Null }
    Write-Host "Povučeno sa telefona: $($files.Count) lista"
    $Folder = $work
}

$report = Join-Path $work 'izvestaj.txt'
if (Test-Path $report) { Remove-Item $report -Confirm:$false }
$env:RAD_LISTE_DIR = (Resolve-Path $Folder).Path
$env:RAD_LISTE_OUT = $report
& .\gradlew.bat :app:testDebugUnitTest --tests 'com.rad.shifttracker.ListeProveraTest' --rerun -q
if ($LASTEXITCODE -ne 0) { throw 'Provera nije prošla (build ili test)' }
Get-Content $report -Encoding UTF8
