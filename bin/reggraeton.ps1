<#
.SYNOPSIS
  Runs the reggraeton CLI from a source checkout, with the JDK chosen by bin\setup-java.ps1.

.EXAMPLE
  .\bin\reggraeton.ps1 assess C:\work\legacy-repo --out reggraeton-report

.NOTES
  Build it first:  . .\bin\java-env.ps1; mvn -q package -DskipTests
  Extra JVM options: $env:REGGRAETON_JAVA_OPTS = '-Xmx2g'
#>
$ErrorActionPreference = 'Stop'
$java = & (Join-Path $PSScriptRoot 'resolve-java.ps1')
$jar = Join-Path (Split-Path -Parent $PSScriptRoot) 'reggraeton-cli\target\reggraeton.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    Write-Host "reggraeton.jar not found. Build it first:  . .\bin\java-env.ps1; mvn -q package -DskipTests"
    exit 1
}
$javaOptions = @()
if ($env:REGGRAETON_JAVA_OPTS) {
    $javaOptions = @($env:REGGRAETON_JAVA_OPTS -split '\s+' | Where-Object { $_ })
}
& $java.Java @javaOptions -jar $jar @args
exit $LASTEXITCODE
