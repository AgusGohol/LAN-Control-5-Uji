import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** v1.11.0: blue theme matching the icon, with a live dark/light switch that needs no restart. */
class ThemeTests {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static List<Color> remapSet(){return List.of(Ui.BG,Ui.SURFACE,Ui.FIELD,Ui.TEXT,Ui.MUTED,Ui.ACCENT,Ui.WARN,Ui.DANGER,Ui.CANVAS);}
    static void run()throws Exception{
        boolean start=Ui.dark;
        // Within each palette the remappable colors must be distinct, and no value may be shared across palettes,
        // or a live switch (and the component-tree remap) would turn one role into another. This exact collision
        // (dark SURFACE == light TEXT) once made card titles vanish in light mode.
        Ui.palette(true);Set<Integer> darkSet=rgbSet(remapSet());check(darkSet.size()==remapSet().size(),"dark palette colors distinct");
        Ui.palette(false);Set<Integer> lightSet=rgbSet(remapSet());check(lightSet.size()==remapSet().size(),"light palette colors distinct");
        Set<Integer> both=new HashSet<>(darkSet);both.retainAll(lightSet);check(both.isEmpty(),"no color shared between dark and light palettes");
        // Accent follows the app icon (blue): blue channel clearly dominant in both themes.
        Ui.palette(true);check(Ui.ACCENT.getBlue()>Ui.ACCENT.getRed()+60&&Ui.ACCENT.getBlue()>Ui.ACCENT.getGreen()+40,"dark accent is blue like the icon");
        Ui.palette(false);check(Ui.ACCENT.getBlue()>Ui.ACCENT.getRed()+60,"light accent is blue like the icon");
        // Dark is actually dark, light is actually light.
        Ui.palette(true);check(lum(Ui.BG)<60&&lum(Ui.TEXT)>180,"dark: dark background, light text");
        Ui.palette(false);check(lum(Ui.BG)>200&&lum(Ui.TEXT)<80,"light: light background, dark text");
        // Live switch round-trip: a label created in dark returns to its dark color after dark->light->dark.
        SwingUtilities.invokeAndWait(()->{
            Ui.palette(true);Ui.install();
            JFrame f=new JFrame();f.setUndecorated(true);JLabel body=Ui.label("x",14,false,Ui.TEXT),accent=Ui.label("y",14,true,Ui.ACCENT);
            JPanel p=new JPanel();p.add(body);p.add(accent);f.add(p);f.pack();f.setVisible(true);
            int darkBody=Ui.TEXT.getRGB(),darkAccent=Ui.ACCENT.getRGB();
            check(body.getForeground().getRGB()==darkBody,"label starts dark");
            Ui.setMode(false);
            check(body.getForeground().getRGB()==Ui.TEXT.getRGB()&&body.getForeground().getRGB()!=darkBody,"body text follows switch to light");
            check(accent.getForeground().getRGB()==Ui.ACCENT.getRGB(),"accent text follows switch to light");
            Ui.setMode(true);
            check(body.getForeground().getRGB()==darkBody,"body text returns to the exact dark color");
            check(accent.getForeground().getRGB()==darkAccent,"accent text returns to the exact dark color");
            f.dispose();
        });
        // Preference round-trips through the shared settings file.
        Ui.saveDark(false);check(!Ui.savedDark(),"light preference saved");
        Ui.saveDark(true);check(Ui.savedDark(),"dark preference saved");
        Ui.palette(start);Ui.install();
        System.out.println("PASS: v1.11.0 theme: blue accent from the icon, distinct collision-free dark/light palettes, live switch round-trip without restart, saved preference.");
    }
    static Set<Integer> rgbSet(List<Color> cs){Set<Integer> s=new HashSet<>();for(Color c:cs)s.add(c.getRGB());return s;}
    static int lum(Color c){return (c.getRed()*2126+c.getGreen()*7152+c.getBlue()*722)/10000;}
}
