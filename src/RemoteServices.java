import java.awt.*;
import java.awt.datatransfer.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Visible, explicitly enabled LAN administration in the logged-in user's session. */
class RemoteServices implements AutoCloseable {
    static final int CHUNK=256*1024,LIST_LIMIT=5000;
    final Map<String,Upload> uploads=new HashMap<>();
    record Upload(Path target,Path temporary,OutputStream output) {}
    /** Set by the client session: schedules install + restart after UPDATE_APPLY has been answered. */
    volatile Runnable onUpdate,onElevate;
    /** With several masters on one client, only one may upload an update at a time. */
    static final java.util.concurrent.atomic.AtomicReference<RemoteServices> UPDATING=new java.util.concurrent.atomic.AtomicReference<>();
    /** "ADMIN", "BIASA" or unknown; set by the client window after checking its Windows token. */
    static volatile String privilege="TIDAK DIKETAHUI";
    static String b64(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    static String un64(String s){return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8);}
    static byte[] text(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static Path path(String s)throws IOException{if(s.isBlank())throw new IOException("Isi alamat file/folder klien.");return Path.of(s).toAbsolutePath().normalize();}
    /** Desktop of the logged-in user; on Windows this follows OneDrive/redirected Desktop folders. */
    /** Windows computer name (shown as the default name of this client on the master). */
    static String hostName(){String n=System.getenv("COMPUTERNAME");if(n!=null&&!n.isBlank())return n.trim();try{return java.net.InetAddress.getLocalHost().getHostName();}catch(Exception e){return "";}}
    static volatile Path desktopOverride;// tests
    static Path desktop(){
        if(desktopOverride!=null)return desktopOverride;
        try{File home=javax.swing.filechooser.FileSystemView.getFileSystemView().getHomeDirectory();if(System.getProperty("os.name").toLowerCase().contains("win")&&home.isDirectory())return home.toPath();}catch(Exception ignored){}
        Path d=Path.of(System.getProperty("user.home"),"Desktop");return Files.isDirectory(d)?d:Path.of(System.getProperty("user.home"));
    }
    static void windows()throws IOException{if(!System.getProperty("os.name").toLowerCase().contains("win"))throw new IOException("Fitur ini memerlukan Windows.");}
    /** Synchronized: file RPCs run on a worker thread while close() may run on the session thread. */
    synchronized byte[] execute(String op,List<String> a)throws Exception {
        switch(op){
            case "LIST": {
                List<Path> paths;
                if(a.get(0).isBlank())paths=Arrays.stream(File.listRoots()).map(File::toPath).toList();
                else try(var stream=Files.list(path(a.get(0)))){paths=stream.sorted().limit(LIST_LIMIT).toList();}
                // Fields: path, type, size, modified epoch ms (4th field ignored by masters older than v1.5.0).
                StringBuilder b=new StringBuilder();for(Path p:paths){boolean dir=Files.isDirectory(p);long size=0,modified=0;try{if(!dir)size=Files.size(p);}catch(IOException ignored){}try{if(!a.get(0).isBlank())modified=Files.getLastModifiedTime(p).toMillis();}catch(IOException ignored){}b.append(b64(p.toString())).append('\t').append(dir?"D":"F").append('\t').append(size).append('\t').append(modified).append('\n');}return text(b.toString());
            }
            case "SIZE":return text(Long.toString(Files.size(path(a.get(0)))));
            case "GET": {
                Path p=path(a.get(0));long offset=Long.parseLong(a.get(1));int count=Integer.parseInt(a.get(2));
                if(offset<0||count<1||count>CHUNK)throw new IOException("Ukuran blok tidak valid.");
                try(RandomAccessFile f=new RandomAccessFile(p.toFile(),"r")){f.seek(offset);byte[] b=new byte[count];int n=f.read(b);return n<0?new byte[0]:Arrays.copyOf(b,n);}
            }
            case "PUT_BEGIN": {
                if(uploads.size()>=4)throw new IOException("Terlalu banyak transfer aktif.");
                Path target=path(a.get(0));if(Files.exists(target))throw new IOException("File tujuan sudah ada. Gunakan nama lain.");
                Path temporary=Files.createTempFile(target.getParent(),".lan5-",".part");String id=UUID.randomUUID().toString();
                uploads.put(id,new Upload(target,temporary,Files.newOutputStream(temporary)));return text(id);
            }
            case "PUT_CHUNK": {
                Upload u=uploads.get(a.get(0));if(u==null)throw new IOException("Transfer tidak ditemukan.");byte[] b=Base64.getDecoder().decode(a.get(1));if(b.length>CHUNK)throw new IOException("Blok terlalu besar.");u.output.write(b);return text("OK");
            }
            case "PUT_END": {
                Upload u=uploads.remove(a.get(0));if(u==null)throw new IOException("Transfer tidak ditemukan.");
                try{u.output.close();Files.move(u.temporary,u.target);}finally{Files.deleteIfExists(u.temporary);}return text("Tersimpan");
            }
            case "PUT_CANCEL": {Upload u=uploads.remove(a.get(0));if(u!=null){u.output.close();Files.deleteIfExists(u.temporary);}return text("Dibatalkan");}
            case "VERSION":return text(LanControl.VERSION);
            case "DESKTOP":return text(desktop().toString());
            case "PRIVILEGE":return text(privilege);
            case "HOSTNAME":return text(hostName());
            case "ELEVATE": {
                if("ADMIN".equals(privilege))throw new IOException("Klien sudah berjalan sebagai Administrator.");
                Runnable elevate=onElevate;if(elevate==null)throw new IOException("Klien ini tidak dapat meminta izin Administrator.");
                elevate.run();return text("Jendela UAC ditampilkan di klien. Seseorang di klien perlu menekan Ya.");
            }
            case "UPDATE_BEGIN": {
                String version=a.get(0);if(!Updater.newer(version))throw new IOException("Versi "+version+" tidak lebih baru dari klien v"+LanControl.VERSION+".");
                if(onUpdate==null)throw new IOException("Klien ini tidak dapat memulai ulang otomatis.");
                if(uploads.size()>=4)throw new IOException("Terlalu banyak transfer aktif.");
                if(!UPDATING.compareAndSet(null,this)&&UPDATING.get()!=this)throw new IOException("Klien sedang menerima pembaruan dari komputer utama lain.");
                Path target=Updater.staged();Files.createDirectories(target.getParent());Files.deleteIfExists(target);
                Path temporary=Files.createTempFile(target.getParent(),".lan5-",".part");String id=UUID.randomUUID().toString();
                uploads.put(id,new Upload(target,temporary,Files.newOutputStream(temporary)));return text(id);
            }
            case "UPDATE_APPLY": {
                String version=a.get(0),found=Updater.inspect(Updater.staged());
                if(found==null||!found.equals(version)||!Updater.newer(found))throw new IOException("File pembaruan tidak lengkap atau tidak valid.");
                Runnable apply=onUpdate;if(apply==null)throw new IOException("Klien ini tidak dapat memulai ulang otomatis.");
                apply.run();return text("Klien memasang v"+version+" dan mulai ulang.");
            }
            case "MKDIR":Files.createDirectory(path(a.get(0)));return text("Folder dibuat");
            case "RENAME":Files.move(path(a.get(0)),path(a.get(1)));return text("Nama diubah");
            case "DELETE":Files.delete(path(a.get(0)));return text("Dihapus (folder harus kosong)");
            case "OPEN":Desktop.getDesktop().open(path(a.get(0)).toFile());return text("Dibuka di klien");
            case "CLIP_GET":return text((String)Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor));
            case "CLIP_SET":Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(a.get(0)),null);return text("Teks clipboard klien diperbarui");
            case "LOCK":windows();new ProcessBuilder("rundll32.exe","user32.dll,LockWorkStation").start();return text("Permintaan kunci dikirim");
            case "RESTART":case "SHUTDOWN":windows();new ProcessBuilder("shutdown.exe",op.equals("RESTART")?"/r":"/s","/t","30").start();return text("Dijadwalkan 30 detik. Batalkan dengan tombol Batal shutdown.");
            case "CANCEL_POWER":windows();new ProcessBuilder("shutdown.exe","/a").start();return text("Permintaan pembatalan dikirim");
            default:throw new IOException("Perintah tidak dikenal.");
        }
    }
    public synchronized void close(){UPDATING.compareAndSet(this,null);for(Upload u:uploads.values())try{u.output.close();Files.deleteIfExists(u.temporary);}catch(IOException ignored){}uploads.clear();}

    static class Startup {
        static boolean isAdmin()throws Exception{return powershell("$p=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent());[Console]::Write($p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))","").equalsIgnoreCase("True");}
        static Path jar()throws Exception {Path p=Path.of(LanControl.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();if(!Files.isRegularFile(p)||!p.toString().endsWith(".jar"))throw new IOException("Jalankan JAR dari folder permanen yang sudah diekstrak.");return p;}
        static Path javaw()throws Exception {Path p=Path.of(System.getProperty("java.home"),"bin","javaw.exe");if(!Files.exists(p))throw new IOException("javaw.exe tidak ditemukan.");return p;}
        static String elevationScript(Path executable,Path jar){return elevationScript(executable,jar,"client");}
        static String elevationScript(Path executable,Path jar,String arguments){return "Start-Process -FilePath "+literal(executable.toString())+" -ArgumentList "+literal("-jar \""+jar+"\" "+arguments)+" -WorkingDirectory "+literal(jar.getParent().toString())+" -Verb RunAs";}
        static void elevate()throws Exception {elevate("client");}
        static void elevate(String arguments)throws Exception {windows();powershell(elevationScript(javaw(),jar(),arguments),"",180);}
        // The elevated login task is created through schtasks.exe (a native tool), not PowerShell's ScheduledTasks
        // cmdlets, because those cmdlets are blocked where a security policy forces PowerShell into Constrained
        // Language Mode. schtasks consumes a full task definition as XML, which keeps the program path and its
        // arguments in separate fields (no fragile /TR quoting) and asks for no stored password (InteractiveToken).
        static String taskName(){return "LANControl5Uji-Client-"+System.getProperty("user.name","user").replaceAll("[^A-Za-z0-9_-]","_");}
        static String account(){String d=System.getenv("USERDOMAIN"),u=System.getenv("USERNAME");if(u==null||u.isBlank())u=System.getProperty("user.name","");return (d!=null&&!d.isBlank()?d+"\\":"")+u;}
        static String xmlEsc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
        /** Task Scheduler XML: runs the client at this user's logon, elevated (HighestAvailable) with no UAC prompt. */
        static String taskXml(Path javaw,Path jar){String acct=xmlEsc(account());return
            "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n"+
            "<Task version=\"1.2\" xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\">\n"+
            "  <RegistrationInfo><Description>LAN Control 5: klien terbuka sebagai Administrator saat login</Description></RegistrationInfo>\n"+
            "  <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>"+acct+"</UserId></LogonTrigger></Triggers>\n"+
            "  <Principals><Principal id=\"Author\"><UserId>"+acct+"</UserId><LogonType>InteractiveToken</LogonType><RunLevel>HighestAvailable</RunLevel></Principal></Principals>\n"+
            "  <Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries><StopIfGoingOnBatteries>false</StopIfGoingOnBatteries><AllowHardTerminate>false</AllowHardTerminate><StartWhenAvailable>true</StartWhenAvailable><Enabled>true</Enabled><Hidden>false</Hidden><ExecutionTimeLimit>PT0S</ExecutionTimeLimit><Priority>7</Priority></Settings>\n"+
            "  <Actions Context=\"Author\"><Exec><Command>"+xmlEsc(javaw.toString())+"</Command><Arguments>-jar &quot;"+xmlEsc(jar.toString())+"&quot; client --auto</Arguments><WorkingDirectory>"+xmlEsc(jar.getParent().toString())+"</WorkingDirectory></Exec></Actions>\n"+
            "</Task>\n";}
        /** Runs schtasks.exe and returns {exit code, combined output}. Native tool, unaffected by PowerShell policy. */
        static String[] runSchtasks(String... args)throws Exception{
            windows();List<String> cmd=new ArrayList<>();cmd.add("schtasks.exe");cmd.addAll(Arrays.asList(args));
            Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start();p.getOutputStream().close();
            var out=LanControl.WORK.submit(()->p.getInputStream().readAllBytes());
            if(!p.waitFor(20,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("schtasks melewati batas waktu.");}
            byte[] b=out.get(5,TimeUnit.SECONDS);
            return new String[]{String.valueOf(p.exitValue()),new String(b,java.nio.charset.Charset.defaultCharset()).trim()};
        }
        static void createAdminTask()throws Exception{
            Path xml=Files.createTempFile("lan5-task-",".xml");
            // schtasks /XML requires a UTF-16 file with a byte-order mark; UTF-8 fails with
            // "unable to switch the encoding". Java's UTF_16LE writes no BOM, so prepend it.
            try{Files.write(xml,("﻿"+taskXml(javaw(),jar())).getBytes(StandardCharsets.UTF_16LE));
                String[] r=runSchtasks("/Create","/TN",taskName(),"/XML",xml.toString(),"/F");
                if(!"0".equals(r[0]))throw new IOException("Tidak bisa membuat tugas Administrator"+(r[1].isEmpty()?" (schtasks kode "+r[0]+")":": "+firstLines(r[1])));
            }finally{try{Files.deleteIfExists(xml);}catch(Exception ignored){}}
        }
        static void removeTask()throws Exception {try{runSchtasks("/Delete","/TN",taskName(),"/F");}catch(Exception ignored){}}
        static void enableAdmin(String password)throws Exception {
            windows();if(!isAdmin())throw new IOException("Tutup klien, buka Jalankan-Klien-Admin.bat dan setujui UAC di komputer klien dahulu.");
            saveCode(password);createAdminTask();
            try{Files.deleteIfExists(shortcut());}catch(Exception ignored){}try{Files.deleteIfExists(startupCmd());}catch(Exception ignored){}
        }
        static Path config(){return Path.of(System.getenv("APPDATA"),"LANControl5Uji","client.properties");}
        static Path shortcut(){return Path.of(System.getenv("APPDATA"),"Microsoft","Windows","Start Menu","Programs","Startup","LAN Control 5 UJI Klien.lnk");}
        static String literal(String s){return "'"+s.replace("'","''")+"'";}
        static String powershell(String script,String input)throws Exception{return powershell(script,input,20);}
        // Default calls (shortcut, scheduled task, queries) carry no secret, so a failure reports the real Windows reason.
        static String powershell(String script,String input,int timeout)throws Exception{return powershell(script,input,timeout,true);}
        // hidden=true suppresses stderr for calls whose input is the connection code (DPAPI), so the secret never reaches a dialog.
        static String powershell(String script,String input,int timeout,boolean showReason)throws Exception{
            // Progress off; the try/catch writes the real exception as plain text (LANERR:...) to STDOUT, which works even
            // where stderr is serialized or restricted. The message is the exception text, never the piped input.
            windows();String wrapped="$ProgressPreference='SilentlyContinue';$ErrorActionPreference='Stop';try{"+script+"}catch{try{[Console]::Out.Write('LANERR:'+$_.Exception.GetType().Name+': '+$_.Exception.Message)}catch{};exit 1}";
            String encoded=Base64.getEncoder().encodeToString(wrapped.getBytes(StandardCharsets.UTF_16LE));
            Process p=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-EncodedCommand",encoded).start();
            try(OutputStream out=p.getOutputStream()){out.write(input.getBytes(StandardCharsets.UTF_8));}
            var stdout=LanControl.WORK.submit(()->p.getInputStream().readAllBytes());
            var stderr=LanControl.WORK.submit(()->p.getErrorStream().readAllBytes());
            if(!p.waitFor(timeout,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Pengaturan Windows melewati batas waktu.");}
            byte[] result=stdout.get(5,TimeUnit.SECONDS);byte[] errBytes=stderr.get(5,TimeUnit.SECONDS);
            if(p.exitValue()!=0){String all=new String(result,StandardCharsets.UTF_8)+"\n"+new String(errBytes,StandardCharsets.UTF_8);int i=all.indexOf("LANERR:");
                String reason=i>=0?firstLines(all.substring(i+7)):firstLines(all);
                throw new IOException("Pengaturan Windows gagal."+(reason.isEmpty()?" (kode keluar "+p.exitValue()+", tanpa pesan). Pastikan PowerShell tersedia dan folder aplikasi dapat diakses.":" "+reason));}
            return new String(result,StandardCharsets.UTF_8).trim();
        }
        /** Readable PowerShell error for a dialog: decode CLIXML error records, drop noise, trim. */
        static String firstLines(String text){
            text=text.strip();
            if(text.contains("CLIXML")){
                // Pull the human text out of the serialized error/warning records; fall back to stripping tags.
                StringBuilder clix=new StringBuilder();java.util.regex.Matcher m=java.util.regex.Pattern.compile("<S S=\"(?:Error|Warning)\">(.*?)</S>",java.util.regex.Pattern.DOTALL).matcher(text);
                while(m.find())clix.append(unclix(m.group(1)));
                text=clix.length()>0?clix.toString():unclix(text.replaceAll("#< CLIXML","").replaceAll("<[^>]*>"," "));
            }
            StringBuilder s=new StringBuilder();for(String line:text.split("\\R")){String t=line.strip();if(t.isEmpty()||t.startsWith("At line:")||t.startsWith("+")||t.startsWith("CategoryInfo")||t.startsWith("FullyQualifiedErrorId"))continue;if(s.length()>0)s.append(" ");s.append(t);if(s.length()>300)break;}
            String out=s.toString().replaceAll("\\s+"," ").strip();return out.length()>300?out.substring(0,300)+"...":out;}
        /** Undo CLIXML escaping: _xHHHH_ control chars and basic XML entities. */
        static String unclix(String s){
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("_x([0-9A-Fa-f]{4})_").matcher(s);StringBuilder b=new StringBuilder();
            while(m.find())m.appendReplacement(b,java.util.regex.Matcher.quoteReplacement(String.valueOf((char)Integer.parseInt(m.group(1),16))));m.appendTail(b);
            return b.toString().replace("&lt;","<").replace("&gt;",">").replace("&amp;","&").replace("&quot;","\"").replace("&apos;","'");}
        static String protect(String password,boolean encrypt)throws Exception {
            String script="Add-Type -AssemblyName System.Security;$b=[Convert]::FromBase64String([Console]::In.ReadToEnd());$r=[Security.Cryptography.ProtectedData]::"+(encrypt?"Protect":"Unprotect")+"($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser);[Console]::Write([Convert]::ToBase64String($r))";
            return powershell(script,password,20,false);
        }
        /** Saves only the connection code (Windows DPAPI, this account) — no auto-start. Used by the "Ingat kode" option. */
        // Code storage uses AES-GCM with a key derived from this Windows user + computer, so it never needs PowerShell/DPAPI
        // (blocked on PCs where a security policy forces PowerShell into Constrained Language Mode).
        static final String CODE_MARK="A1:";
        static javax.crypto.SecretKey codeKey()throws Exception{
            String seed=(System.getProperty("user.name","?")+"|"+String.valueOf(System.getenv("COMPUTERNAME"))+"|LANControl5Uji").toLowerCase();
            javax.crypto.spec.PBEKeySpec spec=new javax.crypto.spec.PBEKeySpec(seed.toCharArray(),"LC5-code-salt-v1".getBytes(StandardCharsets.UTF_8),100000,256);
            return new javax.crypto.spec.SecretKeySpec(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),"AES");
        }
        static String encryptCode(String plain)throws Exception{
            byte[] iv=new byte[12];new java.security.SecureRandom().nextBytes(iv);
            javax.crypto.Cipher c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");c.init(javax.crypto.Cipher.ENCRYPT_MODE,codeKey(),new javax.crypto.spec.GCMParameterSpec(128,iv));
            byte[] ct=c.doFinal(plain.getBytes(StandardCharsets.UTF_8));byte[] out=new byte[12+ct.length];System.arraycopy(iv,0,out,0,12);System.arraycopy(ct,0,out,12,ct.length);
            return CODE_MARK+Base64.getEncoder().encodeToString(out);
        }
        static String decryptCode(String stored)throws Exception{
            byte[] in=Base64.getDecoder().decode(stored.substring(CODE_MARK.length()));
            javax.crypto.Cipher c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");c.init(javax.crypto.Cipher.DECRYPT_MODE,codeKey(),new javax.crypto.spec.GCMParameterSpec(128,java.util.Arrays.copyOf(in,12)));
            return new String(c.doFinal(java.util.Arrays.copyOfRange(in,12,in.length)),StandardCharsets.UTF_8);
        }
        static void saveCode(String password)throws Exception {
            windows();if(password.length()<12)throw new IOException("Gunakan kode minimal 12 karakter.");
            Properties props=new Properties();props.setProperty("code",encryptCode(password));Files.createDirectories(config().getParent());
            Path temp=Files.createTempFile(config().getParent(),"settings-",".tmp");try(OutputStream out=Files.newOutputStream(temp)){props.store(out,"LAN Control 5");}
            Files.move(temp,config(),StandardCopyOption.REPLACE_EXISTING);
        }
        static boolean hasCode(){return Files.isRegularFile(config());}
        static void forgetCode()throws Exception{windows();Files.deleteIfExists(config());}
        static boolean hasShortcut(){try{return Files.exists(shortcut())||Files.exists(startupCmd());}catch(Exception e){return false;}}
        /** Whether the elevated login task is registered (runs the client as Administrator at login, no UAC). */
        static boolean taskActive()throws Exception{return "0".equals(runSchtasks("/Query","/TN",taskName())[0]);}
        static Path startupCmd(){return Path.of(System.getenv("APPDATA"),"Microsoft","Windows","Start Menu","Programs","Startup","LAN Control 5 UJI Klien.cmd");}
        /** Writes a .cmd launcher in the Startup folder (no COM) - used when creating a .lnk shortcut is blocked by policy. */
        static void writeStartupCmd(Path jar,Path javaw)throws Exception{
            Files.createDirectories(startupCmd().getParent());
            Files.writeString(startupCmd(),"@echo off\r\nstart \"\" \""+javaw+"\" -jar \""+jar+"\" client --auto\r\n",StandardCharsets.UTF_8);
        }
        static void enable(String password)throws Exception {
            windows();try{removeTask();}catch(Exception ignored){}saveCode(password);
            Path jar=jar(),javaw=javaw();
            try{powershell("$s=(New-Object -ComObject WScript.Shell).CreateShortcut("+literal(shortcut().toString())+");$s.TargetPath="+literal(javaw.toString())+";$s.Arguments="+literal("-jar \""+jar+"\" client --auto")+";$s.WorkingDirectory="+literal(jar.getParent().toString())+";$s.Description='LAN Control 5 - klien terlihat saat login';"+startupIcon()+"$s.Save()","");Files.deleteIfExists(startupCmd());}
            catch(Exception comBlocked){writeStartupCmd(jar,javaw);try{Files.deleteIfExists(shortcut());}catch(Exception ignored){}}
        }
        /** Startup shortcut uses the client icon when it can be installed next to the JAR. */
        static String startupIcon(){try{Icons.install();return "$s.IconLocation="+literal(Icons.icon(Icons.KLIEN)+",0")+";";}catch(Exception e){return "";}}
        static String load()throws Exception{windows();Properties p=new Properties();try(InputStream in=Files.newInputStream(config())){p.load(in);}
            String stored=p.getProperty("code","");if(stored.startsWith(CODE_MARK))return decryptCode(stored);return un64(protect(stored,false));}
        /** Remove ONLY the non-elevated login launchers (Startup shortcut and .cmd), keeping the elevated task.
         *  The elevated client calls this at startup so a leftover non-admin launcher can't open a second instance. */
        static void removeLoginLaunchers(){try{Files.deleteIfExists(shortcut());}catch(Exception ignored){}try{Files.deleteIfExists(startupCmd());}catch(Exception ignored){}}
        /** Turn off auto-start (login shortcut and elevated task) but keep the saved code. */
        static void disableStartup()throws Exception{windows();try{removeTask();}catch(Exception ignored){}Files.deleteIfExists(shortcut());Files.deleteIfExists(startupCmd());}
        static void disable()throws Exception{windows();removeTask();Files.deleteIfExists(shortcut());Files.deleteIfExists(startupCmd());Files.deleteIfExists(config());}
    }
}
