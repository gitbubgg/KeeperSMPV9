package net.keeper.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Util {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");

    public static NamespacedKey ACTION;
    public static NamespacedKey VALUE;

    private Util() {
    }

    static void init(KeeperPlugin plugin) {
        ACTION = new NamespacedKey(plugin, "action");
        VALUE = new NamespacedKey(plugin, "value");
    }

    public static Component text(String legacy) {
        return LEGACY.deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    public static String strip(String legacy) {
        return LEGACY.serialize(LEGACY.deserialize(legacy)).replaceAll("&[0-9a-fk-orx]", "");
    }

    public static String money(double amount) {
        return MONEY.format(amount);
    }

    /** Pretty name for a material, e.g. DIAMOND_PICKAXE -> Diamond Pickaxe. */
    public static String nice(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String part : m.name().toLowerCase().split("_")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    public static ItemStack item(Material material, int amount, String name, String... lore) {
        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (name != null) meta.displayName(text(name));
            if (lore.length > 0) {
                List<Component> lines = new ArrayList<>();
                for (String line : lore) lines.add(text(line));
                meta.lore(lines);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack tag(ItemStack stack, String action, String value) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(ACTION, PersistentDataType.STRING, action);
            if (value != null) {
                meta.getPersistentDataContainer().set(VALUE, PersistentDataType.STRING, value);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static String action(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(ACTION, PersistentDataType.STRING);
    }

    public static String value(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(VALUE, PersistentDataType.STRING);
    }

    /** Remaining durability as a 0..1 fraction. Non damageable items return 1. */
    public static double condition(ItemStack stack) {
        if (stack == null) return 0;
        short max = stack.getType().getMaxDurability();
        if (max <= 0) return 1.0;
        if (!(stack.getItemMeta() instanceof Damageable d) || !d.hasDamage()) return 1.0;
        return Math.max(0.0, 1.0 - ((double) d.getDamage() / (double) max));
    }

    public static String serialize(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return String.join(";", loc.getWorld().getName(),
                Double.toString(loc.getX()), Double.toString(loc.getY()), Double.toString(loc.getZ()),
                Float.toString(loc.getYaw()), Float.toString(loc.getPitch()));
    }

    public static Location deserialize(String raw) {
        if (raw == null) return null;
        String[] p = raw.split(";");
        if (p.length < 4) return null;
        World world = org.bukkit.Bukkit.getWorld(p[0]);
        if (world == null) return null;
        try {
            double x = Double.parseDouble(p[1]);
            double y = Double.parseDouble(p[2]);
            double z = Double.parseDouble(p[3]);
            float yaw = p.length > 4 ? Float.parseFloat(p[4]) : 0f;
            float pitch = p.length > 5 ? Float.parseFloat(p[5]) : 0f;
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Parses amounts written the way players type them: 200k, 5m, 1.5b, 2t,
     * with commas and stray currency symbols tolerated. Returns -1 when the
     * text is not a number.
     */
    public static double parseAmount(String raw) {
        if (raw == null) return -1;
        String text = raw.trim().toLowerCase().replace(",", "").replace("$", "");
        if (text.isEmpty()) return -1;
        double multiplier = 1;
        char last = text.charAt(text.length() - 1);
        switch (last) {
            case 'k' -> multiplier = 1_000d;
            case 'm' -> multiplier = 1_000_000d;
            case 'b' -> multiplier = 1_000_000_000d;
            case 't' -> multiplier = 1_000_000_000_000d;
            default -> {
            }
        }
        if (multiplier > 1) text = text.substring(0, text.length() - 1);
        if (text.isEmpty()) return -1;
        try {
            double value = Double.parseDouble(text) * multiplier;
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) return -1;
            return value;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public static List<String> lines(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }
}
