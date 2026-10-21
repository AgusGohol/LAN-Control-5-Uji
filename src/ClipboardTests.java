import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.*;
import javax.swing.*;

class ClipboardTests {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void run()throws Exception{
        Clipboard clipboard=new Clipboard("test");String text="Teks Indonesia\r\nBaris kedua\t日本語 😀";
        TextClipboard.write(clipboard,text);check(TextClipboard.read(clipboard).equals(text),"Unicode multiline clipboard roundtrip");
        TextClipboard.write(clipboard,"");check(TextClipboard.read(clipboard).isEmpty(),"empty clipboard supported");
        try{TextClipboard.validate("x".repeat(TextClipboard.LIMIT+1));throw new AssertionError("limit missing");}catch(java.io.IOException expected){}
        ControlQueue queue=new ControlQueue();queue.offer("RPC file LIST Lw==");queue.offer("KEYDOWN 17");queue.offer("RPC copy COPY_TEXT");queue.offer("KEYUP 17");queue.offer("RPC paste PASTE_TEXT "+RemoteServices.b64(text));
        check(queue.poll(1).equals("KEYDOWN 17"),"control starts first");check(queue.poll(1).equals("RPC copy COPY_TEXT"),"copy stays in key order");check(queue.poll(1).equals("KEYUP 17"),"control release ordered");check(queue.poll(1).contains("PASTE_TEXT"),"paste prioritized over file work");check(queue.poll(1).contains("LIST"),"file request retained");
        SwingUtilities.invokeAndWait(()->{
            java.util.List<String> sent=new ArrayList<>();java.util.List<Integer> shortcuts=new ArrayList<>();BufferedImage frame=new BufferedImage(1600,900,BufferedImage.TYPE_INT_RGB);
            LanControl.View view=new LanControl.View(()->frame,sent::add);view.shortcut=shortcuts::add;view.setSize(800,600);
            KeyListener listener=view.getKeyListeners()[0];
            listener.keyPressed(new KeyEvent(view,KeyEvent.KEY_PRESSED,1,0,KeyEvent.VK_CONTROL,KeyEvent.CHAR_UNDEFINED));
            for(int key:new int[]{KeyEvent.VK_C,KeyEvent.VK_V}){listener.keyPressed(new KeyEvent(view,KeyEvent.KEY_PRESSED,2,InputEvent.CTRL_DOWN_MASK,key,(char)key));listener.keyPressed(new KeyEvent(view,KeyEvent.KEY_PRESSED,3,InputEvent.CTRL_DOWN_MASK,key,(char)key));listener.keyReleased(new KeyEvent(view,KeyEvent.KEY_RELEASED,4,InputEvent.CTRL_DOWN_MASK,key,(char)key));}
            listener.keyReleased(new KeyEvent(view,KeyEvent.KEY_RELEASED,5,0,KeyEvent.VK_CONTROL,KeyEvent.CHAR_UNDEFINED));
            check(shortcuts.equals(java.util.List.of(KeyEvent.VK_C,KeyEvent.VK_V)),"shortcuts intercepted once, autorepeat suppressed");check(sent.equals(java.util.List.of("KEYDOWN 17","KEYUP 17")),"no duplicate remote C/V keypresses");
            view.stretch=true;view.paint(new BufferedImage(800,600,1).getGraphics());check(view.area.equals(new Rectangle(0,0,800,600)),"stretch fills viewport");sent.clear();view.dispatchEvent(new MouseEvent(view,MouseEvent.MOUSE_PRESSED,0,0,400,300,1,false,MouseEvent.BUTTON1));check(sent.contains("MOVE 0.5 0.5"),"stretched clicks map correctly");
            BufferedImage solid=new BufferedImage(1600,900,BufferedImage.TYPE_INT_RGB);Graphics2D fill=solid.createGraphics();fill.setColor(Color.ORANGE);fill.fillRect(0,0,1600,900);fill.dispose();
            LanControl.View cached=new LanControl.View(()->solid,x->{});cached.setSize(800,600);cached.displayWidth=800;cached.displayHeight=600;cached.prepare(solid);cached.stretch=true;
            BufferedImage canvas=new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB);cached.paint(canvas.getGraphics());check(canvas.getRGB(790,590)==Color.ORANGE.getRGB(),"cached sizing change fills bottom-right without black border");
            JScrollPane pane=new JScrollPane(cached);pane.setSize(1200,700);pane.doLayout();pane.getViewport().doLayout();check(cached.getSize().equals(pane.getViewport().getExtentSize()),"fit viewport grows to full window");cached.oneToOne=true;check(!cached.getScrollableTracksViewportWidth()&&!cached.getScrollableTracksViewportHeight(),"native mode retains scrollbars");
            sent.clear();view.escape=()->true;listener.keyPressed(new KeyEvent(view,KeyEvent.KEY_PRESSED,1,0,27,(char)27));listener.keyReleased(new KeyEvent(view,KeyEvent.KEY_RELEASED,2,0,27,(char)27));check(sent.isEmpty(),"fullscreen Escape stays local");
            view.escape=()->false;listener.keyPressed(new KeyEvent(view,KeyEvent.KEY_PRESSED,1,0,27,(char)27));listener.keyReleased(new KeyEvent(view,KeyEvent.KEY_RELEASED,2,0,27,(char)27));check(sent.equals(java.util.List.of("KEYDOWN 27","KEYUP 27")),"normal Escape reaches client");
        });
        System.out.println("PASS: Unicode clipboard, size bounds, ordered copy/paste, shortcut interception, stretched input mapping and Escape routing.");
    }
}
