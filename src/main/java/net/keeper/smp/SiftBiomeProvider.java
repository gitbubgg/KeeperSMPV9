package net.keeper.smp;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.List;

/**
 * Zones the Sift into a few distinct regions purely by biome label - the
 * default generator still shapes the terrain, so this only works cleanly
 * for biomes whose distinctive look (surface blocks, foliage/water color,
 * natural trees) is driven by the biome tag itself rather than by special
 * underground generation context. Sculk-covered "Deep Dark" terrain and
 * the boss's own lair are deliberately not attempted here - those need
 * blocks placed by hand/code, which is a later, separate pass.
 *
 * Rings out from spawn:
 *  - Singer's Meadow (0-300):    Biome.MEADOW
 *  - Lullaby Hills   (300-700):  Biome.CHERRY_GROVE - its natural cherry
 *                                 trees are already our reskinned Songwood
 *  - The Carapace    (700+):     Biome.BADLANDS - layered orange-red
 *                                 terracotta, closest vanilla match to the
 *                                 Sift's described red/orange stone
 */
public class SiftBiomeProvider extends BiomeProvider {

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        double dist = Math.sqrt((double) x * x + (double) z * z);
        if (dist < 300) return Biome.MEADOW;
        if (dist < 700) return Biome.CHERRY_GROVE;
        return Biome.BADLANDS;
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return List.of(Biome.MEADOW, Biome.CHERRY_GROVE, Biome.BADLANDS);
    }
}
