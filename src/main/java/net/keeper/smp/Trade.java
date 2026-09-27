package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /trade <player>: once accepted, both players get a shared item-trade GUI.
 * Each side can only touch their own half; once both mark ready the offers
 * swap atomically, and closing early (or disconnecting) safely hands
 * everything back.
 */
public class Trade implements Listener {

    private static final int READY_A = 45;
    private static final int CANCEL = 49;
    private static final int READY_B = 53;

    private static class Session {
        final Player a;
        final Player b;
        final Inventory inventory;
        boolean readyA = false;
        boolean readyB = false;
        boolean locked = false;

        Session(Player a, Player b, Inventory inventory) {
            this.a = a;
            this.b = b;
            this.inventory = inventory;
        }
    }

    private static class TradeHolder implements InventoryHolder {
        Session session;

        @Override
        public Inventory getInventory() {
            return session.inventory;
        }
    }

    private record Pending(UUID requester, long expiry) {
    }

    private final KeeperPlugin plugin;
    private final Map<UUID, Pending> requests = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();

    public Trade(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isASlot(int slot) {
        return slot < 45 && slot % 9 < 4;
    }

    private boolean isBSlot(int slot) {
        return slot < 45 && slot % 9 > 4;
    }

    // ---------------- request/accept ----------------

    public void request(Player sender, Player target) {
        if (target.equals(sender)) {
            sender.sendMessage(Util.text("&cYou cannot trade with yourself."));
            return;
        }
        if (sessions.containsKey(sender.getUniqueId()) || sessions.containsKey(target.getUniqueId())) {
            sender.sendMessage(Util.text("&cOne of you is already in a trade."));
            return;
        }
        requests.put(target.getUniqueId(), new Pending(sender.getUniqueId(), System.currentTimeMillis() + 60_000L));
        sender.sendMessage(Util.text("&aTrade request sent to &f" + target.getName() + "&a."));
        target.sendMessage(Util.text("&f" + sender.getName() + " &7wants to trade. &a/trade accept &7or &c/trade deny"));
    }

    public void accept(Player target) {
        Pending pending = requests.remove(target.getUniqueId());
        if (pending == null || System.currentTimeMillis() > pending.expiry()) {
            target.sendMessage(Util.text("&cNo pending trade request."));
            return;
        }
        Player requester = Bukkit.getPlayer(pending.requester());
        if (requester == null) {
            target.sendMessage(Util.text("&cThat player went offline."));
            return;
        }
        if (sessions.containsKey(requester.getUniqueId()) || sessions.containsKey(target.getUniqueId())) {
            target.sendMessage(Util.text("&cOne of you is already in a trade."));
            return;
        }
        open(requester, target);
    }

    public void deny(Player target) {
        Pending pending = requests.remove(target.getUniqueId());
        if (pending == null) {
            target.sendMessage(Util.text("&cNo pending trade request."));
            return;
        }
        target.sendMessage(Util.text("&cTrade denied."));
        Player requester = Bukkit.getPlayer(pending.requester());
        if (requester != null) requester.sendMessage(Util.text("&c" + target.getName() + " denied your trade."));
    }

    // ---------------- the trade itself ----------------

    private void open(Player a, Player b) {
        TradeHolder holder = new TradeHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Util.text("&8Trade: &f" + a.getName() + " &7/ &f" + b.getName()));
        Session session = new Session(a, b, inv);
        holder.session = session;
        sessions.put(a.getUniqueId(), session);
        sessions.put(b.getUniqueId(), session);

        for (int slot = 0; slot < 54; slot++) {
            if (isASlot(slot) || isBSlot(slot) || slot == READY_A || slot == READY_B || slot == CANCEL) continue;
            inv.setItem(slot, Util.item(Material.GRAY_STAINED_GLASS_PANE, 1, "&8"));
        }
        inv.setItem(CANCEL, Util.item(Material.BARRIER, 1, "&cCancel trade"));
        refresh(session);

        a.openInventory(inv);
        b.openInventory(inv);
    }

    private void refresh(Session session) {
        session.inventory.setItem(READY_A, Util.item(session.readyA ? Material.LIME_STAINED_GLASS_PANE
                        : Material.RED_STAINED_GLASS_PANE, 1,
                session.a.getName() + (session.readyA ? " &aready" : " &cnot ready")));
        session.inventory.setItem(READY_B, Util.item(session.readyB ? Material.LIME_STAINED_GLASS_PANE
                        : Material.RED_STAINED_GLASS_PANE, 1,
                session.b.getName() + (session.readyB ? " &aready" : " &cnot ready")));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeHolder)) return;
        for (int slot : event.getRawSlots()) {
            if (slot < 54) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = holder.session;
        if (session.locked) {
            event.setCancelled(true);
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) {
            if (event.isShiftClick()) event.setCancelled(true);
            return;
        }

        if (slot == CANCEL) {
            event.setCancelled(true);
            cancel(session, "&7Trade cancelled.");
            return;
        }
        if (slot == READY_A || slot == READY_B) {
            event.setCancelled(true);
            boolean isA = player.getUniqueId().equals(session.a.getUniqueId());
            boolean isB = player.getUniqueId().equals(session.b.getUniqueId());
            if (slot == READY_A && isA) session.readyA = !session.readyA;
            if (slot == READY_B && isB) session.readyB = !session.readyB;
            refresh(session);
            if (session.readyA && session.readyB) complete(session);
            return;
        }

        boolean ownsSlot = (isASlot(slot) && player.getUniqueId().equals(session.a.getUniqueId()))
                || (isBSlot(slot) && player.getUniqueId().equals(session.b.getUniqueId()));
        if (!ownsSlot) {
            event.setCancelled(true);
            return;
        }
        session.readyA = false;
        session.readyB = false;
        Bukkit.getScheduler().runTask(plugin, () -> refresh(session));
    }

    private void complete(Session session) {
        session.locked = true;
        ItemStack[] fromA = collect(session, true);
        ItemStack[] fromB = collect(session, false);

        give(session.b, fromA);
        give(session.a, fromB);

        session.a.sendMessage(Util.text("&aTrade complete."));
        session.b.sendMessage(Util.text("&aTrade complete."));
        end(session, true);
    }

    private ItemStack[] collect(Session session, boolean sideA) {
        java.util.List<ItemStack> items = new java.util.ArrayList<>();
        for (int slot = 0; slot < 45; slot++) {
            boolean matches = sideA ? isASlot(slot) : isBSlot(slot);
            if (!matches) continue;
            ItemStack item = session.inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) items.add(item);
            session.inventory.setItem(slot, null);
        }
        return items.toArray(new ItemStack[0]);
    }

    private void give(Player player, ItemStack[] items) {
        for (ItemStack item : items) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    private void cancel(Session session, String message) {
        if (session.locked) return;
        session.locked = true;
        give(session.a, collect(session, true));
        give(session.b, collect(session, false));
        session.a.sendMessage(Util.text(message));
        session.b.sendMessage(Util.text(message));
        end(session, true);
    }

    private void end(Session session, boolean closeInventories) {
        sessions.remove(session.a.getUniqueId());
        sessions.remove(session.b.getUniqueId());
        if (closeInventories) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                session.a.closeInventory();
                session.b.closeInventory();
            });
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeHolder holder)) return;
        cancel(holder.session, "&7Trade cancelled, the other player closed the window.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        requests.remove(event.getPlayer().getUniqueId());
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) cancel(session, "&7Trade cancelled, a player disconnected.");
    }
}
