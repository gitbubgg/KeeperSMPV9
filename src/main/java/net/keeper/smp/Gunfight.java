package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * /gunfight queues two players for a 1v1 duel armed with a reskinned
 * vanilla ranged weapon ("gun"). The pool a player can be randomly handed
 * one from gets better the longer they have played.
 */
public class Gunfight implements Listener {

    private record Tier(int minutes, String name, Material material, int power, boolean multishot) {
    }

    private record SavedState(ItemStack[] contents, ItemStack[] armor, Location location,
                               GameMode gameMode, double health, int food) {
    }

    private final KeeperPlugin plugin;
    private final List<Tier> tiers = new ArrayList<>();
    private final Deque<UUID> queue = new ArrayDeque<>();
    private final Map<UUID, UUID> opponents = new HashMap<>();
    private final Map<UUID, SavedState> saved = new HashMap<>();
    private final Random random = new Random();

    public Gunfight(KeeperPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        tiers.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("gunfight.tiers");
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
            Material material = Material.matchMaterial(s.getString("material", "CROSSBOW"));
            if (material == null) material = Material.CROSSBOW;
            tiers.add(new Tier(minutes, s.getString("name", "&7Gun"), material,
                    Math.max(1, s.getInt("power", 1)), s.getBoolean("multishot", false)));
        }
        tiers.sort(Comparator.comparingInt(Tier::minutes));
    }

    public void join(Player player) {
        UUID uuid = player.getUniqueId();
        if (opponents.containsKey(uuid)) {
            player.sendMessage(Util.text("&cYou are already in a gunfight."));
            return;
        }
        if (queue.contains(uuid)) {
            player.sendMessage(Util.text("&7You are already queued. Waiting for an opponent..."));
            return;
        }
        if (tiers.isEmpty()) {
            player.sendMessage(Util.text("&cGunfight has no configured gun tiers, ask staff to set some up."));
            return;
        }
        queue.add(uuid);
        player.sendMessage(Util.text("&aQueued for &f/gunfight&a. Waiting for an opponent..."));
        tryMatch();
    }

    public void leave(Player player) {
        if (queue.remove(player.getUniqueId())) {
            player.sendMessage(Util.text("&7Left the gunfight queue."));
        } else {
            player.sendMessage(Util.text("&cYou are not queued."));
        }
    }

    private void tryMatch() {
        while (queue.size() >= 2) {
            Player a = Bukkit.getPlayer(queue.poll());
            Player b = Bukkit.getPlayer(queue.poll());
            if (a == null || !a.isOnline()) {
                if (b != null && b.isOnline()) queue.addFirst(b.getUniqueId());
                continue;
            }
            if (b == null || !b.isOnline()) {
                queue.addFirst(a.getUniqueId());
                continue;
            }
            start(a, b);
        }
    }

    /** Staff-set duel spawn point. Falls back to the lobby when unset. */
    private Location arenaSpot(String key, Player fallback) {
        Location stored = Util.deserialize(plugin.getConfig().getString("gunfight.arena." + key));
        return stored != null ? stored : plugin.lobby(fallback.getWorld());
    }

    public void setArena(Player player, int slot) {
        plugin.getConfig().set("gunfight.arena.pos" + slot, Util.serialize(player.getLocation()));
        plugin.saveConfig();
        player.sendMessage(Util.text("&aGunfight arena position " + slot + " set to your location."));
    }

    private void start(Player a, Player b) {
        save(a);
        save(b);
        opponents.put(a.getUniqueId(), b.getUniqueId());
        opponents.put(b.getUniqueId(), a.getUniqueId());

        prepare(a);
        prepare(b);
        a.teleportAsync(arenaSpot("pos1", a));
        b.teleportAsync(arenaSpot("pos2", b));
        arm(a);
        arm(b);

        Bukkit.broadcast(Util.text("&c[Gunfight] &f" + a.getName() + " &7vs &f"
                + b.getName() + " &7has started!"));
    }

    private void save(Player player) {
        PlayerInventory inv = player.getInventory();
        saved.put(player.getUniqueId(), new SavedState(
                inv.getContents().clone(), inv.getArmorContents().clone(),
                player.getLocation().clone(), player.getGameMode(),
                player.getHealth(), player.getFoodLevel()));
    }

    private void prepare(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        if (player.getGameMode() != GameMode.SURVIVAL) player.setGameMode(GameMode.SURVIVAL);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setNoDamageTicks(40);
    }

    private void arm(Player player) {
        Tier tier = randomTierFor(player);
        ItemStack weapon = Util.item(tier.material(), 1, tier.name(), "&7Gunfight weapon");
        weapon.addUnsafeEnchantment(Enchantment.POWER, tier.power());
        if (tier.multishot() && tier.material() == Material.CROSSBOW) {
            weapon.addUnsafeEnchantment(Enchantment.MULTISHOT, 1);
        }
        player.getInventory().addItem(weapon);
        player.getInventory().addItem(new ItemStack(Material.ARROW, 64));
        player.sendMessage(Util.text("&8&m----------------------------"));
        player.sendMessage(Util.text(" &c&lGUNFIGHT! &7Your gun: " + tier.name()));
        player.sendMessage(Util.text("&8&m----------------------------"));
    }

    /** The gun pool is every tier the player's playtime has unlocked; the pick within it is random. */
    private Tier randomTierFor(Player player) {
        long minutes = plugin.playtime().minutes(player.getUniqueId());
        List<Tier> pool = new ArrayList<>();
        for (Tier tier : tiers) {
            if (tier.minutes() <= minutes) pool.add(tier);
        }
        if (pool.isEmpty()) pool.add(tiers.get(0));
        return pool.get(random.nextInt(pool.size()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!opponents.containsKey(victim.getUniqueId())) return;
        if (victim.getHealth() - event.getFinalDamage() > 0) return;
        event.setCancelled(true);
        end(victim, Bukkit.getPlayer(opponents.get(victim.getUniqueId())));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        queue.remove(player.getUniqueId());
        UUID opponent = opponents.get(player.getUniqueId());
        if (opponent != null) end(player, Bukkit.getPlayer(opponent));
    }

    private void end(Player loser, Player winner) {
        opponents.remove(loser.getUniqueId());
        if (winner != null) opponents.remove(winner.getUniqueId());

        restore(loser);
        if (winner == null) return;
        restore(winner);

        double reward = plugin.getConfig().getDouble("gunfight.reward-money", 5000);
        if (reward > 0) plugin.econ().deposit(winner.getUniqueId(), reward);
        winner.sendMessage(Util.text("&aYou won the gunfight! &7+" + plugin.econ().fmt(reward)));
        loser.sendMessage(Util.text("&cYou lost the gunfight against &f" + winner.getName() + "&c."));
        Bukkit.broadcast(Util.text("&c[Gunfight] &f" + winner.getName() + " &7defeated &f" + loser.getName()));
    }

    private void restore(Player player) {
        SavedState state = saved.remove(player.getUniqueId());
        if (state == null) return;
        player.getInventory().setContents(state.contents());
        player.getInventory().setArmorContents(state.armor());
        player.teleportAsync(state.location());
        player.setGameMode(state.gameMode());
        player.setHealth(Math.min(state.health(), 20.0));
        player.setFoodLevel(state.food());
    }
}
