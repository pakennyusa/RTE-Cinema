package studios.rte.cinema;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ItemFrame;
import org.bukkit.map.MapView;
import java.util.*;
final class Screen {
    final String name,world;
    final int width,height;
    final List<UUID> frames;
    final List<Integer> maps;
    volatile java.awt.image.BufferedImage[] images;
    volatile long generation;
    long lastSentGeneration;
    volatile String filename;
    volatile boolean paused;
    volatile double seconds;
    volatile Decoder decoder;
    boolean loop = false;
    int lastAudioSegment = -1;
    Screen(String name,String world,int width,int height,List<UUID> frames,List<Integer> maps){
        this.name=name;this.world=world;this.width=width;this.height=height;
        this.frames=new ArrayList<>(frames);this.maps=new ArrayList<>(maps);
        this.images=new java.awt.image.BufferedImage[width*height];
    }
    World bukkitWorld(){return Bukkit.getWorld(world);}
    ItemFrame itemFrame(int i){
        World w=bukkitWorld();
        if(w==null)return null;
        var e=w.getEntity(frames.get(i));
        return e instanceof ItemFrame f?f:null;
    }
    MapView map(int i){return Bukkit.getMap(maps.get(i));}
    void save(ConfigurationSection s){
        s.set("world",world);s.set("width",width);s.set("height",height);
        s.set("frames",frames.stream().map(UUID::toString).toList());s.set("maps",maps);
        s.set("loop",loop);
    }
    static Screen load(String name,ConfigurationSection s){
        var uuids=s.getStringList("frames").stream().map(UUID::fromString).toList();
        var ids=s.getIntegerList("maps");
        int w=s.getInt("width"),h=s.getInt("height");
        if(w<1||h<1||uuids.size()!=w*h||ids.size()!=w*h)throw new IllegalArgumentException("Invalid saved screen "+name);
        Screen screen=new Screen(name,Objects.requireNonNull(s.getString("world")),w,h,uuids,ids);
        screen.loop=s.getBoolean("loop",false);
        return screen;
    }
}
