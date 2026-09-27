package net.keeper.smp;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** /baltop and /playtimetop: scans every saved player file, not just who's online. */
public class Leaderboard {

    private record Row(String name, double balance, long playtimeMillis) {
    }

    private final KeeperPlugin plugin;

    public Leaderboard(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    private List<Row> scan() {
        List<Row> rows = new ArrayList<>();
        File folder = new File(plugin.getDataFolder(), "players");
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return rows;

        for (File file : files) {
            try {
                UUID uuid = UUID.fromString(file.getName().replace(".yml", ""));
                Data.PlayerData live = plugin.data().getIfLoaded(uuid);
                if (live != null) {
                    long playtime = live.playtimeMillis
                            + (live.sessionStart > 0 ? System.currentTimeMillis() - live.sessionStart : 0);
                    rows.add(new Row(live.name, live.balance, playtime));
                    continue;
                }
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
                String name = yml.getString("name", "?");
                if (name.isBlank()) continue;
                rows.add(new Row(name, yml.getDouble("balance", 0), yml.getLong("playtime-millis", 0)));
            } catch (Exception ignored) {
            }
        }
        return rows;
    }

    public void balanceTop(Player sender) {
        List<Row> rows = scan();
        rows.sort(Comparator.comparingDouble(Row::balance).reversed());
        sender.sendMessage(Util.text("&8&m----------------------------"));
        sender.sendMessage(Util.text(" &e&lTop Balances"));
        int rank = 1;
        for (Row row : rows.stream().limit(10).toList()) {
            sender.sendMessage(Util.text(" &7" + rank++ + ". &f" + row.name()
                    + " &7- &a" + plugin.econ().fmt(row.balance())));
        }
        sender.sendMessage(Util.text("&8&m----------------------------"));
    }

    public void playtimeTop(Player sender) {
        List<Row> rows = scan();
        rows.sort(Comparator.comparingLong(Row::playtimeMillis).reversed());
        sender.sendMessage(Util.text("&8&m----------------------------"));
        sender.sendMessage(Util.text(" &b&lTop Playtime"));
        int rank = 1;
        for (Row row : rows.stream().limit(10).toList()) {
            long minutes = row.playtimeMillis() / 60_000L;
            sender.sendMessage(Util.text(" &7" + rank++ + ". &f" + row.name()
                    + " &7- &b" + (minutes / 60) + "h " + (minutes % 60) + "m"));
        }
        sender.sendMessage(Util.text("&8&m----------------------------"));
    }
}
