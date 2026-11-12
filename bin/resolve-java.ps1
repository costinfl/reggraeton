<#
.SYNOPSIS
  Finds the Java installation reggraeton's scripts use. Used by the other scripts in bin\ and scripts\.

.DESCRIPTION
  Order:
    1. $env:REGGRAETON_JAVA_HOME          (a JDK folder, for one-off overrides)
    2. .reggraeton\java-home              (saved by bin\setup-java.ps1)
    3. $env:JAVA_HOME
    4. java.exe on the PATH
  Returns an object with Java (path of java.exe), Home (JDK folder, or $null when only an
  executable on the PATH was found) and Source (where it came from). Throws when nothing is found.
#>
[CmdletBinding()]
param()

$root = Split-Path -Parent $PSScriptRoot
$onWindows = ($PSVersionTable.PSEdition -ne 'Core') -or $IsWindows
$exeName = if ($onWindows) { 'java.exe' } else { 'java' }

function Get-JavaExe([string]$jdkHome) {
    if ([string]::IsNullOrWhiteSpace($jdkHome)) { return $null }
    $exe = Join-Path (Join-Path $jdkHome.Trim() 'bin') $exeName
    if (Test-Path -LiteralPath $exe -PathType Leaf) { return $exe }
    return $null
}

$candidates = @()
$candidates += , @('REGGRAETON_JAVA_HOME', $env:REGGRAETON_JAVA_HOME)
$saved = Join-Path (Join-Path $root '.reggraeton') 'java-home'
if (Test-Path -LiteralPath $saved -PathType Leaf) {
    $candidates += , @('.reggraeton\java-home (bin\setup-java.ps1)', ([System.IO.File]::ReadAllText($saved)).Trim())
}
$candidates += , @('JAVA_HOME', $env:JAVA_HOME)

foreach ($candidate in $candidates) {
    $source = $candidate[0]
    $jdkHome = $candidate[1]
    if ([string]::IsNullOrWhiteSpace($jdkHome)) { continue }
    $exe = Get-JavaExe $jdkHome
    if ($exe) {
        return [pscustomobject]@{ Java = $exe; Home = $jdkHome.Trim(); Source = $source }
    }
    Write-Warning "$source points to '$jdkHome', which has no bin\$exeName - ignoring it."
}

$onPath = Get-Command $exeName -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if ($onPath) {
    return [pscustomobject]@{ Java = $onPath.Path; Home = $null; Source = 'PATH' }
}

throw "No Java found. Run .\bin\setup-java.ps1 to choose one of your installed JDKs (17 or newer), or set REGGRAETON_JAVA_HOME to a JDK folder."
