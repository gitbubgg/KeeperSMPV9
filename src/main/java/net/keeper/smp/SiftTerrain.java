package net.keeper.smp;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * The Carapace zone reuses the Badlands biome for its terrain shape, which
 * also naturally places plain red sand and dead bushes - real overworld
 * blocks a player can already dig up back home, not anything distinctly
 * "Sift". Swaps both out for materials with zero natural spawn anywhere in
 * vanilla (dried kelp block, wither rose), which the resource pack then
 * retextures - unlike tuff/terracotta, neither can ever leak into the main
 * world's generation, since nothing generates them there in the first place.
 *
 * <p>Runs once per chunk, only on first generation (isNewChunk), so it never
 * rescans terrain a player has already touched.</p>
 */
public class SiftTerrain implements Listener {

    private final KeeperPlugin plugin;

    public SiftTerrain(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    private String siftWorldName() {
        return plugin.getConfig().getString("general.rtp-sift-world", "sift");
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) return;
        if (!event.getWorld().getName().equals(siftWorldName())) return;

        Chunk chunk = event.getChunk();
        int minY = event.getWorld().getMinHeight();
        int maxY = event.getWorld().getMaxHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (block.getType() == Material.RED_SAND) {
                        block.setType(Material.DRIED_KELP_BLOCK, false);
                    } else if (block.getType() == Material.DEAD_BUSH) {
                        block.setType(Material.WITHER_ROSE, false);
                    }
                }
            }
        }
    }
}
