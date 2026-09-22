package net.keeper.smp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Generates a large spawn plaza in place. Everything is queued first and then
 * placed a few thousand blocks per tick, because setting a hundred thousand
 * blocks in one tick would hang the server.
 *
 * This flattens whatever is already there inside its footprint and cannot be
 * undone, which is why it asks for confirmation.
 */
public class SpawnBuilder {

    private record Place(int x, int y, int z, Material material) {
    }

    private static final int PLATFORM_RADIUS = 34;
    private static final int CLEAR_RADIUS = 38;
    private static final int CLEAR_HEIGHT = 30;
    private static final int PER_TICK = 3000;

    private final KeeperPlugin plugin;
    private final Map<UUID, Long> pending = new HashMap<>();
    private boolean running = false;

    public SpawnBuilder(KeeperPlugin plugin) {
        this.plugin = plugin;
    }

    public void request(Player player) {
        if (running) {
            player.sendMessage(Util.text("&cA spawn build is already running."));
            return;
        }
        pending.put(player.getUniqueId(), System.currentTimeMillis() + 60_000L);
        player.sendMessage(Util.text("&8&m--------------------------------"));
        player.sendMessage(Util.text(" &c&lThis flattens everything nearby."));
        player.sendMessage(Util.text(" &7A circle &f" + (CLEAR_RADIUS * 2) + " blocks wide &7and &f"
                + CLEAR_HEIGHT + " tall &7centred on you"));
        player.sendMessage(Util.text(" &7will be cleared and rebuilt. There is no undo."));
        player.sendMessage(Util.text(" &7World spawn moves here too."));
        player.sendMessage(Util.text(" &7An invisible fence rings the edge, open at the four gates."));
        player.sendMessage(Util.text(" &aRun /buildspawn confirm &7within 60s to go ahead."));
        player.sendMessage(Util.text("&8&m--------------------------------"));
    }

    public void confirm(Player player) {
        Long expiry = pending.remove(player.getUniqueId());
        if (expiry == null || System.currentTimeMillis() > expiry) {
            player.sendMessage(Util.text("&cNothing to confirm. Run &f/buildspawn &cfirst."));
            return;
        }
        if (running) {
            player.sendMessage(Util.text("&cA spawn build is already running."));
            return;
        }
        build(player, player.getLocation().getBlock().getLocation());
    }

    private void build(Player player, Location centre) {
        World world = centre.getWorld();
        if (world == null) return;
        int cx = centre.getBlockX();
        int cy = centre.getBlockY();
        int cz = centre.getBlockZ();

        Deque<Place> queue = new ArrayDeque<>();

        // 1. Clear the airspace and the ground the plaza will sit on.
        for (int x = -CLEAR_RADIUS; x <= CLEAR_RADIUS; x++) {
            for (int z = -CLEAR_RADIUS; z <= CLEAR_RADIUS; z++) {
                if (x * x + z * z > CLEAR_RADIUS * CLEAR_RADIUS) continue;
                for (int y = 0; y <= CLEAR_HEIGHT; y++) {
                    queue.add(new Place(cx + x, cy + y, cz + z, Material.AIR));
                }
            }
        }

        // 2. Foundation slab, three layers deep so nothing shows through.
        for (int x = -PLATFORM_RADIUS - 1; x <= PLATFORM_RADIUS + 1; x++) {
            for (int z = -PLATFORM_RADIUS - 1; z <= PLATFORM_RADIUS + 1; z++) {
                int d2 = x * x + z * z;
                if (d2 > (PLATFORM_RADIUS + 1) * (PLATFORM_RADIUS + 1)) continue;
                for (int y = -3; y <= -1; y++) {
                    queue.add(new Place(cx + x, cy + y, cz + z, Material.DEEPSLATE_BRICKS));
                }
            }
        }

        // 3. The floor, as concentric bands with an inlaid compass rose.
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                int d2 = x * x + z * z;
                if (d2 > PLATFORM_RADIUS * PLATFORM_RADIUS) continue;
                double d = Math.sqrt(d2);
                Material floor;
                if (d > PLATFORM_RADIUS - 2) {
                    floor = Material.POLISHED_DEEPSLATE;
                } else if (d > PLATFORM_RADIUS - 3) {
                    floor = Material.POLISHED_BLACKSTONE_BRICKS;
                } else if (d > 26) {
                    floor = ((x + z) % 6 == 0) ? Material.SMOOTH_QUARTZ : Material.POLISHED_BLACKSTONE;
                } else if (d > 18) {
                    floor = Material.POLISHED_BLACKSTONE;
                } else if (d > 17) {
                    floor = Material.SMOOTH_QUARTZ;
                } else if (d > 11) {
                    // Radial spokes pointing at the four gateways.
                    boolean spoke = Math.abs(x) < 2 || Math.abs(z) < 2
                            || Math.abs(Math.abs(x) - Math.abs(z)) < 2;
                    floor = spoke ? Material.SMOOTH_QUARTZ : Material.BLACKSTONE;
                } else {
                    floor = Material.SMOOTH_QUARTZ;
                }
                queue.add(new Place(cx + x, cy - 1, cz + z, floor));

                // Sea lanterns set into the floor for ambient light.
                if (d > 12 && d < PLATFORM_RADIUS - 4 && (x % 9 == 0) && (z % 9 == 0)) {
                    queue.add(new Place(cx + x, cy - 1, cz + z, Material.SEA_LANTERN));
                }
            }
        }

        // 4. Perimeter wall with a lantern every few blocks.
        ring(queue, cx, cy, cz, PLATFORM_RADIUS, 0, Material.POLISHED_BLACKSTONE_BRICKS);
        ring(queue, cx, cy, cz, PLATFORM_RADIUS, 1, Material.POLISHED_BLACKSTONE_BRICKS);
        ring(queue, cx, cy, cz, PLATFORM_RADIUS, 2, Material.POLISHED_BLACKSTONE_BRICK_WALL);
        for (int deg = 0; deg < 360; deg += 15) {
            int x = (int) Math.round(Math.cos(Math.toRadians(deg)) * PLATFORM_RADIUS);
            int z = (int) Math.round(Math.sin(Math.toRadians(deg)) * PLATFORM_RADIUS);
            queue.add(new Place(cx + x, cy + 2, cz + z, Material.CHISELED_POLISHED_BLACKSTONE));
            queue.add(new Place(cx + x, cy + 3, cz + z, Material.SEA_LANTERN));
        }

        // 4b. Invisible fence on top of the wall so nobody walks off the edge.
        //     Gateways punch through this again below, so the four entrances stay open.
        for (int y = 3; y <= 6; y++) {
            ring(queue, cx, cy, cz, PLATFORM_RADIUS, y, Material.BARRIER);
            ring(queue, cx, cy, cz, PLATFORM_RADIUS + 1, y, Material.BARRIER);
        }

        // 5. Four gateways, cut through the wall with an arch over each.
        gateway(queue, cx, cy, cz, PLATFORM_RADIUS, 1, 0);
        gateway(queue, cx, cy, cz, PLATFORM_RADIUS, -1, 0);
        gateway(queue, cx, cy, cz, PLATFORM_RADIUS, 0, 1);
        gateway(queue, cx, cy, cz, PLATFORM_RADIUS, 0, -1);

        // 6. Corner towers on the diagonals.
        int t = (int) Math.round(PLATFORM_RADIUS * 0.66);
        tower(queue, cx + t, cy, cz + t);
        tower(queue, cx - t, cy, cz + t);
        tower(queue, cx + t, cy, cz - t);
        tower(queue, cx - t, cy, cz - t);

        // 7. Fountains between the towers.
        int f = (int) Math.round(PLATFORM_RADIUS * 0.55);
        fountain(queue, cx + f, cy, cz);
        fountain(queue, cx - f, cy, cz);
        fountain(queue, cx, cy, cz + f);
        fountain(queue, cx, cy, cz - f);

        // 8. Central stepped dais.
        disc(queue, cx, cy - 1, cz, 11, Material.POLISHED_BLACKSTONE_BRICKS);
        disc(queue, cx, cy, cz, 9, Material.SMOOTH_QUARTZ);
        disc(queue, cx, cy + 1, cz, 7, Material.QUARTZ_BLOCK);
        disc(queue, cx, cy + 2, cz, 5, Material.CHISELED_QUARTZ_BLOCK);
        ring(queue, cx, cy, cz, 9, 1, Material.QUARTZ_PILLAR);
        ring(queue, cx, cy, cz, 7, 2, Material.QUARTZ_PILLAR);

        // 9. Beacon spire at the middle.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                queue.add(new Place(cx + x, cy + 2, cz + z, Material.IRON_BLOCK));
            }
        }
        queue.add(new Place(cx, cy + 3, cz, Material.BEACON));
        queue.add(new Place(cx, cy + 4, cz, Material.TINTED_GLASS));
        for (int y = 5; y <= 20; y++) {
            Material material = switch (y % 4) {
                case 0 -> Material.AMETHYST_BLOCK;
                case 2 -> Material.CUT_COPPER;
                default -> Material.QUARTZ_PILLAR;
            };
            queue.add(new Place(cx, cy + y, cz, material));
            if (y % 5 == 0) {
                queue.add(new Place(cx + 1, cy + y, cz, Material.SEA_LANTERN));
                queue.add(new Place(cx - 1, cy + y, cz, Material.SEA_LANTERN));
                queue.add(new Place(cx, cy + y, cz + 1, Material.SEA_LANTERN));
                queue.add(new Place(cx, cy + y, cz - 1, Material.SEA_LANTERN));
            }
        }
        queue.add(new Place(cx, cy + 21, cz, Material.SEA_LANTERN));
        queue.add(new Place(cx, cy + 22, cz, Material.LANTERN));

        // 10. Planters so it is not all stone.
        for (int deg = 22; deg < 360; deg += 45) {
            int x = (int) Math.round(Math.cos(Math.toRadians(deg)) * 15);
            int z = (int) Math.round(Math.sin(Math.toRadians(deg)) * 15);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    queue.add(new Place(cx + x + dx, cy - 1, cz + z + dz,
                            Material.POLISHED_BLACKSTONE_BRICKS));
                    queue.add(new Place(cx + x + dx, cy, cz + z + dz, Material.MOSS_BLOCK));
                }
            }
            queue.add(new Place(cx + x, cy + 1, cz + z, Material.MANGROVE_ROOTS));
            queue.add(new Place(cx + x, cy + 2, cz + z, Material.MANGROVE_ROOTS));
            queue.add(new Place(cx + x, cy + 3, cz + z, Material.AZALEA_LEAVES));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    queue.add(new Place(cx + x + dx, cy + 4, cz + z + dz, Material.AZALEA_LEAVES));
                }
            }
            queue.add(new Place(cx + x, cy + 5, cz + z, Material.FLOWERING_AZALEA_LEAVES));
        }

        running = true;
        int total = queue.size();
        Bukkit.broadcast(Util.text("&6[Spawn] &7Building a new spawn, expect a brief dip in TPS."));
        plugin.getLogger().info("Spawn build queued: " + total + " block placements.");

        final int[] done = {0};
        final int[] lastPercent = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int placed = 0;
            while (placed < PER_TICK && !queue.isEmpty()) {
                Place place = queue.poll();
                Block block = world.getBlockAt(place.x(), place.y(), place.z());
                if (block.getType() != place.material()) {
                    block.setType(place.material(), false);
                }
                // Placed leaves have no log to hold them, so without this they rot away.
                if (place.material().name().endsWith("_LEAVES")
                        && block.getBlockData() instanceof Leaves leaves) {
                    leaves.setPersistent(true);
                    block.setBlockData(leaves, false);
                }
                placed++;
                done[0]++;
            }
            int percent = (int) ((done[0] / (double) total) * 100);
            if (percent >= lastPercent[0] + 25 && percent < 100) {
                lastPercent[0] = percent;
                if (player.isOnline()) {
                    player.sendActionBar(Util.text("&7Building spawn... &f" + percent + "%"));
                }
            }
            if (queue.isEmpty()) {
                running = false;
                placeCrates(world, cx, cy, cz);
                Location spawn = new Location(world, cx + 0.5, cy + 3, cz + 0.5);
                world.setSpawnLocation(cx, cy + 3, cz);
                if (player.isOnline()) {
                    player.teleportAsync(spawn.clone().add(0, 0, 10));
                    player.sendMessage(Util.text("&aSpawn built. &7World spawn moved here."));
                    player.sendMessage(Util.text("&7Blocks placed: &f" + total));
                    player.sendMessage(Util.text("&74 crates placed and registered around the dais."));
                    player.sendMessage(Util.text("&8Add more anywhere with /setcrate."));
                }
                Bukkit.broadcast(Util.text("&6[Spawn] &7The new spawn is finished."));
                plugin.getLogger().info("Spawn build finished at " + cx + " " + cy + " " + cz);
                task.cancel();
            }
        }, 1L, 1L);
    }

    /**
     * Four crate pedestals on the cardinal spokes, registered so players can
     * use them straight away without anyone running /setcrate.
     */
    private void placeCrates(World world, int cx, int cy, int cz) {
        // offset x, offset z, facing back toward the middle, tier
        Object[][] spots = {
                {13, 0, BlockFace.WEST, "COMMON"},
                {-13, 0, BlockFace.EAST, "RARE"},
                {0, 13, BlockFace.NORTH, "EPIC"},
                {0, -13, BlockFace.SOUTH, "LEGENDARY"},
        };
        for (Object[] spot : spots) {
            int x = cx + (int) spot[0];
            int z = cz + (int) spot[1];
            BlockFace facing = (BlockFace) spot[2];
            String tier = (String) spot[3];

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.getBlockAt(x + dx, cy - 1, z + dz)
                            .setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
                    world.getBlockAt(x + dx, cy, z + dz)
                            .setType(Material.CHISELED_QUARTZ_BLOCK, false);
                }
            }
            world.getBlockAt(x, cy, z).setType(Material.SEA_LANTERN, false);

            Block crate = world.getBlockAt(x, cy + 1, z);
            crate.setType(Material.ENDER_CHEST, false);
            if (crate.getBlockData() instanceof Directional directional) {
                directional.setFacing(facing);
                crate.setBlockData(directional, false);
            }
            plugin.crates().registerCrate(crate, tier);

            // Sign on top saying which key this one takes.
            Block signBlock = world.getBlockAt(x, cy + 2, z);
            signBlock.setType(Material.OAK_SIGN, false);
            if (signBlock.getBlockData() instanceof Rotatable rotatable) {
                rotatable.setRotation(facing);
                signBlock.setBlockData(rotatable, false);
            }
            if (signBlock.getState() instanceof org.bukkit.block.Sign sign) {
                String colour = switch (tier) {
                    case "RARE" -> "&b";
                    case "EPIC" -> "&5";
                    case "LEGENDARY" -> "&6";
                    default -> "&7";
                };
                sign.getSide(Side.FRONT).line(0, Util.text("&8[ Crate ]"));
                sign.getSide(Side.FRONT).line(1, Util.text(colour + "&l" + tier));
                sign.getSide(Side.FRONT).line(2, Util.text("&0Right click"));
                sign.getSide(Side.FRONT).line(3, Util.text("&0with " + tier + " key"));
                sign.getSide(Side.BACK).line(1, Util.text(colour + "&l" + tier));
                sign.getSide(Side.BACK).line(2, Util.text("&0Crate"));
                sign.update(true, false);
            }
        }
        plugin.getLogger().info("Registered 4 spawn crates, one per tier.");
    }

    /**
     * Rings the current world spawn with barriers without rebuilding anything.
     * For a spawn that was generated before the fence existed.
     */
    public void fenceExistingSpawn(Player player) {
        fenceExistingSpawn(player, false);
    }

    public void fenceExistingSpawn(Player player, boolean sealGates) {
        World world = player.getWorld();
        Location spawn = world.getSpawnLocation();
        int cx = spawn.getBlockX();
        int cy = spawn.getBlockY();
        int cz = spawn.getBlockZ();
        int placed = 0;

        for (int deg = 0; deg < 360; deg++) {
            for (int r = PLATFORM_RADIUS; r <= PLATFORM_RADIUS + 1; r++) {
                int x = (int) Math.round(Math.cos(Math.toRadians(deg)) * r);
                int z = (int) Math.round(Math.sin(Math.toRadians(deg)) * r);
                // Leave the four gateways walkable.
                boolean gateway = (Math.abs(x) <= 2 && Math.abs(z) >= r - 2)
                        || (Math.abs(z) <= 2 && Math.abs(x) >= r - 2);
                if (gateway && !sealGates) continue;
                for (int y = -1; y <= 4; y++) {
                    Block block = world.getBlockAt(cx + x, cy + y, cz + z);
                    if (block.getType().isAir()) {
                        block.setType(Material.BARRIER, false);
                        placed++;
                    }
                }
            }
        }
        player.sendMessage(Util.text("&8&m----------------------------"));
        player.sendMessage(Util.text(" &aPlaced &f" + placed + " &abarriers."));
        player.sendMessage(Util.text(" &7Centre: &f" + cx + ", " + cy + ", " + cz
                + " &8(world spawn)"));
        player.sendMessage(Util.text(" &7Ring radius: &f" + PLATFORM_RADIUS + "-"
                + (PLATFORM_RADIUS + 1) + "&7, height &f" + cy + " to " + (cy + 4)));
        player.sendMessage(Util.text(" &7Gateways: " + (sealGates ? "&csealed too" : "&aleft open")));
        if (placed == 0) {
            player.sendMessage(Util.text(" &cNothing placed, so that ring is already solid."));
            player.sendMessage(Util.text(" &7Either the fence is there, or world spawn is"));
            player.sendMessage(Util.text(" &7somewhere other than the middle of your plaza."));
        }
        player.sendMessage(Util.text(" &8Hold a barrier item to see them."));
        player.sendMessage(Util.text(" &8Run /spawnfence full to close the gateways as well."));
        player.sendMessage(Util.text("&8&m----------------------------"));
    }

    // ---------------- shape helpers ----------------

    private void ring(Deque<Place> queue, int cx, int cy, int cz, int radius, int yOffset,
                      Material material) {
        for (int deg = 0; deg < 360; deg++) {
            int x = (int) Math.round(Math.cos(Math.toRadians(deg)) * radius);
            int z = (int) Math.round(Math.sin(Math.toRadians(deg)) * radius);
            queue.add(new Place(cx + x, cy + yOffset, cz + z, material));
        }
    }

    private void disc(Deque<Place> queue, int cx, int cy, int cz, int radius, Material material) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z > radius * radius) continue;
                queue.add(new Place(cx + x, cy, cz + z, material));
            }
        }
    }

    /** Cuts an opening in the perimeter and puts a lit arch over it. */
    private void gateway(Deque<Place> queue, int cx, int cy, int cz, int radius, int dx, int dz) {
        int gx = cx + dx * radius;
        int gz = cz + dz * radius;
        // perpendicular axis
        int px = dz;
        int pz = dx;

        for (int w = -2; w <= 2; w++) {
            for (int y = 0; y <= 5; y++) {
                for (int out = -1; out <= 2; out++) {
                    queue.add(new Place(gx + px * w + dx * out, cy + y,
                            gz + pz * w + dz * out, Material.AIR));
                }
            }
        }
        for (int side = -3; side <= 3; side += 6) {
            for (int y = 0; y <= 9; y++) {
                Material material = (y == 0 || y == 9)
                        ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS;
                queue.add(new Place(gx + px * side, cy + y, gz + pz * side, material));
                queue.add(new Place(gx + px * side - dx, cy + y, gz + pz * side - dz, material));
            }
            queue.add(new Place(gx + px * side, cy + 10, gz + pz * side, Material.SEA_LANTERN));
            queue.add(new Place(gx + px * side, cy + 11, gz + pz * side, Material.GOLD_BLOCK));
        }
        for (int w = -3; w <= 3; w++) {
            queue.add(new Place(gx + px * w, cy + 6, gz + pz * w, Material.QUARTZ_BLOCK));
            queue.add(new Place(gx + px * w, cy + 7, gz + pz * w, Material.SMOOTH_QUARTZ));
            queue.add(new Place(gx + px * w, cy + 8, gz + pz * w, Material.QUARTZ_PILLAR));
        }
        for (int w = -2; w <= 2; w += 2) {
            queue.add(new Place(gx + px * w, cy + 5, gz + pz * w, Material.IRON_BARS));
            queue.add(new Place(gx + px * w, cy + 4, gz + pz * w, Material.LANTERN));
        }
    }

    private void tower(Deque<Place> queue, int cx, int cy, int cz) {
        int radius = 3;
        for (int y = 0; y <= 16; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    int d2 = x * x + z * z;
                    if (d2 > radius * radius) continue;
                    boolean edge = d2 > (radius - 1) * (radius - 1);
                    if (y == 16) {
                        queue.add(new Place(cx + x, cy + y, cz + z,
                                edge ? Material.POLISHED_BLACKSTONE_BRICK_WALL : Material.SMOOTH_QUARTZ));
                    } else if (edge) {
                        Material material = (y % 5 == 0)
                                ? Material.CHISELED_POLISHED_BLACKSTONE
                                : Material.POLISHED_BLACKSTONE_BRICKS;
                        queue.add(new Place(cx + x, cy + y, cz + z, material));
                    } else {
                        queue.add(new Place(cx + x, cy + y, cz + z,
                                y == 0 ? Material.SMOOTH_QUARTZ : Material.AIR));
                    }
                }
            }
        }
        queue.add(new Place(cx, cy + 15, cz, Material.SEA_LANTERN));
        queue.add(new Place(cx, cy + 17, cz, Material.SEA_LANTERN));
        queue.add(new Place(cx, cy + 18, cz, Material.LANTERN));
    }

    private void fountain(Deque<Place> queue, int cx, int cy, int cz) {
        int radius = 4;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int d2 = x * x + z * z;
                if (d2 > radius * radius) continue;
                boolean rim = d2 > (radius - 1) * (radius - 1);
                queue.add(new Place(cx + x, cy - 1, cz + z,
                        rim ? Material.DARK_PRISMARINE : Material.PRISMARINE_BRICKS));
                if (rim) {
                    queue.add(new Place(cx + x, cy, cz + z, Material.PRISMARINE));
                } else {
                    queue.add(new Place(cx + x, cy, cz + z, Material.WATER));
                }
            }
        }
        queue.add(new Place(cx, cy, cz, Material.PRISMARINE_BRICKS));
        queue.add(new Place(cx, cy + 1, cz, Material.PRISMARINE_BRICKS));
        queue.add(new Place(cx, cy + 2, cz, Material.SEA_LANTERN));
        queue.add(new Place(cx, cy + 3, cz, Material.WATER));
    }
}
