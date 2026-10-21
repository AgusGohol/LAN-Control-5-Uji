@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java belum terpasang. Pasang Java 17 atau lebih baru.
  pause
  exit /b 1
)
java -jar LAN-Control-5.jar client
if errorlevel 1 (
  echo Aplikasi gagal. Pastikan Java 17 atau lebih baru tersedia.
  pause
  exit /b 1
)
