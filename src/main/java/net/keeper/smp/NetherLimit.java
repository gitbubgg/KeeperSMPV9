package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.io.File;
import java.io.IOException;

/**
 * Ancient debris is treated as a finite resource: once the configured total
 * has been mined out of the world, no more of it can be broken, whether it
 * is in an already-generated chunk or one that generates later.
 */
public class NetherLimit implements Listener {

    private final KeeperPlugin plugin;
    private final File file;
    private int remaining;

    public NetherLimit(KeeperPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "nether-limit.yml");
        load();
    }

    private int startingAmount() {
        return plugin.getConfig().getInt("nether-limit.ancient-debris", 25000);
    }

    private void load() {
        if (!file.exists()) {
            remaining = startingAmount();
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        remaining = yml.getInt("ancient-debris-remaining", startingAmount());
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("ancient-debris-remaining", remaining);
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed saving nether-limit.yml: " + ex.getMessage());
        }
    }

    public int remaining() {
        return remaining;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.ANCIENT_DEBRIS) return;
        if (event.getBlock().getWorld().getEnvironment() != World.Environment.NETHER) return;

        Player player = event.getPlayer();
        if (remaining <= 0) {
            event.setCancelled(true);
            player.sendMessage(Util.text("&cEvery bit of ancient debris in this world has already been mined out."));
            return;
        }
        remaining--;
        save();
        if (remaining == 0) {
            Bukkit.broadcast(Util.text("&6[Nether] &7The last piece of ancient debris in this world has just been mined."));
        } else if (remaining <= 100) {
            player.sendActionBar(Util.text("&6" + remaining + " &7ancient debris left in the world"));
        }
    }
}
