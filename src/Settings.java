import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** Small UTF-8 properties store in %APPDATA%\LANControl5 (or ~/.lancontrol5uji outside Windows). */
class Settings {
    static Path folder(){String app=System.getenv("APPDATA");return app!=null&&!app.isBlank()?Path.of(app,"LANControl5Uji"):Path.of(System.getProperty("user.home"),".lancontrol5uji");}
    static Path file(String name){return folder().resolve(name);}
    static Properties load(Path file){
        Properties p=new Properties();
        try(Reader in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(in);}catch(IOException ignored){}
        return p;
    }
    static synchronized void save(Path file,Properties p)throws IOException{
        Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"settings-",".tmp");
        try{try(Writer out=Files.newBufferedWriter(temp,StandardCharsets.UTF_8)){p.store(out,"LAN Control 5 - pengaturan komputer utama");}
            Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
    }
}
