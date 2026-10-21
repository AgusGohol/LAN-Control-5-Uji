import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Client-side frame encoder. Unchanged screens send FRAME_SAME. With a v1.7+ master ("TILES") only the 64x64 tiles
 * that changed are sent, merged into rectangles, so typing, cursors and small windows cost a few KB instead of a full frame.
 * Large changes, new sessions, quality changes and the "settled" refresh after input still send a full frame.
 */
class FrameCache {
    static final int TILE=64;static final byte[] MAGIC={'L','T','1'};static final double FULL_FRAME_ABOVE=.55;
    int[] previous;int width,height;String settings="";
    long fullFrames,tileFrames,sameFrames;
    void clear(){previous=null;settings="";}
    byte[] encode(BufferedImage raw,StreamQuality.State state)throws IOException{
        Dimension size=state.size(raw.getWidth(),raw.getHeight());BufferedImage img=StreamQuality.scale(raw,size);int[] px=StreamQuality.pixels(img);
        // Keep a private copy when the caller's own buffer is used directly, so later changes to it are still detected.
        int[] keep=img==raw?px.clone():px;
        String signature=state.config.toString()+size+state.interactive();
        boolean comparable=previous!=null&&width==img.getWidth()&&height==img.getHeight()&&settings.equals(signature)&&!state.forceKey;
        if(comparable){
            List<Rectangle> dirty=dirty(previous,px,width,height);
            if(dirty.isEmpty()){sameFrames++;return LanControl.command("FRAME_SAME");}
            long area=0;for(Rectangle r:dirty)area+=(long)r.width*r.height;
            if(state.config.tiles()&&area<=FULL_FRAME_ABOVE*width*height){previous=keep;tileFrames++;return tiles(img,dirty,state);}
        }
        byte[] bytes=StreamQuality.encodeScaled(img,state);previous=keep;width=img.getWidth();height=img.getHeight();settings=signature;state.forceKey=false;fullFrames++;return bytes;
    }
    /** Changed tiles, merged into horizontal runs and then vertically into rectangles. */
    static List<Rectangle> dirty(int[] a,int[] b,int w,int h){
        int cols=(w+TILE-1)/TILE,rows=(h+TILE-1)/TILE;boolean[] changed=new boolean[cols];List<Rectangle> open=new ArrayList<>(),done=new ArrayList<>();
        for(int ty=0;ty<rows;ty++){
            Arrays.fill(changed,false);int y0=ty*TILE,y1=Math.min(h,y0+TILE);int remaining=cols;
            for(int y=y0;y<y1&&remaining>0;y++){int row=y*w;
                for(int tx=0;tx<cols;tx++)if(!changed[tx]){int x0=tx*TILE,x1=Math.min(w,x0+TILE);
                    if(!Arrays.equals(a,row+x0,row+x1,b,row+x0,row+x1)&&differs(a,b,row+x0,row+x1)){changed[tx]=true;remaining--;}}}
            List<Rectangle> runs=new ArrayList<>();
            for(int tx=0;tx<cols;){if(!changed[tx]){tx++;continue;}int start=tx;while(tx<cols&&changed[tx])tx++;int x0=start*TILE;runs.add(new Rectangle(x0,y0,Math.min(w,tx*TILE)-x0,y1-y0));}
            List<Rectangle> next=new ArrayList<>();boolean[] used=new boolean[open.size()];
            for(Rectangle run:runs){int match=-1;for(int i=0;i<open.size();i++){Rectangle o=open.get(i);if(!used[i]&&o.x==run.x&&o.width==run.width&&o.y+o.height==run.y){match=i;break;}}
                if(match>=0){used[match]=true;Rectangle o=open.get(match);o.height+=run.height;next.add(o);}else next.add(run);}
            for(int i=0;i<open.size();i++)if(!used[i])done.add(open.get(i));
            open=next;
        }
        done.addAll(open);return done;
    }
    /** TYPE_INT_RGB ignores the top byte; some sources leave it set, so compare colour bits only when the fast check fails. */
    static boolean differs(int[] a,int[] b,int from,int to){for(int i=from;i<to;i++)if(((a[i]^b[i])&0xffffff)!=0)return true;return false;}
    static byte[] tiles(BufferedImage img,List<Rectangle> rects,StreamQuality.State state)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(32*1024);DataOutputStream out=new DataOutputStream(bytes);
        out.write(MAGIC);out.writeInt(img.getWidth());out.writeInt(img.getHeight());out.writeInt(rects.size());
        boolean lossless=state.lossless();float quality=state.quality();
        for(Rectangle r:rects){BufferedImage part=img.getSubimage(r.x,r.y,r.width,r.height);byte[] data=lossless?StreamQuality.png(part):StreamQuality.jpeg(part,quality);
            out.writeInt(r.x);out.writeInt(r.y);out.writeInt(r.width);out.writeInt(r.height);out.writeInt(data.length);out.write(data);}
        out.flush();if(bytes.size()>=LanControl.MAX-1024)throw new IOException("Gambar melampaui batas paket.");return bytes.toByteArray();
    }
    static boolean isTiles(byte[] data){return data.length>15&&data[0]==MAGIC[0]&&data[1]==MAGIC[1]&&data[2]==MAGIC[2];}
    /** Master side: paints the tiles onto a copy of the current frame. Returns null when the base frame does not match (a full frame is then requested). */
    record Applied(BufferedImage image,List<Rectangle> changed) {}
    static BufferedImage apply(BufferedImage base,byte[] data)throws IOException{Applied a=applyTiles(base,data);return a==null?null:a.image();}
    static Applied applyTiles(BufferedImage base,byte[] data)throws IOException{
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(data));in.skipNBytes(3);int w=in.readInt(),h=in.readInt(),n=in.readInt();
        if(base==null||base.getWidth()!=w||base.getHeight()!=h||n<0||n>10_000)return null;
        BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        if(base.getType()==BufferedImage.TYPE_INT_RGB&&base.getRaster().getDataBuffer() instanceof java.awt.image.DataBufferInt){int[] src=StreamQuality.pixels(StreamQuality.rgb(base));System.arraycopy(src,0,StreamQuality.pixels(out),0,src.length);}
        else {Graphics2D g=out.createGraphics();g.drawImage(base,0,0,null);g.dispose();}
        Graphics2D g=out.createGraphics();List<Rectangle> changed=new ArrayList<>(n);
        try{for(int i=0;i<n;i++){int x=in.readInt(),y=in.readInt(),tw=in.readInt(),th=in.readInt(),len=in.readInt();
                if(x<0||y<0||tw<1||th<1||x+tw>w||y+th>h||len<1||len>data.length)throw new IOException("Potongan layar tidak valid.");
                byte[] part=in.readNBytes(len);BufferedImage tile=ImageIO.read(new ByteArrayInputStream(part));if(tile==null)throw new IOException("Potongan layar rusak.");
                g.drawImage(tile,x,y,tw,th,null);changed.add(new Rectangle(x,y,tw,th));}}
        finally{g.dispose();}
        return new Applied(out,changed);
    }
}
