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
import net.buildtheearth.buildteamtools.utils.io.ConfigPaths;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
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

    private static final int DEFAULT_MAX_CONTROL_POINTS = 250;
    private static final int DEFAULT_MAX_PATH_POINTS = 6_000;
    private static final int DEFAULT_MAX_BLOCK_PLACEMENTS = 30_000;
    private static final long DEFAULT_MAX_PREPARED_REGION_VOLUME = 300_000L;
    private static final int DEFAULT_MAX_PREPARED_REGION_AXIS_LENGTH = 512;
    private static final int DEFAULT_BLOCK_PLACEMENT_BATCH_SIZE = 250;
    private static final int SELECTION_PADDING = 4;
    private static final int SELECTION_VERTICAL_PADDING = 12;
    private static final int PREPARE_SELECTION_EXPANSION = 8;
    private static final int SURFACE_Y_OFFSET = 1;

    private static final int DEFAULT_RAIL_LANE_COUNT = 1;
    private static final int DEFAULT_RAIL_LANE_SPACING = 5;

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
    private final RailLimits limits;
    private final Runnable preparationFinishedCallback;

    public RailScripts(Player player, GeneratorComponent generatorComponent) {
        this(player, generatorComponent, () -> {
        });
    }

    public RailScripts(Player player, GeneratorComponent generatorComponent, Runnable preparationFinishedCallback) {
        super(player, generatorComponent);
        this.limits = RailLimits.fromConfig();
        this.preparationFinishedCallback = preparationFinishedCallback;

        sendRailInfo("Rail Generator is validating your selection...");

        Bukkit.getScheduler().runTaskAsynchronously(BuildTeamTools.getInstance(), () -> {
            boolean queuedGeneration = false;

            try {
                if (!prepareSession()) return;

                queuedGeneration = railScript_v_2_0();
            } catch (Exception exception) {
                runOnMainThread(() -> getGeneratorComponent().sendError(getPlayer()));
                ChatHelper.logError("Rail Generator failed while preparing or generating.", exception);
            } finally {
                if (!queuedGeneration)
                    runOnMainThread(preparationFinishedCallback);
            }
        });
    }

    private boolean prepareSession() {
        controlPoints = getControlPoints();

        if (!hasValidControlPoints()) return false;

        List<Vector> railSelectionPoints = createRailSelectionPoints(controlPoints);
        centerPath = createCenterPath(controlPoints);

        if (!hasValidCenterPath()) return false;

        if (!hasSafeEstimatedBlockCount(centerPath)) return false;

        int selectionMinY = getSelectionMinY(controlPoints);
        int selectionMaxY = getSelectionMaxY(controlPoints);

        if (!hasSafePreparedSelection(railSelectionPoints, selectionMinY, selectionMaxY)) return false;

        sendRailInfo("Rail Generator is preparing terrain data...");

        GeneratorUtils.createPolySelection(
                getPlayer(),
                railSelectionPoints,
                selectionMinY,
                selectionMaxY
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

        return hasValidCenterPath();
    }

    private boolean railScript_v_2_0() {
        if (!hasValidCenterPath())
            return false;

        Map<PositionKey, BlockState> railBlocks = buildRailBlocks(centerPath);

        if (railBlocks.size() > limits.maxBlockPlacements()) {
            sendRailError("Rail Generator would place " + railBlocks.size() + " blocks. The limit is "
                    + limits.maxBlockPlacements() + ". Split the rail into smaller selections.");
            return false;
        }

        sendRailInfo("Rail Generator queued " + railBlocks.size() + " block changes over "
                + centerPath.size() + " path points. Watch the action bar for progress.");

        queueRailBlockPlacements(railBlocks);
        finishOnMainThread();
        return true;
    }

    private void queueRailBlockPlacements(Map<PositionKey, BlockState> railBlocks) {
        List<Vector> positions = new ArrayList<>(limits.blockPlacementBatchSize());
        List<BlockState> blockStates = new ArrayList<>(limits.blockPlacementBatchSize());

        for (Map.Entry<PositionKey, BlockState> entry : railBlocks.entrySet()) {
            positions.add(entry.getKey().toVector());
            blockStates.add(entry.getValue());

            if (positions.size() == limits.blockPlacementBatchSize()) {
                setBlockStatesAtPositions(new ArrayList<>(positions), new ArrayList<>(blockStates));
                positions.clear();
                blockStates.clear();
            }
        }

        if (!positions.isEmpty())
            setBlockStatesAtPositions(positions, blockStates);
    }

    private void finishOnMainThread() {
        runOnMainThread(() -> {
            try {
                finish(blocks, getRestoreSelectionPoints());
            } finally {
                preparationFinishedCallback.run();
            }
        });
    }

    private boolean hasValidControlPoints() {
        if (controlPoints.size() < 2) {
            sendRailError("Rail Generator needs at least two points.");
            return false;
        }

        if (controlPoints.size() > limits.maxControlPoints()) {
            sendRailError("Rail Generator has too many points. Please use fewer points.");
            return false;
        }

        return true;
    }

    private boolean hasValidCenterPath() {
        if (centerPath.size() < 2) {
            sendRailError("Rail Generator could not create a valid rail path. Select at least two different blocks.");
            return false;
        }

        if (centerPath.size() > limits.maxPathPoints()) {
            sendRailError("Rail Generator path has " + centerPath.size() + " points. The limit is "
                    + limits.maxPathPoints() + ". Split the rail into smaller selections.");
            return false;
        }

        return true;
    }

    private boolean hasSafeEstimatedBlockCount(List<Vector> path) {
        long estimatedBlocks = (long) path.size() * getRailLaneCount() * 5L;

        if (estimatedBlocks <= limits.maxBlockPlacements())
            return true;

        sendRailError("Rail Generator would likely place too many blocks. Split the rail into smaller selections.");
        return false;
    }

    private boolean hasSafePreparedSelection(List<Vector> selectionPoints, int minY, int maxY) {
        if (selectionPoints.size() < 2) {
            sendRailError("Rail Generator could not create a safe preparation selection.");
            return false;
        }

        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (Vector point : selectionPoints) {
            minX = Math.min(minX, point.getBlockX());
            minZ = Math.min(minZ, point.getBlockZ());
            maxX = Math.max(maxX, point.getBlockX());
            maxZ = Math.max(maxZ, point.getBlockZ());
        }

        long width = (long) maxX - minX + 1L;
        long height = (long) maxY - minY + 1L + PREPARE_SELECTION_EXPANSION * 2L;
        long length = (long) maxZ - minZ + 1L;
        long volume = width * height * length;

        if (width > limits.maxPreparedRegionAxisLength() || length > limits.maxPreparedRegionAxisLength()) {
            sendRailError("Rail Generator selection is too wide to prepare safely. Split the rail into smaller selections.");
            return false;
        }

        if (volume > limits.maxPreparedRegionVolume()) {
            sendRailError("Rail Generator selection would prepare " + volume + " blocks. The limit is "
                    + limits.maxPreparedRegionVolume() + ". Split the rail into smaller selections.");
            return false;
        }

        return true;
    }

    private void sendRailInfo(String message) {
        sendRailMessage(Component.text(message, NamedTextColor.YELLOW));
    }

    private void sendRailError(String message) {
        sendRailMessage(Component.text(message, NamedTextColor.RED));
    }

    private void sendRailMessage(Component message) {
        runOnMainThread(() -> getPlayer().sendMessage(message));
    }

    private void runOnMainThread(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
            return;
        }

        Bukkit.getScheduler().runTask(BuildTeamTools.getInstance(), runnable);
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

        List<Vector> shiftedPoints = GeneratorUtils.shiftPoints(selectionLine, getSelectionPadding(), true);

        if (shiftedPoints == null || shiftedPoints.size() < 3)
            return GeneratorUtils.createBoundsSelectionPoints(points, getSelectionPadding());

        return shiftedPoints;
    }

    private int getSelectionPadding() {
        int sideLaneCount = (getRailLaneCount() - 1) / 2;
        return SELECTION_PADDING + (getRailLaneSpacing() * sideLaneCount) + 2;
    }

    private List<Vector> createCenterPath(List<Vector> points) {
        return GeneratorUtils.removeOrthogonalCorners(GeneratorUtils.createShortestBlockPath(points));
    }

    private Map<PositionKey, BlockState> buildRailBlocks(List<Vector> path) {
        Map<PositionKey, BlockState> railBlocks = new LinkedHashMap<>();
        List<List<Vector>> railCenterPaths = createRailCenterPaths(path);
        Set<PositionKey> centerPositions = getCenterPositions(railCenterPaths);
        Set<ColumnKey> centerColumns = getCenterColumns(railCenterPaths);
        Map<ColumnKey, RailSideBlock> sideBlocks = new LinkedHashMap<>();

        for (List<Vector> railCenterPath : railCenterPaths) {
            for (int index = 0; index < railCenterPath.size(); index++) {
                Vector center = railCenterPath.get(index);

                for (RailSidePlacement sidePlacement : getSidePlacements(railCenterPath, index))
                    addSideBlock(sideBlocks, center, sidePlacement, centerPositions, centerColumns);
            }
        }

        for (RailSideBlock sideBlock : sideBlocks.values())
            railBlocks.put(sideBlock.key(), createAnvilBlockState(resolveSideBlockFacing(sideBlock, sideBlocks)));

        for (List<Vector> railCenterPath : railCenterPaths)
            for (Vector center : railCenterPath)
                railBlocks.put(PositionKey.from(center), createCenterBlockState(center));

        return railBlocks;
    }

    private List<List<Vector>> createRailCenterPaths(List<Vector> path) {
        int railLaneCount = getRailLaneCount();

        if (railLaneCount <= 1)
            return List.of(path);

        List<List<Vector>> railCenterPaths = new ArrayList<>();
        int sideLaneCount = (railLaneCount - 1) / 2;
        int laneSpacing = getRailLaneSpacing();

        for (int laneIndex = sideLaneCount; laneIndex >= 1; laneIndex--) {
            List<Vector> leftLane = createShiftedRailLane(laneIndex * laneSpacing, 1);

            if (leftLane.size() >= 2)
                railCenterPaths.add(leftLane);
        }

        railCenterPaths.add(path);

        for (int laneIndex = 1; laneIndex <= sideLaneCount; laneIndex++) {
            List<Vector> rightLane = createShiftedRailLane(laneIndex * laneSpacing, -1);

            if (rightLane.size() >= 2)
                railCenterPaths.add(rightLane);
        }

        return railCenterPaths;
    }

    private int getRailLaneCount() {
        return DEFAULT_RAIL_LANE_COUNT;
    }

    private int getRailLaneSpacing() {
        return DEFAULT_RAIL_LANE_SPACING;
    }

    private List<Vector> createShiftedRailLane(int distance, int sideSign) {
        List<List<Vector>> shiftedLines = GeneratorUtils.shiftPointsAll(controlPoints, distance);
        List<Vector> candidatePoints = flattenShiftedLines(shiftedLines);

        if (candidatePoints.isEmpty())
            return Collections.emptyList();

        List<Vector> shiftedControlPoints = createShiftedControlPoints(candidatePoints, distance, sideSign);

        if (shiftedControlPoints.size() < 2)
            return Collections.emptyList();

        List<Vector> shiftedPath = createCenterPath(shiftedControlPoints);

        if (shiftedPath.size() < 2)
            return Collections.emptyList();

        adjustPathToTerrain(shiftedPath);
        return shiftedPath;
    }

    private List<Vector> flattenShiftedLines(List<List<Vector>> shiftedLines) {
        if (shiftedLines == null || shiftedLines.isEmpty())
            return Collections.emptyList();

        List<Vector> candidatePoints = new ArrayList<>();

        for (List<Vector> shiftedLine : shiftedLines) {
            if (shiftedLine == null || shiftedLine.isEmpty())
                continue;

            for (Vector point : shiftedLine) {
                if (point != null)
                    candidatePoints.add(point);
            }
        }

        return candidatePoints;
    }

    private List<Vector> createShiftedControlPoints(List<Vector> candidatePoints, int distance, int sideSign) {
        List<Vector> shiftedControlPoints = new ArrayList<>();

        for (int index = 0; index < controlPoints.size(); index++) {
            Vector basePoint = controlPoints.get(index);
            Vector direction = getControlPointDirection(index);

            if (direction.lengthSquared() == 0)
                continue;

            direction.normalize();

            Vector normal = new Vector(-direction.getZ(), 0, direction.getX());

            if (sideSign < 0)
                normal.multiply(-1);

            Vector shiftedPoint = getBestShiftedPoint(basePoint, normal, candidatePoints, distance);
            addIfDifferentFromPrevious(shiftedControlPoints, shiftedPoint);
        }

        return shiftedControlPoints;
    }

    private Vector getControlPointDirection(int index) {
        if (controlPoints.size() < 2)
            return new Vector(0, 0, 0);

        if (index == 0)
            return getHorizontalDirection(controlPoints.get(0), controlPoints.get(1));

        if (index == controlPoints.size() - 1)
            return getHorizontalDirection(controlPoints.get(index - 1), controlPoints.get(index));

        Vector previousDirection = getHorizontalDirection(controlPoints.get(index - 1), controlPoints.get(index));
        Vector nextDirection = getHorizontalDirection(controlPoints.get(index), controlPoints.get(index + 1));
        Vector combinedDirection = previousDirection.add(nextDirection);

        if (combinedDirection.lengthSquared() != 0)
            return combinedDirection;

        if (nextDirection.lengthSquared() != 0)
            return nextDirection;

        return previousDirection;
    }

    private Vector getHorizontalDirection(Vector from, Vector to) {
        return new Vector(
                to.getBlockX() - from.getBlockX(),
                0,
                to.getBlockZ() - from.getBlockZ()
        );
    }

    private Vector getBestShiftedPoint(Vector basePoint, Vector normal, List<Vector> candidatePoints, int distance) {
        Vector idealPoint = getIdealShiftedPoint(basePoint, normal, distance);
        Vector bestPoint = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        for (Vector candidatePoint : candidatePoints) {
            double signedOffset = getSignedOffset(basePoint, candidatePoint, normal);

            if (signedOffset < 0.5D)
                continue;

            double distanceSquared = getHorizontalDistanceSquared(candidatePoint, idealPoint);

            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                bestPoint = candidatePoint;
            }
        }

        double maxCandidateDistanceSquared = Math.max(16D, distance * distance * 2.25D);

        if (bestPoint == null || bestDistanceSquared > maxCandidateDistanceSquared)
            return idealPoint;

        return new Vector(bestPoint.getBlockX(), basePoint.getBlockY(), bestPoint.getBlockZ());
    }

    private Vector getIdealShiftedPoint(Vector basePoint, Vector normal, int distance) {
        return new Vector(
                basePoint.getBlockX() + (int) Math.round(normal.getX() * distance),
                basePoint.getBlockY(),
                basePoint.getBlockZ() + (int) Math.round(normal.getZ() * distance)
        );
    }

    private double getSignedOffset(Vector basePoint, Vector candidatePoint, Vector normal) {
        double offsetX = candidatePoint.getX() - basePoint.getX();
        double offsetZ = candidatePoint.getZ() - basePoint.getZ();

        return offsetX * normal.getX() + offsetZ * normal.getZ();
    }

    private void addIfDifferentFromPrevious(List<Vector> points, Vector point) {
        if (points.isEmpty()) {
            points.add(point);
            return;
        }

        Vector previousPoint = points.get(points.size() - 1);

        if (previousPoint.getBlockX() == point.getBlockX() && previousPoint.getBlockZ() == point.getBlockZ())
            return;

        points.add(point);
    }

    private double getHorizontalDistanceSquared(Vector first, Vector second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();

        return dx * dx + dz * dz;
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

    private Set<ColumnKey> getCenterColumns(List<List<Vector>> railCenterPaths) {
        Set<ColumnKey> centerColumns = new HashSet<>();

        for (List<Vector> railCenterPath : railCenterPaths)
            for (Vector center : railCenterPath)
                centerColumns.add(ColumnKey.from(PositionKey.from(center)));

        return centerColumns;
    }

    private Set<PositionKey> getCenterPositions(List<List<Vector>> railCenterPaths) {
        Set<PositionKey> centerPositions = new HashSet<>();

        for (List<Vector> railCenterPath : railCenterPaths)
            for (Vector center : railCenterPath)
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

    private void adjustPathToTerrain(List<Vector> path) {
        if (blocks == null || path.isEmpty()) return;

        for (Vector point : path)
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

    private record RailLimits(
            int maxControlPoints,
            int maxPathPoints,
            int maxBlockPlacements,
            long maxPreparedRegionVolume,
            int maxPreparedRegionAxisLength,
            int blockPlacementBatchSize
    ) {

        private static RailLimits fromConfig() {
            FileConfiguration config = BuildTeamTools.getInstance().getConfig();

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
