package net.keeper.smp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A vanilla spawner cannot be relied on to produce primed TNT, so the TNT
 * spawner is run by the plugin instead: the block is tracked and a task drops
 * live TNT on it while a player is nearby.
 */
public class Spawners implements Listener {

    private final KeeperPlugin plugin;
    private final NamespacedKey tag;
    private final Set<String> blocks = new HashSet<>();
    private final File file;

    public Spawners(KeeperPlugin plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "tnt_spawner");
        this.file = new File(plugin.getDataFolder(), "tnt-spawners.yml");
        load();
    }

    public ItemStack createItem(String displayName) {
        ItemStack stack = new ItemStack(Material.SPAWNER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Util.text(displayName));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(Util.text("&7Drops live TNT while someone is within"));
            lore.add(Util.text("&7" + radius() + " blocks of it."));
            lore.add(Util.text("&cIt will blow up its surroundings, and you."));
            lore.add(Util.text("&7The spawner block itself survives the blasts."));
            lore.add(Util.text("&8Break it to get it back."));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public boolean isTntSpawnerItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        return stack.getItemMeta().getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }

    private int radius() {
        return plugin.getConfig().getInt("tnt-spawner.trigger-radius", 12);
    }

    private String serialize(Block block) {
        return block.getWorld().getName() + ";" + block.getX() + ";" + block.getY()
                + ";" + block.getZ();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isTntSpawnerItem(event.getItemInHand())) return;
        blocks.add(serialize(event.getBlock()));
        save();
        event.getPlayer().sendMessage(Util.text("&cTNT spawner armed. &7Stand clear."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        String key = serialize(event.getBlock());
        if (!blocks.remove(key)) return;
        save();
        event.setDropItems(false);
        event.getBlock().getWorld().dropItemNaturally(
                event.getBlock().getLocation().add(0.5, 0.5, 0.5),
                createItem("&cTNT Spawner"));
        event.getPlayer().sendMessage(Util.text("&7TNT spawner picked back up."));
    }

    /**
     * The TNT appears directly above the spawner, so without this the first
     * blast destroys the block that produced it. Tracked spawners are pulled
     * out of every explosion's block list, which leaves the surroundings
     * destructible but keeps the spawner itself intact.
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!plugin.getConfig().getBoolean("tnt-spawner.protect-block", true)) return;
        event.blockList().removeIf(block -> blocks.contains(serialize(block)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!plugin.getConfig().getBoolean("tnt-spawner.protect-block", true)) return;
        event.blockList().removeIf(block -> blocks.contains(serialize(block)));
    }

    /** Runs on a timer set by tnt-spawner.interval-seconds. */
    public void tick() {
        if (blocks.isEmpty()) return;
        int r = radius();
        int fuse = plugin.getConfig().getInt("tnt-spawner.fuse-ticks", 60);
        int perCycle = plugin.getConfig().getInt("tnt-spawner.tnt-per-cycle", 1);

        for (String key : new ArrayList<>(blocks)) {
            String[] parts = key.split(";");
            if (parts.length < 4) continue;
            World world = plugin.getServer().getWorld(parts[0]);
            if (world == null) continue;
            int x, y, z;
            try {
                x = Integer.parseInt(parts[1]);
                y = Integer.parseInt(parts[2]);
                z = Integer.parseInt(parts[3]);
            } catch (NumberFormatException ex) {
                continue;
            }
            Location location = new Location(world, x + 0.5,
                    y + 1.0 + plugin.getConfig().getDouble("tnt-spawner.spawn-height", 0.6),
                    z + 0.5);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

            // The block may have been blown up by its own output.
            if (world.getBlockAt(x, y, z).getType() != Material.SPAWNER) {
                blocks.remove(key);
                save();
                continue;
            }

            boolean nearby = false;
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(location) <= (double) r * r) {
                    nearby = true;
                    break;
                }
            }
            if (!nearby) continue;

            for (int i = 0; i < Math.max(1, perCycle); i++) {
                TNTPrimed tnt = world.spawn(location, TNTPrimed.class);
                tnt.setFuseTicks(Math.max(10, fuse));
            }
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        blocks.addAll(yml.getStringList("blocks"));
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("blocks", new ArrayList<>(blocks));
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed saving tnt spawners: " + ex.getMessage());
        }
    }

    public int count() {
        return blocks.size();
    }
}
