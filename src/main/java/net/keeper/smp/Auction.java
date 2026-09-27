package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Auction {

    public static class Listing {
        public String id;
        public UUID seller;
        public String sellerName;
        public ItemStack item;
        public double price;
        public long expiry;
    }

    public record Pending(ItemStack item, double price) {
    }

    private final KeeperPlugin plugin;
    private final Map<String, Listing> listings = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, List<ItemStack>> mailbox = new ConcurrentHashMap<>();
    private final File file;

    public Auction(KeeperPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auctions.yml");
        load();
    }

    // ---------------- listing ----------------

    public int countFor(UUID uuid) {
        int n = 0;
        for (Listing listing : listings.values()) {
            if (listing.seller.equals(uuid)) n++;
        }
        return n;
    }

    /**
     * Start a listing from the item in the main hand. The offhand is deliberately
     * ignored, so a shield or totem in the offhand can never be listed by mistake.
     */
    public void start(Player player, double price) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            player.sendMessage(Util.text("&cHold the item in your main hand, not your offhand."));
            return;
        }
        if (!player.getInventory().getItemInOffHand().getType().isAir()
                && hand.equals(player.getInventory().getItemInOffHand())) {
            player.sendMessage(Util.text("&7Listing the item from your main hand."));
        }
        if (price <= 0) {
            player.sendMessage(Util.text("&cSet a price above 0. Usage: /ah sell <price>"));
            return;
        }
        int limit = plugin.ranks().listingLimit(player);
        if (countFor(player.getUniqueId()) >= limit) {
            player.sendMessage(Util.text("&cYou already have &f" + limit + " &clistings up."));
            player.sendMessage(Util.text("&7Higher ranks get more slots, see &f/rank&7."));
            return;
        }

        pending.put(player.getUniqueId(), new Pending(hand.clone(), price));
        double sellFloor = plugin.econ().sellValue(player, hand);
        double tax = plugin.ranks().of(player).auctionTax;
        double net = Math.max(price, sellFloor) * (1.0 - tax);
        player.sendMessage(Util.text("&8&m--------------------------------"));
        player.sendMessage(Util.text(" &fList " + hand.getAmount() + "x "
                + Util.nice(hand.getType()) + "&7?"));
        player.sendMessage(Util.text(" &7Price: &f" + plugin.econ().fmt(price)));
        if (sellFloor > price) {
            player.sendMessage(Util.text(" &8That is below its &f/sell &8value of &7"
                    + plugin.econ().fmt(sellFloor) + "&8, so buyers will pay that instead."));
        }
        player.sendMessage(Util.text(" &7Fee: &f" + Math.round(tax * 100) + "%"
                + " &7so you receive &a" + plugin.econ().fmt(net)));
        player.sendMessage(Util.text(" &aType /ah confirm &7or &c/ah cancel"));
        player.sendMessage(Util.text("&8&m--------------------------------"));
    }

    public boolean hasPending(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    public void confirm(Player player) {
        Pending p = pending.remove(player.getUniqueId());
        if (p == null) {
            player.sendMessage(Util.text("&cNothing waiting to be confirmed."));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!hand.isSimilar(p.item()) || hand.getAmount() < p.item().getAmount()) {
            player.sendMessage(Util.text("&cYou are not holding that item any more."));
            return;
        }
        player.getInventory().setItemInMainHand(null);

        Listing listing = new Listing();
        listing.id = UUID.randomUUID().toString().substring(0, 8);
        listing.seller = player.getUniqueId();
        listing.sellerName = player.getName();
        listing.item = p.item();
        listing.price = p.price();
        listing.expiry = System.currentTimeMillis()
                + plugin.ranks().of(player).listingHours * 3_600_000L;
        listings.put(listing.id, listing);
        save();

        player.sendMessage(Util.text("&aListed &f" + p.item().getAmount() + "x "
                + Util.nice(p.item().getType()) + " &afor &f" + plugin.econ().fmt(p.price()) + "&a."));
        Bukkit.broadcast(Util.text("&6[AH] &f" + player.getName() + " &7listed &f"
                + p.item().getAmount() + "x " + Util.nice(p.item().getType())
                + " &7for &a" + plugin.econ().fmt(p.price()) + " &8(/ah)"));
    }

    public void cancelPending(Player player) {
        if (pending.remove(player.getUniqueId()) != null) {
            player.sendMessage(Util.text("&cListing cancelled."));
        } else {
            player.sendMessage(Util.text("&cNothing waiting to be confirmed."));
        }
    }

    // ---------------- buying ----------------

    public List<Listing> all() {
        List<Listing> list = new ArrayList<>(listings.values());
        list.sort((a, b) -> Double.compare(a.price, b.price));
        return list;
    }

    public List<Listing> mine(UUID uuid) {
        List<Listing> list = new ArrayList<>();
        for (Listing listing : listings.values()) {
            if (listing.seller.equals(uuid)) list.add(listing);
        }
        return list;
    }

    /** The cheapest live listing for a material, or null when there is none. */
    public Listing cheapest(org.bukkit.Material material) {
        Listing best = null;
        for (Listing listing : listings.values()) {
            if (listing.item.getType() != material) continue;
            if (best == null || listing.price < best.price) best = listing;
        }
        return best;
    }

    public void buy(Player buyer, String id) {
        Listing listing = listings.get(id);
        if (listing == null) {
            buyer.sendMessage(Util.text("&cThat listing is gone."));
            return;
        }
        if (listing.seller.equals(buyer.getUniqueId())) {
            buyer.sendMessage(Util.text("&cThat is your own listing. Use the Mine tab to pull it back."));
            return;
        }

        // A listing priced below the item's guaranteed /sell value is topped up to
        // that value at purchase time: the seller is never shorted, and the buyer
        // pays the extra amount on top of the listed price to cover the gap.
        double sellFloor = plugin.econ().sellValue(listing.seller, listing.sellerName, listing.item);
        double price = Math.max(listing.price, sellFloor);

        if (!plugin.econ().withdraw(buyer.getUniqueId(), price)) {
            buyer.sendMessage(Util.text("&cYou need &f" + plugin.econ().fmt(price) + "&c."));
            return;
        }
        listings.remove(id);

        double tax = rankTax(listing.seller, listing.sellerName);
        double net = price * (1.0 - tax);
        plugin.econ().deposit(listing.seller, net);

        give(buyer, listing.item);
        buyer.sendMessage(Util.text("&aBought &f" + listing.item.getAmount() + "x "
                + Util.nice(listing.item.getType()) + " &afor &f"
                + plugin.econ().fmt(price) + "&a."));
        if (price > listing.price) {
            buyer.sendMessage(Util.text("&8Topped up from the listed " + plugin.econ().fmt(listing.price)
                    + " to match its guaranteed /sell value."));
        }

        Player seller = Bukkit.getPlayer(listing.seller);
        if (seller != null) {
            seller.sendMessage(Util.text("&aYour &f" + Util.nice(listing.item.getType())
                    + " &asold to &f" + buyer.getName() + " &afor &f" + plugin.econ().fmt(net) + "&a."));
        }
        save();
    }

    private double rankTax(UUID uuid, String name) {
        return plugin.ranks().of(uuid, name).auctionTax;
    }

    /** Pull your own listing back off the board. */
    public void retrieve(Player player, String id) {
        Listing listing = listings.get(id);
        if (listing == null || !listing.seller.equals(player.getUniqueId())) {
            player.sendMessage(Util.text("&cThat is not one of your listings."));
            return;
        }
        listings.remove(id);
        give(player, listing.item);
        player.sendMessage(Util.text("&aPulled &f" + Util.nice(listing.item.getType())
                + " &aback off the auction house."));
        save();
    }

    // ---------------- expiry and delivery ----------------

    public void tickExpiry() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Listing listing : new ArrayList<>(listings.values())) {
            if (now <= listing.expiry) continue;
            listings.remove(listing.id);
            Player seller = Bukkit.getPlayer(listing.seller);
            if (seller != null) {
                give(seller, listing.item);
                seller.sendMessage(Util.text("&7Your listing for &f"
                        + Util.nice(listing.item.getType()) + " &7expired and came back to you."));
            } else {
                mailbox.computeIfAbsent(listing.seller, k -> new ArrayList<>()).add(listing.item);
            }
            changed = true;
        }
        if (changed) save();
    }

    private void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    /** Hand over anything that piled up while the player was away. */
    public void deliver(Player player) {
        List<ItemStack> items = mailbox.remove(player.getUniqueId());
        if (items == null || items.isEmpty()) return;
        for (ItemStack item : items) give(player, item);
        player.sendMessage(Util.text("&7Returned &f" + items.size()
                + " &7expired auction item(s) to you."));
        save();
    }

    // ---------------- persistence ----------------

    @SuppressWarnings("unchecked")
    public void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        if (yml.isConfigurationSection("listings")) {
            for (String id : yml.getConfigurationSection("listings").getKeys(false)) {
                String path = "listings." + id + ".";
                ItemStack item = yml.getItemStack(path + "item");
                if (item == null) continue;
                Listing listing = new Listing();
                listing.id = id;
                listing.seller = UUID.fromString(yml.getString(path + "seller"));
                listing.sellerName = yml.getString(path + "seller-name", "?");
                listing.item = item;
                listing.price = yml.getDouble(path + "price");
                listing.expiry = yml.getLong(path + "expiry");
                listings.put(id, listing);
            }
        }
        if (yml.isConfigurationSection("mailbox")) {
            for (String raw : yml.getConfigurationSection("mailbox").getKeys(false)) {
                List<?> stored = yml.getList("mailbox." + raw);
                if (stored == null) continue;
                List<ItemStack> items = new ArrayList<>();
                for (Object o : stored) {
                    if (o instanceof ItemStack stack) items.add(stack);
                }
                mailbox.put(UUID.fromString(raw), items);
            }
        }
        plugin.getLogger().info("Loaded " + listings.size() + " auction listings.");
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Listing listing : listings.values()) {
            String path = "listings." + listing.id + ".";
            yml.set(path + "seller", listing.seller.toString());
            yml.set(path + "seller-name", listing.sellerName);
            yml.set(path + "item", listing.item);
            yml.set(path + "price", listing.price);
            yml.set(path + "expiry", listing.expiry);
        }
        for (Map.Entry<UUID, List<ItemStack>> e : mailbox.entrySet()) {
            yml.set("mailbox." + e.getKey(), e.getValue());
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed saving auctions: " + ex.getMessage());
        }
    }
}
