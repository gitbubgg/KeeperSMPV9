package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Zoglin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.List;

public class MobControl implements Listener {

    private final KeeperPlugin plugin;

    public MobControl(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    public static boolean hostile(Entity entity) {
        return entity instanceof Monster
                || entity instanceof Slime
                || entity instanceof MagmaCube
                || entity instanceof Ghast
                || entity instanceof Phantom
                || entity instanceof Hoglin
                || entity instanceof Zoglin;
    }

    private int radius() {
        return plugin.getConfig().getInt("general.mob-cull-radius", 64);
    }

    /**
     * Returns true when at least one player is close by and every one of them
     * has mob spawning switched off.
     */
    private boolean everyoneNearbyOptedOut(Location location) {
        int r = radius();
        boolean anyNearby = false;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(location) > (double) r * r) continue;
            anyNearby = true;
            if (plugin.data().get(player.getUniqueId()).mobSpawns) return false;
        }
        return anyNearby;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!plugin.getConfig().getBoolean("general.mob-cull-enabled", true)) return;
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.CUSTOM
                || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                || reason == CreatureSpawnEvent.SpawnReason.COMMAND) {
            return;
        }
        if (!hostile(event.getEntity())) return;
        if (everyoneNearbyOptedOut(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Sweeps loaded chunks and clears hostiles that wandered into opted out areas. */
    public void cull() {
        if (!plugin.getConfig().getBoolean("general.mob-cull-enabled", true)) return;
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            List<Player> players = world.getPlayers();
            if (players.isEmpty()) continue;
            for (LivingEntity entity : world.getLivingEntities()) {
                if (!hostile(entity)) continue;
                if (entity.getCustomName() != null) continue;
                if (everyoneNearbyOptedOut(entity.getLocation())) {
                    entity.remove();
                }
            }
        }
    }
}
