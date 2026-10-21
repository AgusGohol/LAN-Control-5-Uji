import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** v1.5.0: reconnect routing, busy/wrong-code handshake, touchpad scroll, special keys, clipboard sync, settings, file manager helpers. */
class UpdateTests {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void run()throws Exception{
        input();clipboard();handshake();services();helpers();settings();reconnect();
        System.out.println("PASS: v1.5.0 reconnect keeps windows working, BUSY/wrong-code handshake, touchpad scroll, special keys, local paste, slow-copy wait, saved settings, file manager helpers.");
    }
    static void input()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            List<String> sent=new ArrayList<>();BufferedImage frame=new BufferedImage(1600,900,BufferedImage.TYPE_INT_RGB);
            LanControl.View v=new LanControl.View(()->frame,sent::add);v.setSize(800,600);v.paint(new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB).getGraphics());
            for(int i=0;i<4;i++)v.dispatchEvent(new MouseWheelEvent(v,MouseEvent.MOUSE_WHEEL,0,0,400,300,400,300,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,0,0.25));
            check(sent.equals(List.of("WHEEL 1")),"touchpad fractions accumulate into one notch: "+sent);sent.clear();
            v.dispatchEvent(new MouseWheelEvent(v,MouseEvent.MOUSE_WHEEL,0,0,400,300,400,300,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,-2,-2.0));check(sent.equals(List.of("WHEEL -2")),"normal mouse wheel unchanged");sent.clear();
            v.dispatchEvent(new MouseEvent(v,MouseEvent.MOUSE_PRESSED,0,0,400,300,1,false,4));v.dispatchEvent(new MouseEvent(v,MouseEvent.MOUSE_RELEASED,0,0,400,300,1,false,4));
            check(sent.isEmpty(),"extra mouse buttons ignored without errors");
            LanControl.combo(sent::add,KeyEvent.VK_CONTROL,KeyEvent.VK_SHIFT,KeyEvent.VK_ESCAPE);
            check(sent.equals(List.of("KEYDOWN 17","KEYDOWN 16","KEYDOWN 27","KEYUP 27","KEYUP 16","KEYUP 17")),"special combo pressed and released in reverse order");
            check(LanControl.COMBOS.length==LanControl.COMBO_NAMES.length,"every special key has a label");
        });
        ControlQueue q=new ControlQueue();q.offer("RPC x LIST -");LanControl.combo(q::offer,KeyEvent.VK_WINDOWS,KeyEvent.VK_R);check(q.poll(1).equals("KEYDOWN 524"),"special keys precede file work");
    }
    static void clipboard()throws Exception{
        check(LanControl.Peer.pushNeeded("baru",null),"first paste uploads master text");check(!LanControl.Peer.pushNeeded("sama","sama"),"unchanged master text pastes client clipboard");
        check(!LanControl.Peer.pushNeeded(null,"x"),"master without text pastes client clipboard");check(LanControl.Peer.pushNeeded("lain","x"),"new master text uploaded");
        Clipboard c=new Clipboard("slow-app");TextClipboard.write(c,"lama");
        CompletableFuture.runAsync(()->{try{Thread.sleep(400);TextClipboard.write(c,"baru disalin");}catch(Exception e){throw new RuntimeException(e);}});
        long start=System.nanoTime();check(TextClipboard.awaitChange(c,"lama",1500).equals("baru disalin"),"slow copy captured");check((System.nanoTime()-start)/1_000_000<1200,"returns once clipboard changes");
        Transferable image=new Transferable(){public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.imageFlavor};}public boolean isDataFlavorSupported(DataFlavor f){return f.equals(DataFlavor.imageFlavor);}public Object getTransferData(DataFlavor f){return new BufferedImage(1,1,1);}};
        c.setContents(image,null);check(TextClipboard.peek(c)==null,"image clipboard has no text");
        try{TextClipboard.awaitChange(c,"lama",300);throw new AssertionError("non-text must fail");}catch(IOException expected){check(expected.getMessage().contains(TextClipboard.NOT_TEXT),"non-text marker for master");}
    }
    static void handshake()throws Exception{
        try(ServerSocket server=new ServerSocket(0,8,InetAddress.getLoopbackAddress())){
            CountDownLatch release=new CountDownLatch(1);List<String> sessions=new CopyOnWriteArrayList<>();
            Future<?> loop=LanControl.WORK.submit(()->{try{LanControl.serve(server,"benar-kode-123",()->true,(s,w)->{sessions.add("open");release.await(20,TimeUnit.SECONDS);});}catch(IOException ignored){}});
            int port=server.getLocalPort();
            List<LanControl.Wire> masters=new ArrayList<>();for(int i=0;i<LanControl.MAX_CONTROLLERS;i++)masters.add(LanControl.dial("127.0.0.1",port,"benar-kode-123",3000));
            try{LanControl.dial("127.0.0.1",port,"benar-kode-123",3000);throw new AssertionError("controller above the limit must be refused");}catch(LanControl.BusyException expected){check(LanControl.connectionReason(expected).contains("batas"),"busy reason readable");}
            try{LanControl.dial("127.0.0.1",port,"salah-kode-1234",3000);throw new AssertionError("wrong code must fail");}catch(LanControl.BadCodeException expected){check(LanControl.connectionReason(expected).contains("berbeda"),"wrong code reason");}
            release.countDown();for(LanControl.Wire m:masters)m.close();
            LanControl.Wire again=null;for(int i=0;i<40&&again==null;i++)try{again=LanControl.dial("127.0.0.1",port,"benar-kode-123",3000);}catch(LanControl.BusyException wait){Thread.sleep(50);}
            check(again!=null,"slot freed after session ends");again.close();
            // READY is sent just before the session callback runs, so give the last session a moment to register.
            for(int i=0;i<100&&sessions.size()<LanControl.MAX_CONTROLLERS+1;i++)Thread.sleep(20);check(sessions.size()==LanControl.MAX_CONTROLLERS+1,"limit + one accepted sessions");
            server.close();loop.get(5,TimeUnit.SECONDS);
        }
    }
    static void services()throws Exception{
        Path dir=Files.createTempDirectory("lan5-v15-");
        try(RemoteServices s=new RemoteServices()){Files.writeString(dir.resolve("b.txt"),"x");Files.createDirectory(dir.resolve("a"));
            String[] lines=LanControl.string(s.execute("LIST",List.of(dir.toString()))).split("\n");check(lines.length==2,"two entries");
            for(String line:lines){String[] p=line.split("\t");check(p.length==4&&Long.parseLong(p[3])>0,"listing carries modification time");}
            List<RemoteTools.Entry> rows=RemoteTools.parse(String.join("\n",lines));check(rows.stream().anyMatch(e->e.name().equals("a")&&e.kind().equals("Folder")),"folder parsed with short name");
            check(rows.stream().anyMatch(e->e.name().equals("b.txt")&&e.size()==1&&e.modified()>0),"file parsed with size/date");
            check(RemoteTools.parse(RemoteServices.b64("C:\\")+"\tD\t0\n").get(0).kind().equals("Drive"),"drive row");
            check(RemoteTools.parse(RemoteServices.b64("C:\\x.txt")+"\tF\t5\n").get(0).modified()==0,"older client listing (3 fields) still works");
        }finally{try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    static void helpers(){
        check(RemoteTools.size(1536).equals("1,5 KB"),"readable KB: "+RemoteTools.size(1536));check(RemoteTools.size(5L*1024*1024*1024).equals("5,0 GB"),"readable GB");check(RemoteTools.size(-1).isEmpty(),"folder size blank");
        Set<String> taken=new HashSet<>(List.of("laporan.pdf","laporan (2).pdf","catatan"));
        check(RemoteTools.unique("Laporan.pdf",taken).equals("Laporan (3).pdf"),"case-insensitive unique name");check(RemoteTools.unique("catatan",taken).equals("catatan (2)"),"no extension");check(RemoteTools.unique("baru.txt",taken).equals("baru.txt"),"free name kept");
        check(RemoteTools.child("C:\\Data","a.txt").equals("C:\\Data\\a.txt"),"windows join");check(RemoteTools.child("C:\\","a.txt").equals("C:\\a.txt"),"drive root join");check(RemoteTools.child("/tmp","a").equals("/tmp/a"),"posix join");
        check(RemoteTools.parent("C:\\Users\\Ani").equals("C:\\Users"),"parent folder");check(RemoteTools.parent("C:\\Users").equals("C:\\"),"parent to drive");check(RemoteTools.parent("C:\\").isEmpty(),"drive to drive list");
        check(RemoteTools.absolute("D:\\x")&&RemoteTools.absolute("\\\\server\\share")&&!RemoteTools.absolute("folder"),"absolute detection");
        JPanel p=new JPanel(new Ui.WrapLayout(FlowLayout.LEFT,8,5));for(int i=0;i<8;i++)p.add(new JButton("Tombol "+i));p.setSize(300,10);check(p.getPreferredSize().height>3*new JButton("x").getPreferredSize().height,"toolbar wraps on narrow window");
    }
    static void settings()throws Exception{
        Path file=Files.createTempDirectory("lan5-settings-").resolve("controller.properties");
        Properties saved=new Properties();saved.setProperty("card.1.ip","192.168.1.21");saved.setProperty("card.1.name","Kasir — Depan");saved.setProperty("card.3.ip","100.83.64.90");saved.setProperty("quality","HEMAT");saved.setProperty("reconnect","false");saved.setProperty("focus","false");
        Settings.save(file,saved);Properties loaded=Settings.load(file);check(loaded.equals(saved),"UTF-8 settings roundtrip");check(Settings.load(file.resolveSibling("missing")).isEmpty(),"missing settings = defaults");
        SwingUtilities.invokeAndWait(()->{LanControl.Controller c=new LanControl.Controller(true);c.apply(loaded);
            check(c.cards.get(0).ip.getText().equals("192.168.1.21")&&c.cards.get(0).title.getText().equals("Kasir — Depan"),"IP and name restored");check(c.cards.get(1).name.equals("Komputer 2"),"default name");
            check(c.quality.getSelectedIndex()==3&&!c.reconnect.isSelected()&&!c.focusLayout.isSelected(),"options restored");
            Properties again=c.snapshot();check(again.getProperty("card.3.ip").equals("100.83.64.90")&&again.getProperty("quality").equals("HEMAT")&&again.getProperty("code")==null,"snapshot contains no plain code");
        });
        Files.deleteIfExists(file);Files.deleteIfExists(file.getParent());
    }
    /** A window opened before a disconnect must control the new session after automatic reconnection. */
    static void reconnect()throws Exception{
        try(ServerSocket server=new ServerSocket(0,8,InetAddress.getLoopbackAddress())){
            BlockingQueue<String> received=new LinkedBlockingQueue<>();List<Socket> sockets=new CopyOnWriteArrayList<>();
            Future<?> loop=LanControl.WORK.submit(()->{try{LanControl.serve(server,"sambung-ulang-01",()->true,(s,w)->{sockets.add(s);while(!s.isClosed()){String m=LanControl.string(w.read());if(m.startsWith("KEY"))received.add(sockets.size()+":"+m);}});}catch(IOException ignored){}});
            LanControl.Controller[] holder=new LanControl.Controller[1];LanControl.Sender[] window=new LanControl.Sender[1];
            SwingUtilities.invokeAndWait(()->{LanControl.Controller c=new LanControl.Controller(true);c.port=server.getLocalPort();c.password.setText("sambung-ulang-01");LanControl.Card card=c.cards.get(0);card.ip.setText("127.0.0.1");
                window[0]=LanControl.sender(List.of(card));card.connect();holder[0]=c;});
            LanControl.Card card=holder[0].cards.get(0);
            for(int i=0;i<100&&card.peer==null;i++)Thread.sleep(50);check(card.peer!=null,"connected");
            LanControl.Peer first=card.peer;window[0].send("KEYDOWN 65");check("1:KEYDOWN 65".equals(received.poll(5,TimeUnit.SECONDS)),"input reaches first session");
            sockets.get(0).close();
            for(int i=0;i<200&&(card.peer==null||card.peer==first);i++)Thread.sleep(50);
            check(card.peer!=null&&card.peer!=first&&card.peer.alive,"automatic reconnection after drop");
            window[0].send("KEYDOWN 66");check("2:KEYDOWN 66".equals(received.poll(5,TimeUnit.SECONDS)),"window opened earlier controls the new session");
            SwingUtilities.invokeAndWait(card::disconnect);Thread.sleep(300);check(card.peer==null&&card.retry==null&&!card.wanted,"Putus stops reconnection");
            server.close();loop.get(5,TimeUnit.SECONDS);
        }
    }
}
