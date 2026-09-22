package net.keeper.smp;

import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /daily - one reward per calendar day. Missing a day resets the streak
 * back to day 1; the reward table cycles after cycle-days.
 */
public class Daily {

    private record Reward(double money, long shards) {
    }

    private final KeeperPlugin plugin;
    private final Map<Integer, Reward> rewards = new LinkedHashMap<>();
    private int cycleDays = 7;

    public Daily(KeeperPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        rewards.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("daily");
        cycleDays = Math.max(1, root == null ? 7 : root.getInt("cycle-days", 7));
        ConfigurationSection days = root == null ? null : root.getConfigurationSection("rewards");
        if (days == null) return;
        for (String key : days.getKeys(false)) {
            int day;
            try {
                day = Integer.parseInt(key);
            } catch (NumberFormatException ex) {
                continue;
            }
            rewards.put(day, new Reward(days.getDouble(key + ".money", 0),
                    days.getLong(key + ".shards", 0)));
        }
    }

    private String today() {
        return LocalDate.now(ZoneId.systemDefault()).toString();
    }

    private String yesterday() {
        return LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString();
    }

    public void claim(Player player) {
        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        String today = today();
        if (today.equals(data.lastDailyDate)) {
            player.sendMessage(Util.text("&cYou already claimed today. Come back tomorrow."));
            return;
        }
        data.dailyStreak = yesterday().equals(data.lastDailyDate) ? data.dailyStreak + 1 : 1;
        if (data.dailyStreak > cycleDays) data.dailyStreak = 1;
        data.lastDailyDate = today;

        Reward reward = rewards.getOrDefault(data.dailyStreak, new Reward(0, 0));
        if (reward.money() > 0) plugin.econ().deposit(player.getUniqueId(), reward.money());
        if (reward.shards() > 0) plugin.shards().give(player.getUniqueId(), reward.shards());
        plugin.data().save(player.getUniqueId());

        player.sendMessage(Util.text("&8&m----------------------------"));
        player.sendMessage(Util.text(" &eDaily reward &7- streak day &f" + data.dailyStreak
                + "&7/" + cycleDays));
        if (reward.money() > 0) {
            player.sendMessage(Util.text(" &a+ " + plugin.econ().fmt(reward.money())));
        }
        if (reward.shards() > 0) {
            player.sendMessage(Util.text(" &b+ " + reward.shards() + " shards"));
        }
        player.sendMessage(Util.text(" &7Come back tomorrow to keep the streak going."));
        player.sendMessage(Util.text("&8&m----------------------------"));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
    }
}
