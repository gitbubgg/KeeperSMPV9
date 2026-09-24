package net.keeper.smp;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Two shard-shop tools with hard-coded behaviour: the excavator mines a 3x3
 * plane in one hit, the tree feller drops an entire tree from one log.
 */
public class SpecialTools implements Listener {

    public static final String EXCAVATOR = "excavator";
    public static final String TREEFELLER = "treefeller";

    private final KeeperPlugin plugin;
    private final NamespacedKey tag;

    public SpecialTools(KeeperPlugin plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "special_tool");
    }

    private String toolId(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(tag, PersistentDataType.STRING);
    }

    public ItemStack createExcavator() {
        ItemStack stack = Util.item(org.bukkit.Material.DIAMOND_PICKAXE, 1, "&bExcavator",
                "&7Mines a 3x3 area in one hit.");
        stack.addUnsafeEnchantment(Enchantment.EFFICIENCY, 4);
        stack.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        var meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(tag, PersistentDataType.STRING, EXCAVATOR);
        stack.setItemMeta(meta);
        return stack;
    }

    public ItemStack createTreefeller() {
        ItemStack stack = Util.item(org.bukkit.Material.DIAMOND_AXE, 1, "&2Tree Feller",
                "&7Break one log, drop the whole tree.");
        stack.addUnsafeEnchantment(Enchantment.EFFICIENCY, 4);
        stack.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        var meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(tag, PersistentDataType.STRING, TREEFELLER);
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        String id = toolId(tool);
        if (id == null) return;

        switch (id) {
            case EXCAVATOR -> excavate(player, event.getBlock(), tool);
            case TREEFELLER -> {
                if (isLog(event.getBlock().getType().name())) fellTree(event.getBlock(), tool);
            }
            default -> {
            }
        }
    }

    private boolean isLog(String name) {
        return name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
    }

    /** Breaks the 3x3 plane around the mined block, facing the way the player is digging. */
    private void excavate(Player player, Block origin, ItemStack tool) {
        float pitch = player.getLocation().getPitch();
        BlockFace facing = player.getFacing();
        List<int[]> offsets = new ArrayList<>();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                if (pitch <= -60f || pitch >= 60f) {
                    offsets.add(new int[]{a, 0, b}); // mining straight up/down: X/Z plane
                } else if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) {
                    offsets.add(new int[]{a, b, 0}); // facing north/south: X/Y plane
                } else {
                    offsets.add(new int[]{0, b, a}); // facing east/west: Z/Y plane
                }
            }
        }
        for (int[] o : offsets) {
            Block candidate = origin.getRelative(o[0], o[1], o[2]);
            if (candidate.getType().isAir() || candidate.getType().getHardness() < 0) continue;
            candidate.breakNaturally(tool);
        }
    }

    /** Flood-fills connected logs of the same type and drops them all. */
    private void fellTree(Block origin, ItemStack tool) {
        var logType = origin.getType();
        Set<Block> visited = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        visited.add(origin);
        queue.add(origin);
        int limit = 256;

        while (!queue.isEmpty() && visited.size() < limit) {
            Block current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block next = current.getRelative(dx, dy, dz);
                        if (next.getType() != logType || visited.contains(next)) continue;
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        visited.remove(origin);
        for (Block log : visited) {
            log.breakNaturally(tool);
        }
    }
}
