import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

class ServiceTests {
    static void ok(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static String text(byte[] b){return LanControl.string(b);}
    static void run()throws Exception {
        Path dir=Files.createTempDirectory("lan5-service-test-");
        try(RemoteServices service=new RemoteServices()) {
            Path target=dir.resolve("unicode-é-berkas.bin");byte[] original=new byte[700000];new Random(51).nextBytes(original);
            String id=text(service.execute("PUT_BEGIN",List.of(target.toString())));
            for(int offset=0;offset<original.length;offset+=RemoteServices.CHUNK){byte[] chunk=Arrays.copyOfRange(original,offset,Math.min(original.length,offset+RemoteServices.CHUNK));service.execute("PUT_CHUNK",List.of(id,Base64.getEncoder().encodeToString(chunk)));}
            ok(!Files.exists(target),"unfinished upload not published");service.execute("PUT_END",List.of(id));
            ok(Arrays.equals(original,Files.readAllBytes(target)),"uploaded file exact bytes");
            ByteArrayOutputStream downloaded=new ByteArrayOutputStream();long size=Long.parseLong(text(service.execute("SIZE",List.of(target.toString()))));
            for(long offset=0;offset<size;offset+=RemoteServices.CHUNK)downloaded.write(service.execute("GET",List.of(target.toString(),Long.toString(offset),Integer.toString(RemoteServices.CHUNK))));
            ok(Arrays.equals(original,downloaded.toByteArray()),"download exact bytes");
            boolean collision=false;try{service.execute("PUT_BEGIN",List.of(target.toString()));}catch(IOException expected){collision=true;}ok(collision,"no silent overwrite on upload");
            String listing=text(service.execute("LIST",List.of(dir.toString())));ok(listing.contains(RemoteServices.b64(target.toString())),"unicode file listing");
            Path renamed=dir.resolve("renamed.bin");service.execute("RENAME",List.of(target.toString(),renamed.toString()));ok(Files.exists(renamed),"rename");
            service.execute("DELETE",List.of(renamed.toString()));ok(!Files.exists(renamed),"delete");
            Path canceled=dir.resolve("canceled.bin");String cancelId=text(service.execute("PUT_BEGIN",List.of(canceled.toString())));service.execute("PUT_CANCEL",List.of(cancelId));ok(!Files.exists(canceled),"cancel does not publish");
            service.execute("PUT_BEGIN",List.of(dir.resolve("disconnected.bin").toString()));service.close();
            try(var files=Files.list(dir)){ok(files.findAny().isEmpty(),"disconnect cleans pending upload");}
            Path sub=dir.resolve("folder");service.execute("MKDIR",List.of(sub.toString()));ok(Files.isDirectory(sub),"create directory");service.execute("DELETE",List.of(sub.toString()));
            ok(RemoteServices.Startup.literal("O'Brien").equals("'O''Brien'"),"PowerShell literal quoting");
            String elevation=RemoteServices.Startup.elevationScript(Path.of("C:/Program Files/Java/javaw.exe"),Path.of("C:/O'Brien/LAN-Control-5.jar"));
            ok(elevation.contains("-Verb RunAs"),"explicit UAC elevation");ok(elevation.contains("O''Brien"),"elevation path quoting");
            // v1.12.8: the elevated login task is defined as Task Scheduler XML and installed with schtasks.exe,
            // so it works where PowerShell's ScheduledTasks cmdlets are blocked (Constrained Language Mode).
            String task=RemoteServices.Startup.taskXml(Path.of("C:/Java/javaw.exe"),Path.of("C:/Apps/LAN-Control-5.jar"));
            ok(task.contains("<RunLevel>HighestAvailable</RunLevel>"),"client runs elevated (Administrator)");
            ok(task.contains("<LogonType>InteractiveToken</LogonType>"),"interactive logon token, no stored password");
            ok(task.contains("<LogonTrigger>"),"starts at this user's logon");
            ok(task.contains("javaw.exe</Command>")&&task.contains("client --auto"),"launches the client and auto-shares");
            ok(task.contains("LAN-Control-5.jar"),"points at the installed JAR");
            ok(task.contains("encoding=\"UTF-16\""),"XML declares UTF-16 (schtasks rejects UTF-8: 'unable to switch the encoding')");
            ok(!LanControl.connectionReason(new EOFException()).contains("null"),"readable EOF error");
            rpcTest(dir);
            System.out.println("PASS: 700 KB chunk upload/download, unicode paths, overwrite rejection, rename/delete/mkdir, cancel/disconnect cleanup, encrypted RPC responses and errors.");
        }finally{try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    static void rpcTest(Path dir)throws Exception {
        ExecutorService ex=Executors.newSingleThreadExecutor();
        try(ServerSocket ss=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Future<?> server=ex.submit(()->{try(Socket s=ss.accept();LanControl.Wire w=new LanControl.Wire(s,"rpc-test-password",true);RemoteServices service=new RemoteServices()){
                for(int i=0;i<2;i++)LanControl.handleRpc(w,service,LanControl.string(w.read()));
            }catch(Exception e){throw new RuntimeException(e);}});
            try(Socket s=new Socket(InetAddress.getLoopbackAddress(),ss.getLocalPort());LanControl.Wire w=new LanControl.Wire(s,"rpc-test-password",false)){
                w.send(LanControl.command("RPC abc LIST "+RemoteServices.b64(dir.toString())));String good=text(w.read());ok(good.startsWith("RESULT abc OK "),"RPC success id");
                w.send(LanControl.command("RPC xyz BAD_OPERATION"));String bad=text(w.read());ok(bad.startsWith("RESULT xyz ERR "),"RPC error id");
            }server.get(20,TimeUnit.SECONDS);
        }finally{ex.shutdownNow();}
    }
}
