<#
.SYNOPSIS
  End-to-end check of reggraeton (PowerShell version of scripts/e2e.sh).

.DESCRIPTION
  Builds reggraeton, converts samples\legacy-shop in both modes and runs the generated Playwright
  tests against the demo shop (samples\demo-app). Expected per mode: 10 tests, 8 pass, 2 fail by
  design (a TODO(reggraeton) stub and an undefined step).
  Java comes from bin\resolve-java.ps1 (choose it once with bin\setup-java.ps1); Maven uses the same JDK.

.EXAMPLE
  .\scripts\e2e.ps1                              # installed Microsoft Edge, headless

.EXAMPLE
  $env:BROWSER_CHANNEL = 'chrome'; $env:HEADLESS = 'false'; .\scripts\e2e.ps1
#>
[CmdletBinding()]
param([int]$Port = 8080)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$java = & (Join-Path $root 'bin\resolve-java.ps1')
if ($java.Home) {
    $env:JAVA_HOME = $java.Home
    $env:Path = (Join-Path $java.Home 'bin') + [System.IO.Path]::PathSeparator + $env:Path
}
if (-not $env:BROWSER_CHANNEL) { $env:BROWSER_CHANNEL = 'msedge' }
$env:BASE_URL = "http://localhost:$Port"
$env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("reggraeton-e2e-" + [System.Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $work | Out-Null

function Invoke-Native([string]$exe, [string[]]$arguments, [string]$log) {
    # run a native command without PowerShell turning its stderr into errors; returns the exit code
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($log) { & $exe @arguments *> $log } else { & $exe @arguments }
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}

Write-Host "== using Java: $($java.Java) (from $($java.Source))"
Write-Host "== building reggraeton"
$mvn = (Get-Command mvn -ErrorAction SilentlyContinue)
if (-not $mvn) { throw "Maven (mvn) is not on the PATH." }
if ((Invoke-Native $mvn.Path @('-q', '-B', '-f', (Join-Path $root 'pom.xml'), 'install', '-DskipTests') $null) -ne 0) { throw "build failed" }

Write-Host "== starting the demo shop on port $Port"
$server = Start-Process -FilePath $java.Java -PassThru -WindowStyle Hidden `
    -ArgumentList @("`"$(Join-Path $root 'samples\demo-app\DemoServer.java')`"", "$Port", "`"$(Join-Path $root 'samples\demo-app\site')`"") `
    -RedirectStandardOutput (Join-Path $work 'server.log') -RedirectStandardError (Join-Path $work 'server.err')
$status = 0
try {
    $up = $false
    for ($i = 0; $i -lt 60 -and -not $up; $i++) {
        try {
            Invoke-WebRequest -Uri "$($env:BASE_URL)/login.html" -UseBasicParsing -TimeoutSec 2 | Out-Null
            $up = $true
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $up) { throw "demo shop did not start; see $work\server.err" }

    foreach ($mode in 'bdd', 'native') {
        $project = Join-Path $work $mode
        Write-Host "== converting in $mode mode -> $project"
        $code = Invoke-Native $java.Java @('-jar', (Join-Path $root 'reggraeton-cli\target\reggraeton.jar'), 'convert',
            (Join-Path $root 'samples\legacy-shop'), '--out', $project, '--mode', $mode) $null
        if ($code -ne 0) { throw "convert failed in $mode mode" }

        $headless = if ($env:HEADLESS) { $env:HEADLESS } else { 'true' }
        Write-Host "== running the $mode tests (browser: $($env:BROWSER_CHANNEL), headless: $headless)"
        Push-Location $project
        try {
            Invoke-Native $mvn.Path @('-B', 'test') (Join-Path $work "$mode.log") | Out-Null
        } finally {
            Pop-Location
        }
        $tests = 0
        $failing = 0
        foreach ($report in Get-ChildItem (Join-Path $project 'target\surefire-reports') -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue) {
            $text = [System.IO.File]::ReadAllText($report.FullName)
            $tests += ([regex]::Matches($text, '<testcase ')).Count
            $failing += ([regex]::Matches($text, '<(failure|error)[ >]')).Count
        }
        Write-Host "   $tests tests, $failing failing"
        if ($tests -ne 12 -or $failing -ne 2) {
            Write-Host "   UNEXPECTED: wanted 12 tests with 2 failing by design. Log: $work\$mode.log"
            $status = 1
        }
    }
} finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force }
}

if ($status -eq 0) {
    Write-Host "== OK: both modes ran 12 tests, 10 passed, 2 failed by design (work dir: $work)"
}
exit $status
