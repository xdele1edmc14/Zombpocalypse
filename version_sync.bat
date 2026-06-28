@echo off
rem Launch the Version Sync GUI without a console window.
rem pyw is the windowed Python launcher that ships with Python on Windows.
start "" pyw "%~dp0version_sync.py"
