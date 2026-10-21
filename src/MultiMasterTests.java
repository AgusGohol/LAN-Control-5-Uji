import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/** v1.9.0: several masters controlling one client at the same time. Never touches the real keyboard or mouse. */
class MultiMasterTests {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static class Recorder extends LanControl.Input {
        final List<String> log=new ArrayList<>();
        Recorder(){super((Void)null);}
        void press(int code,boolean key){log.add((key?"press ":"click ")+code);}
        void lift(int code,boolean key){log.add((key?"release ":"unclick ")+code);}
        List<String> take(){List<String> l=new ArrayList<>(log);log.clear();return l;}
    }
    static void run()throws Exception{
        sharedKeys();names();concurrentMasters();updateGuard();
        System.out.println("PASS: v1.9.0 shared control: "+LanControl.MAX_CONTROLLERS+" masters at once (one more refused), own video/input per master, shared keys held until the last master lets go, other-master names, one update upload at a time.");
    }
    static void sharedKeys(){
        Recorder in=new Recorder();LanControl.Hands a=new LanControl.Hands(),b=new LanControl.Hands();
        in.apply("KEYDOWN 16",a);in.apply("KEYDOWN 16",b);in.take();
        in.apply("KEYUP 16",b);check(in.take().isEmpty(),"Shift stays down while the other master still holds it");
        in.apply("KEYUP 16",a);check(in.take().equals(List.of("release 16")),"Shift released when the last master lets go");
        in.apply("KEYDOWN 65",a);in.apply("KEYDOWN 65",a);check(in.take().equals(List.of("press 65","press 65")),"auto-repeat still types");
        in.apply("KEYUP 65",a);check(in.take().equals(List.of("release 65")),"one release after auto-repeat");
        in.apply("KEYUP 70",b);check(in.take().isEmpty(),"a master cannot release a key it never pressed");
        in.apply("DOWN 1024",a);in.take();in.release(b);check(in.take().isEmpty(),"disconnect of one master keeps the other's mouse button");
        in.release(a);check(in.take().equals(List.of("unclick 1024")),"own button released on disconnect");
        in.apply("KEYDOWN 17",a);in.apply("KEYDOWN 18",b);in.take();in.apply("RESET",b);check(in.take().equals(List.of("release 18")),"RESET only releases that master's keys");
        in.chord(86);check(in.take().equals(List.of("press 86","release 86")),"paste keeps Ctrl held by another master");
        in.release(a);in.take();in.chord(86);check(in.take().equals(List.of("press 17","press 86","release 86","release 17")),"paste presses Ctrl when nobody holds it");
        in.apply("KEYDOWN 16");in.apply("KEYUP 16");check(in.take().equals(List.of("press 16","release 16")),"single-master API unchanged");
    }
    static void names(){
        Map<String,String> all=new LinkedHashMap<>();all.put("w1","Guru (10.0.0.5)");all.put("w2","Admin (10.0.0.9)");all.put("w3","Guru (10.0.0.5)");all.put("w4","10.0.0.7");
        check(LanControl.otherControllers(all,"w1").equals(List.of("10.0.0.7","Admin (10.0.0.9)")),"stale session of the same master is not another master");
        check(LanControl.otherControllers(all,"w2").equals(List.of("10.0.0.7","Guru (10.0.0.5)")),"duplicate names shown once");
        check(LanControl.otherControllers(Map.of("w1","Guru"),"w1").isEmpty(),"alone = nobody else");
    }
    /** Every master gets its own replies, at the same time, over the encrypted protocol. */
    static void concurrentMasters()throws Exception{
        try(ServerSocket server=new ServerSocket(0,16,InetAddress.getLoopbackAddress())){
            Future<?> loop=LanControl.WORK.submit(()->{try{LanControl.serve(server,"bersama-kode-01",()->true,(s,w)->{while(!s.isClosed()){String m=LanControl.string(w.read());w.send(LanControl.command("ECHO "+m));}});}catch(IOException ignored){}});
            int port=server.getLocalPort();List<LanControl.Wire> masters=new ArrayList<>();
            for(int i=0;i<LanControl.MAX_CONTROLLERS;i++)masters.add(LanControl.dial("127.0.0.1",port,"bersama-kode-01",3000));
            for(int round=0;round<20;round++){for(int i=0;i<masters.size();i++)masters.get(i).send(LanControl.command("M"+i+" "+round));
                for(int i=0;i<masters.size();i++)check(LanControl.string(masters.get(i).read()).equals("ECHO M"+i+" "+round),"each master receives only its own stream");}
            try{LanControl.dial("127.0.0.1",port,"bersama-kode-01",3000).close();throw new AssertionError("fifth master must be refused");}catch(LanControl.BusyException expected){}
            masters.remove(0).close();LanControl.Wire late=null;
            for(int i=0;i<40&&late==null;i++)try{late=LanControl.dial("127.0.0.1",port,"bersama-kode-01",3000);}catch(LanControl.BusyException wait){Thread.sleep(50);}
            check(late!=null,"a master can join after another leaves");late.send(LanControl.command("halo"));check(LanControl.string(late.read()).equals("ECHO halo"),"late master works");
            late.close();for(LanControl.Wire m:masters)m.close();server.close();loop.get(5,TimeUnit.SECONDS);
        }
    }
    static void updateGuard()throws Exception{
        try(RemoteServices first=new RemoteServices();RemoteServices second=new RemoteServices()){
            first.onUpdate=()->{};second.onUpdate=()->{};
            first.execute("UPDATE_BEGIN",List.of("9.0.0"));
            boolean refused=false;try{second.execute("UPDATE_BEGIN",List.of("9.0.0"));}catch(IOException e){refused=e.getMessage().contains("komputer utama lain");}
            check(refused,"second master cannot upload an update at the same time");
            first.close();second.execute("UPDATE_BEGIN",List.of("9.0.0"));
        }
        check(RemoteServices.UPDATING.get()==null,"update slot freed");
    }
}
