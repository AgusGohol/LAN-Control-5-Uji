import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** v1.6.2: the master warns about clients without administrator rights and can ask them to restart elevated. */
class PrivilegeTests {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void run()throws Exception{
        String script=RemoteServices.Startup.elevationScript(Path.of("C:/Program Files/Java/bin/javaw.exe"),Path.of("C:/Users/Windows 10/LAN/LAN-Control-5.jar"),"client --resume --upgrade-startup");
        check(script.contains("-Verb RunAs")&&script.contains("'-jar \"C:\\Users\\Windows 10\\LAN\\LAN-Control-5.jar\" client --resume --upgrade-startup'".replace('\\',java.io.File.separatorChar))||script.contains("client --resume --upgrade-startup"),"elevation keeps sharing and upgrades startup");
        check(RemoteServices.Startup.elevationScript(Path.of("j"),Path.of("C:/a/LAN-Control-5.jar")).contains("\" client'"),"launcher elevation unchanged");
        String previous=RemoteServices.privilege;
        try(RemoteServices s=new RemoteServices()){
            RemoteServices.privilege="BIASA";check(LanControl.string(s.execute("PRIVILEGE",List.of())).equals("BIASA"),"privilege reported");
            boolean refused=false;try{s.execute("ELEVATE",List.of());}catch(IOException e){refused=true;}check(refused,"no hook = no elevation");
            int[] asked={0};s.onElevate=()->asked[0]++;s.execute("ELEVATE",List.of());check(asked[0]==1,"elevation requested");
            RemoteServices.privilege="ADMIN";refused=false;try{s.execute("ELEVATE",List.of());}catch(IOException e){refused=e.getMessage().contains("sudah");}check(refused&&asked[0]==1,"already elevated client is not restarted");
        }
        RemoteServices.privilege="BIASA";
        try(ServerSocket server=new ServerSocket(0,8,InetAddress.getLoopbackAddress())){
            int[] elevations={0};
            Future<?> loop=LanControl.WORK.submit(()->{try{LanControl.serve(server,"izin-admin-kode",()->true,(s,w)->{try(RemoteServices services=new RemoteServices()){services.onElevate=()->elevations[0]++;while(!s.isClosed()){String m=LanControl.string(w.read());if(m.startsWith("RPC "))LanControl.handleRpc(w,services,m);}}});}catch(IOException ignored){}});
            LanControl.Controller[] holder=new LanControl.Controller[1];
            SwingUtilities.invokeAndWait(()->{LanControl.Controller c=new LanControl.Controller(true);c.port=server.getLocalPort();c.password.setText("izin-admin-kode");c.autoUpdate.setSelected(false);LanControl.Card card=c.cards.get(0);card.ip.setText("127.0.0.1");card.connect();holder[0]=c;});
            LanControl.Card card=holder[0].cards.get(0);for(int i=0;i<100&&!"BIASA".equals(card.privilege);i++)Thread.sleep(50);Thread.sleep(100);
            String[] status={""};SwingUtilities.invokeAndWait(()->status[0]=card.status.getText());
            check(card.limited()&&status[0].contains("Izin BIASA")&&card.status.getForeground().equals(Ui.WARN),"master card warns about non-admin client: "+status[0]);
            card.peer.rpc("ELEVATE");check(elevations[0]==1,"master can ask client to elevate");
            RemoteServices.privilege="ADMIN";SwingUtilities.invokeAndWait(()->{card.privilege=null;});holder[0].checkPrivilege(card,card.peer);for(int i=0;i<100&&card.privilege==null;i++)Thread.sleep(30);Thread.sleep(100);
            SwingUtilities.invokeAndWait(()->status[0]=card.status.getText());check(!card.limited()&&!status[0].contains("BIASA"),"admin client shows no warning");
            SwingUtilities.invokeAndWait(card::disconnect);server.close();loop.get(5,TimeUnit.SECONDS);
        }finally{RemoteServices.privilege=previous;}
        System.out.println("PASS: v1.6.2 client privilege: reported to master, BIASA warning on card, remote elevation request, admin clients untouched, elevation keeps sharing + upgrades startup.");
    }
}
