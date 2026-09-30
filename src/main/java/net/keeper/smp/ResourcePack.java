package net.keeper.smp;

import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Pushes the Sift reskin pack to a player only while they're actually
 * standing in the Sift dimension, and strips it the instant they leave.
 * Rebuild resourcepack/keepersmp-sift.zip with resourcepack/build.sh and
 * update resourcepack.url/sha1 in config.yml whenever the pack's source
 * files change.
 *
 * <p>Every reskin in this pack (including the Warden boss retexture) is a
 * global override - the client has no concept of "only in this dimension".
 * Scoping the pack itself to dimension entry/exit is what actually keeps
 * these reskins from showing up on a real Warden, cherry tree, or mushroom
 * field anywhere else on the server: a player outside the Sift simply never
 * has the pack loaded at all.</p>
 *
 * <p>Reads url/sha1 straight from the jar's bundled config.yml rather than
 * plugin.getConfig(), since Bukkit's saveDefaultConfig() never overwrites a
 * config.yml that already exists on disk - a server with an older on-disk
 * config would otherwise keep serving a stale url/sha1 forever regardless
 * of what ships in a new jar.</p>
 */
public class ResourcePack implements Listener {

    private static final UUID PACK_ID = UUID.fromString("5171f5b6-3b9a-4b0a-9c1f-6a1e6c9b2d21");

    private final KeeperPlugin plugin;

    public ResourcePack(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    private String siftWorldName() {
        return plugin.getConfig().getString("general.rtp-sift-world", "sift");
    }

    private boolean inSift(World world) {
        return world != null && world.getName().equals(siftWorldName());
    }

    private void apply(Player player) {
        String url;
        String sha1Hex;
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return;
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            url = bundled.getString("resourcepack.url", "");
            sha1Hex = bundled.getString("resourcepack.sha1", "");
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read bundled resourcepack config: " + e.getMessage());
            return;
        }
        if (url.isBlank() || sha1Hex.isBlank()) return;
        byte[] hash = hexToBytes(sha1Hex);
        player.addResourcePack(PACK_ID, url, hash,
                "The Sift has its own look.", false);
    }

    private void remove(Player player) {
        player.removeResourcePack(PACK_ID);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (inSift(event.getPlayer().getWorld())) {
            apply(event.getPlayer());
        }
    }

    @EventHandler
    public void onChangeWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (inSift(player.getWorld())) {
            apply(player);
        } else if (inSift(event.getFrom())) {
            remove(player);
        }
    }
}
