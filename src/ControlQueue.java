import java.util.ArrayDeque;

/** Bounded control-first scheduling: stale pointer positions never accumulate. */
class ControlQueue {
    final ArrayDeque<String> controls=new ArrayDeque<>(),bulk=new ArrayDeque<>();
    String motion,configuration;boolean acknowledgement=false;
    static boolean input(String s){return s.startsWith("MOVE ")||s.startsWith("DOWN ")||s.startsWith("UP ")||s.startsWith("KEYDOWN ")||s.startsWith("KEYUP ")||s.startsWith("WHEEL ")||s.equals("RESET");}
    synchronized boolean offer(String s){
        if(s.startsWith("MOVE "))motion=s;
        else if(s.equals("VIDEO_ACK"))acknowledgement=true;
        else if(s.startsWith("VIDEO "))configuration=s;
        else if(input(s)||s.matches("RPC [^ ]+ (COPY_TEXT|PASTE_TEXT)( .*)?")){
            if(controls.size()>254)return false;
            if(motion!=null){controls.addLast(motion);motion=null;}
            controls.addLast(s);
        }else{if(bulk.size()>=32)return false;bulk.addLast(s);}
        notifyAll();return true;
    }
    synchronized String poll(long milliseconds)throws InterruptedException{
        long deadline=System.nanoTime()+milliseconds*1_000_000;
        while(true){
            if(!controls.isEmpty())return controls.removeFirst();
            // Frame acknowledgements are tiny and gate the next screen update: never starve them behind continuous mouse motion.
            if(acknowledgement){acknowledgement=false;return "VIDEO_ACK";}
            if(motion!=null){String next=motion;motion=null;return next;}
            if(configuration!=null){String next=configuration;configuration=null;return next;}
            if(!bulk.isEmpty())return bulk.removeFirst();
            long remaining=deadline-System.nanoTime();if(remaining<=0)return null;
            wait(Math.max(1,remaining/1_000_000));
        }
    }
}
