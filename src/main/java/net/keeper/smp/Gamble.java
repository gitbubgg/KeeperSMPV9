package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Two ways to gamble money: /gamble <player> <amount> challenges a specific
 * player (accept/deny, same shape as /tpa); /gamble queue <amount> joins a
 * pool of players wagering that exact amount and gets auto-matched with
 * whoever else is waiting there, first come first served. Either way, both
 * sides put the amount up, a coin flip decides the winner, and the winner
 * gets the pot minus a small house cut.
 */
public class Gamble implements Listener {

    public record Challenge(UUID from, double amount, long expiry) {
    }

    private final KeeperPlugin plugin;
    private final Map<UUID, Challenge> challenges = new HashMap<>();
    private final Map<Double, Deque<UUID>> queues = new HashMap<>();
    private final Random random = new Random();

    public Gamble(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- direct challenge ----------------

    public void challenge(Player sender, Player target, double amount) {
        if (sender.equals(target)) {
            sender.sendMessage(Util.text("&cYou cannot gamble against yourself."));
            return;
        }
        if (amount <= 0) {
            sender.sendMessage(Util.text("&cAmount must be positive."));
            return;
        }
        if (plugin.econ().balance(sender.getUniqueId()) < amount) {
            sender.sendMessage(Util.text("&cYou don't have " + plugin.econ().fmt(amount) + "."));
            return;
        }
        challenges.put(target.getUniqueId(), new Challenge(sender.getUniqueId(), amount,
                System.currentTimeMillis() + 60_000L));
        sender.sendMessage(Util.text("&aGamble challenge sent to &f" + target.getName()
                + " &afor " + plugin.econ().fmt(amount) + ". It lasts 60s."));
        target.sendMessage(Util.text("&8&m----------------------------"));
        target.sendMessage(Util.text(" &f" + sender.getName() + " &7wants to gamble &f"
                + plugin.econ().fmt(amount) + " &7with you - winner takes the pot."));
        target.sendMessage(Util.text(" &a/gamble accept &7or &c/gamble deny"));
        target.sendMessage(Util.text("&8&m----------------------------"));
    }

    public void accept(Player target) {
        Challenge challenge = challenges.remove(target.getUniqueId());
        if (challenge == null || System.currentTimeMillis() > challenge.expiry()) {
            target.sendMessage(Util.text("&cYou have no pending gamble challenge."));
            return;
        }
        Player sender = Bukkit.getPlayer(challenge.from());
        if (sender == null) {
            target.sendMessage(Util.text("&cThat player went offline."));
            return;
        }
        resolve(sender, target, challenge.amount());
    }

    public void deny(Player target) {
        Challenge challenge = challenges.remove(target.getUniqueId());
        if (challenge == null) {
            target.sendMessage(Util.text("&cYou have no pending gamble challenge."));
            return;
        }
        target.sendMessage(Util.text("&cGamble declined."));
        Player sender = Bukkit.getPlayer(challenge.from());
        if (sender != null) sender.sendMessage(Util.text("&c" + target.getName() + " declined your gamble."));
    }

    // ---------------- random matchmaking ----------------

    public void queue(Player player, double amount) {
        if (amount <= 0) {
            player.sendMessage(Util.text("&cAmount must be positive."));
            return;
        }
        if (plugin.econ().balance(player.getUniqueId()) < amount) {
            player.sendMessage(Util.text("&cYou don't have " + plugin.econ().fmt(amount) + "."));
            return;
        }
        Deque<UUID> pool = queues.computeIfAbsent(amount, k -> new ArrayDeque<>());
        if (pool.contains(player.getUniqueId())) {
            player.sendMessage(Util.text("&7You are already queued for that amount."));
            return;
        }
        pool.add(player.getUniqueId());
        player.sendMessage(Util.text("&aQueued to gamble &f" + plugin.econ().fmt(amount)
                + " &awith a random player. Waiting for an opponent..."));
        tryMatch(amount);
    }

    public void leaveQueue(Player player) {
        boolean removed = false;
        for (Deque<UUID> pool : queues.values()) {
            removed |= pool.remove(player.getUniqueId());
        }
        player.sendMessage(Util.text(removed ? "&7Left the gamble queue." : "&cYou are not queued."));
    }

    private void tryMatch(double amount) {
        Deque<UUID> pool = queues.get(amount);
        if (pool == null) return;
        while (pool.size() >= 2) {
            Player a = Bukkit.getPlayer(pool.poll());
            Player b = Bukkit.getPlayer(pool.poll());
            if (a == null || !a.isOnline()) {
                if (b != null && b.isOnline()) pool.addFirst(b.getUniqueId());
                continue;
            }
            if (b == null || !b.isOnline()) {
                pool.addFirst(a.getUniqueId());
                continue;
            }
            if (plugin.econ().balance(a.getUniqueId()) < amount) {
                a.sendMessage(Util.text("&cYou no longer have enough to cover your gamble queue - removed."));
                continue;
            }
            if (plugin.econ().balance(b.getUniqueId()) < amount) {
                b.sendMessage(Util.text("&cYou no longer have enough to cover your gamble queue - removed."));
                pool.addFirst(a.getUniqueId());
                continue;
            }
            resolve(a, b, amount);
        }
    }

    // ---------------- resolution ----------------

    private void resolve(Player a, Player b, double amount) {
        if (!plugin.econ().withdraw(a.getUniqueId(), amount)) {
            a.sendMessage(Util.text("&cYou don't have " + plugin.econ().fmt(amount) + " anymore."));
            b.sendMessage(Util.text("&c" + a.getName() + " could no longer cover the wager."));
            return;
        }
        if (!plugin.econ().withdraw(b.getUniqueId(), amount)) {
            plugin.econ().deposit(a.getUniqueId(), amount);
            b.sendMessage(Util.text("&cYou don't have " + plugin.econ().fmt(amount) + " anymore."));
            a.sendMessage(Util.text("&c" + b.getName() + " could no longer cover the wager."));
            return;
        }

        Player winner = random.nextBoolean() ? a : b;
        Player loser = winner == a ? b : a;

        double pot = amount * 2;
        double cut = plugin.getConfig().getDouble("gamble.house-cut-percent", 0.05);
        double payout = pot * (1 - cut);
        plugin.econ().deposit(winner.getUniqueId(), payout);

        winner.sendMessage(Util.text("&aYou won the gamble against &f" + loser.getName()
                + "&a! &7+" + plugin.econ().fmt(payout)));
        loser.sendMessage(Util.text("&cYou lost &f" + plugin.econ().fmt(amount)
                + " &cgambling against &f" + winner.getName() + "&c."));
        Bukkit.broadcast(Util.text("&6[Gamble] &f" + winner.getName() + " &7won &f"
                + plugin.econ().fmt(payout) + " &7off &f" + loser.getName() + "&7."));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        challenges.remove(uuid);
        challenges.values().removeIf(c -> c.from().equals(uuid));
        for (Deque<UUID> pool : queues.values()) pool.remove(uuid);
    }
}
