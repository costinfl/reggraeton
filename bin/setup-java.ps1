<#
.SYNOPSIS
  Chooses the JDK that reggraeton's scripts use, without changing PATH or system settings.

.DESCRIPTION
  Detects installed JDKs (Program Files vendors, IntelliJ's %USERPROFILE%\.jdks, Scoop, the
  JavaSoft registry keys, JAVA_HOME and the PATH), shows them with their versions, and saves
  your choice to .reggraeton\java-home in this checkout. bin\reggraeton.ps1, bin\reggraeton.cmd,
  bin\java-env.ps1 and scripts\e2e.ps1 then use it. Java 17 or newer is required.

.PARAMETER JavaHome
  Use this JDK folder (the one containing bin\java.exe) without asking.

.PARAMETER List
  Only list the detected JDKs.

.EXAMPLE
  .\bin\setup-java.ps1

.EXAMPLE
  .\bin\setup-java.ps1 -JavaHome 'C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot'

.NOTES
  If scripts are blocked by the execution policy:
    powershell -ExecutionPolicy Bypass -File .\bin\setup-java.ps1
#>
[CmdletBinding()]
param(
    [string]$JavaHome,
    [switch]$List
)

$ErrorActionPreference = 'Stop'
$MinimumVersion = 17
$root = Split-Path -Parent $PSScriptRoot
$configDir = Join-Path $root '.reggraeton'
$configFile = Join-Path $configDir 'java-home'
$onWindows = ($PSVersionTable.PSEdition -ne 'Core') -or $IsWindows
$exeName = if ($onWindows) { 'java.exe' } else { 'java' }

function Get-JavaExe([string]$jdkHome) {
    if ([string]::IsNullOrWhiteSpace($jdkHome)) { return $null }
    $exe = Join-Path (Join-Path $jdkHome 'bin') $exeName
    if (Test-Path -LiteralPath $exe -PathType Leaf) { return $exe }
    return $null
}

# Version text such as "21.0.5" or "1.8.0_392", from the JDK's release file or from java -version.
function Get-JdkVersion([string]$jdkHome) {
    $release = Join-Path $jdkHome 'release'
    if (Test-Path -LiteralPath $release -PathType Leaf) {
        foreach ($line in [System.IO.File]::ReadAllLines($release)) {
            if ($line -match '^JAVA_VERSION="?([^"]+)"?') { return $Matches[1] }
        }
    }
    $exe = Get-JavaExe $jdkHome
    if (-not $exe) { return $null }
    # java -version writes to stderr: relax ErrorActionPreference so PowerShell 5.1 does not stop on it
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = (& $exe -version 2>&1 | ForEach-Object { "$_" }) -join "`n"
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($output -match 'version "([^"]+)"') { return $Matches[1] }
    return $null
}

function Get-MajorVersion([string]$version) {
    if (-not $version) { return 0 }
    if ($version -match '^1\.(\d+)') { return [int]$Matches[1] }
    if ($version -match '^(\d+)') { return [int]$Matches[1] }
    return 0
}

function Find-Jdks {
    $homes = New-Object System.Collections.Generic.List[string]
    $add = {
        param([string]$candidate)
        if ([string]::IsNullOrWhiteSpace($candidate)) { return }
        if (-not (Get-JavaExe $candidate)) { return }
        $full = [System.IO.Path]::GetFullPath($candidate.Trim().TrimEnd('\', '/'))
        foreach ($existing in $homes) {
            if ([string]::Equals($existing, $full, [System.StringComparison]::OrdinalIgnoreCase)) { return }
        }
        $homes.Add($full)
    }

    $parents = @()
    if ($onWindows) {
        $vendors = @('Java', 'Eclipse Adoptium', 'Eclipse Foundation', 'AdoptOpenJDK', 'Microsoft', 'Zulu',
            'Amazon Corretto', 'BellSoft', 'Semeru', 'IBM', 'OpenJDK', 'RedHat', 'SapMachine', 'GraalVM')
        $bases = @($env:ProgramW6432, $env:ProgramFiles, ${env:ProgramFiles(x86)})
        if ($env:LOCALAPPDATA) { $bases += (Join-Path $env:LOCALAPPDATA 'Programs') }
        foreach ($base in $bases) {
            if ([string]::IsNullOrWhiteSpace($base)) { continue }
            foreach ($vendor in $vendors) { $parents += (Join-Path $base $vendor) }
        }
        if ($env:USERPROFILE) {
            $parents += (Join-Path $env:USERPROFILE '.jdks')
        }
    } else {
        $parents += '/usr/lib/jvm', '/Library/Java/JavaVirtualMachines', '/opt'
        if ($HOME) { $parents += (Join-Path $HOME '.jdks'), (Join-Path $HOME '.sdkman/candidates/java') }
    }

    foreach ($parent in $parents) {
        if (-not (Test-Path -LiteralPath $parent -PathType Container)) { continue }
        foreach ($dir in Get-ChildItem -LiteralPath $parent -Directory -ErrorAction SilentlyContinue) {
            & $add $dir.FullName
            & $add (Join-Path $dir.FullName 'Contents/Home')   # macOS bundles
            foreach ($sub in Get-ChildItem -LiteralPath $dir.FullName -Directory -ErrorAction SilentlyContinue) {
                & $add $sub.FullName                            # e.g. Zulu\zulu-17
            }
        }
    }

    if ($onWindows -and $env:USERPROFILE) {
        $scoop = Join-Path $env:USERPROFILE 'scoop\apps'
        if (Test-Path -LiteralPath $scoop -PathType Container) {
            foreach ($app in Get-ChildItem -LiteralPath $scoop -Directory -ErrorAction SilentlyContinue) {
                & $add (Join-Path $app.FullName 'current')
            }
        }
        foreach ($key in @('HKLM:\SOFTWARE\JavaSoft\JDK', 'HKLM:\SOFTWARE\JavaSoft\Java Development Kit')) {
            if (Test-Path $key) {
                foreach ($version in Get-ChildItem $key -ErrorAction SilentlyContinue) {
                    $value = (Get-ItemProperty $version.PSPath -ErrorAction SilentlyContinue).JavaHome
                    & $add $value
                }
            }
        }
    }

    & $add $env:JAVA_HOME
    $onPath = Get-Command $exeName -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { & $add (Split-Path -Parent (Split-Path -Parent $onPath.Path)) }

    $result = @()
    foreach ($jdkHome in $homes) {
        $version = Get-JdkVersion $jdkHome
        $result += [pscustomobject]@{ Home = $jdkHome; Version = $version; Major = (Get-MajorVersion $version) }
    }
    return @($result | Sort-Object -Property @{ Expression = 'Major'; Descending = $true }, Home)
}

function Save-Choice([string]$jdkHome) {
    if (-not (Test-Path -LiteralPath $configDir)) { New-Item -ItemType Directory -Path $configDir | Out-Null }
    # UTF-8 without BOM, no trailing newline: read by PowerShell, cmd (set /p) and sh alike
    [System.IO.File]::WriteAllText($configFile, $jdkHome, (New-Object System.Text.UTF8Encoding $false))
    Write-Host ""
    Write-Host "Saved: reggraeton scripts in this checkout will use $jdkHome"
    Write-Host "  (stored in $configFile; REGGRAETON_JAVA_HOME overrides it)"
    Write-Host ""
    Write-Host "For Maven in this PowerShell session (sets JAVA_HOME for the session only):"
    Write-Host "  . .\bin\java-env.ps1"
}

# ---- explicit choice ------------------------------------------------------------------------
if ($JavaHome) {
    $resolved = Resolve-Path -LiteralPath $JavaHome.Trim() -ErrorAction SilentlyContinue
    if (-not $resolved) {
        Write-Error "'$JavaHome' does not exist"
        exit 1
    }
    $full = $resolved.ProviderPath.TrimEnd('\', '/')
    if (-not (Get-JavaExe $full)) {
        Write-Error "'$full' is not a JDK folder: it has no bin\$exeName"
        exit 1
    }
    $version = Get-JdkVersion $full
    $major = Get-MajorVersion $version
    if ($major -lt $MinimumVersion) {
        Write-Error "'$full' is Java $version; reggraeton needs Java $MinimumVersion or newer."
        exit 1
    }
    Write-Host "Java $version at $full"
    Save-Choice $full
    exit 0
}

# ---- detection --------------------------------------------------------------------------------
$jdks = @(Find-Jdks)
if ($jdks.Count -eq 0) {
    Write-Host "No JDK found in the usual places."
    Write-Host "Run again with the folder of a JDK $MinimumVersion+ (the one containing bin\$exeName):"
    Write-Host "  .\bin\setup-java.ps1 -JavaHome 'C:\path\to\jdk-21'"
    exit 1
}

$current = $null
if (Test-Path -LiteralPath $configFile -PathType Leaf) { $current = ([System.IO.File]::ReadAllText($configFile)).Trim() }

Write-Host "Installed JDKs:"
for ($i = 0; $i -lt $jdks.Count; $i++) {
    $jdk = $jdks[$i]
    $marks = @()
    if ($jdk.Major -lt $MinimumVersion) { $marks += "too old, needs $MinimumVersion+" }
    if ($current -and [string]::Equals($current, $jdk.Home, [System.StringComparison]::OrdinalIgnoreCase)) { $marks += 'current choice' }
    $note = if ($marks.Count -gt 0) { '   (' + ($marks -join ', ') + ')' } else { '' }
    $versionText = if ($jdk.Version) { $jdk.Version } else { '?' }
    Write-Host ("  [{0}] Java {1,-12} {2}{3}" -f ($i + 1), $versionText, $jdk.Home, $note)
}
if ($List) { exit 0 }

$usable = @($jdks | Where-Object { $_.Major -ge $MinimumVersion })
if ($usable.Count -eq 0) {
    Write-Host ""
    Write-Host "None of them is Java $MinimumVersion or newer. Install a JDK $MinimumVersion+ and run this again."
    exit 1
}
$default = [array]::IndexOf($jdks, $usable[0]) + 1

while ($true) {
    $answer = Read-Host "Use which JDK? [1-$($jdks.Count), Enter = $default, q = quit]"
    if ([string]::IsNullOrWhiteSpace($answer)) { $answer = "$default" }
    if ($answer -eq 'q') { exit 1 }
    $number = 0
    if ([int]::TryParse($answer, [ref]$number) -and $number -ge 1 -and $number -le $jdks.Count) {
        $chosen = $jdks[$number - 1]
        if ($chosen.Major -lt $MinimumVersion) {
            Write-Host "Java $($chosen.Version) is too old; choose a JDK $MinimumVersion or newer."
            continue
        }
        Save-Choice $chosen.Home
        exit 0
    }
    Write-Host "Please enter a number between 1 and $($jdks.Count)."
}
