@echo off
setlocal
cd /d "%~dp0"
if "%~1"=="" goto run
call ..\gradlew.bat -p "%~dp0." extractCatalog "-PcompetitorDir=%~1" --console=plain
if errorlevel 1 exit /b 1
:run
echo Starting backend and frontend. The browser opens when both are ready.
call ..\gradlew.bat -p "%~dp0." run --console=plain
exit /b %errorlevel%
