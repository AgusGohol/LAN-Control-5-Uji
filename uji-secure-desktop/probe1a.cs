// LAN Control 5 - "Proyek resiko" Langkah 1a
// HANYA MEMBACA nama desktop yang sedang aktif. TIDAK jadi SYSTEM, TIDAK menangkap layar,
// TIDAK mengirim klik, TIDAK SwitchDesktop. Risiko nyaris nol. Berhenti sendiri ~15 detik.
// Ditulis agar kompatibel dengan compiler C# bawaan Windows (.NET Framework 4.x / C# 5).

using System;
using System.IO;
using System.Text;
using System.Runtime.InteropServices;
using System.Threading;
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

        Log("=== LAN Control 5 - Probe 1a (HANYA LAPORAN) ===");
        Log("Waktu mulai : " + DateTime.Now);
        try {
            WindowsIdentity id = WindowsIdentity.GetCurrent();
            bool admin = new WindowsPrincipal(id).IsInRole(WindowsBuiltInRole.Administrator);
            Log("Akun        : " + id.Name + (admin ? "  (Administrator)" : "  (bukan admin)"));
        } catch (Exception e) { Log("Akun        : (gagal baca: " + e.Message + ")"); }

        IntPtr td = GetThreadDesktop(GetCurrentThreadId());
        Log("Desktop proses ini : " + DesktopName(td));
        Log("");
        Log("Memantau INPUT desktop selama ~15 detik.");
        Log(">> Silakan PICU UAC sekarang: klik kanan Command Prompt -> 'Run as administrator'");
        Log("   (boleh langsung tekan No/Yes; kita hanya ingin melihat namanya).");
        Log("   Jika nama berubah jadi 'Winlogon' = kita BISA mendeteksi secure desktop.");
        Log("   Jika muncul 'OpenInputDesktop gagal, error 5' saat UAC = butuh SYSTEM (itu wajar, info berharga).");
        Log("");

        string last = "__none__";
        for (int i = 0; i < 30; i++) {
            IntPtr h = OpenInputDesktop(0, false, DESKTOP_READOBJECTS);
            string name;
            if (h == IntPtr.Zero) name = "(OpenInputDesktop gagal, error " + Marshal.GetLastWin32Error() + ")";
            else { name = DesktopName(h); CloseDesktop(h); }
            if (name != last) { Log(string.Format("[{0,5:0.0}s] INPUT desktop = {1}", i * 0.5, name)); last = name; }
            Thread.Sleep(500);
        }

        Log("");
        Log("Selesai : " + DateTime.Now);
        try { File.WriteAllText(logPath, log.ToString()); Console.WriteLine(); Console.WriteLine("Log tersimpan di: " + logPath); }
        catch (Exception e) { Console.WriteLine("Gagal menyimpan log: " + e.Message); }
        Console.WriteLine("Tekan Enter untuk menutup...");
        Console.ReadLine();
    }
}
