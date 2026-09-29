package net.keeper.smp;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Pushes the server's resource pack (currently the Sift reskins) to
 * players on join. Rebuild resourcepack/keepersmp-sift.zip with
 * resourcepack/build.sh and update resourcepack.url/sha1 in config.yml
 * whenever the pack's source files change.
 */
public class ResourcePack implements Listener {

    private final KeeperPlugin plugin;

    public ResourcePack(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String url = plugin.getConfig().getString("resourcepack.url", "");
        String sha1 = plugin.getConfig().getString("resourcepack.sha1", "");
        if (url.isBlank() || sha1.isBlank()) return;
        event.getPlayer().setResourcePack(url, sha1);
    }
}
