package studios.rte.cinema;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/**
 * Offline OGG segment preparation and vanilla resource-pack delivery.
 * Resource pack hosting is explicitly external: configured public HTTPS URL.
 */
final class AudioManager {
    private final JavaPlugin plugin;
    private final Path media;
    private final Path audio;
    private final Path pack;
    private final Map<String, Integer> segments=new ConcurrentHashMap<>();
    private final Set<String> fullTracks=ConcurrentHashMap.newKeySet();
    private final Set<UUID> optedOut=ConcurrentHashMap.newKeySet();
    private static final int SECONDS=4;

    AudioManager(JavaPlugin plugin,Path media) throws IOException {
        this.plugin=plugin;this.media=media;
        this.audio=plugin.getDataFolder().toPath().resolve("audio");
        Files.createDirectories(audio);
        this.pack=plugin.getDataFolder().toPath().resolve("RTE-Cinema-Audio.zip");
        loadIndex();
        try(var dirs=Files.list(audio)){
            for(Path folder:dirs.filter(Files::isDirectory).toList())
                if(Files.isRegularFile(folder.resolve("full.ogg")))fullTracks.add(folder.getFileName().toString());
        }
    }
    Path pack(){return pack;}
    int segmentSeconds(){return SECONDS;}
    boolean ready(String filename){return segments.containsKey(id(filename));}
    boolean fullReady(String filename){return fullTracks.contains(id(filename));}
    int count(String filename){return segments.getOrDefault(id(filename),0);}
    boolean muted(Player player){return optedOut.contains(player.getUniqueId());}
    void toggleMute(Player player,boolean mute){
        if(mute)optedOut.add(player.getUniqueId());else optedOut.remove(player.getUniqueId());
    }
    String id(String filename){
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(filename.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0,16);
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private void loadIndex()throws IOException{
        if(!Files.isDirectory(audio))return;
        try(var dirs=Files.list(audio)){
            for(Path dir:dirs.filter(Files::isDirectory).toList()){
                try(var files=Files.list(dir)){
                    int count=(int)files.filter(p->p.getFileName().toString().matches("s[0-9]{5}\\.ogg")).count();
                    if(count>0)segments.put(dir.getFileName().toString(),count);
                }
            }
        }
    }
    /**
     * Runs exclusively on a background executor. Do not invoke on the server thread.
     */
    int prepare(Path movie,String ffmpeg) throws IOException,InterruptedException {
        String mediaId=id(movie.getFileName().toString());
        Path folder=audio.resolve(mediaId);
        Files.createDirectories(folder);
        try(var existing=Files.list(folder)){
            for(Path f:existing.toList())Files.deleteIfExists(f);
        }
        List<String> cmd=List.of(ffmpeg,"-hide_banner","-loglevel","error","-nostdin",
                "-i",movie.toAbsolutePath().toString(),"-vn","-map","0:a:0",
                "-ac","1","-ar","24000","-c:a","libvorbis","-q:a","2",
                "-f","segment","-segment_time",Integer.toString(SECONDS),
                "-reset_timestamps","1",folder.resolve("s%05d.ogg").toString());
        Process process=new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(InputStream in=process.getInputStream()){in.transferTo(output);}
        int exit=process.waitFor();
        if(exit!=0)throw new IOException("FFmpeg audio conversion failed: "+output.toString(StandardCharsets.UTF_8));
        int n;
        try(var files=Files.list(folder)){n=(int)files.filter(p->p.getFileName().toString().matches("s[0-9]{5}\\.ogg")).count();}
        if(n==0)throw new IOException("No audio stream or empty conversion result.");
        segments.put(mediaId,n);
        generatePack();
        return n;
    }
    int prepareSingle(Path movie,String ffmpeg)throws IOException,InterruptedException {
        String mediaId=id(movie.getFileName().toString());
        Path folder=audio.resolve(mediaId);
        Files.createDirectories(folder);
        Path target=folder.resolve("full.ogg");
        Path temp=folder.resolve("full.tmp.ogg");
        List<String> cmd=List.of(ffmpeg,"-y","-hide_banner","-loglevel","error","-nostdin",
            "-i",movie.toAbsolutePath().toString(),"-vn","-map","0:a:0",
            "-ac","2","-ar","24000","-c:a","libvorbis","-q:a","2",
            temp.toAbsolutePath().toString());
        Process process=new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream log=new ByteArrayOutputStream();
        try(InputStream in=process.getInputStream()){in.transferTo(log);}
        int code=process.waitFor();
        if(code!=0)throw new IOException("Single OGG conversion failed: "+log.toString(StandardCharsets.UTF_8));
        Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
        fullTracks.add(mediaId);
        generatePack();
        return (int)Math.min(Integer.MAX_VALUE,Files.size(target));
    }
    void playSingle(Player player,Screen screen,Location at){
        if(muted(player)||screen.filename==null||!fullReady(screen.filename))return;
        float volume=(float)Math.max(0.01,Math.min(4,plugin.getConfig().getDouble("audio.volume",1.0)));
        player.playSound(at,"rtecinema:"+id(screen.filename)+".full",SoundCategory.RECORDS,volume,1f);
    }
    private synchronized void generatePack()throws IOException {
        Path temporary=pack.resolveSibling(pack.getFileName()+".tmp");
        int format=plugin.getConfig().getInt("audio.pack-format",97);
        String mcmeta="{\"pack\":{\"pack_format\":"+format+",\"min_format\":["+format+",0],\"max_format\":"+format+",\"description\":\"RTE Cinema Audio\"}}";
        StringBuilder sounds=new StringBuilder("{");
        boolean first=true;
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(temporary))){
            entry(zip,"pack.mcmeta",mcmeta.getBytes(StandardCharsets.UTF_8));
            List<String> ids=new ArrayList<>(segments.keySet());Collections.sort(ids);
            for(String id:ids){
                for(int i=0;i<segments.get(id);i++){
                    Path file=audio.resolve(id).resolve(String.format(Locale.ROOT,"s%05d.ogg",i));
                    if(!Files.isRegularFile(file))continue;
                    String name=String.format(Locale.ROOT,"s%05d",i);
                    entry(zip,"assets/rtecinema/sounds/"+id+"/"+name+".ogg",Files.readAllBytes(file));
                    if(!first)sounds.append(',');first=false;
                    sounds.append('"').append(id).append('.').append(name).append("\":{\"sounds\":[{\"name\":\"rtecinema:")
                          .append(id).append('/').append(name).append("\",\"stream\":true}]}");
                }
            }
            for(String id:new TreeSet<>(fullTracks)){
                Path file=audio.resolve(id).resolve("full.ogg");
                if(!Files.isRegularFile(file))continue;
                entry(zip,"assets/rtecinema/sounds/"+id+"/full.ogg",Files.readAllBytes(file));
                if(!first)sounds.append(',');first=false;
                sounds.append('"').append(id).append(".full\":{\"sounds\":[{\"name\":\"rtecinema:")
                    .append(id).append("/full\",\"stream\":true}]}");
            }
            sounds.append('}');
            entry(zip,"assets/rtecinema/sounds.json",sounds.toString().getBytes(StandardCharsets.UTF_8));
        }
        try{Files.move(temporary,pack,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException e){Files.move(temporary,pack,StandardCopyOption.REPLACE_EXISTING);}
    }
    private static void entry(ZipOutputStream zip,String name,byte[] data)throws IOException{
        zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();
    }
    byte[] sha1()throws IOException{
        if(!Files.isRegularFile(pack))throw new IOException("Prepare audio before distributing the pack.");
        try(InputStream in=Files.newInputStream(pack)){
            MessageDigest digest=MessageDigest.getInstance("SHA-1");
            byte[] chunk=new byte[8192];int n;
            while((n=in.read(chunk))!=-1)digest.update(chunk,0,n);
            return digest.digest();
        }catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    void sendPack(Player player)throws IOException{
        String url=plugin.getConfig().getString("audio.pack-url","");
        if(!url.startsWith("https://"))throw new IOException("Set audio.pack-url to your publicly accessible HTTPS URL.");
        player.setResourcePack(url,sha1());
    }
    void play(Player player,Screen screen,int index,Location at){
        if(muted(player)||index<0||index>=count(screen.filename))return;
        String key="rtecinema:"+id(screen.filename)+"."+String.format(Locale.ROOT,"s%05d",index);
        float volume=(float)Math.max(0.01,Math.min(4,plugin.getConfig().getDouble("audio.volume",1.0)));
        player.playSound(at,key,SoundCategory.RECORDS,volume,1f);
    }
    void silence(Player player,Screen screen){
        if(screen.filename==null)return;
        String prefix="rtecinema:"+id(screen.filename)+".";
        player.stopSound("rtecinema:"+id(screen.filename)+".full",SoundCategory.RECORDS);
        // Stop all segments of the active film to avoid overlap after pause/seek/stop.
        for(int i=0;i<count(screen.filename);i++)
            player.stopSound(prefix+String.format(Locale.ROOT,"s%05d",i),SoundCategory.RECORDS);
    }
}
