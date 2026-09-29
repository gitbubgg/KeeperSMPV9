package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class Teleport implements Listener {

    public enum Kind { TPA, TPAHERE }

    public record Request(UUID from, Kind kind, long expiry) {
    }

    private final KeeperPlugin plugin;
    private final Map<UUID, Long> combat = new HashMap<>();
    private final Map<UUID, Request> requests = new HashMap<>();
    private final Deque<UUID> rtpQueue = new ArrayDeque<>();
    private final Random random = new Random();

    private static final Material[] UNSAFE = {
            Material.LAVA, Material.WATER, Material.FIRE, Material.CACTUS,
            Material.MAGMA_BLOCK, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH
    };

    public Teleport(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- combat ----------------

    public int combatSeconds() {
        return plugin.getConfig().getInt("general.combat-tag-seconds", 20);
    }

    public void tag(Player player) {
        combat.put(player.getUniqueId(), System.currentTimeMillis() + combatSeconds() * 1000L);
    }

    public boolean inCombat(Player player) {
        Long until = combat.get(player.getUniqueId());
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            combat.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public long combatLeft(Player player) {
        Long until = combat.get(player.getUniqueId());
        if (until == null) return 0;
        return Math.max(0, (until - System.currentTimeMillis()) / 1000);
    }

    /** True if the player may not teleport right now, with a message sent. */
    public boolean blockedByCombat(Player player) {
        if (!inCombat(player)) return false;
        player.sendMessage(Util.text("&cYou are in combat. Wait &f" + combatLeft(player) + "s&c."));
        return true;
    }

    /**
     * Runs before Protect's spawn-PvP cancel (which is default priority), so
     * punching someone in the lobby still tags both of you for combat even
     * though Protect stops the hit from actually landing.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player victim = event.getEntity() instanceof Player p ? p : null;
        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (victim == null || attacker == null || victim.equals(attacker)) return;
        tag(victim);
        tag(attacker);
        victim.sendActionBar(Util.text("&cIn combat for " + combatSeconds() + "s"));
        attacker.sendActionBar(Util.text("&cIn combat for " + combatSeconds() + "s"));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        combat.remove(player.getUniqueId());
        plugin.data().get(player.getUniqueId()).backLocation = Util.serialize(player.getLocation());
    }

    /** Logging out mid-fight kills you on the spot instead of safely vanishing. */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (inCombat(player) && player.getHealth() > 0) {
            player.setHealth(0);
        }
    }

    // ---------------- warmup ----------------

    public void withWarmup(Player player, Location destination, String label) {
        int warmup = plugin.ranks().of(player).tpWarmup;
        if (warmup <= 0) {
            doTeleport(player, destination, label);
            return;
        }
        player.sendMessage(Util.text("&7Teleporting in &f" + warmup + "s&7. Do not take damage."));
        Location start = player.getLocation();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (inCombat(player)) {
                player.sendMessage(Util.text("&cTeleport cancelled, you took damage."));
                return;
            }
            if (start.getWorld() != null && start.distanceSquared(player.getLocation()) > 4) {
                player.sendMessage(Util.text("&cTeleport cancelled, you moved."));
                return;
            }
            doTeleport(player, destination, label);
        }, warmup * 20L);
    }

    public void doTeleport(Player player, Location destination, String label) {
        plugin.data().get(player.getUniqueId()).backLocation = Util.serialize(player.getLocation());
        player.teleportAsync(destination).thenAccept(ok -> {
            if (ok && label != null) player.sendMessage(Util.text("&aTeleported to &f" + label + "&a."));
        });
    }

    // ---------------- tpa ----------------

    public void request(Player sender, Player target, Kind kind) {
        if (sender.equals(target)) {
            sender.sendMessage(Util.text("&cYou cannot send that to yourself."));
            return;
        }
        if (blockedByCombat(sender)) return;

        Data.PlayerData targetData = plugin.data().get(target.getUniqueId());
        boolean accepting = kind == Kind.TPA ? targetData.acceptTpa : targetData.acceptTpaHere;
        if (!accepting && !plugin.ranks().of(sender).isStaff()) {
            sender.sendMessage(Util.text("&c" + target.getName() + " has those requests switched off."));
            return;
        }

        requests.put(target.getUniqueId(), new Request(sender.getUniqueId(), kind,
                System.currentTimeMillis() + 60_000L));
        sender.sendMessage(Util.text("&aRequest sent to &f" + target.getName() + "&a. It lasts 60s."));
        target.sendMessage(Util.text("&8&m----------------------------"));
        if (kind == Kind.TPA) {
            target.sendMessage(Util.text(" &f" + sender.getName() + " &7wants to teleport to you."));
        } else {
            target.sendMessage(Util.text(" &f" + sender.getName() + " &7wants you to teleport to them."));
        }
        target.sendMessage(Util.text(" &a/tpaccept &7or &c/tpdeny"));
        target.sendMessage(Util.text("&8&m----------------------------"));
    }

    public void accept(Player target) {
        Request request = requests.remove(target.getUniqueId());
        if (request == null || System.currentTimeMillis() > request.expiry()) {
            target.sendMessage(Util.text("&cYou have no pending request."));
            return;
        }
        Player sender = Bukkit.getPlayer(request.from());
        if (sender == null) {
            target.sendMessage(Util.text("&cThat player went offline."));
            return;
        }
        if (request.kind() == Kind.TPA) {
            if (blockedByCombat(sender)) return;
            withWarmup(sender, target.getLocation(), target.getName());
            target.sendMessage(Util.text("&aAccepted. &f" + sender.getName() + " &ais on the way."));
        } else {
            if (blockedByCombat(target)) return;
            withWarmup(target, sender.getLocation(), sender.getName());
            sender.sendMessage(Util.text("&f" + target.getName() + " &aaccepted your request."));
        }
    }

    public void deny(Player target) {
        Request request = requests.remove(target.getUniqueId());
        if (request == null) {
            target.sendMessage(Util.text("&cYou have no pending request."));
            return;
        }
        target.sendMessage(Util.text("&cRequest denied."));
        Player sender = Bukkit.getPlayer(request.from());
        if (sender != null) sender.sendMessage(Util.text("&c" + target.getName() + " denied your request."));
    }

    // ---------------- random teleport ----------------

    public void rtp(Player player, String[] args) {
        if (blockedByCombat(player)) return;
        String arg = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "overworld";

        World target;
        String label;
        boolean nether = false;
        switch (arg) {
            case "nether" -> {
                target = dimension("general.rtp-nether-worlds", World.Environment.NETHER);
                label = "the Nether";
                nether = true;
            }
            case "end", "the_end" -> {
                target = dimension("general.rtp-end-worlds", World.Environment.THE_END);
                label = "the End";
            }
            case "sift" -> {
                target = Bukkit.getWorld(plugin.getConfig().getString("general.rtp-sift-world", "sift"));
                label = "the Sift";
            }
            case "overworld", "world" -> {
                target = world();
                label = "the wild";
            }
            default -> {
                player.sendMessage(Util.text("&cUsage: &f/rtp &7[nether|end|sift]"));
                return;
            }
        }
        if (target == null) {
            player.sendMessage(Util.text("&cThat dimension isn't loaded on this server."));
            return;
        }

        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        int cooldown = plugin.ranks().of(player).rtpCooldown;
        long since = (System.currentTimeMillis() - data.lastRtp) / 1000;
        if (cooldown > 0 && since < cooldown) {
            player.sendMessage(Util.text("&c/rtp is cooling down for another &f" + (cooldown - since) + "s&c."));
            return;
        }
        player.sendMessage(Util.text("&7Looking for somewhere to drop you..."));
        World finalTarget = target;
        CompletableFuture<Location> future = nether
                ? findSafeNether(finalTarget, 40)
                : findSafe(finalTarget, arg.equals("end") || arg.equals("the_end") ? 60 : 24);
        future.thenAccept(location -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (location == null) {
                player.sendMessage(Util.text("&cCould not find a safe spot. Try again."));
                return;
            }
            data.lastRtp = System.currentTimeMillis();
            withWarmup(player, location, label);
        }));
    }

    /** Looks up a configured world list first, falling back to the first loaded world of that environment. */
    private World dimension(String configPath, World.Environment environment) {
        for (String name : plugin.getConfig().getStringList(configPath)) {
            World world = Bukkit.getWorld(name);
            if (world != null) return world;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == environment) return world;
        }
        return null;
    }

    public void rtpQueue(Player player) {
        if (blockedByCombat(player)) return;
        if (rtpQueue.contains(player.getUniqueId())) {
            rtpQueue.remove(player.getUniqueId());
            player.sendMessage(Util.text("&cYou left the drop queue."));
            return;
        }
        rtpQueue.add(player.getUniqueId());
        player.sendMessage(Util.text("&aYou joined the random drop queue. Run &f/rtpq &aagain to leave."));

        if (rtpQueue.size() < 2) return;

        UUID firstId = rtpQueue.poll();
        UUID secondId = rtpQueue.poll();
        Player first = firstId == null ? null : Bukkit.getPlayer(firstId);
        Player second = secondId == null ? null : Bukkit.getPlayer(secondId);
        if (first == null || second == null) {
            if (first != null) rtpQueue.add(first.getUniqueId());
            if (second != null) rtpQueue.add(second.getUniqueId());
            return;
        }
        findSafe(world(), 24).thenAccept(location -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (location == null) {
                first.sendMessage(Util.text("&cCould not find a drop site. Try again."));
                second.sendMessage(Util.text("&cCould not find a drop site. Try again."));
                return;
            }
            for (Player p : new Player[]{first, second}) {
                p.sendMessage(Util.text("&6Dropping you in with &f"
                        + (p.equals(first) ? second.getName() : first.getName()) + "&6. Good luck."));
                doTeleport(p, location.clone().add(random.nextInt(9) - 4, 0, random.nextInt(9) - 4), null);
            }
        }));
    }

    private World world() {
        for (String name : plugin.getConfig().getStringList("general.rtp-worlds")) {
            World world = Bukkit.getWorld(name);
            if (world != null) return world;
        }
        return Bukkit.getWorlds().get(0);
    }

    /** Hunt for a safe surface location, checking chunks off the main thread. */
    private CompletableFuture<Location> findSafe(World world, int attempts) {
        CompletableFuture<Location> result = new CompletableFuture<>();
        attempt(world, attempts, result);
        return result;
    }

    private void attempt(World world, int left, CompletableFuture<Location> result) {
        if (left <= 0) {
            result.complete(null);
            return;
        }
        int max = plugin.getConfig().getInt("general.rtp-radius", 5000);
        int min = plugin.getConfig().getInt("general.rtp-min-radius", 250);
        int x = pick(min, max);
        int z = pick(min, max);
        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            int y = world.getHighestBlockYAt(x, z);
            Location candidate = new Location(world, x + 0.5, y + 1, z + 0.5);
            Material ground = world.getBlockAt(x, y, z).getType();
            boolean safe = ground.isSolid();
            for (Material bad : UNSAFE) {
                if (ground == bad) safe = false;
            }
            if (safe && y > world.getMinHeight() + 2) {
                result.complete(candidate);
            } else {
                attempt(world, left - 1, result);
            }
        }).exceptionally(ex -> {
            attempt(world, left - 1, result);
            return null;
        });
    }

    /**
     * The Nether has a solid bedrock roof, so surface-scan logic would just
     * drop everyone on top of it. Instead scan down each column for the
     * first 2-block air pocket over safe, non-lava ground.
     */
    private CompletableFuture<Location> findSafeNether(World world, int attempts) {
        CompletableFuture<Location> result = new CompletableFuture<>();
        attemptNether(world, attempts, result);
        return result;
    }

    private void attemptNether(World world, int left, CompletableFuture<Location> result) {
        if (left <= 0) {
            result.complete(null);
            return;
        }
        int max = plugin.getConfig().getInt("general.rtp-nether-radius", 3000);
        int min = plugin.getConfig().getInt("general.rtp-nether-min-radius", 100);
        int x = pick(min, max);
        int z = pick(min, max);
        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            Location found = netherPocket(world, x, z);
            if (found != null) {
                result.complete(found);
            } else {
                attemptNether(world, left - 1, result);
            }
        }).exceptionally(ex -> {
            attemptNether(world, left - 1, result);
            return null;
        });
    }

    private Location netherPocket(World world, int x, int z) {
        int top = Math.min(world.getMaxHeight() - 3, 120);
        int bottom = world.getMinHeight() + 5;
        for (int y = top; y >= bottom; y--) {
            Material floor = world.getBlockAt(x, y, z).getType();
            if (!floor.isSolid() || isUnsafe(floor)) continue;
            Material feet = world.getBlockAt(x, y + 1, z).getType();
            Material head = world.getBlockAt(x, y + 2, z).getType();
            if (feet.isAir() && head.isAir()) {
                return new Location(world, x + 0.5, y + 1, z + 0.5);
            }
        }
        return null;
    }

    private boolean isUnsafe(Material material) {
        for (Material bad : UNSAFE) {
            if (material == bad) return true;
        }
        return false;
    }

    private int pick(int min, int max) {
        int span = Math.max(1, max - min);
        int value = min + random.nextInt(span);
        return random.nextBoolean() ? value : -value;
    }

    // ---------------- back ----------------

    public void back(Player player) {
        if (blockedByCombat(player)) return;
        Rank rank = plugin.ranks().of(player);
        if ("none".equalsIgnoreCase(rank.back)) {
            player.sendMessage(Util.text("&c/back needs Keeper+ or higher."));
            return;
        }
        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        if ("limited".equalsIgnoreCase(rank.back)) {
            long since = (System.currentTimeMillis() - data.lastBack) / 1000;
            if (since < rank.backCooldown) {
                player.sendMessage(Util.text("&c/back is cooling down for another &f"
                        + (rank.backCooldown - since) + "s&c."));
                return;
            }
        }
        Location destination = data.back();
        if (destination == null) {
            player.sendMessage(Util.text("&cNo previous location on file."));
            return;
        }
        data.lastBack = System.currentTimeMillis();
        withWarmup(player, destination, "your last location");
    }
}
