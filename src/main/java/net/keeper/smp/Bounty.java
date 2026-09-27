package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** /bounty places a price on a player's head, paid out to whoever kills them. */
public class Bounty implements Listener {

    private final KeeperPlugin plugin;
    private final Map<UUID, Double> bounties = new LinkedHashMap<>();
    private final File file;

    public Bounty(KeeperPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bounties.yml");
        load();
    }

    public double on(UUID uuid) {
        return bounties.getOrDefault(uuid, 0.0);
    }

    public void place(Player placer, Player target, double amount) {
        if (target.equals(placer)) {
            placer.sendMessage(Util.text("&cYou cannot put a bounty on yourself."));
            return;
        }
        if (amount <= 0) {
            placer.sendMessage(Util.text("&cAmount has to be above 0."));
            return;
        }
        if (!plugin.econ().withdraw(placer.getUniqueId(), amount)) {
            placer.sendMessage(Util.text("&cYou do not have that much."));
            return;
        }
        bounties.merge(target.getUniqueId(), amount, Double::sum);
        save();
        placer.sendMessage(Util.text("&aPlaced a bounty of &f" + plugin.econ().fmt(amount)
                + " &aon &f" + target.getName() + "&a."));
        Bukkit.broadcast(Util.text("&c[Bounty] &f" + target.getName() + " &7now has a bounty of &f"
                + plugin.econ().fmt(on(target.getUniqueId())) + " &7on their head."));
    }

    public void list(Player sender) {
        if (bounties.isEmpty()) {
            sender.sendMessage(Util.text("&7No bounties right now."));
            return;
        }
        sender.sendMessage(Util.text("&8&m----------------------------"));
        sender.sendMessage(Util.text(" &c&lBounties"));
        bounties.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(e -> {
                    Data.PlayerData cached = plugin.data().getIfLoaded(e.getKey());
                    String name = cached != null ? cached.name : e.getKey().toString();
                    sender.sendMessage(Util.text(" &f" + name + " &7- &c" + plugin.econ().fmt(e.getValue())));
                });
        sender.sendMessage(Util.text("&8&m----------------------------"));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Double amount = bounties.remove(victim.getUniqueId());
        if (amount == null || amount <= 0) return;
        save();
        Player killer = victim.getKiller();
        if (killer == null) return;
        plugin.econ().deposit(killer.getUniqueId(), amount);
        Bukkit.broadcast(Util.text("&c[Bounty] &f" + killer.getName() + " &7collected &f"
                + plugin.econ().fmt(amount) + " &7for killing &f" + victim.getName() + "&7."));
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            try {
                bounties.put(UUID.fromString(key), yml.getDouble(key));
            } catch (Exception ignored) {
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : bounties.entrySet()) {
            yml.set(e.getKey().toString(), e.getValue());
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed saving bounties: " + ex.getMessage());
        }
    }
}
