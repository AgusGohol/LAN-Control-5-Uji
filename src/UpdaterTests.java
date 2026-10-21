import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.jar.*;
import javax.swing.*;

/** v1.5.1 self-update: version rules, JAR verification, staging, client push protocol and reconnect after client restart. */
class UpdaterTests {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static byte[] jar(String mainClass,String version,boolean withMain)throws IOException{
        Manifest m=new Manifest();m.getMainAttributes().putValue("Manifest-Version","1.0");m.getMainAttributes().putValue("Main-Class",mainClass);if(version!=null)m.getMainAttributes().putValue("Implementation-Version",version);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(JarOutputStream out=new JarOutputStream(bytes,m)){if(withMain){out.putNextEntry(new JarEntry("LanControl.class"));byte[] filler=new byte[200_000];new Random(7).nextBytes(filler);out.write(filler);out.closeEntry();}}
        return bytes.toByteArray();
    }
    static void run()throws Exception{
        check(Updater.compare("1.10.0","1.9.9")>0&&Updater.compare("1.5.1","1.5.0")>0&&Updater.compare("1.5","1.5.0")==0&&Updater.compare("1.4.4","1.5.0")<0,"numeric version order");
        check(Updater.newer("99.0")&&!Updater.newer(LanControl.VERSION)&&!Updater.newer("1.0.0")&&!Updater.newer(null),"newer than running version");
        Path dir=Files.createTempDirectory("lan5-update-");Path previous=Updater.appDir;Updater.appDir=dir;
        try{
            Path good=dir.resolve("good.jar");Files.write(good,jar("LanControl","9.0.0",true));check("9.0.0".equals(Updater.inspect(good)),"valid jar version read");
            byte[] full=Files.readAllBytes(good);Path cut=dir.resolve("cut.jar");Files.write(cut,Arrays.copyOf(full,full.length/2));check(Updater.inspect(cut)==null,"half-written jar rejected");
            Path other=dir.resolve("other.jar");Files.write(other,jar("SomethingElse","9.0.0",true));check(Updater.inspect(other)==null,"foreign application rejected");
            Path noVersion=dir.resolve("nov.jar");Files.write(noVersion,jar("LanControl",null,true));check(Updater.inspect(noVersion)==null,"jar without version rejected");
            check(Updater.pending()==null,"no staged update");Files.createDirectories(Updater.staging());
            Files.write(Updater.staged(),jar("LanControl","1.0.0",true));check(Updater.pending()==null,"older staged version ignored");
            Files.write(Updater.staged(),full);check("9.0.0".equals(Updater.pending()),"newer staged version detected");Files.delete(Updater.staged());
            String script=Updater.helperScript(4242,Path.of("C:/O'Brien/update/LAN-Control-5.jar"),Path.of("C:/O'Brien/LAN-Control-5.jar"),Path.of("C:/O'Brien/update/LAN-Control-5.previous.jar"),Path.of("C:/Java/bin/javaw.exe"),Updater.arguments(Path.of("C:/O'Brien/LAN-Control-5.jar"),List.of("client","--auto")),Path.of("C:/O'Brien"));
            check(script.contains("Wait-Process -Id 4242")&&script.contains("O''Brien")&&script.contains("client --auto "+Updater.UPDATED_FLAG)&&script.indexOf("previous.jar")<script.indexOf("Remove-Item"),"helper waits, backs up, swaps and restarts with same mode");
            online();
            clientServices(full);
            pushAndReconnect(full);
        }finally{Updater.appDir=previous;try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("PASS: v1.5.1 self-update: version order, truncated/foreign jar rejection, staging, helper script, signed GitHub rilis/ channel (wrong key/tampered/mismatched/older rejected), client install RPCs, master push and reconnect after client restart.");
    }
    /** Internet channel: only releases signed with the release key, newer than the running version and matching their tag are staged. */
    static void online()throws Exception{
        var good=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var evil=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String savedKey=Updater.publicKey,savedApi=Updater.apiBase,savedRepo=Updater.repository;
        byte[] release=jar("LanControl","9.1.0",true);Map<String,byte[]> files=new ConcurrentHashMap<>();String[] tag={"9.1.0"};
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);int port=server.getAddress().getPort();String base="http://127.0.0.1:"+port;
        server.createContext("/",x->{String path=x.getRequestURI().getPath();byte[] body;int code=200;
            if(path.equals("/pemilik/LAN-Control-5/main/rilis/versi-terbaru.txt"))body=("# LAN Control 5\r\nversi="+tag[0]+"\r\n").getBytes();
            else {body=files.get(path);if(body==null){body=new byte[0];code=404;}}
            x.getResponseHeaders().add("Content-Type","application/octet-stream");x.sendResponseHeaders(code,body.length==0?-1:body.length);try(OutputStream o=x.getResponseBody()){o.write(body);}});
        server.start();
        try{
            Updater.publicKey=Base64.getEncoder().encodeToString(good.getPublic().getEncoded());Updater.apiBase=base;Updater.repository="pemilik/LAN-Control-5";
            files.put("/pemilik/LAN-Control-5/main/rilis/LAN-Control-5-9.1.0.jar",release);files.put("/pemilik/LAN-Control-5/main/rilis/LAN-Control-5-9.1.0.jar.sig",sign(evil.getPrivate(),release));
            boolean rejected=false;try{Updater.checkOnline();}catch(IOException e){rejected=e.getMessage().contains("Tanda tangan");}check(rejected&&!Files.exists(Updater.staged()),"release signed with another key rejected and not staged");
            files.put("/pemilik/LAN-Control-5/main/rilis/LAN-Control-5-9.1.0.jar.sig",sign(good.getPrivate(),release));
            byte[] tampered=release.clone();tampered[tampered.length/2]^=1;files.put("/pemilik/LAN-Control-5/main/rilis/LAN-Control-5-9.1.0.jar",tampered);
            rejected=false;try{Updater.checkOnline();}catch(IOException e){rejected=true;}check(rejected&&!Files.exists(Updater.staged()),"modified jar rejected");
            files.put("/pemilik/LAN-Control-5/main/rilis/LAN-Control-5-9.1.0.jar",release);files.put("/pemilik/LAN-Control-5/main/rilis/LAN-Control-5-9.2.0.jar",release);files.put("/pemilik/LAN-Control-5/main/rilis/LAN-Control-5-9.2.0.jar.sig",sign(good.getPrivate(),release));tag[0]="9.2.0";rejected=false;try{Updater.checkOnline();}catch(IOException e){rejected=e.getMessage().contains("tidak sesuai");}check(rejected,"announced version must match jar version");
            tag[0]="1.0.0";check(Updater.checkOnline()==null,"older release ignored");
            tag[0]="9.1.0";check("9.1.0".equals(Updater.checkOnline())&&Arrays.equals(Files.readAllBytes(Updater.staged()),release)&&"9.1.0".equals(Updater.pending()),"signed newer release downloaded and staged");
            check("9.1.0".equals(Updater.checkOnline()),"already staged release not downloaded again");Files.delete(Updater.staged());
            check(Updater.parseLatest("versi=1.6.0\n")!=null&&Updater.parseLatest("versi=1.6.0; rm\n")==null&&Updater.parseLatest("kosong")==null,"strict version file parsing");tag[0]="9.3.0";boolean missing=false;try{Updater.checkOnline();}catch(IOException e){missing=e.getMessage().contains("404");}check(missing&&!Files.exists(Updater.staged()),"missing jar for announced version is an error, nothing staged");
            Updater.repository="";check(Updater.checkOnline()==null,"no repository = no internet check");
        }finally{server.stop(0);Updater.publicKey=savedKey;Updater.apiBase=savedApi;Updater.repository=savedRepo;}
    }
    static byte[] sign(java.security.PrivateKey key,byte[] data)throws Exception{var s=java.security.Signature.getInstance("Ed25519");s.initSign(key);s.update(data);return Base64.getEncoder().encode(s.sign());}
    /** Client side: only newer, complete, verified JARs are staged and trigger a restart. */
    static void clientServices(byte[] full)throws Exception{
        try(RemoteServices s=new RemoteServices()){
            check(LanControl.string(s.execute("VERSION",List.of())).equals(LanControl.VERSION),"version RPC");
            boolean refused=false;try{s.execute("UPDATE_BEGIN",List.of("9.0.0"));}catch(IOException e){refused=true;}check(refused,"no restart hook = no update");
            int[] applied={0};s.onUpdate=()->applied[0]++;
            refused=false;try{s.execute("UPDATE_BEGIN",List.of("1.0.0"));}catch(IOException e){refused=e.getMessage().contains("tidak lebih baru");}check(refused,"downgrade refused");
            String id=LanControl.string(s.execute("UPDATE_BEGIN",List.of("9.0.0")));s.execute("PUT_CHUNK",List.of(id,Base64.getEncoder().encodeToString(Arrays.copyOf(full,1000))));s.execute("PUT_END",List.of(id));
            refused=false;try{s.execute("UPDATE_APPLY",List.of("9.0.0"));}catch(IOException e){refused=true;}check(refused&&applied[0]==0,"incomplete transfer never installs");
            id=LanControl.string(s.execute("UPDATE_BEGIN",List.of("9.0.0")));for(int o=0;o<full.length;o+=RemoteServices.CHUNK)s.execute("PUT_CHUNK",List.of(id,Base64.getEncoder().encodeToString(Arrays.copyOfRange(full,o,Math.min(full.length,o+RemoteServices.CHUNK)))));s.execute("PUT_END",List.of(id));
            refused=false;try{s.execute("UPDATE_APPLY",List.of("9.0.1"));}catch(IOException e){refused=true;}check(refused,"version must match");
            s.execute("UPDATE_APPLY",List.of("9.0.0"));check(applied[0]==1&&Arrays.equals(Files.readAllBytes(Updater.staged()),full),"verified jar staged and restart requested");
            Files.delete(Updater.staged());
        }
    }
    /** Master side: an older client receives the master's own JAR, "restarts", and is reconnected even with Sambung ulang otomatis off. */
    static void pushAndReconnect(byte[] full)throws Exception{
        try(ServerSocket server=new ServerSocket(0,8,InetAddress.getLoopbackAddress())){
            List<String> operations=new CopyOnWriteArrayList<>();ByteArrayOutputStream received=new ByteArrayOutputStream();AtomicIntegerHolder sessions=new AtomicIntegerHolder();
            Future<?> loop=LanControl.WORK.submit(()->{try{LanControl.serve(server,"pembaruan-kode-01",()->true,(s,w)->{int session=++sessions.value;
                while(!s.isClosed()){String m=LanControl.string(w.read());if(!m.startsWith("RPC "))continue;String[] p=m.split(" ",-1);String op=p[2];operations.add(session+":"+op);String reply="OK";
                    switch(op){case "VERSION"->reply=session==1?"1.4.9":LanControl.VERSION;case "UPDATE_BEGIN"->reply="u1";case "PUT_CHUNK"->{synchronized(received){received.write(Base64.getDecoder().decode(RemoteServices.un64(p[4])));}}}
                    LanControl.reply(w,p[1],LanControl.command(reply));if(op.equals("UPDATE_APPLY")){Thread.sleep(100);s.close();}}});}catch(IOException ignored){}});
            LanControl.Controller[] holder=new LanControl.Controller[1];
            SwingUtilities.invokeAndWait(()->{LanControl.Controller c=new LanControl.Controller(true);c.port=server.getLocalPort();c.password.setText("pembaruan-kode-01");c.reconnect.setSelected(false);c.ownJar=()->full;
                LanControl.Card card=c.cards.get(0);card.ip.setText("127.0.0.1");card.connect();holder[0]=c;});
            LanControl.Card card=holder[0].cards.get(0);
            for(int i=0;i<300&&sessions.value<2;i++)Thread.sleep(50);
            for(int i=0;i<100&&(card.peer==null||!operations.contains("2:VERSION"));i++)Thread.sleep(50);
            check(operations.indexOf("1:VERSION")<operations.indexOf("1:UPDATE_BEGIN")&&operations.indexOf("1:PUT_END")<operations.indexOf("1:UPDATE_APPLY"),"push order: "+operations);
            synchronized(received){check(Arrays.equals(received.toByteArray(),full),"client received master jar exactly");}
            check(card.peer!=null&&card.peer.alive&&!card.expectRestart,"reconnected after client restart although auto-reconnect is off");
            check(operations.stream().noneMatch(o->o.startsWith("2:UPDATE")),"up-to-date client is not updated again");
            SwingUtilities.invokeAndWait(card::disconnect);server.close();loop.get(5,TimeUnit.SECONDS);
        }
    }
    static class AtomicIntegerHolder {volatile int value;}
}
