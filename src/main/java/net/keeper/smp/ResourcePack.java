package net.keeper.smp;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Pushes the server's resource pack (currently the Sift reskins) to
 * players on join. Rebuild resourcepack/keepersmp-sift.zip with
 * resourcepack/build.sh and update resourcepack.url/sha1 in config.yml
 * whenever the pack's source files change.
 *
 * <p>Reads straight from the jar's bundled config.yml rather than
 * plugin.getConfig(), since Bukkit's saveDefaultConfig() never
 * overwrites a config.yml that already exists on disk - a server with
 * an older on-disk config would otherwise keep serving a stale
 * url/sha1 forever regardless of what ships in a new jar.</p>
 */
public class ResourcePack implements Listener {

    private final KeeperPlugin plugin;

    public ResourcePack(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String url;
        String sha1;
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return;
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            url = bundled.getString("resourcepack.url", "");
            sha1 = bundled.getString("resourcepack.sha1", "");
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read bundled resourcepack config: " + e.getMessage());
            return;
        }
        if (url.isBlank() || sha1.isBlank()) return;
        event.getPlayer().setResourcePack(url, sha1);
    }
}
