package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks total playtime per player and pays a one-time reward the first time
 * they cross each configured minute threshold.
 */
public class Playtime implements Listener {

    public record Milestone(int minutes, double money, long shards, String item, int itemAmount) {
    }

    private final KeeperPlugin plugin;
    private final Map<Integer, Milestone> milestones = new LinkedHashMap<>();

    public Playtime(KeeperPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        milestones.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("playtime.milestones");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            int minutes;
            try {
                minutes = Integer.parseInt(key);
            } catch (NumberFormatException ex) {
                continue;
            }
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            milestones.put(minutes, new Milestone(minutes, s.getDouble("money", 0),
                    s.getLong("shards", 0), s.getString("item"), s.getInt("item-amount", 1)));
        }
    }

    /** Total playtime including the current session, in millis. */
    public long millis(UUID uuid) {
        Data.PlayerData data = plugin.data().get(uuid);
        long live = data.sessionStart > 0 ? System.currentTimeMillis() - data.sessionStart : 0;
        return data.playtimeMillis + live;
    }

    public long minutes(UUID uuid) {
        return millis(uuid) / 60_000L;
    }

    /** e.g. "3h 21m" or "42m". */
    public String formatted(UUID uuid) {
        long totalMinutes = minutes(uuid);
        long hours = totalMinutes / 60;
        long mins = totalMinutes % 60;
        return hours > 0 ? hours + "h " + mins + "m" : mins + "m";
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.data().get(event.getPlayer().getUniqueId()).sessionStart = System.currentTimeMillis();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        flush(event.getPlayer().getUniqueId());
    }

    /** Folds the live session into the stored total. Safe to call repeatedly. */
    public void flush(UUID uuid) {
        Data.PlayerData data = plugin.data().getIfLoaded(uuid);
        if (data == null || data.sessionStart == 0L) return;
        long now = System.currentTimeMillis();
        data.playtimeMillis += now - data.sessionStart;
        data.sessionStart = now;
    }

    /** Runs periodically: folds sessions in and pays out any newly-crossed milestone. */
    public void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            flush(uuid);
            Data.PlayerData data = plugin.data().get(uuid);
            long mins = data.playtimeMillis / 60_000L;
            for (Milestone milestone : milestones.values()) {
                if (mins < milestone.minutes()) continue;
                if (data.playtimeMilestones.contains(milestone.minutes())) continue;
                data.playtimeMilestones.add(milestone.minutes());
                grant(player, milestone);
            }
        }
    }

    private void grant(Player player, Milestone milestone) {
        if (milestone.money() > 0) plugin.econ().deposit(player.getUniqueId(), milestone.money());
        if (milestone.shards() > 0) plugin.shards().give(player.getUniqueId(), milestone.shards());
        if (milestone.item() != null && !milestone.item().isBlank()) {
            Material material = Material.matchMaterial(milestone.item());
            if (material != null) {
                ItemStack stack = new ItemStack(material, Math.max(1, milestone.itemAmount()));
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
        }
        plugin.data().save(player.getUniqueId());

        long hours = milestone.minutes() / 60;
        String label = hours > 0
                ? hours + " hour" + (hours == 1 ? "" : "s") + " played"
                : milestone.minutes() + " minutes played";
        player.sendMessage(Util.text("&8&m----------------------------"));
        player.sendMessage(Util.text(" &6Playtime milestone: &f" + label));
        if (milestone.money() > 0) {
            player.sendMessage(Util.text(" &a+ " + plugin.econ().fmt(milestone.money())));
        }
        if (milestone.shards() > 0) {
            player.sendMessage(Util.text(" &b+ " + milestone.shards() + " shards"));
        }
        player.sendMessage(Util.text("&8&m----------------------------"));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
    }
}
