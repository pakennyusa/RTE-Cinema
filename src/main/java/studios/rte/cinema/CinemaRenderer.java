package studios.rte.cinema;
import org.bukkit.entity.Player;
import org.bukkit.map.*;
final class CinemaRenderer extends MapRenderer {
    private final Screen screen;
    private final int tile;
    private long lastGeneration=-1;
    CinemaRenderer(Screen screen,int tile){super(false);this.screen=screen;this.tile=tile;}
    @Override public void render(MapView view,MapCanvas canvas,Player player){
        var image=screen.images[tile];
        if(image!=null && lastGeneration!=screen.generation){
            canvas.drawImage(0,0,image);
            lastGeneration=screen.generation;
        }
    }
}
