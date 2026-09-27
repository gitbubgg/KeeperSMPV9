package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Cosmetic-only pets (a following mob, no AI/combat) and walking particle trails. */
public class Cosmetics implements Listener {

    private final KeeperPlugin plugin;
    private final Map<UUID, Entity> pets = new HashMap<>();

    public Cosmetics(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    public void equipPet(Player player, String type) {
        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        if (type == null) {
            data.activePet = null;
            despawn(player);
            player.sendMessage(Util.text("&7Pet unequipped."));
        } else if (data.ownedPets.contains(type)) {
            data.activePet = type;
            spawnPet(player, type);
            player.sendMessage(Util.text("&aPet equipped."));
        } else {
            player.sendMessage(Util.text("&cYou do not own that pet."));
            return;
        }
        plugin.data().save(player.getUniqueId());
    }

    public void equipTrail(Player player, String particle) {
        Data.PlayerData data = plugin.data().get(player.getUniqueId());
        if (particle == null) {
            data.activeTrail = null;
            player.sendMessage(Util.text("&7Trail unequipped."));
        } else if (data.ownedTrails.contains(particle)) {
            data.activeTrail = particle;
            player.sendMessage(Util.text("&aTrail equipped."));
        } else {
            player.sendMessage(Util.text("&cYou do not own that trail."));
            return;
        }
        plugin.data().save(player.getUniqueId());
    }

    private void spawnPet(Player player, String typeName) {
        despawn(player);
        EntityType type;
        try {
            type = EntityType.valueOf(typeName);
        } catch (IllegalArgumentException ex) {
            return;
        }
        Entity entity = player.getWorld().spawnEntity(player.getLocation(), type);
        entity.customName(Util.text("&d" + player.getName() + "'s pet"));
        entity.setCustomNameVisible(true);
        entity.setPersistent(false);
        if (entity instanceof Mob mob) {
            mob.setAI(false);
            mob.setSilent(true);
            mob.setInvulnerable(true);
        }
        pets.put(player.getUniqueId(), entity);
    }

    private void despawn(Player player) {
        Entity entity = pets.remove(player.getUniqueId());
        if (entity != null) entity.remove();
    }

    /** Runs periodically: keeps pets following their owner and puffs trail particles. */
    public void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Data.PlayerData data = plugin.data().getIfLoaded(player.getUniqueId());
            if (data == null) continue;

            if (data.activeTrail != null) {
                try {
                    Particle particle = Particle.valueOf(data.activeTrail);
                    player.getWorld().spawnParticle(particle,
                            player.getLocation().add(0, 0.1, 0), 3, 0.2, 0.1, 0.2, 0);
                } catch (IllegalArgumentException ignored) {
                }
            }

            Entity pet = pets.get(player.getUniqueId());
            if (pet == null || !pet.isValid()) {
                if (data.activePet != null) spawnPet(player, data.activePet);
                continue;
            }
            if (!pet.getWorld().equals(player.getWorld())) {
                pet.teleport(player.getLocation());
                continue;
            }
            Location loc = player.getLocation();
            Vector direction = loc.getDirection().setY(0);
            if (direction.lengthSquared() < 1.0E-4) direction = new Vector(0, 0, 1);
            direction.normalize();
            Location target = loc.clone().subtract(direction.multiply(2));
            target.setY(loc.getY());
            pet.teleport(target);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Data.PlayerData data = plugin.data().get(event.getPlayer().getUniqueId());
        if (data.activePet != null) spawnPet(event.getPlayer(), data.activePet);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        despawn(event.getPlayer());
    }
}
