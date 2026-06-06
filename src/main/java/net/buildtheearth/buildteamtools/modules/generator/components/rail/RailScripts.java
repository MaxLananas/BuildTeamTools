package net.buildtheearth.buildteamtools.modules.generator.components.rail;

import com.alpsbte.alpslib.utils.ChatHelper;
import com.alpsbte.alpslib.utils.GeneratorUtils;
import com.cryptomorin.xseries.XMaterial;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.buildtheearth.buildteamtools.BuildTeamTools;
import net.buildtheearth.buildteamtools.modules.generator.model.GeneratorComponent;
import net.buildtheearth.buildteamtools.modules.generator.model.Script;
import net.buildtheearth.buildteamtools.modules.generator.model.Settings;
import net.buildtheearth.buildteamtools.utils.MenuItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RailScripts extends Script {

    private static final int MAX_CONTROL_POINTS = 250;
    private static final int MAX_PATH_POINTS = 20_000;
    private static final int MAX_BLOCK_PLACEMENTS = 100_000;
    private static final int SELECTION_PADDING = 4;
    private static final int SELECTION_VERTICAL_PADDING = 12;
    private static final int PREPARE_SELECTION_EXPANSION = 8;
    private static final int SURFACE_Y_OFFSET = 1;
    private static final Direction DEFAULT_FACING = Direction.EAST;

    private static final XMaterial[] CENTER_MATERIALS = new XMaterial[]{
            XMaterial.DEAD_FIRE_CORAL_BLOCK,
            XMaterial.STONE,
            XMaterial.COBBLESTONE
    };

    private Block[][][] blocks;
    private List<Vector> controlPoints = new ArrayList<>();
    private List<Vector> centerPath = new ArrayList<>();
    private RailType railType = RailType.STANDARD;

    public RailScripts(Player player, GeneratorComponent generatorComponent) {
        super(player, generatorComponent);

        sendRailInfo("Rail Generator is preparing your selection...");

        Bukkit.getScheduler().runTaskAsynchronously(BuildTeamTools.getInstance(), () -> {
            try {
                if (!prepareSession()) return;

                railScript_v_2_0();
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(BuildTeamTools.getInstance(), () -> getGeneratorComponent().sendError(getPlayer()));
                ChatHelper.logError("Rail Generator failed while preparing or generating.", exception);
            }
        });
    }

    private boolean prepareSession() {
        controlPoints = getControlPoints();

        if (!hasValidControlPoints()) return false;

        List<Vector> railSelectionPoints = createRailSelectionPoints(controlPoints);
        GeneratorUtils.createPolySelection(
                getPlayer(),
                railSelectionPoints,
                getSelectionMinY(controlPoints),
                getSelectionMaxY(controlPoints)
        );

        blocks = GeneratorUtils.prepareScriptSession(
                localSession,
                actor,
                getPlayer(),
                weWorld,
                PREPARE_SELECTION_EXPANSION,
                true,
                false,
                false
        );

        snapMissingControlPointHeightsToTerrain(controlPoints);
        centerPath = createCenterPath(controlPoints);
        adjustCenterPathToTerrain();
        railType = getRailType();

        return true;
    }

    private void railScript_v_2_0() {
        if (centerPath.size() < 2) {
            sendRailError("Rail Generator could not create a valid rail path.");
            return;
        }

        if (centerPath.size() > MAX_PATH_POINTS) {
            sendRailError("Rail Generator path is too large. Please use a smaller selection.");
            return;
        }

        Map<PositionKey, BlockState> railBlocks = buildRailBlocks(centerPath);

        if (railBlocks.size() > MAX_BLOCK_PLACEMENTS) {
            sendRailError("Rail Generator would place too many blocks. Please use a smaller selection.");
            return;
        }

        setBlockStatesAtPositions(
                railBlocks.keySet().stream().map(PositionKey::toVector).toList(),
                new ArrayList<>(railBlocks.values())
        );

        finish(blocks, getRestoreSelectionPoints());
    }

    private boolean hasValidControlPoints() {
        if (controlPoints.size() < 2) {
            sendRailError("Rail Generator needs at least two points.");
            return false;
        }

        if (controlPoints.size() > MAX_CONTROL_POINTS) {
            sendRailError("Rail Generator has too many points. Please use fewer points.");
            return false;
        }

        return true;
    }

    private void sendRailInfo(String message) {
        Bukkit.getScheduler().runTask(
                BuildTeamTools.getInstance(),
                () -> getPlayer().sendMessage(Component.text(message, NamedTextColor.YELLOW))
        );
    }

    private void sendRailError(String message) {
        Bukkit.getScheduler().runTask(
                BuildTeamTools.getInstance(),
                () -> getPlayer().sendMessage(Component.text(message, NamedTextColor.RED))
        );
    }

    private List<Vector> getControlPoints() {
        List<Vector> selectionPoints = GeneratorUtils.getSelectionPointsFromRegion(getRegion());

        if (selectionPoints == null) return Collections.emptyList();

        return GeneratorUtils.copyToBlockVectors(selectionPoints);
    }

    private List<Vector> createRailSelectionPoints(List<Vector> points) {
        List<Vector> selectionLine = new ArrayList<>(points);

        if (selectionLine.size() >= 2)
            selectionLine = GeneratorUtils.extendPolyLine(selectionLine);

        List<Vector> shiftedPoints = GeneratorUtils.shiftPoints(selectionLine, SELECTION_PADDING, true);

        if (shiftedPoints == null || shiftedPoints.size() < 3)
            return GeneratorUtils.createBoundsSelectionPoints(points, SELECTION_PADDING);

        return shiftedPoints;
    }

    private List<Vector> createCenterPath(List<Vector> points) {
        return GeneratorUtils.removeOrthogonalCorners(GeneratorUtils.createShortestBlockPath(points));
    }

    private Map<PositionKey, BlockState> buildRailBlocks(List<Vector> path) {
        Map<PositionKey, BlockState> railBlocks = new LinkedHashMap<>();
        Set<PositionKey> centerPositions = getCenterPositions(path);
        Set<ColumnKey> centerColumns = getCenterColumns(path);
        Map<ColumnKey, RailSideBlock> sideBlocks = new LinkedHashMap<>();

        for (int index = 0; index < path.size(); index++) {
            Vector center = path.get(index);

            for (RailSidePlacement sidePlacement : getSidePlacements(path, index))
                addSideBlock(sideBlocks, center, sidePlacement, centerPositions, centerColumns);
        }

        for (RailSideBlock sideBlock : sideBlocks.values())
            railBlocks.put(sideBlock.key(), createAnvilBlockState(resolveSideBlockFacing(sideBlock, sideBlocks)));

        for (Vector center : path)
            railBlocks.put(PositionKey.from(center), createCenterBlockState(center));

        return railBlocks;
    }

    private List<RailSidePlacement> getSidePlacements(List<Vector> path, int index) {
        List<RailSidePlacement> placements = new ArrayList<>();
        Vector center = path.get(index);
        RailStep previousStep = index > 0 ? getStep(path.get(index - 1), center) : null;
        RailStep nextStep = index < path.size() - 1 ? getStep(center, path.get(index + 1)) : null;

        addSidePlacements(placements, getRailStep(path, index, new RailStep(1, 0)));

        if (previousStep != null)
            addSidePlacements(placements, previousStep);

        if (nextStep != null)
            addSidePlacements(placements, nextStep);

        return placements;
    }

    private void addSidePlacements(List<RailSidePlacement> placements, RailStep step) {
        if (step.dx() != 0 && step.dz() != 0) {
            addSidePlacement(placements, new RailStep(step.dx(), 0), GeneratorUtils.getFacing(0, step.dz(), DEFAULT_FACING));
            addSidePlacement(placements, new RailStep(0, step.dz()), GeneratorUtils.getFacing(step.dx(), 0, DEFAULT_FACING));
            return;
        }

        if (step.dx() != 0) {
            Direction facing = GeneratorUtils.getFacing(step.dx(), 0, DEFAULT_FACING);
            addSidePlacement(placements, new RailStep(0, 1), facing);
            addSidePlacement(placements, new RailStep(0, -1), facing);
            return;
        }

        Direction facing = GeneratorUtils.getFacing(0, step.dz(), DEFAULT_FACING);
        addSidePlacement(placements, new RailStep(1, 0), facing);
        addSidePlacement(placements, new RailStep(-1, 0), facing);
    }

    private void addSidePlacement(List<RailSidePlacement> placements, RailStep offset, Direction facing) {
        for (RailSidePlacement placement : placements) {
            if (placement.offset().equals(offset)) return;
        }

        placements.add(new RailSidePlacement(offset, facing));
    }

    private void addSideBlock(
            Map<ColumnKey, RailSideBlock> sideBlocks,
            Vector center,
            RailSidePlacement sidePlacement,
            Set<PositionKey> centerPositions,
            Set<ColumnKey> centerColumns
    ) {
        RailStep sideOffset = sidePlacement.offset();

        if (sideOffset.dx() == 0 && sideOffset.dz() == 0)
            return;

        int x = center.getBlockX() + sideOffset.dx();
        int z = center.getBlockZ() + sideOffset.dz();
        int y = getRailSurfaceY(x, z, center.getBlockY());

        PositionKey key = PositionKey.of(x, y, z);
        ColumnKey columnKey = ColumnKey.from(key);

        if (centerPositions.contains(key) || centerColumns.contains(columnKey))
            return;

        sideBlocks
                .computeIfAbsent(columnKey, ignored -> new RailSideBlock(key))
                .addFacing(sidePlacement.facing());
    }

    private Direction resolveSideBlockFacing(RailSideBlock sideBlock, Map<ColumnKey, RailSideBlock> sideBlocks) {
        PositionKey key = sideBlock.key();
        boolean east = sideBlocks.containsKey(ColumnKey.of(key.x() + 1, key.z()));
        boolean west = sideBlocks.containsKey(ColumnKey.of(key.x() - 1, key.z()));
        boolean south = sideBlocks.containsKey(ColumnKey.of(key.x(), key.z() + 1));
        boolean north = sideBlocks.containsKey(ColumnKey.of(key.x(), key.z() - 1));
        int xConnections = (east ? 1 : 0) + (west ? 1 : 0);
        int zConnections = (south ? 1 : 0) + (north ? 1 : 0);
        Direction preferredFacing = sideBlock.getPreferredFacing();

        if (xConnections > zConnections)
            return resolveAxisFacing(preferredFacing, Direction.EAST, Direction.WEST, east, west);

        if (zConnections > xConnections)
            return resolveAxisFacing(preferredFacing, Direction.SOUTH, Direction.NORTH, south, north);

        return preferredFacing;
    }

    private Direction resolveAxisFacing(
            Direction preferredFacing,
            Direction positiveFacing,
            Direction negativeFacing,
            boolean hasPositiveNeighbor,
            boolean hasNegativeNeighbor
    ) {
        if (preferredFacing == positiveFacing && hasPositiveNeighbor || preferredFacing == negativeFacing && hasNegativeNeighbor)
            return preferredFacing;

        if (hasPositiveNeighbor && !hasNegativeNeighbor)
            return positiveFacing;

        if (hasNegativeNeighbor && !hasPositiveNeighbor)
            return negativeFacing;

        return preferredFacing == negativeFacing ? negativeFacing : positiveFacing;
    }

    private Set<ColumnKey> getCenterColumns(List<Vector> path) {
        Set<ColumnKey> centerColumns = new HashSet<>();

        for (Vector center : path)
            centerColumns.add(ColumnKey.from(PositionKey.from(center)));

        return centerColumns;
    }

    private Set<PositionKey> getCenterPositions(List<Vector> path) {
        Set<PositionKey> centerPositions = new HashSet<>();

        for (Vector center : path)
            centerPositions.add(PositionKey.from(center));

        return centerPositions;
    }

    private RailStep getRailStep(List<Vector> path, int index, RailStep fallbackStep) {
        RailStep previousStep = index > 0 ? getStep(path.get(index - 1), path.get(index)) : null;
        RailStep nextStep = index < path.size() - 1 ? getStep(path.get(index), path.get(index + 1)) : null;

        if (previousStep != null && nextStep != null) {
            int dx = Integer.compare(previousStep.dx() + nextStep.dx(), 0);
            int dz = Integer.compare(previousStep.dz() + nextStep.dz(), 0);

            if (dx != 0 || dz != 0) return new RailStep(dx, dz);
        }

        if (nextStep != null) return nextStep;

        if (previousStep != null) return previousStep;

        return fallbackStep;
    }

    private RailStep getStep(Vector from, Vector to) {
        int dx = Integer.compare(to.getBlockX() - from.getBlockX(), 0);
        int dz = Integer.compare(to.getBlockZ() - from.getBlockZ(), 0);

        if (dx == 0 && dz == 0) return null;

        return new RailStep(dx, dz);
    }

    private void snapMissingControlPointHeightsToTerrain(List<Vector> points) {
        if (blocks == null || !hasMissingControlPointHeights(points)) return;

        GeneratorUtils.adjustHeight(points, blocks);
    }

    private void adjustCenterPathToTerrain() {
        if (blocks == null || centerPath.isEmpty()) return;

        for (Vector point : centerPath)
            point.setY(getRailSurfaceY(point.getBlockX(), point.getBlockZ(), point.getBlockY()));
    }

    private int getRailSurfaceY(int x, int z, int fallbackY) {
        if (blocks == null)
            return fallbackY;

        int surfaceY = GeneratorUtils.getMaxHeight(blocks, x, z, MenuItems.getIgnoredMaterials());

        if (surfaceY == 0)
            return fallbackY;

        return surfaceY + SURFACE_Y_OFFSET;
    }

    private boolean hasMissingControlPointHeights(List<Vector> points) {
        // WorldEdit polygon and convex selection points can arrive with Y=0,
        // which means the rail should snap those points to the prepared terrain.
        for (Vector point : points)
            if (point.getBlockY() != 0) return false;

        return true;
    }

    private List<Vector> getRestoreSelectionPoints() {
        return controlPoints;
    }

    private int getSelectionMinY(List<Vector> points) {
        int minY = getRegion() != null ? getRegion().getMinimumY() : GeneratorUtils.getMinHeight(points);
        return Math.max(getPlayer().getWorld().getMinHeight(), minY - SELECTION_VERTICAL_PADDING);
    }

    private int getSelectionMaxY(List<Vector> points) {
        int maxY = getRegion() != null ? getRegion().getMaximumY() : GeneratorUtils.getMaxHeight(points);
        return Math.min(getPlayer().getWorld().getMaxHeight() - 1, maxY + SELECTION_VERTICAL_PADDING);
    }

    private BlockState createCenterBlockState(Vector position) {
        return switch (railType) {
            case STANDARD -> createStandardCenterBlockState(position);
        };
    }

    private BlockState createStandardCenterBlockState(Vector position) {
        int index = Math.floorMod(
                position.getBlockX() * 31 + position.getBlockY() * 23 + position.getBlockZ() * 17,
                CENTER_MATERIALS.length
        );

        return GeneratorUtils.getBlockState(CENTER_MATERIALS[index]);
    }

    private BlockState createAnvilBlockState(Direction direction) {
        return GeneratorUtils.getBlockStateWithFacing(BlockTypes.ANVIL, direction);
    }

    private RailType getRailType() {
        Settings settings = getGeneratorComponent().getPlayerSettings().get(getPlayer().getUniqueId());

        if (!(settings instanceof RailSettings railSettings))
            return RailType.STANDARD;

        Object value = railSettings.getValues().get(RailFlag.RAIL_TYPE);
        return value instanceof RailType selectedRailType ? selectedRailType : RailType.STANDARD;
    }

    private record RailStep(int dx, int dz) {
    }

    private record RailSidePlacement(RailStep offset, Direction facing) {
    }

    private record PositionKey(int x, int y, int z) {

        private static PositionKey from(Vector vector) {
            return new PositionKey(
                    vector.getBlockX(),
                    vector.getBlockY(),
                    vector.getBlockZ()
            );
        }

        private static PositionKey of(int x, int y, int z) {
            return new PositionKey(x, y, z);
        }

        private Vector toVector() {
            return new Vector(x, y, z);
        }
    }

    private record ColumnKey(int x, int z) {

        private static ColumnKey from(PositionKey key) {
            return new ColumnKey(key.x(), key.z());
        }

        private static ColumnKey of(int x, int z) {
            return new ColumnKey(x, z);
        }
    }

    private static class RailSideBlock {

        private final PositionKey key;
        private final Map<Direction, Integer> facingScores = new LinkedHashMap<>();

        private RailSideBlock(PositionKey key) {
            this.key = key;
        }

        private PositionKey key() {
            return key;
        }

        private void addFacing(Direction facing) {
            facingScores.merge(facing, 1, Integer::sum);
        }

        private Direction getPreferredFacing() {
            Direction preferredFacing = DEFAULT_FACING;
            int preferredScore = -1;

            for (Map.Entry<Direction, Integer> entry : facingScores.entrySet()) {
                if (entry.getValue() > preferredScore) {
                    preferredFacing = entry.getKey();
                    preferredScore = entry.getValue();
                }
            }

            return preferredFacing;
        }
    }
}
