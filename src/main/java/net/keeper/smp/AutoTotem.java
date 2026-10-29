package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Keeps a totem topped up in the offhand of whichever players are listed in
 * config (auto-totem.players), pulling one from their main inventory the
 * moment the offhand isn't already carrying one. Checked on a repeating
 * timer rather than an event, since there's no single Bukkit event that
 * reliably fires for every way an offhand totem can go away.
 */
public class AutoTotem {

    private final KeeperPlugin plugin;

    public AutoTotem(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    private List<String> listedPlayers() {
        return plugin.getConfig().getStringList("auto-totem.players");
    }

    public void tick() {
        List<String> names = listedPlayers();
        if (names.isEmpty()) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean listed = names.stream().anyMatch(n -> n.equalsIgnoreCase(player.getName()));
            if (!listed) continue;

            Inventory inv = player.getInventory();
            if (inv.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) continue;

            int slot = findTotem(inv);
            if (slot == -1) continue;

            ItemStack totem = inv.getItem(slot);
            ItemStack single = totem.clone();
            single.setAmount(1);
            totem.setAmount(totem.getAmount() - 1);
            inv.setItem(slot, totem.getAmount() > 0 ? totem : null);
            inv.setItemInOffHand(single);
        }
    }

    private int findTotem(Inventory inv) {
        for (int i = 0; i < inv.getStorageContents().length; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == Material.TOTEM_OF_UNDYING) return i;
        }
        return -1;
    }
}
