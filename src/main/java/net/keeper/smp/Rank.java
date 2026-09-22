package net.keeper.smp;

import org.bukkit.configuration.ConfigurationSection;

public enum Rank {
    KEEPER(0),
    KEEPER_PLUS(1),
    KEEPER_PLUS2(2),
    KEEPER_PLUS3(3),
    MOD(4),
    ADMIN(5),
    OWNER(6);

    /** Higher weight wins when two ranks are compared. */
    public final int weight;

    public String display = "Keeper";
    public int homes = 3;
    public int listings = 20;
    public boolean autosell = false;
    public double sellBonus = 0.0;
    public double shopDiscount = 0.0;
    public double auctionTax = 0.05;
    public int listingHours = 24;
    public int tpWarmup = 5;
    public int rtpCooldown = 300;
    public String back = "none";
    public int backCooldown = 600;
    public boolean enderchest = false;
    public boolean craftingtable = false;
    public boolean nickname = false;
    public boolean priority = false;

    Rank(int weight) {
        this.weight = weight;
    }

    public boolean atLeast(Rank other) {
        return this.weight >= other.weight;
    }

    public boolean isStaff() {
        return atLeast(MOD);
    }

    /** Ranks that can be bought. */
    public boolean isPurchasable() {
        return this == KEEPER_PLUS || this == KEEPER_PLUS2 || this == KEEPER_PLUS3;
    }

    public static Rank parse(String raw, Rank fallback) {
        if (raw == null) return fallback;
        String s = raw.trim().toUpperCase()
                .replace(' ', '_')
                .replace("KEEPER+++", "KEEPER_PLUS3")
                .replace("KEEPER++", "KEEPER_PLUS2")
                .replace("KEEPER+", "KEEPER_PLUS");
        try {
            return Rank.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    static void loadAll(ConfigurationSection root, ConfigurationSection perkToggles) {
        boolean sellBonusOn = perkToggles == null || perkToggles.getBoolean("sell-bonus", true);
        boolean discountOn = perkToggles == null || perkToggles.getBoolean("shop-discount", true);
        boolean taxOn = perkToggles == null || perkToggles.getBoolean("auction-tax", true);
        boolean priorityOn = perkToggles == null || perkToggles.getBoolean("priority-join", true);
        boolean nickOn = perkToggles == null || perkToggles.getBoolean("nickname-colour", true);

        for (Rank r : values()) {
            ConfigurationSection s = root == null ? null : root.getConfigurationSection(r.name());
            if (s == null) continue;
            r.display = s.getString("display", r.name());
            r.homes = s.getInt("homes", r.homes);
            r.listings = s.getInt("listings", r.listings);
            r.autosell = s.getBoolean("autosell", r.autosell);
            r.sellBonus = sellBonusOn ? s.getDouble("sell-bonus", 0.0) : 0.0;
            r.shopDiscount = discountOn ? s.getDouble("shop-discount", 0.0) : 0.0;
            r.auctionTax = taxOn ? s.getDouble("auction-tax", 0.05) : 0.05;
            r.listingHours = s.getInt("listing-hours", r.listingHours);
            r.tpWarmup = s.getInt("tp-warmup", r.tpWarmup);
            r.rtpCooldown = s.getInt("rtp-cooldown", r.rtpCooldown);
            r.back = s.getString("back", r.back);
            r.backCooldown = s.getInt("back-cooldown", r.backCooldown);
            r.enderchest = s.getBoolean("enderchest", r.enderchest);
            r.craftingtable = s.getBoolean("craftingtable", r.craftingtable);
            r.nickname = nickOn && s.getBoolean("nickname", false);
            r.priority = priorityOn && s.getBoolean("priority", false);
        }
    }
}
