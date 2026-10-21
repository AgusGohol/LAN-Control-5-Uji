@echo off
rem === LAN Control 5 - Uji Langkah 1b: probe yang sama, tapi sebagai ADMINISTRATOR ===
rem Minta izin admin sekali (UAC), lalu kompilasi + jalankan probe1a.cs dengan hak admin.

rem --- cek apakah sudah berjalan sebagai administrator ---
net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Meminta izin Administrator ^(akan muncul UAC - tekan Yes^)...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

rem --- di sini sudah elevated ---
cd /d "%~dp0"
echo === Berjalan sebagai Administrator ===
echo.

set "CSC="
for %%d in ("%WINDIR%\Microsoft.NET\Framework64\v4.0.30319" "%WINDIR%\Microsoft.NET\Framework\v4.0.30319") do if exist "%%~d\csc.exe" set "CSC=%%~d\csc.exe"
if "%CSC%"=="" (
  echo [GAGAL] Tidak menemukan compiler C# bawaan Windows ^(csc.exe^).
  pause
  exit /b 1
)
echo Compiler : %CSC%
echo Mengompilasi probe1a.cs ...
"%CSC%" /nologo /out:probe1a.exe probe1a.cs
if errorlevel 1 (
  echo.
  echo [GAGAL] Kompilasi gagal. Kirim pesan error di atas ke Claude.
  pause
  exit /b 1
)
echo Berhasil. Menjalankan probe sebagai Administrator...
echo.
probe1a.exe
echo.
echo Log tersimpan di: %~dp0probe1a-log.txt
echo Kirim isi probe1a-log.txt itu ke Claude.
pause
