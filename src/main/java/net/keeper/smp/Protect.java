package net.keeper.smp;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

/**
 * Keeps the spawn plaza intact. The protected area is a circle around the
 * world spawn point, which /buildspawn sets, so it follows the build without
 * anything to configure by hand.
 *
 * Staff are exempt, so anyone Mod or above can still edit spawn.
 */
public class Protect implements Listener {

    private final KeeperPlugin plugin;

    public Protect(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("protection.enabled", true);
    }

    private int radius() {
        return plugin.getConfig().getInt("protection.radius", 45);
    }

    /** True when the location sits inside the protected circle. */
    public boolean protectedArea(Location location) {
        if (!enabled() || location == null) return false;
        World world = location.getWorld();
        if (world == null) return false;
        Location spawn = world.getSpawnLocation();
        double dx = location.getX() - spawn.getX();
        double dz = location.getZ() - spawn.getZ();
        int r = radius();
        return dx * dx + dz * dz <= (double) r * r;
    }

    /** Cancels and explains, unless the player is staff. */
    private boolean blocked(Player player, Location location) {
        if (!protectedArea(location)) return false;
        boolean bypass = plugin.getConfig().getBoolean("protection.staff-bypass", true);
        if (bypass && player != null && plugin.ranks().of(player).isStaff()) return false;
        if (player != null) {
            player.sendActionBar(Util.text("&cSpawn is protected."));
        }
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (blocked(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (blocked(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (blocked(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (blocked(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player player = event.getRemover() instanceof Player p ? p : null;
        if (blocked(player, event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Creepers, TNT, end crystals and beds. No player to warn here. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> protectedArea(block.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> protectedArea(block.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (protectedArea(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (blocked(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * Ice and snow forming on the plaza. A cold biome would otherwise freeze
     * the fountains and cover the floor overnight.
     */
    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (protectedArea(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Snow layers piling up, fire and vines creeping along. */
    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (protectedArea(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Stops the build melting or decaying, leaves included. */
    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (protectedArea(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Endermen lifting blocks, sheep eating grass, falling anvils. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Player) return;
        if (protectedArea(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }
}
