package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Heuristic macro/autoclicker flag, same spirit as XrayGuard: not proof,
 * just a "go take a look" ping for staff. Two independent tells:
 *  - sustained superhuman click rate while attacking
 *  - repeatedly acting (breaking blocks/attacking) with zero movement or
 *    camera change, the classic sign of an unattended AFK macro
 */
public class MacroGuard implements Listener {

    private static class Tracker {
        final Deque<Long> hits = new ArrayDeque<>();
        Location stillSince;
        int actionsWhileStill = 0;
        long lastAlert = 0L;
    }

    private final KeeperPlugin plugin;
    private final Map<UUID, Tracker> trackers = new HashMap<>();

    public MacroGuard(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    private Tracker tracker(Player player) {
        return trackers.computeIfAbsent(player.getUniqueId(), k -> new Tracker());
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (plugin.ranks().of(player).isStaff()) return;
        Tracker tracker = tracker(player);

        long now = System.currentTimeMillis();
        tracker.hits.addLast(now);
        while (!tracker.hits.isEmpty() && now - tracker.hits.peekFirst() > 5000L) tracker.hits.pollFirst();

        int cpsLimit = plugin.getConfig().getInt("macro.max-sustained-cps", 15);
        if (tracker.hits.size() > cpsLimit * 5) {
            alert(player, player.getName() + " &7is swinging at &f" + (tracker.hits.size() / 5)
                    + " &7hits/sec sustained.");
            tracker.hits.clear();
        }
        trackAction(player, tracker);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (plugin.ranks().of(player).isStaff()) return;
        trackAction(player, tracker(player));
    }

    private void trackAction(Player player, Tracker tracker) {
        Location loc = player.getLocation();
        if (tracker.stillSince != null && sameSpot(tracker.stillSince, loc)) {
            tracker.actionsWhileStill++;
        } else {
            tracker.stillSince = loc.clone();
            tracker.actionsWhileStill = 1;
        }

        int threshold = plugin.getConfig().getInt("macro.still-action-threshold", 80);
        long now = System.currentTimeMillis();
        if (tracker.actionsWhileStill >= threshold && now - tracker.lastAlert > 60_000L) {
            alert(player, player.getName() + " &7has acted &f" + tracker.actionsWhileStill
                    + " &7times with no movement or camera change. Possible AFK macro.");
            tracker.lastAlert = now;
            tracker.actionsWhileStill = 0;
        }
    }

    private boolean sameSpot(Location a, Location b) {
        return a.getWorld().equals(b.getWorld())
                && a.distanceSquared(b) < 0.0001
                && a.getYaw() == b.getYaw()
                && a.getPitch() == b.getPitch();
    }

    private void alert(Player player, String detail) {
        String message = "&c[Macro?] &f" + detail;
        plugin.getLogger().warning(Util.strip(message));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (plugin.ranks().of(online).isStaff()) online.sendMessage(Util.text(message));
        }
    }
}
