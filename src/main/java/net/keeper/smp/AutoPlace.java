package net.keeper.smp;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Owner-only /autoplace toggle: while on, placing a block also fills the
 * rest of the 3x3 platform around it (same Y, same block) with copies of
 * whatever was just placed.
 */
public class AutoPlace implements Listener {

    private final KeeperPlugin plugin;
    private final Set<UUID> enabled = new HashSet<>();

    public AutoPlace(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean toggle(Player player) {
        boolean nowOn = !enabled.remove(player.getUniqueId());
        if (nowOn) enabled.add(player.getUniqueId());
        return nowOn;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!enabled.contains(player.getUniqueId())) return;
        if (!plugin.ranks().of(player).atLeast(Rank.OWNER)) return;

        Block placed = event.getBlockPlaced();
        Material type = placed.getType();
        if (!type.isSolid()) return;

        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Block target = placed.getRelative(dx, 0, dz);
                if (!target.getType().isAir()) continue;
                if (!creative) {
                    ItemStack cost = new ItemStack(type, 1);
                    if (!player.getInventory().containsAtLeast(cost, 1)) continue;
                    player.getInventory().removeItem(cost);
                }
                target.setType(type);
            }
        }
    }
}
