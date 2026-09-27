package net.keeper.smp;

import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class Econ implements Listener {

    /** stock of -1 means unlimited. */
    public record ShopEntry(Material material, double price, int unit, boolean half, int stock) {
        public double unitPrice() {
            return price / Math.max(1, unit);
        }
    }

    private final KeeperPlugin plugin;
    private final File stockFile;
    private final Map<Material, ShopEntry> shop = new LinkedHashMap<>();
    private final Map<Material, Double> sell = new LinkedHashMap<>();
    /** Remaining stock for shop entries with a limited quantity, persisted across restarts. */
    private final Map<Material, Integer> stockRemaining = new LinkedHashMap<>();
    private double defaultSellPrice;
    private double elytraEnchantBonusPerLevel;

    public Econ(KeeperPlugin plugin) {
        this.plugin = plugin;
        this.stockFile = new File(plugin.getDataFolder(), "shop-stock.yml");
        loadStock();
        reload();
    }

    private void loadStock() {
        stockRemaining.clear();
        if (!stockFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(stockFile);
        for (String key : yml.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material != null) stockRemaining.put(material, yml.getInt(key));
        }
    }

    private void saveStock() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<Material, Integer> e : stockRemaining.entrySet()) {
            yml.set(e.getKey().name(), e.getValue());
        }
        try {
            yml.save(stockFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed saving shop stock: " + ex.getMessage());
        }
    }

    public void reload() {
        shop.clear();
        sell.clear();
        defaultSellPrice = plugin.getConfig().getDouble("general.default-sell-price", 0.0);
        elytraEnchantBonusPerLevel = plugin.getConfig()
                .getDouble("general.elytra-enchant-bonus-per-level", 0.0);

        ConfigurationSection shopSection = plugin.getConfig().getConfigurationSection("shop");
        if (shopSection != null) {
            for (String key : shopSection.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                if (material == null) {
                    plugin.getLogger().warning("Unknown shop material: " + key);
                    continue;
                }
                double price = shopSection.getDouble(key + ".price", 0);
                int unit = Math.max(1, shopSection.getInt(key + ".unit", 1));
                boolean half = shopSection.getBoolean(key + ".half", false);
                int stock = shopSection.getInt(key + ".stock", -1);
                shop.put(material, new ShopEntry(material, price, unit, half, stock));
                // First time this item has ever had a stock limit: start it full.
                // A later reload never refills it, only a fresh, never-tracked item does.
                if (stock >= 0 && !stockRemaining.containsKey(material)) {
                    stockRemaining.put(material, stock);
                    saveStock();
                }
            }
        }

        ConfigurationSection sellSection = plugin.getConfig().getConfigurationSection("sell");
        if (sellSection != null) {
            for (String key : sellSection.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                if (material == null) {
                    plugin.getLogger().warning("Unknown sell material: " + key);
                    continue;
                }
                sell.put(material, sellSection.getDouble(key));
            }
        }
        plugin.getLogger().info("Loaded " + shop.size() + " shop entries and " + sell.size() + " sell prices.");
    }

    public Map<Material, ShopEntry> shopEntries() {
        return shop;
    }

    public ShopEntry shopEntry(Material material) {
        return shop.get(material);
    }

    /** -1 for an item with no stock limit. */
    public int stockRemaining(Material material) {
        return stockRemaining.getOrDefault(material, -1);
    }

    /**
     * Base sell price for one unit, before rank bonus and durability. Anything
     * not explicitly priced falls back to the configured default, so /sell
     * accepts everything except what sellValue() excludes outright (crate keys).
     */
    public double basePrice(Material material) {
        Double explicit = sell.get(material);
        return explicit != null ? explicit : defaultSellPrice;
    }

    public boolean sellable(Material material) {
        return basePrice(material) > 0;
    }

    // ---------------- balances ----------------

    public double balance(UUID uuid) {
        return plugin.data().get(uuid).balance;
    }

    public void deposit(UUID uuid, double amount) {
        Data.PlayerData d = plugin.data().get(uuid);
        d.balance = Math.max(0, d.balance + amount);
    }

    public boolean withdraw(UUID uuid, double amount) {
        Data.PlayerData d = plugin.data().get(uuid);
        if (d.balance < amount) return false;
        d.balance -= amount;
        return true;
    }

    public void set(UUID uuid, double amount) {
        plugin.data().get(uuid).balance = Math.max(0, amount);
    }

    public String symbol() {
        return plugin.getConfig().getString("general.currency-symbol", "$");
    }

    public String fmt(double amount) {
        return symbol() + Util.money(amount);
    }

    // ---------------- buying ----------------

    /** Price a player pays for one bundle, after their rank discount. */
    public double buyCost(Player player, ShopEntry entry, int count) {
        double raw = entry.unitPrice() * count;
        double discount = plugin.ranks().of(player).shopDiscount;
        return raw * (1.0 - discount);
    }

    public boolean buy(Player player, Material material, int count) {
        ShopEntry entry = shop.get(material);
        if (entry == null) {
            player.sendMessage(Util.text("&cThat item is not for sale."));
            return false;
        }
        if (entry.stock() >= 0) {
            int stockLeft = stockRemaining.getOrDefault(material, 0);
            if (stockLeft <= 0) {
                player.sendMessage(Util.text("&cThat item is sold out."));
                return false;
            }
            if (count > stockLeft) {
                player.sendMessage(Util.text("&cOnly &f" + stockLeft + " &cleft in stock."));
                return false;
            }
        }
        double cost = buyCost(player, entry, count);
        if (!withdraw(player.getUniqueId(), cost)) {
            player.sendMessage(Util.text("&cYou need &f" + fmt(cost) + "&c and you have &f"
                    + fmt(balance(player.getUniqueId())) + "&c."));
            return false;
        }
        int remaining = count;
        while (remaining > 0) {
            int give = Math.min(remaining, material.getMaxStackSize());
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, give));
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            remaining -= give;
        }
        if (entry.stock() >= 0) {
            stockRemaining.merge(material, -count, Integer::sum);
            saveStock();
        }
        player.sendMessage(Util.text("&aBought &f" + count + "x " + Util.nice(material)
                + " &afor &f" + fmt(cost) + "&a."));
        if (entry.stock() >= 0) {
            player.sendMessage(Util.text("&8" + stockRemaining.getOrDefault(material, 0)
                    + " left in stock."));
        }
        return true;
    }

    // ---------------- selling ----------------

    /** What a player is paid for a specific stack, durability and rank included. */
    public double sellValue(Player player, ItemStack stack) {
        return sellValue(player.getUniqueId(), player.getName(), stack);
    }

    /** Same as above, but works for an offline seller (e.g. an auction listing). */
    public double sellValue(UUID sellerId, String sellerName, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return 0;
        if (plugin.crates().keyTier(stack) != null) return 0;
        double base = basePrice(stack.getType());
        if (base <= 0) return 0;
        double bonus = plugin.ranks().of(sellerId, sellerName).sellBonus;
        double value = base * stack.getAmount() * (1.0 + bonus);
        if (stack.getType() == Material.ELYTRA && elytraEnchantBonusPerLevel > 0) {
            int levels = stack.getEnchantments().values().stream().mapToInt(Integer::intValue).sum();
            value *= (1.0 + levels * elytraEnchantBonusPerLevel);
        }
        if (stack.getType().getMaxDurability() > 0) {
            value *= Util.condition(stack);
        }
        return value;
    }

    /** True if this is a shulker box still holding items, which selling would destroy. */
    private boolean hasShulkerContents(ItemStack stack) {
        return stack.getItemMeta() instanceof BlockStateMeta meta
                && meta.getBlockState() instanceof ShulkerBox box
                && !box.getInventory().isEmpty();
    }

    /** Sells the item in the main hand. */
    public void sellHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            player.sendMessage(Util.text("&cHold the item you want to sell in your main hand."));
            return;
        }
        if (hasShulkerContents(hand)) {
            player.sendMessage(Util.text("&cEmpty that shulker box before selling it."));
            return;
        }
        double value = sellValue(player, hand);
        if (value <= 0) {
            player.sendMessage(Util.text("&c" + Util.nice(hand.getType()) + " cannot be sold here."));
            return;
        }
        int amount = hand.getAmount();
        Material type = hand.getType();
        player.getInventory().setItemInMainHand(null);
        deposit(player.getUniqueId(), value);
        player.sendMessage(Util.text("&aSold &f" + amount + "x " + Util.nice(type)
                + " &afor &f" + fmt(value) + "&a."));
        player.sendMessage(Util.text("&8 " + fmt(value / Math.max(1, amount)) + " each"));
        compare(player, type, amount);
    }

    /** Prices the held item without selling it. Used by /value. */
    public void quote(Player player, org.bukkit.inventory.ItemStack stack) {
        Material type = stack.getType();
        int amount = stack.getAmount();
        player.sendMessage(Util.text("&8&m----------------------------"));
        player.sendMessage(Util.text(" &f" + amount + "x " + Util.nice(type)));

        ShopEntry entry = shopEntry(type);
        if (entry != null) {
            double discount = plugin.ranks().of(player).shopDiscount;
            player.sendMessage(Util.text(" &7Shop buy: &f"
                    + fmt(entry.unitPrice() * (1 - discount)) + " &7each"));
        }

        double value = sellValue(player, stack);
        if (value <= 0) {
            player.sendMessage(Util.text(" &7Sell: &cnot sellable here"));
        } else {
            player.sendMessage(Util.text(" &7Sell: &a" + fmt(value) + " &7total &8("
                    + fmt(value / Math.max(1, amount)) + " each)"));
            if (type.getMaxDurability() > 0) {
                player.sendMessage(Util.text(" &8Scaled to "
                        + Math.round(Util.condition(stack) * 100) + "% durability"));
            }
        }
        compare(player, type, amount);
        player.sendMessage(Util.text("&8&m----------------------------"));
    }

    /**
     * Points out when the auction house is paying better than the server shop,
     * so nobody dumps something valuable for the flat rate by mistake.
     */
    private void compare(Player player, Material type, int amount) {
        Auction.Listing cheapest = plugin.auction().cheapest(type);
        if (cheapest == null) {
            player.sendMessage(Util.text("&8 Nothing like it listed on /ah right now."));
            return;
        }
        int listedAmount = Math.max(1, cheapest.item.getAmount());
        double perItem = cheapest.price / listedAmount;
        player.sendMessage(Util.text("&8 Cheapest on /ah: &7" + fmt(cheapest.price)
                + " &8for &7" + listedAmount + " &8(" + fmt(perItem) + " each)"));
        double wouldFetch = perItem * amount;
        if (wouldFetch > basePrice(type) * amount) {
            player.sendMessage(Util.text("&e At that rate " + amount + " would fetch "
                    + fmt(wouldFetch) + " on /ah."));
        }
    }

    /** Sells every sellable item in the inventory, skipping armour and offhand. */
    public void sellAll(Player player) {
        double total = 0;
        int items = 0;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()) continue;
            if (hasShulkerContents(stack)) continue;
            double value = sellValue(player, stack);
            if (value <= 0) continue;
            total += value;
            items += stack.getAmount();
            contents[i] = null;
        }
        if (total <= 0) {
            player.sendMessage(Util.text("&cNothing in your inventory can be sold."));
            return;
        }
        player.getInventory().setStorageContents(contents);
        deposit(player.getUniqueId(), total);
        player.sendMessage(Util.text("&aSold &f" + items + " &aitems for &f" + fmt(total) + "&a."));
        player.sendMessage(Util.text("&8 " + fmt(total / Math.max(1, items)) + " per item on average"));
    }

    // ---------------- auto sell ----------------

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        if (!data.autoSell) return;
        if (!plugin.ranks().of(player).autosell) {
            data.autoSell = false;
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (hasShulkerContents(stack)) return;
        double value = sellValue(player, stack);
        if (value <= 0) return;
        event.setCancelled(true);
        event.getItem().remove();
        deposit(player.getUniqueId(), value);
        player.sendActionBar(Util.text("&a+" + fmt(value) + " &7(" + stack.getAmount() + "x "
                + Util.nice(stack.getType()) + ")"));
    }
}
