package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.Arrays;

/**
 * Elytra are sold in /shop as a limited, priced item, so the free ones
 * vanilla places in item frames on End City ships are stripped out as
 * chunks load, instead of being left as a second, uncontrolled source.
 */
public class EndLoot implements Listener {

    private final KeeperPlugin plugin;

    public EndLoot(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("end-loot.remove-elytra", true);
    }

    private void strip(Iterable<Entity> entities) {
        for (Entity entity : entities) {
            if (entity instanceof ItemFrame frame && frame.getItem().getType() == Material.ELYTRA) {
                frame.setItem(null);
            }
        }
    }

    /** Strips any elytra already sitting in item frames in chunks that are already loaded. */
    public void sweepLoadedChunks() {
        if (!enabled()) return;
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) continue;
            for (Chunk chunk : world.getLoadedChunks()) {
                strip(Arrays.asList(chunk.getEntities()));
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!enabled()) return;
        if (event.getWorld().getEnvironment() != World.Environment.THE_END) return;
        strip(Arrays.asList(event.getChunk().getEntities()));
    }
}
