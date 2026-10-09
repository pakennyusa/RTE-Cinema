package studios.rte.cinema;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
final class Decoder implements Runnable {
    private final Screen screen;
    private final Path file;
    private final String ffmpeg;
    private final int fps;
    private final double start;
    private final Logger logger;
    private final AtomicBoolean running=new AtomicBoolean(true);
    private volatile Process process;
    volatile boolean finished;
    Decoder(Screen screen,Path file,String ffmpeg,int fps,double start,Logger logger){
        this.screen=screen;this.file=file;this.ffmpeg=ffmpeg;this.fps=fps;this.start=start;this.logger=logger;
    }
    void close(){
        running.set(false);
        Process p=process;
        if(p!=null)p.destroyForcibly();
    }
    @Override public void run(){
        int w=screen.width*128,h=screen.height*128,size=w*h*3;
        try{
            var command=new java.util.ArrayList<String>();
            command.add(ffmpeg);command.add("-hide_banner");command.add("-loglevel");command.add("error");command.add("-nostdin");
            if(start>0){command.add("-ss");command.add(Double.toString(start));}
            command.add("-re");command.add("-i");command.add(file.toString());
            command.add("-an");command.add("-sn");command.add("-dn");
            command.add("-vf");command.add("fps="+fps+",scale="+w+":"+h+":flags=fast_bilinear,format=rgb24");
            command.add("-f");command.add("rawvideo");command.add("-pix_fmt");command.add("rgb24");command.add("pipe:1");
            Process p=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process=p;
            byte[] bytes=new byte[size];
            try(InputStream in=new BufferedInputStream(p.getInputStream(),size)){
                while(running.get()){
                    int read=0;
                    while(read<size){
                        int n=in.read(bytes,read,size-read);
                        if(n<0)return;
                        read+=n;
                    }
                    BufferedImage[] tiles=new BufferedImage[screen.width*screen.height];
                    for(int y=0;y<screen.height;y++)for(int x=0;x<screen.width;x++)tiles[y*screen.width+x]=new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB);
                    int idx=0;
                    for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                        int rgb=((bytes[idx++]&255)<<16)|((bytes[idx++]&255)<<8)|(bytes[idx++]&255);
                        tiles[(y/128)*screen.width+(x/128)].setRGB(x%128,y%128,rgb);
                    }
                    screen.images=tiles;
                    screen.generation++;
                }
            }finally{
                if(running.get())finished=true;
                p.destroyForcibly();
            }
        }catch(IOException ex){
            if(running.get())logger.warning("Cinema decoder for "+screen.name+" stopped: "+ex.getMessage());
        }
    }
}
