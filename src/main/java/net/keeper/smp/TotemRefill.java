package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Toggled per player via /totemrefill. When a real totem pops, every other
 * empty inventory slot (and the offhand, if the totem popped from the main
 * hand) gets restocked with a totem - except the exact slot the totem just
 * popped from, which is left empty. Two of the restocked slots are decoys:
 * a Totem of Undying by material (so it looks identical, same name and
 * icon) that's tagged to never actually save them - EntityResurrectEvent
 * still fires for it since vanilla only checks the material, so it's
 * cancelled outright instead of triggering a real resurrection.
 */
public class TotemRefill implements Listener {

    /** Marker so a decoy slot in the "eligible for removal" pool doesn't collide with a real slot index. */
    private static final int OFFHAND_SLOT = -1;

    private final KeeperPlugin plugin;
    private final NamespacedKey fakeKey;
    private final Random random = new Random();

    public TotemRefill(KeeperPlugin plugin) {
        this.plugin = plugin;
        this.fakeKey = new NamespacedKey(plugin, "fake_totem");
    }

    @EventHandler(ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!plugin.data().get(player.getUniqueId()).totemRefill) return;

        PlayerInventory inv = player.getInventory();
        ItemStack main = inv.getItemInMainHand();
        boolean fromMainHand = main.getType() == Material.TOTEM_OF_UNDYING;
        ItemStack triggering = fromMainHand ? main : inv.getItemInOffHand();

        if (isFake(triggering)) {
            event.setCancelled(true);
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> refill(player, fromMainHand));
    }

    private void refill(Player player, boolean fromMainHand) {
        PlayerInventory inv = player.getInventory();
        int heldSlot = inv.getHeldItemSlot();

        List<Integer> filled = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            if (fromMainHand && slot == heldSlot) continue;
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                inv.setItem(slot, new ItemStack(Material.TOTEM_OF_UNDYING));
                filled.add(slot);
            }
        }

        boolean offhandFilled = false;
        if (fromMainHand) {
            ItemStack off = inv.getItemInOffHand();
            if (off == null || off.getType() == Material.AIR) {
                inv.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
                offhandFilled = true;
            }
        }

        List<Integer> pool = new ArrayList<>(filled);
        if (offhandFilled) pool.add(OFFHAND_SLOT);
        Collections.shuffle(pool, random);
        for (int i = 0; i < Math.min(2, pool.size()); i++) {
            int slot = pool.get(i);
            if (slot == OFFHAND_SLOT) inv.setItemInOffHand(fakeTotem());
            else inv.setItem(slot, fakeTotem());
        }
    }

    private ItemStack fakeTotem() {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(fakeKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isFake(ItemStack item) {
        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(fakeKey, PersistentDataType.BYTE);
    }
}
