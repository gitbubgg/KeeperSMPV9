package net.keeper.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class Gui implements Listener {

    /** Marks an inventory as one of ours so clicks can be locked down. */
    public static class Holder implements InventoryHolder {
        public final String screen;
        public Inventory inventory;

        Holder(String screen) {
            this.screen = screen;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int PAGE_SIZE = 45;
    private final KeeperPlugin plugin;

    public Gui(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    private Inventory create(String screen, int rows, String title) {
        Holder holder = new Holder(screen);
        Inventory inventory = Bukkit.createInventory(holder, rows * 9, Util.text(title));
        holder.inventory = inventory;
        return inventory;
    }

    private ItemStack filler() {
        return Util.item(Material.GRAY_STAINED_GLASS_PANE, 1, "&8");
    }

    // ---------------- main menu ----------------

    public void openMenu(Player player) {
        Inventory inv = create("menu", 4, "&8Server Menu");
        Rank rank = plugin.ranks().of(player);
        Data.PlayerData data = plugin.data().get(player.getUniqueId());

        inv.setItem(10, Util.tag(Util.item(Material.EMERALD, 1, "&aShop",
                "&7Buy gear, blocks and consumables.", "", "&eClick to open"), "shop", null));
        inv.setItem(12, Util.tag(Util.item(Material.CHEST, 1, "&6Auction House",
                "&7Buy and sell with other players.",
                "&7Your listings: &f" + plugin.auction().countFor(player.getUniqueId())
                        + "&7/&f" + rank.listings, "", "&eClick to open"), "ah", null));
        inv.setItem(14, Util.tag(Util.item(Material.RED_BED, 1, "&dHomes",
                "&7Homes: &f" + data.homes.size() + "&7/&f" + rank.homes,
                "&7Set one with &f/sethome <name>", "", "&eClick to list"), "homes", null));
        inv.setItem(16, Util.tag(Util.item(Material.COMPASS, 1, "&bRandom Teleport",
                "&7Drop into the wild.",
                "&7Cooldown: &f" + rank.rtpCooldown + "s", "", "&eClick to run /rtp"), "rtp", null));
        inv.setItem(20, Util.tag(Util.item(Material.GOLD_INGOT, 1, "&eBalance",
                "&7You have &a" + plugin.econ().fmt(data.balance),
                "", "&eClick to sell your held item"), "sell_hand", null));
        inv.setItem(22, Util.tag(Util.item(Material.COMPARATOR, 1, "&fSettings",
                "&7Mob spawning, teleport requests, auto sell.", "", "&eClick to open"),
                "settings", null));
        inv.setItem(24, Util.tag(Util.item(Material.NETHER_STAR, 1, "&6Ranks",
                "&7Your rank: " + rank.display, "", "&eClick to view ranks"), "ranks", null));

        inv.setItem(30, Util.tag(Util.item(Material.AMETHYST_SHARD, 1, "&bShard Shop",
                "&7Your shards: &f" + Util.money(plugin.shards().balance(player.getUniqueId())),
                "&7Spawners, netherite and crate keys.",
                "", "&eClick to open"), "shardshop", null));
        inv.setItem(32, Util.tag(Util.item(Material.CLOCK, 1, "&fGo AFK",
                "&7Earn &b" + plugin.getConfig().getLong("afk.shards-per-minute", 1)
                        + " shard(s) &7per minute while idle.",
                "", "&eClick to run /afk"), "afk", null));

        for (int i = 27; i < 36; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler());
        }
        inv.setItem(31, Util.tag(Util.item(Material.BARRIER, 1, "&cClose"), "close", null));
        player.openInventory(inv);
    }

    // ---------------- shop ----------------

    public void openShop(Player player, int page) {
        List<Econ.ShopEntry> entries = new ArrayList<>(plugin.econ().shopEntries().values());
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        Inventory inv = create("shop", 6, "&8Shop &7(page " + (page + 1) + "/" + pages + ")");
        double discount = plugin.ranks().of(player).shopDiscount;

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= entries.size()) break;
            Econ.ShopEntry entry = entries.get(index);

            List<String> lore = new ArrayList<>();
            if (entry.unit() > 1) {
                lore.add("&7Sold in bundles of &f" + entry.unit());
                lore.add("&7Bundle price: &a" + plugin.econ().fmt(entry.price() * (1 - discount)));
                lore.add("&8(" + plugin.econ().fmt(entry.unitPrice() * (1 - discount)) + " each)");
            } else {
                lore.add("&7Price: &a" + plugin.econ().fmt(entry.price() * (1 - discount)));
            }
            if (discount > 0) lore.add("&d" + Math.round(discount * 100) + "% rank discount applied");
            lore.add("");
            if (entry.unit() > 1) {
                lore.add("&eLeft click &7buy a bundle of " + entry.unit());
                if (entry.half()) lore.add("&eRight click &7buy a half bundle of " + entry.unit() / 2);
            } else {
                lore.add("&eLeft click &7buy 1");
                lore.add("&eRight click &7buy 8");
                lore.add("&eShift click &7buy 32");
            }
            double sellBack = plugin.econ().basePrice(entry.material());
            if (sellBack > 0) lore.add("&8Sells back for " + plugin.econ().fmt(sellBack) + " each");

            int stockLeft = plugin.econ().stockRemaining(entry.material());
            if (stockLeft >= 0) {
                lore.add("");
                lore.add(stockLeft > 0 ? "&6" + stockLeft + " left in stock" : "&cSold out");
            }

            ItemStack display = Util.item(entry.material(), Math.min(entry.unit(), 64),
                    "&f" + Util.nice(entry.material()), lore.toArray(new String[0]));
            inv.setItem(i, Util.tag(display, "shopitem", entry.material().name()));
        }

        inv.setItem(45, Util.tag(Util.item(Material.ARROW, 1, "&fPrevious page"), "shoppage",
                String.valueOf(page - 1)));
        inv.setItem(48, Util.tag(Util.item(Material.HOPPER, 1, "&aSell held item",
                "&7Same as &f/sell"), "sell_hand", null));
        inv.setItem(49, Util.tag(Util.item(Material.NETHER_STAR, 1, "&eBack to menu"), "menu", null));
        inv.setItem(50, Util.tag(Util.item(Material.CHEST, 1, "&aSell everything",
                "&7Same as &f/sell all", "&cSells every sellable item you carry."),
                "sell_all", null));
        inv.setItem(53, Util.tag(Util.item(Material.ARROW, 1, "&fNext page"), "shoppage",
                String.valueOf(page + 1)));
        inv.setItem(46, Util.item(Material.GOLD_INGOT, 1, "&eBalance",
                "&a" + plugin.econ().fmt(plugin.econ().balance(player.getUniqueId()))));
        player.openInventory(inv);
    }

    // ---------------- auction house ----------------

    public void openAuction(Player player, int page, boolean mineOnly) {
        List<Auction.Listing> listings = mineOnly
                ? plugin.auction().mine(player.getUniqueId())
                : plugin.auction().all();
        int pages = Math.max(1, (int) Math.ceil(listings.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        Inventory inv = create(mineOnly ? "ah_mine" : "ah", 6,
                (mineOnly ? "&8Your Listings " : "&8Auction House ") + "&7(" + (page + 1) + "/" + pages + ")");

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= listings.size()) break;
            Auction.Listing listing = listings.get(index);

            ItemStack display = listing.item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                if (meta.hasLore() && meta.lore() != null) lore.addAll(meta.lore());
                lore.add(Util.text("&8"));
                lore.add(Util.text("&7Price: &a" + plugin.econ().fmt(listing.price)));
                lore.add(Util.text("&7Seller: &f" + listing.sellerName));
                long hours = Math.max(0, (listing.expiry - System.currentTimeMillis()) / 3_600_000L);
                lore.add(Util.text("&7Expires in: &f" + hours + "h"));
                lore.add(Util.text("&8"));
                lore.add(Util.text(mineOnly ? "&eClick to pull it back" : "&eClick to buy"));
                meta.lore(lore);
                display.setItemMeta(meta);
            }
            inv.setItem(i, Util.tag(display, mineOnly ? "ah_take" : "ah_buy", listing.id));
        }

        inv.setItem(45, Util.tag(Util.item(Material.ARROW, 1, "&fPrevious page"),
                mineOnly ? "ahminepage" : "ahpage", String.valueOf(page - 1)));
        inv.setItem(48, Util.tag(Util.item(Material.WRITABLE_BOOK, 1, "&aSell held item",
                "&7Hold the item in your &fmain hand&7,",
                "&7then run &f/ah sell <price>&7.",
                "&8The offhand is never used."), "ah_help", null));
        inv.setItem(49, Util.tag(Util.item(Material.NETHER_STAR, 1, "&eBack to menu"), "menu", null));
        inv.setItem(50, Util.tag(Util.item(Material.ENDER_CHEST, 1,
                mineOnly ? "&6All listings" : "&6Your listings",
                "&7You have &f" + plugin.auction().countFor(player.getUniqueId())
                        + "&7/&f" + plugin.ranks().listingLimit(player)),
                mineOnly ? "ah" : "ah_mine", null));
        inv.setItem(53, Util.tag(Util.item(Material.ARROW, 1, "&fNext page"),
                mineOnly ? "ahminepage" : "ahpage", String.valueOf(page + 1)));
        inv.setItem(46, Util.item(Material.GOLD_INGOT, 1, "&eBalance",
                "&a" + plugin.econ().fmt(plugin.econ().balance(player.getUniqueId()))));
        player.openInventory(inv);
    }

    // ---------------- settings ----------------

    public void openSettings(Player player) {
        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        Rank rank = plugin.ranks().of(player);
        Inventory inv = create("settings", 3, "&8Your Settings");

        inv.setItem(10, Util.tag(Util.item(data.mobSpawns ? Material.ZOMBIE_HEAD : Material.BARRIER, 1,
                "&fMob spawning: " + (data.mobSpawns ? "&aON" : "&cOFF"),
                "&7When off, hostile mobs stop spawning",
                "&7near you and any that wander into your",
                "&7chunks are cleared, as long as nobody",
                "&7nearby has spawning switched on.",
                "", "&eClick to toggle"), "toggle", "mob"));

        inv.setItem(12, Util.tag(Util.item(data.acceptTpa ? Material.ENDER_PEARL : Material.BARRIER, 1,
                "&f/tpa requests: " + (data.acceptTpa ? "&aON" : "&cOFF"),
                "&7Others asking to teleport to you.",
                "&8Staff can still teleport to you.",
                "", "&eClick to toggle"), "toggle", "tpa"));

        inv.setItem(14, Util.tag(Util.item(data.acceptTpaHere ? Material.ENDER_EYE : Material.BARRIER, 1,
                "&f/tpahere requests: " + (data.acceptTpaHere ? "&aON" : "&cOFF"),
                "&7Others asking you to come to them.",
                "&8Staff can still summon you.",
                "", "&eClick to toggle"), "toggle", "tpahere"));

        boolean canAuto = rank.autosell;
        inv.setItem(16, Util.tag(Util.item(canAuto
                        ? (data.autoSell ? Material.HOPPER : Material.CHEST_MINECART)
                        : Material.IRON_BARS, 1,
                "&fAuto sell: " + (!canAuto ? "&8locked" : data.autoSell ? "&aON" : "&cOFF"),
                canAuto ? "&7Sellable items you pick up are" : "&cNeeds Keeper+ or higher.",
                canAuto ? "&7cashed in straight away." : "&7See &f/rank&7.",
                "", canAuto ? "&eClick to toggle" : "&8Unavailable"), "toggle", "autosell"));

        inv.setItem(13, Util.tag(Util.item(Material.GOLDEN_CARROT, 1,
                "&fNight vision: " + (data.nightVision ? "&aON" : "&cOFF"),
                "&7Always see clearly in the dark.",
                "", "&eClick to toggle"), "toggle", "nightvision"));

        inv.setItem(22, Util.tag(Util.item(Material.NETHER_STAR, 1, "&eBack to menu"), "menu", null));
        player.openInventory(inv);
    }

    // ---------------- ranks ----------------

    public void openRanks(Player player) {
        Inventory inv = create("ranks", 3, "&8Ranks");
        Rank current = plugin.ranks().of(player);

        inv.setItem(10, Util.item(Material.LEATHER_CHESTPLATE, 1, "&7Keeper &8(default)",
                "&7Homes: &f" + Rank.KEEPER.homes,
                "&7Listings: &f" + Rank.KEEPER.listings,
                "&7/rtp cooldown: &f" + Rank.KEEPER.rtpCooldown + "s",
                "", current == Rank.KEEPER ? "&aThis is your rank" : "&8Included for everyone"));

        inv.setItem(12, rankItem(player, Rank.KEEPER_PLUS, "3.50"));
        inv.setItem(14, rankItem(player, Rank.KEEPER_PLUS2, "6.50"));
        inv.setItem(16, rankItem(player, Rank.KEEPER_PLUS3, "11.00"));

        if (current.isStaff()) {
            inv.setItem(4, Util.item(Material.SHIELD, 1, "&fStaff rank: " + current.display,
                    "&7Staff ranks are assigned, not sold."));
        }
        inv.setItem(22, Util.tag(Util.item(Material.NETHER_STAR, 1, "&eBack to menu"), "menu", null));
        player.openInventory(inv);
    }

    private ItemStack rankItem(Player player, Rank rank, String price) {
        Rank current = plugin.ranks().of(player);
        Material icon = switch (rank) {
            case KEEPER_PLUS -> Material.IRON_CHESTPLATE;
            case KEEPER_PLUS2 -> Material.DIAMOND_CHESTPLATE;
            default -> Material.NETHERITE_CHESTPLATE;
        };
        List<String> lore = new ArrayList<>();
        lore.add("&7" + price + " USD per month");
        lore.add("");
        lore.add("&7Homes: &f" + rank.homes);
        lore.add("&7Auction listings: &f" + rank.listings);
        lore.add("&7Auction fee: &f" + Math.round(rank.auctionTax * 100) + "%");
        lore.add("&7Listing length: &f" + rank.listingHours + "h");
        if (rank.sellBonus > 0) lore.add("&7Sell payout: &a+" + Math.round(rank.sellBonus * 100) + "%");
        if (rank.shopDiscount > 0) lore.add("&7Shop discount: &a" + Math.round(rank.shopDiscount * 100) + "%");
        lore.add("&7Teleport warmup: &f" + rank.tpWarmup + "s");
        lore.add("&7/rtp cooldown: &f" + rank.rtpCooldown + "s");
        if (rank.autosell) lore.add("&a- Auto sell");
        if (!"none".equals(rank.back)) lore.add("&a- /back after death (" + rank.back + ")");
        if (rank.enderchest) lore.add("&a- /ec anywhere");
        if (rank.craftingtable) lore.add("&a- /craft anywhere");
        if (rank.nickname) lore.add("&a- Coloured nickname");
        if (rank.priority) lore.add("&a- Priority join");
        lore.add("");
        if (current == rank) {
            lore.add("&aThis is your current rank");
        } else if (current.weight > rank.weight) {
            lore.add("&8You already have a higher rank");
        } else {
            lore.add("&eClick for a checkout link");
        }
        return Util.tag(Util.item(icon, 1, rank.display, lore.toArray(new String[0])),
                "buyrank", rank.name());
    }

    /** Hands the player a personal Stripe link carrying their checkout code. */
    public void sendCheckout(Player player, Rank rank) {
        if (!rank.isPurchasable()) {
            player.sendMessage(Util.text("&cThat rank is not for sale."));
            return;
        }
        String base = plugin.getConfig().getString("stripe.links." + rank.name(), "");
        if (base.isEmpty()) {
            player.sendMessage(Util.text("&cNo checkout link is configured for that rank."));
            return;
        }
        String code = plugin.stripe().issueCode(player, rank);
        String url = base + (base.contains("?") ? "&" : "?") + "client_reference_id=" + code;

        player.closeInventory();
        player.sendMessage(Util.text("&8&m--------------------------------"));
        player.sendMessage(Util.text(" &fCheckout for " + rank.display));
        player.sendMessage(Util.text(" &7Your code: &e" + code));
        player.sendMessage(Util.text(" &7Valid for &f"
                + plugin.getConfig().getInt("stripe.code-ttl-minutes", 30) + " minutes&7."));
        player.sendMessage(Component.text("   ")
                .append(Util.text("&b&nClick here to open checkout"))
                .clickEvent(ClickEvent.openUrl(url)));
        player.sendMessage(Util.text(" &8Or copy this link:"));
        player.sendMessage(Util.text(" &f" + url));
        player.sendMessage(Util.text(" &7Your rank lands within a minute of payment."));
        player.sendMessage(Util.text("&8&m--------------------------------"));
    }

    // ---------------- clicks ----------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clicked = event.getCurrentItem();
        String action = Util.action(clicked);
        if (action == null) return;
        String value = Util.value(clicked);

        switch (action) {
            case "close" -> player.closeInventory();
            case "menu" -> openMenu(player);
            case "shop" -> openShop(player, 0);
            case "shoppage" -> openShop(player, parse(value));
            case "ah" -> openAuction(player, 0, false);
            case "ahpage" -> openAuction(player, parse(value), false);
            case "ah_mine" -> openAuction(player, 0, true);
            case "ahminepage" -> openAuction(player, parse(value), true);
            case "settings" -> openSettings(player);
            case "ranks" -> openRanks(player);
            case "shardshop" -> plugin.shards().openShop(player, 0);
            case "shardpage" -> plugin.shards().openShop(player, parse(value));
            case "shardbuy" -> plugin.shards().buy(player, value);
            case "afk" -> {
                player.closeInventory();
                plugin.shards().toggle(player);
            }
            case "homes" -> {
                player.closeInventory();
                plugin.homes().list(player);
            }
            case "rtp" -> {
                player.closeInventory();
                plugin.teleport().rtp(player);
            }
            case "sell_hand" -> {
                plugin.econ().sellHand(player);
                if (holder.screen.equals("shop")) openShop(player, 0);
            }
            case "sell_all" -> {
                plugin.econ().sellAll(player);
                openShop(player, 0);
            }
            case "shopitem" -> {
                Material material = Material.matchMaterial(value == null ? "" : value);
                if (material == null) return;
                Econ.ShopEntry entry = plugin.econ().shopEntry(material);
                if (entry == null) return;
                int count;
                if (entry.unit() > 1) {
                    count = event.isRightClick() && entry.half() ? entry.unit() / 2 : entry.unit();
                } else if (event.isShiftClick()) {
                    count = 32;
                } else if (event.isRightClick()) {
                    count = 8;
                } else {
                    count = 1;
                }
                plugin.econ().buy(player, material, count);
                openShop(player, currentShopPage(event));
            }
            case "ah_buy" -> {
                plugin.auction().buy(player, value);
                openAuction(player, 0, false);
            }
            case "ah_take" -> {
                plugin.auction().retrieve(player, value);
                openAuction(player, 0, true);
            }
            case "ah_help" -> {
                player.closeInventory();
                player.sendMessage(Util.text("&7Hold the item in your &fmain hand&7 and run &f/ah sell <price>&7."));
            }
            case "toggle" -> {
                Data.PlayerData data = plugin.data().get(player.getUniqueId());
                switch (value == null ? "" : value) {
                    case "mob" -> data.mobSpawns = !data.mobSpawns;
                    case "tpa" -> data.acceptTpa = !data.acceptTpa;
                    case "tpahere" -> data.acceptTpaHere = !data.acceptTpaHere;
                    case "autosell" -> {
                        if (!plugin.ranks().of(player).autosell) {
                            player.sendMessage(Util.text("&cAuto sell needs Keeper+ or higher."));
                            return;
                        }
                        data.autoSell = !data.autoSell;
                    }
                    case "nightvision" -> {
                        data.nightVision = !data.nightVision;
                        Util.nightVision(player, data.nightVision);
                    }
                    default -> {
                        return;
                    }
                }
                plugin.data().save(player.getUniqueId());
                openSettings(player);
            }
            case "buyrank" -> sendCheckout(player, Rank.parse(value, Rank.KEEPER));
            default -> {
            }
        }
    }

    private int currentShopPage(InventoryClickEvent event) {
        ItemStack next = event.getInventory().getItem(53);
        String value = Util.value(next);
        int parsed = parse(value);
        return Math.max(0, parsed - 1);
    }

    private int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ex) {
            return 0;
        }
    }
}
