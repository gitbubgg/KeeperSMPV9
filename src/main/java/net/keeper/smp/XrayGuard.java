package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Flags players who keep mining valuable ore that had no exposed face
 * before it broke - meaning they couldn't have legitimately seen it, a
 * classic x-ray tell. Pings online staff in chat and logs to console.
 * This is a heuristic, not proof; it's a "go take a look" alert.
 */
public class XrayGuard implements Listener {

    private static final Set<Material> WATCHED = Set.of(
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.ANCIENT_DEBRIS,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE
    );

    private final KeeperPlugin plugin;
    /** Per player, timestamps (millis) of recent "blind" ore breaks. */
    private final Map<UUID, Deque<Long>> blindBreaks = new HashMap<>();

    public XrayGuard(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!WATCHED.contains(block.getType())) return;
        if (plugin.ranks().of(event.getPlayer()).isStaff()) return;
        if (isExposed(block)) return;

        UUID uuid = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        Deque<Long> recent = blindBreaks.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        recent.addLast(now);

        long windowMillis = plugin.getConfig().getInt("xray.window-seconds", 120) * 1000L;
        while (!recent.isEmpty() && now - recent.peekFirst() > windowMillis) recent.pollFirst();

        int threshold = plugin.getConfig().getInt("xray.alert-threshold", 3);
        if (recent.size() >= threshold) {
            alert(event.getPlayer(), block.getType(), recent.size());
            recent.clear();
        }
    }

    /** True if any of the 6 neighbours were see-through before this block broke. */
    private boolean isExposed(Block block) {
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN,
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Material neighbor = block.getRelative(face).getType();
            if (neighbor.isAir() || !neighbor.isOccluding()) return true;
        }
        return false;
    }

    private void alert(Player player, Material ore, int count) {
        String message = "&c[X-ray?] &f" + player.getName() + " &7mined &f" + count
                + " &7fully-enclosed &f" + Util.nice(ore) + " &7in a row. Worth checking.";
        plugin.getLogger().warning(Util.strip(message));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (plugin.ranks().of(online).isStaff()) {
                online.sendMessage(Util.text(message));
            }
        }
    }
}
