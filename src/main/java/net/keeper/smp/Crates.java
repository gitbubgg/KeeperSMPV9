package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Crate keys and crate blocks. A key carries its tier, a crate block is just a
 * marked chest, and the loot that comes out is decided by the key, so a better
 * key means better loot from the same crate.
 */
public class Crates implements Listener {

    public record LootEntry(Material material, int min, int max, int weight,
                            double money, long shards, String enchant, int level) {
    }

    public record Tier(String id, String name, Material keyMaterial, int rolls,
                       List<LootEntry> loot, Map<String, Integer> dailyLimits) {
        public int totalWeight() {
            int total = 0;
            for (LootEntry e : loot) total += e.weight();
            return total;
        }
    }

    private final KeeperPlugin plugin;
    private final Map<String, Tier> tiers = new LinkedHashMap<>();
    /** location -> required tier id, or ANY for a crate that takes every key. */
    private final Map<String, String> crateBlocks = new HashMap<>();
    public static final String ANY = "ANY";
    private final Random random = new Random();
    /** Guards against Geyser sending the same tap twice. */
    private final Map<java.util.UUID, Long> lastOpen = new HashMap<>();
    private final File file;
    private NamespacedKey keyTag;

    public Crates(KeeperPlugin plugin) {
        this.plugin = plugin;
        this.keyTag = new NamespacedKey(plugin, "crate_key");
        this.file = new File(plugin.getDataFolder(), "crates.yml");
        reload();
        loadBlocks();
    }

    public void reload() {
        tiers.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("crates");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Material keyMaterial = Material.matchMaterial(s.getString("key-item", "TRIPWIRE_HOOK"));
            if (keyMaterial == null) keyMaterial = Material.TRIPWIRE_HOOK;

            List<LootEntry> loot = new ArrayList<>();
            for (Map<?, ?> raw : s.getMapList("loot")) {
                String itemName = str(raw.get("item"));
                Material material = itemName == null ? null : Material.matchMaterial(itemName);
                int min = intOf(raw.get("min"), 1);
                int max = intOf(raw.get("max"), Math.max(1, min));
                int weight = intOf(raw.get("weight"), 10);
                double money = dblOf(raw.get("money"), 0);
                long shards = intOf(raw.get("shards"), 0);
                String enchant = str(raw.get("enchant"));
                int level = intOf(raw.get("level"), 1);
                if (material == null && money <= 0 && shards <= 0) continue;
                loot.add(new LootEntry(material, min, max, weight, money, shards, enchant, level));
            }
            Map<String, Integer> limits = new HashMap<>();
            ConfigurationSection limitSection = s.getConfigurationSection("daily-limits");
            if (limitSection != null) {
                for (String group : limitSection.getKeys(false)) {
                    limits.put(group.toLowerCase(Locale.ROOT), limitSection.getInt(group));
                }
            }
            tiers.put(id.toUpperCase(Locale.ROOT), new Tier(id.toUpperCase(Locale.ROOT),
                    s.getString("name", id), keyMaterial, Math.max(1, s.getInt("rolls", 3)),
                    loot, limits));
        }
        plugin.getLogger().info("Loaded " + tiers.size() + " crate tiers.");
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private int intOf(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private double dblOf(Object o, double fallback) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception ex) {
            return fallback;
        }
    }

    public Map<String, Tier> tiers() {
        return tiers;
    }

    // ---------------- keys ----------------

    public ItemStack createKey(String tierId, int amount) {
        Tier tier = tiers.get(tierId == null ? "" : tierId.toUpperCase(Locale.ROOT));
        if (tier == null) return null;
        ItemStack stack = new ItemStack(tier.keyMaterial(), Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Util.text(tier.name()));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(Util.text("&7Right click a crate to open it."));
            lore.add(Util.text("&7Rewards: &f" + tier.rolls() + " rolls"));
            lore.add(Util.text("&8Crate key"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(keyTag, PersistentDataType.STRING, tier.id());
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public String keyTier(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer()
                .get(keyTag, PersistentDataType.STRING);
    }

    // ---------------- crate blocks ----------------

    private String serialize(Block block) {
        return block.getWorld().getName() + ";" + block.getX() + ";" + block.getY()
                + ";" + block.getZ();
    }

    public boolean isCrate(Block block) {
        return crateBlocks.containsKey(serialize(block));
    }

    /** ANY, or the tier id this crate demands. */
    public String crateTier(Block block) {
        return crateBlocks.getOrDefault(serialize(block), ANY);
    }

    public void addCrate(Player player, Block block, String tierId) {
        if (block == null) {
            player.sendMessage(Util.text("&cLook at the block you want to turn into a crate."));
            return;
        }
        String tier = ANY;
        if (tierId != null && !tierId.isBlank() && !tierId.equalsIgnoreCase(ANY)) {
            String upper = tierId.toUpperCase(Locale.ROOT);
            if (!tiers.containsKey(upper)) {
                player.sendMessage(Util.text("&cNo crate tier called &f" + tierId + "&c."));
                player.sendMessage(Util.text("&7Tiers: &f" + String.join(", ", tiers.keySet())
                        + ", ANY"));
                return;
            }
            tier = upper;
        }
        crateBlocks.put(serialize(block), tier);
        saveBlocks();
        player.sendMessage(Util.text("&aThat " + Util.nice(block.getType()) + " is now a "
                + (tier.equals(ANY) ? "crate that takes &fany key"
                : "&f" + tier + " &acrate") + "&a."));
    }

    /** Used by the spawn builder, which has no player message to send. */
    public void registerCrate(Block block, String tierId) {
        crateBlocks.put(serialize(block), tierId == null ? ANY : tierId.toUpperCase(Locale.ROOT));
        saveBlocks();
    }

    public void removeCrate(Player player, Block block) {
        if (block != null && crateBlocks.remove(serialize(block)) != null) {
            saveBlocks();
            player.sendMessage(Util.text("&7That block is no longer a crate."));
        } else {
            player.sendMessage(Util.text("&cThat block is not a crate."));
        }
    }

    public int crateCount() {
        return crateBlocks.size();
    }

    // ---------------- opening ----------------

    /**
     * Runs at LOWEST so nothing else, spawn protection included, sees this as
     * a block placement first. Both results are denied explicitly, because
     * cancelling alone has been known to still consume the item on some
     * clients.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        // Bedrock through Geyser does not always arrive as a clean right click,
        // so a left click on a crate while holding a key counts as well.
        boolean clickedBlock = action == Action.RIGHT_CLICK_BLOCK
                || action == Action.LEFT_CLICK_BLOCK;
        if (!clickedBlock) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        Player player = event.getPlayer();

        if (plugin.getConfig().getBoolean("crates.debug", false)) {
            plugin.getLogger().info("[crate-debug] " + player.getName() + " " + action
                    + " hand=" + event.getHand() + " block=" + block.getType()
                    + " isCrate=" + isCrate(block)
                    + " key=" + keyTier(player.getInventory().getItemInMainHand()));
        }

        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND
                && keyTier(player.getInventory().getItemInMainHand()) == null) {
            return;
        }

        if (!isCrate(block)) {
            // Holding a key and clicking something that is not a crate: say so
            // rather than letting the click fall through to other handlers.
            if (keyTier(player.getInventory().getItemInMainHand()) != null) {
                event.setUseItemInHand(Event.Result.DENY);
                player.sendActionBar(Util.text("&7That is not a crate."));
            }
            return;
        }

        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        ItemStack hand = player.getInventory().getItemInMainHand();
        String tierId = keyTier(hand);
        if (tierId == null) {
            player.sendMessage(Util.text("&cYou need a key in your main hand."));
            player.sendMessage(Util.text("&7Buy one with &f/shardshop&7."));
            return;
        }
        Tier tier = tiers.get(tierId);
        if (tier == null) {
            player.sendMessage(Util.text("&cThat key's tier no longer exists."));
            return;
        }
        String required = crateTier(block);
        if (!required.equals(ANY) && !required.equals(tierId)) {
            Tier want = tiers.get(required);
            player.sendMessage(Util.text("&cThis crate needs a "
                    + (want == null ? required : want.name()) + "&c."));
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(Util.text("&cMake room in your inventory first."));
            return;
        }

        // One tap can arrive twice from Bedrock; ignore the echo.
        long now = System.currentTimeMillis();
        Long previous = lastOpen.get(player.getUniqueId());
        if (previous != null && now - previous < 400) return;
        lastOpen.put(player.getUniqueId(), now);

        hand.setAmount(hand.getAmount() - 1);
        open(player, tier, block.getLocation());
    }

    /**
     * Opens the nearest crate within range using the held key. This is the
     * path /usekey takes, so a player can always open a crate even if their
     * client's block interaction does not reach the server as expected.
     */
    public void useNearest(Player player, int range) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        String tierId = keyTier(hand);
        if (tierId == null) {
            player.sendMessage(Util.text("&cHold a key in your main hand first."));
            player.sendMessage(Util.text("&7Buy one with &f/shardshop&7."));
            return;
        }
        Tier tier = tiers.get(tierId);
        if (tier == null) {
            player.sendMessage(Util.text("&cThat key's tier no longer exists."));
            return;
        }

        Block best = null;
        double bestDistance = Double.MAX_VALUE;
        Location eye = player.getEyeLocation();
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    Block candidate = eye.getBlock().getRelative(dx, dy, dz);
                    if (!isCrate(candidate)) continue;
                    double distance = candidate.getLocation().add(0.5, 0.5, 0.5)
                            .distanceSquared(eye);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        if (best == null) {
            player.sendMessage(Util.text("&cNo crate within " + range + " blocks."));
            player.sendMessage(Util.text("&7Stand next to one at spawn and try again."));
            return;
        }
        String required = crateTier(best);
        if (!required.equals(ANY) && !required.equals(tierId)) {
            Tier want = tiers.get(required);
            player.sendMessage(Util.text("&cThe nearest crate needs a "
                    + (want == null ? required : want.name()) + "&c."));
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(Util.text("&cMake room in your inventory first."));
            return;
        }
        hand.setAmount(hand.getAmount() - 1);
        open(player, tier, best.getLocation());
    }

    /** Wipes every registered crate. For clearing up after rebuilding spawn. */
    public int clearAll() {
        int count = crateBlocks.size();
        crateBlocks.clear();
        saveBlocks();
        return count;
    }

    /** Keys are tools, not blocks. Placing one would lose it for nothing. */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        if (keyTier(hand) == null) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(Util.text("&cCrate keys cannot be placed."));
        event.getPlayer().sendMessage(Util.text("&7Right click a crate at spawn to use it."));
    }

    private void open(Player player, Tier tier, Location location) {
        if (tier.loot().isEmpty() || tier.totalWeight() <= 0) {
            player.sendMessage(Util.text("&cThat crate has no loot configured."));
            return;
        }
        player.sendMessage(Util.text("&8&m----------------------------"));
        player.sendMessage(Util.text(" &fOpening " + tier.name()));

        // Only gear is deduplicated, and only when the enchantment matches too.
        // That way a full armour set is possible, two identical chestplates are
        // not, and stackables like totems and ingots can still repeat.
        Set<String> gearGiven = new HashSet<>();
        Set<Material> limitHit = new HashSet<>();
        for (int i = 0; i < tier.rolls(); i++) {
            LootEntry entry = roll(tier);
            for (int retry = 0; retry < 16 && entry != null
                    && (gearGiven.contains(gearKey(entry))
                        || atDailyLimit(player, tier, entry.material())); retry++) {
                if (entry.material() != null && atDailyLimit(player, tier, entry.material())) {
                    limitHit.add(entry.material());
                }
                entry = roll(tier);
            }
            if (entry == null) continue;
            if (entry.material() != null && atDailyLimit(player, tier, entry.material())) {
                limitHit.add(entry.material());
                continue;
            }
            String gearKey = gearKey(entry);
            if (gearKey != null) {
                if (gearGiven.contains(gearKey)) continue;
                gearGiven.add(gearKey);
            }
            countTowardLimit(player, tier, entry.material());
            if (entry.money() > 0) {
                plugin.econ().deposit(player.getUniqueId(), entry.money());
                player.sendMessage(Util.text(" &a+ " + plugin.econ().fmt(entry.money())));
            }
            if (entry.shards() > 0) {
                plugin.shards().give(player.getUniqueId(), entry.shards());
                player.sendMessage(Util.text(" &b+ " + entry.shards() + " shards"));
            }
            if (entry.material() != null) {
                int amount = entry.min() >= entry.max() ? entry.min()
                        : entry.min() + random.nextInt(entry.max() - entry.min() + 1);
                ItemStack stack = new ItemStack(entry.material(), Math.max(1, amount));
                if (entry.enchant() != null && !entry.enchant().isBlank()) {
                    Enchantment enchantment = Enchantment.getByKey(
                            NamespacedKey.minecraft(entry.enchant().toLowerCase(Locale.ROOT)));
                    if (enchantment != null) {
                        stack.addUnsafeEnchantment(enchantment, Math.max(1, entry.level()));
                    }
                }
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
                player.sendMessage(Util.text(" &f+ " + amount + "x " + Util.nice(entry.material())
                        + (entry.enchant() == null || entry.enchant().isBlank() ? ""
                        : " &d(" + entry.enchant() + " " + entry.level() + ")")));
            }
        }
        for (Material blockedMaterial : limitHit) {
            long hours = hoursLeft(player, tier, blockedMaterial);
            player.sendMessage(Util.text(" &8Daily cap reached on " + Util.nice(blockedMaterial)
                    + " from this crate" + (hours > 0 ? ", resets in " + hours + "h" : "")));
        }
        player.sendMessage(Util.text("&8&m----------------------------"));
        plugin.data().save(player.getUniqueId());

        player.playSound(location, Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1.2f);
        player.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        location.getWorld().spawnParticle(org.bukkit.Particle.END_ROD,
                location.clone().add(0.5, 1.2, 0.5), 40, 0.4, 0.4, 0.4, 0.05);

        if (tier.id().equals("LEGENDARY")) {
            Bukkit.broadcast(Util.text("&6[Crates] &f" + player.getName()
                    + " &7opened a " + tier.name() + "&7!"));
        }
    }

    /**
     * A dedupe key for gear, or null for anything that may repeat. Durability
     * is the test: armour, tools and weapons have it, totems and ingots do not.
     * The enchantment is part of the key, so the same sword with a different
     * enchantment still counts as a separate reward.
     */
    private String gearKey(LootEntry entry) {
        if (entry == null || entry.material() == null) return null;
        if (entry.material().getMaxDurability() <= 0) return null;
        String enchant = entry.enchant() == null ? "" : entry.enchant().toLowerCase(Locale.ROOT);
        return entry.material().name() + "|" + enchant + "|" + entry.level();
    }

    /**
     * Daily limits are keyed on a word that appears in the material name, so a
     * limit named "sword" covers every sword and "chestplate" every chestplate.
     * The window is a rolling 24 hours from the first one awarded.
     */
    private List<String> groupsFor(Tier tier, Material material) {
        List<String> groups = new ArrayList<>();
        if (material == null) return groups;
        String name = material.name().toLowerCase(Locale.ROOT);
        for (String group : tier.dailyLimits().keySet()) {
            if (name.contains(group)) groups.add(group);
        }
        return groups;
    }

    private boolean atDailyLimit(Player player, Tier tier, Material material) {
        List<String> groups = groupsFor(tier, material);
        if (groups.isEmpty()) return false;
        Map<String, String> counters = plugin.data().get(player.getUniqueId()).crateLimits;
        long now = System.currentTimeMillis();
        for (String group : groups) {
            int limit = tier.dailyLimits().getOrDefault(group, Integer.MAX_VALUE);
            String raw = counters.get(tier.id() + ":" + group);
            if (raw == null) continue;
            String[] parts = raw.split(";");
            if (parts.length < 2) continue;
            try {
                int count = Integer.parseInt(parts[0]);
                long resetAt = Long.parseLong(parts[1]);
                if (now > resetAt) continue;
                if (count >= limit) return true;
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    private void countTowardLimit(Player player, Tier tier, Material material) {
        List<String> groups = groupsFor(tier, material);
        if (groups.isEmpty()) return;
        Map<String, String> counters = plugin.data().get(player.getUniqueId()).crateLimits;
        long now = System.currentTimeMillis();
        for (String group : groups) {
            String key = tier.id() + ":" + group;
            String raw = counters.get(key);
            int count = 0;
            long resetAt = now + 86_400_000L;
            if (raw != null) {
                String[] parts = raw.split(";");
                try {
                    long existingReset = Long.parseLong(parts[1]);
                    if (now <= existingReset) {
                        count = Integer.parseInt(parts[0]);
                        resetAt = existingReset;
                    }
                } catch (Exception ignored) {
                }
            }
            counters.put(key, (count + 1) + ";" + resetAt);
        }
    }

    /** Hours until a group's window rolls over, for the player message. */
    private long hoursLeft(Player player, Tier tier, Material material) {
        List<String> groups = groupsFor(tier, material);
        long soonest = Long.MAX_VALUE;
        Map<String, String> counters = plugin.data().get(player.getUniqueId()).crateLimits;
        for (String group : groups) {
            String raw = counters.get(tier.id() + ":" + group);
            if (raw == null) continue;
            String[] parts = raw.split(";");
            if (parts.length < 2) continue;
            try {
                soonest = Math.min(soonest, Long.parseLong(parts[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        if (soonest == Long.MAX_VALUE) return 0;
        return Math.max(1, (soonest - System.currentTimeMillis()) / 3_600_000L);
    }

    private LootEntry roll(Tier tier) {
        int pick = random.nextInt(tier.totalWeight());
        int running = 0;
        for (LootEntry entry : tier.loot()) {
            running += entry.weight();
            if (pick < running) return entry;
        }
        return tier.loot().get(0);
    }

    // ---------------- persistence ----------------

    private void loadBlocks() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        // Older files stored a plain list with no tier attached.
        for (String raw : yml.getStringList("blocks")) {
            crateBlocks.put(raw, ANY);
        }
        if (yml.isConfigurationSection("crates")) {
            for (String raw : yml.getConfigurationSection("crates").getKeys(false)) {
                crateBlocks.put(raw.replace('_', ';'), yml.getString("crates." + raw, ANY));
            }
        }
        plugin.getLogger().info("Loaded " + crateBlocks.size() + " crate block(s).");
    }

    public void saveBlocks() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, String> e : crateBlocks.entrySet()) {
            yml.set("crates." + e.getKey().replace(';', '_'), e.getValue());
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed saving crates: " + ex.getMessage());
        }
    }
}
