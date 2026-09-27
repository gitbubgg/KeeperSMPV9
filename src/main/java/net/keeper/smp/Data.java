package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Data {

    public static class PlayerData {
        public final UUID uuid;
        public String name = "";
        public double balance;
        public long shards = 0;
        public boolean afk = false;
        public Rank rank = Rank.KEEPER;
        /** Epoch millis the paid rank runs out. 0 means no expiry. */
        public long rankExpiry = 0L;
        public String subscriptionId = null;
        public String nickname = null;

        public boolean mobSpawns = true;
        public boolean acceptTpa = true;
        public boolean acceptTpaHere = true;
        public boolean autoSell = false;
        public boolean nightVision = false;

        public final Map<String, String> homes = new LinkedHashMap<>();
        /** "TIER:group" -> "count;resetEpochMillis" for crate daily limits. */
        public final Map<String, String> crateLimits = new LinkedHashMap<>();

        public long lastRtp = 0L;
        public long lastBack = 0L;
        public String backLocation = null;

        /** Runtime only, not persisted: when the current session started. */
        public long sessionStart = 0L;
        public long playtimeMillis = 0L;
        public final Set<Integer> playtimeMilestones = new HashSet<>();

        /** yyyy-MM-dd of the last /daily claim, or null if never claimed. */
        public String lastDailyDate = null;
        public int dailyStreak = 0;

        public final Set<String> ownedPets = new HashSet<>();
        public final Set<String> ownedTrails = new HashSet<>();
        public String activePet = null;
        public String activeTrail = null;

        PlayerData(UUID uuid) {
            this.uuid = uuid;
        }

        public Location back() {
            return Util.deserialize(backLocation);
        }
    }

    private final KeeperPlugin plugin;
    private final File folder;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    public Data(KeeperPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "players");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Could not create the players data folder.");
        }
    }

    private File fileFor(UUID uuid) {
        return new File(folder, uuid + ".yml");
    }

    public PlayerData get(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::load);
    }

    public PlayerData getIfLoaded(UUID uuid) {
        return cache.get(uuid);
    }

    private PlayerData load(UUID uuid) {
        PlayerData data = new PlayerData(uuid);
        data.balance = plugin.getConfig().getDouble("general.starting-balance", 500.0);
        File file = fileFor(uuid);
        if (!file.exists()) return data;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        data.name = yml.getString("name", "");
        data.balance = yml.getDouble("balance", data.balance);
        data.shards = yml.getLong("shards", 0);
        data.rank = Rank.parse(yml.getString("rank"), Rank.KEEPER);
        data.rankExpiry = yml.getLong("rank-expiry", 0L);
        data.subscriptionId = yml.getString("subscription");
        data.nickname = yml.getString("nickname");
        data.mobSpawns = yml.getBoolean("settings.mob-spawns", true);
        data.acceptTpa = yml.getBoolean("settings.accept-tpa", true);
        data.acceptTpaHere = yml.getBoolean("settings.accept-tpahere", true);
        data.autoSell = yml.getBoolean("settings.auto-sell", false);
        data.nightVision = yml.getBoolean("settings.night-vision", false);
        data.lastRtp = yml.getLong("last-rtp", 0L);
        data.lastBack = yml.getLong("last-back", 0L);
        data.backLocation = yml.getString("back");
        data.playtimeMillis = yml.getLong("playtime-millis", 0L);
        data.playtimeMilestones.addAll(yml.getIntegerList("playtime-milestones"));
        data.lastDailyDate = yml.getString("daily.last-date");
        data.dailyStreak = yml.getInt("daily.streak", 0);
        data.ownedPets.addAll(yml.getStringList("cosmetics.owned-pets"));
        data.ownedTrails.addAll(yml.getStringList("cosmetics.owned-trails"));
        data.activePet = yml.getString("cosmetics.active-pet");
        data.activeTrail = yml.getString("cosmetics.active-trail");

        if (yml.isConfigurationSection("crate-limits")) {
            for (String key : yml.getConfigurationSection("crate-limits").getKeys(false)) {
                data.crateLimits.put(key.replace('_', ':'), yml.getString("crate-limits." + key));
            }
        }
        if (yml.isConfigurationSection("homes")) {
            for (String key : yml.getConfigurationSection("homes").getKeys(false)) {
                data.homes.put(key, yml.getString("homes." + key));
            }
        }
        return data;
    }

    public void save(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data == null) return;
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("name", data.name);
        yml.set("balance", data.balance);
        yml.set("shards", data.shards);
        yml.set("rank", data.rank.name());
        yml.set("rank-expiry", data.rankExpiry);
        yml.set("subscription", data.subscriptionId);
        yml.set("nickname", data.nickname);
        yml.set("settings.mob-spawns", data.mobSpawns);
        yml.set("settings.accept-tpa", data.acceptTpa);
        yml.set("settings.accept-tpahere", data.acceptTpaHere);
        yml.set("settings.auto-sell", data.autoSell);
        yml.set("settings.night-vision", data.nightVision);
        yml.set("last-rtp", data.lastRtp);
        yml.set("last-back", data.lastBack);
        yml.set("back", data.backLocation);
        yml.set("playtime-millis", data.playtimeMillis);
        yml.set("playtime-milestones", new ArrayList<>(data.playtimeMilestones));
        yml.set("daily.last-date", data.lastDailyDate);
        yml.set("daily.streak", data.dailyStreak);
        yml.set("cosmetics.owned-pets", new ArrayList<>(data.ownedPets));
        yml.set("cosmetics.owned-trails", new ArrayList<>(data.ownedTrails));
        yml.set("cosmetics.active-pet", data.activePet);
        yml.set("cosmetics.active-trail", data.activeTrail);
        for (Map.Entry<String, String> e : data.homes.entrySet()) {
            yml.set("homes." + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : data.crateLimits.entrySet()) {
            yml.set("crate-limits." + e.getKey().replace(':', '_'), e.getValue());
        }
        try {
            yml.save(fileFor(uuid));
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed saving data for " + uuid + ": " + ex.getMessage());
        }
    }

    public void saveAll() {
        for (UUID uuid : cache.keySet()) save(uuid);
    }

    public void unload(UUID uuid) {
        save(uuid);
        if (Bukkit.getPlayer(uuid) == null) cache.remove(uuid);
    }

    /** Look up by name across the cache, for offline auction payouts. */
    public PlayerData byName(String name) {
        for (PlayerData d : cache.values()) {
            if (d.name.equalsIgnoreCase(name)) return d;
        }
        return null;
    }

    public Map<UUID, PlayerData> loaded() {
        return cache;
    }
}
