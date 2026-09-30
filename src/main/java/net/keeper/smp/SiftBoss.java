package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Switch;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Animals;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Sift's boss room: a hand-placed Deep-Dark-style chamber dug into the
 * Carapace zone (biome labels alone can't reproduce sculk decoration - see
 * SiftBiomeProvider), gated by a retextured lever that only works if you're
 * holding a Sift Ward. That's crafted from an Ichor Marrow, which any
 * animal killed in the Sift has a chance to drop. Flipping the lever with
 * a Ward consumes it, runs a short scripted camera-pan "cutscene", then
 * spawns a buffed, tracked Warden with a boss bar and a guaranteed drop.
 */
public class SiftBoss implements Listener {

    private static final int LAIR_X = 800;
    private static final int LAIR_Y = 30;
    private static final int LAIR_Z = 0;
    private static final double BOSS_HEALTH = 400.0;

    private final KeeperPlugin plugin;
    private final NamespacedKey ichorKey;
    private final NamespacedKey wardKey;
    private final NamespacedKey bossKey;
    private final Set<UUID> frozen = new HashSet<>();

    private Warden boss;
    private BossBar bossBar;

    public SiftBoss(KeeperPlugin plugin) {
        this.plugin = plugin;
        this.ichorKey = new NamespacedKey(plugin, "ichor_marrow");
        this.wardKey = new NamespacedKey(plugin, "sift_ward");
        this.bossKey = new NamespacedKey(plugin, "sift_boss");
    }

    // ---------------- items ----------------

    private ItemStack ichorMarrow() {
        ItemStack item = Util.item(Material.GHAST_TEAR, 1, "&5Ichor Marrow",
                "&7Dropped by animals in the Sift.", "&7Used to craft a Sift Ward.");
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(ichorKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack siftWard() {
        ItemStack item = Util.item(Material.ECHO_SHARD, 1, "&d&lSift Ward",
                "&7Flips the lever in the Sift's hollow.", "&7Consumed on use.");
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(wardKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isSiftWard(ItemStack item) {
        if (item == null || item.getType() != Material.ECHO_SHARD) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(wardKey, PersistentDataType.BYTE);
    }

    /**
     * Registers the Ichor Marrow -> Sift Ward recipe. Called from onEnable
     * every start, so the key is removed first - Bukkit.addRecipe throws if
     * a plugin reload re-registers the same key without a full JVM restart.
     */
    public void registerRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "sift_ward");
        Bukkit.removeRecipe(key);
        ShapelessRecipe recipe = new ShapelessRecipe(key, siftWard());
        recipe.addIngredient(new RecipeChoice.ExactChoice(ichorMarrow()));
        recipe.addIngredient(2, Material.CHERRY_PLANKS);
        recipe.addIngredient(1, Material.TUFF);
        Bukkit.addRecipe(recipe);
    }

    // ---------------- animal drop ----------------

    private ItemStack chitinFragment() {
        return Util.item(Material.PRISMARINE_SHARD, 1, "&dChitin Fragment",
                "&7Shed by animals in the Sift.");
    }

    private ItemStack wardensWhisper() {
        return Util.item(Material.PHANTOM_MEMBRANE, 1, "&5&lWarden's Whisper",
                "&7A rare trace the Sift's animals carry.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onAnimalDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Animals)) return;
        World sift = Bukkit.getWorld(plugin.getConfig().getString("general.rtp-sift-world", "sift"));
        if (sift == null || !event.getEntity().getWorld().equals(sift)) return;
        if (event.getEntity().getKiller() == null) return;
        double ichorChance = plugin.getConfig().getDouble("sift-boss.ichor-drop-chance", 0.25);
        if (Math.random() < ichorChance) {
            event.getDrops().add(ichorMarrow());
        }
        double chitinChance = plugin.getConfig().getDouble("sift-boss.chitin-drop-chance", 0.35);
        if (Math.random() < chitinChance) {
            event.getDrops().add(chitinFragment());
        }
        double whisperChance = plugin.getConfig().getDouble("sift-boss.whisper-drop-chance", 0.10);
        if (Math.random() < whisperChance) {
            event.getDrops().add(wardensWhisper());
        }
    }

    // ---------------- lair ----------------

    private Location leverLocation(World world) {
        return new Location(world, LAIR_X, LAIR_Y + 1, LAIR_Z);
    }

    /** Digs and decorates the boss chamber the first time, guarded by checking for its own lever. */
    public void buildLairIfNeeded() {
        World world = Bukkit.getWorld(plugin.getConfig().getString("general.rtp-sift-world", "sift"));
        if (world == null) return;
        Location leverLoc = leverLocation(world);
        if (leverLoc.getBlock().getType() == Material.LEVER) return;

        int radius = 6;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = 0; y <= 5; y++) {
                    Block block = world.getBlockAt(LAIR_X + x, LAIR_Y + y, LAIR_Z + z);
                    boolean edge = Math.abs(x) == radius || Math.abs(z) == radius || y == 0 || y == 5;
                    if (!edge) {
                        block.setType(Material.AIR);
                        continue;
                    }
                    if (y == 0 && Math.random() < 0.3) {
                        block.setType(Material.SCULK);
                    } else if (Math.random() < 0.08) {
                        block.setType(Material.SCULK_VEIN);
                    } else {
                        block.setType(Material.DEEPSLATE_TILES);
                    }
                }
            }
        }
        world.getBlockAt(LAIR_X, LAIR_Y + 2, LAIR_Z).setType(Material.SCULK_CATALYST);
        world.getBlockAt(LAIR_X, LAIR_Y, LAIR_Z).setType(Material.SCULK_SHRIEKER);

        Block leverBlock = leverLoc.getBlock();
        leverBlock.setType(Material.LEVER);
        if (leverBlock.getBlockData() instanceof Switch sw) {
            sw.setFace(Switch.Face.WALL);
            leverBlock.setBlockData(sw);
        }
        plugin.getLogger().info("Built the Sift boss lair at " + LAIR_X + "," + LAIR_Y + "," + LAIR_Z + ".");
    }

    // ---------------- lever gate ----------------

    @EventHandler(ignoreCancelled = true)
    public void onLever(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.LEVER) return;
        World world = Bukkit.getWorld(plugin.getConfig().getString("general.rtp-sift-world", "sift"));
        if (world == null || !block.getLocation().equals(leverLocation(world))) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (boss != null) {
            player.sendMessage(Util.text("&cSomething is already stirring in the hollow."));
            return;
        }
        ItemStack ward = null;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isSiftWard(item)) {
                ward = item;
                break;
            }
        }
        if (ward == null) {
            player.sendMessage(Util.text("&cThis lever needs a &d&lSift Ward &cto turn."));
            return;
        }
        ward.setAmount(ward.getAmount() - 1);
        startCutscene(player, world);
    }

    // ---------------- cutscene + boss spawn ----------------

    private void startCutscene(Player player, World world) {
        frozen.add(player.getUniqueId());
        player.setInvulnerable(true);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0, false, false));

        Location center = new Location(world, LAIR_X + 0.5, LAIR_Y + 2, LAIR_Z + 0.5);
        List<Location> pan = List.of(
                center.clone().add(offset(0)),
                center.clone().add(offset(90)),
                center.clone().add(offset(180)),
                center.clone().add(offset(270))
        );

        new BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    frozen.remove(player.getUniqueId());
                    cancel();
                    return;
                }
                if (step >= pan.size()) {
                    finishCutscene(player, world, center);
                    cancel();
                    return;
                }
                Location point = pan.get(step);
                point.setDirection(center.toVector().subtract(point.toVector()));
                player.teleport(point);
                step++;
            }
        }.runTaskTimer(plugin, 0L, 30L);
    }

    private Vector offset(int degrees) {
        double rad = Math.toRadians(degrees);
        return new Vector(Math.cos(rad) * 5, 0, Math.sin(rad) * 5);
    }

    private void finishCutscene(Player player, World world, Location center) {
        frozen.remove(player.getUniqueId());
        player.setInvulnerable(false);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.teleport(center.clone().add(0, 0, 6));

        boss = (Warden) world.spawnEntity(center, EntityType.WARDEN);
        boss.customName(Util.text("&5&lThe Hollow Warden"));
        boss.setCustomNameVisible(true);
        var maxHealth = boss.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) maxHealth.setBaseValue(BOSS_HEALTH);
        boss.setHealth(BOSS_HEALTH);
        boss.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);

        bossBar = Bukkit.createBossBar("The Hollow Warden", BarColor.PURPLE, BarStyle.SEGMENTED_10);
        bossBar.addPlayer(player);
        bossBar.setProgress(1.0);
        player.sendMessage(Util.text("&5&lThe Hollow Warden &7stirs..."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!frozen.contains(event.getPlayer().getUniqueId())) return;
        if (event.getTo() == null) return;
        if (event.getFrom().distanceSquared(event.getTo()) > 0.01) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (frozen.contains(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onBossHealthTick(EntityDamageEvent event) {
        if (boss == null || !event.getEntity().equals(boss) || bossBar == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (boss == null || !boss.isValid() || bossBar == null) return;
            var maxHealth = boss.getAttribute(Attribute.MAX_HEALTH);
            double max = maxHealth != null ? maxHealth.getBaseValue() : BOSS_HEALTH;
            bossBar.setProgress(Math.max(0, Math.min(1, boss.getHealth() / max)));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        frozen.remove(event.getPlayer().getUniqueId());
        if (bossBar != null) bossBar.removePlayer(event.getPlayer());
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        if (boss == null || !event.getEntity().equals(boss)) return;
        event.getDrops().clear();
        event.getDrops().add(Util.item(Material.NETHERITE_INGOT, 1, "&d&lHollow Heart",
                "&7Proof you felled the Sift's boss."));
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            plugin.econ().deposit(killer.getUniqueId(), plugin.getConfig().getDouble("sift-boss.reward-money", 25000));
        }
        Bukkit.broadcast(Util.text("&5&lThe Hollow Warden &7has fallen."));
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        boss = null;
    }
}
