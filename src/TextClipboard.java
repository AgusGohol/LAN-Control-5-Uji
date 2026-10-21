import java.awt.Toolkit;
import java.awt.datatransfer.*;
import java.io.IOException;
import java.util.concurrent.*;

class TextClipboard {
    static final int LIMIT=1024*1024;
    /** Shared marker: also present in error text of clients older than v1.5.0. */
    static final String NOT_TEXT="tidak berisi teks";
    static final ExecutorService TRANSFER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"clipboard-transfer");t.setDaemon(true);return t;});
    static String read()throws Exception{return read(Toolkit.getDefaultToolkit().getSystemClipboard());}
    static String read(Clipboard clipboard)throws Exception{
        String text=peek(clipboard);if(text==null)throw new IOException("Clipboard "+NOT_TEXT+". Salin teks terlebih dahulu.");validate(text);return text;
    }
    /** Current text or null when the clipboard holds no text (file, image). Retries while another app holds the clipboard. */
    static String peek()throws Exception{return peek(Toolkit.getDefaultToolkit().getSystemClipboard());}
    static String peek(Clipboard clipboard)throws Exception{
        for(int i=0;;i++)try{
            if(!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor))return null;
            return (String)clipboard.getData(DataFlavor.stringFlavor);
        }catch(IllegalStateException|UnsupportedFlavorException e){if(i==5)throw new IOException("Clipboard sedang dipakai aplikasi lain. Coba kembali.",e);Thread.sleep(40);}
    }
    /** After a copy shortcut: wait until the clipboard changes (slow apps such as Word/browsers), up to the timeout. */
    static String awaitChange(String before,long timeoutMillis)throws Exception{return awaitChange(Toolkit.getDefaultToolkit().getSystemClipboard(),before,timeoutMillis);}
    static String awaitChange(Clipboard clipboard,String before,long timeoutMillis)throws Exception{
        long deadline=System.nanoTime()+timeoutMillis*1_000_000L;
        while(System.nanoTime()<deadline){
            Thread.sleep(60);String now;try{now=peek(clipboard);}catch(IOException busy){continue;}
            if(now!=null&&!now.equals(before)){validate(now);return now;}
            if(now==null&&before!=null)break;
        }
        String text=peek(clipboard);if(text==null)throw new IOException("Clipboard klien "+NOT_TEXT+" (file/gambar disalin).");validate(text);return text;
    }
    static void validate(String text)throws IOException{if(text.length()>LIMIT)throw new IOException("Teks clipboard maksimal 1 juta karakter.");}
    static void write(String text)throws Exception{write(Toolkit.getDefaultToolkit().getSystemClipboard(),text);}
    static void write(Clipboard clipboard,String text)throws Exception{
        validate(text);
        for(int i=0;;i++)try{clipboard.setContents(new StringSelection(text),null);return;}catch(IllegalStateException e){if(i==5)throw new IOException("Clipboard sedang dipakai aplikasi lain. Coba kembali.",e);Thread.sleep(40);}
    }
}
