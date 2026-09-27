package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Shards are a second currency earned only by idling with /afk. They buy
 * spawners, netherite and crate keys, none of which the money shop sells.
 */
public class Shards implements Listener {

    public record ShopItem(String id, Material icon, String name, long price, String kind,
                           String payload, List<String> lore) {
    }

    private final KeeperPlugin plugin;
    private final Map<UUID, Long> afkSince = new HashMap<>();
    private final Map<UUID, Long> lastPaid = new HashMap<>();
    private final Map<String, ShopItem> items = new LinkedHashMap<>();

    public Shards(KeeperPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        items.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("shardshop");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Material icon = Material.matchMaterial(s.getString("icon", "PAPER"));
            if (icon == null) icon = Material.PAPER;
            items.put(id.toUpperCase(Locale.ROOT), new ShopItem(
                    id.toUpperCase(Locale.ROOT), icon,
                    s.getString("name", id),
                    s.getLong("price", 1000),
                    s.getString("kind", "item").toLowerCase(Locale.ROOT),
                    s.getString("payload", ""),
                    s.getStringList("lore")));
        }
        plugin.getLogger().info("Loaded " + items.size() + " shard shop entries.");
    }

    // ---------------- shard balance ----------------

    public long balance(UUID uuid) {
        return plugin.data().get(uuid).shards;
    }

    public void give(UUID uuid, long amount) {
        Data.PlayerData data = plugin.data().get(uuid);
        data.shards = Math.max(0, data.shards + amount);
    }

    public boolean take(UUID uuid, long amount) {
        Data.PlayerData data = plugin.data().get(uuid);
        if (data.shards < amount) return false;
        data.shards -= amount;
        return true;
    }

    // ---------------- afk ----------------

    public boolean isAfk(Player player) {
        return afkSince.containsKey(player.getUniqueId());
    }

    public void toggle(Player player) {
        if (isAfk(player)) {
            clear(player, true);
            return;
        }
        afkSince.put(player.getUniqueId(), System.currentTimeMillis());
        lastPaid.put(player.getUniqueId(), System.currentTimeMillis());
        plugin.data().get(player.getUniqueId()).afk = true;
        long rate = plugin.getConfig().getLong("afk.shards-per-minute", 1);
        player.sendMessage(Util.text("&7You are now &fAFK&7. Earning &b" + rate
                + " shard" + (rate == 1 ? "" : "s") + " &7per minute."));
        player.sendMessage(Util.text("&8Moving, chatting or any command other than /afk ends it."));
        Bukkit.broadcast(Util.text("&8* &7" + player.getName() + " is now AFK"));
    }

    public void clear(Player player, boolean announce) {
        if (afkSince.remove(player.getUniqueId()) == null) return;
        lastPaid.remove(player.getUniqueId());
        plugin.data().get(player.getUniqueId()).afk = false;
        if (announce) {
            player.sendMessage(Util.text("&7You are no longer AFK. Shards: &b"
                    + balance(player.getUniqueId())));
            Bukkit.broadcast(Util.text("&8* &7" + player.getName() + " is back"));
        }
    }

    /** Runs once a second. Pays out whole minutes of idling. */
    public void tick() {
        long rate = plugin.getConfig().getLong("afk.shards-per-minute", 1);
        if (Util.isWeekend() && plugin.getConfig().getBoolean("general.weekend-bonus-enabled", true)) {
            rate = Math.round(rate * plugin.getConfig().getDouble("general.weekend-bonus-multiplier", 2.0));
        }
        long now = System.currentTimeMillis();
        for (UUID uuid : new ArrayList<>(afkSince.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                afkSince.remove(uuid);
                lastPaid.remove(uuid);
                continue;
            }
            long since = now - lastPaid.getOrDefault(uuid, now);
            if (since < 60_000L) continue;
            long minutes = since / 60_000L;
            lastPaid.put(uuid, now);
            give(uuid, rate * minutes);
            player.sendActionBar(Util.text("&b+" + (rate * minutes) + " shards &8| total "
                    + balance(uuid)));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        clear(event.getPlayer(), true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (isAfk(player)) {
            Bukkit.getScheduler().runTask(plugin, () -> clear(player, true));
        }
    }

    // ---------------- shop ----------------

    public Map<String, ShopItem> entries() {
        return items;
    }

    public void openShop(Player player, int page) {
        List<ShopItem> list = new ArrayList<>(items.values());
        int perPage = 45;
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) perPage));
        page = Math.max(0, Math.min(page, pages - 1));

        Gui.Holder holder = new Gui.Holder("shardshop");
        Inventory inv = Bukkit.createInventory(holder, 54,
                Util.text("&8Shard Shop &7(" + (page + 1) + "/" + pages + ")"));
        holder.inventory = inv;

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= list.size()) break;
            ShopItem item = list.get(index);
            List<String> lore = new ArrayList<>(item.lore());
            lore.add("");
            lore.add("&7Cost: &b" + Util.money(item.price()) + " shards");
            lore.add(balance(player.getUniqueId()) >= item.price()
                    ? "&eClick to buy" : "&cYou cannot afford this yet");
            inv.setItem(i, Util.tag(Util.item(item.icon(), 1, item.name(),
                    lore.toArray(new String[0])), "shardbuy", item.id()));
        }

        inv.setItem(45, Util.tag(Util.item(Material.ARROW, 1, "&fPrevious page"),
                "shardpage", String.valueOf(page - 1)));
        inv.setItem(49, Util.tag(Util.item(Material.NETHER_STAR, 1, "&eBack to menu"), "menu", null));
        inv.setItem(46, Util.item(Material.AMETHYST_SHARD, 1, "&bYour shards",
                "&f" + Util.money(balance(player.getUniqueId())),
                "", "&7Earn more with &f/afk"));
        inv.setItem(53, Util.tag(Util.item(Material.ARROW, 1, "&fNext page"),
                "shardpage", String.valueOf(page + 1)));
        player.openInventory(inv);
    }

    public void buy(Player player, String id) {
        ShopItem item = items.get(id == null ? "" : id.toUpperCase(Locale.ROOT));
        if (item == null) {
            player.sendMessage(Util.text("&cThat is not in the shard shop."));
            return;
        }
        if (balance(player.getUniqueId()) < item.price()) {
            player.sendMessage(Util.text("&cYou need &b" + Util.money(item.price())
                    + " shards &cand have &b" + Util.money(balance(player.getUniqueId())) + "&c."));
            return;
        }
        if (item.kind().equals("pet") || item.kind().equals("trail")) {
            Data.PlayerData data = plugin.data().get(player.getUniqueId());
            java.util.Set<String> owned = item.kind().equals("pet") ? data.ownedPets : data.ownedTrails;
            if (owned.contains(item.payload())) {
                player.sendMessage(Util.text("&cYou already own that."));
                return;
            }
            take(player.getUniqueId(), item.price());
            owned.add(item.payload());
            plugin.data().save(player.getUniqueId());
            player.sendMessage(Util.text("&aUnlocked &f" + item.name()
                    + "&a! Equip it with &f/cosmetics&a."));
            openShop(player, 0);
            return;
        }
        ItemStack reward = build(item);
        if (reward == null) {
            player.sendMessage(Util.text("&cThat shop entry is misconfigured. Tell an admin."));
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(Util.text("&cMake room in your inventory first."));
            return;
        }
        take(player.getUniqueId(), item.price());
        player.getInventory().addItem(reward);
        plugin.data().save(player.getUniqueId());
        player.sendMessage(Util.text("&aBought " + item.name() + " &afor &b"
                + Util.money(item.price()) + " shards&a."));
        openShop(player, 0);
    }

    /** Turns a shop entry into the actual item stack. */
    private ItemStack build(ShopItem item) {
        switch (item.kind()) {
            case "spawner" -> {
                EntityType type;
                try {
                    type = EntityType.valueOf(item.payload().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    return null;
                }
                ItemStack stack = new ItemStack(Material.SPAWNER);
                if (stack.getItemMeta() instanceof BlockStateMeta meta
                        && meta.getBlockState() instanceof CreatureSpawner spawner) {
                    spawner.setSpawnedType(type);
                    meta.setBlockState(spawner);
                    meta.displayName(Util.text(item.name()));
                    stack.setItemMeta(meta);
                }
                return stack;
            }
            case "tntspawner" -> {
                return plugin.spawners().createItem(item.name());
            }
            case "key" -> {
                return plugin.crates().createKey(item.payload(), 1);
            }
            case "enchantbook" -> {
                return Util.enchantBook(item.payload(), item.name());
            }
            case "excavator" -> {
                return plugin.specialTools().createExcavator();
            }
            case "treefeller" -> {
                return plugin.specialTools().createTreefeller();
            }
            default -> {
                Material material = Material.matchMaterial(item.payload());
                if (material == null) return null;
                return new ItemStack(material, 1);
            }
        }
    }
}
