@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java belum terpasang. Pasang Java 17 atau lebih baru.
  pause
  exit /b 1
)
echo Memasang mode Administrator (akan muncul UAC sekali). Setujui dengan "Ya".
javaw -jar LAN-Control-5.jar --pasang-admin
echo Setelah muncul pesan "Mode Administrator aktif", nyalakan ulang PC klien ini.
