package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class Commands implements CommandExecutor, TabCompleter {

    private final KeeperPlugin plugin;

    public Commands(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean deny(CommandSender sender, Rank needed) {
        sender.sendMessage(Util.text("&cThat command needs " + needed.display + "&c or higher."));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        // console only commands first
        if (name.equals("keeper")) {
            return keeper(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("That command has to be run by a player.");
            return true;
        }
        Rank rank = plugin.ranks().of(player);
        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        if (!name.equals("afk")) plugin.shards().clear(player, true);

        switch (name) {
            case "menu" -> plugin.gui().openMenu(player);
            case "shop" -> plugin.gui().openShop(player, 0);
            case "settings" -> plugin.gui().openSettings(player);
            case "rank" -> plugin.gui().openRanks(player);

            case "sell" -> {
                if (args.length > 0 && args[0].equalsIgnoreCase("all")) {
                    plugin.econ().sellAll(player);
                } else {
                    plugin.econ().sellHand(player);
                }
            }

            case "autosell" -> {
                if (!rank.autosell) return deny(sender, Rank.KEEPER_PLUS);
                data.autoSell = !data.autoSell;
                plugin.data().save(player.getUniqueId());
                player.sendMessage(Util.text("&7Auto sell is now "
                        + (data.autoSell ? "&aON" : "&cOFF") + "&7."));
            }

            case "lobby" -> {
                if (plugin.teleport().blockedByCombat(player)) return true;
                plugin.teleport().withWarmup(player, plugin.lobby(player.getWorld()), "the lobby");
            }

            case "setlobby" -> {
                if (!rank.isStaff()) return deny(sender, Rank.MOD);
                org.bukkit.Location where = player.getLocation();
                plugin.setLobby(where);
                player.sendMessage(Util.text("&8&m----------------------------"));
                player.sendMessage(Util.text(" &aLobby set to your exact position."));
                player.sendMessage(Util.text(" &7" + String.format("%.1f, %.1f, %.1f",
                        where.getX(), where.getY(), where.getZ())
                        + " &8in " + where.getWorld().getName()));
                player.sendMessage(Util.text(" &7Facing is saved too, so &f/lobby &7puts"));
                player.sendMessage(Util.text(" &7people down looking the way you are now."));
                player.sendMessage(Util.text(" &7World spawn moved here as well, so respawns"));
                player.sendMessage(Util.text(" &7and the protection circle follow it."));
                player.sendMessage(Util.text("&8&m----------------------------"));
            }

            case "value" -> {
                org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    player.sendMessage(Util.text("&cHold an item to price it."));
                    return true;
                }
                plugin.econ().quote(player, hand);
            }

            case "balance" -> {
                player.sendMessage(Util.text("&7Balance: &a" + plugin.econ().fmt(data.balance)));
                player.sendMessage(Util.text("&7Shards: &b" + Util.money(data.shards)
                        + " &8(earn them with /afk)"));
            }

            case "pay" -> pay(player, args);

            case "auction" -> auction(player, args);

            case "sethome" -> plugin.homes().set(player, args.length > 0 ? args[0] : "home");
            case "home" -> plugin.homes().go(player, args.length > 0 ? args[0] : null);
            case "delhome" -> plugin.homes().delete(player, args.length > 0 ? args[0] : null);
            case "homes" -> plugin.homes().list(player);

            case "rtp" -> plugin.teleport().rtp(player, args);
            case "rtpq" -> plugin.teleport().rtpQueue(player);
            case "back" -> plugin.teleport().back(player);

            case "tpa" -> {
                Player target = target(player, args);
                if (target != null) plugin.teleport().request(player, target, Teleport.Kind.TPA);
            }
            case "tpahere" -> {
                Player target = target(player, args);
                if (target != null) plugin.teleport().request(player, target, Teleport.Kind.TPAHERE);
            }
            case "tpaccept" -> plugin.teleport().accept(player);
            case "tpdeny" -> plugin.teleport().deny(player);

            case "ec" -> {
                if (!rank.enderchest) return deny(sender, Rank.KEEPER_PLUS2);
                player.openInventory(player.getEnderChest());
            }
            case "craft" -> {
                if (!rank.craftingtable) return deny(sender, Rank.KEEPER_PLUS2);
                player.openWorkbench(null, true);
            }
            case "nick" -> {
                if (!rank.nickname) return deny(sender, Rank.KEEPER_PLUS);
                if (args.length == 0) {
                    data.nickname = null;
                    player.displayName(null);
                    player.sendMessage(Util.text("&7Nickname cleared."));
                } else {
                    String nick = String.join(" ", args);
                    if (Util.strip(nick).length() > 16) {
                        player.sendMessage(Util.text("&cKeep it to 16 characters or fewer."));
                        return true;
                    }
                    data.nickname = nick;
                    player.displayName(Util.text(nick));
                    player.sendMessage(Util.text("&7Nickname set to &r" + nick));
                }
                plugin.data().save(player.getUniqueId());
                plugin.tablist().apply(player);
            }

            // ---------------- staff ----------------
            case "fly" -> {
                if (!rank.isStaff()) return deny(sender, Rank.MOD);
                boolean on = !player.getAllowFlight();
                player.setAllowFlight(on);
                player.setFlying(on);
                player.sendMessage(Util.text("&7Flight " + (on ? "&aON" : "&cOFF")));
            }
            case "gmc" -> {
                if (!rank.isStaff()) return deny(sender, Rank.MOD);
                boolean creative = player.getGameMode() == GameMode.CREATIVE;
                player.setGameMode(creative ? GameMode.SURVIVAL : GameMode.CREATIVE);
                player.sendMessage(Util.text("&7Game mode: &f"
                        + player.getGameMode().name().toLowerCase(Locale.ROOT)));
            }
            case "forcetp" -> {
                if (!rank.isStaff()) return deny(sender, Rank.MOD);
                Player target = target(player, args);
                if (target == null) return true;
                player.teleportAsync(target.getLocation());
                player.sendMessage(Util.text("&7Teleported to &f" + target.getName() + "&7."));
            }
            case "afk" -> plugin.shards().toggle(player);

            case "shardshop" -> plugin.shards().openShop(player, 0);

            case "spectator" -> {
                if (!rank.isStaff()) return deny(sender, Rank.MOD);
                boolean spec = player.getGameMode() == GameMode.SPECTATOR;
                player.setGameMode(spec ? GameMode.SURVIVAL : GameMode.SPECTATOR);
                player.sendMessage(Util.text("&7Game mode: &f"
                        + player.getGameMode().name().toLowerCase(Locale.ROOT)));
            }

            case "spawnstash" -> {
                if (!rank.isStaff()) return deny(sender, Rank.MOD);
                plugin.stash().create(player);
            }

            case "usekey" -> plugin.crates().useNearest(player, 6);

            case "clearcrates" -> {
                if (!rank.isStaff()) return deny(sender, Rank.MOD);
                int cleared = plugin.crates().clearAll();
                player.sendMessage(Util.text("&aCleared &f" + cleared
                        + " &aregistered crate(s)."));
                player.sendMessage(Util.text("&7Re-register the ones you want with &f/setcrate&7."));
            }

            case "setcrate" -> {
                if (!rank.isStaff()) return deny(sender, Rank.MOD);
                plugin.crates().addCrate(player, player.getTargetBlockExact(6),
                        args.length > 0 ? args[0] : null);
            }

            case "delcrate" -> {
                if (!rank.isStaff()) return deny(sender, Rank.MOD);
                plugin.crates().removeCrate(player, player.getTargetBlockExact(6));
            }

            case "givekey" -> {
                if (!rank.isStaff()) return deny(sender, Rank.MOD);
                if (args.length < 2) {
                    player.sendMessage(Util.text("&cUsage: /givekey <player> <tier> [amount]"));
                    player.sendMessage(Util.text("&7Tiers: &f"
                            + String.join(", ", plugin.crates().tiers().keySet())));
                    return true;
                }
                Player target = target(player, args);
                if (target == null) return true;
                int amount = 1;
                if (args.length > 2) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
                    } catch (NumberFormatException ignored) {
                    }
                }
                org.bukkit.inventory.ItemStack key = plugin.crates().createKey(args[1], amount);
                if (key == null) {
                    player.sendMessage(Util.text("&cNo crate tier called &f" + args[1] + "&c."));
                    return true;
                }
                target.getInventory().addItem(key);
                player.sendMessage(Util.text("&aGave &f" + amount + " &akey(s) to &f"
                        + target.getName() + "&a."));
                target.sendMessage(Util.text("&aYou received &f" + amount + " &acrate key(s)."));
            }

            case "spawnfence" -> {
                if (!rank.atLeast(Rank.OWNER)) return deny(sender, Rank.OWNER);
                plugin.spawnBuilder().fenceExistingSpawn(player,
                        args.length > 0 && args[0].equalsIgnoreCase("full"));
            }

            case "buildspawn" -> {
                if (!rank.atLeast(Rank.OWNER)) return deny(sender, Rank.OWNER);
                if (args.length > 0 && args[0].equalsIgnoreCase("confirm")) {
                    plugin.spawnBuilder().confirm(player);
                } else {
                    plugin.spawnBuilder().request(player);
                }
            }

            case "grantrank" -> grantRank(sender, args);
            case "eco" -> eco(sender, args);

            default -> {
                return false;
            }
        }
        return true;
    }

    private Player target(Player sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Util.text("&cName a player."));
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            // Bedrock names carry a prefix, so fall back to a loose match.
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().equalsIgnoreCase(args[0])
                        || online.getName().replace(".", "").equalsIgnoreCase(args[0].replace(".", ""))) {
                    target = online;
                    break;
                }
            }
        }
        if (target == null) {
            sender.sendMessage(Util.text("&c" + args[0] + " is not online."));
            return null;
        }
        return target;
    }

    private void pay(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Util.text("&cUsage: /pay <player> <amount>"));
            return;
        }
        Player target = target(player, args);
        if (target == null) return;
        if (target.equals(player)) {
            player.sendMessage(Util.text("&cYou cannot pay yourself."));
            return;
        }
        double amount = Util.parseAmount(args[1]);
        if (amount < 0) {
            player.sendMessage(Util.text("&cThat is not a number. Try 500, 20k or 1.5m."));
            return;
        }
        if (amount <= 0) {
            player.sendMessage(Util.text("&cAmount has to be above 0."));
            return;
        }
        if (!plugin.econ().withdraw(player.getUniqueId(), amount)) {
            player.sendMessage(Util.text("&cYou do not have that much."));
            return;
        }
        plugin.econ().deposit(target.getUniqueId(), amount);
        player.sendMessage(Util.text("&aSent &f" + plugin.econ().fmt(amount)
                + " &ato &f" + target.getName() + "&a."));
        target.sendMessage(Util.text("&aReceived &f" + plugin.econ().fmt(amount)
                + " &afrom &f" + player.getName() + "&a."));
    }

    private void auction(Player player, String[] args) {
        if (args.length == 0) {
            plugin.gui().openAuction(player, 0, false);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell", "list" -> {
                if (args.length < 2) {
                    player.sendMessage(Util.text("&cUsage: /ah sell <price>"));
                    return;
                }
                double price = Util.parseAmount(args[1]);
                if (price < 0) {
                    player.sendMessage(Util.text("&cThat is not a price. Try 2500, 200k, 5m or 5b."));
                    return;
                }
                plugin.auction().start(player, price);
            }
            case "confirm", "yes" -> plugin.auction().confirm(player);
            case "cancel", "no" -> plugin.auction().cancelPending(player);
            case "mine" -> plugin.gui().openAuction(player, 0, true);
            case "help" -> {
                player.sendMessage(Util.text("&7/ah &8- browse listings"));
                player.sendMessage(Util.text("&7/ah sell <price> &8- list the item in your main hand"));
                player.sendMessage(Util.text("&7/ah confirm &8- confirm that listing"));
                player.sendMessage(Util.text("&7/ah mine &8- your own listings"));
            }
            default -> plugin.gui().openAuction(player, 0, false);
        }
    }

    private boolean grantRank(CommandSender sender, String[] args) {
        boolean allowed = !(sender instanceof Player p) || plugin.ranks().of(p).isStaff();
        if (!allowed) return deny(sender, Rank.MOD);
        if (args.length < 2) {
            sender.sendMessage(Util.text("&cUsage: /grantrank <player> <rank> [days]"));
            sender.sendMessage(Util.text("&7Ranks: KEEPER, KEEPER_PLUS, KEEPER_PLUS2, KEEPER_PLUS3"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        UUID uuid;
        String shown = args[0];
        if (target != null) {
            uuid = target.getUniqueId();
            shown = target.getName();
        } else {
            Data.PlayerData cached = plugin.data().byName(args[0]);
            if (cached == null) {
                sender.sendMessage(Util.text("&cThat player has to be online, or have joined before."));
                return true;
            }
            uuid = cached.uuid;
        }
        Rank rank = Rank.parse(args[1], null);
        if (rank == null || rank.isStaff()) {
            sender.sendMessage(Util.text("&cPick one of KEEPER, KEEPER_PLUS, KEEPER_PLUS2, KEEPER_PLUS3."));
            sender.sendMessage(Util.text("&7Staff ranks come from the staff list in config.yml."));
            return true;
        }
        int days = 30;
        if (args.length > 2) {
            try {
                days = Integer.parseInt(args[2]);
            } catch (NumberFormatException ignored) {
            }
        }
        long expiry = rank == Rank.KEEPER ? 0 : System.currentTimeMillis() + days * 86_400_000L;
        plugin.ranks().grant(uuid, rank, expiry, null);
        sender.sendMessage(Util.text("&aSet &f" + shown + " &ato " + rank.display
                + (rank == Rank.KEEPER ? "" : " &afor &f" + days + " &adays") + "&a."));
        return true;
    }

    private boolean eco(CommandSender sender, String[] args) {
        boolean allowed = !(sender instanceof Player p) || plugin.ranks().of(p).isStaff();
        if (!allowed) return deny(sender, Rank.MOD);
        if (args.length < 3) {
            sender.sendMessage(Util.text("&cUsage: /eco <give|take|set> <player> <amount>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        UUID uuid;
        if (target != null) {
            uuid = target.getUniqueId();
        } else {
            Data.PlayerData cached = plugin.data().byName(args[1]);
            if (cached == null) {
                sender.sendMessage(Util.text("&cUnknown player."));
                return true;
            }
            uuid = cached.uuid;
        }
        double amount = Util.parseAmount(args[2]);
        if (amount < 0) {
            sender.sendMessage(Util.text("&cThat is not a number. Try 5000, 250k or 2m."));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> plugin.econ().deposit(uuid, amount);
            case "take" -> plugin.econ().withdraw(uuid, amount);
            case "set" -> plugin.econ().set(uuid, amount);
            default -> {
                sender.sendMessage(Util.text("&cUse give, take or set."));
                return true;
            }
        }
        plugin.data().save(uuid);
        sender.sendMessage(Util.text("&aDone. New balance: &f"
                + plugin.econ().fmt(plugin.econ().balance(uuid))));
        return true;
    }

    private boolean keeper(CommandSender sender, String[] args) {
        boolean allowed = !(sender instanceof Player p) || plugin.ranks().of(p).isStaff();
        if (!allowed) return deny(sender, Rank.MOD);
        if (args.length == 0) {
            sender.sendMessage(Util.text("&7/keeper reload &8- reload config"));
            sender.sendMessage(Util.text("&7/keeper stripe &8- check the Stripe link"));
            sender.sendMessage(Util.text("&7/keeper status &8- protection, spawn and crate info"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadEverything();
                sender.sendMessage(Util.text("&aConfig, prices and ranks reloaded."));
            }
            case "stripe" -> sender.sendMessage(Util.text("&7Stripe: &f" + plugin.stripe().status()));
            case "status" -> {
                boolean on = plugin.getConfig().getBoolean("protection.enabled", true);
                int radius = plugin.getConfig().getInt("protection.radius", 45);
                boolean bypass = plugin.getConfig().getBoolean("protection.staff-bypass", true);
                sender.sendMessage(Util.text("&8&m----------------------------"));
                sender.sendMessage(Util.text(" &fBuild: &a" + KeeperPlugin.BUILD));
                sender.sendMessage(Util.text(" &fProtection: " + (on ? "&aon" : "&coff")
                        + " &7radius &f" + radius));
                sender.sendMessage(Util.text(" &7Staff bypass: &f" + bypass));
                sender.sendMessage(Util.text(" &7Crates registered: &f"
                        + plugin.crates().crateCount()));
                sender.sendMessage(Util.text(" &7Stashes: &f" + plugin.stash().count()
                        + "  &7TNT spawners: &f" + plugin.spawners().count()));
                if (sender instanceof Player p) {
                    org.bukkit.Location spawn = p.getWorld().getSpawnLocation();
                    sender.sendMessage(Util.text(" &7World spawn: &f" + spawn.getBlockX() + ", "
                            + spawn.getBlockY() + ", " + spawn.getBlockZ()
                            + " &8(" + p.getWorld().getName() + ")"));
                    double dx = p.getLocation().getX() - spawn.getX();
                    double dz = p.getLocation().getZ() - spawn.getZ();
                    int distance = (int) Math.sqrt(dx * dx + dz * dz);
                    org.bukkit.Location lobby = plugin.lobby(p.getWorld());
                    sender.sendMessage(Util.text(" &7Lobby point: &f"
                            + String.format("%.1f, %.1f, %.1f", lobby.getX(), lobby.getY(),
                            lobby.getZ())
                            + (plugin.getConfig().getString("lobby.location") == null
                            ? " &8(defaulting to world spawn)" : "")));
                    sender.sendMessage(Util.text(" &7You are &f" + distance
                            + " &7blocks from it, inside zone: &f"
                            + plugin.protect().protectedArea(p.getLocation())));
                    sender.sendMessage(Util.text(" &7Your rank: " + plugin.ranks().of(p).display
                            + " &8(staff: " + plugin.ranks().of(p).isStaff() + ")"));
                }
                sender.sendMessage(Util.text("&8&m----------------------------"));
            }
            default -> sender.sendMessage(Util.text("&cUnknown option."));
        }
        return true;
    }

    // ---------------- tab completion ----------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();

        if (args.length == 1) {
            switch (name) {
                case "home", "delhome" -> {
                    if (sender instanceof Player player) {
                        out.addAll(plugin.data().get(player.getUniqueId()).homes.keySet());
                    }
                }
                case "auction" -> out.addAll(List.of("sell", "confirm", "cancel", "mine", "help"));
                case "rtp" -> out.addAll(List.of("nether", "end"));
                case "eco" -> out.addAll(List.of("give", "take", "set"));
                case "keeper" -> out.addAll(List.of("reload", "stripe", "status"));
                case "buildspawn" -> out.add("confirm");
                case "spawnfence" -> out.add("full");
                case "setcrate" -> {
                    out.addAll(plugin.crates().tiers().keySet());
                    out.add("ANY");
                }
                case "givekey" -> {
                    for (Player online : Bukkit.getOnlinePlayers()) out.add(online.getName());
                }
                case "sell" -> out.add("all");
                case "tpa", "tpahere", "forcetp", "pay", "grantrank" -> {
                    for (Player online : Bukkit.getOnlinePlayers()) out.add(online.getName());
                }
                default -> {
                }
            }
        } else if (args.length == 2 && name.equals("grantrank")) {
            out.addAll(List.of("KEEPER", "KEEPER_PLUS", "KEEPER_PLUS2", "KEEPER_PLUS3"));
        } else if (args.length == 2 && name.equals("givekey")) {
            out.addAll(plugin.crates().tiers().keySet());
        } else if (args.length == 2 && name.equals("eco")) {
            for (Player online : Bukkit.getOnlinePlayers()) out.add(online.getName());
        }

        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        Collections.sort(out);
        return out;
    }
}
