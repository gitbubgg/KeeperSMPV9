package net.keeper.smp;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Locale;

public class Homes {

    private final KeeperPlugin plugin;

    public Homes(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    private String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    public void set(Player player, String rawName) {
        String name = key(rawName == null || rawName.isBlank() ? "home" : rawName);
        if (name.isEmpty()) {
            player.sendMessage(Util.text("&cUse letters, numbers, - and _ only."));
            return;
        }
        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        int limit = plugin.ranks().homeLimit(player);
        boolean replacing = data.homes.containsKey(name);
        if (!replacing && data.homes.size() >= limit) {
            player.sendMessage(Util.text("&cYou are at your limit of &f" + limit + " &chomes."));
            player.sendMessage(Util.text("&7Upgrade with &f/rank &7for more, or remove one with &f/delhome&7."));
            return;
        }
        data.homes.put(name, Util.serialize(player.getLocation()));
        plugin.data().save(player.getUniqueId());
        player.sendMessage(Util.text((replacing ? "&aMoved home &f" : "&aSet home &f") + name
                + " &7(" + data.homes.size() + "/" + limit + ")"));
    }

    public void go(Player player, String rawName) {
        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        if (data.homes.isEmpty()) {
            player.sendMessage(Util.text("&cYou have no homes. Set one with &f/sethome <name>&c."));
            return;
        }
        String name = rawName == null || rawName.isBlank()
                ? data.homes.keySet().iterator().next()
                : key(rawName);
        String raw = data.homes.get(name);
        if (raw == null) {
            player.sendMessage(Util.text("&cNo home called &f" + name + "&c."));
            list(player);
            return;
        }
        Location destination = Util.deserialize(raw);
        if (destination == null) {
            player.sendMessage(Util.text("&cThat home is in a world that is not loaded."));
            return;
        }
        if (plugin.teleport().blockedByCombat(player)) return;
        plugin.teleport().withWarmup(player, destination, name);
    }

    public void delete(Player player, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            player.sendMessage(Util.text("&cUsage: /delhome <name>"));
            return;
        }
        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        if (data.homes.remove(key(rawName)) == null) {
            player.sendMessage(Util.text("&cNo home called &f" + key(rawName) + "&c."));
            return;
        }
        plugin.data().save(player.getUniqueId());
        player.sendMessage(Util.text("&aDeleted home &f" + key(rawName) + "&a."));
    }

    public void list(Player player) {
        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        int limit = plugin.ranks().homeLimit(player);
        player.sendMessage(Util.text("&7Homes &f" + data.homes.size() + "&7/&f" + limit));
        if (data.homes.isEmpty()) {
            player.sendMessage(Util.text("&8 none yet, try /sethome base"));
            return;
        }
        player.sendMessage(Util.text("&f " + String.join("&7, &f", data.homes.keySet())));
    }
}
