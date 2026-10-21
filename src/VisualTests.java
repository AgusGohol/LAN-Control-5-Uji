import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import javax.swing.*;

class VisualTests {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static BufferedImage sample(int w,int h){
        BufferedImage im=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=im.createGraphics();g.setColor(new Color(241,245,249));g.fillRect(0,0,w,h);
        g.setColor(new Color(27,44,66));g.fillRect(0,0,w,68);g.setFont(new Font("SansSerif",Font.BOLD,22));g.setColor(Color.WHITE);g.drawString("Workspace / Daftar pekerjaan",34,43);
        g.setColor(new Color(39,62,90));g.fillRect(0,68,210,h-68);g.setFont(new Font("SansSerif",Font.PLAIN,16));g.setColor(Color.WHITE);for(int i=0;i<5;i++)g.drawString(new String[]{"Dashboard","Dokumen","Perangkat","Riwayat","Pengaturan"}[i],28,118+i*49);
        g.setColor(new Color(29,46,67));g.setFont(new Font("SansSerif",Font.BOLD,32));g.drawString("Ringkasan pekerjaan",260,138);g.setFont(new Font("SansSerif",Font.PLAIN,17));g.setColor(new Color(92,111,135));g.drawString("Contoh tampilan untuk pemeriksaan kualitas teks",260,175);
        g.setColor(new Color(53,163,145));g.fillRoundRect(w-240,112,180,46,12,12);g.setColor(Color.WHITE);g.drawString("Tambah dokumen",w-220,142);
        for(int row=0;row<13;row++){int y=220+row*51;g.setColor(row%2==0?Color.WHITE:new Color(232,239,246));g.fillRect(258,y,w-310,50);g.setColor(new Color(65,83,105));g.setFont(new Font("SansSerif",row==0?Font.BOLD:Font.PLAIN,16));g.drawString(row==0?"NAMA DOKUMEN":"Laporan perangkat "+String.format("%02d",row),280,y+31);g.drawString(row==0?"STATUS":"Tersimpan",w/2+100,y+31);g.drawString(row==0?"PEMBARUAN":"Hari ini",w-225,y+31);}
        g.dispose();return im;
    }
    static void test()throws Exception {
        StreamQuality.State state=new StreamQuality.State();state.config=new StreamQuality.Config("NATIVE",900,600);
        BufferedImage source=sample(1600,900);byte[] data=StreamQuality.encode(source,state);BufferedImage decoded=ImageIO.read(new ByteArrayInputStream(data));
        check(decoded.getWidth()==1600&&decoded.getHeight()==900,"native resolution retained");
        int[] a=source.getRGB(0,0,1600,900,null,0,1600),b=decoded.getRGB(0,0,1600,900,null,0,1600);check(java.util.Arrays.equals(a,b),"PNG mode preserves every text pixel");
        state.config=new StreamQuality.Config("AUTO",600,338);Dimension small=state.size(3840,2160);state.config=new StreamQuality.Config("AUTO",1800,1000);Dimension large=state.size(3840,2160);check(large.width>small.width,"auto resolution grows with viewport");
        check(Math.abs(large.width/(double)large.height-16/9.0)<.01,"preserve aspect ratio");
        for(int i=0;i<14;i++)state.sample(900);check(state.factor<1,"congestion reduces auto resolution");for(int i=0;i<100;i++)state.sample(70);check(state.factor==1,"resolution recovers after congestion");
        state.begin();state.ready.acquire();state.ack();state.ack();check(state.ready.availablePermits()==StreamQuality.State.WINDOW,"duplicate acknowledgements cannot queue extra frames");
        state.config=new StreamQuality.Config("HD",100,100);check(state.size(3840,2160).equals(new Dimension(1920,1080)),"HD resolution");state.config=new StreamQuality.Config("HEMAT",100,100);check(state.size(3840,2160).equals(new Dimension(960,540)),"economy resolution");
        check(ImageIO.read(new ByteArrayInputStream(StreamQuality.encode(source,state))).getWidth()==960,"JPEG roundtrip");
        check(StreamQuality.fit(800,600,3840,2160).equals(new Dimension(800,600)),"never upscale source");
        streamLoopback();
        SwingUtilities.invokeAndWait(()->{
            java.util.List<String> sent=new java.util.ArrayList<>();LanControl.View v=new LanControl.View(()->source,sent::add);v.setSize(800,600);BufferedImage canvas=new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB);v.paint(canvas.getGraphics());
            v.dispatchEvent(new MouseEvent(v,MouseEvent.MOUSE_PRESSED,0,0,400,300,1,false,MouseEvent.BUTTON1));v.dispatchEvent(new MouseEvent(v,MouseEvent.MOUSE_RELEASED,0,0,400,300,1,false,MouseEvent.BUTTON1));
            check(sent.contains("MOVE 0.5 0.5"),"letterbox input maps to source center");sent.clear();v.dispatchEvent(new MouseEvent(v,MouseEvent.MOUSE_PRESSED,0,0,400,10,1,false,MouseEvent.BUTTON1));check(sent.isEmpty(),"letterbox margins never send input");
        });
        System.out.println("PASS: native lossless pixels, viewport adaptation, aspect ratios, congestion/recovery, single in-flight frame, HD/economy encode and input mapping.");
    }
    static void streamLoopback()throws Exception{
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(java.net.ServerSocket listener=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){
            var server=executor.submit(()->{try(var socket=listener.accept();var wire=new LanControl.Wire(socket,"quality-test-code",true)){
                StreamQuality.State state=new StreamQuality.State();BufferedImage source=sample(1920,1080);
                check(LanControl.string(wire.read()).equals("LAN5 V5 HELLO"),"stream handshake");wire.send(LanControl.command("LAN5 V5 READY"));
                for(int i=0;i<3;i++){state.configure(LanControl.string(wire.read()).split(" "));state.ready.acquire();state.begin();wire.send(StreamQuality.encode(source,state));check(LanControl.string(wire.read()).equals("VIDEO_ACK"),"frame acknowledgement");state.ack();}
            }catch(Exception e){throw new RuntimeException(e);}});
            try(var socket=new java.net.Socket(java.net.InetAddress.getLoopbackAddress(),listener.getLocalPort());var wire=new LanControl.Wire(socket,"quality-test-code",false)){
                wire.send(LanControl.command("LAN5 V5 HELLO"));check(LanControl.string(wire.read()).equals("LAN5 V5 READY"),"stream version");
                int previous=0;for(String request:new String[]{"VIDEO AUTO 600 338","VIDEO HD 1700 1000","VIDEO NATIVE 1700 1000"}){wire.send(LanControl.command(request));BufferedImage image=ImageIO.read(new ByteArrayInputStream(wire.read()));check(image!=null&&image.getWidth()>=previous,"adaptive image over encrypted wire");previous=image.getWidth();wire.send(LanControl.command("VIDEO_ACK"));}
            }server.get(20,java.util.concurrent.TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
    }
    static void layout(Component c){if(c instanceof Container p){p.doLayout();for(Component child:p.getComponents())layout(child);}}
    static void preview()throws Exception {
        LanControl.Controller controller=new LanControl.Controller(true);controller.cards.get(0).image=sample(1920,1080);controller.cards.get(0).ip.setText("100.83.64.90");controller.cards.get(0).status.setText("Online • 100.83.64.90");controller.cards.get(0).status.setForeground(Ui.ACCENT);controller.cards.get(0).picture.setText("1920 × 1080  /  Otomatis");controller.count.setText("1 / 4 klien online");
        for(Dimension size:new Dimension[]{new Dimension(1480,1060),new Dimension(900,850)}){
            controller.dashboard.setSize(size);for(int i=0;i<5;i++)layout(controller.dashboard);
            for(LanControl.Card c:controller.cards){check(c.getWidth()>400,"card has sufficient width");if(c.view.isVisible())check(c.view.getHeight()>100,"preview retains usable height");check(c.ip.getWidth()>80,"IP field is visible");}
            check(controller.cards.get(0).view.getHeight()>=300,"single online client has readable preview height");
            BufferedImage image=new BufferedImage(size.width,size.height,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);controller.dashboard.printAll(g);g.dispose();ImageIO.write(image,"png",new File("preview-"+size.width+".png"));
        }
        controller.dashboard.setSize(1920,1080);controller.cards.get(1).image=sample(1024,768);controller.grid.refreshFocus();for(int i=0;i<5;i++)layout(controller.dashboard);
        check(controller.grid.focused()==controller.cards.get(0),"second connection retains first focused client");check(controller.cards.get(0).getWidth()>controller.cards.get(1).getWidth(),"focused client remains larger with two online");check(controller.cards.get(1).view.isVisible(),"online sidebar thumbnail visible");
        BufferedImage two=new BufferedImage(1920,1080,BufferedImage.TYPE_INT_RGB);controller.dashboard.printAll(two.getGraphics());ImageIO.write(two,"png",new File("preview-two-clients.png"));
        controller.focus(controller.cards.get(1));for(int i=0;i<5;i++)layout(controller.dashboard);check(controller.grid.focused()==controller.cards.get(1),"focus switch selects second client");check(controller.cards.get(1).getWidth()>controller.cards.get(0).getWidth(),"second client becomes large");
        controller.cards.get(1).image=null;controller.grid.refreshFocus();for(int i=0;i<5;i++)layout(controller.dashboard);check(controller.grid.focused()==controller.cards.get(0),"disconnect falls back to remaining client");
        controller.grid.focusEnabled=false;for(int i=0;i<5;i++)layout(controller.dashboard);check(controller.cards.get(0).getWidth()==controller.cards.get(1).getWidth(),"grid mode remains available");
        System.out.println("PASS: responsive dashboard, two-client focus retention, explicit focus switch, disconnect fallback and grid mode.");
    }
}
