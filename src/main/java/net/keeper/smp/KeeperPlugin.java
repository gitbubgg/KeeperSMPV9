package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class KeeperPlugin extends JavaPlugin implements Listener {

    /**
     * Bumped whenever features change, so /keeper status and the startup log
     * say plainly which build is actually running.
     */
    public static final String BUILD = "2026-09-27 r22 (barriers, protect, tier-crates, "
            + "daily-caps, tnt-spawner, lobby, value, rank-tags, crate-nametags, "
            + "crate-spawnprotect-fix, daily-streaks, playtime-milestones, "
            + "bonus-events, sidebar, netherite-upgrade, elytra-shop, "
            + "elytra-stock, end-loot-strip, forcetpahere, shulker-shop, "
            + "nightvision-setting, nether-debris-limit, gunfight, "
            + "ownerkit, combat-teleport-fix, excavator, treefeller, "
            + "sell-everything, autoplace, deathchest, bounty, combatlog-kill, "
            + "trade, sendshards, leaderboards, enchantbooks, cosmetics, "
            + "rank-ping, sponsored-auctions, weekend-bonus, bedrock-guard, "
            + "xray-guard, macro-guard, rtp-nether-end, more-spawners, mega-sharpness, "
            + "mega-protection)";


    private Data data;
    private Ranks ranks;
    private Econ econ;
    private Homes homes;
    private Teleport teleport;
    private Auction auction;
    private Gui gui;
    private Stripe stripe;
    private MobControl mobControl;
    private Shards shards;
    private Crates crates;
    private Stash stash;
    private SpawnBuilder spawnBuilder;
    private Protect protect;
    private Spawners spawners;
    private Tablist tablist;
    private Playtime playtime;
    private Daily daily;
    private BonusEvents bonusEvents;
    private Sidebar sidebar;
    private EndLoot endLoot;
    private NetherLimit netherLimit;
    private Gunfight gunfight;
    private SpecialTools specialTools;
    private AutoPlace autoPlace;
    private DeathChest deathChest;
    private Bounty bounty;
    private Trade trade;
    private Leaderboard leaderboard;
    private Cosmetics cosmetics;
    private BedrockGuard bedrockGuard;
    private XrayGuard xrayGuard;
    private MacroGuard macroGuard;
    private MegaSharpness megaSharpness;
    private MegaProtection megaProtection;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Util.init(this);

        Rank.loadAll(getConfig().getConfigurationSection("ranks"),
                getConfig().getConfigurationSection("perks"));

        data = new Data(this);
        ranks = new Ranks(this);
        econ = new Econ(this);
        homes = new Homes(this);
        teleport = new Teleport(this);
        auction = new Auction(this);
        gui = new Gui(this);
        stripe = new Stripe(this);
        mobControl = new MobControl(this);
        crates = new Crates(this);
        shards = new Shards(this);
        stash = new Stash(this);
        spawnBuilder = new SpawnBuilder(this);
        protect = new Protect(this);
        spawners = new Spawners(this);
        tablist = new Tablist(this);
        tablist.setupTeams();
        playtime = new Playtime(this);
        daily = new Daily(this);
        bonusEvents = new BonusEvents(this);
        sidebar = new Sidebar(this);
        endLoot = new EndLoot(this);
        netherLimit = new NetherLimit(this);
        gunfight = new Gunfight(this);
        specialTools = new SpecialTools(this);
        autoPlace = new AutoPlace(this);
        deathChest = new DeathChest(this);
        bounty = new Bounty(this);
        trade = new Trade(this);
        leaderboard = new Leaderboard(this);
        cosmetics = new Cosmetics(this);
        bedrockGuard = new BedrockGuard();
        xrayGuard = new XrayGuard(this);
        macroGuard = new MacroGuard(this);
        megaSharpness = new MegaSharpness(this);
        megaProtection = new MegaProtection(this);

        Commands commands = new Commands(this);
        String[] names = {
                "menu", "shop", "sell", "autosell", "auction", "sethome", "home", "delhome", "homes",
                "rtp", "rtpq", "tpa", "tpahere", "tpaccept", "tpdeny", "back", "ec", "craft", "nick",
                "settings", "rank", "balance", "pay", "grantrank", "eco", "fly", "gmc", "forcetpa",
                "afk", "shardshop", "spectator", "spawnstash", "setcrate", "delcrate", "givekey",
                "usekey", "clearcrates",
                "buildspawn", "spawnfence", "value", "lobby", "setlobby", "keeper",
                "daily", "playtime", "upgrade", "forcetpahere", "gunfight", "ownerkit", "autoplace",
                "giveshards", "smtable", "bounty", "trade", "sendshards", "baltop", "playtimetop",
                "cosmetics"
        };
        for (String name : names) {
            if (getCommand(name) == null) {
                getLogger().warning("Command " + name + " is missing from plugin.yml");
                continue;
            }
            getCommand(name).setExecutor(commands);
            getCommand(name).setTabCompleter(commands);
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(ranks, this);
        Bukkit.getPluginManager().registerEvents(econ, this);
        Bukkit.getPluginManager().registerEvents(teleport, this);
        Bukkit.getPluginManager().registerEvents(gui, this);
        Bukkit.getPluginManager().registerEvents(mobControl, this);
        Bukkit.getPluginManager().registerEvents(shards, this);
        Bukkit.getPluginManager().registerEvents(crates, this);
        Bukkit.getPluginManager().registerEvents(stash, this);
        Bukkit.getPluginManager().registerEvents(protect, this);
        Bukkit.getPluginManager().registerEvents(spawners, this);
        Bukkit.getPluginManager().registerEvents(tablist, this);
        Bukkit.getPluginManager().registerEvents(playtime, this);
        Bukkit.getPluginManager().registerEvents(sidebar, this);
        Bukkit.getPluginManager().registerEvents(endLoot, this);
        endLoot.sweepLoadedChunks();
        Bukkit.getPluginManager().registerEvents(netherLimit, this);
        Bukkit.getPluginManager().registerEvents(gunfight, this);
        Bukkit.getPluginManager().registerEvents(specialTools, this);
        Bukkit.getPluginManager().registerEvents(autoPlace, this);
        Bukkit.getPluginManager().registerEvents(deathChest, this);
        Bukkit.getPluginManager().registerEvents(bounty, this);
        Bukkit.getPluginManager().registerEvents(trade, this);
        Bukkit.getPluginManager().registerEvents(cosmetics, this);
        Bukkit.getPluginManager().registerEvents(bedrockGuard, this);
        Bukkit.getPluginManager().registerEvents(xrayGuard, this);
        Bukkit.getPluginManager().registerEvents(macroGuard, this);
        Bukkit.getPluginManager().registerEvents(megaSharpness, this);
        Bukkit.getPluginManager().registerEvents(megaProtection, this);

        // tab list footer numbers
        Bukkit.getScheduler().runTaskTimer(this, () -> tablist.refresh(), 100L, 200L);
        // tnt spawners
        int tntInterval = Math.max(1, getConfig().getInt("tnt-spawner.interval-seconds", 3));
        Bukkit.getScheduler().runTaskTimer(this, () -> spawners.tick(),
                100L, tntInterval * 20L);
        // afk shard payouts
        Bukkit.getScheduler().runTaskTimer(this, () -> shards.tick(), 20L, 20L);
        // auction expiry
        Bukkit.getScheduler().runTaskTimer(this, () -> auction.tickExpiry(), 1200L, 1200L);
        // clear hostiles out of opted out areas
        Bukkit.getScheduler().runTaskTimer(this, () -> mobControl.cull(), 100L, 60L);
        // periodic save
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            data.saveAll();
            auction.save();
        }, 6000L, 6000L);
        // stripe polling, off the main thread
        int poll = Math.max(20, getConfig().getInt("stripe.poll-seconds", 60));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> stripe.poll(),
                200L, poll * 20L);
        // playtime tracking and milestone payouts
        Bukkit.getScheduler().runTaskTimer(this, () -> playtime.tick(), 1200L, 1200L);
        // scheduled bonus events
        int bonusInterval = Math.max(1, getConfig().getInt("bonus-events.interval-minutes", 45));
        Bukkit.getScheduler().runTaskTimer(this, () -> bonusEvents.tick(),
                bonusInterval * 1200L, bonusInterval * 1200L);
        // personal sidebar
        Bukkit.getScheduler().runTaskTimer(this, () -> sidebar.refresh(), 100L, 100L);
        // death chest expiry sweep
        Bukkit.getScheduler().runTaskTimer(this, () -> deathChest.tick(), 100L, 100L);
        // cosmetic pet following and trail particles
        Bukkit.getScheduler().runTaskTimer(this, () -> cosmetics.tick(), 20L, 10L);

        getLogger().info("KeeperSMP enabled. Stripe: " + (stripe.enabled() ? "on" : "off"));
        getLogger().info("Build: " + BUILD);
    }

    @Override
    public void onDisable() {
        if (playtime != null) playtime.tick();
        if (data != null) data.saveAll();
        if (auction != null) auction.save();
        if (stripe != null) stripe.saveState();
        if (crates != null) crates.saveBlocks();
        if (stash != null) stash.save();
        if (spawners != null) spawners.save();
        if (netherLimit != null) netherLimit.save();
        if (deathChest != null) deathChest.save();
        if (bounty != null) bounty.save();
    }

    public void reloadEverything() {
        reloadConfig();
        Rank.loadAll(getConfig().getConfigurationSection("ranks"),
                getConfig().getConfigurationSection("perks"));
        ranks.reload();
        econ.reload();
        crates.reload();
        shards.reload();
        playtime.reload();
        daily.reload();
        gunfight.reload();
    }

    /**
     * The exact spot /lobby sends people to, facing included. Falls back to
     * world spawn when nobody has set one.
     */
    public org.bukkit.Location lobby(org.bukkit.World fallbackWorld) {
        org.bukkit.Location stored = Util.deserialize(getConfig().getString("lobby.location"));
        if (stored != null) return stored;
        org.bukkit.World world = fallbackWorld == null
                ? getServer().getWorlds().get(0) : fallbackWorld;
        return world.getSpawnLocation().clone().add(0.5, 0, 0.5);
    }

    /** Stores the lobby point and moves world spawn to match. */
    public void setLobby(org.bukkit.Location location) {
        getConfig().set("lobby.location", Util.serialize(location));
        saveConfig();
        if (location.getWorld() != null) {
            location.getWorld().setSpawnLocation(location.getBlockX(), location.getBlockY(),
                    location.getBlockZ());
        }
    }

    /** Priority join, so ranked players can still get in on a full server. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.KICK_FULL) return;
        Rank rank = ranks.of(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        if (rank.priority || rank.isStaff()) {
            event.allow();
        }
    }

    public Data data() {
        return data;
    }

    public Ranks ranks() {
        return ranks;
    }

    public Econ econ() {
        return econ;
    }

    public Homes homes() {
        return homes;
    }

    public Teleport teleport() {
        return teleport;
    }

    public Auction auction() {
        return auction;
    }

    public Gui gui() {
        return gui;
    }

    public Stripe stripe() {
        return stripe;
    }

    public Shards shards() {
        return shards;
    }

    public Crates crates() {
        return crates;
    }

    public Stash stash() {
        return stash;
    }

    public SpawnBuilder spawnBuilder() {
        return spawnBuilder;
    }

    public Protect protect() {
        return protect;
    }

    public Spawners spawners() {
        return spawners;
    }

    public Tablist tablist() {
        return tablist;
    }

    public Playtime playtime() {
        return playtime;
    }

    public Daily daily() {
        return daily;
    }

    public BonusEvents bonusEvents() {
        return bonusEvents;
    }

    public Sidebar sidebar() {
        return sidebar;
    }

    public EndLoot endLoot() {
        return endLoot;
    }

    public NetherLimit netherLimit() {
        return netherLimit;
    }

    public Gunfight gunfight() {
        return gunfight;
    }

    public SpecialTools specialTools() {
        return specialTools;
    }

    public AutoPlace autoPlace() {
        return autoPlace;
    }

    public DeathChest deathChest() {
        return deathChest;
    }

    public Bounty bounty() {
        return bounty;
    }

    public Trade trade() {
        return trade;
    }

    public Leaderboard leaderboard() {
        return leaderboard;
    }

    public Cosmetics cosmetics() {
        return cosmetics;
    }

    public MegaSharpness megaSharpness() {
        return megaSharpness;
    }

    public MegaProtection megaProtection() {
        return megaProtection;
    }
}
