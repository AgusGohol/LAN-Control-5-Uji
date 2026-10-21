import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
public class Build {
    public static void main(String[] args)throws Exception {
        try(var old=Files.walk(Path.of("build"))){for(Path p:old.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}catch(NoSuchFileException fresh){}
        Files.createDirectories(Path.of("build"));
        List<String> options=new ArrayList<>(List.of("--release","17","-encoding","UTF-8","-d","build"));
        try(var sources=Files.list(Path.of("src"))){sources.filter(p->p.toString().endsWith(".java")).sorted().forEach(p->options.add(p.toString()));}
        int result=ToolProvider.getSystemJavaCompiler().run(null,null,null,options.toArray(String[]::new));
        if(result!=0)throw new Exception("Compile gagal: "+result);
        // Images (window icons, .ico for shortcuts) are packed into the JAR.
        if(Files.isDirectory(Path.of("resources")))try(var res=Files.walk(Path.of("resources"))){for(Path r:res.filter(Files::isRegularFile).toList()){Path t=Path.of("build").resolve(Path.of("resources").relativize(r));Files.createDirectories(t.getParent());Files.copy(r,t,StandardCopyOption.REPLACE_EXISTING);}}
        Manifest m=new Manifest();m.getMainAttributes().putValue("Manifest-Version","1.0");m.getMainAttributes().putValue("Main-Class","LanControl");
        var version=java.util.regex.Pattern.compile("VERSION=\"([0-9.]+)\"").matcher(Files.readString(Path.of("src","LanControl.java")));if(!version.find())throw new Exception("VERSION tidak ditemukan");m.getMainAttributes().putValue("Implementation-Version",version.group(1));
        Path output=Path.of("LAN-Control-5.jar"),temporary=Path.of("LAN-Control-5.building");
        try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(temporary),m);var paths=Files.walk(Path.of("build"))){
            for(Path p:paths.filter(Files::isRegularFile).toList()){jar.putNextEntry(new JarEntry(Path.of("build").relativize(p).toString().replace('\\','/')));Files.copy(p,jar);jar.closeEntry();}
        }
        Files.move(temporary,output,StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Build selesai: LAN-Control-5.jar v"+version.group(1));
    }
}
