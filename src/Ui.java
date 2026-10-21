import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;

class Ui {
    // Colors are swappable at runtime (dark <-> light) so the theme can change without reopening the app.
    static Color BG,SURFACE,FIELD,LINE,TEXT,MUTED,ACCENT,ON_ACCENT,BTN,SELECT,WARN,DANGER,CANVAS;
    static boolean dark=true;
    static{palette(true);}
    /** The icon is a blue gradient on white screens, so the accent is that blue in both themes. */
    static void palette(boolean d){
        dark=d;
        if(d){BG=new Color(13,19,30);SURFACE=new Color(24,34,50);FIELD=new Color(15,23,36);LINE=new Color(40,54,74);
            TEXT=new Color(231,239,248);MUTED=new Color(168,183,204);ACCENT=new Color(42,130,246);ON_ACCENT=new Color(255,255,255);
            BTN=new Color(38,52,72);SELECT=new Color(26,64,120);WARN=new Color(242,190,92);DANGER=new Color(250,128,110);CANVAS=new Color(9,15,24);}
        else{BG=new Color(240,244,250);SURFACE=new Color(255,255,255);FIELD=new Color(236,241,248);LINE=new Color(210,220,233);
            TEXT=new Color(22,31,46);MUTED=new Color(92,108,130);ACCENT=new Color(23,98,214);ON_ACCENT=new Color(255,255,255);
            BTN=new Color(226,233,243);SELECT=new Color(205,224,252);WARN=new Color(176,120,12);DANGER=new Color(200,55,45);CANVAS=new Color(222,228,236);}
    }
    static final String FONT=java.util.Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()).contains("Segoe UI")?"Segoe UI":"SansSerif";
    static void install(){
        Font base=new Font(FONT,Font.PLAIN,15);
        for(String key:new String[]{"Label","Button","CheckBox","TextField","PasswordField","ComboBox","Table","TableHeader","TextArea","TabbedPane","OptionPane"})UIManager.put(key+".font",base);
        for(String key:new String[]{"Panel","OptionPane","Viewport","CheckBox","TabbedPane"})UIManager.put(key+".background",BG);
        for(String key:new String[]{"Label","Button","CheckBox","TextField","PasswordField","ComboBox","Table","TableHeader","TextArea","TabbedPane"})UIManager.put(key+".foreground",TEXT);
        for(String key:new String[]{"TextField","PasswordField","ComboBox","TextArea","Table"})UIManager.put(key+".background",FIELD);
        UIManager.put("TextField.caretForeground",TEXT);UIManager.put("PasswordField.caretForeground",TEXT);UIManager.put("TextArea.caretForeground",TEXT);
        // Plain JButtons (JOptionPane OK/Cancel, file chooser): themed fill so the Button.foreground stays readable.
        javax.swing.plaf.ColorUIResource buttonFill=new javax.swing.plaf.ColorUIResource(BTN);
        UIManager.put("Button.background",buttonFill);UIManager.put("Button.gradient",java.util.Arrays.asList(.3f,0f,buttonFill,buttonFill,buttonFill));
        UIManager.put("Button.select",new javax.swing.plaf.ColorUIResource(SELECT));UIManager.put("Button.focus",ACCENT);UIManager.put("Button.disabledText",MUTED);
        UIManager.put("Button.border",new javax.swing.plaf.BorderUIResource(new CompoundBorder(new LineBorder(LINE,1,true),new EmptyBorder(6,18,6,18))));
        UIManager.put("OptionPane.messageForeground",TEXT);UIManager.put("Table.gridColor",LINE);UIManager.put("TableHeader.background",SURFACE);
        UIManager.put("Table.selectionBackground",SELECT);UIManager.put("Table.selectionForeground",TEXT);
        UIManager.put("ComboBox.selectionBackground",SELECT);UIManager.put("ComboBox.selectionForeground",TEXT);
        UIManager.put("ComboBox.border",new LineBorder(LINE,1,true));
        UIManager.put("CheckBox.icon",new Icon(){public int getIconWidth(){return 17;}public int getIconHeight(){return 17;}public void paintIcon(Component c,Graphics g,int x,int y){Graphics2D a=(Graphics2D)g.create();a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);boolean selected=c instanceof AbstractButton b&&b.isSelected();a.setColor(selected?ACCENT:FIELD);a.fillRoundRect(x+1,y+1,14,14,5,5);a.setColor(selected?ACCENT:LINE);a.drawRoundRect(x+1,y+1,14,14,5,5);if(selected){a.setColor(ON_ACCENT);a.setStroke(new BasicStroke(2));a.drawLine(x+4,y+8,x+7,y+11);a.drawLine(x+7,y+11,x+12,y+5);}a.dispose();}});
        UIManager.put("TextField.border",new CompoundBorder(new LineBorder(LINE,1,true),new EmptyBorder(8,10,8,10)));
        UIManager.put("PasswordField.border",UIManager.get("TextField.border"));
        UIManager.put("ScrollPane.border",new EmptyBorder(0,0,0,0));UIManager.put("ScrollBar.width",10);
        for(String key:new String[]{"PopupMenu","MenuItem"}){UIManager.put(key+".background",SURFACE);UIManager.put(key+".foreground",TEXT);UIManager.put(key+".font",new Font(FONT,Font.PLAIN,14));}
        UIManager.put("PopupMenu.border",new LineBorder(LINE,1));UIManager.put("MenuItem.selectionBackground",SELECT);UIManager.put("MenuItem.selectionForeground",TEXT);UIManager.put("MenuItem.disabledForeground",MUTED);UIManager.put("Separator.foreground",LINE);
        UIManager.put("ProgressBar.foreground",ACCENT);UIManager.put("ProgressBar.background",FIELD);UIManager.put("ProgressBar.selectionForeground",BG);UIManager.put("ProgressBar.selectionBackground",TEXT);UIManager.put("ProgressBar.font",new Font(FONT,Font.BOLD,13));
        UIManager.put("ToolTip.font",new Font(FONT,Font.PLAIN,13));UIManager.put("ToolTip.background",SURFACE);UIManager.put("ToolTip.foreground",TEXT);UIManager.put("ToolTip.border",new LineBorder(LINE,1));
        UIManager.put("TabbedPane.selected",SURFACE);UIManager.put("TabbedPane.selectedForeground",ACCENT);UIManager.put("TabbedPane.contentAreaColor",BG);UIManager.put("TabbedPane.unselectedBackground",BG);UIManager.put("TabbedPane.focus",SURFACE);
        UIManager.put("Table.font",new Font(FONT,Font.PLAIN,14));UIManager.put("TableHeader.font",new Font(FONT,Font.BOLD,13));
    }
    /** Listeners (e.g. toggle buttons) notified after a live theme switch so they can refresh their own text. */
    static final java.util.List<Runnable> themeListeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Switch theme live: remap the colors already set on open windows, reinstall UI defaults, repaint. No restart. */
    static void setMode(boolean d){
        if(d!=dark){
            Color[] names={BG,SURFACE,FIELD,TEXT,MUTED,ACCENT,WARN,DANGER,CANVAS};
            int[] olds=new int[names.length];for(int i=0;i<names.length;i++)olds[i]=names[i].getRGB();
            palette(d);install();
            Color[] now={BG,SURFACE,FIELD,TEXT,MUTED,ACCENT,WARN,DANGER,CANVAS};
            java.util.Map<Integer,Color> map=new java.util.HashMap<>();for(int i=0;i<olds.length;i++)map.put(olds[i],now[i]);
            for(Window w:Window.getWindows())if(w.isDisplayable()){remap(w,map);SwingUtilities.updateComponentTreeUI(w);w.revalidate();w.repaint();}
            for(Runnable r:themeListeners)try{r.run();}catch(RuntimeException ignored){}
        }
        saveDark(d);
    }
    static void remap(Component c,java.util.Map<Integer,Color> map){
        Color f=c.getForeground();if(f!=null){Color n=map.get(f.getRGB());if(n!=null)c.setForeground(n);}
        Color b=c.getBackground();if(b!=null){Color n=map.get(b.getRGB());if(n!=null)c.setBackground(n);}
        if(c instanceof Container box)for(Component k:box.getComponents())remap(k,map);
    }
    static boolean savedDark(){return !"light".equals(Settings.load(Settings.file("ui.properties")).getProperty("theme"));}
    static void saveDark(boolean d){try{java.util.Properties p=new java.util.Properties();p.setProperty("theme",d?"dark":"light");Settings.save(Settings.file("ui.properties"),p);}catch(java.io.IOException ignored){}}
    /** A toggle button that flips the theme live and keeps its own label in sync. */
    static JButton themeToggle(){
        JButton[] holder=new JButton[1];
        Runnable sync=()->holder[0].setText(dark?"☀  Terang":"☾  Gelap");
        holder[0]=button(dark?"☀  Terang":"☾  Gelap",()->setMode(!dark));
        holder[0].setToolTipText("Ganti tema terang/gelap. Berubah langsung tanpa menutup aplikasi.");
        themeListeners.add(sync);return holder[0];
    }
    /** FlowLayout that wraps onto extra rows instead of hiding buttons on narrow windows. */
    static class WrapLayout extends FlowLayout {
        WrapLayout(int align,int hgap,int vgap){super(align,hgap,vgap);}
        public Dimension preferredLayoutSize(Container target){return size(target,true);}
        public Dimension minimumLayoutSize(Container target){Dimension d=size(target,false);d.width-=getHgap()+1;return d;}
        Dimension size(Container target,boolean preferred){synchronized(target.getTreeLock()){
            Container sized=target;while(sized.getSize().width==0&&sized.getParent()!=null)sized=sized.getParent();
            int width=sized.getSize().width;if(width==0)width=Integer.MAX_VALUE;Insets in=target.getInsets();int max=width-(in.left+in.right+getHgap()*2);
            Dimension result=new Dimension(0,0);int rowWidth=0,rowHeight=0;
            for(Component c:target.getComponents())if(c.isVisible()){Dimension d=preferred?c.getPreferredSize():c.getMinimumSize();
                if(rowWidth>0&&rowWidth+getHgap()+d.width>max){row(result,rowWidth,rowHeight);rowWidth=0;rowHeight=0;}
                if(rowWidth>0)rowWidth+=getHgap();rowWidth+=d.width;rowHeight=Math.max(rowHeight,d.height);}
            row(result,rowWidth,rowHeight);result.width+=in.left+in.right+getHgap()*2;result.height+=in.top+in.bottom+getVgap()*2;
            return result;
        }}
        void row(Dimension d,int w,int h){d.width=Math.max(d.width,w);if(d.height>0)d.height+=getVgap();d.height+=h;}
    }
    static JPanel transparent(LayoutManager layout){JPanel p=new JPanel(layout);p.setOpaque(false);return p;}
    static JLabel label(String text,int size,boolean bold,Color color){JLabel l=new JLabel(text);l.setFont(new Font(FONT,bold?Font.BOLD:Font.PLAIN,Math.max(13,size)));l.setForeground(color);return l;}
    static class RoundPanel extends JPanel {
        RoundPanel(LayoutManager layout){super(layout);setOpaque(false);setBorder(new EmptyBorder(16,16,16,16));}
        protected void paintComponent(Graphics g){Graphics2D a=(Graphics2D)g.create();a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);a.setColor(SURFACE);a.fillRoundRect(0,0,getWidth()-1,getHeight()-1,22,22);a.setColor(LINE);a.drawRoundRect(0,0,getWidth()-1,getHeight()-1,22,22);a.dispose();super.paintComponent(g);}
    }
    static JButton button(String text,Runnable action){return new SoftButton(text,action);}
    static class SoftButton extends JButton {
        boolean primary=false;
        SoftButton(String text,Runnable action){super(text);setFont(new Font(FONT,Font.BOLD,14));setBorder(new EmptyBorder(9,14,9,14));setOpaque(false);setContentAreaFilled(false);setFocusPainted(false);setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));setForeground(TEXT);addActionListener(e->action.run());addMouseListener(new MouseAdapter(){public void mouseEntered(MouseEvent e){repaint();}public void mouseExited(MouseEvent e){repaint();}});}
        protected void paintComponent(Graphics g){Graphics2D a=(Graphics2D)g.create();a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);Color fill=primary?ACCENT:BTN;if(getModel().isRollover())fill=fill.brighter();if(getModel().isPressed())fill=fill.darker();a.setColor(fill);a.fillRoundRect(0,0,getWidth(),getHeight(),12,12);if(hasFocus()){a.setColor(ACCENT);a.drawRoundRect(1,1,getWidth()-3,getHeight()-3,12,12);}a.dispose();setForeground(primary?ON_ACCENT:TEXT);super.paintComponent(g);}
        public void updateUI(){super.updateUI();setBorder(new EmptyBorder(9,14,9,14));setContentAreaFilled(false);setOpaque(false);setFocusPainted(false);setForeground(TEXT);setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));}
    }
    static JButton primary(String text,Runnable r){SoftButton b=new SoftButton(text,r);b.primary=true;return b;}
    static class Grid extends JPanel implements Scrollable {
        boolean focusEnabled=true;Component layoutFocus,preferredFocus;
        void refreshFocus(){Component current=focused();if(current!=layoutFocus){layoutFocus=current;revalidate();repaint();}}
        boolean connected(Component child){return child instanceof LanControl.Card card&&(card.peer!=null||card.image!=null||card.retry!=null);}
        Component focused(){if(!focusEnabled)return null;if(preferredFocus!=null&&connected(preferredFocus))return preferredFocus;if(layoutFocus!=null&&connected(layoutFocus))return layoutFocus;for(Component child:getComponents())if(connected(child))return child;return null;}

        static final int GAP=16,SIDE_SLOT=250,ROW=370,FOCUS_NARROW=560;
        /** Any number of computers: 1 column on narrow windows, 2 normally, 3 on wide windows with more than 4 computers. */
        int columns(){int width=getParent()==null?getWidth():getParent().getWidth(),n=getComponentCount();if(width<1150)return 1;return n<=4||width<1700?2:3;}
        int available(){return getParent()==null?Math.max(getHeight(),740):getParent().getHeight();}
        /** Height of each computer in the right column of the focus layout (3 fit on screen, more scroll). */
        int sideSlot(int others){int shown=Math.max(1,Math.min(others,3));return Math.max(SIDE_SLOT,(available()-GAP*(shown-1))/shown);}
        Grid(){setOpaque(false);setLayout(new LayoutManager(){public void addLayoutComponent(String s,Component c){}public void removeLayoutComponent(Component c){}public Dimension minimumLayoutSize(Container c){return new Dimension(480,500);}public Dimension preferredLayoutSize(Container c){return Grid.this.getPreferredSize();}public void layoutContainer(Container c){
            Component focus=focused();boolean split=focus!=null&&getWidth()>=1400;int gap=GAP,others=getComponentCount()-1;
            for(Component child:getComponents())if(child instanceof LanControl.Card card){boolean show=!split||child==focus||connected(child);if(card.view.isVisible()!=show)card.view.setVisible(show);}
            if(split){int left=(getWidth()-gap)*68/100,right=getWidth()-gap-left,n=0,h=sideSlot(others);focus.setBounds(0,0,left,Math.min(getHeight(),available()));for(Component child:getComponents())if(child!=focus){child.setBounds(left+gap,n*(h+gap),right,h);n++;}return;}
            if(focus!=null){focus.setBounds(0,0,getWidth(),FOCUS_NARROW);int y=FOCUS_NARROW+gap;for(Component child:getComponents())if(child!=focus){child.setBounds(0,y,getWidth(),ROW);y+=ROW+gap;}return;}
            int cols=columns(),rows=Math.max(1,(getComponentCount()+cols-1)/cols),w=(getWidth()-gap*(cols-1))/cols,h=Math.max(ROW,(getHeight()-gap*(rows-1))/rows);for(int i=0;i<getComponentCount();i++)getComponent(i).setBounds((i%cols)*(w+gap),(i/cols)*(h+gap),w,h);
        }});}
        public Dimension getPreferredSize(){int width=getParent()==null?1200:getParent().getWidth(),available=available(),others=Math.max(0,getComponentCount()-1);
            if(focused()!=null){if(width>=1400){int h=sideSlot(others);return new Dimension(width,Math.max(available,others*h+Math.max(0,others-1)*GAP));}return new Dimension(width,FOCUS_NARROW+others*(ROW+GAP));}
            int cols=columns(),rows=Math.max(1,(getComponentCount()+cols-1)/cols);return new Dimension(width,Math.max(available,rows*ROW+(rows-1)*GAP));}
        public Dimension getPreferredScrollableViewportSize(){return new Dimension(1200,780);}public int getScrollableUnitIncrement(Rectangle r,int o,int d){return 30;}public int getScrollableBlockIncrement(Rectangle r,int o,int d){return 250;}public boolean getScrollableTracksViewportWidth(){return true;}public boolean getScrollableTracksViewportHeight(){return getParent()!=null&&getPreferredSize().height<=getParent().getHeight();}
    }
}
