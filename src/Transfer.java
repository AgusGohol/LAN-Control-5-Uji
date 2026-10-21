import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Uploads files and whole folders to a client folder over the encrypted session. Used by File & akses and by dropping onto a client screen. */
class Transfer {
    interface Listener {void progress(String label,long done,long total);}
    static final class Result {
        int files,folders;final List<String> renamed=new ArrayList<>(),failed=new ArrayList<>();
        String summary(String target){return "Terkirim "+files+" file"+(folders>0?" dan "+folders+" folder":"")+" ke "+target;}
        String details(String target){StringBuilder b=new StringBuilder(summary(target));
            if(!renamed.isEmpty())b.append("\n\nNama sudah ada, disimpan dengan nama baru:\n").append(String.join("\n",renamed));
            if(!failed.isEmpty())b.append("\n\nGagal:\n").append(String.join("\n",failed.subList(0,Math.min(20,failed.size())))).append(failed.size()>20?"\n... dan "+(failed.size()-20)+" lainnya":"");
            return b.toString();}
        boolean worthShowing(){return !renamed.isEmpty()||!failed.isEmpty();}
    }
    /** Desktop folder of the logged-in client user (falls back to an error for clients older than v1.6.1). */
    static String desktop(LanControl.Peer peer)throws Exception{
        try{return LanControl.string(peer.rpc("DESKTOP"));}catch(IOException e){if(String.valueOf(e.getMessage()).contains("tidak dikenal"))throw new IOException("Klien belum v1.6.1. Tunggu klien diperbarui otomatis, lalu coba lagi.");throw e;}
    }
    static Set<String> names(LanControl.Peer peer,String folder)throws Exception{
        Set<String> taken=new HashSet<>();for(RemoteTools.Entry e:RemoteTools.parse(LanControl.string(peer.rpc("LIST",folder))))taken.add(e.name().toLowerCase(Locale.ROOT));return taken;
    }
    static long size(List<Path> items)throws IOException{
        long total=0;for(Path item:items){if(Files.isDirectory(item)){try(var walk=Files.walk(item)){for(Path p:walk.toList())if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))total+=Files.size(p);}}else if(Files.isRegularFile(item))total+=Files.size(item);}
        return total;
    }
    /**
     * Sends every item into {@code base}. Top-level names that already exist are renamed "name (2)"; nothing is overwritten.
     * Throws only when cancelled; individual failures are collected in the result.
     */
    static Result upload(LanControl.Peer peer,String base,List<Path> items,Set<String> taken,BooleanSupplier cancelled,Listener listener)throws Exception{
        Result result=new Result();long total=size(items);long[] sent={0};int[] index={0};int count=items.size();
        for(Path item:items){
            if(cancelled.getAsBoolean())throw new IOException("Transfer dibatalkan.");
            index[0]++;String original=item.getFileName()==null?item.toString():item.getFileName().toString(),name=RemoteTools.unique(original,taken);taken.add(name.toLowerCase(Locale.ROOT));
            String target=RemoteTools.child(base,name),prefix=count>1?"Kirim "+index[0]+"/"+count+": ":"Kirim: ";
            if(!name.equals(original))result.renamed.add(original+"  →  "+name);
            if(Files.isDirectory(item))folder(peer,item,target,result,sent,total,cancelled,listener,prefix);
            else if(Files.isRegularFile(item))file(peer,item,target,name,result,sent,total,cancelled,listener,prefix);
            else result.failed.add(original+": bukan file atau folder biasa");
        }
        return result;
    }
    static void folder(LanControl.Peer peer,Path local,String remote,Result result,long[] sent,long total,BooleanSupplier cancelled,Listener listener,String prefix)throws Exception{
        try{peer.rpc("MKDIR",remote);result.folders++;}catch(IOException e){result.failed.add(local.getFileName()+": "+e.getMessage());return;}
        List<Path> children;try(var list=Files.list(local)){children=list.sorted().toList();}catch(IOException e){result.failed.add(local+": "+e.getMessage());return;}
        for(Path c:children){
            if(cancelled.getAsBoolean())throw new IOException("Transfer dibatalkan.");
            String target=RemoteTools.child(remote,c.getFileName().toString());
            if(Files.isDirectory(c,LinkOption.NOFOLLOW_LINKS))folder(peer,c,target,result,sent,total,cancelled,listener,prefix);
            else if(Files.isRegularFile(c,LinkOption.NOFOLLOW_LINKS))file(peer,c,target,c.getFileName().toString(),result,sent,total,cancelled,listener,prefix);
        }
    }
    static void file(LanControl.Peer peer,Path local,String remote,String name,Result result,long[] sent,long total,BooleanSupplier cancelled,Listener listener,String prefix)throws Exception{
        String id;try{id=LanControl.string(peer.rpc("PUT_BEGIN",remote));}catch(IOException e){result.failed.add(name+": "+e.getMessage());sent[0]+=Files.size(local);return;}
        boolean done=false;
        try(InputStream input=Files.newInputStream(local)){byte[] buf=new byte[RemoteServices.CHUNK];int n;
            while((n=input.readNBytes(buf,0,buf.length))>0){if(cancelled.getAsBoolean())throw new IOException("Transfer dibatalkan.");
                peer.rpc("PUT_CHUNK",id,Base64.getEncoder().encodeToString(Arrays.copyOf(buf,n)));sent[0]+=n;listener.progress(prefix+name,sent[0],total);}
            peer.rpc("PUT_END",id);done=true;result.files++;listener.progress(prefix+name,sent[0],total);
        }catch(IOException e){if(cancelled.getAsBoolean()||!peer.alive)throw e;result.failed.add(name+": "+e.getMessage());}
        finally{if(!done&&peer.alive)try{peer.rpc("PUT_CANCEL",id);}catch(Exception ignored){}}
    }
}
