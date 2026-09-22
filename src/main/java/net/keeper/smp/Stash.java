package net.keeper.smp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A buried bait chest with a hidden skeleton spawner behind the wall. Nothing
 * points to it on the surface, so a player who finds it almost certainly saw
 * through the stone. Opening it pings every staff member online and writes a
 * console line with coordinates.
 *
 * Treat a hit as a lead worth checking, not proof on its own. Someone can
 * stumble onto a stash while mining, so look at how directly they travelled to
 * it before acting.
 */
public class Stash implements Listener {

    private final KeeperPlugin plugin;
    private final Set<String> stashes = new HashSet<>();
    private final File file;

    public Stash(KeeperPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stashes.yml");
        load();
    }

    private String serialize(Block block) {
        return block.getWorld().getName() + ";" + block.getX() + ";" + block.getY()
                + ";" + block.getZ();
    }

    /** Digs the chamber out around the player's feet and fills the bait chest. */
    public void create(Player player) {
        Location base = player.getLocation().getBlock().getLocation();
        if (base.getBlockY() < base.getWorld().getMinHeight() + 6) {
            player.sendMessage(Util.text("&cToo close to the bottom of the world."));
            return;
        }

        // Hollow a 5x3x5 pocket, casing it in stone so nothing shows on the surface.
        for (int x = -2; x <= 2; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    Block block = base.clone().add(x, y, z).getBlock();
                    boolean shell = Math.abs(x) == 2 || Math.abs(z) == 2 || y == -1 || y == 2;
                    block.setType(shell ? Material.STONE : Material.AIR, false);
                }
            }
        }

        Block chestBlock = base.getBlock();
        chestBlock.setType(Material.CHEST, false);
        if (chestBlock.getState() instanceof Chest chest) {
            chest.getBlockInventory().addItem(
                    new ItemStack(Material.DIAMOND_BLOCK, 3),
                    new ItemStack(Material.NETHERITE_SCRAP, 2),
                    new ItemStack(Material.ANCIENT_DEBRIS, 2),
                    new ItemStack(Material.GOLDEN_APPLE, 6),
                    new ItemStack(Material.DIAMOND, 12));
            chest.update();
        }

        // Spawner tucked behind the wall, so it is not visible from inside.
        Block spawnerBlock = base.clone().add(0, 0, 2).getBlock();
        spawnerBlock.setType(Material.SPAWNER, false);
        if (spawnerBlock.getState() instanceof CreatureSpawner spawner) {
            spawner.setSpawnedType(EntityType.SKELETON);
            spawner.setRequiredPlayerRange(4);
            spawner.setSpawnCount(4);
            spawner.setMaxNearbyEntities(8);
            spawner.setDelay(20);
            spawner.update();
        }

        stashes.add(serialize(chestBlock));
        save();

        player.sendMessage(Util.text("&8&m--------------------------------"));
        player.sendMessage(Util.text(" &aStash placed at &f" + base.getBlockX() + ", "
                + base.getBlockY() + ", " + base.getBlockZ()));
        player.sendMessage(Util.text(" &7Bait chest is registered. Staff get a ping if it opens."));
        player.sendMessage(Util.text(" &7Total stashes: &f" + stashes.size()));
        player.sendMessage(Util.text(" &8Seal yourself out on the way up so nothing leads here."));
        player.sendMessage(Util.text("&8&m--------------------------------"));
        plugin.getLogger().info("Stash created by " + player.getName() + " at "
                + base.getBlockX() + " " + base.getBlockY() + " " + base.getBlockZ());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!stashes.contains(serialize(block))) return;

        Player player = event.getPlayer();
        if (plugin.ranks().of(player).isStaff()) return;

        String where = block.getX() + ", " + block.getY() + ", " + block.getZ()
                + " in " + block.getWorld().getName();
        plugin.getLogger().warning("STASH OPENED by " + player.getName() + " at " + where);

        for (Player staff : plugin.getServer().getOnlinePlayers()) {
            if (!plugin.ranks().of(staff).isStaff()) continue;
            staff.sendMessage(Util.text("&c&lSTASH &8| &f" + player.getName()
                    + " &7opened a stash at &f" + where));
            staff.sendMessage(Util.text("&8 Worth a look, but people do find these by accident."));
        }
    }

    public int count() {
        return stashes.size();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        stashes.addAll(yml.getStringList("stashes"));
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        List<String> list = new ArrayList<>(stashes);
        yml.set("stashes", list);
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed saving stashes: " + ex.getMessage());
        }
    }
}
