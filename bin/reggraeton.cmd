@echo off
rem Runs the reggraeton CLI from a source checkout. Build it first with: mvn -q package -DskipTests
setlocal
set "JAR=%~dp0..\reggraeton-cli\target\reggraeton.jar"
if not exist "%JAR%" (
  echo reggraeton.jar not found. Build it first: mvn -q package -DskipTests 1>&2
  exit /b 1
)
java %REGGRAETON_JAVA_OPTS% -jar "%JAR%" %*
