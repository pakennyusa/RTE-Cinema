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
    @Override public void onEnable(){
        saveDefaultConfig();
        mediaRoot=getDataFolder().toPath().resolve("media");
        try{Files.createDirectories(mediaRoot);}catch(IOException e){throw new IllegalStateException(e);}
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
        Decoder d=s.decoder;s.decoder=null;if(d!=null)d.close();
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
    private void broadcast(){
        int maxDistance=Math.max(8,Math.min(128,getConfig().getInt("view-distance",32)));
        double distSq=maxDistance*maxDistance;
        for(Screen s:screens.values()){
            if(s.decoder==null||s.paused||s.generation==0||s.generation==s.lastSentGeneration)continue;
            int fps=Math.max(1,Math.min(10,getConfig().getInt("fps",8)));
            long delta=s.generation-s.lastSentGeneration;
            s.lastSentGeneration=s.generation;
            s.seconds+=delta/(double)fps;
            ItemFrame first=s.itemFrame(0);
            if(first==null)continue;
            for(Player p:Bukkit.getOnlinePlayers()){
                if(!p.hasPermission("rtecinema.watch")||p.getWorld()!=first.getWorld())continue;
                if(p.getLocation().distanceSquared(first.getLocation())>distSq)continue;
                for(int i=0;i<s.maps.size();i++){
                    MapView map=s.map(i);
                    if(map!=null)p.sendMap(map);
                }
            }
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
                    s.paused=true;s.decoder.close();s.decoder=null;p.sendMessage("Paused.");
                }
                case "resume"->{
                    if(!allowed(p,"rtecinema.control"))return true;
                    Screen s=resolve(p);
                    if(s==null||!s.paused||s.filename==null){p.sendMessage("Nothing paused.");return true;}
                    start(s,s.filename,s.seconds);p.sendMessage("Resumed.");
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
        s.filename=file;s.paused=false;s.seconds=offset;s.lastSentGeneration=s.generation;
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
        if(slot==10)p.performCommand("cinema resume");
        else if(slot==12)p.performCommand("cinema pause");
        else if(slot==14)p.performCommand("cinema stop");
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        if(args.length==1)return List.of("help","create","list","select","gui","status","play","pause","resume","stop","delete").stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&(args[0].equalsIgnoreCase("select")||args[0].equalsIgnoreCase("delete")))return screens.keySet().stream().filter(s->s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("play")){
            try(var paths=Files.list(mediaRoot)){return paths.filter(Files::isRegularFile).map(p->p.getFileName().toString()).filter(s->s.startsWith(args[1])).limit(30).toList();}
            catch(IOException ignored){return List.of();}
        }
        return List.of();
    }
}