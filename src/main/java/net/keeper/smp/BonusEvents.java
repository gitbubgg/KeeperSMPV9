package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Random;

/**
 * A server-wide surprise reward on a fixed schedule. The timing is fixed so
 * players learn to expect it; the amount is randomised so it stays a
 * surprise.
 */
public class BonusEvents {

    private final KeeperPlugin plugin;
    private final Random random = new Random();

    public BonusEvents(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    public void tick() {
        if (!plugin.getConfig().getBoolean("bonus-events.enabled", true)) return;
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        double minMoney = plugin.getConfig().getDouble("bonus-events.money-min", 100);
        double maxMoney = plugin.getConfig().getDouble("bonus-events.money-max", 500);
        long minShards = plugin.getConfig().getLong("bonus-events.shards-min", 5);
        long maxShards = plugin.getConfig().getLong("bonus-events.shards-max", 25);

        double money = minMoney + random.nextDouble() * Math.max(0, maxMoney - minMoney);
        long shardSpread = Math.max(0, maxShards - minShards);
        long shards = minShards + (shardSpread > 0 ? random.nextInt((int) shardSpread + 1) : 0);

        Bukkit.broadcast(Util.text("&8&m----------------------------"));
        Bukkit.broadcast(Util.text(" &6&lBonus wave! &7Everyone online just got a surprise."));
        Bukkit.broadcast(Util.text(" &a+ " + plugin.econ().fmt(money) + "  &b+ " + shards + " shards"));
        Bukkit.broadcast(Util.text("&8&m----------------------------"));

        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.econ().deposit(player.getUniqueId(), money);
            plugin.shards().give(player.getUniqueId(), shards);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.6f);
            plugin.data().save(player.getUniqueId());
        }
    }
}
