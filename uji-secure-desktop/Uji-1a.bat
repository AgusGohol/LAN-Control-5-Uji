@echo off
setlocal
cd /d "%~dp0"
echo === LAN Control 5 - Uji Langkah 1a (hanya laporan, tanpa admin) ===
echo.

rem Cari compiler C# bawaan Windows (.NET Framework 4.x). Tidak perlu install apa pun.
set "CSC="
for %%d in ("%WINDIR%\Microsoft.NET\Framework64\v4.0.30319" "%WINDIR%\Microsoft.NET\Framework\v4.0.30319") do if exist "%%~d\csc.exe" set "CSC=%%~d\csc.exe"

if "%CSC%"=="" (
  echo [GAGAL] Tidak menemukan compiler C# bawaan Windows ^(csc.exe^).
  echo        Biasanya ada di Windows 10/11. Kirim pesan ini ke Claude.
  echo.
  pause
  exit /b 1
)
echo Compiler : %CSC%
echo Mengompilasi probe1a.cs ...
"%CSC%" /nologo /out:probe1a.exe probe1a.cs
if errorlevel 1 (
  echo.
  echo [GAGAL] Kompilasi gagal. Salin/foto pesan error di atas dan kirim ke Claude.
  echo         ^(Tidak ada yang berjalan - aman.^)
  pause
  exit /b 1
)
echo Berhasil dikompilasi: probe1a.exe
echo.
echo Menjalankan... siapkan untuk memicu UAC saat diminta.
echo.
probe1a.exe
echo.
echo Log tersimpan di: %~dp0probe1a-log.txt
echo Kirim isi probe1a-log.txt itu ke Claude.
pause
