import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** v1.6.1: folders and files dropped on a client screen arrive on the client's Desktop with the same structure, never overwriting. */
class TransferTests {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void run()throws Exception{
        Path root=Files.createTempDirectory("lan5-drop-"),local=Files.createDirectories(root.resolve("utama")),desktop=Files.createDirectories(root.resolve("Desktop klien"));
        Path previous=RemoteServices.desktopOverride;RemoteServices.desktopOverride=desktop;
        try(ServerSocket server=new ServerSocket(0,8,InetAddress.getLoopbackAddress())){
            // Local material: a file, a folder tree with Unicode, an empty sub-folder and a 600 KB file (multiple chunks).
            Path laporan=Files.writeString(local.resolve("laporan.txt"),"isi baru");Path folder=Files.createDirectories(local.resolve("Proyek Ä"));
            Files.writeString(folder.resolve("catatan.txt"),"satu");Files.createDirectories(folder.resolve("kosong"));Path deep=Files.createDirectories(folder.resolve("data/2026"));byte[] big=new byte[600_000];new Random(3).nextBytes(big);Files.write(deep.resolve("besar.bin"),big);
            Files.writeString(desktop.resolve("laporan.txt"),"sudah ada di klien");
            Future<?> loop=LanControl.WORK.submit(()->{try{LanControl.serve(server,"kirim-desktop-01",()->true,(s,w)->{try(RemoteServices services=new RemoteServices()){while(!s.isClosed()){String m=LanControl.string(w.read());if(m.startsWith("RPC "))LanControl.handleRpc(w,services,m);}}});}catch(IOException ignored){}});
            LanControl.Controller[] holder=new LanControl.Controller[1];
            SwingUtilities.invokeAndWait(()->{LanControl.Controller c=new LanControl.Controller(true);c.port=server.getLocalPort();c.password.setText("kirim-desktop-01");c.autoUpdate.setSelected(false);LanControl.Card card=c.cards.get(0);card.ip.setText("127.0.0.1");card.connect();holder[0]=c;});
            LanControl.Card card=holder[0].cards.get(0);for(int i=0;i<100&&card.peer==null;i++)Thread.sleep(50);check(card.peer!=null,"connected");
            check(Transfer.desktop(card.peer).equals(desktop.toString()),"client reports its Desktop folder");
            BufferedImage frame=new BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB);card.image=frame;
            check(card.view.drop!=null,"card screen accepts dropped files");
            SwingUtilities.invokeAndWait(()->{card.view.setSize(640,360);card.view.dragOver=true;card.view.paint(new BufferedImage(640,360,BufferedImage.TYPE_INT_RGB).getGraphics());card.view.dragOver=false;});
            // Same path as a real drop on the card screen.
            SwingUtilities.invokeAndWait(()->card.view.drop.accept(List.of(laporan,folder)));
            for(int i=0;i<200&&holder[0].dropTransfers.get()>0;i++)Thread.sleep(50);Thread.sleep(200);
            check(holder[0].dropTransfers.get()==0,"transfer finished");
            check(Files.readString(desktop.resolve("laporan.txt")).equals("sudah ada di klien"),"existing Desktop file not overwritten");
            check(Files.readString(desktop.resolve("laporan (2).txt")).equals("isi baru"),"dropped file renamed on conflict");
            Path copy=desktop.resolve("Proyek Ä");check(Files.readString(copy.resolve("catatan.txt")).equals("satu"),"folder file copied");
            check(Files.isDirectory(copy.resolve("kosong")),"empty sub-folder kept");check(Arrays.equals(Files.readAllBytes(copy.resolve("data/2026/besar.bin")),big),"nested multi-chunk file exact");
            try(var parts=Files.walk(desktop)){check(parts.noneMatch(p->p.getFileName().toString().endsWith(".part")),"no temporary files left");}
            // Dropping the same folder again creates "Proyek Ä (2)" instead of mixing contents.
            Transfer.Result again=Transfer.upload(card.peer,desktop.toString(),List.of(folder),Transfer.names(card.peer,desktop.toString()),()->false,(a,b,c)->{});
            check(Files.isDirectory(desktop.resolve("Proyek Ä (2)/data/2026"))&&again.files==2&&again.folders==4&&again.renamed.size()==1,"second folder drop renamed: "+again.files+"/"+again.folders);
            boolean stopped=false;try{Transfer.upload(card.peer,desktop.toString(),List.of(folder),Transfer.names(card.peer,desktop.toString()),()->true,(a,b,c)->{});}catch(IOException e){stopped=e.getMessage().contains("dibatalkan");}check(stopped,"cancel stops transfer");
            SwingUtilities.invokeAndWait(()->{for(java.awt.Window w:java.awt.Window.getWindows())if(w instanceof JDialog d)d.dispose();card.disconnect();});server.close();loop.get(5,TimeUnit.SECONDS);
        }finally{RemoteServices.desktopOverride=previous;try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("PASS: v1.6.1 drop onto client screen: Desktop target, files + nested/empty folders, multi-chunk, no overwrite (name (2)), repeat folder drop, cancel, drop overlay.");
    }
}
