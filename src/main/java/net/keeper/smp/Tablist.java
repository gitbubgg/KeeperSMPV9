package net.keeper.smp;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Puts the rank on the player's name and sorts the tab list by it.
 *
 * Tab order is not directly settable. The client sorts players by the name of
 * the scoreboard team they belong to, alphabetically, so each rank gets a team
 * whose name starts with a digit: 0 for Owner down to 6 for Keeper. That digit
 * is what pushes higher ranks to the top.
 */
public class Tablist implements Listener {

    private final KeeperPlugin plugin;

    public Tablist(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    /** Team name for a rank. Lower number sorts higher in tab. */
    private String teamName(Rank rank) {
        int order = Rank.OWNER.weight - rank.weight;
        return order + "_" + rank.name().toLowerCase();
    }

    /** Creates the seven teams if they are not there yet. */
    public void setupTeams() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Rank rank : Rank.values()) {
            String name = teamName(rank);
            Team team = board.getTeam(name);
            if (team == null) {
                team = board.registerNewTeam(name);
            }
            team.prefix(Util.text(rank.display + " &r"));
            team.setAllowFriendlyFire(true);
            team.setCanSeeFriendlyInvisibles(false);
        }
    }

    /** Moves the player into the team for their current rank. */
    public void apply(Player player) {
        Rank rank = plugin.ranks().of(player);
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team target = board.getTeam(teamName(rank));
        if (target == null) {
            setupTeams();
            target = board.getTeam(teamName(rank));
        }
        if (target == null) return;

        // Leaving any old rank team first, or the player shows up twice.
        for (Rank other : Rank.values()) {
            if (other == rank) continue;
            Team team = board.getTeam(teamName(other));
            if (team != null && team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());
            }
        }
        if (!target.hasEntry(player.getName())) {
            target.addEntry(player.getName());
        }

        String shown = plugin.data().get(player.getUniqueId()).nickname;
        String name = rank.nickname && shown != null ? shown : player.getName();
        player.playerListName(Util.text(rank.display + " &r&f" + name));
    }

    public void applyAll() {
        for (Player player : Bukkit.getOnlinePlayers()) apply(player);
    }

    /** Rank tag in front of chat messages. */
    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Rank rank = plugin.ranks().of(player);
        String shown = plugin.data().get(player.getUniqueId()).nickname;
        String name = rank.nickname && shown != null ? shown : player.getName();

        event.renderer((source, displayName, message, viewer) ->
                Util.text(rank.display + " &r&f" + name + "&7: &f")
                        .append(message.colorIfAbsent(net.kyori.adventure.text.format
                                .NamedTextColor.WHITE)));
    }

    /** Header and footer on the tab screen. */
    public void decorate(Player player) {
        player.sendPlayerListHeader(Util.text("&b&lKeeperSMP"));
        player.sendPlayerListFooter(Util.text("&7Balance: &a"
                + plugin.econ().fmt(plugin.econ().balance(player.getUniqueId()))
                + "  &7Shards: &b" + Util.money(plugin.shards().balance(player.getUniqueId()))
                + "\n&8/menu for the server menu"));
    }

    /** Called on a timer so the footer numbers stay current. */
    public void refresh() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            decorate(player);
        }
    }

    public Component nameOf(Player player) {
        Rank rank = plugin.ranks().of(player);
        return Util.text(rank.display + " &r&f" + player.getName());
    }
}
