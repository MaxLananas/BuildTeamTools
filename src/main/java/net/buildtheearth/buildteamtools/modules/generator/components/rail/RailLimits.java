package net.buildtheearth.buildteamtools.modules.generator.components.rail;

import net.buildtheearth.buildteamtools.BuildTeamTools;
import net.buildtheearth.buildteamtools.utils.io.ConfigPaths;
import net.buildtheearth.buildteamtools.utils.io.ConfigUtil;
import org.bukkit.configuration.file.FileConfiguration;

record RailLimits(
        int maxControlPoints,
        int maxPathPoints,
        int maxBlockPlacements,
        long maxPreparedRegionVolume,
        int maxPreparedRegionAxisLength,
        int blockPlacementBatchSize
) {

    private static final int DEFAULT_MAX_CONTROL_POINTS = 500;
    private static final int DEFAULT_MAX_PATH_POINTS = 12_000;
    private static final int DEFAULT_MAX_BLOCK_PLACEMENTS = 75_000;
    private static final long DEFAULT_MAX_PREPARED_REGION_VOLUME = 750_000L;
    private static final int DEFAULT_MAX_PREPARED_REGION_AXIS_LENGTH = 768;
    private static final int DEFAULT_BLOCK_PLACEMENT_BATCH_SIZE = 500;

    static RailLimits fromConfig() {
        FileConfiguration config = BuildTeamTools.getInstance().getConfig(ConfigUtil.GENERATOR);

        return new RailLimits(
                getPositiveInt(config, ConfigPaths.Generator.Rail.MAX_CONTROL_POINTS, DEFAULT_MAX_CONTROL_POINTS, 2),
                getPositiveInt(config, ConfigPaths.Generator.Rail.MAX_PATH_POINTS, DEFAULT_MAX_PATH_POINTS, 2),
                getPositiveInt(config, ConfigPaths.Generator.Rail.MAX_BLOCK_PLACEMENTS, DEFAULT_MAX_BLOCK_PLACEMENTS, 1),
                getPositiveLong(config, ConfigPaths.Generator.Rail.MAX_PREPARED_REGION_VOLUME, DEFAULT_MAX_PREPARED_REGION_VOLUME),
                getPositiveInt(config, ConfigPaths.Generator.Rail.MAX_PREPARED_REGION_AXIS_LENGTH, DEFAULT_MAX_PREPARED_REGION_AXIS_LENGTH, 1),
                getPositiveInt(config, ConfigPaths.Generator.Rail.BLOCK_PLACEMENT_BATCH_SIZE, DEFAULT_BLOCK_PLACEMENT_BATCH_SIZE, 1)
        );
    }

    private static int getPositiveInt(FileConfiguration config, String path, int fallback, int minimum) {
        return Math.max(minimum, config.getInt(path, fallback));
    }

    private static long getPositiveLong(FileConfiguration config, String path, long fallback) {
        return Math.max(1L, config.getLong(path, fallback));
    }
}
