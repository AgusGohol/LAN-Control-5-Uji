import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** v1.10.0: the Pintasan combinations can also be typed on the keyboard while the client screen has focus. */
class KeyboardShortcutTests {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static final int CTRL=KeyEvent.VK_CONTROL,ALT=KeyEvent.VK_ALT,SHIFT=KeyEvent.VK_SHIFT,WIN=KeyEvent.VK_WINDOWS,TAB=KeyEvent.VK_TAB;
    static final int CA=InputEvent.CTRL_DOWN_MASK|InputEvent.ALT_DOWN_MASK;
    static KeyListener listener;static LanControl.View view;
    static void down(int key,int mods){listener.keyPressed(new KeyEvent(view,KeyEvent.KEY_PRESSED,0,mods,key,KeyEvent.CHAR_UNDEFINED));}
    static void up(int key,int mods){listener.keyReleased(new KeyEvent(view,KeyEvent.KEY_RELEASED,0,mods,key,KeyEvent.CHAR_UNDEFINED));}
    static List<String> keys(int... codes){List<String> l=new ArrayList<>();for(int c:codes)l.add((c>0?"KEYDOWN ":"KEYUP ")+Math.abs(c));return l;}
    static void run()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            List<String> sent=new ArrayList<>();BufferedImage frame=new BufferedImage(800,450,BufferedImage.TYPE_INT_RGB);
            view=new LanControl.View(()->frame,sent::add);view.shortcut=k->{};view.setSize(800,450);listener=view.getKeyListeners()[0];
            // Ctrl+Alt+E → Win+E: Ctrl/Alt are let go on the client first (Alt before Ctrl), the physical release later sends nothing twice.
            down(CTRL,InputEvent.CTRL_DOWN_MASK);down(ALT,CA);sent.clear();
            down(KeyEvent.VK_E,CA);down(KeyEvent.VK_E,CA);up(KeyEvent.VK_E,CA);
            check(sent.equals(keys(-ALT,-CTRL,WIN,KeyEvent.VK_E,-KeyEvent.VK_E,-WIN)),"Ctrl+Alt+E sends exactly Win+E once: "+sent);
            sent.clear();down(KeyEvent.VK_D,CA);up(KeyEvent.VK_D,CA);check(sent.equals(keys(WIN,KeyEvent.VK_D,-KeyEvent.VK_D,-WIN)),"second shortcut while still holding Ctrl+Alt: "+sent);
            sent.clear();down(KeyEvent.VK_Z,CA);check(sent.equals(keys(CTRL,ALT,KeyEvent.VK_Z)),"ordinary key afterwards gets Ctrl+Alt back on the client: "+sent);
            sent.clear();up(KeyEvent.VK_Z,CA);up(ALT,InputEvent.CTRL_DOWN_MASK);up(CTRL,0);check(sent.equals(keys(-KeyEvent.VK_Z,-ALT,-CTRL)),"modifiers released normally after being pressed again");
            int[][] expected={{WIN},{WIN,KeyEvent.VK_D},{WIN,KeyEvent.VK_E},{WIN,KeyEvent.VK_R},{WIN,KeyEvent.VK_X},{WIN,SHIFT,KeyEvent.VK_S},null,{ALT,KeyEvent.VK_F4},{CTRL,SHIFT,KeyEvent.VK_ESCAPE},{KeyEvent.VK_PRINTSCREEN}};
            for(int i=0;i<expected.length;i++){if(expected[i]==null)continue;int key=LanControl.COMBO_HOTKEYS[i];
                down(CTRL,InputEvent.CTRL_DOWN_MASK);down(ALT,CA);sent.clear();down(key,CA);up(key,CA);up(ALT,InputEvent.CTRL_DOWN_MASK);up(CTRL,0);
                List<String> want=new ArrayList<>(keys(-ALT,-CTRL));for(int k:expected[i])want.add("KEYDOWN "+k);for(int j=expected[i].length-1;j>=0;j--)want.add("KEYUP "+expected[i][j]);
                check(sent.equals(want),LanControl.COMBO_KEYS[i]+" sends "+LanControl.COMBO_NAMES[i]+": "+sent);}
            down(CTRL,InputEvent.CTRL_DOWN_MASK);down(ALT,CA);sent.clear();down(KeyEvent.VK_ADD,CA);check(sent.equals(keys(-ALT,-CTRL,KeyEvent.VK_PRINTSCREEN,-KeyEvent.VK_PRINTSCREEN)),"numpad + is Print Screen (as in Remote Desktop)");up(KeyEvent.VK_ADD,CA);
            sent.clear();down(KeyEvent.VK_K,CA);check(sent.equals(List.of("RESET")),"Ctrl+Alt+K releases every key on the client");up(KeyEvent.VK_K,CA);up(ALT,InputEvent.CTRL_DOWN_MASK);up(CTRL,0);
            // Alt+Page Up / Page Down → Alt+Tab / Alt+Shift+Tab with Alt kept down on the client.
            sent.clear();down(ALT,InputEvent.ALT_DOWN_MASK);down(KeyEvent.VK_PAGE_UP,InputEvent.ALT_DOWN_MASK);up(KeyEvent.VK_PAGE_UP,InputEvent.ALT_DOWN_MASK);down(KeyEvent.VK_PAGE_UP,InputEvent.ALT_DOWN_MASK);up(KeyEvent.VK_PAGE_UP,InputEvent.ALT_DOWN_MASK);
            down(KeyEvent.VK_PAGE_DOWN,InputEvent.ALT_DOWN_MASK);up(KeyEvent.VK_PAGE_DOWN,InputEvent.ALT_DOWN_MASK);up(ALT,0);
            check(sent.equals(keys(ALT,TAB,-TAB,TAB,-TAB,SHIFT,TAB,-TAB,-SHIFT,-ALT)),"Alt+Page Up cycles windows, Alt+Page Down goes back: "+sent);
            sent.clear();down(KeyEvent.VK_PAGE_UP,0);up(KeyEvent.VK_PAGE_UP,0);check(sent.equals(keys(KeyEvent.VK_PAGE_UP,-KeyEvent.VK_PAGE_UP)),"Page Up alone still reaches the client");
            sent.clear();down(CTRL,InputEvent.CTRL_DOWN_MASK);down(ALT,CA);down(SHIFT,CA|InputEvent.SHIFT_DOWN_MASK);down(KeyEvent.VK_E,CA|InputEvent.SHIFT_DOWN_MASK);
            check(sent.equals(keys(CTRL,ALT,SHIFT,KeyEvent.VK_E)),"Ctrl+Alt+Shift+E is not a shortcut and passes through");
            for(int k:new int[]{KeyEvent.VK_E,SHIFT,ALT,CTRL})up(k,0);
            view.escape=()->true;sent.clear();down(KeyEvent.VK_ESCAPE,0);up(KeyEvent.VK_ESCAPE,0);check(sent.isEmpty(),"Escape alone still leaves full screen");
            check(LanControl.COMBO_KEYS.length==LanControl.COMBOS.length&&LanControl.COMBO_HOTKEYS.length==LanControl.COMBOS.length,"every menu entry has a keyboard equivalent");
            // Focus indicator + Perbesar legend.
            check(!view.enlarged,"card preview shows no badge by default");
            check(LanControl.KEY_LEGEND.length>=5,"Perbesar window lists the keyboard shortcuts");
            LanControl.View big=new LanControl.View(()->frame,x->{}){public boolean hasFocus(){return true;}};big.enlarged=true;big.setSize(640,360);big.displayWidth=640;big.displayHeight=360;big.prepare(frame);
            BufferedImage canvas=new BufferedImage(640,360,BufferedImage.TYPE_INT_RGB);big.paint(canvas.getGraphics());check(true,"focus badge paints without error");
        });
        System.out.println("PASS: v1.10.0 keyboard shortcuts: Ctrl+Alt+W/D/E/R/X/S/F4/Esc/P and numpad +, Alt+Page Up/Down task switching, Ctrl+Alt+K release, no double presses, modifiers restored, Escape unchanged.");
    }
}
