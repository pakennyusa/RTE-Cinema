package studios.rte.cinema;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class RTECinema extends JavaPlugin implements CommandExecutor,TabCompleter,Listener {
    private final Map<String,Screen> screens=new LinkedHashMap<>();
    private final Map<UUID,String> selected=new HashMap<>();
    private ExecutorService executor;
    private BukkitTask broadcaster;
    private Path mediaRoot;
    private File screenFile;
    private AudioManager audio;
    private final Set<UUID> audioReady=ConcurrentHashMap.newKeySet();
    private final Set<UUID> audioOffered=ConcurrentHashMap.newKeySet();
    @Override public void onEnable(){
        saveDefaultConfig();
        mediaRoot=getDataFolder().toPath().resolve("media");
        try{
            Files.createDirectories(mediaRoot);
            audio=new AudioManager(this,mediaRoot);
        }catch(IOException e){throw new IllegalStateException(e);}
        screenFile=new File(getDataFolder(),"screens.yml");
        executor=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"RTE-Cinema-FFmpeg");t.setDaemon(true);return t;});
        loadScreens();
        Objects.requireNonNull(getCommand("cinema")).setExecutor(this);
        Objects.requireNonNull(getCommand("cinema")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this,this);
        broadcaster=Bukkit.getScheduler().runTaskTimer(this,this::broadcast,10L,2L);
        getLogger().info("RTE Cinema enabled with "+screens.size()+" saved screens.");
    }
    @Override public void onDisable(){
        if(broadcaster!=null)broadcaster.cancel();
        for(Screen s:screens.values())stop(s);
        if(executor!=null)executor.shutdownNow();
        saveScreens();
    }
    private void stop(Screen s){
        silence(s);
        Decoder d=s.decoder;s.decoder=null;if(d!=null)d.close();
        s.lastAudioSegment=-1;
        s.playStartNanos=0L;
        s.filename=null;s.paused=false;s.seconds=0;
    }
    private void saveScreens(){
        YamlConfiguration yaml=new YamlConfiguration();
        screens.forEach((name,s)->s.save(yaml.createSection("screens."+name)));
        try{yaml.save(screenFile);}catch(IOException e){getLogger().severe("Unable to save screens: "+e.getMessage());}
    }
    private void loadScreens(){
        if(!screenFile.exists())return;
        var section=YamlConfiguration.loadConfiguration(screenFile).getConfigurationSection("screens");
        if(section==null)return;
        for(String name:section.getKeys(false)){
            try{
                Screen s=Screen.load(name,Objects.requireNonNull(section.getConfigurationSection(name)));
                screens.put(name.toLowerCase(Locale.ROOT),s);
                for(int i=0;i<s.maps.size();i++)attachRenderer(s,i);
            }catch(Exception e){getLogger().warning("Could not load "+name+": "+e.getMessage());}
        }
    }
    private void attachRenderer(Screen s,int tile){
        MapView map=s.map(tile);
        if(map==null){getLogger().warning("Missing map "+s.maps.get(tile));return;}
        for(var renderer:List.copyOf(map.getRenderers()))map.removeRenderer(renderer);
        map.addRenderer(new CinemaRenderer(s,tile));
        map.setTrackingPosition(false);
        map.setUnlimitedTracking(false);
    }
    private void silence(Screen s){
        if(audio==null || s.filename==null)return;
        for(Player p:Bukkit.getOnlinePlayers())audio.silence(p,s);
    }
    private void broadcast(){
        int maxDistance=Math.max(8,Math.min(128,getConfig().getInt("view-distance",32)));
        double distSq=maxDistance*maxDistance;
        double audioOffset=getConfig().getDouble("audio.offset-ms",0.0)/1000.0;
        for(Screen screen:screens.values()){
            if(screen.decoder==null||screen.paused)continue;
            if(screen.decoder.finished){
                if(screen.loop&&screen.filename!=null){
                    try{start(screen,screen.filename,0);}
                    catch(IOException e){getLogger().warning("Loop failed: "+e.getMessage());stop(screen);}
                }else{silence(screen);screen.decoder=null;screen.filename=null;screen.lastAudioSegment=-1;}
                continue;
            }
            boolean newFrame=screen.generation!=screen.lastSentGeneration;
            if(screen.playStartNanos==0L){
                if(!newFrame)continue;
                screen.playStartNanos=System.nanoTime();
            }
            screen.seconds=screen.startOffsetSeconds+(System.nanoTime()-screen.playStartNanos)/1_000_000_000.0;
            ItemFrame first=screen.itemFrame(0);
            if(first==null)continue;
            // Each segment is dispatched only once and after the matching frame packet batch.
            // Offset is adjustable because the vanilla client does not acknowledge sound/video presentation.
            int segment=(int)Math.floor(Math.max(0,screen.seconds+audioOffset)/audio.segmentSeconds());
            boolean single=getConfig().getString("audio.mode","single").equalsIgnoreCase("single");
            boolean audioCue=single ? audio.fullReady(screen.filename)&&screen.lastAudioSegment<0 : audio.ready(screen.filename)&&segment!=screen.lastAudioSegment;
            for(Player viewer:Bukkit.getOnlinePlayers()){
                if(!viewer.hasPermission("rtecinema.watch")||viewer.getWorld()!=first.getWorld())continue;
                if(viewer.getLocation().distanceSquared(first.getLocation())>distSq)continue;
                if(newFrame){
                    for(int i=0;i<screen.maps.size();i++){
                        MapView map=screen.map(i);
                        if(map!=null)viewer.sendMap(map);
                    }
                }
                if(newFrame&&audioCue&&audioReady.contains(viewer.getUniqueId())){
                    if(single)audio.playSingle(viewer,screen,first.getLocation());
                    else audio.play(viewer,screen,segment,first.getLocation());
                    if(getConfig().getBoolean("audio.debug",false))
                        getLogger().info("Audio dispatch ["+screen.name+"]: segment "+segment+" at "+String.format(Locale.ROOT,"%.2f",screen.seconds)+"s to "+viewer.getName());
                }
            }
            if(newFrame&&audioCue)screen.lastAudioSegment=single?0:segment;
            if(newFrame)screen.lastSentGeneration=screen.generation;
        }
    }
    private boolean allowed(CommandSender sender,String permission){
        if(sender.hasPermission(permission)||sender.hasPermission("rtecinema.admin"))return true;
        sender.sendMessage(ChatColor.RED+"You need "+permission+".");return false;
    }
    private Screen resolve(Player p){String n=selected.get(p.getUniqueId());return n==null?null:screens.get(n);}
    private void help(CommandSender s){
        s.sendMessage(ChatColor.GOLD+"RTE Cinema commands");
        s.sendMessage("/cinema create <name> <width> <height> - look at bottom-left wall block");
        s.sendMessage("/cinema list | select <name> | gui | status");
        s.sendMessage("/cinema play <filename> | pause | resume | stop | delete <name>");
        s.sendMessage("/cinema audio prepare <filename> | audio pack | audio status | audio test [segment]");
        s.sendMessage("/cinema loop on|off | loop toggle | loop status (per theater)");
        s.sendMessage("Media folder: plugins/RTECinema/media");
    }
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
        if(args.length==0||args[0].equalsIgnoreCase("help")){help(sender);return true;}
        if(!(sender instanceof Player p)){sender.sendMessage("Use in-game as a player.");return true;}
        try{
            switch(args[0].toLowerCase(Locale.ROOT)){
                case "create"->{
                    if(!allowed(p,"rtecinema.create"))return true;
                    if(args.length!=4){p.sendMessage("Usage: /cinema create <name> <width> <height>");return true;}
                    String name=args[1].toLowerCase(Locale.ROOT);
                    if(!name.matches("[a-z0-9_-]{1,24}")||screens.containsKey(name)){p.sendMessage("Invalid or duplicate screen.");return true;}
                    int w=Integer.parseInt(args[2]),h=Integer.parseInt(args[3]);
                    if(w<1||h<1||w>Math.max(1,getConfig().getInt("max-screen-width",4))||h>Math.max(1,getConfig().getInt("max-screen-height",3))){
                        p.sendMessage("Screen exceeds configured size limits.");return true;
                    }
                    create(p,name,w,h);
                }
                case "select"->{
                    if(!allowed(p,"rtecinema.use"))return true;
                    if(args.length<2||!screens.containsKey(args[1].toLowerCase(Locale.ROOT))){p.sendMessage("Unknown screen.");return true;}
                    selected.put(p.getUniqueId(),args[1].toLowerCase(Locale.ROOT));
                    p.sendMessage(ChatColor.GREEN+"Selected "+args[1]);
                }
                case "list"->{if(allowed(p,"rtecinema.use"))p.sendMessage("Screens: "+String.join(", ",screens.keySet()));}
                case "loop"->{
                    if(!allowed(p,"rtecinema.control"))return true;
                    Screen s=resolve(p);
                    if(s==null){p.sendMessage("Select a theater first.");return true;}
                    if(args.length==1||args[1].equalsIgnoreCase("status")){
                        p.sendMessage(ChatColor.GOLD+"Loop for "+s.name+": "+(s.loop?"ON":"OFF"));return true;
                    }
                    if(args[1].equalsIgnoreCase("toggle"))s.loop=!s.loop;
                    else if(args[1].equalsIgnoreCase("on"))s.loop=true;
                    else if(args[1].equalsIgnoreCase("off"))s.loop=false;
                    else{p.sendMessage("Usage: /cinema loop on|off|toggle|status");return true;}
                    saveScreens();
                    p.sendMessage(ChatColor.GREEN+"Loop for "+s.name+" is now "+(s.loop?"ON":"OFF"));
                }
                case "audio"->{
                    if(args.length<2){p.sendMessage("/cinema audio prepare <filename> | pack | status");return true;}
                    switch(args[1].toLowerCase(Locale.ROOT)){
                        case "prepare"->{
                            if(!allowed(p,"rtecinema.control"))return true;
                            if(args.length<3){p.sendMessage("Usage: /cinema audio prepare <filename>");return true;}
                            String filename=args[2];
                            Path movie=safeFile(filename);
                            String ffmpeg=getConfig().getString("ffmpeg-path","ffmpeg");
                            p.sendMessage(ChatColor.YELLOW+"Preparing OGG audio asynchronously. This may take a while.");
                            executor.submit(()->{
                                try{
                                    boolean singleMode=getConfig().getString("audio.mode","single").equalsIgnoreCase("single");
                                    int parts=singleMode?audio.prepareSingle(movie,ffmpeg):audio.prepare(movie,ffmpeg);
                                    Bukkit.getScheduler().runTask(this,()->{
                                        p.sendMessage(ChatColor.GREEN+(singleMode?"Single OGG soundtrack ready ("+parts+" bytes).":"Audio ready: "+parts+" OGG segments.")+" Re-upload RTE-Cinema-Audio.zip to your HTTPS host, then use /cinema audio pack.");
                                    });
                                }catch(Exception ex){
                                    getLogger().warning("Audio preparation failed for "+filename+": "+ex.getMessage());
                                    Bukkit.getScheduler().runTask(this,()->p.sendMessage(ChatColor.RED+"Audio preparation failed: "+ex.getMessage()));
                                }
                            });
                        }
                        case "pack"->{
                            if(!allowed(p,"rtecinema.watch"))return true;
                            audioReady.remove(p.getUniqueId());
                            audioOffered.add(p.getUniqueId());
                            audio.sendPack(p);
                            p.sendMessage("Resource pack offered. Accept it for movie audio.");
                        }
                        case "test"->{
                            if(!allowed(p,"rtecinema.use"))return true;
                            Screen current=resolve(p);
                            if(current==null||current.filename==null||!(audio.ready(current.filename)||audio.fullReady(current.filename))){
                                p.sendMessage(ChatColor.RED+"Select a screen playing a film with prepared audio first.");return true;
                            }
                            if(!audioReady.contains(p.getUniqueId())){
                                p.sendMessage(ChatColor.RED+"Cinema pack not confirmed loaded. Run /cinema audio pack.");return true;
                            }
                            int index=0;
                            if(args.length>=3){
                                try{index=Integer.parseInt(args[2]);}
                                catch(NumberFormatException ex){p.sendMessage(ChatColor.RED+"Usage: /cinema audio test [segment number]");return true;}
                            }
                            if(getConfig().getString("audio.mode","single").equalsIgnoreCase("single")){
                                p.sendMessage(ChatColor.GOLD+"Testing full-length OGG soundtrack.");
                                audio.playSingle(p,current,p.getLocation());
                                return true;
                            }
                            int count=audio.count(current.filename);
                            if(index<0||index>=count){p.sendMessage(ChatColor.RED+"Segment must be 0 through "+(count-1));return true;}
                            p.sendMessage(ChatColor.GOLD+"Testing audio segment "+index+" of "+count+" (Jukebox/Note Blocks volume).");
                            audio.play(p,current,index,p.getLocation());
                            getLogger().info("Audio diagnostic: sent segment "+index+" of "+count+" to "+p.getName()+" for "+current.filename);
                        }
                        case "status"->{
                            if(!allowed(p,"rtecinema.use"))return true;
                            Screen current=resolve(p);
                            p.sendMessage("Audio pack loaded: "+audioReady.contains(p.getUniqueId())+
                                "; selected film prepared: "+(current!=null&&current.filename!=null&&(getConfig().getString("audio.mode","single").equalsIgnoreCase("single")?audio.fullReady(current.filename):audio.ready(current.filename)))+
                                "; segments: "+(current==null||current.filename==null?0:audio.count(current.filename))+
                                "; timeline: "+(current==null?0:(int)current.seconds)+"s");
                        }
                        default->p.sendMessage("/cinema audio prepare <filename> | pack | status");
                    }
                }
                case "gui"->{if(allowed(p,"rtecinema.use"))openGui(p);}
                case "status"->{
                    if(!allowed(p,"rtecinema.use"))return true;
                    Screen s=resolve(p);
                    p.sendMessage(s==null?"Select a screen first.":"Screen "+s.name+" "+s.width+"x"+s.height+" "+(s.filename==null?"idle":s.paused?"paused":"playing")+" "+s.filename);
                }
                case "play"->{
                    if(!allowed(p,"rtecinema.control"))return true;
                    Screen s=resolve(p);
                    if(s==null||args.length<2){p.sendMessage("Select screen then /cinema play <filename>");return true;}
                    start(s,args[1],0);p.sendMessage(ChatColor.GREEN+"Starting "+args[1]);
                }
                case "pause"->{
                    if(!allowed(p,"rtecinema.control"))return true;
                    Screen s=resolve(p);
                    if(s==null||s.decoder==null){p.sendMessage("Nothing playing.");return true;}
                    s.paused=true;s.decoder.close();s.decoder=null;silence(s);p.sendMessage("Paused.");
                }
                case "resume"->{
                    if(!allowed(p,"rtecinema.control"))return true;
                    Screen s=resolve(p);
                    if(s==null||!s.paused||s.filename==null){p.sendMessage("Nothing paused.");return true;}
                    boolean singleMode=getConfig().getString("audio.mode","single").equalsIgnoreCase("single");
                    start(s,s.filename,singleMode?0:s.seconds);
                    p.sendMessage(singleMode?"Restarted from beginning (single-track audio cannot seek).":"Resumed.");
                }
                case "stop"->{
                    if(!allowed(p,"rtecinema.control"))return true;
                    Screen s=resolve(p);
                    if(s==null){p.sendMessage("Select screen.");return true;}
                    stop(s);p.sendMessage("Stopped.");
                }
                case "delete"->{
                    if(!allowed(p,"rtecinema.delete"))return true;
                    if(args.length<2){p.sendMessage("Usage: /cinema delete <name>");return true;}
                    Screen s=screens.remove(args[1].toLowerCase(Locale.ROOT));
                    if(s==null){p.sendMessage("Unknown screen.");return true;}
                    stop(s);
                    for(int i=0;i<s.frames.size();i++){ItemFrame frame=s.itemFrame(i);if(frame!=null)frame.remove();}
                    saveScreens();p.sendMessage("Deleted.");
                }
                default->help(p);
            }
        }catch(IllegalArgumentException e){p.sendMessage(ChatColor.RED+"Invalid input: "+e.getMessage());}
        catch(IOException e){p.sendMessage(ChatColor.RED+"Media error: "+e.getMessage());}
        return true;
    }
    private void create(Player p,String name,int w,int h){
        org.bukkit.util.RayTraceResult hit=p.rayTraceBlocks(8);
        Block target=hit==null?null:hit.getHitBlock();
        BlockFace face=hit==null?null:hit.getHitBlockFace();
        if(target==null||face==null||face==BlockFace.UP||face==BlockFace.DOWN){p.sendMessage("Look at a vertical wall's bottom-left block.");return;}
        int dx=face.getModZ(),dz=-face.getModX();
        List<Block> spots=new ArrayList<>();
        for(int row=0;row<h;row++)for(int col=0;col<w;col++){
            Block support=target.getRelative(dx*col,row,dz*col);
            Block air=support.getRelative(face);
            if(!support.getType().isSolid()||!air.getType().isAir()){p.sendMessage("Backing must be solid with empty front.");return;}
            spots.add(air);
        }
        List<UUID> entities=new ArrayList<>();
        List<Integer> ids=new ArrayList<>();
        try{
            for(Block block:spots){
                MapView map=Bukkit.createMap(p.getWorld());
                map.setScale(MapView.Scale.CLOSEST);
                ItemStack item=new ItemStack(Material.FILLED_MAP);
                MapMeta meta=(MapMeta)item.getItemMeta();
                meta.setMapView(map);item.setItemMeta(meta);
                ItemFrame frame=p.getWorld().spawn(block.getLocation().add(.5,.5,.5),ItemFrame.class);
                frame.setFacingDirection(face,true);
                frame.setFixed(true);
                frame.setItem(item,false);
                entities.add(frame.getUniqueId());ids.add(map.getId());
            }
            Screen screen=new Screen(name,p.getWorld().getName(),w,h,entities,ids);
            screens.put(name,screen);selected.put(p.getUniqueId(),name);
            for(int i=0;i<ids.size();i++)attachRenderer(screen,i);
            saveScreens();
            p.sendMessage(ChatColor.GREEN+"Created "+name+" ("+w+"x"+h+").");
        }catch(RuntimeException ex){
            for(UUID id:entities){var entity=p.getWorld().getEntity(id);if(entity!=null)entity.remove();}
            throw ex;
        }
    }
    private Path safeFile(String file)throws IOException{
        if(!file.matches("[a-zA-Z0-9._ -]{1,120}")||file.startsWith("."))throw new IOException("Use local filename, not URL/path.");
        Path root=mediaRoot.toRealPath();
        Path path=root.resolve(file).normalize();
        if(!path.startsWith(root)||!Files.isRegularFile(path)||!path.toRealPath().startsWith(root))throw new IOException("File not in media folder.");
        String lower=file.toLowerCase(Locale.ROOT);
        if(!(lower.endsWith(".mp4")||lower.endsWith(".webm")||lower.endsWith(".mkv")||lower.endsWith(".mov")))throw new IOException("Allowed: MP4, WebM, MKV, MOV.");
        return path;
    }
    private void start(Screen s,String file,double offset)throws IOException{
        Path path=safeFile(file);
        if(s.decoder!=null)s.decoder.close();
        silence(s);
        s.filename=file;s.paused=false;s.seconds=offset;s.startOffsetSeconds=offset;
        s.playStartNanos=0L;s.lastSentGeneration=s.generation;s.lastAudioSegment=-1;
        int fps=Math.max(1,Math.min(10,getConfig().getInt("fps",8)));
        Decoder decoder=new Decoder(s,path,getConfig().getString("ffmpeg-path","ffmpeg"),fps,offset,getLogger());
        s.decoder=decoder;executor.submit(decoder);
    }
    private void openGui(Player p){
        Inventory inv=Bukkit.createInventory(null,27,"RTE Cinema | Controls");
        ItemStack[] contents=new ItemStack[27];
        contents[10]=button(Material.LIME_DYE,"Resume");
        contents[12]=button(Material.YELLOW_DYE,"Pause");
        contents[14]=button(Material.RED_DYE,"Stop");
        contents[16]=button(Material.MAP,"Status");
        Screen current=resolve(p);
        contents[22]=button(Material.REPEATER,"Loop: "+(current!=null&&current.loop?"ON":"OFF"));
        inv.setContents(contents);p.openInventory(inv);
    }
    private ItemStack button(Material type,String name){
        ItemStack item=new ItemStack(type);
        var meta=item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD+name);
        item.setItemMeta(meta);return item;
    }
    @EventHandler public void onInventoryClick(InventoryClickEvent e){
        if(!e.getView().getTitle().equals("RTE Cinema | Controls"))return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p))return;
        int slot=e.getRawSlot();
        if(slot==16){p.performCommand("cinema status");return;}
        if(!p.hasPermission("rtecinema.control")&&!p.hasPermission("rtecinema.admin"))return;
        if(slot==22){p.performCommand("cinema loop toggle");p.closeInventory();return;}
        if(slot==10)p.performCommand("cinema resume");
        else if(slot==12)p.performCommand("cinema pause");
        else if(slot==14)p.performCommand("cinema stop");
    }
    @EventHandler public void onPackStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent event){
        switch(event.getStatus()){
            case SUCCESSFULLY_LOADED -> {
                if(audioOffered.contains(event.getPlayer().getUniqueId()))audioReady.add(event.getPlayer().getUniqueId());
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> audioReady.remove(event.getPlayer().getUniqueId());
            default -> {}
        }
    }
    @EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent event){
        audioReady.remove(event.getPlayer().getUniqueId());
        audioOffered.remove(event.getPlayer().getUniqueId());
        selected.remove(event.getPlayer().getUniqueId());
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        if(args.length==1)return List.of("help","create","list","select","gui","status","play","pause","resume","stop","delete","loop","audio").stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("loop"))return List.of("on","off","toggle","status");
        if(args.length==2&&args[0].equalsIgnoreCase("audio"))return List.of("prepare","pack","status","test");
        if(args.length==2&&(args[0].equalsIgnoreCase("select")||args[0].equalsIgnoreCase("delete")))return screens.keySet().stream().filter(s->s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("play")){
            try(var paths=Files.list(mediaRoot)){return paths.filter(Files::isRegularFile).map(p->p.getFileName().toString()).filter(s->s.startsWith(args[1])).limit(30).toList();}
            catch(IOException ignored){return List.of();}
        }
        return List.of();
    }
}