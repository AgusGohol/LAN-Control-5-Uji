// LAN Control 5 - "Proyek resiko" Langkah 1a (v2)
// HANYA MEMBACA nama desktop yang sedang aktif. TIDAK jadi SYSTEM, TIDAK menangkap layar,
// TIDAK mengirim klik, TIDAK SwitchDesktop. Risiko nyaris nol. Berhenti sendiri ~40 detik.
// Kompatibel dengan compiler C# bawaan Windows (.NET Framework 4.x / C# 5).

using System;
using System.IO;
using System.Text;
using System.Runtime.InteropServices;
using System.Threading;
using System.Collections.Generic;
using System.Security.Principal;

class Probe {
    [DllImport("user32.dll", SetLastError = true)]
    static extern IntPtr OpenInputDesktop(uint dwFlags, bool fInherit, uint dwDesiredAccess);
    [DllImport("user32.dll", SetLastError = true)]
    static extern IntPtr GetThreadDesktop(uint dwThreadId);
    [DllImport("kernel32.dll")]
    static extern uint GetCurrentThreadId();
    [DllImport("user32.dll", SetLastError = true)]
    static extern bool CloseDesktop(IntPtr hDesktop);
    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool GetUserObjectInformationW(IntPtr hObj, int nIndex, byte[] pvInfo, uint nLength, out uint lpnLengthNeeded);

    const int UOI_NAME = 2;
    const uint DESKTOP_READOBJECTS = 0x0001;
    static StringBuilder log = new StringBuilder();

    static void Log(string s) { Console.WriteLine(s); log.AppendLine(s); }

    static string DesktopName(IntPtr h) {
        if (h == IntPtr.Zero) return "(null)";
        uint needed;
        byte[] buf = new byte[256];
        if (GetUserObjectInformationW(h, UOI_NAME, buf, (uint)buf.Length, out needed)) {
            int n = (int)needed; if (n > buf.Length) n = buf.Length;
            return Encoding.Unicode.GetString(buf, 0, n).TrimEnd('\0');
        }
        return "(gagal baca nama, error " + Marshal.GetLastWin32Error() + ")";
    }

    static void Main() {
        string dir = AppDomain.CurrentDomain.BaseDirectory;
        string logPath = Path.Combine(dir, "probe1a-log.txt");
        const int totalSec = 40;
        const int steps = totalSec * 2; // tiap 0.5 detik

        Log("=== LAN Control 5 - Probe 1a v2 (HANYA LAPORAN) ===");
        Log("Waktu mulai : " + DateTime.Now);
        try {
            WindowsIdentity id = WindowsIdentity.GetCurrent();
            bool admin = new WindowsPrincipal(id).IsInRole(WindowsBuiltInRole.Administrator);
            Log("Akun        : " + id.Name + (admin ? "  (Administrator)" : "  (bukan admin)"));
        } catch (Exception e) { Log("Akun        : (gagal baca: " + e.Message + ")"); }

        IntPtr td = GetThreadDesktop(GetCurrentThreadId());
        Log("Desktop proses ini : " + DesktopName(td));
        Log("Lama pemantauan     : " + totalSec + " detik");
        Log("");
        Console.WriteLine("==================================================================");
        Console.WriteLine(" SEKARANG picu UAC: klik kanan Command Prompt -> 'Run as administrator'");
        Console.WriteLine(" >> BIARKAN kotak UAC terbuka 3-5 detik, BARU tekan No (atau Yes).");
        Console.WriteLine(" >> Boleh diulang beberapa kali selama hitung mundur berjalan.");
        Console.WriteLine("==================================================================");
        Console.WriteLine();

        List<string> seen = new List<string>();
        string last = "__none__";
        bool secure = false;
        for (int i = 0; i < steps; i++) {
            IntPtr h = OpenInputDesktop(0, false, DESKTOP_READOBJECTS);
            string name;
            if (h == IntPtr.Zero) name = "(OpenInputDesktop gagal, error " + Marshal.GetLastWin32Error() + ")";
            else { name = DesktopName(h); CloseDesktop(h); }

            if (!seen.Contains(name)) seen.Add(name);
            if (name.IndexOf("Winlogon", StringComparison.OrdinalIgnoreCase) >= 0) secure = true;
            if (name != last) { Log(string.Format("[{0,5:0.0}s] INPUT desktop = {1}", i * 0.5, name)); last = name; }

            int remain = (steps - i) / 2;
            Console.Write("\r   sisa waktu: " + remain + " detik   (desktop input terbaca: " + last + ")        ");
            Thread.Sleep(500);
        }
        Console.WriteLine();
        Console.WriteLine();

        Log("");
        Log("Desktop berbeda yang terlihat: " + string.Join(", ", seen.ToArray()));
        Log("Secure desktop (Winlogon) terdeteksi? : " + (secure ? "YA" : "TIDAK"));
        Log("Selesai : " + DateTime.Now);
        try { File.WriteAllText(logPath, log.ToString()); Console.WriteLine("Log tersimpan di: " + logPath); }
        catch (Exception e) { Console.WriteLine("Gagal menyimpan log: " + e.Message); }
        Console.WriteLine("Tekan Enter untuk menutup...");
        Console.ReadLine();
    }
}
