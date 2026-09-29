package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Death drops go into a chest at the death spot instead of scattering on the
 * ground, so nothing despawns or gets grabbed by a bystander. Only the owner
 * (or staff) can open it; it auto-expires and drops normally if never
 * collected, and clears itself out once emptied.
 */
public class DeathChest implements Listener {

    private record Entry(UUID owner, String ownerName, long expiry) {
    }

    private final KeeperPlugin plugin;
    private final Map<String, Entry> chests = new HashMap<>();
    private final File file;

    public DeathChest(KeeperPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "deathchests.yml");
        load();
    }

    private String key(Location loc) {
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (event.getDrops().isEmpty()) return;
        Player player = event.getEntity();
        Location loc = player.getLocation().getBlock().getLocation();
        Block block = loc.getBlock();
        block.setType(Material.CHEST);
        if (!(block.getState() instanceof Chest chest)) return;

        for (ItemStack item : event.getDrops()) {
            Map<Integer, ItemStack> leftover = chest.getBlockInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                block.getWorld().dropItemNaturally(loc, drop);
            }
        }
        event.getDrops().clear();
        chest.update();

        int minutes = plugin.getConfig().getInt("deathchest.minutes", 10);
        long expiry = System.currentTimeMillis() + minutes * 60_000L;
        chests.put(key(loc), new Entry(player.getUniqueId(), player.getName(), expiry));
        save();
        player.sendMessage(Util.text("&cYour items are in a death chest at your death location."));
        player.sendMessage(Util.text("&7It stays for &f" + minutes + " minutes&7, only you (or staff) can open it."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getInventory().getHolder() instanceof Chest chest)) return;
        Entry entry = chests.get(key(chest.getLocation()));
        if (entry == null) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        if (player.getUniqueId().equals(entry.owner()) || plugin.ranks().of(player).isStaff()) return;
        event.setCancelled(true);
        player.sendMessage(Util.text("&cThat death chest belongs to &f" + entry.ownerName() + "&c."));
    }

    /**
     * Without this, a hopper (or dropper/dispenser) sitting next to the death
     * spot siphons the loot out within a tick or two of the chest appearing -
     * then tick()'s "remove once emptied" cleanup deletes the chest right
     * after, looking exactly like it despawned with nothing in it.
     */
    @EventHandler(ignoreCancelled = true)
    public void onHopperPull(InventoryMoveItemEvent event) {
        if (!(event.getSource().getHolder() instanceof Chest chest)) return;
        if (chests.containsKey(key(chest.getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        String key = key(event.getBlock().getLocation());
        Entry entry = chests.get(key);
        if (entry == null) return;
        Player player = event.getPlayer();
        if (!player.getUniqueId().equals(entry.owner()) && !plugin.ranks().of(player).isStaff()) {
            event.setCancelled(true);
            player.sendMessage(Util.text("&cThat death chest belongs to &f" + entry.ownerName() + "&c."));
            return;
        }
        chests.remove(key);
        save();
    }

    /** Runs periodically: clears out emptied or expired chests. */
    public void tick() {
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (var it = chests.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Entry> e = it.next();
            String[] parts = e.getKey().split(";");
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                it.remove();
                changed = true;
                continue;
            }
            Block block = world.getBlockAt(Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            if (block.getType() != Material.CHEST) {
                it.remove();
                changed = true;
                continue;
            }
            if (block.getState() instanceof Chest chest && chest.getBlockInventory().isEmpty()) {
                block.setType(Material.AIR);
                it.remove();
                changed = true;
                continue;
            }
            if (now > e.getValue().expiry()) {
                if (block.getState() instanceof Chest chest) {
                    for (ItemStack item : chest.getBlockInventory().getContents()) {
                        if (item != null) block.getWorld().dropItemNaturally(block.getLocation(), item);
                    }
                }
                block.setType(Material.AIR);
                it.remove();
                changed = true;
            }
        }
        if (changed) save();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            try {
                UUID owner = UUID.fromString(yml.getString(key + ".owner"));
                String name = yml.getString(key + ".owner-name", "?");
                long expiry = yml.getLong(key + ".expiry");
                chests.put(key, new Entry(owner, name, expiry));
            } catch (Exception ignored) {
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, Entry> e : chests.entrySet()) {
            yml.set(e.getKey() + ".owner", e.getValue().owner().toString());
            yml.set(e.getKey() + ".owner-name", e.getValue().ownerName());
            yml.set(e.getKey() + ".expiry", e.getValue().expiry());
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed saving death chests: " + ex.getMessage());
        }
    }
}
