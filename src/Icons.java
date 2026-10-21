import java.awt.Image;
import java.awt.Window;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Application icons (window/taskbar, .ico files for Windows shortcuts) and the Desktop / Start menu shortcuts.
 * "utama" = main controller, "klien" = client (normal or administrator).
 */
final class Icons {
    private Icons(){}
    static final int[] SIZES={16,20,24,32,40,48,64,128,256};
    static final String UTAMA="utama",KLIEN="klien";
    private static final Map<String,List<Image>> CACHE=new HashMap<>();
    static synchronized List<Image> images(String kind){
        return CACHE.computeIfAbsent(kind,k->{List<Image> list=new ArrayList<>();
            for(int s:SIZES)try(InputStream in=Icons.class.getResourceAsStream("/ikon/"+k+"-"+s+".png")){if(in!=null)list.add(ImageIO.read(in));}catch(IOException ignored){}
            return list;});
    }
    static void apply(Window w,String kind){List<Image> list=images(kind);if(!list.isEmpty())w.setIconImages(list);}
    static byte[] ico(String kind)throws IOException{try(InputStream in=Icons.class.getResourceAsStream("/ikon/"+kind+".ico")){if(in==null)throw new IOException("Ikon tidak ditemukan di aplikasi.");return in.readAllBytes();}}

    /** Writes ikon/utama.ico and ikon/klien.ico next to the JAR (shortcuts point there). Returns true when a file changed. */
    static boolean install()throws Exception{
        Path dir=Updater.dir().resolve("ikon");Files.createDirectories(dir);boolean changed=false;
        for(String kind:new String[]{UTAMA,KLIEN}){Path f=dir.resolve(kind+".ico");byte[] data=ico(kind);
            if(!Files.exists(f)||!Arrays.equals(Files.readAllBytes(f),data)){Path temp=Files.createTempFile(dir,kind,".tmp");Files.write(temp,data);Files.move(temp,f,StandardCopyOption.REPLACE_EXISTING);changed=true;}}
        return changed;
    }
    static Path icon(String kind)throws Exception{return Updater.dir().resolve("ikon").resolve(kind+".ico");}
    static String name(String kind){return kind.equals(UTAMA)?"LAN Control 5 - Utama":"LAN Control 5 - Klien";}

    /** PowerShell that creates/updates the Desktop and Start menu shortcut for one mode. */
    static String shortcutScript(String kind,Path javaw,Path jar,Path icon,String arguments){
        return "$w=New-Object -ComObject WScript.Shell;"+
            "foreach($d in @([Environment]::GetFolderPath('Desktop'),[Environment]::GetFolderPath('Programs'))){"+
            "if([string]::IsNullOrEmpty($d)){continue};"+
            "$s=$w.CreateShortcut((Join-Path $d "+RemoteServices.Startup.literal(name(kind)+".lnk")+"));"+
            "$s.TargetPath="+RemoteServices.Startup.literal(javaw.toString())+";"+
            "$s.Arguments="+RemoteServices.Startup.literal("-jar \""+jar+"\""+(arguments.isEmpty()?"":" "+arguments))+";"+
            "$s.WorkingDirectory="+RemoteServices.Startup.literal(jar.getParent().toString())+";"+
            "$s.IconLocation="+RemoteServices.Startup.literal(icon+",0")+";"+
            "$s.Description="+RemoteServices.Startup.literal(kind.equals(UTAMA)?"LAN Control 5 - kontrol semua komputer":"LAN Control 5 - bagikan komputer ini")+";"+
            "$s.Save()}";
    }
    /** Only refresh the icon of shortcuts that already exist (Desktop, Start menu, Startup); never creates new ones. */
    static String refreshScript(Path utama,Path klien){
        return "$w=New-Object -ComObject WScript.Shell;"+
            "foreach($d in @([Environment]::GetFolderPath('Desktop'),[Environment]::GetFolderPath('Programs'),[Environment]::GetFolderPath('Startup'))){"+
            "if([string]::IsNullOrEmpty($d)){continue};"+
            "foreach($n in @(@('LAN Control 5 - Utama.lnk',"+RemoteServices.Startup.literal(utama+",0")+"),@('LAN Control 5 - Klien.lnk',"+RemoteServices.Startup.literal(klien+",0")+"),@('LAN Control 5 Klien.lnk',"+RemoteServices.Startup.literal(klien+",0")+"))){"+
            "$p=Join-Path $d $n[0];if(Test-Path -LiteralPath $p){$s=$w.CreateShortcut($p);$s.IconLocation=$n[1];$s.Save()}}}";
    }
    static void createShortcut(String kind,String arguments)throws Exception{
        RemoteServices.windows();install();
        RemoteServices.Startup.powershell(shortcutScript(kind,RemoteServices.Startup.javaw(),RemoteServices.Startup.jar(),icon(kind),arguments),"");
    }
    /**
     * Called at start-up (background). Creates this mode's Desktop/Start shortcut once per Windows account,
     * and refreshes icons of existing shortcuts after an update changed the icon files.
     */
    static void ensure(String kind,java.util.function.Supplier<String> arguments){
        try{RemoteServices.windows();RemoteServices.Startup.jar();}catch(Exception notInstalled){return;}
        try{boolean changed=install();Path flags=Settings.file("pintasan.properties");Properties p=Settings.load(flags);
            if(!"1".equals(p.getProperty(kind))){createShortcut(kind,arguments.get());p.setProperty(kind,"1");Settings.save(flags,p);}
            else if(changed)RemoteServices.Startup.powershell(refreshScript(icon(UTAMA),icon(KLIEN)),"");
        }catch(Exception ignored){}
    }
}
