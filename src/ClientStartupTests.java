import java.util.*;
import java.util.Base64;

/** v1.12.0: client can remember the connection code and auto-open/share on login. This covers the checkbox state mapping. */
class ClientStartupTests {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void run()throws Exception{
        // v1.12.6: code stored with AES (no PowerShell/DPAPI), so "Ingat kode" works on locked-down PCs.
        String code="Rahasia-Lab-2026!";String enc=RemoteServices.Startup.encryptCode(code);
        check(enc.startsWith("A1:"),"stored code carries the AES marker");
        check(!enc.contains(code),"plaintext code is not visible in the stored value");
        check(RemoteServices.Startup.decryptCode(enc).equals(code),"code decrypts back to the original");
        check(!RemoteServices.Startup.encryptCode(code).equals(enc),"each encryption uses a fresh random IV");
        boolean tampered=false;try{RemoteServices.Startup.decryptCode("A1:"+Base64.getEncoder().encodeToString(new byte[40]));}catch(Exception e){tampered=true;}check(tampered,"garbage/tampered ciphertext is rejected");
        // {remember, rememberEnabled, autoStart, admin}
        check(Arrays.equals(LanControl.Agent.startupFlags(false,false,false),new boolean[]{false,true,false,false}),"fresh client: nothing checked, remember editable");
        check(Arrays.equals(LanControl.Agent.startupFlags(true,false,false),new boolean[]{true,true,false,false}),"Ingat kode only: remember on and still editable, no auto-start");
        check(Arrays.equals(LanControl.Agent.startupFlags(true,true,false),new boolean[]{true,false,true,false}),"login auto-start: remember forced on and locked, auto-start on, not admin");
        check(Arrays.equals(LanControl.Agent.startupFlags(true,false,true),new boolean[]{true,false,true,true}),"admin auto-start: remember locked on, auto-start on, admin on");
        check(Arrays.equals(LanControl.Agent.startupFlags(false,true,false),new boolean[]{true,false,true,false}),"auto-start always implies the code is kept");
        // v1.12.2: a failed Windows setup shows the real reason. CLIXML error records are decoded to plain text.
        check(RemoteServices.Startup.firstLines("#< CLIXML\r\n<Objs Version=\"1.1.0.1\"><S S=\"Error\">Access is denied_x000D__x000A_</S></Objs>").equals("Access is denied"),"CLIXML error decoded to plain text");
        check(RemoteServices.Startup.firstLines("Register-ScheduledTask : Access is denied.\r\nAt line:1 char:1\r\n+ Register-ScheduledTask").equals("Register-ScheduledTask : Access is denied."),"plain PowerShell error trimmed of noise lines");
        check(RemoteServices.Startup.firstLines("<S S=\"Error\">running scripts is disabled on this system_x000D__x000A_</S>").contains("running scripts is disabled"),"execution-policy error surfaced");
        System.out.println("PASS: v1.12.0 client code memory & auto-start: checkbox state mapping (remember/lock/auto-start/admin) for every saved state.");
    }
}
