@echo off
rem Startet den EnigmaEngine WORLD_HOST Testserver in einem eigenen Terminal.
cd /d "%~dp0"

set "JAVA_CMD="
if not defined JAVA_CMD if exist "C:\Program Files\Java\jdk-25\bin\java.exe" set "JAVA_CMD=C:\Program Files\Java\jdk-25\bin\java.exe"
if not defined JAVA_CMD if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_CMD set "JAVA_CMD=java"

if not "%JAVA_CMD%"=="java" if not exist "%JAVA_CMD%" (
    echo Java nicht gefunden. Bitte C:\Program Files\Java\jdk-25 installieren oder JAVA_HOME setzen.
    pause
    exit /b 1
)

if not exist "EnigmaEngine.jar" (
    echo EnigmaEngine.jar nicht gefunden.
    pause
    exit /b 1
)

start "EnigmaEngine WORLD_HOST" cmd /k ""%JAVA_CMD%" -Xmx2G -Denigma.distributed.enabled=true -Denigma.distributed.role=WORLD_HOST -Denigma.distributed.bind=0.0.0.0 -Denigma.distributed.port=25567 -jar "EnigmaEngine.jar" nogui"