@echo off
rem Runs the reggraeton CLI from a source checkout.
rem Java is taken from (first match): REGGRAETON_JAVA_HOME, the JDK saved by bin\setup-java.ps1
rem (.reggraeton\java-home), JAVA_HOME, java.exe on the PATH.
rem Build first:  powershell -Command ". .\bin\java-env.ps1; mvn -q package -DskipTests"
setlocal
set "ROOT=%~dp0.."
set "JAR=%ROOT%\reggraeton-cli\target\reggraeton.jar"
set "JAVA_EXE="

if defined REGGRAETON_JAVA_HOME if exist "%REGGRAETON_JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%REGGRAETON_JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if exist "%ROOT%\.reggraeton\java-home" set /p "SAVED_HOME="<"%ROOT%\.reggraeton\java-home"
if not defined JAVA_EXE if defined SAVED_HOME if exist "%SAVED_HOME%\bin\java.exe" set "JAVA_EXE=%SAVED_HOME%\bin\java.exe"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%J in ('where java.exe 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%J"
if not defined JAVA_EXE goto noJava

if not exist "%JAR%" goto noJar
"%JAVA_EXE%" %REGGRAETON_JAVA_OPTS% -jar "%JAR%" %*
exit /b %ERRORLEVEL%

:noJava
echo No Java found. Choose one of your installed JDKs with: 1>&2
echo   powershell -ExecutionPolicy Bypass -File "%~dp0setup-java.ps1" 1>&2
echo or set REGGRAETON_JAVA_HOME to a JDK folder. 1>&2
exit /b 1

:noJar
echo reggraeton.jar not found. Build it first: 1>&2
echo   powershell -Command ". .\bin\java-env.ps1; mvn -q package -DskipTests" 1>&2
exit /b 1
