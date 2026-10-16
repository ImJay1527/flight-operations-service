@echo off
rem Double-click to open the Flight Operations control panel (launcher.py), without a console window.
rem Needs Python 3 (python.org); everything else is checked by the panel itself.
cd /d "%~dp0"
where pythonw >nul 2>nul && (start "" pythonw launcher.py & exit /b)
where python >nul 2>nul && (python launcher.py & exit /b)
echo Python 3 is needed to open the control panel: https://www.python.org/downloads/
pause
