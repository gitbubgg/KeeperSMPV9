package net.keeper.smp;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Protection stand-in with no level cap, same idea as MegaSharpness: a
 * plain long tagged onto armor via PersistentDataContainer rather than a
 * real Enchantment (capped at 255 by Paper regardless of what the
 * enchantment itself declares as its max level). Reduces incoming damage
 * by a percentage summed across every worn piece that carries the tag,
 * uncapped - a high enough level really does block essentially all damage.
 * Damage is still floored at 0 rather than going negative, since negative
 * damage would heal the wearer instead of doing nothing.
 */
public class MegaProtection implements Listener {

    private final NamespacedKey key;

    public MegaProtection(KeeperPlugin plugin) {
        this.key = new NamespacedKey(plugin, "mega_protection");
    }

    /** Tags the item with the given level and adds a matching lore line. */
    public ItemStack apply(ItemStack item, long level) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.LONG, level);
        List<net.kyori.adventure.text.Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Util.text("&b&oMega Protection &f" + NumberFormat.getInstance(Locale.US).format(level)));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private long levelOf(ItemStack item) {
        if (item == null) return 0;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        Long level = meta.getPersistentDataContainer().get(key, PersistentDataType.LONG);
        return level == null ? 0 : level;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        long total = 0;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            total += levelOf(piece);
        }
        if (total <= 0) return;
        double percent = total * 0.05;
        event.setDamage(Math.max(0, event.getDamage() * (1 - percent)));
    }
}
