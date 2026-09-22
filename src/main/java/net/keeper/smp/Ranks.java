package net.keeper.smp;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class Ranks implements Listener {

    private final KeeperPlugin plugin;
    private final Set<String> owners = new HashSet<>();
    private final Set<String> admins = new HashSet<>();
    private final Set<String> mods = new HashSet<>();

    public Ranks(KeeperPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        owners.clear();
        admins.clear();
        mods.clear();
        for (String s : plugin.getConfig().getStringList("staff.owner")) owners.add(norm(s));
        for (String s : plugin.getConfig().getStringList("staff.admin")) admins.add(norm(s));
        for (String s : plugin.getConfig().getStringList("staff.mod")) mods.add(norm(s));
    }

    /**
     * Bedrock names arrive through Floodgate with a prefix and with spaces turned
     * into underscores, so compare on a form that ignores both.
     */
    private String norm(String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.ROOT).replace(" ", "_").replace(".", "");
    }

    private Rank staffRank(String name) {
        String n = norm(name);
        if (owners.contains(n)) return Rank.OWNER;
        if (admins.contains(n)) return Rank.ADMIN;
        if (mods.contains(n)) return Rank.MOD;
        return null;
    }

    /** The rank actually in force for this player right now. */
    public Rank of(Player player) {
        Rank staff = staffRank(player.getName());
        if (staff != null) return staff;
        return purchased(player.getUniqueId());
    }

    public Rank of(UUID uuid, String name) {
        Rank staff = staffRank(name);
        if (staff != null) return staff;
        return purchased(uuid);
    }

    /** The paid rank on file, dropping it back to Keeper if it has lapsed. */
    public Rank purchased(UUID uuid) {
        Data.PlayerData data = plugin.data().get(uuid);
        if (data.rank.isPurchasable() && data.rankExpiry > 0 && System.currentTimeMillis() > data.rankExpiry) {
            data.rank = Rank.KEEPER;
            data.rankExpiry = 0;
            data.subscriptionId = null;
            plugin.data().save(uuid);
        }
        return data.rank;
    }

    public void grant(UUID uuid, Rank rank, long expiryMillis, String subscriptionId) {
        Data.PlayerData data = plugin.data().get(uuid);
        data.rank = rank;
        data.rankExpiry = expiryMillis;
        if (subscriptionId != null) data.subscriptionId = subscriptionId;
        plugin.data().save(uuid);

        Player online = plugin.getServer().getPlayer(uuid);
        if (online != null) {
            online.sendMessage(Util.text("&aYour rank is now " + rank.display + "&a."));
            apply(online);
        }
    }

    public void revoke(UUID uuid) {
        Data.PlayerData data = plugin.data().get(uuid);
        data.rank = Rank.KEEPER;
        data.rankExpiry = 0;
        data.subscriptionId = null;
        plugin.data().save(uuid);
        Player online = plugin.getServer().getPlayer(uuid);
        if (online != null) {
            online.sendMessage(Util.text("&cYour paid rank has ended. You are back to &7Keeper&c."));
            apply(online);
        }
    }

    /** Push rank driven abilities onto the player. */
    public void apply(Player player) {
        Rank rank = of(player);
        if (rank.atLeast(Rank.ADMIN)) {
            player.setAllowFlight(true);
        } else if (!player.getGameMode().name().equals("CREATIVE")
                && !player.getGameMode().name().equals("SPECTATOR")) {
            player.setAllowFlight(false);
            player.setFlying(false);
        }
        if (rank.nickname && plugin.data().get(player.getUniqueId()).nickname != null) {
            player.displayName(Util.text(plugin.data().get(player.getUniqueId()).nickname));
        }
        if (plugin.tablist() != null) {
            plugin.tablist().apply(player);
            plugin.tablist().decorate(player);
        }
    }

    public int homeLimit(Player player) {
        return of(player).homes;
    }

    public int listingLimit(Player player) {
        return of(player).listings;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        data.name = player.getName();
        purchased(player.getUniqueId());
        apply(player);
        plugin.auction().deliver(player);
        player.sendMessage(Util.text("&8&m--------------------------------"));
        player.sendMessage(Util.text(" &fRank: " + of(player).display
                + "  &7|  &fBalance: &a" + plugin.econ().fmt(data.balance)));
        player.sendMessage(Util.text(" &7Type &f/menu &7to open the server menu."));
        player.sendMessage(Util.text("&8&m--------------------------------"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.data().unload(event.getPlayer().getUniqueId());
    }
}
