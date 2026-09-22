package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;

/**
 * An always-on personal stats panel: username, rank, balance, shards and
 * playtime. Each player needs their own Scoreboard for the numbers to
 * differ per viewer, so Tablist's rank teams are mirrored onto it, keeping
 * tab-list sorting working for players who now have one.
 */
public class Sidebar implements Listener {

    private static final String OBJECTIVE = "keeper_side";

    private final KeeperPlugin plugin;

    public Sidebar(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    private Scoreboard boardFor(Player player) {
        Scoreboard board = player.getScoreboard();
        if (board == null || board.equals(Bukkit.getScoreboardManager().getMainScoreboard())) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(board);
        }
        return board;
    }

    public void show(Player player) {
        if (!plugin.getConfig().getBoolean("sidebar.enabled", true)) return;
        Scoreboard board = boardFor(player);
        plugin.tablist().mirrorMembership(board);

        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Util.text("&b&lKeeperSMP"));
        }
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        update(player, board, objective);
    }

    /** Called on a timer so the numbers and tab-sort membership stay current. */
    public void refresh() {
        if (!plugin.getConfig().getBoolean("sidebar.enabled", true)) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Scoreboard board = player.getScoreboard();
            Objective objective = board == null ? null : board.getObjective(OBJECTIVE);
            if (objective == null) {
                show(player);
                continue;
            }
            plugin.tablist().mirrorMembership(board);
            update(player, board, objective);
        }
    }

    private void update(Player player, Scoreboard board, Objective objective) {
        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        Rank rank = plugin.ranks().of(player);

        List<String> lines = Util.lines(
                "&f" + player.getName(),
                rank.display,
                "&8&m----------------",
                "&7Balance: &a" + plugin.econ().fmt(data.balance),
                "&7Shards: &b" + Util.money(data.shards),
                "&7Playtime: &d" + plugin.playtime().formatted(player.getUniqueId())
        );

        int score = lines.size();
        for (String line : lines) {
            String teamName = "side_" + score;
            Team team = board.getTeam(teamName);
            if (team == null) team = board.registerNewTeam(teamName);
            team.prefix(Util.text(line));
            String entry = invisibleEntry(score);
            if (!team.hasEntry(entry)) team.addEntry(entry);
            objective.getScore(entry).setScore(score);
            score--;
        }
    }

    /** A distinct, invisible fake scoreboard entry for the line at this slot. */
    private String invisibleEntry(int slot) {
        return ChatColor.RESET.toString().repeat(Math.max(1, slot));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Runs a tick late so it lands after Tablist has set up its teams.
        Bukkit.getScheduler().runTask(plugin, () -> show(event.getPlayer()));
    }
}
