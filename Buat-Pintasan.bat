@echo off
setlocal
cd /d "%~dp0"
echo Buat pintasan LAN Control 5 di Desktop dan menu Start:
echo   1 = Komputer UTAMA
echo   2 = Komputer KLIEN
echo   3 = Komputer KLIEN dengan izin Administrator (disarankan)
choice /c 123 /n /m "Pilih 1, 2, atau 3: "
if errorlevel 3 (set MODE=klien-admin) else if errorlevel 2 (set MODE=klien) else (set MODE=utama)
java -jar LAN-Control-5.jar --shortcut %MODE%
