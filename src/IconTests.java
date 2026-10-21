import java.awt.Image;
import java.nio.file.*;
import java.util.*;

/** v1.7.2: application icons inside the JAR, .ico installation, shortcut scripts for both modes. */
class IconTests {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void run()throws Exception{
        for(String kind:new String[]{Icons.UTAMA,Icons.KLIEN}){List<Image> list=Icons.images(kind);check(list.size()==Icons.SIZES.length,kind+": all window icon sizes present");
            check(list.get(0).getWidth(null)==16&&list.get(list.size()-1).getWidth(null)==256,kind+": 16 to 256 px");
            byte[] ico=Icons.ico(kind);check(ico.length>1000&&ico[0]==0&&ico[1]==0&&ico[2]==1&&ico[3]==0&&(ico[4]&255)>=6,kind+": multi-size .ico for Windows shortcuts");}
        check(!Arrays.equals(Icons.ico(Icons.UTAMA),Icons.ico(Icons.KLIEN)),"main and client icons differ");
        Path dir=Files.createTempDirectory("lan5-icons-"),previous=Updater.appDir;Updater.appDir=dir;
        try{check(Icons.install(),"first install writes icons");check(!Icons.install(),"unchanged icons are not rewritten");
            check(Arrays.equals(Files.readAllBytes(dir.resolve("ikon/klien.ico")),Icons.ico(Icons.KLIEN)),"client .ico installed next to the JAR");
            Files.write(dir.resolve("ikon/utama.ico"),new byte[]{1,2,3});check(Icons.install(),"updated icon is written again (shortcuts refreshed)");}
        finally{Updater.appDir=previous;try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        String s=Icons.shortcutScript(Icons.KLIEN,Path.of("C:/Program Files/Java/bin/javaw.exe"),Path.of("C:/Users/Windows 10/LAN/LAN-Control-5.jar"),Path.of("C:/Users/Windows 10/LAN/ikon/klien.ico"),"--request-admin");
        check(s.contains("'LAN Control 5 - Klien.lnk'")&&s.contains("--request-admin")&&s.contains("klien.ico,0")&&s.contains("GetFolderPath('Desktop')")&&s.contains("GetFolderPath('Programs')"),"client shortcut on Desktop + Start menu with its icon");
        check(Icons.shortcutScript(Icons.UTAMA,Path.of("j"),Path.of("C:/a/LAN-Control-5.jar"),Path.of("C:/a/ikon/utama.ico"),"").contains("'LAN Control 5 - Utama.lnk'"),"main shortcut name");
        String refresh=Icons.refreshScript(Path.of("u.ico"),Path.of("k.ico"));check(refresh.contains("Test-Path")&&refresh.contains("LAN Control 5 Klien.lnk")&&!refresh.contains("CreateShortcut((Join"),"refresh only touches existing shortcuts, including the startup one");
        System.out.println("PASS: v1.7.2 icons: window icons 16-256 px for main and client, .ico files installed/refreshed, Desktop + Start menu shortcut scripts.");
    }
}
