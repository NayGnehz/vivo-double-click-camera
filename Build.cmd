@echo off
setlocal
set "portableRoot=%~dp0"
:findRoot
set "portableEnvironment="
for /d %%D in ("%portableRoot%_*") do if exist "%%~fD\bin\Invoke-Portable.ps1" set "portableEnvironment=%%~fD"
if defined portableEnvironment goto foundRoot
for %%I in ("%portableRoot%..") do set "portableParent=%%~fI\"
if /i "%portableParent%"=="%portableRoot%" (
    echo Restore the shared portable environment beside this workspace. 1>&2
    exit /b 1
)
set "portableRoot=%portableParent%"
goto findRoot
:foundRoot
set "portablePwsh=%portableEnvironment%\tools\pwsh\7.6.9\win-x64\pwsh.exe"
if not exist "%portablePwsh%" (
    echo Restore the pinned portable PowerShell in the shared environment. 1>&2
    exit /b 1
)
"%portablePwsh%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0Build.ps1" %*
exit /b %ERRORLEVEL%