@echo off
rem Compatibility entry: backend now owns the collector lifecycle and reads config\analyzer.json.
call "%~dp0start-protolens.cmd" %*
exit /b %errorlevel%
