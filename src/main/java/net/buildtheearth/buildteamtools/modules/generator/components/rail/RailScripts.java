package net.buildtheearth.buildteamtools.modules.generator.components.rail;

import com.alpsbte.alpslib.utils.GeneratorUtils;
import com.cryptomorin.xseries.XMaterial;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.buildtheearth.buildteamtools.BuildTeamTools;
import net.buildtheearth.buildteamtools.modules.generator.model.GeneratorComponent;
import net.buildtheearth.buildteamtools.modules.generator.model.Script;
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
    private static final Direction DEFAULT_FACING = Direction.EAST;

    private static final XMaterial[] CENTER_MATERIALS = new XMaterial[]{
            XMaterial.DEAD_FIRE_CORAL_BLOCK,
            XMaterial.STONE,
            XMaterial.COBBLESTONE
    };

    private Block[][][] blocks;
    private List<Vector> controlPoints = new ArrayList<>();
    private List<Vector> centerPath = new ArrayList<>();

    public RailScripts(Player player, GeneratorComponent generatorComponent) {
        super(player, generatorComponent);

        Bukkit.getScheduler().runTaskAsynchronously(BuildTeamTools.getInstance(), () -> {
            try {
                prepareSession();
                railScript_v_2_0();
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(BuildTeamTools.getInstance(), () -> getGeneratorComponent().sendError(getPlayer()));
                exception.printStackTrace();
            }
        });
    }

    private void prepareSession() {
        controlPoints = getControlPoints();

        if (!hasValidControlPoints()) return;

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
    }

    private void railScript_v_2_0() {
        if (!hasValidControlPoints()) return;

        if (centerPath.size() < 2) {
            getPlayer().sendMessage("§cRail Generator could not create a valid rail path.");
            return;
        }

        if (centerPath.size() > MAX_PATH_POINTS) {
            getPlayer().sendMessage("§cRail Generator path is too large. Please use a smaller selection.");
            return;
        }

        Map<PositionKey, BlockState> railBlocks = buildRailBlocks(centerPath);

        if (railBlocks.size() > MAX_BLOCK_PLACEMENTS) {
            getPlayer().sendMessage("§cRail Generator would place too many blocks. Please use a smaller selection.");
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
            getPlayer().sendMessage("§cRail Generator needs at least two points.");
            return false;
        }

        if (controlPoints.size() > MAX_CONTROL_POINTS) {
            getPlayer().sendMessage("§cRail Generator has too many points. Please use fewer points.");
            return false;
        }

        return true;
    }

    private List<Vector> getControlPoints() {
        List<Vector> selectionPoints = GeneratorUtils.getSelectionPointsFromRegion(getRegion());

        if (selectionPoints == null) return Collections.emptyList();

        return copyPoints(selectionPoints);
    }

    private List<Vector> createRailSelectionPoints(List<Vector> points) {
        List<Vector> selectionLine = new ArrayList<>(points);

        if (selectionLine.size() >= 2)
            selectionLine = GeneratorUtils.extendPolyLine(selectionLine);

        List<Vector> shiftedPoints = GeneratorUtils.shiftPoints(selectionLine, SELECTION_PADDING, true);

        if (shiftedPoints == null || shiftedPoints.size() < 3) return createBoundsSelectionPoints(points);

        return shiftedPoints;
    }

    private List<Vector> createCenterPath(List<Vector> points) {
        return removeOrthogonalCorners(createShortestPath(points));
    }

    private List<Vector> createShortestPath(List<Vector> points) {
        List<Vector> path = new ArrayList<>();

        if (points.isEmpty()) return path;

        path.add(toBlockVector(points.get(0)));

        for (int index = 0; index < points.size() - 1; index++)
            appendShortestLine(path, points.get(index), points.get(index + 1));

        return path;
    }

    private void appendShortestLine(List<Vector> path, Vector start, Vector end) {
        int deltaX = end.getBlockX() - start.getBlockX();
        int deltaY = end.getBlockY() - start.getBlockY();
        int deltaZ = end.getBlockZ() - start.getBlockZ();
        int horizontalSteps = Math.max(Math.abs(deltaX), Math.abs(deltaZ));

        if (horizontalSteps == 0) {
            replaceLastPoint(path, end);
            return;
        }

        for (int step = 1; step <= horizontalSteps; step++) {
            double progress = step / (double) horizontalSteps;
            path.add(new Vector(
                    start.getBlockX() + (int) Math.round(deltaX * progress),
                    start.getBlockY() + (int) Math.round(deltaY * progress),
                    start.getBlockZ() + (int) Math.round(deltaZ * progress)
            ));
        }
    }

    private List<Vector> removeOrthogonalCorners(List<Vector> path) {
        List<Vector> result = new ArrayList<>();

        for (int index = 0; index < path.size(); index++) {
            if (index > 0 && index < path.size() - 1 && isOrthogonalCorner(path.get(index - 1), path.get(index), path.get(index + 1))) continue;

            result.add(path.get(index));
        }

        return result;
    }

    private boolean isOrthogonalCorner(Vector previous, Vector current, Vector next) {
        RailStep previousStep = getStep(previous, current);
        RailStep nextStep = getStep(current, next);

        return previousStep != null
                && nextStep != null
                && previousStep.dx() * nextStep.dx() + previousStep.dz() * nextStep.dz() == 0
                && previousStep.dx() != nextStep.dx()
                && previousStep.dz() != nextStep.dz();
    }

    private Map<PositionKey, BlockState> buildRailBlocks(List<Vector> path) {
        Map<PositionKey, BlockState> railBlocks = new LinkedHashMap<>();
        Set<PositionKey> centerPositions = getCenterPositions(path);
        Set<ColumnKey> centerColumns = getCenterColumns(path);
        Map<PositionKey, RailSideBlock> sideBlocks = buildSideBlocks(path, centerPositions, centerColumns);
        Map<ColumnKey, RailSideBlock> sideColumns = createSideColumnMap(sideBlocks);

        for (RailSideBlock sideBlock : sideBlocks.values()) {
            if (!centerColumns.contains(ColumnKey.from(sideBlock.key())))
                railBlocks.put(sideBlock.key(), createAnvilBlockState(resolveSideBlockFacing(sideBlock, sideColumns)));
        }

        for (Vector center : path)
            railBlocks.put(PositionKey.from(center), createCenterBlockState(center));

        return railBlocks;
    }

    private Map<PositionKey, RailSideBlock> buildSideBlocks(
            List<Vector> path,
            Set<PositionKey> centerPositions,
            Set<ColumnKey> centerColumns
    ) {
        Map<PositionKey, RailSideBlock> exactSideBlocks = new LinkedHashMap<>();

        for (int index = 0; index < path.size(); index++) {
            for (RailSidePlacement sidePlacement : getSidePlacements(path, index))
                addSideBlock(exactSideBlocks, sidePlacement, centerPositions, centerColumns);
        }

        Map<PositionKey, RailSideBlock> sideBlocks = selectBestSideBlockPerColumn(exactSideBlocks, centerPositions);
        repairMissingSideBlocks(path, sideBlocks, centerPositions, centerColumns);

        return sideBlocks;
    }

    private List<RailSidePlacement> getSidePlacements(List<Vector> path, int index) {
        List<RailSidePlacement> placements = new ArrayList<>();
        Vector center = path.get(index);
        RailStep previousStep = index > 0 ? getStep(path.get(index - 1), center) : null;
        RailStep nextStep = index < path.size() - 1 ? getStep(center, path.get(index + 1)) : null;

        addSidePlacements(placements, center, getRailStep(path, index, new RailStep(1, 0)));

        if (previousStep != null)
            addSidePlacements(placements, center, previousStep);

        if (nextStep != null)
            addSidePlacements(placements, center, nextStep);

        if (previousStep != null && nextStep != null) {
            int dx = Integer.compare(previousStep.dx() + nextStep.dx(), 0);
            int dz = Integer.compare(previousStep.dz() + nextStep.dz(), 0);

            if (dx != 0 || dz != 0)
                addSidePlacements(placements, center, new RailStep(dx, dz));
        }

        return placements;
    }

    private void addSidePlacements(List<RailSidePlacement> placements, Vector center, RailStep step) {
        int x = center.getBlockX();
        int y = center.getBlockY();
        int z = center.getBlockZ();

        if (step.dx() != 0 && step.dz() != 0) {
            addSidePlacement(placements, new Vector(x + step.dx(), y, z), GeneratorUtils.getFacing(0, step.dz(), DEFAULT_FACING));
            addSidePlacement(placements, new Vector(x, y, z + step.dz()), GeneratorUtils.getFacing(step.dx(), 0, DEFAULT_FACING));
        } else if (step.dx() != 0) {
            Direction facing = GeneratorUtils.getFacing(step.dx(), 0, DEFAULT_FACING);
            addSidePlacement(placements, new Vector(x, y, z + 1), facing);
            addSidePlacement(placements, new Vector(x, y, z - 1), facing);
        } else {
            Direction facing = GeneratorUtils.getFacing(0, step.dz(), DEFAULT_FACING);
            addSidePlacement(placements, new Vector(x + 1, y, z), facing);
            addSidePlacement(placements, new Vector(x - 1, y, z), facing);
        }
    }

    private void addSidePlacement(List<RailSidePlacement> placements, Vector position, Direction facing) {
        PositionKey key = PositionKey.from(position);

        for (RailSidePlacement placement : placements) {
            if (PositionKey.from(placement.position()).equals(key)) return;
        }

        placements.add(new RailSidePlacement(position, facing));
    }

    private void addSideBlock(
            Map<PositionKey, RailSideBlock> sideBlocks,
            RailSidePlacement sidePlacement,
            Set<PositionKey> centerPositions,
            Set<ColumnKey> centerColumns
    ) {
        PositionKey key = PositionKey.from(sidePlacement.position());

        if (centerPositions.contains(key) || centerColumns.contains(ColumnKey.from(key))) return;

        sideBlocks
                .computeIfAbsent(key, ignored -> new RailSideBlock(key))
                .addFacing(sidePlacement.facing());
    }

    private Map<PositionKey, RailSideBlock> selectBestSideBlockPerColumn(
            Map<PositionKey, RailSideBlock> exactSideBlocks,
            Set<PositionKey> centerPositions
    ) {
        Map<ColumnKey, RailSideBlock> selectedColumns = new LinkedHashMap<>();

        for (RailSideBlock sideBlock : exactSideBlocks.values()) {
            ColumnKey columnKey = ColumnKey.from(sideBlock.key());
            RailSideBlock selectedBlock = selectedColumns.get(columnKey);

            if (selectedBlock == null || isBetterSideBlock(sideBlock, selectedBlock, centerPositions))
                selectedColumns.put(columnKey, sideBlock);
        }

        Map<PositionKey, RailSideBlock> sideBlocks = new LinkedHashMap<>();

        for (RailSideBlock sideBlock : selectedColumns.values())
            sideBlocks.put(sideBlock.key(), sideBlock);

        return sideBlocks;
    }

    private boolean isBetterSideBlock(
            RailSideBlock candidate,
            RailSideBlock selectedBlock,
            Set<PositionKey> centerPositions
    ) {
        int candidateCoverage = getSideBlockCoverageScore(candidate.key(), centerPositions);
        int selectedCoverage = getSideBlockCoverageScore(selectedBlock.key(), centerPositions);

        return candidateCoverage > selectedCoverage
                || candidateCoverage == selectedCoverage && candidate.getSupportScore() > selectedBlock.getSupportScore();
    }

    private int getSideBlockCoverageScore(PositionKey sideBlockKey, Set<PositionKey> centerPositions) {
        int score = 0;

        for (PositionKey centerPosition : centerPositions)
            if (isAdjacent(sideBlockKey, centerPosition))
                score++;

        return score;
    }

    private void repairMissingSideBlocks(
            List<Vector> path,
            Map<PositionKey, RailSideBlock> sideBlocks,
            Set<PositionKey> centerPositions,
            Set<ColumnKey> centerColumns
    ) {
        Map<ColumnKey, RailSideBlock> sideColumns = createSideColumnMap(sideBlocks);

        for (int index = 0; index < path.size(); index++) {
            Vector center = path.get(index);
            int sideBlockCount = getAdjacentSideBlockCount(center, sideBlocks);

            if (sideBlockCount >= 2) continue;

            List<RailSidePlacement> repairPlacements = getSidePlacements(path, index);
            repairPlacements.addAll(getFallbackSidePlacements(center, getRailStep(path, index, new RailStep(1, 0))));

            for (RailSidePlacement sidePlacement : repairPlacements) {
                sideBlockCount = repairSidePlacement(
                        sidePlacement,
                        PositionKey.from(center),
                        sideBlocks,
                        sideColumns,
                        centerPositions,
                        centerColumns,
                        sideBlockCount
                );

                if (sideBlockCount >= 2) break;
            }
        }
    }

    private List<RailSidePlacement> getFallbackSidePlacements(Vector center, RailStep step) {
        List<RailSidePlacement> placements = new ArrayList<>();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;

                addSidePlacement(
                        placements,
                        new Vector(center.getBlockX() + dx, center.getBlockY(), center.getBlockZ() + dz),
                        getFallbackFacing(dx, dz, step)
                );
            }
        }

        return placements;
    }

    private Direction getFallbackFacing(int offsetX, int offsetZ, RailStep step) {
        if (Math.abs(step.dx()) >= Math.abs(step.dz()) && step.dx() != 0) return GeneratorUtils.getFacing(step.dx(), 0, DEFAULT_FACING);

        if (step.dz() != 0) return GeneratorUtils.getFacing(0, step.dz(), DEFAULT_FACING);

        if (Math.abs(offsetX) >= Math.abs(offsetZ) && offsetX != 0) return GeneratorUtils.getFacing(offsetX, 0, DEFAULT_FACING);

        return GeneratorUtils.getFacing(0, offsetZ, DEFAULT_FACING);
    }

    private int repairSidePlacement(
            RailSidePlacement sidePlacement,
            PositionKey centerKey,
            Map<PositionKey, RailSideBlock> sideBlocks,
            Map<ColumnKey, RailSideBlock> sideColumns,
            Set<PositionKey> centerPositions,
            Set<ColumnKey> centerColumns,
            int sideBlockCount
    ) {
        PositionKey key = PositionKey.from(sidePlacement.position());

        if (centerPositions.contains(key) || centerColumns.contains(ColumnKey.from(key)) || sideBlocks.containsKey(key)) return sideBlockCount;

        ColumnKey columnKey = ColumnKey.from(key);
        RailSideBlock existingColumnBlock = sideColumns.get(columnKey);
        boolean replacingAdjacentBlock = existingColumnBlock != null && isAdjacent(existingColumnBlock.key(), centerKey);

        if (existingColumnBlock != null) {
            if (!canReplaceSideBlock(existingColumnBlock.key(), centerPositions, sideBlocks)) return sideBlockCount;

            sideBlocks.remove(existingColumnBlock.key());
        }

        RailSideBlock sideBlock = new RailSideBlock(key);
        sideBlock.addFacing(sidePlacement.facing());
        sideBlocks.put(key, sideBlock);
        sideColumns.put(columnKey, sideBlock);
        return sideBlockCount + (replacingAdjacentBlock ? 0 : 1);
    }

    private boolean canReplaceSideBlock(
            PositionKey sideBlockKey,
            Set<PositionKey> centerPositions,
            Map<PositionKey, RailSideBlock> sideBlocks
    ) {
        for (PositionKey centerPosition : centerPositions) {
            if (isAdjacent(sideBlockKey, centerPosition) && getAdjacentSideBlockCount(centerPosition.toVector(), sideBlocks) <= 2) return false;
        }

        return true;
    }

    private int getAdjacentSideBlockCount(Vector center, Map<PositionKey, RailSideBlock> sideBlocks) {
        int count = 0;

        for (PositionKey sideBlockKey : sideBlocks.keySet()) {
            if (isAdjacent(sideBlockKey, PositionKey.from(center)))
                count++;
        }

        return count;
    }

    private boolean isAdjacent(PositionKey first, PositionKey second) {
        int dx = Math.abs(first.x() - second.x());
        int dy = Math.abs(first.y() - second.y());
        int dz = Math.abs(first.z() - second.z());

        return dx <= 1 && dy <= 1 && dz <= 1 && (dx != 0 || dz != 0);
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

    private Map<ColumnKey, RailSideBlock> createSideColumnMap(Map<PositionKey, RailSideBlock> sideBlocks) {
        Map<ColumnKey, RailSideBlock> sideColumns = new LinkedHashMap<>();

        for (RailSideBlock sideBlock : sideBlocks.values())
            sideColumns.put(ColumnKey.from(sideBlock.key()), sideBlock);

        return sideColumns;
    }

    private Direction resolveSideBlockFacing(RailSideBlock sideBlock, Map<ColumnKey, RailSideBlock> sideColumns) {
        PositionKey key = sideBlock.key();
        boolean east = sideColumns.containsKey(ColumnKey.of(key.x() + 1, key.z()));
        boolean west = sideColumns.containsKey(ColumnKey.of(key.x() - 1, key.z()));
        boolean south = sideColumns.containsKey(ColumnKey.of(key.x(), key.z() + 1));
        boolean north = sideColumns.containsKey(ColumnKey.of(key.x(), key.z() - 1));
        int xConnections = (east ? 1 : 0) + (west ? 1 : 0);
        int zConnections = (south ? 1 : 0) + (north ? 1 : 0);
        Direction preferredFacing = sideBlock.getPreferredFacing();

        if (xConnections > zConnections) return resolveAxisFacing(preferredFacing, Direction.EAST, Direction.WEST, east, west);

        if (zConnections > xConnections) return resolveAxisFacing(preferredFacing, Direction.SOUTH, Direction.NORTH, south, north);

        return preferredFacing;
    }

    private Direction resolveAxisFacing(
            Direction preferredFacing,
            Direction positiveFacing,
            Direction negativeFacing,
            boolean hasPositiveNeighbor,
            boolean hasNegativeNeighbor
    ) {
        if (preferredFacing == positiveFacing && hasPositiveNeighbor || preferredFacing == negativeFacing && hasNegativeNeighbor) return preferredFacing;

        if (hasPositiveNeighbor && !hasNegativeNeighbor) return positiveFacing;

        if (hasNegativeNeighbor && !hasPositiveNeighbor) return negativeFacing;

        return preferredFacing == negativeFacing ? negativeFacing : positiveFacing;
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

    private boolean hasMissingControlPointHeights(List<Vector> points) {
        for (Vector point : points)
            if (point.getBlockY() != 0) return false;

        return true;
    }

    private List<Vector> getRestoreSelectionPoints() {
        return controlPoints;
    }

    private int getSelectionMinY(List<Vector> points) {
        int minY = getRegion() != null ? getRegion().getMinimumY() : getMinPointY(points);
        return Math.max(getPlayer().getWorld().getMinHeight(), minY - SELECTION_VERTICAL_PADDING);
    }

    private int getSelectionMaxY(List<Vector> points) {
        int maxY = getRegion() != null ? getRegion().getMaximumY() : getMaxPointY(points);
        return Math.min(getPlayer().getWorld().getMaxHeight() - 1, maxY + SELECTION_VERTICAL_PADDING);
    }

    private List<Vector> createBoundsSelectionPoints(List<Vector> points) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (Vector point : points) {
            minX = Math.min(minX, point.getBlockX() - SELECTION_PADDING);
            minZ = Math.min(minZ, point.getBlockZ() - SELECTION_PADDING);
            maxX = Math.max(maxX, point.getBlockX() + SELECTION_PADDING);
            maxZ = Math.max(maxZ, point.getBlockZ() + SELECTION_PADDING);
        }

        return List.of(
                new Vector(minX, 0, minZ),
                new Vector(maxX, 0, minZ),
                new Vector(maxX, 0, maxZ),
                new Vector(minX, 0, maxZ)
        );
    }

    private int getMinPointY(List<Vector> points) {
        int minY = Integer.MAX_VALUE;

        for (Vector point : points)
            minY = Math.min(minY, point.getBlockY());

        return minY == Integer.MAX_VALUE ? getPlayer().getWorld().getMinHeight() : minY;
    }

    private int getMaxPointY(List<Vector> points) {
        int maxY = Integer.MIN_VALUE;

        for (Vector point : points)
            maxY = Math.max(maxY, point.getBlockY());

        return maxY == Integer.MIN_VALUE ? getPlayer().getWorld().getMaxHeight() - 1 : maxY;
    }

    private List<Vector> copyPoints(List<Vector> points) {
        List<Vector> copiedPoints = new ArrayList<>();

        for (Vector point : points)
            copiedPoints.add(toBlockVector(point));

        return copiedPoints;
    }

    private BlockState createCenterBlockState(Vector position) {
        int index = Math.floorMod(
                position.getBlockX() * 31 + position.getBlockY() * 23 + position.getBlockZ() * 17,
                CENTER_MATERIALS.length
        );

        return GeneratorUtils.getBlockState(CENTER_MATERIALS[index]);
    }

    private BlockState createAnvilBlockState(Direction direction) {
        return GeneratorUtils.getBlockStateWithFacing(BlockTypes.ANVIL, direction);
    }

    private Vector toBlockVector(Vector vector) {
        return new Vector(
                vector.getBlockX(),
                vector.getBlockY(),
                vector.getBlockZ()
        );
    }

    private void replaceLastPoint(List<Vector> points, Vector point) {
        Vector blockPoint = toBlockVector(point);

        if (points.isEmpty()) {
            points.add(blockPoint);
            return;
        }

        points.set(points.size() - 1, blockPoint);
    }

    private record RailStep(int dx, int dz) {
    }

    private record RailSidePlacement(Vector position, Direction facing) {
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

        private int getSupportScore() {
            int score = 0;

            for (int facingScore : facingScores.values())
                score += facingScore;

            return score;
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
