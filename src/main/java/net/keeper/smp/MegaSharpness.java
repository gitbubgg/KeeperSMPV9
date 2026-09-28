package net.keeper.smp;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Sharpness stand-in with no level cap. A real Enchantment - vanilla or a
 * custom-registered one - still has to go through Paper's own item-meta
 * enchant validation, which rejects anything outside [1, 255] regardless of
 * what the enchantment itself declares as its max level. This sidesteps
 * that entirely: the "level" is just a plain long tagged onto the item via
 * PersistentDataContainer, shown as a fake enchant line in the lore, and
 * applied as bonus melee damage using the same formula vanilla Sharpness
 * uses (extra damage = level * 0.5 + 0.5).
 */
public class MegaSharpness implements Listener {

    private final NamespacedKey key;

    public MegaSharpness(KeeperPlugin plugin) {
        this.key = new NamespacedKey(plugin, "mega_sharpness");
    }

    /** Tags the item with the given level and adds a matching lore line. */
    public ItemStack apply(ItemStack item, long level) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.LONG, level);
        List<net.kyori.adventure.text.Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Util.text("&b&oMega Sharpness &f" + NumberFormat.getInstance(Locale.US).format(level)));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public long levelOf(ItemStack item) {
        if (item == null) return 0;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        Long level = meta.getPersistentDataContainer().get(key, PersistentDataType.LONG);
        return level == null ? 0 : level;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        long level = levelOf(player.getInventory().getItemInMainHand());
        if (level <= 0) return;
        event.setDamage(event.getDamage() + level * 0.5 + 0.5);
    }
}
