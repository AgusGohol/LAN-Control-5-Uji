import javax.swing.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class LanControl {
    static final String VERSION="1.12.11",HELLO="LAN5 V5 HELLO",READY="LAN5 V5 READY",BUSY="LAN5 V5 BUSY";
    static final int PORT=45971, MAX=8*1024*1024;
    static volatile boolean justUpdated;
    static final Color BG=Ui.BG, PANEL=Ui.SURFACE, TEXT=Ui.TEXT;
    static final ExecutorService WORK=Executors.newCachedThreadPool(LanControl::daemon);
    static Thread daemon(Runnable r){Thread t=new Thread(r);t.setDaemon(true);return t;}
    static void later(Runnable r){SwingUtilities.invokeLater(r);}
    static String message(Throwable e){
        Throwable t=e;while((t instanceof CompletionException||t instanceof ExecutionException)&&t.getCause()!=null)t=t.getCause();
        return t.getMessage()==null||t.getMessage().isBlank()?t.getClass().getSimpleName():t.getMessage();
    }
    static void error(Component c,Exception e){later(()->JOptionPane.showMessageDialog(c,message(e),"Koneksi / aplikasi",JOptionPane.ERROR_MESSAGE));}
    static void theme(){Ui.palette(Ui.savedDark());Ui.install();}
    static JButton button(String s,Runnable r){return Ui.button(s,r);}
    static JFrame window(String title){JFrame f=new JFrame(title);f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);f.setMinimumSize(new Dimension(700,450));return f;}
    static byte[] command(String s){return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    static String string(byte[] b){return new String(b,java.nio.charset.StandardCharsets.UTF_8);}
    static class BadCodeException extends IOException {BadCodeException(){super("Kode koneksi utama dan klien berbeda.");}}
    static class BusyException extends IOException {BusyException(){super("Klien menolak sambungan baru: sudah dibuka "+MAX_CONTROLLERS+" komputer utama (batas), atau klien masih versi lama yang hanya menerima satu komputer utama.");}}
    /** v1.9.0: several masters may view and control one client at the same time. */
    static final int MAX_CONTROLLERS=4;
    static String connectionReason(Exception e){
        if(e instanceof javax.crypto.AEADBadTagException||e instanceof BadCodeException)return "Kode koneksi utama dan klien berbeda.";
        if(e instanceof BusyException)return e.getMessage();
        if(e instanceof java.io.EOFException)return "Klien menutup koneksi. Pastikan versi sama, kode sama, dan klien sedang berbagi.";
        if(e instanceof java.net.ConnectException)return "Klien belum berbagi atau port ditolak. Periksa IP dan izin firewall Java.";
        if(e instanceof java.net.SocketTimeoutException)return "Koneksi melewati batas waktu. Periksa jaringan, IP, dan firewall.";
        if(e instanceof java.net.UnknownHostException)return "Alamat IP/nama komputer tidak dikenal.";
        if(e instanceof java.net.NoRouteToHostException)return "Komputer tidak terjangkau. Periksa apakah klien menyala dan di jaringan yang sama.";
        return message(e);
    }

    // Per-session salt creates a new key; fixed direction prefixes and counters prevent nonce reuse.
    static class Wire implements Closeable {
        final Socket socket;final DataInputStream in;final DataOutputStream out;final SecretKey key;
        final int tx,rx;long sent=0,received=0;
        Wire(Socket s,String password,boolean server)throws Exception{
            socket=s;s.setTcpNoDelay(true);s.setSoTimeout(15000);
            in=new DataInputStream(s.getInputStream());out=new DataOutputStream(s.getOutputStream());
            byte[] salt=new byte[32];
            if(server){new SecureRandom().nextBytes(salt);out.write(salt);out.flush();}else in.readFully(salt);
            PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,120000,256);
            key=new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),"AES");spec.clearPassword();
            tx=server?1:2;rx=server?2:1;
        }
        static byte[] nonce(int direction,long counter){return ByteBuffer.allocate(12).putInt(direction).putLong(counter).array();}
        synchronized void send(byte[] data)throws Exception{
            if(data.length>MAX-16)throw new IOException("Gambar terlalu besar");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce(tx,sent++)));
            byte[] b=cipher.doFinal(data);out.writeInt(b.length);out.write(b);out.flush();
        }
        /** A zero-length packet tells the other side that the connection code did not match. */
        synchronized void rejectCode(){try{out.writeInt(0);out.flush();}catch(IOException ignored){}}
        byte[] read()throws Exception{
            int n=in.readInt();if(n==0)throw new BadCodeException();if(n<16||n>MAX)throw new IOException("Paket tidak valid");
            byte[] b=new byte[n];in.readFully(b);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce(rx,received++)));return cipher.doFinal(b);
        }
        public void close(){try{socket.close();}catch(IOException ignored){}}
    }
    /** Controller side: connect, authenticate and confirm that the client accepted this session. */
    static Wire dial(String host,int port,String password,int timeout)throws Exception{
        Socket s=new Socket();
        try{s.connect(new InetSocketAddress(host,port),timeout);Wire w=new Wire(s,password,false);w.send(command(HELLO));String reply=string(w.read());
            if(reply.equals(BUSY))throw new BusyException();
            if(!reply.equals(READY))throw new IOException("Gunakan versi terbaru yang sama pada utama dan klien.");
            return w;
        }catch(Exception e){try{s.close();}catch(IOException ignored){}throw e;}
    }
    interface Session {void run(Socket socket,Wire wire)throws Exception;}
    /** Client side: up to MAX_CONTROLLERS masters at once; one more gets BUSY instead of waiting silently. Wrong codes get an explicit rejection. */
    static void serve(ServerSocket server,String password,java.util.function.BooleanSupplier active,Session session)throws IOException{
        java.util.concurrent.atomic.AtomicInteger open=new java.util.concurrent.atomic.AtomicInteger();
        while(active.getAsBoolean()&&!server.isClosed()){
            Socket s=server.accept();
            WORK.submit(()->{boolean mine=false;
                try(Wire w=new Wire(s,password,true)){
                    String hello;try{hello=string(w.read());}catch(AEADBadTagException wrong){w.rejectCode();return;}
                    if(!hello.equals(HELLO))return;
                    if(open.incrementAndGet()>MAX_CONTROLLERS){open.decrementAndGet();w.send(command(BUSY));return;}
                    mine=true;w.send(command(READY));session.run(s,w);
                }catch(Exception ignored){}
                finally{if(mine)open.decrementAndGet();try{s.close();}catch(IOException ignored){}}
            });
        }
    }
    /** Names of the masters other than {@code me}; a stale session of the same master (same name) is not counted. */
    static <K> List<String> otherControllers(Map<K,String> all,K me){String mine=all.get(me);List<String> others=new ArrayList<>();
        for(Map.Entry<K,String> e:all.entrySet())if(e.getKey()!=me&&!e.getValue().equals(mine)&&!others.contains(e.getValue()))others.add(e.getValue());Collections.sort(others);return others;}
    /** Keys and mouse buttons held by one master. Physical keys are released only when no master holds them any more. */
    static class Hands {final Set<Integer> keys=new HashSet<>(),buttons=new HashSet<>();}
    static class Input {
        final FrameCache frameCache=new FrameCache();final Robot robot;Robot captureRobot;volatile Rectangle screen;final Object captureLock=new Object();
        /** Default hands for single-master callers; every master session gets its own Hands. */
        final Hands shared=new Hands();
        /** How many masters hold each key / mouse button right now. */
        final Map<Integer,Integer> keyHolders=new HashMap<>(),buttonHolders=new HashMap<>();
        Input()throws Exception{robot=new Robot();captureRobot=new Robot();robot.setAutoDelay(0);robot.setAutoWaitForIdle(false);screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();}
        /** Tests only: no Robot; override press/lift to observe what would reach the physical keyboard and mouse. */
        Input(Void test){robot=null;captureRobot=null;}
        void press(int code,boolean key){if(key)robot.keyPress(code);else robot.mousePress(code);}
        void lift(int code,boolean key){if(key)robot.keyRelease(code);else robot.mouseRelease(code);}
        synchronized void release(){release(shared);}
        /** Releases what this master holds; a key another master still holds stays pressed. */
        synchronized void release(Hands h){for(int k:h.keys)let(keyHolders,k,true);for(int b:h.buttons)let(buttonHolders,b,false);h.keys.clear();h.buttons.clear();}
        void let(Map<Integer,Integer> holders,int code,boolean key){Integer n=holders.get(code);if(n==null)return;if(n>1){holders.put(code,n-1);return;}holders.remove(code);
            try{lift(code,key);}catch(RuntimeException ignored){}}
        synchronized void apply(String msg){apply(msg,shared);}
        synchronized void apply(String msg,Hands h){
            try{
                String[] p=msg.split(" ");
                switch(p[0]){
                    case "MOVE":double x=Double.parseDouble(p[1]),y=Double.parseDouble(p[2]);if(!Double.isFinite(x)||!Double.isFinite(y))return;
                        Rectangle bounds=currentBounds();robot.mouseMove(bounds.x+(int)(Math.max(0,Math.min(1,x))*(bounds.width-1)),bounds.y+(int)(Math.max(0,Math.min(1,y))*(bounds.height-1)));break;
                    case "DOWN":int b=Integer.parseInt(p[1]);if(b==1024||b==2048||b==4096){if(h.buttons.add(b))buttonHolders.merge(b,1,Integer::sum);press(b,false);}break;
                    case "UP":b=Integer.parseInt(p[1]);if(h.buttons.remove(b))let(buttonHolders,b,false);break;
                    // Auto-repeat sends KEYDOWN again while held: always pressed, counted once per master.
                    case "KEYDOWN":int k=Integer.parseInt(p[1]);if(k>0&&k<65536){press(k,true);if(h.keys.add(k))keyHolders.merge(k,1,Integer::sum);}break;
                    case "KEYUP":k=Integer.parseInt(p[1]);if(h.keys.remove(k))let(keyHolders,k,true);break;
                    case "WHEEL":robot.mouseWheel(Math.max(-10,Math.min(10,Integer.parseInt(p[1]))));break;
                    case "RESET":release(h);break;
                }
            }catch(RuntimeException ignored){}
        }
        synchronized void chord(int key){boolean held=keyHolders.containsKey(KeyEvent.VK_CONTROL);if(!held)press(KeyEvent.VK_CONTROL,true);try{press(key,true);lift(key,true);}finally{if(!held)lift(KeyEvent.VK_CONTROL,true);}}
        Rectangle currentBounds(){return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();}
        byte[] frame(StreamQuality.State state)throws Exception{return frame(frameCache,state);}
        /** Each master has its own FrameCache: tile updates are relative to the last frame sent to that master. */
        byte[] frame(FrameCache cache,StreamQuality.State state)throws Exception{
            java.awt.image.BufferedImage shot;
            synchronized(captureLock){Rectangle bounds=currentBounds();if(!bounds.equals(screen)){captureRobot=new Robot();screen=new Rectangle(bounds);}shot=captureRobot.createScreenCapture(bounds);}
            return cache.encode(shot,state);
        }
    }

    static class Agent {
        final JFrame f=window("LAN Control 5 (UJI) — Klien");final JPasswordField password=new JPasswordField(24);
        final JLabel status=new JLabel("Belum berbagi layar");JButton elevateButton;volatile boolean active=false;volatile ServerSocket listener;
        final JCheckBox rememberCode=new JCheckBox("Ingat kode"),autoStart=new JCheckBox("Buka & berbagi otomatis saat PC dinyalakan"),adminStartup=new JCheckBox("dengan izin Administrator (kontrol penuh)");
        /** Every connected master (v1.9.0: several at once) with the name it reported, or its IP until it does. */
        final Map<Wire,String> controllers=new ConcurrentHashMap<>();
        final boolean automatic;
        volatile Boolean admin;
        Agent(boolean automatic){this(automatic,false,false);}
        Agent(boolean automatic,boolean resume){this(automatic,resume,false);}
        Agent(boolean automatic,boolean resume,boolean upgradeStartup){this.automatic=automatic;
            JPanel root=new JPanel(new BorderLayout(0,22));root.setBorder(BorderFactory.createEmptyBorder(26,28,24,28));
            JPanel heading=Ui.transparent(new GridLayout(0,1,0,7));heading.add(Ui.label("LAN CONTROL 5 (UJI)",12,true,Ui.ACCENT));heading.add(Ui.label("Bagikan komputer ini",27,true,Ui.TEXT));heading.add(Ui.label("Klien v"+VERSION+"  /  Kontrol layar dan file melalui LAN atau Tailscale",13,false,Ui.MUTED));root.add(heading,BorderLayout.NORTH);
            Ui.RoundPanel body=new Ui.RoundPanel(new BorderLayout(0,18));JPanel details=Ui.transparent(new GridLayout(0,1,0,9));
            JLabel permission=Ui.label("Memeriksa izin Windows...",13,false,Ui.MUTED);details.add(permission);
            WORK.submit(()->{try{boolean isAdmin=RemoteServices.Startup.isAdmin();admin=isAdmin;RemoteServices.privilege=isAdmin?"ADMIN":"BIASA";
                    later(()->{permission.setText(isAdmin?"Izin: ADMINISTRATOR  /  Klik dan ketik ke semua aplikasi":"Izin: BIASA  /  Klik ke aplikasi Administrator (Task Manager, installer) TIDAK berfungsi");permission.setForeground(isAdmin?Ui.ACCENT:Ui.WARN);elevateButton.setVisible(!isAdmin);});}
                catch(Exception e){later(()->permission.setText("Status izin Windows belum tersedia"));}});
            details.add(Ui.label("Alamat komputer ini: "+addresses(),13,true,Ui.TEXT));details.add(Ui.label("Kode koneksi  /  minimal 12 karakter, sama dengan komputer utama",12,false,Ui.MUTED));
            password.setPreferredSize(new Dimension(300,40));JPanel codeRow=Ui.transparent(new Ui.WrapLayout(FlowLayout.LEFT,10,0));codeRow.add(password);rememberCode.setOpaque(false);rememberCode.setToolTipText("Simpan kode (terenkripsi Windows DPAPI) agar terisi otomatis saat klien dibuka lagi.");rememberCode.addActionListener(e->toggleRemember());codeRow.add(rememberCode);details.add(codeRow);body.add(details,BorderLayout.NORTH);
            JPanel actions=Ui.transparent(new GridLayout(0,1,0,9));actions.add(Ui.primary("Mulai berbagi layar & izinkan kontrol",this::start));actions.add(button("Hentikan berbagi / putuskan kontrol",this::stop));elevateButton=Ui.primary("Mulai ulang sebagai Administrator (disarankan)",this::elevateSelf);elevateButton.setVisible(false);actions.add(elevateButton);
            autoStart.setOpaque(false);autoStart.setToolTipText("Saat akun Windows ini login, klien terbuka sendiri dan langsung berbagi memakai kode tersimpan. Tidak perlu ada yang menyentuh PC ini.");autoStart.addActionListener(e->applyStartup());
            adminStartup.setOpaque(false);adminStartup.setToolTipText("Klien terbuka otomatis sebagai Administrator agar komputer utama punya kontrol penuh. Perlu persetujuan UAC satu kali saat mengaktifkan.");adminStartup.addActionListener(e->{if(autoStart.isSelected())applyStartup();});
            JPanel autoRow=Ui.transparent(new Ui.WrapLayout(FlowLayout.LEFT,18,0));autoRow.add(autoStart);autoRow.add(adminStartup);actions.add(autoRow);
            actions.add(Ui.label("Atur sekali di PC ini. Untuk mode Administrator, buka klien lewat Jalankan-Klien-Admin.bat lalu centang (UAC muncul sekali).",11,false,Ui.MUTED));
            body.add(actions);
            JPanel bottom=Ui.transparent(new GridLayout(0,1,0,9));status.setForeground(Ui.ACCENT);bottom.add(status);bottom.add(Ui.label("Resolusi mengikuti jendela kontrol. Kualitas dapat dipilih di komputer utama.",12,false,Ui.MUTED));body.add(bottom,BorderLayout.SOUTH);
            root.add(body);JPanel foot=Ui.transparent(new BorderLayout(10,0));foot.add(Ui.label("Biarkan klien terbuka  /  Port TCP "+PORT+"  /  Tombol hentikan selalu tersedia",12,false,Ui.MUTED));JPanel themeRow=Ui.transparent(new FlowLayout(FlowLayout.RIGHT,0,0));themeRow.add(Ui.themeToggle());foot.add(themeRow,BorderLayout.EAST);root.add(foot,BorderLayout.SOUTH);
            Icons.apply(f,Icons.KLIEN);f.add(root);f.setSize(900,720);f.setLocationRelativeTo(null);f.setVisible(true);
            WORK.submit(()->Icons.ensure(Icons.KLIEN,()->{try{return RemoteServices.Startup.isAdmin()?"--request-admin":"client";}catch(Exception e){return "client";}}));
            if(resume&&!automatic)WORK.submit(()->{try{Properties saved=Updater.takeResume();String code=saved==null?null:Updater.code(saved);
                    if(code!=null){later(()->{password.setText(code);start();status.setText((justUpdated?"Diperbarui ke v"+VERSION:"Berjalan kembali")+"  /  Menunggu kontrol utama...");});
                        // After elevation from a normal client with login startup: move startup to the elevated Task Scheduler entry (no UAC at next login).
                        if(upgradeStartup){try{RemoteServices.Startup.enableAdmin(code);later(()->status.setText("Startup otomatis kini dengan izin Administrator  /  Menunggu kontrol utama..."));}catch(Exception e){error(f,new Exception("Startup administrator belum aktif: "+message(e)));}}}}
                catch(Exception e){error(f,new Exception("Isi kode lagi lalu klik Mulai berbagi: "+message(e)));}});
            if(automatic)WORK.submit(()->{try{
                    // Single instance: when Administrator mode is set up, the elevated task's instance must be the only one.
                    boolean elevated=RemoteServices.Startup.isAdmin();admin=elevated;boolean adminTask=false;try{adminTask=RemoteServices.Startup.taskActive();}catch(Exception ignored){}
                    if(adminTask){
                        if(elevated)RemoteServices.Startup.removeLoginLaunchers();   // clean any leftover non-admin launcher so it can't double-open
                        else try{Thread.sleep(1600);}catch(InterruptedException ignored){}   // let the elevated instance grab the port first, then stand down
                    }
                    String code=RemoteServices.Startup.load();later(()->{password.setText(code);start();});
                }catch(Exception e){later(()->error(f,new Exception("Startup otomatis gagal: "+message(e))));}});
            refreshStartupState();
        }
        /** Checkbox "Ingat kode": save/forget the code only (no auto-start). Forced on while auto-start is active. */
        void toggleRemember(){boolean want=rememberCode.isSelected();if(!want&&autoStart.isSelected()){rememberCode.setSelected(true);error(f,new Exception("Matikan \"Buka & berbagi otomatis\" dulu sebelum melupakan kode."));return;}
            String code=new String(password.getPassword());WORK.submit(()->{try{if(want){RemoteServices.Startup.saveCode(code);later(()->status.setText("Kode disimpan (terenkripsi). Akan terisi otomatis saat klien dibuka lagi."));}
                else{RemoteServices.Startup.forgetCode();later(()->status.setText("Kode tersimpan dihapus dari PC ini."));}}catch(Exception e){String m=message(e);later(()->{rememberCode.setSelected(!want);error(f,new Exception(m));});}});}
        /** Apply the current auto-start checkboxes (login shortcut, or elevated task for Administrator), reverting the UI on failure. */
        void applyStartup(){boolean on=autoStart.isSelected(),admin=adminStartup.isSelected();String code=new String(password.getPassword());
            WORK.submit(()->{try{if(on){if(admin)RemoteServices.Startup.enableAdmin(code);else RemoteServices.Startup.enable(code);
                    later(()->{rememberCode.setSelected(true);rememberCode.setEnabled(false);status.setText(admin?"Aktif: klien terbuka & berbagi otomatis sebagai Administrator saat PC dinyalakan.":"Aktif: klien terbuka & berbagi otomatis saat PC dinyalakan.");start();});}
                else{RemoteServices.Startup.disableStartup();later(()->{rememberCode.setEnabled(true);status.setText("Buka otomatis dimatikan."+(rememberCode.isSelected()?" Kode tetap disimpan.":""));});}}
                catch(Exception e){String m=message(e);later(()->{refreshStartupState();error(f,new Exception(m));});}});}
        /** Read the real saved state and reflect it in the checkboxes; prefill the saved code on a manual open. */
        void refreshStartupState(){WORK.submit(()->{boolean code=RemoteServices.Startup.hasCode(),sc=RemoteServices.Startup.hasShortcut(),task=false;try{task=RemoteServices.Startup.taskActive();}catch(Exception ignored){}
            boolean[] flags=startupFlags(code,sc,task);String saved=null;if(code&&!automatic){try{saved=RemoteServices.Startup.load();}catch(Exception ignored){}}String prefill=saved;
            later(()->{rememberCode.setSelected(flags[0]);rememberCode.setEnabled(flags[1]);autoStart.setSelected(flags[2]);adminStartup.setSelected(flags[3]);if(prefill!=null&&password.getPassword().length==0)password.setText(prefill);});});}
        /** State mapping for the client checkboxes: {remember, rememberEnabled, autoStart, admin}. Auto-start implies the code is kept, so "Ingat kode" is forced on and locked. */
        static boolean[] startupFlags(boolean hasCode,boolean hasShortcut,boolean taskActive){boolean on=hasShortcut||taskActive;return new boolean[]{hasCode||on,!on,on,taskActive};}
        /** IPv4 addresses, skipping automatic 169.254.x.x addresses that cannot be used for control. */
        static String addresses(){List<String>a=new ArrayList<>();try{for(NetworkInterface nic:Collections.list(NetworkInterface.getNetworkInterfaces()))if(nic.isUp()&&!nic.isLoopback())for(InetAddress ip:Collections.list(nic.getInetAddresses()))if(ip instanceof Inet4Address&&!ip.isLinkLocalAddress())a.add(ip.getHostAddress());}catch(Exception ignored){}return a.isEmpty()?"(tidak ada jaringan aktif)":String.join(" / ",a);}
        void stop(){active=false;for(Wire w:controllers.keySet())w.close();try{if(listener!=null)listener.close();}catch(Exception ignored){}status.setText("Berbagi dihentikan");password.setEnabled(true);}
        void start(){if(active)return;String pass=new String(password.getPassword());if(pass.length()<12){error(f,new Exception("Gunakan kode minimal 12 karakter."));return;}
            active=true;password.setEnabled(false);status.setText("Menyiapkan berbagi...");
            WORK.submit(()->{
                // Auto-start retries briefly so the elevated instance can win the port if a stale instance is still releasing it;
                // if it still can't bind, another client already runs here, so it stands down quietly (no error dialog).
                ServerSocket bound=null;int tries=automatic?6:1;
                for(int i=0;i<tries;i++){try{bound=new ServerSocket(PORT);break;}
                    catch(BindException e){if(i+1<tries)try{Thread.sleep(600);}catch(InterruptedException ignored){}}
                    catch(Exception e){active=false;later(()->{password.setEnabled(true);status.setText("Berbagi dihentikan");error(f,e);});return;}}
                if(bound==null){
                    active=false;
                    // A redundant NON-admin auto-start stands down quietly so only one client runs. The elevated instance never
                    // exits itself: it stays visible and just reports the situation, so the app is never invisible.
                    if(automatic&&!Boolean.TRUE.equals(admin)){later(()->System.exit(0));return;}
                    later(()->{password.setEnabled(true);status.setText("Klien lain sudah berjalan di komputer ini — jendela ini boleh ditutup.");
                        if(!automatic)error(f,new Exception("Port "+PORT+" sudah dipakai. Kemungkinan klien LAN Control lain sudah berjalan di komputer ini (misalnya dari startup otomatis). Tutup klien lain tersebut atau gunakan jendela yang sudah terbuka."));});
                    return;}
                listener=bound;final ServerSocket server=bound;
                later(()->{if(!f.isVisible())f.setVisible(true);status.setText("Menunggu kontrol utama...");});
                try{Input input=new Input();serve(server,pass,()->active,(s,w)->session(s,w,input));}
                catch(Exception e){if(active)later(()->error(f,e));}
                finally{try{server.close();}catch(Exception ignored){}later(()->{if(listener==server)stop();});}});
        }
        /**
         * Restart this client elevated (UAC must be confirmed on this computer). Sharing resumes with the same code,
         * the master reconnects by itself, and a normal login startup is upgraded to the elevated one.
         */
        void elevateSelf(){
            if(Boolean.TRUE.equals(admin)){JOptionPane.showMessageDialog(f,"Klien sudah berjalan sebagai Administrator.");return;}
            String code=new String(password.getPassword());boolean wasActive=active&&code.length()>=12,upgrade;try{upgrade=Files.exists(RemoteServices.Startup.shortcut());}catch(Exception e){upgrade=false;}boolean upgradeStartup=upgrade;
            if(wasActive)stop();status.setText("Menunggu persetujuan UAC di komputer ini (tekan Ya)...");
            WORK.submit(()->{try{if(wasActive)Updater.stash(code,Map.of());RemoteServices.Startup.elevate("client"+(wasActive?" --resume":"")+(wasActive&&upgradeStartup?" --upgrade-startup":""));later(()->System.exit(0));}
                catch(Exception e){try{Files.deleteIfExists(Updater.resumeFile());}catch(IOException ignored){}
                    later(()->{status.setText("Izin Administrator tidak diberikan. Klien tetap berjalan dengan izin BIASA.");if(wasActive)start();});}});
        }
        /** Called after the master pushed a verified newer JAR: restart with the same mode; the master reconnects by itself. */
        void restartForUpdate(){String code=new String(password.getPassword());status.setText("Memasang pembaruan, klien mulai ulang...");
            WORK.submit(()->{try{List<String> args=new ArrayList<>(List.of("client"));if(automatic)args.add("--auto");else {Updater.stash(code,Map.of());args.add("--resume");}
                    Updater.restart(args);later(()->{stop();System.exit(0);});}
                catch(Exception e){error(f,new Exception("Pembaruan otomatis gagal: "+message(e)));}});}
        void showControllers(){List<String> names=new ArrayList<>(controllers.values());later(()->{if(!active)return;
            if(names.isEmpty())status.setText("Menunggu kontrol utama...");
            else if(names.size()==1)status.setText("Akses layar & file oleh "+names.get(0));
            else status.setText("Dikontrol bersama oleh "+names.size()+" komputer utama: "+String.join(", ",names));});}
        /** CONTROLLERS rpc: a master reports its name and gets the names of the other masters on this client (one per line). */
        String controllersFor(Wire w,String host,String name){String me=Card.cleanName(name);if(!me.isEmpty())controllers.put(w,me+" ("+host+")");showControllers();
            return String.join("\n",otherControllers(controllers,w));}
        void session(Socket s,Wire w,Input input)throws Exception{
            s.setSoTimeout(10000);String host=s.getInetAddress().getHostAddress();controllers.put(w,host);showControllers();Hands hands=new Hands();
            // File RPCs run on their own ordered worker so transfers and folder listings never delay mouse/keyboard input.
            ExecutorService files=Executors.newSingleThreadExecutor(LanControl::daemon),clipboard=Executors.newSingleThreadExecutor(LanControl::daemon);
            try(RemoteServices services=new RemoteServices()){
                services.onUpdate=()->WORK.submit(()->{try{Thread.sleep(600);}catch(InterruptedException ignored){}later(this::restartForUpdate);});
                services.onElevate=()->WORK.submit(()->{try{Thread.sleep(600);}catch(InterruptedException ignored){}later(this::elevateSelf);});
                StreamQuality.State stream=new StreamQuality.State();FrameCache cache=new FrameCache();
                Future<?> video=WORK.submit(()->{try{streamVideo(w,stream,()->input.frame(cache,stream),()->active&&!s.isClosed());}catch(Exception e){w.close();}});
                try{while(active&&!s.isClosed()){String msg=string(w.read());
                    if(msg.startsWith("RPC ")){String[] r=msg.split(" ",-1);
                        if(r.length>=3&&r[2].equals("CONTROLLERS")){try{reply(w,r[1],command(controllersFor(w,host,r.length>3&&!r[3].equals("-")?RemoteServices.un64(r[3]):"")));}catch(Exception e){fail(w,r[1],e);}}
                        else if(clipboardRpc(msg))handleRpc(w,services,msg,input,clipboard);else files.execute(()->{try{handleRpc(w,services,msg,null,null);}catch(Exception e){w.close();}});}
                    else if(msg.startsWith("VIDEO "))stream.configure(msg.split(" "));else if(msg.equals("VIDEO_KEY"))stream.forceKey=true;else if(msg.equals("VIDEO_ACK"))stream.ack();else {if(ControlQueue.input(msg))stream.interact();input.apply(msg,hands);if(!msg.startsWith("MOVE "))stream.nudge();}}}
                finally{video.cancel(true);files.shutdownNow();clipboard.shutdownNow();}
            }finally{controllers.remove(w);input.release(hands);showControllers();}
        }
    }
    /** Client video loop: capture + encode + send, paced by the stream state (also used by the latency tests). */
    static void streamVideo(Wire w,StreamQuality.State stream,Callable<byte[]> frame,java.util.function.BooleanSupplier running)throws Exception{
        while(running.getAsBoolean()){
            if(!stream.ready.tryAcquire(10,TimeUnit.SECONDS))throw new IOException("Pratinjau tidak dikonfirmasi");
            long begun=System.nanoTime();stream.begin();w.send(frame.call());stream.waitNext(begun);
        }
    }
    static boolean clipboardRpc(String msg){String[] p=msg.split(" ",4);return p.length>=3&&(p[2].equals("COPY_TEXT")||p[2].equals("PASTE_TEXT"));}
    static void handleRpc(Wire w,RemoteServices services,String msg)throws Exception{handleRpc(w,services,msg,null,null);}
    static void handleRpc(Wire w,RemoteServices services,String msg,Input input,Executor async)throws Exception{
        String[] parts=msg.split(" ",-1);if(parts.length<3)throw new IOException("RPC tidak valid");String id=parts[1];
        try{List<String> args=new ArrayList<>();for(int i=3;i<parts.length;i++)args.add(parts[i].equals("-")?"":RemoteServices.un64(parts[i]));byte[] result;
            if(parts[2].equals("COPY_TEXT")||parts[2].equals("PASTE_TEXT")){
                if(input==null)throw new IOException("Input desktop tidak tersedia");
                if(parts[2].equals("PASTE_TEXT")){TextClipboard.write(args.get(0));input.chord(KeyEvent.VK_V);result=command("OK");}
                else {
                    // Press Ctrl+C in key order, then wait for the application to publish its copy (slow apps need more than 200 ms).
                    String before;try{before=TextClipboard.peek();}catch(Exception busy){before=null;}String previous=before;input.chord(KeyEvent.VK_C);
                    Runnable finish=()->{try{reply(w,id,command(TextClipboard.awaitChange(previous,1200)));}catch(Exception e){fail(w,id,e);}};
                    if(async==null)finish.run();else async.execute(finish);return;
                }
            }else result=services.execute(parts[2],args);
            reply(w,id,result);}
        catch(Exception e){fail(w,id,e);}
    }
    static void reply(Wire w,String id,byte[] result)throws Exception{w.send(command("RESULT "+id+" OK "+Base64.getEncoder().encodeToString(result)));}
    static void fail(Wire w,String id,Exception e){try{w.send(command("RESULT "+id+" ERR "+RemoteServices.b64(message(e))));}catch(Exception broken){w.close();}}
    static class Peer {
        String videoConfig="";long frames=0,frameBytes=0,started=System.nanoTime();
        /** Screen updates (new, partial or unchanged) received in the last 2 seconds: the real stream rate. */
        final ArrayDeque<Long> updates=new ArrayDeque<>();
        synchronized void tick(){long now=System.nanoTime();updates.addLast(now);while(now-updates.peekFirst()>2_000_000_000L)updates.pollFirst();}
        synchronized double fps(){long now=System.nanoTime();while(!updates.isEmpty()&&now-updates.peekFirst()>2_000_000_000L)updates.pollFirst();return updates.size()/2.0;}
        final Wire wire;final ControlQueue queue=new ControlQueue();volatile boolean alive=true;final Card card;
        /** Text known to be on both clipboards after the last sync. Ctrl+V only uploads master text when it changed. */
        volatile String clipboardMark;
        Peer(Wire w,Card c){wire=w;card=c;}
        final ConcurrentHashMap<String,CompletableFuture<byte[]>> pending=new ConcurrentHashMap<>();
        CompletableFuture<byte[]> request(String operation,String... args)throws Exception{
            if(!alive)throw new IOException("Klien terputus.");String id=UUID.randomUUID().toString();CompletableFuture<byte[]> reply=new CompletableFuture<>();pending.put(id,reply);
            StringBuilder msg=new StringBuilder("RPC "+id+" "+operation);for(String arg:args)msg.append(' ').append(arg.isEmpty()?"-":RemoteServices.b64(arg));send(msg.toString());
            reply.orTimeout(30,TimeUnit.SECONDS).whenComplete((r,e)->pending.remove(id));return reply;
        }
        static boolean pushNeeded(String master,String mark){return master!=null&&!master.equals(mark);}
        void shortcut(int key){
            try{
                if(key==KeyEvent.VK_C){
                    request("COPY_TEXT").whenCompleteAsync((result,failure)->{if(!alive)return;
                        if(failure!=null){String m=message(failure);
                            // File/image copied on the client: keep it there. The next Ctrl+V on this client pastes it instead of old master text.
                            if(m.contains(TextClipboard.NOT_TEXT)){try{clipboardMark=TextClipboard.peek();}catch(Exception ignored){}card.notice("File/gambar disalin di klien. Ctrl+V di klien ini akan menempelkannya.");}
                            else error(card,new IOException("Clipboard gagal: "+m));return;}
                        try{String text=string(result);TextClipboard.write(text);clipboardMark=text;card.notice("Teks klien disalin ke komputer utama.");}catch(Exception e){error(card,e);}},TextClipboard.TRANSFER);
                    return;
                }
                String text=TextClipboard.peek();
                if(pushNeeded(text,clipboardMark)){TextClipboard.validate(text);clipboardMark=text;
                    request("PASTE_TEXT",text).whenComplete((r,failure)->{if(failure!=null&&alive){clipboardMark=null;error(card,new IOException("Tempel gagal: "+message(failure)));}});}
                else {send("KEYDOWN "+KeyEvent.VK_CONTROL);send("KEYDOWN "+KeyEvent.VK_V);send("KEYUP "+KeyEvent.VK_V);}
            }catch(Exception e){error(card,e);}
        }
        byte[] rpc(String operation,String... args)throws Exception{
            try{return request(operation,args).get(30,TimeUnit.SECONDS);}catch(ExecutionException e){throw new IOException(message(e));}
        }
        void send(String s){if(alive&&!queue.offer(s))close();}
        void close(){alive=false;wire.close();pending.values().forEach(p->p.completeExceptionally(new IOException("Klien terputus.")));pending.clear();}
        void start(){
            WORK.submit(()->{try{while(alive){String s=queue.poll(2000);wire.send(command(s==null?"PING":s));}}catch(Exception e){close();}});
            WORK.submit(()->{try{while(alive){byte[] data=wire.read();if(Arrays.equals(data,command("FRAME_SAME"))){tick();send("VIDEO_ACK");continue;}if(data.length>7&&data[0]=='R'){String[] p=string(data).split(" ",4);if(p.length==4&&p[0].equals("RESULT")){CompletableFuture<byte[]> task=pending.get(p[1]);if(task!=null){byte[] result=Base64.getDecoder().decode(p[3]);if(p[2].equals("OK"))task.complete(result);else task.completeExceptionally(new IOException(string(result)));}continue;}}BufferedImage image;
List<java.awt.Rectangle> changed=null;
                if(FrameCache.isTiles(data)){FrameCache.Applied applied=FrameCache.applyTiles(card.image,data);if(applied==null){send("VIDEO_KEY");send("VIDEO_ACK");continue;}image=applied.image();changed=applied.changed();}
                else {image=ImageIO.read(new ByteArrayInputStream(data));if(image==null)throw new IOException("Gambar tidak valid");}
                card.prepareViews(image,changed);card.image=image;frames++;tick();frameBytes=data.length;send("VIDEO_ACK");later(card::refreshViews);}}catch(Exception e){}finally{close();later(()->{if(card.peer==this){card.peer=null;card.image=null;card.lost();card.refreshViews();}});}});
        }
    }
    interface Sender {void send(String s);}
    /** Special key combinations the master's own Windows would otherwise intercept. */
    static final String[] COMBO_NAMES={"Tombol Windows  (menu Start)","Win + D  (tampilkan desktop)","Win + E  (File Explorer)","Win + R  (Run)","Win + X  (menu power user)","Win + Shift + S  (potong layar)","Alt + Tab  (ganti jendela)","Alt + F4  (tutup jendela aktif di klien)","Ctrl + Shift + Esc  (Task Manager)","Print Screen"};
    static final int[][] COMBOS={{KeyEvent.VK_WINDOWS},{KeyEvent.VK_WINDOWS,KeyEvent.VK_D},{KeyEvent.VK_WINDOWS,KeyEvent.VK_E},{KeyEvent.VK_WINDOWS,KeyEvent.VK_R},{KeyEvent.VK_WINDOWS,KeyEvent.VK_X},{KeyEvent.VK_WINDOWS,KeyEvent.VK_SHIFT,KeyEvent.VK_S},{KeyEvent.VK_ALT,KeyEvent.VK_TAB},{KeyEvent.VK_ALT,KeyEvent.VK_F4},{KeyEvent.VK_CONTROL,KeyEvent.VK_SHIFT,KeyEvent.VK_ESCAPE},{KeyEvent.VK_PRINTSCREEN}};
    /** v1.10.0: keyboard equivalents, pressed while the client screen has focus. The real combinations are caught by the master's own Windows. */
    static final String[] COMBO_KEYS={"Ctrl+Alt+W","Ctrl+Alt+D","Ctrl+Alt+E","Ctrl+Alt+R","Ctrl+Alt+X","Ctrl+Alt+S","Alt+Page Up","Ctrl+Alt+F4","Ctrl+Alt+Esc","Ctrl+Alt+P"};
    static final int[] COMBO_HOTKEYS={KeyEvent.VK_W,KeyEvent.VK_D,KeyEvent.VK_E,KeyEvent.VK_R,KeyEvent.VK_X,KeyEvent.VK_S,0,KeyEvent.VK_F4,KeyEvent.VK_ESCAPE,KeyEvent.VK_P};
    /** Ctrl+Alt+key → the client combination, or null. Numpad + is Print Screen as in Windows Remote Desktop. */
    static int[] hotkey(int key){if(key==KeyEvent.VK_ADD)return new int[]{KeyEvent.VK_PRINTSCREEN};for(int i=0;i<COMBO_HOTKEYS.length;i++)if(COMBO_HOTKEYS[i]!=0&&COMBO_HOTKEYS[i]==key)return COMBOS[i];return null;}
    static final String RESET_KEY="Ctrl+Alt+K";
    static String menuText(String name,String keys){return "<html><table cellpadding=0 cellspacing=0 width=430><tr><td>"+name+"</td><td align=right><font color=#B1C0D3>"+keys+"</font></td></tr></table></html>";}
    static void combo(Sender sender,int... keys){for(int k:keys)sender.send("KEYDOWN "+k);for(int i=keys.length-1;i>=0;i--)sender.send("KEYUP "+keys[i]);}
    static JButton shortcutsButton(String text,java.util.function.Supplier<Sender> target,Runnable after){
        JPopupMenu menu=new JPopupMenu();JButton[] holder=new JButton[1];
        for(int i=0;i<COMBOS.length;i++){int[] keys=COMBOS[i];JMenuItem item=new JMenuItem(menuText(COMBO_NAMES[i],COMBO_KEYS[i]));item.addActionListener(e->{Sender s=target.get();if(s!=null){combo(s,keys);after.run();}});menu.add(item);}
        menu.addSeparator();JMenuItem hint=new JMenuItem("Pakai keyboard: klik layar klien dulu, lalu tekan kombinasi di kanan.");hint.setEnabled(false);menu.add(hint);
        menu.addSeparator();JMenuItem cad=new JMenuItem("Ctrl + Alt + Del  (tidak didukung: layar aman Windows)");cad.setEnabled(false);menu.add(cad);
        JMenuItem reset=new JMenuItem(menuText("Lepaskan semua tombol",RESET_KEY));reset.addActionListener(e->{Sender s=target.get();if(s!=null){s.send("RESET");after.run();}});menu.add(reset);
        holder[0]=button(text,()->menu.show(holder[0],0,holder[0].getHeight()));holder[0].setToolTipText("Kirim kombinasi tombol yang tidak dapat diteruskan langsung dari keyboard komputer utama. Bisa juga lewat keyboard, misalnya Ctrl+Alt+E untuk Win+E dan Alt+Page Up untuk Alt+Tab.");return holder[0];
    }
    static class View extends JPanel implements Scrollable {
        final java.util.function.Supplier<BufferedImage> source;final Sender sender;
        Rectangle area=new Rectangle();boolean dragging=false;volatile boolean oneToOne=false,stretch=false;java.util.function.IntConsumer shortcut;java.util.function.BooleanSupplier escape=()->false;final Set<Integer> swallowed=new HashSet<>();
        /** Ctrl/Alt released on the client for a keyboard shortcut while still held here; pressed again before the next ordinary key. */
        final Set<Integer> lifted=new LinkedHashSet<>();
        volatile int displayWidth,displayHeight;volatile Prepared prepared;double wheel=0;
        /** Files/folders dropped from Windows Explorer onto the client screen; null disables dropping. */
        java.util.function.Consumer<List<java.nio.file.Path>> drop;volatile boolean dragOver;
        /** True for the Perbesar window: shows the "keyboard goes to client" badge and a thicker focus frame. */
        boolean enlarged=false;
        record Prepared(BufferedImage source,int width,int height,BufferedImage scaled) {}
        void prepare(BufferedImage frame){prepare(frame,null);}
        /** Pre-scales the frame for painting; with a list of changed areas only those parts of the previous scaled frame are recomputed. */
        void prepare(BufferedImage frame,List<java.awt.Rectangle> changed){
            Prepared last=prepared;int w0=displayWidth,h0=displayHeight;
            if(changed!=null&&last!=null&&!oneToOne&&last.width()==w0&&last.height()==h0&&last.source().getWidth()==frame.getWidth()&&last.source().getHeight()==frame.getHeight()){
                BufferedImage updated=FastScale.update(last.scaled(),frame,changed);if(updated!=null){prepared=new Prepared(frame,w0,h0,updated);return;}}
            int w=displayWidth,h=displayHeight;if(oneToOne||w<1||h<1){prepared=null;return;}double scale=Math.min(w/(double)frame.getWidth(),h/(double)frame.getHeight());int width=stretch?w:Math.max(1,(int)(frame.getWidth()*scale)),height=stretch?h:Math.max(1,(int)(frame.getHeight()*scale));BufferedImage scaled=FastScale.resize(frame,width,height);prepared=new Prepared(frame,w,h,scaled);}
        static boolean mainButton(MouseEvent e){return e.getButton()>=MouseEvent.BUTTON1&&e.getButton()<=MouseEvent.BUTTON3;}
        View(java.util.function.Supplier<BufferedImage> source,Sender sender){this.source=source;this.sender=sender;setFocusable(true);setBackground(Ui.CANVAS);
            addComponentListener(new ComponentAdapter(){public void componentResized(ComponentEvent e){displayWidth=getWidth();displayHeight=getHeight();prepared=null;}});
            MouseAdapter m=new MouseAdapter(){
                void move(MouseEvent e){if(area.contains(e.getPoint()))sender.send("MOVE "+((e.getX()-area.x)/(double)area.width)+" "+((e.getY()-area.y)/(double)area.height));}
                public void mousePressed(MouseEvent e){if(!area.contains(e.getPoint())||source.get()==null)return;requestFocusInWindow();if(!mainButton(e))return;dragging=true;move(e);sender.send("DOWN "+InputEvent.getMaskForButton(e.getButton()));}
                public void mouseReleased(MouseEvent e){if(dragging&&mainButton(e)){move(e);sender.send("UP "+InputEvent.getMaskForButton(e.getButton()));dragging=false;}}
                public void mouseMoved(MouseEvent e){if(hasFocus())move(e);}public void mouseDragged(MouseEvent e){move(e);}
                // Touchpads report fractional rotation (getWheelRotation()==0); accumulate until a whole notch is reached.
                public void mouseWheelMoved(MouseWheelEvent e){if(!area.contains(e.getPoint()))return;wheel+=e.getPreciseWheelRotation();int notches=(int)wheel;if(notches!=0){wheel-=notches;sender.send("WHEEL "+notches);}}
            };addMouseListener(m);addMouseMotionListener(m);addMouseWheelListener(m);
            addKeyListener(new KeyAdapter(){public void keyPressed(KeyEvent e){int k=e.getKeyCode();boolean ctrlAlt=e.isControlDown()&&e.isAltDown()&&!e.isShiftDown()&&!e.isMetaDown();int[] mapped=ctrlAlt?hotkey(k):null;
                if(swallowed.contains(k)){}
                else if(mapped!=null||(ctrlAlt&&k==KeyEvent.VK_K)){swallowed.add(k);lift();if(mapped!=null)combo(sender,mapped);else sender.send("RESET");}
                else if(e.isAltDown()&&!e.isControlDown()&&(k==KeyEvent.VK_PAGE_UP||k==KeyEvent.VK_PAGE_DOWN)){swallowed.add(k);restore(e);
                    // Alt stays held on the client, so the task switcher stays open until Alt is released here.
                    if(k==KeyEvent.VK_PAGE_DOWN)sender.send("KEYDOWN "+KeyEvent.VK_SHIFT);sender.send("KEYDOWN "+KeyEvent.VK_TAB);sender.send("KEYUP "+KeyEvent.VK_TAB);if(k==KeyEvent.VK_PAGE_DOWN)sender.send("KEYUP "+KeyEvent.VK_SHIFT);}
                else if(k==KeyEvent.VK_ESCAPE&&escape.getAsBoolean()){swallowed.add(k);}
                else if(shortcut!=null&&e.isControlDown()&&!e.isAltDown()&&!e.isShiftDown()&&(k==KeyEvent.VK_C||k==KeyEvent.VK_V)){swallowed.add(k);shortcut.accept(k);}
                else if(k!=KeyEvent.VK_UNDEFINED){restore(e);sender.send("KEYDOWN "+k);}e.consume();}
                public void keyReleased(KeyEvent e){int k=e.getKeyCode();if(!swallowed.remove(k)&&!lifted.remove(k))sender.send("KEYUP "+k);e.consume();}
                /** Release Alt before Ctrl on the client: Alt let go while Ctrl is down never opens an application's menu bar. */
                void lift(){for(int m:new int[]{KeyEvent.VK_ALT,KeyEvent.VK_CONTROL})if(lifted.add(m))sender.send("KEYUP "+m);}
                void restore(KeyEvent e){for(int m:new int[]{KeyEvent.VK_CONTROL,KeyEvent.VK_ALT})if(lifted.contains(m)&&(m==KeyEvent.VK_CONTROL?e.isControlDown():e.isAltDown()))sender.send("KEYDOWN "+m);lifted.clear();}});
            setFocusTraversalKeysEnabled(false);addFocusListener(new FocusAdapter(){public void focusLost(FocusEvent e){dragging=false;swallowed.clear();lifted.clear();wheel=0;sender.send("RESET");}});
            if(!GraphicsEnvironment.isHeadless())new java.awt.dnd.DropTarget(this,java.awt.dnd.DnDConstants.ACTION_COPY,new java.awt.dnd.DropTargetAdapter(){
                boolean accept(java.awt.dnd.DropTargetDragEvent e){boolean ok=drop!=null&&source.get()!=null&&e.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);if(ok)e.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY);else e.rejectDrag();if(dragOver!=ok){dragOver=ok;repaint();}return ok;}
                public void dragEnter(java.awt.dnd.DropTargetDragEvent e){accept(e);}public void dragOver(java.awt.dnd.DropTargetDragEvent e){accept(e);}
                public void dragExit(java.awt.dnd.DropTargetEvent e){dragOver=false;repaint();}
                @SuppressWarnings("unchecked") public void drop(java.awt.dnd.DropTargetDropEvent e){dragOver=false;repaint();
                    if(drop==null||source.get()==null){e.rejectDrop();return;}
                    try{e.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY);List<java.io.File> files=(List<java.io.File>)e.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);e.dropComplete(true);
                        if(!files.isEmpty())drop.accept(files.stream().map(java.io.File::toPath).toList());}catch(Exception x){e.dropComplete(false);}}
            },true);
        }
        public Dimension getPreferredScrollableViewportSize(){return new Dimension(800,500);}
        public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){return 24;}
        public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){return Math.max(24,(orientation==SwingConstants.HORIZONTAL?visible.width:visible.height)-24);}
        public boolean getScrollableTracksViewportWidth(){return !oneToOne;}
        public boolean getScrollableTracksViewportHeight(){return !oneToOne;}
        public Dimension getPreferredSize(){BufferedImage im=source.get();return oneToOne&&im!=null?new Dimension(im.getWidth(),im.getHeight()):new Dimension(440,230);}
        protected void paintComponent(Graphics graphics){super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);BufferedImage im=source.get();
            if(im==null){area=new Rectangle();int cx=getWidth()/2,cy=getHeight()/2;
                g.setColor(Ui.LINE);g.setStroke(new BasicStroke(2));g.drawRoundRect(cx-24,cy-42,48,32,7,7);g.drawLine(cx,cy-10,cx,cy-2);g.drawLine(cx-12,cy-2,cx+12,cy-2);
                g.setColor(Ui.MUTED);g.setFont(new Font(Ui.FONT,Font.PLAIN,13));String t="Siap dihubungkan";g.drawString(t,cx-g.getFontMetrics().stringWidth(t)/2,cy+25);g.dispose();return;}
            double scale=oneToOne?1:Math.min(getWidth()/(double)im.getWidth(),getHeight()/(double)im.getHeight());int w=stretch&&!oneToOne?getWidth():Math.max(1,(int)(im.getWidth()*scale)),h=stretch&&!oneToOne?getHeight():Math.max(1,(int)(im.getHeight()*scale));
            area=new Rectangle(Math.max(0,(getWidth()-w)/2),Math.max(0,(getHeight()-h)/2),w,h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
            Prepared ready=prepared;if(!oneToOne&&ready!=null&&ready.source()==im&&ready.width()==getWidth()&&ready.height()==getHeight())g.drawImage(ready.scaled(),area.x,area.y,w,h,null);else {g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(im,area.x,area.y,w,h,null);}focusIndicator(g);
            if(dragOver){g.setColor(new Color(Ui.BG.getRed(),Ui.BG.getGreen(),Ui.BG.getBlue(),190));g.fillRect(0,0,getWidth(),getHeight());g.setColor(Ui.ACCENT);g.setStroke(new BasicStroke(3,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND,1,new float[]{12,9},0));g.drawRoundRect(12,12,getWidth()-25,getHeight()-25,18,18);
                g.setFont(new Font(Ui.FONT,Font.BOLD,Math.max(15,Math.min(26,getWidth()/30))));String t="Lepas untuk mengirim ke Desktop klien";g.drawString(t,(getWidth()-g.getFontMetrics().stringWidth(t))/2,getHeight()/2);}
            g.dispose();
        }
        /** AnyDesk-style cue: a frame plus a corner badge telling whether what you type now goes to the client. */
        void focusIndicator(Graphics2D g){
            boolean active=hasFocus();
            if(active){g.setStroke(new BasicStroke(enlarged?3:2));g.setColor(Ui.ACCENT);g.drawRect(1,1,getWidth()-3,getHeight()-3);}
            else if(enlarged){g.setStroke(new BasicStroke(2));g.setColor(Ui.MUTED);g.drawRect(1,1,getWidth()-3,getHeight()-3);}
            if(!enlarged)return;
            String text=active?"⌨  Ketikan & pintasan → klien":"Klik layar untuk mengetik ke klien";
            g.setFont(new Font(Ui.FONT,Font.BOLD,13));FontMetrics fm=g.getFontMetrics();int pad=9,tw=fm.stringWidth(text),bw=tw+pad*2,bh=fm.getHeight()+8;
            g.setColor(new Color(Ui.SURFACE.getRed(),Ui.SURFACE.getGreen(),Ui.SURFACE.getBlue(),active?238:226));g.fillRoundRect(10,10,bw,bh,12,12);
            g.setColor(active?Ui.ACCENT:Ui.LINE);g.setStroke(new BasicStroke(1));g.drawRoundRect(10,10,bw,bh,12,12);
            g.setColor(active?Ui.ACCENT:Ui.MUTED);g.drawString(text,10+pad,10+fm.getAscent()+4);
        }
    }
    /** Keyboard equivalents for the keys Windows on the master would otherwise steal, for the legend under the Perbesar window. */
    static final String[] KEY_LEGEND={"Ctrl+Alt+E → Win+E","Ctrl+Alt+R → Win+R","Ctrl+Alt+X → Win+X","Ctrl+Alt+W → Start","Alt+PgUp → Alt+Tab","Ctrl+Alt+F4 → Alt+F4","Ctrl+Alt+Esc → Task Manager","Ctrl+Alt+P → Print Screen","Ctrl+Alt+K → lepas tombol"};
    static class Card extends Ui.RoundPanel {
        int number;JLabel numberLabel;final Controller owner;volatile Peer peer;volatile BufferedImage image;final JTextField ip=new JTextField(14);final JCheckBox selected=new JCheckBox("Pilih");
        final JLabel title,status=Ui.label("Offline",12,true,Ui.MUTED),picture=Ui.label(" ",11,false,Ui.MUTED);final View view;
        boolean connecting,wanted;volatile boolean expectRestart;volatile String privilege;
        /** Other masters connected to this client right now (v1.9.0 clients), "" when none. */
        volatile String others="";String host="";int attempts;javax.swing.Timer retry;String name,note;long noticeUntil;
        final List<View> largeViews=new CopyOnWriteArrayList<>();String mode="AUTO";
        Card(int n,Controller owner){super(new BorderLayout(0,12));number=n;this.owner=owner;name="Komputer "+n;
            JPanel head=Ui.transparent(new BorderLayout());JPanel identity=Ui.transparent(new FlowLayout(FlowLayout.LEFT,9,0));numberLabel=Ui.label(String.format("%02d",n),13,true,Ui.ACCENT);identity.add(numberLabel);
            title=Ui.label(name,16,true,Ui.TEXT);title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));title.setToolTipText("Klik untuk mengganti nama komputer ini");title.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){rename();}});identity.add(title);
            JButton edit=button("✎ Ubah nama",this::rename);edit.setFont(new Font(Ui.FONT,Font.PLAIN,12));edit.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));edit.setToolTipText("Ganti nama komputer ini, misalnya Kasir 1, Lab-02, Ruang Guru");identity.add(edit);head.add(identity);
            status.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){privilegeHelp();}});selected.setOpaque(false);JPanel choose=Ui.transparent(new FlowLayout(FlowLayout.RIGHT,6,0));choose.add(button("Fokus",()->owner.focus(this)));choose.add(selected);JButton remove=button("✕",()->owner.removeCard(this));remove.setToolTipText("Hapus komputer ini dari daftar");choose.add(remove);head.add(choose,BorderLayout.EAST);
            JPanel address=Ui.transparent(new BorderLayout(8,0));ip.setToolTipText("IP LAN atau Tailscale klien");ip.addActionListener(e->connect());address.add(ip);JPanel connect=Ui.transparent(new GridLayout(1,2,6,0));connect.add(Ui.primary("Hubungkan",this::connect));connect.add(button("Putus",this::disconnect));address.add(connect,BorderLayout.EAST);
            JPanel upper=Ui.transparent(new BorderLayout(0,12));upper.add(head,BorderLayout.NORTH);upper.add(address);add(upper,BorderLayout.NORTH);
            view=new View(()->image,msg->{Peer p=peer;if(p!=null)p.send(msg);});view.shortcut=key->{Peer p=peer;if(p!=null)p.shortcut(key);};view.drop=items->owner.sendFiles(List.of(this),items,view);add(view);
            JPanel footer=Ui.transparent(new BorderLayout(8,0));JPanel info=Ui.transparent(new GridLayout(0,1,0,4));info.add(status);info.add(picture);footer.add(info);
            JPanel actions=Ui.transparent(new FlowLayout(FlowLayout.RIGHT,6,0));
            actions.add(shortcutsButton("Pintasan",()->{Peer p=peer;if(p==null){error(owner.f,new Exception("Hubungkan klien terlebih dahulu."));return null;}return p::send;},view::requestFocusInWindow));
            actions.add(button("File & akses",()->{if(peer==null)error(owner.f,new Exception("Hubungkan klien terlebih dahulu."));else new RemoteTools(this);}));actions.add(button("Perbesar",()->owner.open(this,false)));footer.add(actions,BorderLayout.EAST);add(footer,BorderLayout.SOUTH);
            selected.addActionListener(e->{Peer p=peer;if(p!=null)p.send("RESET");});
        }
        boolean limited(){return peer!=null&&"BIASA".equals(privilege);}
        void showOnline(){if(peer==null)return;boolean warn=limited(),shared=!others.isEmpty();int count=shared?others.split("\n").length:0;
            status.setText("Online • "+host+(warn?"  •  ⚠ Izin BIASA":"")+(shared?"  •  Juga dikontrol "+count+" komputer utama lain":""));status.setForeground(warn||shared?Ui.WARN:Ui.ACCENT);
            status.setCursor(Cursor.getPredefinedCursor(warn?Cursor.HAND_CURSOR:Cursor.DEFAULT_CURSOR));
            String tip=warn?"Klien berjalan tanpa izin Administrator: klik ke Task Manager, installer, dan aplikasi admin tidak berfungsi. Klik untuk memperbaiki.":null;
            if(shared)tip=(tip==null?"":tip+" ")+"Juga dikontrol oleh: "+others.replace("\n",", ")+". Kursor dan ketikan dipakai bersama: bergantianlah.";status.setToolTipText(tip);}
        /** Explains the UIPI limitation and lets the master ask the client to restart elevated. */
        void privilegeHelp(){
            Peer p=peer;if(p==null||!limited())return;
            Object[] options={"Minta izin Administrator","Tutup"};
            int choice=JOptionPane.showOptionDialog(owner.f,name+" berjalan dengan izin BIASA.\n\nWindows melarang klik dan ketikan dari program biasa ke jendela yang berjalan sebagai Administrator,\nmisalnya Task Manager, installer, Regedit, atau aplikasi yang dibuka dengan \"Run as administrator\".\n\nMinta izin Administrator: jendela UAC muncul di komputer klien dan SESEORANG DI KLIEN perlu menekan Ya.\nSetelah itu klien tersambung kembali sendiri, dan startup otomatisnya ikut memakai izin Administrator.\n(Alternatif: jalankan Jalankan-Klien-Admin.bat di klien.)","Izin klien",JOptionPane.DEFAULT_OPTION,JOptionPane.WARNING_MESSAGE,null,options,options[0]);
            if(choice!=0)return;expectRestart=true;notice("Menunggu seseorang menekan Ya pada jendela UAC di klien...");noticeUntil=System.currentTimeMillis()+180_000;
            WORK.submit(()->{try{p.rpc("ELEVATE");}catch(Exception e){expectRestart=false;String m=message(e);later(()->{noticeUntil=0;error(owner.f,new Exception(m.contains("tidak dikenal")?"Klien belum v1.6.2. Tunggu klien diperbarui otomatis, lalu coba lagi.":m));});}});
        }
        void applyName(String s){name=s==null||s.isBlank()?"Komputer "+number:s.trim();title.setText(name);}
        /** New position after another computer was removed; a default name follows the number. */
        void renumber(int n){boolean defaultName=name.equals("Komputer "+number);number=n;numberLabel.setText(String.format("%02d",n));if(defaultName)applyName("");}
        static final int NAME_LIMIT=40;
        boolean defaultName(){return name.equals("Komputer "+number);}
        void rename(){String s=(String)JOptionPane.showInputDialog(owner.f,"Nama untuk komputer nomor "+number+"\n(contoh: Kasir 1, Lab-02, Ruang Guru). Kosongkan untuk kembali ke \"Komputer "+number+"\".","Ubah nama komputer",JOptionPane.PLAIN_MESSAGE,null,null,name);
            if(s!=null){applyName(cleanName(s));owner.save();}}
        static String cleanName(String s){String t=s.replaceAll("[\\p{Cntrl}]","").trim();return t.length()>NAME_LIMIT?t.substring(0,NAME_LIMIT).trim():t;}
        void notice(String text){later(()->{picture.setText(text);noticeUntil=System.currentTimeMillis()+5000;});}
        void prepareViews(BufferedImage frame){prepareViews(frame,null);}
        void prepareViews(BufferedImage frame,List<java.awt.Rectangle> changed){if(largeViews.isEmpty()&&view.isShowing())view.prepare(frame,changed);for(View v:largeViews)if(v.isShowing())v.prepare(frame,changed);}
        void refreshViews(){view.repaint();for(View v:largeViews){if(v.oneToOne)v.revalidate();v.repaint();}}
        void updateStream(){Peer p=peer;if(p==null){if(image==null&&!connecting&&retry==null&&note==null&&System.currentTimeMillis()>noticeUntil)picture.setText(" ");picture.setToolTipText(picture.getText().isBlank()?null:picture.getText());return;}int w=view.getWidth(),h=view.getHeight();
            for(View v:largeViews)if(v.isShowing()){Dimension d=v.oneToOne?new Dimension(3840,2160):v.getSize();if((long)d.width*d.height>(long)w*h){w=d.width;h=d.height;}}
            if(view.getGraphicsConfiguration()!=null){double dpi=view.getGraphicsConfiguration().getDefaultTransform().getScaleX();w=(int)(w*dpi);h=(int)(h*dpi);}
            boolean enlarged=largeViews.stream().anyMatch(v->v.isShowing()&&!(SwingUtilities.getWindowAncestor(v) instanceof Frame frame&&(frame.getExtendedState()&Frame.ICONIFIED)!=0));
            boolean focusedPreview=!enlarged&&view.isShowing()&&view.getHeight()>=300;
            boolean visible=enlarged||(view.isShowing()&&!view.getVisibleRect().isEmpty()&&!(SwingUtilities.getWindowAncestor(view) instanceof Frame frame&&(frame.getExtendedState()&Frame.ICONIFIED)!=0));
            String config="VIDEO "+((enlarged||focusedPreview)?mode:"PREVIEW")+" "+Math.max(320,w)+" "+Math.max(180,h)+" "+(visible?((enlarged||focusedPreview)?(mode.equals("NATIVE")?50:33):300):1000)+" TILES";if(!config.equals(p.videoConfig)){p.videoConfig=config;p.send(config);}
            BufferedImage im=image;if(im!=null&&System.currentTimeMillis()>noticeUntil){double seconds=(System.nanoTime()-p.started)/1e9;picture.setText(im.getWidth()+" × "+im.getHeight()+"  /  "+String.format(java.util.Locale.ROOT,"%.0f fps",p.fps())+"  /  "+modeLabel(mode));}
        }
        void connect(){wanted=true;attempts=0;stopRetry();open(false,false);}
        /** Used by "Hubungkan semua" and the launch auto-connect: errors stay on the card instead of popping up. */
        void connectQuietly(boolean retryOnFail){if(ip.getText().trim().isEmpty()||peer!=null||connecting)return;wanted=true;attempts=0;stopRetry();open(true,retryOnFail);}
        void disconnect(){wanted=false;attempts=0;stopRetry();note=null;Peer p=peer;if(p!=null)p.close();else if(!connecting){status.setText("Offline");status.setForeground(Ui.MUTED);picture.setText(" ");}}
        void stopRetry(){if(retry!=null){retry.stop();retry=null;}}
        void lost(){if(wanted&&(owner.reconnect.isSelected()||expectRestart))scheduleRetry(expectRestart?"Klien mulai ulang untuk pembaruan.":"Koneksi terputus.");else {status.setText("Terputus");status.setForeground(Ui.MUTED);}}
        void scheduleRetry(String reason){stopRetry();attempts++;int seconds=Math.min(15,1+2*attempts);note=reason;
            status.setText("Menyambung ulang dalam "+seconds+" dtk  (percobaan "+attempts+")");status.setForeground(Ui.WARN);picture.setText(reason);
            retry=new javax.swing.Timer(seconds*1000,e->{retry=null;if(wanted&&peer==null)open(true,true);});retry.setRepeats(false);retry.start();}
        void open(boolean silent,boolean retryOnFail){
            if(connecting||peer!=null)return;String host=ip.getText().trim(),pass=new String(owner.password.getPassword());
            if(host.isEmpty()||pass.length()<12){wanted=false;if(!silent)error(owner.f,new Exception("Isi IP klien dan kode minimal 12 karakter."));else {status.setText("Isi IP dan kode");status.setForeground(Ui.WARN);}return;}
            connecting=true;status.setText("Menghubungkan...");status.setForeground(Ui.ACCENT);int port=owner.port;
            WORK.submit(()->{try{Wire w=dial(host,port,pass,5000);Peer p=new Peer(w,this);
                    later(()->{connecting=false;if(!wanted){p.close();status.setText("Offline");status.setForeground(Ui.MUTED);return;}peer=p;attempts=0;note=null;expectRestart=false;privilege=null;this.host=host;showOnline();p.start();updateStream();owner.save();owner.checkVersion(this,p,host);owner.checkPrivilege(this,p);owner.checkName(this,p);owner.checkControllers(this,p);});}
                catch(Exception e){String reason=connectionReason(e);boolean fatal=e instanceof BadCodeException;
                    later(()->{connecting=false;if(!wanted)return;
                        if(retryOnFail&&!fatal&&(owner.reconnect.isSelected()||expectRestart)){scheduleRetry(reason);return;}
                        wanted=false;note=reason;status.setText("Koneksi gagal");status.setForeground(Ui.DANGER);picture.setText(reason);
                        if(fatal)owner.alertOnce("Kode koneksi berbeda dengan klien. Periksa kode pada komputer utama dan klien.");
                        else if(!silent)error(owner.f,new Exception("Gagal ke "+host+": "+reason));});}});
        }
    }
    static final String[] MODES={"Otomatis","HD 1080p","Asli / teks","Hemat data"};
    static final List<String> MODE_CODES=List.of("AUTO","HD","NATIVE","HEMAT");
    static String modeCode(int index){return MODE_CODES.get(index);}
    static String modeLabel(String mode){return switch(mode){case "HD"->"HD";case "NATIVE"->"Asli / teks";case "HEMAT"->"Hemat";default->"Otomatis";};}
    /** Routes to each card's current peer, so open windows keep working after a reconnect. */
    static Sender sender(List<Card> targets){return msg->{for(Card c:targets){Peer p=c.peer;if(p!=null)p.send(msg);}};}
    static class Controller {
        static final ExecutorService SETTINGS=Executors.newSingleThreadExecutor(LanControl::daemon);
        final Ui.Grid grid=new Ui.Grid();JCheckBox focusLayout;final JCheckBox reconnect=new JCheckBox("Sambung ulang otomatis",true),remember=new JCheckBox("Ingat kode"),autoUpdate=new JCheckBox("Perbarui klien otomatis",true),onlineUpdate=new JCheckBox("Cek pembaruan internet",true);
        final JPanel updateBar=Ui.transparent(new Ui.WrapLayout(FlowLayout.LEFT,8,2));final JLabel updateLabel=Ui.label(" ",13,true,Ui.WARN);javax.swing.Timer updateTimer;
        volatile String pendingVersion;volatile long lastInput=System.currentTimeMillis();long postponeUntil,lastCheck,updatedUntil;int countdown=-1;boolean checking,installing,onlineChecking;long nextOnline=System.currentTimeMillis()+20_000;
        final Set<String> pushed=ConcurrentHashMap.newKeySet();java.util.concurrent.Callable<byte[]> ownJar=()->java.nio.file.Files.readAllBytes(RemoteServices.Startup.jar());final JComboBox<String> quality=new JComboBox<>(MODES);
        final JFrame f;final boolean preview;int port=PORT;final JPanel dashboard=new JPanel(new BorderLayout(0,12));final JPasswordField password=new JPasswordField(18);final List<Card> cards=new ArrayList<>();final Set<JFrame> remotes=new HashSet<>();final JLabel count=Ui.label("0 / 4 klien online",13,true,Ui.ACCENT);JScrollPane gridScroll;final javax.swing.Timer timer;
        Path settingsFile=Settings.file("controller.properties");volatile String protectedCode;String protectedPlain;long alerted;
        Controller(){this(false);}
        Controller(boolean preview){this.preview=preview;
            f=preview?null:window("LAN Control 5 (UJI) v"+VERSION+" — Kontrol Utama");dashboard.setBorder(BorderFactory.createEmptyBorder(16,22,12,22));
            JPanel heading=Ui.transparent(new BorderLayout());JPanel words=Ui.transparent(new GridLayout(0,1,0,6));words.add(Ui.label("LAN CONTROL 5  /  v"+VERSION,12,true,Ui.ACCENT));words.add(Ui.label("Ruang kontrol",29,true,Ui.TEXT));words.add(Ui.label("Semua komputer, satu tempat. Pilih klien untuk bekerja bersama.",13,false,Ui.MUTED));heading.add(words);heading.add(count,BorderLayout.EAST);
            Ui.RoundPanel toolbar=new Ui.RoundPanel(new BorderLayout(18,4));toolbar.setBorder(BorderFactory.createEmptyBorder(8,10,8,10));
            JPanel access=Ui.transparent(new Ui.WrapLayout(FlowLayout.LEFT,8,5));access.add(Ui.label("Kode koneksi",12,true,Ui.MUTED));password.setPreferredSize(new Dimension(180,36));access.add(password);
            remember.setOpaque(false);remember.setToolTipText("Simpan kode terenkripsi (Windows DPAPI) agar klien tersambung otomatis saat aplikasi dibuka");remember.addActionListener(e->rememberCode());access.add(remember);
            access.add(Ui.label("Kualitas",12,true,Ui.MUTED));quality.setPreferredSize(new Dimension(135,36));quality.addActionListener(e->{for(Card c:cards)c.mode=modeCode(quality.getSelectedIndex());save();});access.add(quality);
            access.add(Ui.primary("Hubungkan semua",this::connectAll));access.add(button("Putus semua",()->cards.forEach(Card::disconnect)));if(!preview)access.add(Ui.themeToggle());toolbar.add(access,BorderLayout.NORTH);
            password.addFocusListener(new FocusAdapter(){public void focusLost(FocusEvent e){if(remember.isSelected())rememberCode();}});
            JPanel actions=Ui.transparent(new Ui.WrapLayout(FlowLayout.LEFT,8,5));actions.add(button("Pilih semua",()->cards.forEach(c->c.selected.setSelected(true))));actions.add(button("Batal pilih",()->cards.forEach(c->c.selected.setSelected(false))));actions.add(Ui.primary("Kontrol serentak",()->{Card c=cards.stream().filter(x->x.selected.isSelected()&&x.peer!=null).findFirst().orElse(null);if(c==null)error(f,new Exception("Hubungkan dan centang minimal satu klien."));else open(c,true);}));actions.add(button("Lepaskan tombol",this::reset));actions.add(Ui.primary("+ Tambah komputer",this::askNewCard));toolbar.add(actions,BorderLayout.CENTER);
            JPanel top=Ui.transparent(new BorderLayout(0,12));top.add(heading,BorderLayout.NORTH);top.add(toolbar);dashboard.add(top,BorderLayout.NORTH);
            updateBar.add(updateLabel);updateBar.add(Ui.primary("Mulai ulang sekarang",this::installUpdate));updateBar.add(button("Tunda 30 menit",()->{postponeUntil=System.currentTimeMillis()+30*60_000;countdown=-1;tickUpdate();}));updateBar.setVisible(false);top.add(updateBar,BorderLayout.SOUTH);
            for(int i=1;i<=4;i++){Card c=new Card(i,this);cards.add(c);grid.add(c);}JScrollPane scroll=new JScrollPane(grid);gridScroll=scroll;scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);scroll.getViewport().setBackground(Ui.BG);scroll.getVerticalScrollBar().setUnitIncrement(28);dashboard.add(scroll);
            dashboard.add(Ui.label("Klik layar untuk mengontrol  /  Perbesar untuk membaca detail  /  Ctrl+C dan Ctrl+V untuk teks  /  Klik nama komputer untuk mengganti nama",12,false,Ui.MUTED),BorderLayout.SOUTH);
            focusLayout=new JCheckBox("Fokus klien aktif",true);focusLayout.setOpaque(false);focusLayout.setToolTipText("Pertahankan satu layar besar. Gunakan tombol Fokus untuk memilih klien.");focusLayout.addActionListener(e->{grid.focusEnabled=focusLayout.isSelected();grid.revalidate();grid.repaint();save();});actions.add(focusLayout);
            autoUpdate.setOpaque(false);autoUpdate.setToolTipText("Saat terhubung, klien versi lama otomatis menerima versi komputer utama ini lalu mulai ulang.");autoUpdate.addActionListener(e->save());actions.add(autoUpdate);
            onlineUpdate.setOpaque(false);onlineUpdate.setEnabled(!Updater.repository.isBlank());onlineUpdate.setToolTipText(Updater.repository.isBlank()?"Sumber pembaruan internet belum diatur di versi ini.":"Periksa rilis bertanda tangan di github.com/"+Updater.repository+" saat dibuka dan setiap 6 jam.");onlineUpdate.addActionListener(e->{save();nextOnline=0;});actions.add(onlineUpdate);
            reconnect.setOpaque(false);reconnect.setToolTipText("Jika koneksi putus (Wi-Fi, restart klien), utama mencoba menyambung kembali sampai Anda klik Putus.");reconnect.addActionListener(e->{if(!reconnect.isSelected())for(Card c:cards)if(c.retry!=null){c.stopRetry();c.wanted=false;c.status.setText("Offline");c.status.setForeground(Ui.MUTED);}save();});actions.add(reconnect);
            timer=new javax.swing.Timer(700,e->{grid.refreshFocus();updateCount();cards.forEach(Card::updateStream);});
            if(!preview){Icons.apply(f,Icons.UTAMA);WORK.submit(()->Icons.ensure(Icons.UTAMA,()->""));Toolkit.getDefaultToolkit().addAWTEventListener(e->lastInput=System.currentTimeMillis(),AWTEvent.KEY_EVENT_MASK|AWTEvent.MOUSE_EVENT_MASK|AWTEvent.MOUSE_MOTION_EVENT_MASK|AWTEvent.MOUSE_WHEEL_EVENT_MASK);
                if(justUpdated)updatedUntil=System.currentTimeMillis()+30_000;updateTimer=new javax.swing.Timer(1000,e->tickUpdate());updateTimer.start();load();f.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){saveNow();}});f.add(dashboard);f.setSize(1400,1000);f.setLocationRelativeTo(null);f.setVisible(true);timer.start();}
        }
        static final int MAX_CARDS=200;
        void updateCount(){count.setText(cards.stream().filter(c->c.peer!=null&&c.peer.alive).count()+" / "+cards.size()+" klien online");}
        /** Adds an empty computer at the end of the list and scrolls to it. */
        Card addCard(){
            if(cards.size()>=MAX_CARDS){error(f,new Exception("Maksimal "+MAX_CARDS+" komputer."));return cards.get(cards.size()-1);}
            Card c=new Card(cards.size()+1,this);c.mode=modeCode(Math.max(0,quality.getSelectedIndex()));cards.add(c);grid.add(c);grid.revalidate();grid.repaint();updateCount();
            later(()->{grid.revalidate();c.scrollRectToVisible(new Rectangle(0,0,c.getWidth(),Math.max(1,c.getHeight())));});return c;
        }
        /** "+ Tambah komputer": asks for a name and IP (both optional) and connects right away when an IP and the code are known. */
        void askNewCard(){
            JTextField nameField=new JTextField(22),ipField=new JTextField(22);JPanel form=new JPanel(new GridLayout(0,1,0,6));
            form.add(new JLabel("Nama komputer (boleh dikosongkan), contoh: Kasir 1, Lab-02"));form.add(nameField);form.add(new JLabel("Alamat IP klien (boleh diisi nanti)"));form.add(ipField);
            if(JOptionPane.showConfirmDialog(f,form,"Tambah komputer",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;
            Card c=addCard();String name=Card.cleanName(nameField.getText());if(!name.isEmpty())c.applyName(name);c.ip.setText(ipField.getText().trim());save();
            if(!c.ip.getText().isEmpty()&&password.getPassword().length>=12)c.connect();else c.ip.requestFocusInWindow();
        }
        /** A computer still called "Komputer N" takes the client's Windows computer name when it connects. */
        /** Every 3 s: tell the client this master's name and learn which other masters control it too (v1.9.0 clients). */
        void checkControllers(Card card,Peer p){String me=RemoteServices.hostName();
            WORK.submit(()->{card.others="";
                while(card.peer==p&&p.alive){String found;
                    try{found=string(p.rpc("CONTROLLERS",me)).trim();}catch(Exception e){if(message(e).contains("tidak dikenal")||!p.alive)return;found=card.others;}
                    String now=found;later(()->{if(card.peer!=p||now.equals(card.others))return;boolean joined=!now.isEmpty()&&(card.others.isEmpty()||now.split("\n").length>card.others.split("\n").length);card.others=now;card.showOnline();
                        if(joined)card.notice("Komputer utama lain juga mengontrol klien ini: "+now.replace("\n",", ")+". Kursor dipakai bersama, bergantianlah.");});
                    try{Thread.sleep(3000);}catch(InterruptedException e){return;}}});}
        void checkName(Card card,Peer p){
            if(!card.defaultName())return;
            WORK.submit(()->{String host;try{host=Card.cleanName(string(p.rpc("HOSTNAME")));}catch(Exception e){return;}
                if(host.isEmpty())return;later(()->{if(card.peer==p&&card.defaultName()){card.applyName(host);save();card.notice("Nama diambil dari nama komputer Windows. Klik ✎ Ubah nama untuk menggantinya.");}});});
        }
        void setCardCount(int n){while(cards.size()<n)addCard();while(cards.size()>n&&cards.size()>1)discard(cards.get(cards.size()-1));}
        /** Removes a computer after confirmation; it is disconnected and its open windows are closed. */
        void removeCard(Card card){
            if(cards.size()<=1){error(f,new Exception("Minimal satu komputer harus ada."));return;}
            String detail=card.ip.getText().trim().isEmpty()?"":" ("+card.ip.getText().trim()+")";
            if(!preview&&JOptionPane.showConfirmDialog(f,"Hapus "+card.name+detail+" dari daftar?"+(card.peer!=null?"\nKoneksinya akan diputus.":""),"Hapus komputer",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
            discard(card);save();
        }
        void discard(Card card){
            card.disconnect();for(View v:card.largeViews){Window w=SwingUtilities.getWindowAncestor(v);if(w!=null)w.dispose();}
            cards.remove(card);grid.remove(card);if(grid.preferredFocus==card)grid.preferredFocus=null;if(grid.layoutFocus==card)grid.layoutFocus=null;
            for(int i=0;i<cards.size();i++)cards.get(i).renumber(i+1);grid.revalidate();grid.repaint();updateCount();
        }
        void alertOnce(String text){long now=System.currentTimeMillis();if(now-alerted<5000)return;alerted=now;error(f,new Exception(text));}
        void connectAll(){boolean any=false;for(Card c:cards)if(!c.ip.getText().trim().isEmpty()){any=true;c.connectQuietly(reconnect.isSelected());}
            if(!any)error(f,new Exception("Isi IP minimal satu komputer terlebih dahulu."));else if(remember.isSelected())rememberCode();}
        Properties snapshot(){Properties p=new Properties();
            p.setProperty("cards",Integer.toString(cards.size()));for(Card c:cards){p.setProperty("card."+c.number+".ip",c.ip.getText().trim());p.setProperty("card."+c.number+".name",c.name);}
            p.setProperty("quality",modeCode(Math.max(0,quality.getSelectedIndex())));p.setProperty("focus",Boolean.toString(focusLayout.isSelected()));p.setProperty("reconnect",Boolean.toString(reconnect.isSelected()));p.setProperty("remember",Boolean.toString(remember.isSelected()));p.setProperty("autoUpdate",Boolean.toString(autoUpdate.isSelected()));p.setProperty("onlineUpdate",Boolean.toString(onlineUpdate.isSelected()));
            String code=protectedCode;if(remember.isSelected()&&code!=null)p.setProperty("code",code);return p;}
        boolean loading;
        void save(){if(preview||loading)return;Properties p=snapshot();Path file=settingsFile;SETTINGS.submit(()->{try{Settings.save(file,p);}catch(IOException ignored){}});}
        void saveNow(){if(preview)return;try{Settings.save(settingsFile,snapshot());}catch(IOException ignored){}}
        void load(){loading=true;try{apply(Settings.load(settingsFile));}finally{loading=false;}
            // After an automatic update: restore the typed code and reconnect the clients that were online.
            Properties resume=Updater.takeResume();
            if(resume!=null)SETTINGS.submit(()->{String code=null;try{code=Updater.code(resume);}catch(Exception ignored){}String restored=code;
                later(()->{if(restored!=null&&password.getPassword().length==0)password.setText(restored);
                    for(String n:resume.getProperty("cards","").split(","))try{int i=Integer.parseInt(n.trim())-1;if(i>=0&&i<cards.size())cards.get(i).connectQuietly(true);}catch(NumberFormatException ignored){}});});
        }
        void tickUpdate(){
            long now=System.currentTimeMillis();
            if(!checking&&!installing&&now-lastCheck>15_000){lastCheck=now;checking=true;WORK.submit(()->{String v=Updater.pending();later(()->{checking=false;if(v==null||!v.equals(pendingVersion))countdown=-1;pendingVersion=v;});});}
            if(onlineUpdate.isSelected()&&onlineUpdate.isEnabled()&&!onlineChecking&&!installing&&now>=nextOnline){onlineChecking=true;nextOnline=now+6*3600_000L;
                WORK.submit(()->{String result;boolean found=false;try{String got=Updater.checkOnline();found=got!=null;result=got==null?"Tidak ada versi baru di GitHub.":"Versi v"+got+" diunduh dan tanda tangannya cocok.";}
                    catch(Exception e){result="Cek pembaruan internet gagal: "+message(e);}
                    String text=result+" (terakhir dicek "+new java.text.SimpleDateFormat("HH:mm").format(new Date())+")";boolean staged=found;
                    later(()->{onlineChecking=false;onlineUpdate.setToolTipText(text);if(staged)lastCheck=0;else if(text.contains("gagal"))nextOnline=System.currentTimeMillis()+30*60_000L;});});}
            String v=pendingVersion;
            if(v==null||installing){boolean recent=now<updatedUntil;updateBar.setVisible(recent||installing);if(recent&&!installing){updateLabel.setForeground(Ui.ACCENT);updateLabel.setText("Berhasil diperbarui ke v"+VERSION+".");for(Component c:updateBar.getComponents())if(c!=updateLabel)c.setVisible(false);}return;}
            for(Component c:updateBar.getComponents())c.setVisible(true);updateBar.setVisible(true);updateLabel.setForeground(Ui.WARN);
            boolean inUse=!remotes.isEmpty()||RemoteTools.anyBusy()||dropTransfers.get()>0||now<postponeUntil,idle=now-lastInput>=60_000;
            if(!Updater.canRestart()){updateLabel.setText("Pembaruan v"+v+" ada di folder update. Tutup lalu buka aplikasi untuk memasang.");return;}
            if(inUse||!idle){countdown=-1;updateLabel.setText("Pembaruan v"+v+" siap. "+(now<postponeUntil?"Ditunda; ":"")+"Mulai ulang otomatis setelah 1 menit tidak dipakai (jendela Perbesar/transfer ditutup).");return;}
            countdown=countdown<0?30:countdown-1;updateLabel.setText("Pembaruan v"+v+": mulai ulang otomatis dalam "+countdown+" dtk. Gerakkan mouse untuk menunda.");if(countdown<=0)installUpdate();
        }
        /** Restart into the staged version; code and online clients are restored after the restart. */
        void installUpdate(){
            if(installing)return;if(pendingVersion==null){error(f,new Exception("Belum ada pembaruan di folder update."));return;}installing=true;updateLabel.setText("Memasang pembaruan v"+pendingVersion+"...");saveNow();
            String code=new String(password.getPassword());StringBuilder online=new StringBuilder();for(Card c:cards)if(c.peer!=null||c.wanted){if(online.length()>0)online.append(',');online.append(c.number);}
            WORK.submit(()->{try{Updater.stash(code,Map.of("cards",online.toString()));Updater.restart(List.of());later(()->System.exit(0));}
                catch(Exception e){later(()->{installing=false;countdown=-1;postponeUntil=System.currentTimeMillis()+30*60_000;error(f,new Exception("Gagal memasang pembaruan: "+message(e)));});}});
        }
        final java.util.concurrent.atomic.AtomicInteger dropTransfers=new java.util.concurrent.atomic.AtomicInteger();
        /** Files/folders dropped onto a client screen go to that client's Desktop (all targets in a Kontrol serentak window). */
        void sendFiles(List<Card> targets,List<java.nio.file.Path> items,Component from){
            List<Card> online=targets.stream().filter(c->c.peer!=null).toList();Window owner=SwingUtilities.getWindowAncestor(from);
            if(online.isEmpty()){error(owner,new Exception("Klien belum terhubung."));return;}
            String names=items.stream().limit(3).map(x->String.valueOf(x.getFileName())).reduce((a,b)->a+", "+b).orElse("")+(items.size()>3?" dan "+(items.size()-3)+" lainnya":"");
            if(online.size()>1&&JOptionPane.showConfirmDialog(owner,"Kirim "+names+" ke Desktop "+online.size()+" klien?","Kirim ke beberapa klien",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
            JDialog dialog=new JDialog(owner,"Mengirim ke Desktop klien",Dialog.ModalityType.MODELESS);JLabel label=Ui.label("Menyiapkan "+names+"...",13,false,Ui.TEXT);JProgressBar bar=new JProgressBar(0,1000);bar.setStringPainted(true);bar.setString("0%");
            boolean[] cancelled={false};JButton cancel=button("Batal",()->{cancelled[0]=true;label.setText("Membatalkan...");});
            JPanel body=new JPanel(new BorderLayout(0,12));body.setBorder(BorderFactory.createEmptyBorder(18,20,18,20));body.add(label,BorderLayout.NORTH);body.add(bar);JPanel south=Ui.transparent(new FlowLayout(FlowLayout.RIGHT,0,0));south.add(cancel);body.add(south,BorderLayout.SOUTH);
            dialog.add(body);dialog.setSize(560,170);dialog.setLocationRelativeTo(owner);dialog.setVisible(true);dropTransfers.incrementAndGet();
            WORK.submit(()->{List<String> lines=new ArrayList<>();boolean problems=false;long started=System.nanoTime();
                try{for(Card c:online){Peer p=c.peer;if(p==null){lines.add(c.name+": terputus");problems=true;continue;}
                        try{String desk=Transfer.desktop(p);Set<String> taken=Transfer.names(p,desk);
                            Transfer.Result result=Transfer.upload(p,desk,items,taken,()->cancelled[0],(what,done,total)->{int value=total<=0?1000:(int)(done*1000/total);String text=c.name+"  —  "+what+"  ("+RemoteTools.size(done)+" / "+RemoteTools.size(total)+", "+RemoteTools.speed(done,System.nanoTime()-started)+")";later(()->{label.setText(text);bar.setValue(value);bar.setString(value/10+"%");});});
                            c.notice(result.summary("Desktop klien"));lines.add(c.name+": "+result.details("Desktop ("+desk+")"));problems|=result.worthShowing();}
                        catch(Exception e){lines.add(c.name+": gagal — "+message(e));problems=true;if(cancelled[0])break;}}}
                finally{dropTransfers.decrementAndGet();}
                boolean show=problems||online.size()>1;String report=String.join("\n\n",lines);
                later(()->{dialog.dispose();if(show)JOptionPane.showMessageDialog(owner,report,"Kirim ke Desktop klien",JOptionPane.INFORMATION_MESSAGE);});});
        }
        /** Asks the client whether it runs elevated; older clients simply do not answer this. */
        void checkPrivilege(Card card,Peer p){
            WORK.submit(()->{String level;try{level=string(p.rpc("PRIVILEGE"));}catch(Exception e){return;}
                later(()->{if(card.peer!=p)return;card.privilege=level;card.showOnline();if(card.limited())card.notice("Izin BIASA: klik ke aplikasi Administrator tidak berfungsi. Klik status untuk memperbaiki.");});});
        }
        /** Sends this master's own JAR to an older client (once per client and version). */
        void checkVersion(Card card,Peer p,String host){
            if(!autoUpdate.isSelected())return;
            WORK.submit(()->{String version;try{version=string(p.rpc("VERSION"));}catch(Exception e){if(p.alive)card.notice("Klien versi lama: pasang v"+VERSION+" sekali secara manual agar pembaruan otomatis aktif.");return;}
                if(Updater.compare(VERSION,version)<=0)return;byte[] jar;try{jar=ownJar.call();}catch(Exception e){return;}
                if(!pushed.add(host+" "+VERSION)){card.notice("Klien masih v"+version+". Pembaruan otomatis gagal; perbarui manual.");return;}
                try{card.expectRestart=true;Updater.push(p,jar,VERSION,(done,total)->card.notice("Mengirim pembaruan v"+VERSION+" ke klien: "+(done*100/total)+"%"));card.notice("Klien memasang v"+VERSION+" dan mulai ulang...");}
                catch(Exception e){card.expectRestart=false;if(p.alive)card.notice("Pembaruan klien gagal: "+message(e));}});
        }
        void apply(Properties p){
            int saved;try{saved=Integer.parseInt(p.getProperty("cards","4"));}catch(NumberFormatException e){saved=4;}setCardCount(Math.max(1,Math.min(MAX_CARDS,saved)));
            for(Card c:cards){c.ip.setText(p.getProperty("card."+c.number+".ip",""));c.applyName(p.getProperty("card."+c.number+".name",""));}
            int q=MODE_CODES.indexOf(p.getProperty("quality","AUTO"));quality.setSelectedIndex(Math.max(0,q));
            focusLayout.setSelected(!"false".equals(p.getProperty("focus")));grid.focusEnabled=focusLayout.isSelected();reconnect.setSelected(!"false".equals(p.getProperty("reconnect")));remember.setSelected("true".equals(p.getProperty("remember")));autoUpdate.setSelected(!"false".equals(p.getProperty("autoUpdate")));onlineUpdate.setSelected(!"false".equals(p.getProperty("onlineUpdate")));
            String code=p.getProperty("code");
            if(remember.isSelected()&&code!=null&&!preview)SETTINGS.submit(()->{try{String plain=RemoteServices.un64(RemoteServices.Startup.protect(code,false));
                    later(()->{protectedCode=code;protectedPlain=plain;if(password.getPassword().length==0){password.setText(plain);if(reconnect.isSelected())connectAll();}});}
                catch(Exception e){later(()->{protectedCode=null;count.setText("Kode tersimpan tidak bisa dibuka. Isi kode lagi.");});}});
        }
        /** Stores the code protected by Windows DPAPI for this Windows account. Never written as plain text. */
        void rememberCode(){
            if(!remember.isSelected()){protectedCode=null;protectedPlain=null;save();return;}
            String code=new String(password.getPassword());if(code.length()<12||code.equals(protectedPlain)){save();return;}
            protectedPlain=code;SETTINGS.submit(()->{try{String sealed=RemoteServices.Startup.protect(RemoteServices.b64(code),true);later(()->{if(code.equals(protectedPlain)){protectedCode=sealed;save();}});}
                catch(Exception e){later(()->{protectedPlain=null;protectedCode=null;remember.setSelected(false);save();error(f,new Exception("Kode tidak dapat disimpan: "+message(e)));});}});
        }
        void focus(Card card){if(card.peer==null&&card.image==null){error(f,new IOException("Hubungkan klien ini sebelum memilih fokus."));return;}grid.preferredFocus=card;grid.focusEnabled=true;focusLayout.setSelected(true);grid.refreshFocus();grid.revalidate();grid.repaint();later(()->{card.scrollRectToVisible(new Rectangle(0,0,card.getWidth(),Math.min(card.getHeight(),400)));});}
        void reset(){for(Card c:cards)if(c.peer!=null)c.peer.send("RESET");}
        void open(Card source,boolean group){
            if(source.peer==null){error(f,new Exception("Klien belum terhubung."));return;}
            List<Card> targets=new ArrayList<>();if(group){for(Card c:cards)if(c.selected.isSelected()&&c.peer!=null)targets.add(c);}else targets.add(source);
            String label=group?targets.size()+" klien":source.name+"  ("+source.ip.getText().trim()+")";
            JFrame remote=new JFrame(group?"Kontrol serentak — "+label:"Kontrol individual — "+label);remote.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);remote.setMinimumSize(new Dimension(800,550));Icons.apply(remote,Icons.UTAMA);
            Sender sender=sender(targets);
            View v=new View(()->source.image,sender);v.enlarged=true;v.shortcut=key->{if(key==KeyEvent.VK_C){Peer p=source.peer;if(p!=null)p.shortcut(key);}else for(Card c:targets){Peer p=c.peer;if(p!=null)p.shortcut(key);}};v.drop=items->sendFiles(targets,items,v);source.largeViews.add(v);JScrollPane screen=new JScrollPane(v);screen.getViewport().setBackground(Ui.FIELD);remote.add(screen);
            JPanel bar=Ui.transparent(new Ui.WrapLayout(FlowLayout.LEFT,10,6));bar.add(Ui.label(group?"SERENTAK  /  "+label:"INDIVIDUAL  /  "+label,13,true,Ui.ACCENT));
            JComboBox<String> quality=new JComboBox<>(MODES);quality.setSelectedIndex(Math.max(0,MODE_CODES.indexOf(source.mode)));quality.addActionListener(e->{source.mode=modeCode(quality.getSelectedIndex());source.updateStream();v.requestFocusInWindow();});bar.add(quality);
            JComboBox<String> sizing=new JComboBox<>(new String[]{"Pas jendela","Ukuran asli 1:1","Regangkan"});
            sizing.addActionListener(e->{v.oneToOne=sizing.getSelectedIndex()==1;v.stretch=sizing.getSelectedIndex()==2;v.prepared=null;if(v.oneToOne)quality.setSelectedIndex(2);v.revalidate();v.repaint();source.updateStream();v.requestFocusInWindow();});bar.add(sizing);
            bar.add(shortcutsButton("Tombol khusus",()->sender,v::requestFocusInWindow));
            JButton full=button("Layar penuh",()->{});final boolean[] fullscreen={false};final Rectangle[] previous={null};final int[] previousState={0};
            Runnable toggle=()->{sender.send("RESET");if(!fullscreen[0]){previous[0]=remote.getBounds();previousState[0]=remote.getExtendedState();remote.dispose();remote.setUndecorated(true);remote.setExtendedState(JFrame.NORMAL);remote.setBounds(remote.getGraphicsConfiguration().getBounds());remote.setVisible(true);fullscreen[0]=true;full.setText("Keluar layar penuh");}
                else {remote.dispose();remote.setUndecorated(false);remote.setBounds(previous[0]);remote.setExtendedState(previousState[0]);remote.setVisible(true);fullscreen[0]=false;full.setText("Layar penuh");}v.requestFocusInWindow();source.updateStream();};
            full.addActionListener(e->toggle.run());bar.add(full);v.escape=()->{if(!fullscreen[0])return false;toggle.run();return true;};remote.add(bar,BorderLayout.NORTH);
            remote.getRootPane().registerKeyboardAction(e->{if(fullscreen[0])toggle.run();},KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
            JPanel south=Ui.transparent(new BorderLayout(0,2));
            JLabel info=Ui.label(group?"  Input ke klien yang dipilih saat jendela dibuka. Samakan posisi aplikasi di setiap klien.":"  Input hanya ke komputer ini. Tarik file/folder ke layar: kirim ke Desktop klien.  Ctrl+C / Ctrl+V: teks.  Esc: keluar layar penuh.",12,false,Ui.MUTED);info.setBorder(BorderFactory.createEmptyBorder(8,8,2,8));
            JPanel legend=Ui.transparent(new Ui.WrapLayout(FlowLayout.LEFT,14,2));legend.setBorder(BorderFactory.createEmptyBorder(0,6,8,8));
            JLabel lead=Ui.label("⌨  Pintasan keyboard:",12,true,Ui.ACCENT);lead.setToolTipText("Klik layar klien dulu, lalu tekan salah satu kombinasi ini. Daftar lengkap ada di tombol \"Tombol khusus\".");legend.add(lead);
            for(String item:KEY_LEGEND)legend.add(Ui.label(item,12,false,Ui.MUTED));
            south.add(info,BorderLayout.NORTH);south.add(legend,BorderLayout.SOUTH);remote.add(south,BorderLayout.SOUTH);
            remote.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){later(()->{if(!remote.isDisplayable()){sender.send("RESET");source.largeViews.remove(v);source.updateStream();remotes.remove(remote);}});}});
            remote.setSize(1250,820);remote.setLocationRelativeTo(f);remotes.add(remote);remote.setVisible(true);v.requestFocusInWindow();source.updateStream();
        }
    }
    public static void main(String[] args)throws Exception{
        ImageIO.setUseCache(false);
        if(System.getProperty("java.net.useSystemProxies")==null)System.setProperty("java.net.useSystemProxies","true");// follow the Windows proxy settings for update checks
        if(args.length>0&&args[0].equals("--test")){Tests.run();return;}
        if(args.length>0&&args[0].equals("--preview")){theme();SwingUtilities.invokeAndWait(()->{try{VisualTests.preview();}catch(Exception e){throw new RuntimeException(e);}});return;}
        List<String> list=Arrays.asList(args);justUpdated=list.contains(Updater.UPDATED_FLAG);
        // A newer, verified JAR waiting in the update folder is installed before the window opens.
        if(!justUpdated&&!list.contains("--request-admin")&&!list.contains("--shortcut")&&!list.contains("--test")&&!list.contains("--preview")){try{if(Updater.pending()!=null&&Updater.canRestart()){Updater.restart(list);System.exit(0);}}catch(Exception ignored){}}
        if(args.length>1&&args[0].equals("--shortcut")){
            String kind=args[1].startsWith("klien")?Icons.KLIEN:Icons.UTAMA,arguments=args[1].equals("klien-admin")?"--request-admin":kind.equals(Icons.KLIEN)?"client":"";
            try{Icons.createShortcut(kind,arguments);JOptionPane.showMessageDialog(null,"Pintasan \""+Icons.name(kind)+"\" dibuat di Desktop dan menu Start.","LAN Control 5",JOptionPane.INFORMATION_MESSAGE);}
            catch(Exception e){JOptionPane.showMessageDialog(null,"Pintasan gagal dibuat: "+message(e),"LAN Control 5",JOptionPane.ERROR_MESSAGE);System.exit(1);}
            System.exit(0);}
        if(args.length>0&&args[0].equals("--request-admin")){try{RemoteServices.Startup.elevate();}catch(Exception e){JOptionPane.showMessageDialog(null,"Tidak berhasil membuka klien administrator. UAC mungkin dibatalkan.\n"+e.getMessage());System.exit(1);}return;}
        // One-click Administrator setup (Pasang-Akses-Admin.bat): registers the elevated login task from the already-saved
        // code, WITHOUT opening a client window or binding the port - so it works even while the client is already running.
        if(args.length>0&&args[0].equals("--pasang-admin")){
            try{if(!RemoteServices.Startup.isAdmin()){RemoteServices.Startup.elevate("--pasang-admin");return;}   // ask UAC once, elevated run continues below
                if(!RemoteServices.Startup.hasCode())throw new Exception("Buka klien dulu, isi kode koneksi, dan centang \"Ingat kode\" agar kode tersimpan.");
                String code=RemoteServices.Startup.load();RemoteServices.Startup.enableAdmin(code);
                JOptionPane.showMessageDialog(null,"Mode Administrator aktif.\nSetelah PC dinyalakan ulang, klien terbuka SEBAGAI ADMINISTRATOR sendiri\ndan komputer utama mendapat kontrol penuh.\n\nSilakan nyalakan ulang PC klien ini.","LAN Control 5",JOptionPane.INFORMATION_MESSAGE);}
            catch(Exception e){JOptionPane.showMessageDialog(null,"Gagal memasang mode Administrator:\n"+message(e),"LAN Control 5",JOptionPane.ERROR_MESSAGE);System.exit(1);}
            System.exit(0);}
        theme();later(()->{if(args.length>0&&args[0].equals("client"))new Agent(list.contains("--auto"),list.contains("--resume"),list.contains("--upgrade-startup"));else new Controller();});
    }
    static class Tests {
        static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
        static void run()throws Exception{
            ExecutorService ex=Executors.newSingleThreadExecutor();
            try(ServerSocket ss=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
                Future<?> server=ex.submit(()->{try(Socket s=ss.accept();Wire w=new Wire(s,"test-password-long",true)){check(string(w.read()).equals(HELLO),"hello");w.send(command(READY));for(int i=0;i<50;i++){check(string(w.read()).equals("KEYDOWN "+i),"ordering");w.send(command("FRAME "+i));}}catch(Exception e){throw new RuntimeException(e);}});
                try(Socket s=new Socket(InetAddress.getLoopbackAddress(),ss.getLocalPort());Wire w=new Wire(s,"test-password-long",false)){w.send(command(HELLO));check(string(w.read()).equals(READY),"ready");for(int i=0;i<50;i++){w.send(command("KEYDOWN "+i));check(string(w.read()).equals("FRAME "+i),"duplex");}}
                server.get(20,TimeUnit.SECONDS);
            }
            try(ServerSocket ss=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
                Future<Boolean> rejected=ex.submit(()->{try(Socket s=ss.accept();Wire w=new Wire(s,"correct-password-long",true)){w.read();return false;}catch(AEADBadTagException expected){return true;}catch(Exception e){throw new RuntimeException(e);}});
                try(Socket s=new Socket(InetAddress.getLoopbackAddress(),ss.getLocalPort());Wire w=new Wire(s,"incorrect-password-long",false)){w.send(command(HELLO));}
                check(rejected.get(20,TimeUnit.SECONDS),"wrong password rejection");
            }finally{ex.shutdownNow();}
            check(!Arrays.equals(Wire.nonce(1,0),Wire.nonce(2,0)),"direction nonces");
            check(!Arrays.equals(Wire.nonce(1,0),Wire.nonce(1,1)),"sequence nonces");
            System.out.println("PASS: authenticated duplex protocol, 50 ordered commands, wrong password rejection, unique directional nonces.");
            ServiceTests.run();
            VisualTests.test();
            LatencyTests.run();
            ClipboardTests.run();
            PerformanceTests.run();
            UpdateTests.run();
            UpdaterTests.run();
            IconTests.run();
            TransferTests.run();
            PrivilegeTests.run();
            StreamTests.run();
            MultiMasterTests.run();
            KeyboardShortcutTests.run();
            ThemeTests.run();
            ClientStartupTests.run();
        }
    }
}
