@echo off
setlocal
cd /d "%~dp0"
if not exist "update\LAN-Control-5.previous.jar" (
  echo Tidak ada versi sebelumnya di folder update.
  pause
  exit /b 1
)
echo Tutup aplikasi LAN Control 5 di komputer ini terlebih dahulu.
pause
copy /y "LAN-Control-5.jar" "update\LAN-Control-5.rollback-dari.jar" >nul
copy /y "update\LAN-Control-5.previous.jar" "LAN-Control-5.jar"
if errorlevel 1 (
  echo Gagal. Pastikan aplikasi sudah ditutup.
  pause
  exit /b 1
)
if exist "update\LAN-Control-5.jar" del /q "update\LAN-Control-5.jar"
echo Versi sebelumnya sudah dikembalikan. Buka aplikasi seperti biasa.
pause
