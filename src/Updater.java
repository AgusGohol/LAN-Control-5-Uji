import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Self-update. A newer LAN-Control-5.jar placed in the "update" folder next to the application
 * (by the developer on the master, or pushed by the master to clients over the encrypted session)
 * is verified, then swapped in by a small PowerShell helper after this process exits, and the
 * application is started again with the same mode.
 */
class Updater {
    static final String JAR="LAN-Control-5.jar",PREVIOUS="LAN-Control-5.previous.jar",UPDATED_FLAG="--updated";
    /** GitHub repository that publishes releases ("owner/name"); empty disables the internet check. */
    static final String REPO="AgusGohol/LAN-Control-5-Uji";
    /** Ed25519 release key. Only JARs signed by the matching private key (kept offline by the publisher) are installed from the internet. */
    static volatile String publicKey="MCowBQYDK2VwAyEAILcdG8IcO6s3WYYxHUbmXSQHwP15CG/Z40dy37iTo6A=";
    static volatile String apiBase="https://raw.githubusercontent.com",repository=REPO;
    static final long MAX_DOWNLOAD=64L*1024*1024;
    static volatile Path appDir;// overridable for tests
    static Path dir()throws Exception{Path d=appDir;return d!=null?d:RemoteServices.Startup.jar().getParent();}
    static Path staging()throws Exception{return dir().resolve("update");}
    static Path staged()throws Exception{return staging().resolve(JAR);}

    /** Compares dotted versions numerically: 1.10.0 > 1.9.2. */
    static int compare(String a,String b){
        String[] x=a.trim().split("\\."),y=b.trim().split("\\.");
        for(int i=0;i<Math.max(x.length,y.length);i++){int p=i<x.length?number(x[i]):0,q=i<y.length?number(y[i]):0;if(p!=q)return Integer.compare(p,q);}
        return 0;
    }
    static int number(String s){try{return Integer.parseInt(s.replaceAll("[^0-9]",""));}catch(NumberFormatException e){return 0;}}
    static boolean newer(String version){return version!=null&&!version.isBlank()&&compare(version,LanControl.VERSION)>0;}

    /** Version of a LAN Control JAR, or null when the file is incomplete, corrupt or not this application. */
    static String inspect(Path jar){
        try(JarFile j=new JarFile(jar.toFile(),true)){
            Manifest m=j.getManifest();if(m==null)return null;Attributes a=m.getMainAttributes();
            if(!"LanControl".equals(a.getValue("Main-Class"))||j.getEntry("LanControl.class")==null)return null;
            // Read every entry fully: a truncated download fails here instead of after installation.
            byte[] buffer=new byte[16384];for(Enumeration<JarEntry> e=j.entries();e.hasMoreElements();){JarEntry entry=e.nextElement();try(InputStream in=j.getInputStream(entry)){while(in.read(buffer)>=0){}}}
            String v=a.getValue("Implementation-Version");return v==null||v.isBlank()?null:v.trim();
        }catch(Exception e){return null;}
    }
    /** Newer, valid staged version, or null. */
    static String pending(){try{Path p=staged();if(!Files.isRegularFile(p))return null;String v=inspect(p);return newer(v)?v:null;}catch(Exception e){return null;}}

    static String helperScript(long pid,Path staged,Path jar,Path backup,Path javaw,String arguments,Path workDir){
        String q=RemoteServices.Startup.literal(staged.toString()),j=RemoteServices.Startup.literal(jar.toString()),b=RemoteServices.Startup.literal(backup.toString());
        return "$ErrorActionPreference='SilentlyContinue';"+
            "Wait-Process -Id "+pid+" -Timeout 90;Start-Sleep -Milliseconds 800;$ok=$false;"+
            "for($i=0;$i -lt 30 -and -not $ok;$i++){try{Copy-Item -LiteralPath "+j+" -Destination "+b+" -Force -ErrorAction Stop;Copy-Item -LiteralPath "+q+" -Destination "+j+" -Force -ErrorAction Stop;$ok=$true}catch{Start-Sleep -Milliseconds 500}};"+
            "if($ok){Remove-Item -LiteralPath "+q+" -Force};"+
            "Start-Process -FilePath "+RemoteServices.Startup.literal(javaw.toString())+" -ArgumentList "+RemoteServices.Startup.literal(arguments)+" -WorkingDirectory "+RemoteServices.Startup.literal(workDir.toString());
    }
    static String arguments(Path jar,List<String> args){StringBuilder s=new StringBuilder("-jar \""+jar+"\"");for(String a:args)s.append(' ').append(a);s.append(' ').append(UPDATED_FLAG);return s.toString();}
    /** Starts the helper; the caller must exit right after so the JAR can be replaced. */
    static void restart(List<String> args)throws Exception{
        RemoteServices.windows();Path jar=RemoteServices.Startup.jar(),javaw=RemoteServices.Startup.javaw();
        String script=helperScript(ProcessHandle.current().pid(),staged(),jar,staging().resolve(PREVIOUS),javaw,arguments(jar,args),jar.getParent());
        String encoded=Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        // "start" gives the helper its own console, so it survives when Jalankan-*.bat closes its window after this JVM exits.
        Process launcher=new ProcessBuilder("cmd.exe","/c","start","\"\"","/min","powershell.exe","-NoProfile","-NonInteractive","-WindowStyle","Hidden","-EncodedCommand",encoded)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        launcher.getOutputStream().close();if(!launcher.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Pembantu pembaruan tidak dapat dijalankan.");
        if(launcher.exitValue()!=0)throw new IOException("Pembantu pembaruan gagal dijalankan (kode "+launcher.exitValue()+").");
    }
    static boolean canRestart(){try{RemoteServices.windows();RemoteServices.Startup.jar();RemoteServices.Startup.javaw();return true;}catch(Exception e){return false;}}

    // Keeps a typed connection code across the restart, protected by Windows DPAPI and deleted after reading.
    static Path resumeFile(){return Settings.file("resume.properties");}
    static void stash(String code,Map<String,String> extra)throws Exception{
        Properties p=new Properties();if(code!=null&&code.length()>=12)p.setProperty("code",RemoteServices.Startup.protect(RemoteServices.b64(code),true));
        p.setProperty("created",Long.toString(System.currentTimeMillis()));extra.forEach(p::setProperty);Settings.save(resumeFile(),p);
    }
    /** Reads and deletes the resume file; ignores files older than 10 minutes. */
    static Properties takeResume(){
        Path f=resumeFile();if(!Files.exists(f))return null;Properties p=Settings.load(f);try{Files.deleteIfExists(f);}catch(IOException ignored){}
        long created;try{created=Long.parseLong(p.getProperty("created","0"));}catch(NumberFormatException e){created=0;}
        return System.currentTimeMillis()-created>10*60_000?null:p;
    }
    static String code(Properties resume)throws Exception{String c=resume.getProperty("code");return c==null?null:RemoteServices.un64(RemoteServices.Startup.protect(c,false));}

    static boolean verify(byte[] jar,byte[] signature){
        try{java.security.PublicKey key=java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.trim())));
            java.security.Signature v=java.security.Signature.getInstance("Ed25519");v.initVerify(key);v.update(jar);return v.verify(signature);
        }catch(Exception e){return false;}
    }
    /** Release channel inside the repository: rilis/versi-terbaru.txt ("versi=X.Y.Z") and immutable, versioned JAR + signature files. */
    static final String CHANNEL="rilis/versi-terbaru.txt";
    static String jarName(String version){return "LAN-Control-5-"+version+".jar";}
    static String parseLatest(String text){for(String line:text.split("\\R")){line=line.trim();if(line.startsWith("versi=")){String v=line.substring(6).trim();return v.matches("[0-9]+(\\.[0-9]+)*")?v:null;}}return null;}
    static byte[] get(java.net.http.HttpClient http,String url,String accept)throws Exception{
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(60)).header("User-Agent","LAN-Control-5/"+LanControl.VERSION).header("Accept",accept).GET().build();
        var response=http.send(request,java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        try(InputStream in=response.body()){if(response.statusCode()!=200)throw new IOException("Server pembaruan menjawab "+response.statusCode()+".");
            byte[] data=in.readNBytes((int)MAX_DOWNLOAD+1);if(data.length>MAX_DOWNLOAD)throw new IOException("File pembaruan terlalu besar.");return data;}
    }
    /**
     * Checks the GitHub repository (rilis/ folder) for a newer signed release and stages it in the update folder (the normal install path takes over).
     * Returns the staged version, or null when there is nothing newer.
     */
    static String checkOnline()throws Exception{
        String repo=repository;if(repo==null||repo.isBlank())return null;
        var http=java.net.http.HttpClient.newBuilder().proxy(java.net.ProxySelector.getDefault()).followRedirects(java.net.http.HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(15)).build();
        String base=apiBase+"/"+repo+"/main/rilis/",text;
        try{text=LanControl.string(get(http,apiBase+"/"+repo+"/main/"+CHANNEL,"text/plain"));}catch(IOException e){if(String.valueOf(e.getMessage()).contains(" 404."))return null;throw e;}
        String version=parseLatest(text);if(!newer(version))return null;String waiting=pending();if(waiting!=null&&compare(waiting,version)>=0)return waiting;
        byte[] jar=get(http,base+jarName(version),"application/octet-stream"),signature=Base64.getDecoder().decode(LanControl.string(get(http,base+jarName(version)+".sig","application/octet-stream")).trim());
        if(!verify(jar,signature))throw new IOException("Tanda tangan pembaruan v"+version+" tidak valid. Pembaruan ditolak.");
        Files.createDirectories(staging());Path temp=Files.createTempFile(staging(),".download-",".part");
        try{Files.write(temp,jar);String found=inspect(temp);if(!version.equals(found))throw new IOException("Versi di dalam file pembaruan tidak sesuai.");
            Files.move(temp,staged(),StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
        return version;
    }

    /** Master side: send this JAR to a client over the session, then ask it to install and restart. */
    interface Progress {void update(long done,long total);}
    static void push(LanControl.Peer peer,byte[] jar,String version,Progress progress)throws Exception{
        String id=LanControl.string(peer.rpc("UPDATE_BEGIN",version));boolean done=false;
        try{for(int offset=0;offset<jar.length;offset+=RemoteServices.CHUNK){int n=Math.min(RemoteServices.CHUNK,jar.length-offset);
                peer.rpc("PUT_CHUNK",id,Base64.getEncoder().encodeToString(Arrays.copyOfRange(jar,offset,offset+n)));progress.update(offset+n,jar.length);}
            peer.rpc("PUT_END",id);done=true;
        }finally{if(!done&&peer.alive)try{peer.rpc("PUT_CANCEL",id);}catch(Exception ignored){}}
        peer.rpc("UPDATE_APPLY",version);
    }
}
