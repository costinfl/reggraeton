<#
.SYNOPSIS
  Makes the chosen JDK the Java of the current PowerShell session (for mvn and java).

.DESCRIPTION
  Dot-source it:   . .\bin\java-env.ps1
  Sets $env:JAVA_HOME and puts its bin folder first on $env:Path, for this session only;
  nothing is changed permanently. Uses the same JDK as bin\reggraeton.ps1 (see bin\resolve-java.ps1).
#>
$reggraetonJava = & (Join-Path $PSScriptRoot 'resolve-java.ps1')
if ($reggraetonJava.Home) {
    $env:JAVA_HOME = $reggraetonJava.Home
    $env:Path = (Join-Path $reggraetonJava.Home 'bin') + [System.IO.Path]::PathSeparator + $env:Path
    Write-Host "JAVA_HOME=$($env:JAVA_HOME) (from $($reggraetonJava.Source)), for this session"
} else {
    Write-Host "Using $($reggraetonJava.Java) from the PATH (JAVA_HOME unchanged)."
}
Remove-Variable reggraetonJava
