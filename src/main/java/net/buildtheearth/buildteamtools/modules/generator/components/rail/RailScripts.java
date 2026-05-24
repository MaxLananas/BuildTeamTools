package net.buildtheearth.buildteamtools.modules.generator.components.rail;

import com.alpsbte.alpslib.utils.GeneratorUtils;
import com.cryptomorin.xseries.XMaterial;
import com.fastasyncworldedit.core.registry.state.PropertyKey;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.buildtheearth.buildteamtools.modules.generator.model.GeneratorComponent;
import net.buildtheearth.buildteamtools.modules.generator.model.Script;
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
    private static final int INVALID_SIDE_PAIR_PENALTY = 1_000_000;
    private static final int CENTER_COLLISION_PENALTY = 100_000;
    private static final int FALLBACK_SIDE_PAIR_PENALTY = 1_000;

    private static final XMaterial[] CENTER_MATERIALS = new XMaterial[]{
            XMaterial.DEAD_FIRE_CORAL_BLOCK,
            XMaterial.STONE,
            XMaterial.COBBLESTONE
    };

    private final List<Vector> customControlPoints;

    private Block[][][] blocks;
    private List<Vector> controlPoints = new ArrayList<>();
    private List<Vector> centerPath = new ArrayList<>();
    private List<Vector> railSelectionPoints = new ArrayList<>();

    public RailScripts(Player player, GeneratorComponent generatorComponent) {
        this(player, generatorComponent, null);
    }

    public RailScripts(Player player, GeneratorComponent generatorComponent, List<Vector> customControlPoints) {
        super(player, generatorComponent);
        this.customControlPoints = customControlPoints;

        Thread thread = new Thread(() -> {
            prepareSession();
            railScript_v_2_0();
        });
        thread.start();
    }

    private void prepareSession() {
        controlPoints = sanitizePoints(getControlPoints());

        if (!hasValidControlPoints())
            return;

        railSelectionPoints = createRailSelectionPoints(controlPoints);
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
        centerPath = createShortestPath(controlPoints);
    }

    private void railScript_v_2_0() {
        if (!hasValidControlPoints())
            return;

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
        if (customControlPoints != null && customControlPoints.size() >= 2)
            return copyPoints(customControlPoints);

        List<Vector> selectionPoints = GeneratorUtils.getSelectionPointsFromRegion(getRegion());

        if (selectionPoints == null)
            return Collections.emptyList();

        return copyPoints(selectionPoints);
    }

    private List<Vector> createRailSelectionPoints(List<Vector> points) {
        List<Vector> selectionLine = new ArrayList<>(points);

        if (selectionLine.size() >= 2)
            selectionLine = GeneratorUtils.extendPolyLine(selectionLine);

        List<Vector> shiftedPoints = GeneratorUtils.shiftPoints(selectionLine, SELECTION_PADDING, true);

        if (shiftedPoints == null || shiftedPoints.size() < 3)
            return createBoundsSelectionPoints(points);

        return shiftedPoints;
    }

    private List<Vector> createShortestPath(List<Vector> points) {
        List<Vector> path = new ArrayList<>();

        if (points.isEmpty())
            return path;

        addPointIfNew(path, points.get(0));

        for (int index = 0; index < points.size() - 1; index++)
            appendShortestLine(path, points.get(index), points.get(index + 1));

        return path;
    }

    private void appendShortestLine(List<Vector> path, Vector start, Vector end) {
        int startX = start.getBlockX();
        int startY = start.getBlockY();
        int startZ = start.getBlockZ();
        int deltaX = end.getBlockX() - startX;
        int deltaY = end.getBlockY() - startY;
        int deltaZ = end.getBlockZ() - startZ;

        int horizontalSteps = Math.max(Math.abs(deltaX), Math.abs(deltaZ));

        if (horizontalSteps == 0) {
            replaceLastPoint(path, end);
            return;
        }

        for (int step = 1; step <= horizontalSteps; step++) {
            double progress = step / (double) horizontalSteps;
            addPointIfNew(path, new Vector(
                    startX + (int) Math.round(deltaX * progress),
                    startY + (int) Math.round(deltaY * progress),
                    startZ + (int) Math.round(deltaZ * progress)
            ));
        }
    }

    private Map<PositionKey, BlockState> buildRailBlocks(List<Vector> path) {
        Map<PositionKey, BlockState> blockMap = new LinkedHashMap<>();
        Set<PositionKey> centerPositions = getCenterPositions(path);
        Set<ColumnKey> centerColumns = getCenterColumns(path);
        Map<PositionKey, RailSideBlock> sideBlocks = buildSideBlocks(path, centerPositions, centerColumns);

        for (RailSideBlock sideBlock : sideBlocks.values()) {
            if (centerColumns.contains(ColumnKey.from(sideBlock.key())))
                continue;

            blockMap.put(sideBlock.key(), createAnvilBlockState(resolveSideBlockFacing(sideBlock, sideBlocks)));
        }

        for (Vector center : path)
            blockMap.put(PositionKey.from(center), createCenterBlockState(center));

        return blockMap;
    }

    private List<RailSidePair> buildSidePairs(List<Vector> path, Set<PositionKey> centerPositions) {
        List<RailSidePair> sidePairs = new ArrayList<>();
        RailSidePair previousPair = null;

        for (int index = 0; index < path.size(); index++) {
            List<RailSidePair> candidates = getSidePairCandidates(path, centerPositions, index);
            RailSidePair selectedPair = selectSidePair(previousPair, candidates, centerPositions);

            sidePairs.add(selectedPair);
            previousPair = selectedPair;
        }

        return sidePairs;
    }

    private List<RailSidePair> getSidePairCandidates(
            List<Vector> path,
            Set<PositionKey> centerPositions,
            int index
    ) {
        List<RailSidePair> candidates = new ArrayList<>();
        Vector center = path.get(index);
        RailDirection previousDirection = getHorizontalDirection(path, index - 1, index);
        RailDirection nextDirection = getHorizontalDirection(path, index, index + 1);

        addSidePairIfNew(candidates, createSidePair(center, getRailDirection(path, index)));

        if (previousDirection != null)
            addSidePairIfNew(candidates, createSidePair(center, previousDirection));

        if (nextDirection != null)
            addSidePairIfNew(candidates, createSidePair(center, nextDirection));

        if (previousDirection != null && nextDirection != null) {
            addBlendedSidePair(candidates, center, previousDirection, nextDirection);
            addCornerSidePairs(candidates, center, centerPositions, previousDirection, nextDirection);
        }

        addFallbackSidePairs(candidates, center, getRailDirection(path, index), centerPositions);

        return candidates;
    }

    private RailSidePair selectSidePair(
            RailSidePair previousPair,
            List<RailSidePair> candidates,
            Set<PositionKey> centerPositions
    ) {
        RailSidePair selectedPair = candidates.get(0);
        int selectedScore = Integer.MAX_VALUE;

        for (RailSidePair candidate : candidates) {
            RailSidePair[] orientations = new RailSidePair[]{candidate, candidate.reversed()};

            for (RailSidePair orientedCandidate : orientations) {
                int score = getSidePairScore(previousPair, orientedCandidate, centerPositions);

                if (score < selectedScore) {
                    selectedScore = score;
                    selectedPair = orientedCandidate;
                }
            }
        }

        return selectedPair;
    }

    private int getSidePairScore(
            RailSidePair previousPair,
            RailSidePair candidate,
            Set<PositionKey> centerPositions
    ) {
        int score = getCenterCollisionPenalty(candidate, centerPositions);

        if (previousPair != null)
            score += getSidePairTransitionDistance(previousPair, candidate);

        score += candidate.fallbackPenalty();

        return score;
    }

    private int getCenterCollisionPenalty(RailSidePair candidate, Set<PositionKey> centerPositions) {
        if (candidate.hasDuplicatePosition())
            return INVALID_SIDE_PAIR_PENALTY;

        int penalty = 0;

        if (isCenterCollision(candidate.first(), centerPositions))
            penalty += CENTER_COLLISION_PENALTY;

        if (isCenterCollision(candidate.second(), centerPositions))
            penalty += CENTER_COLLISION_PENALTY;

        return penalty;
    }

    private boolean isCenterCollision(RailSidePlacement sidePlacement, Set<PositionKey> centerPositions) {
        return centerPositions.contains(PositionKey.from(sidePlacement.position()));
    }

    private boolean isCenterColumnCollision(
            PositionKey key,
            Set<PositionKey> centerPositions,
            Set<ColumnKey> centerColumns
    ) {
        return centerPositions.contains(key) || centerColumns.contains(ColumnKey.from(key));
    }

    private int getSidePairTransitionDistance(RailSidePair previousPair, RailSidePair candidate) {
        return getChebyshevDistance(previousPair.first().position(), candidate.first().position())
                + getChebyshevDistance(previousPair.second().position(), candidate.second().position());
    }

    private int getChebyshevDistance(Vector first, Vector second) {
        return Math.max(
                Math.max(
                        Math.abs(second.getBlockX() - first.getBlockX()),
                        Math.abs(second.getBlockY() - first.getBlockY())
                ),
                Math.abs(second.getBlockZ() - first.getBlockZ())
        );
    }

    private Map<PositionKey, RailSideBlock> buildSideBlocks(
            List<Vector> path,
            Set<PositionKey> centerPositions,
            Set<ColumnKey> centerColumns
    ) {
        Map<PositionKey, RailSideBlock> exactSideBlocks = new LinkedHashMap<>();

        for (List<RailSidePlacement> sideLane : buildSideLanes(path)) {
            for (RailSidePlacement sidePlacement : sideLane)
                addExactSideBlock(exactSideBlocks, centerPositions, centerColumns, sidePlacement);
        }

        List<RailSidePair> sidePairs = buildSidePairs(path, centerPositions);

        for (RailSidePair sidePair : sidePairs) {
            addExactSideBlock(exactSideBlocks, centerPositions, centerColumns, sidePair.first());
            addExactSideBlock(exactSideBlocks, centerPositions, centerColumns, sidePair.second());
        }

        Map<PositionKey, RailSideBlock> sideBlocks = selectBestSideBlockPerColumn(exactSideBlocks, centerPositions);
        repairMissingSideBlocks(path, sidePairs, sideBlocks, centerPositions, centerColumns);

        return sideBlocks;
    }

    private List<List<RailSidePlacement>> buildSideLanes(List<Vector> path) {
        List<RailSidePlacement> firstLane = new ArrayList<>();
        List<RailSidePlacement> secondLane = new ArrayList<>();

        for (int index = 0; index < path.size() - 1; index++) {
            RailDirection direction = getHorizontalDirection(path, index, index + 1);

            if (direction == null)
                continue;

            RailSidePair startPair = createSidePair(path.get(index), direction);
            RailSidePair endPair = createSidePair(path.get(index + 1), direction);

            boolean reverseSegment = !firstLane.isEmpty() && shouldReverseSideSegment(firstLane, secondLane, startPair);

            if (reverseSegment) {
                startPair = startPair.reversed();
                endPair = endPair.reversed();
            }

            appendSideLaneSegment(firstLane, startPair.first(), endPair.first());
            appendSideLaneSegment(secondLane, startPair.second(), endPair.second());
        }

        return List.of(firstLane, secondLane);
    }

    private boolean shouldReverseSideSegment(
            List<RailSidePlacement> firstLane,
            List<RailSidePlacement> secondLane,
            RailSidePair startPair
    ) {
        RailSidePlacement firstEnd = firstLane.get(firstLane.size() - 1);
        RailSidePlacement secondEnd = secondLane.get(secondLane.size() - 1);
        int normalDistance = getChebyshevDistance(firstEnd.position(), startPair.first().position())
                + getChebyshevDistance(secondEnd.position(), startPair.second().position());
        int reversedDistance = getChebyshevDistance(firstEnd.position(), startPair.second().position())
                + getChebyshevDistance(secondEnd.position(), startPair.first().position());

        return reversedDistance < normalDistance;
    }

    private void appendSideLaneSegment(
            List<RailSidePlacement> lane,
            RailSidePlacement start,
            RailSidePlacement end
    ) {
        if (lane.isEmpty()) {
            lane.add(start);
        } else {
            appendSideLine(lane, lane.get(lane.size() - 1), start);
        }

        appendSideLine(lane, lane.get(lane.size() - 1), end);
    }

    private void appendSideLine(
            List<RailSidePlacement> lane,
            RailSidePlacement start,
            RailSidePlacement end
    ) {
        Vector startPosition = start.position();
        Vector endPosition = end.position();
        int deltaX = endPosition.getBlockX() - startPosition.getBlockX();
        int deltaY = endPosition.getBlockY() - startPosition.getBlockY();
        int deltaZ = endPosition.getBlockZ() - startPosition.getBlockZ();
        int horizontalSteps = Math.max(Math.abs(deltaX), Math.abs(deltaZ));
        Direction facing = getLineFacing(deltaX, deltaZ, end.facing());

        if (horizontalSteps == 0) {
            replaceLastSidePlacement(lane, new RailSidePlacement(endPosition, facing));
            return;
        }

        for (int step = 1; step <= horizontalSteps; step++) {
            double progress = step / (double) horizontalSteps;
            addSidePlacementIfNew(lane, new RailSidePlacement(
                    new Vector(
                            startPosition.getBlockX() + (int) Math.round(deltaX * progress),
                            startPosition.getBlockY() + (int) Math.round(deltaY * progress),
                            startPosition.getBlockZ() + (int) Math.round(deltaZ * progress)
                    ),
                    facing
            ));
        }
    }

    private Direction getLineFacing(int deltaX, int deltaZ, Direction fallbackFacing) {
        if (Math.abs(deltaX) > Math.abs(deltaZ))
            return getXFacing(deltaX);

        if (Math.abs(deltaZ) > Math.abs(deltaX))
            return getZFacing(deltaZ);

        return fallbackFacing;
    }

    private void addSidePlacementIfNew(List<RailSidePlacement> lane, RailSidePlacement sidePlacement) {
        if (lane.isEmpty() || !isSameBlock(lane.get(lane.size() - 1).position(), sidePlacement.position()))
            lane.add(sidePlacement);
    }

    private void replaceLastSidePlacement(List<RailSidePlacement> lane, RailSidePlacement sidePlacement) {
        if (lane.isEmpty()) {
            lane.add(sidePlacement);
            return;
        }

        lane.set(lane.size() - 1, sidePlacement);
    }

    private void addExactSideBlock(
            Map<PositionKey, RailSideBlock> sideBlocks,
            Set<PositionKey> centerPositions,
            Set<ColumnKey> centerColumns,
            RailSidePlacement sidePlacement
    ) {
        PositionKey key = PositionKey.from(sidePlacement.position());

        if (isCenterColumnCollision(key, centerPositions, centerColumns))
            return;

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

            if (selectedBlock == null
                    || getSideBlockCoverageScore(sideBlock.key(), centerPositions) > getSideBlockCoverageScore(selectedBlock.key(), centerPositions)
                    || getSideBlockCoverageScore(sideBlock.key(), centerPositions) == getSideBlockCoverageScore(selectedBlock.key(), centerPositions)
                    && sideBlock.getSupportScore() > selectedBlock.getSupportScore())
                selectedColumns.put(columnKey, sideBlock);
        }

        Map<PositionKey, RailSideBlock> sideBlocks = new LinkedHashMap<>();

        for (RailSideBlock sideBlock : selectedColumns.values())
            sideBlocks.put(sideBlock.key(), sideBlock);

        return sideBlocks;
    }

    private int getSideBlockCoverageScore(PositionKey sideBlockKey, Set<PositionKey> centerPositions) {
        int score = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0)
                    continue;

                if (centerPositions.contains(PositionKey.of(
                        sideBlockKey.x() + dx,
                        sideBlockKey.y(),
                        sideBlockKey.z() + dz
                )))
                    score++;
            }
        }

        return score;
    }

    private void repairMissingSideBlocks(
            List<Vector> path,
            List<RailSidePair> sidePairs,
            Map<PositionKey, RailSideBlock> sideBlocks,
            Set<PositionKey> centerPositions,
            Set<ColumnKey> centerColumns
    ) {
        Map<ColumnKey, RailSideBlock> sideColumns = createSideColumnMap(sideBlocks);

        for (int index = 0; index < path.size(); index++) {
            Vector center = path.get(index);
            int sideBlockCount = getAdjacentSideBlockCount(center, sideBlocks);

            if (sideBlockCount >= 2)
                continue;

            sideBlockCount = repairSidePlacement(
                    sidePairs.get(index).first(),
                    sideBlocks,
                    sideColumns,
                    centerPositions,
                    centerColumns,
                    sideBlockCount
            );

            if (sideBlockCount >= 2)
                continue;

            sideBlockCount = repairSidePlacement(
                    sidePairs.get(index).second(),
                    sideBlocks,
                    sideColumns,
                    centerPositions,
                    centerColumns,
                    sideBlockCount
            );

            if (sideBlockCount >= 2)
                continue;

            for (RailSidePlacement sidePlacement : getFallbackSidePlacements(
                    center,
                    getRailDirection(path, index),
                    centerPositions,
                    centerColumns
            )) {
                sideBlockCount = repairSidePlacement(
                        sidePlacement,
                        sideBlocks,
                        sideColumns,
                        centerPositions,
                        centerColumns,
                        sideBlockCount
                );

                if (sideBlockCount >= 2)
                    break;
            }
        }
    }

    private int repairSidePlacement(
            RailSidePlacement sidePlacement,
            Map<PositionKey, RailSideBlock> sideBlocks,
            Map<ColumnKey, RailSideBlock> sideColumns,
            Set<PositionKey> centerPositions,
            Set<ColumnKey> centerColumns,
            int sideBlockCount
    ) {
        PositionKey key = PositionKey.from(sidePlacement.position());

        if (isCenterColumnCollision(key, centerPositions, centerColumns))
            return sideBlockCount;

        if (sideBlocks.containsKey(key))
            return sideBlockCount;

        ColumnKey columnKey = ColumnKey.from(key);

        RailSideBlock existingColumnBlock = sideColumns.get(columnKey);

        if (existingColumnBlock != null) {
            if (!canReplaceSideBlock(existingColumnBlock.key(), centerPositions, sideBlocks))
                return sideBlockCount;

            sideBlocks.remove(existingColumnBlock.key());
        }

        RailSideBlock sideBlock = new RailSideBlock(key);
        sideBlock.addFacing(sidePlacement.facing());
        sideBlocks.put(key, sideBlock);
        sideColumns.put(columnKey, sideBlock);
        return sideBlockCount + 1;
    }

    private boolean canReplaceSideBlock(
            PositionKey sideBlockKey,
            Set<PositionKey> centerPositions,
            Map<PositionKey, RailSideBlock> sideBlocks
    ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0)
                    continue;

                PositionKey centerKey = PositionKey.of(
                        sideBlockKey.x() + dx,
                        sideBlockKey.y(),
                        sideBlockKey.z() + dz
                );

                if (!centerPositions.contains(centerKey))
                    continue;

                if (getAdjacentSideBlockCount(centerKey.toVector(), sideBlocks) <= 2)
                    return false;
            }
        }

        return true;
    }

    private int getAdjacentSideBlockCount(Vector center, Map<PositionKey, RailSideBlock> sideBlocks) {
        int count = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0)
                    continue;

                if (sideBlocks.containsKey(PositionKey.of(
                        center.getBlockX() + dx,
                        center.getBlockY(),
                        center.getBlockZ() + dz
                )))
                    count++;
            }
        }

        return count;
    }

    private boolean isAdjacentOnSameHeight(Vector center, PositionKey key) {
        if (key.y() != center.getBlockY())
            return false;

        int dx = Math.abs(key.x() - center.getBlockX());
        int dz = Math.abs(key.z() - center.getBlockZ());

        return dx <= 1 && dz <= 1 && (dx != 0 || dz != 0);
    }

    private Map<ColumnKey, RailSideBlock> createSideColumnMap(Map<PositionKey, RailSideBlock> sideBlocks) {
        Map<ColumnKey, RailSideBlock> sideColumns = new LinkedHashMap<>();

        for (RailSideBlock sideBlock : sideBlocks.values())
            sideColumns.put(ColumnKey.from(sideBlock.key()), sideBlock);

        return sideColumns;
    }

    private Direction resolveSideBlockFacing(
            RailSideBlock sideBlock,
            Map<PositionKey, RailSideBlock> sideBlocks
    ) {
        PositionKey key = sideBlock.key();
        boolean east = sideBlocks.containsKey(PositionKey.of(key.x() + 1, key.y(), key.z()));
        boolean west = sideBlocks.containsKey(PositionKey.of(key.x() - 1, key.y(), key.z()));
        boolean south = sideBlocks.containsKey(PositionKey.of(key.x(), key.y(), key.z() + 1));
        boolean north = sideBlocks.containsKey(PositionKey.of(key.x(), key.y(), key.z() - 1));
        int xConnections = (east ? 1 : 0) + (west ? 1 : 0);
        int zConnections = (south ? 1 : 0) + (north ? 1 : 0);
        Direction preferredFacing = sideBlock.getPreferredFacing();

        if (xConnections > zConnections)
            return resolveXFacing(preferredFacing, east, west);

        if (zConnections > xConnections)
            return resolveZFacing(preferredFacing, south, north);

        return preferredFacing;
    }

    private Direction resolveXFacing(Direction preferredFacing, boolean east, boolean west) {
        if (preferredFacing == Direction.EAST && east)
            return Direction.EAST;

        if (preferredFacing == Direction.WEST && west)
            return Direction.WEST;

        if (east && !west)
            return Direction.EAST;

        if (west && !east)
            return Direction.WEST;

        return preferredFacing == Direction.WEST ? Direction.WEST : Direction.EAST;
    }

    private Direction resolveZFacing(Direction preferredFacing, boolean south, boolean north) {
        if (preferredFacing == Direction.SOUTH && south)
            return Direction.SOUTH;

        if (preferredFacing == Direction.NORTH && north)
            return Direction.NORTH;

        if (south && !north)
            return Direction.SOUTH;

        if (north && !south)
            return Direction.NORTH;

        return preferredFacing == Direction.NORTH ? Direction.NORTH : Direction.SOUTH;
    }

    private Set<PositionKey> getCenterPositions(List<Vector> path) {
        Set<PositionKey> centerPositions = new HashSet<>();

        for (Vector center : path)
            centerPositions.add(PositionKey.from(center));

        return centerPositions;
    }

    private Set<ColumnKey> getCenterColumns(List<Vector> path) {
        Set<ColumnKey> centerColumns = new HashSet<>();

        for (Vector center : path)
            centerColumns.add(ColumnKey.from(PositionKey.from(center)));

        return centerColumns;
    }

    private void addBlendedSidePair(
            List<RailSidePair> candidates,
            Vector center,
            RailDirection previousDirection,
            RailDirection nextDirection
    ) {
        int blendedX = Integer.compare(previousDirection.dx() + nextDirection.dx(), 0);
        int blendedZ = Integer.compare(previousDirection.dz() + nextDirection.dz(), 0);

        if (blendedX == 0 && blendedZ == 0)
            return;

        addSidePairIfNew(candidates, createSidePair(center, new RailDirection(blendedX, blendedZ)));
    }

    private void addCornerSidePairs(
            List<RailSidePair> candidates,
            Vector center,
            Set<PositionKey> centerPositions,
            RailDirection previousDirection,
            RailDirection nextDirection
    ) {
        RailSidePair previousPair = createSidePair(center, previousDirection);
        RailSidePair nextPair = createSidePair(center, nextDirection);
        RailSidePlacement[] previousPlacements = new RailSidePlacement[]{
                previousPair.first(),
                previousPair.second()
        };
        RailSidePlacement[] nextPlacements = new RailSidePlacement[]{
                nextPair.first(),
                nextPair.second()
        };

        for (RailSidePlacement previousPlacement : previousPlacements) {
            for (RailSidePlacement nextPlacement : nextPlacements) {
                if (isSameBlock(previousPlacement.position(), nextPlacement.position()))
                    continue;

                RailSidePair cornerPair = new RailSidePair(previousPlacement, nextPlacement, 0);

                if (getCenterCollisionPenalty(cornerPair, centerPositions) == 0)
                    addSidePairIfNew(candidates, cornerPair);
            }
        }
    }

    private void addFallbackSidePairs(
            List<RailSidePair> candidates,
            Vector center,
            RailDirection direction,
            Set<PositionKey> centerPositions
    ) {
        List<RailSidePlacement> placements = getFallbackSidePlacements(center, direction, centerPositions);

        for (int firstIndex = 0; firstIndex < placements.size(); firstIndex++) {
            for (int secondIndex = firstIndex + 1; secondIndex < placements.size(); secondIndex++) {
                RailSidePlacement first = placements.get(firstIndex);
                RailSidePlacement second = placements.get(secondIndex);
                int separation = getChebyshevDistance(first.position(), second.position());

                if (separation < 2)
                    continue;

                addSidePairIfNew(candidates, new RailSidePair(first, second, FALLBACK_SIDE_PAIR_PENALTY));
            }
        }
    }

    private List<RailSidePlacement> getFallbackSidePlacements(
            Vector center,
            RailDirection direction,
            Set<PositionKey> centerPositions
    ) {
        return getFallbackSidePlacements(center, direction, centerPositions, Collections.emptySet());
    }

    private List<RailSidePlacement> getFallbackSidePlacements(
            Vector center,
            RailDirection direction,
            Set<PositionKey> centerPositions,
            Set<ColumnKey> centerColumns
    ) {
        List<RailSidePlacement> placements = new ArrayList<>();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0)
                    continue;

                Vector position = new Vector(
                        center.getBlockX() + dx,
                        center.getBlockY(),
                        center.getBlockZ() + dz
                );

                PositionKey key = PositionKey.from(position);

                if (isCenterColumnCollision(key, centerPositions, centerColumns))
                    continue;

                placements.add(new RailSidePlacement(position, getFallbackFacing(dx, dz, direction)));
            }
        }

        return placements;
    }

    private Direction getFallbackFacing(int offsetX, int offsetZ, RailDirection direction) {
        if (Math.abs(direction.dx()) >= Math.abs(direction.dz()) && direction.dx() != 0)
            return getXFacing(direction.dx());

        if (direction.dz() != 0)
            return getZFacing(direction.dz());

        if (Math.abs(offsetX) >= Math.abs(offsetZ) && offsetX != 0)
            return getXFacing(offsetX);

        return getZFacing(offsetZ);
    }

    private RailSidePair createSidePair(Vector center, RailDirection direction) {
        int x = center.getBlockX();
        int y = center.getBlockY();
        int z = center.getBlockZ();
        int dx = direction.dx();
        int dz = direction.dz();

        if (dx != 0 && dz != 0)
            return new RailSidePair(
                    new RailSidePlacement(new Vector(x + dx, y, z), getZFacing(dz)),
                    new RailSidePlacement(new Vector(x, y, z + dz), getXFacing(dx)),
                    0
            );

        if (dx != 0)
            return new RailSidePair(
                    new RailSidePlacement(new Vector(x, y, z + 1), getXFacing(dx)),
                    new RailSidePlacement(new Vector(x, y, z - 1), getXFacing(dx)),
                    0
            );

        return new RailSidePair(
                new RailSidePlacement(new Vector(x + 1, y, z), getZFacing(dz)),
                new RailSidePlacement(new Vector(x - 1, y, z), getZFacing(dz)),
                0
        );
    }

    private void addSidePairIfNew(List<RailSidePair> candidates, RailSidePair sidePair) {
        for (RailSidePair candidate : candidates) {
            if (candidate.hasSamePositions(sidePair))
                return;
        }

        candidates.add(sidePair);
    }

    private RailDirection getRailDirection(List<Vector> path, int index) {
        RailDirection nextDirection = getHorizontalDirection(path, index, index + 1);
        RailDirection previousDirection = getHorizontalDirection(path, index - 1, index);

        if (nextDirection != null && previousDirection != null) {
            if (nextDirection.equals(previousDirection))
                return nextDirection;

            int blendedX = Integer.compare(nextDirection.dx() + previousDirection.dx(), 0);
            int blendedZ = Integer.compare(nextDirection.dz() + previousDirection.dz(), 0);

            if (blendedX != 0 || blendedZ != 0)
                return new RailDirection(blendedX, blendedZ);

            return nextDirection;
        }

        if (nextDirection != null)
            return nextDirection;

        if (previousDirection != null)
            return previousDirection;

        for (int nextIndex = index + 1; nextIndex < path.size(); nextIndex++) {
            RailDirection direction = getHorizontalDirection(path, index, nextIndex);

            if (direction != null)
                return direction;
        }

        for (int previousIndex = index - 1; previousIndex >= 0; previousIndex--) {
            RailDirection direction = getHorizontalDirection(path, previousIndex, index);

            if (direction != null)
                return direction;
        }

        return new RailDirection(1, 0);
    }

    private RailDirection getHorizontalDirection(List<Vector> path, int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= path.size() || toIndex >= path.size())
            return null;

        Vector from = path.get(fromIndex);
        Vector to = path.get(toIndex);
        int dx = Integer.compare(to.getBlockX() - from.getBlockX(), 0);
        int dz = Integer.compare(to.getBlockZ() - from.getBlockZ(), 0);

        if (dx == 0 && dz == 0)
            return null;

        return new RailDirection(dx, dz);
    }

    private void snapMissingControlPointHeightsToTerrain(List<Vector> points) {
        if (blocks == null || !hasMissingControlPointHeights(points))
            return;

        GeneratorUtils.adjustHeight(points, blocks);
    }

    private boolean hasMissingControlPointHeights(List<Vector> points) {
        if (customControlPoints != null)
            return false;

        for (Vector point : points)
            if (point.getBlockY() != 0)
                return false;

        return true;
    }

    private List<Vector> getRestoreSelectionPoints() {
        if (customControlPoints != null && railSelectionPoints.size() >= 3)
            return railSelectionPoints;

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

    private List<Vector> sanitizePoints(List<Vector> points) {
        List<Vector> sanitizedPoints = new ArrayList<>();

        for (Vector point : points)
            addPointIfNew(sanitizedPoints, toBlockVector(point));

        return sanitizedPoints;
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
        if (BlockTypes.ANVIL == null)
            return null;

        return BlockTypes.ANVIL
                .getDefaultState()
                .with(PropertyKey.FACING, direction);
    }

    private Direction getXFacing(int dx) {
        return dx >= 0 ? Direction.EAST : Direction.WEST;
    }

    private Direction getZFacing(int dz) {
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private Vector toBlockVector(Vector vector) {
        return new Vector(
                vector.getBlockX(),
                vector.getBlockY(),
                vector.getBlockZ()
        );
    }

    private void addPointIfNew(List<Vector> points, Vector point) {
        Vector blockPoint = toBlockVector(point);

        if (points.isEmpty() || !isSameBlock(points.get(points.size() - 1), blockPoint))
            points.add(blockPoint);
    }

    private void replaceLastPoint(List<Vector> points, Vector point) {
        Vector blockPoint = toBlockVector(point);

        if (points.isEmpty()) {
            points.add(blockPoint);
            return;
        }

        points.set(points.size() - 1, blockPoint);
    }

    private boolean isSameBlock(Vector first, Vector second) {
        return first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private record RailDirection(int dx, int dz) {
    }

    private record RailSidePlacement(Vector position, Direction facing) {
    }

    private record RailSidePair(RailSidePlacement first, RailSidePlacement second, int fallbackPenalty) {

        private RailSidePair reversed() {
            return new RailSidePair(second, first, fallbackPenalty);
        }

        private boolean hasDuplicatePosition() {
            return isSamePosition(first, second);
        }

        private boolean hasSamePositions(RailSidePair other) {
            return (isSamePosition(first, other.first) && isSamePosition(second, other.second))
                    || (isSamePosition(first, other.second) && isSamePosition(second, other.first));
        }

        private static boolean isSamePosition(RailSidePlacement first, RailSidePlacement second) {
            return first.position().getBlockX() == second.position().getBlockX()
                    && first.position().getBlockY() == second.position().getBlockY()
                    && first.position().getBlockZ() == second.position().getBlockZ();
        }
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
            Direction preferredFacing = Direction.EAST;
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
