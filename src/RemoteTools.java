import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/** File manager and power/clipboard tools for one client. Always uses the card's current connection. */
class RemoteTools {
    final LanControl.Card card;final JFrame f=new JFrame();final JTextField folder=new JTextField();
    final FileModel model=new FileModel();final JTable table=new JTable(model);final TableRowSorter<FileModel> sorter=new TableRowSorter<>(model);
    final JLabel status=new JLabel("Siap");final JProgressBar progress=new JProgressBar(0,1000);final JButton cancel;final JTextArea clipboard=new JTextArea(6,40);
    volatile boolean busy=false,cancelled=false;
    interface Job {void run()throws Exception;}
    static final Set<RemoteTools> OPEN=java.util.concurrent.ConcurrentHashMap.newKeySet();
    static boolean anyBusy(){for(RemoteTools t:OPEN)if(t.busy)return true;return false;}
    record Entry(String path,String name,String kind,long size,long modified) {boolean dir(){return !kind.equals("File");}}

    static class FileModel extends AbstractTableModel {
        final String[] columns={"Nama","Jenis","Ukuran","Diubah"};List<Entry> rows=new ArrayList<>();
        public int getRowCount(){return rows.size();}public int getColumnCount(){return columns.length;}public String getColumnName(int c){return columns[c];}
        public Class<?> getColumnClass(int c){return c>=2?Long.class:String.class;}
        public Object getValueAt(int r,int c){Entry e=rows.get(r);return switch(c){case 0->e.name();case 1->e.kind();case 2->e.dir()?-1L:e.size();default->e.modified();};}
        void set(List<Entry> list){rows=new ArrayList<>(list);fireTableDataChanged();}
    }
    static class PaddedRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable t,Object v,boolean selected,boolean focus,int row,int column){super.getTableCellRendererComponent(t,v,selected,focus,row,column);setBorder(BorderFactory.createEmptyBorder(0,10,0,10));return this;}
    }
    static String size(long bytes){
        if(bytes<0)return "";if(bytes<1024)return bytes+" B";String[] units={"KB","MB","GB","TB"};double v=bytes;int u=-1;while(v>=1024&&u<units.length-1){v/=1024;u++;}
        return String.format(Locale.forLanguageTag("id-ID"),v<10?"%.1f %s":"%.0f %s",v,units[u]);
    }
    static String date(long millis){return millis<=0?"":new SimpleDateFormat("dd-MM-yyyy HH:mm").format(new Date(millis));}
    static String speed(long bytes,long nanos){return size((long)(bytes/Math.max(.001,nanos/1e9)))+"/dtk";}
    /** Client paths are Windows paths; join without depending on the master's OS. */
    static String child(String base,String name){if(base.endsWith("\\")||base.endsWith("/"))return base+name;return base+(base.contains("/")&&!base.contains("\\")?"/":"\\")+name;}
    static boolean absolute(String s){return s.matches("^[A-Za-z]:[\\\\/].*")||s.startsWith("\\\\")||s.startsWith("/");}
    static String parent(String path){String p=path.replaceAll("[\\\\/]+$","");int i=Math.max(p.lastIndexOf('\\'),p.lastIndexOf('/'));if(i<0)return "";String up=p.substring(0,i);if(up.matches("^[A-Za-z]:$"))return up+"\\";return up.isEmpty()?"/":up;}
    static String fileName(String path){String p=path.replaceAll("[\\\\/]+$","");int i=Math.max(p.lastIndexOf('\\'),p.lastIndexOf('/'));return i<0?p:p.substring(i+1);}
    /** "laporan.pdf" -> "laporan (2).pdf" when the name is taken (case-insensitive like Windows). */
    static String unique(String name,Set<String> taken){
        if(!taken.contains(name.toLowerCase(Locale.ROOT)))return name;int dot=name.lastIndexOf('.');String stem=dot>0?name.substring(0,dot):name,ext=dot>0?name.substring(dot):"";
        for(int i=2;;i++){String candidate=stem+" ("+i+")"+ext;if(!taken.contains(candidate.toLowerCase(Locale.ROOT)))return candidate;}
    }
    static List<Entry> parse(String data){
        List<Entry> rows=new ArrayList<>();
        for(String line:data.split("\n")){if(line.isEmpty())continue;String[] p=line.split("\t");String path=RemoteServices.un64(p[0]);boolean dir=p[1].equals("D");String name=fileName(path);
            boolean drive=name.isEmpty()||path.matches("^[A-Za-z]:[\\\\/]?$");long size=p.length>2?Long.parseLong(p[2]):0,modified=p.length>3?Long.parseLong(p[3]):0;
            rows.add(new Entry(path,drive?path:name,drive?"Drive":dir?"Folder":"File",size,modified));}
        return rows;
    }

    RemoteTools(LanControl.Card card){
        this.card=card;f.setTitle("File & akses — "+card.name+"  ("+card.ip.getText().trim()+")");f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);Icons.apply(f,Icons.UTAMA);
        JPanel root=new JPanel(new BorderLayout(8,8));root.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        JPanel nav=new JPanel(new BorderLayout(6,6));folder.setToolTipText("Alamat folder di klien. Tekan Enter untuk membuka.");folder.addActionListener(e->refresh());nav.add(folder);
        JPanel navButtons=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));navButtons.add(b("Buka / segarkan",this::refresh));navButtons.add(b("Naik",()->{String s=folder.getText().trim();if(!s.isBlank()){folder.setText(parent(s));refresh();}}));navButtons.add(b("Drive",()->{folder.setText("");refresh();}));nav.add(navButtons,BorderLayout.EAST);
        JPanel files=new JPanel(new BorderLayout(6,6));files.add(nav,BorderLayout.NORTH);
        table.setRowSorter(sorter);table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);table.setRowHeight(28);table.setFillsViewportHeight(true);table.setAutoCreateRowSorter(false);
        sorter.setComparator(0,String.CASE_INSENSITIVE_ORDER);sorter.setComparator(1,Comparator.comparingInt((String k)->k.equals("Drive")?0:k.equals("Folder")?1:2));
        sorter.setSortKeys(List.of(new RowSorter.SortKey(1,SortOrder.ASCENDING),new RowSorter.SortKey(0,SortOrder.ASCENDING)));
        DefaultTableCellRenderer sizes=new PaddedRenderer(){protected void setValue(Object v){setText(RemoteTools.size((Long)v));}};sizes.setHorizontalAlignment(SwingConstants.RIGHT);table.setDefaultRenderer(String.class,new PaddedRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(sizes);table.getColumnModel().getColumn(3).setCellRenderer(new PaddedRenderer(){protected void setValue(Object v){setText(RemoteTools.date((Long)v));}});
        int[] widths={430,90,110,150};for(int i=0;i<4;i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2){List<Entry> s=selection();if(s.size()==1&&s.get(0).dir()){folder.setText(s.get(0).path());refresh();}}}});
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"open-folder");table.getActionMap().put("open-folder",new AbstractAction(){public void actionPerformed(ActionEvent e){List<Entry> s=selection();if(s.size()==1&&s.get(0).dir()){folder.setText(s.get(0).path());refresh();}}});
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE,0),"up");table.getActionMap().put("up",new AbstractAction(){public void actionPerformed(ActionEvent e){String s=folder.getText().trim();if(!s.isBlank()&&!busy){folder.setText(parent(s));refresh();}}});
        JScrollPane tableScroll=new JScrollPane(table);TransferHandler drop=new TransferHandler(){
            public boolean canImport(TransferSupport s){return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);}
            @SuppressWarnings("unchecked") public boolean importData(TransferSupport s){if(!canImport(s))return false;try{List<File> list=(List<File>)s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);upload(list.stream().map(File::toPath).toList());return true;}catch(Exception e){LanControl.error(f,e);return false;}}};
        table.setTransferHandler(drop);tableScroll.setTransferHandler(drop);files.add(tableScroll);
        JPanel fileActions=new JPanel(new Ui.WrapLayout(FlowLayout.LEFT,6,4));
        fileActions.add(b("Kirim file / folder",this::chooseUpload));fileActions.add(b("Ambil file",this::download));
        fileActions.add(b("Buka di klien",()->{List<Entry> s=selection();if(s.size()==1)run(()->peer().rpc("OPEN",s.get(0).path()));else message("Pilih satu file/folder untuk dibuka.");}));
        fileActions.add(b("Folder baru",this::makeFolder));fileActions.add(b("Ubah nama",this::rename));fileActions.add(b("Hapus",this::delete));
        JLabel hint=new JLabel("Tip: tarik file atau folder dari Windows Explorer ke daftar ini (atau ke layar klien) untuk mengirim. Ctrl/Shift+klik untuk memilih beberapa.");hint.setForeground(Ui.MUTED);hint.setFont(new Font(Ui.FONT,Font.PLAIN,13));fileActions.add(hint);files.add(fileActions,BorderLayout.SOUTH);
        JPanel access=new JPanel();access.setLayout(new BoxLayout(access,BoxLayout.Y_AXIS));
        access.add(new JLabel("Clipboard teks — salin manual sesuai kebutuhan"));access.add(new JScrollPane(clipboard));JPanel clipButtons=new JPanel();
        clipButtons.add(b("Kirim teks ke clipboard klien",()->{String s=clipboard.getText();run(()->peer().rpc("CLIP_SET",s));}));
        clipButtons.add(b("Ambil clipboard klien",()->run(()->{String s=LanControl.string(peer().rpc("CLIP_GET"));LanControl.later(()->clipboard.setText(s));})));
        clipButtons.add(b("Salin teks ke komputer utama",()->Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(clipboard.getText()),null)));access.add(clipButtons);
        access.add(b("Buka aplikasi / file dari alamat di klien",()->{String s=ask("Alamat lengkap aplikasi atau file di klien:","C:\\Windows\\System32\\notepad.exe");if(s!=null)run(()->peer().rpc("OPEN",s));}));
        JPanel power=new JPanel();power.add(b("Kunci",()->power("LOCK","Kunci komputer klien? Kontrol layar akan terhenti.")));power.add(b("Restart",()->power("RESTART","Restart klien dalam 30 detik? Simpan pekerjaan klien dahulu.")));power.add(b("Shutdown",()->power("SHUTDOWN","Matikan klien dalam 30 detik? Simpan pekerjaan klien dahulu.")));power.add(b("Batal shutdown",()->run(()->peer().rpc("CANCEL_POWER"))));access.add(power);
        access.add(new JLabel("  Izin mengikuti akun Windows klien. UAC dan layar login belum didukung."));
        JTabbedPane tabs=new JTabbedPane();tabs.addTab("File klien",files);tabs.addTab("Clipboard & komputer",access);root.add(tabs);
        JPanel bottom=new JPanel(new BorderLayout(10,0));progress.setStringPainted(true);progress.setVisible(false);progress.setPreferredSize(new Dimension(260,24));
        cancel=LanControl.button("Batal transfer",()->{cancelled=true;status.setText("Membatalkan...");});cancel.setVisible(false);
        JPanel right=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));right.add(progress);right.add(cancel);bottom.add(status);bottom.add(right,BorderLayout.EAST);root.add(bottom,BorderLayout.SOUTH);
        OPEN.add(this);f.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){OPEN.remove(RemoteTools.this);}});f.add(root);f.setSize(1080,680);f.setLocationRelativeTo(null);f.setVisible(true);refresh();
    }
    LanControl.Peer peer()throws IOException{LanControl.Peer p=card.peer;if(p==null||!p.alive)throw new IOException("Klien terputus. Tunggu sampai terhubung kembali lalu klik Buka / segarkan.");return p;}
    JButton b(String text,Runnable r){return LanControl.button(text,()->{if(!busy)r.run();});}
    String ask(String message,String initial){String s=(String)JOptionPane.showInputDialog(f,message,"File & akses",JOptionPane.PLAIN_MESSAGE,null,null,initial);return s==null||s.isBlank()?null:s.trim();}
    boolean confirm(String message){return JOptionPane.showConfirmDialog(f,message,"Konfirmasi untuk klien ini",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION;}
    void message(String text){JOptionPane.showMessageDialog(f,text);}
    List<Entry> selection(){List<Entry> list=new ArrayList<>();for(int row:table.getSelectedRows())list.add(model.rows.get(table.convertRowIndexToModel(row)));return list;}
    Set<String> names(){Set<String> taken=new HashSet<>();for(Entry e:model.rows)taken.add(e.name().toLowerCase(Locale.ROOT));return taken;}
    String current(){return folder.getText().trim();}
    void run(Job job){if(busy)return;busy=true;cancelled=false;status.setText("Memproses...");LanControl.WORK.submit(()->{try{job.run();LanControl.later(()->{if(status.getText().equals("Memproses..."))status.setText("Selesai");});}catch(Exception e){LanControl.error(f,e);LanControl.later(()->status.setText("Gagal: "+LanControl.message(e)));}finally{LanControl.later(()->{busy=false;progress.setVisible(false);cancel.setVisible(false);});}});}
    void refresh(){run(this::list);}
    void list()throws Exception{
        // Snapshot on EDT, never manipulate Swing widgets from worker threads.
        String[] path=new String[1];SwingUtilities.invokeAndWait(()->path[0]=current());
        List<Entry> rows=parse(LanControl.string(peer().rpc("LIST",path[0])));
        LanControl.later(()->{model.set(rows);long files=rows.stream().filter(e->!e.dir()).count();status.setText(path[0].isBlank()?rows.size()+" drive":(rows.size()-files)+" folder, "+files+" file"+(rows.size()>=RemoteServices.LIST_LIMIT?"  (dibatasi "+RemoteServices.LIST_LIMIT+" item)":""));});
    }
    void power(String op,String message){if(confirm(message))run(()->{String result=LanControl.string(peer().rpc(op));LanControl.later(()->JOptionPane.showMessageDialog(f,result));});}
    void makeFolder(){
        String base=current();String s=ask(base.isBlank()?"Alamat lengkap folder baru di klien (contoh C:\\Data\\Baru):":"Nama folder baru di "+base+":",base.isBlank()?"C:\\":"Folder baru");if(s==null)return;
        if(!absolute(s)&&base.isBlank()){message("Buka sebuah folder terlebih dahulu atau tulis alamat lengkap.");return;}
        String target=absolute(s)?s:child(base,s);run(()->{peer().rpc("MKDIR",target);list();});
    }
    void rename(){
        List<Entry> s=selection();if(s.size()!=1){message("Pilih satu file/folder untuk diubah namanya.");return;}Entry e=s.get(0);if(e.kind().equals("Drive"))return;
        String dest=ask("Nama baru (atau alamat lengkap untuk memindahkan):",e.name());if(dest==null||dest.equals(e.name()))return;
        String target=absolute(dest)?dest:child(parent(e.path()),dest);run(()->{peer().rpc("RENAME",e.path(),target);list();});
    }
    void delete(){
        List<Entry> s=selection().stream().filter(e->!e.kind().equals("Drive")).toList();if(s.isEmpty()){message("Pilih file/folder yang akan dihapus.");return;}
        StringBuilder names=new StringBuilder();for(int i=0;i<Math.min(8,s.size());i++)names.append("\n• ").append(s.get(i).name());if(s.size()>8)names.append("\n• ... dan ").append(s.size()-8).append(" lainnya");
        if(!confirm("Hapus permanen "+s.size()+" item di klien?"+names+"\n\nFolder hanya dapat dihapus jika kosong."))return;
        run(()->{LanControl.Peer p=peer();List<String> failed=new ArrayList<>();for(Entry e:s)try{p.rpc("DELETE",e.path());}catch(IOException x){failed.add(e.name()+": "+x.getMessage());}
            list();if(!failed.isEmpty())LanControl.later(()->message("Sebagian tidak terhapus:\n"+String.join("\n",failed)));});
    }
    void chooseUpload(){
        if(current().isBlank()){message("Buka folder tujuan di klien terlebih dahulu (klik dua kali drive/folder).");return;}
        JFileChooser chooser=new JFileChooser();chooser.setMultiSelectionEnabled(true);chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);chooser.setDialogTitle("Pilih file atau folder untuk dikirim ke "+current());if(chooser.showOpenDialog(f)!=JFileChooser.APPROVE_OPTION)return;
        upload(Arrays.stream(chooser.getSelectedFiles()).map(File::toPath).toList());
    }
    void showTransfer(){progress.setValue(0);progress.setString("0%");progress.setVisible(true);cancel.setVisible(true);}
    void progress(String label,long done,long total,long started){int value=total<=0?1000:(int)(done*1000/total);LanControl.later(()->{progress.setValue(value);progress.setString(value/10+"%");status.setText(label+"  —  "+size(done)+" / "+size(total)+"  •  "+speed(done,System.nanoTime()-started));});}
    void checkCancel()throws IOException{if(cancelled)throw new IOException("Transfer dibatalkan.");if(!f.isDisplayable())throw new IOException("Transfer dibatalkan karena jendela ditutup.");}
    void upload(List<Path> chosen){
        if(busy){message("Tunggu proses saat ini selesai.");return;}
        String base=current();if(base.isBlank()){message("Buka folder tujuan di klien terlebih dahulu (klik dua kali drive/folder).");return;}
        if(chosen.isEmpty())return;Set<String> taken=names();showTransfer();
        run(()->{LanControl.Peer p=peer();long started=System.nanoTime();
            Transfer.Result result=Transfer.upload(p,base,chosen,taken,()->cancelled||!f.isDisplayable(),(label,done,total)->progress(label,done,total,started));
            list();LanControl.later(()->{status.setText(result.summary(base));if(result.worthShowing())message(result.details(base));});
        });
    }
    void download(){
        List<Entry> s=selection().stream().filter(e->!e.dir()).toList();if(s.isEmpty()){message("Pilih satu atau beberapa file untuk diambil. Folder belum dapat diambil sekaligus.");return;}
        List<Path> targets=new ArrayList<>();
        if(s.size()==1){JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new File(s.get(0).name()));if(chooser.showSaveDialog(f)!=JFileChooser.APPROVE_OPTION)return;
            Path target=chooser.getSelectedFile().toPath().toAbsolutePath();if(Files.exists(target)&&!confirm("Ganti file lokal ini?\n"+target))return;targets.add(target);}
        else {JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);chooser.setDialogTitle("Pilih folder untuk menyimpan "+s.size()+" file");if(chooser.showSaveDialog(f)!=JFileChooser.APPROVE_OPTION)return;
            Path dir=chooser.getSelectedFile().toPath().toAbsolutePath();Set<String> taken=new HashSet<>();try(var existing=Files.list(dir)){existing.forEach(x->taken.add(x.getFileName().toString().toLowerCase(Locale.ROOT)));}catch(IOException ignored){}
            for(Entry e:s){String name=unique(e.name(),taken);taken.add(name.toLowerCase(Locale.ROOT));targets.add(dir.resolve(name));}}
        showTransfer();
        run(()->{LanControl.Peer p=peer();long total=0;for(Entry e:s)total+=e.size();long received=0,started=System.nanoTime();
            for(int i=0;i<s.size();i++){Entry e=s.get(i);Path target=targets.get(i);String label="Ambil "+(i+1)+"/"+s.size()+": "+e.name();
                long size=Long.parseLong(LanControl.string(p.rpc("SIZE",e.path())));total+=size-e.size();
                Path temp=Files.createTempFile(target.getParent(),".lan5-",".part");boolean saved=false;
                try{try(OutputStream out=Files.newOutputStream(temp)){long offset=0;while(offset<size){checkCancel();byte[] data=p.rpc("GET",e.path(),Long.toString(offset),Integer.toString((int)Math.min(RemoteServices.CHUNK,size-offset)));if(data.length==0)throw new IOException("File klien berubah saat diambil: "+e.name());out.write(data);offset+=data.length;received+=data.length;progress(label,received,total,started);}}
                    Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);saved=true;
                }finally{if(!saved)Files.deleteIfExists(temp);}
            }
            String done="Tersimpan "+s.size()+" file"+(s.size()==1?" di "+targets.get(0):" ke "+targets.get(0).getParent());LanControl.later(()->status.setText(done));
        });
    }
}
