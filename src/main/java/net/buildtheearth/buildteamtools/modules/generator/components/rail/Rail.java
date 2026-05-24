package net.buildtheearth.buildteamtools.modules.generator.components.rail;

import com.alpsbte.alpslib.utils.GeneratorUtils;
import net.buildtheearth.buildteamtools.modules.generator.GeneratorModule;
import net.buildtheearth.buildteamtools.modules.generator.model.GeneratorComponent;
import net.buildtheearth.buildteamtools.modules.generator.model.GeneratorType;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

public class Rail extends GeneratorComponent {

    private static final int TARGET_BLOCK_RANGE = 200;
    private static final int CUSTOM_SELECTION_PADDING = 4;
    private static final int CUSTOM_SELECTION_VERTICAL_PADDING = 12;

    public Rail() {
        super(GeneratorType.RAILWAY);
    }

    @Override
    public void analyzeCommand(Player player, String[] args) {
        if (getRailSettings(player) == null)
            addPlayerSetting(player);

        if (args.length >= 2) {
            String subCommand = args[1].toLowerCase();

            switch (subCommand) {
                case "help", "info", "?" -> {
                    sendHelp(player);
                    return;
                }

                case "add", "point" -> {
                    addPoint(player);
                    return;
                }

                case "clear", "reset" -> {
                    clearPoints(player);
                    return;
                }

                case "points", "list" -> {
                    listPoints(player);
                    return;
                }

                default -> {
                    player.sendMessage("§cUnknown rail command: §7" + args[1]);
                    sendHelp(player);
                    return;
                }
            }
        }

        generate(player);
    }

    @Override
    public boolean checkForPlayer(Player player) {
        RailSettings settings = getRailSettings(player);

        if (settings != null && settings.hasEnoughCustomControlPoints())
            return true;

        return !GeneratorUtils.checkForNoWorldEditSelection(player);
    }

    @Override
    public void generate(Player player) {
        if (!GeneratorModule.getInstance().getRail().checkForPlayer(player))
            return;

        RailSettings settings = getRailSettings(player);

        if (settings != null && settings.hasEnoughCustomControlPoints()) {
            List<Vector> customControlPoints = new ArrayList<>(settings.getCustomControlPoints());
            createCustomPointSelection(player, customControlPoints);
            new RailScripts(player, this, customControlPoints);
            return;
        }

        new RailScripts(player, this);
    }

    private void createCustomPointSelection(Player player, List<Vector> customControlPoints) {
        List<Vector> selectionLine = new ArrayList<>(customControlPoints);

        if (selectionLine.size() >= 2)
            selectionLine = GeneratorUtils.extendPolyLine(selectionLine);

        List<Vector> selectionPoints = GeneratorUtils.shiftPoints(selectionLine, CUSTOM_SELECTION_PADDING, true);

        if (selectionPoints == null || selectionPoints.size() < 3) {
            Vector[] bounds = getCustomPointBounds(customControlPoints);
            GeneratorUtils.createCuboidSelection(player, bounds[0], bounds[1]);
            return;
        }

        GeneratorUtils.createPolySelection(
                player,
                selectionPoints,
                getCustomSelectionMinY(player, customControlPoints),
                getCustomSelectionMaxY(player, customControlPoints)
        );
    }

    private Vector[] getCustomPointBounds(List<Vector> customControlPoints) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (Vector point : customControlPoints) {
            minX = Math.min(minX, point.getBlockX() - CUSTOM_SELECTION_PADDING);
            minY = Math.min(minY, point.getBlockY() - CUSTOM_SELECTION_VERTICAL_PADDING);
            minZ = Math.min(minZ, point.getBlockZ() - CUSTOM_SELECTION_PADDING);
            maxX = Math.max(maxX, point.getBlockX() + CUSTOM_SELECTION_PADDING);
            maxY = Math.max(maxY, point.getBlockY() + CUSTOM_SELECTION_VERTICAL_PADDING);
            maxZ = Math.max(maxZ, point.getBlockZ() + CUSTOM_SELECTION_PADDING);
        }

        return new Vector[]{
                new Vector(minX, minY, minZ),
                new Vector(maxX, maxY, maxZ)
        };
    }

    private int getCustomSelectionMinY(Player player, List<Vector> customControlPoints) {
        int minY = Integer.MAX_VALUE;

        for (Vector point : customControlPoints)
            minY = Math.min(minY, point.getBlockY());

        return Math.max(player.getWorld().getMinHeight(), minY - CUSTOM_SELECTION_VERTICAL_PADDING);
    }

    private int getCustomSelectionMaxY(Player player, List<Vector> customControlPoints) {
        int maxY = Integer.MIN_VALUE;

        for (Vector point : customControlPoints)
            maxY = Math.max(maxY, point.getBlockY());

        return Math.min(player.getWorld().getMaxHeight() - 1, maxY + CUSTOM_SELECTION_VERTICAL_PADDING);
    }

    private void addPoint(Player player) {
        RailSettings settings = getRailSettings(player);

        if (settings == null) {
            player.sendMessage("§cRail settings could not be loaded.");
            return;
        }

        Block targetBlock = player.getTargetBlockExact(TARGET_BLOCK_RANGE);

        if (targetBlock == null) {
            player.sendMessage("§cLook at a block first to add a rail point.");
            return;
        }

        Vector point = new Vector(
                targetBlock.getX(),
                targetBlock.getY() + 1,
                targetBlock.getZ()
        );

        settings.addCustomControlPoint(point);

        player.sendMessage("§aAdded rail point §7#" + settings.getCustomControlPoints().size()
                + " §8(" + point.getBlockX() + ", " + point.getBlockY() + ", " + point.getBlockZ() + ")");
    }

    private void clearPoints(Player player) {
        RailSettings settings = getRailSettings(player);

        if (settings == null) {
            player.sendMessage("§cRail settings could not be loaded.");
            return;
        }

        settings.clearCustomControlPoints();
        player.sendMessage("§aCleared all custom rail points.");
    }

    private void listPoints(Player player) {
        RailSettings settings = getRailSettings(player);

        if (settings == null) {
            player.sendMessage("§cRail settings could not be loaded.");
            return;
        }

        List<Vector> points = settings.getCustomControlPoints();

        if (points.isEmpty()) {
            player.sendMessage("§eNo custom rail points saved.");
            return;
        }

        player.sendMessage("§aSaved rail points:");

        for (int index = 0; index < points.size(); index++) {
            Vector point = points.get(index);

            player.sendMessage("§7#" + (index + 1)
                    + " §8(" + point.getBlockX() + ", " + point.getBlockY() + ", " + point.getBlockZ() + ")");
        }
    }

    private RailSettings getRailSettings(Player player) {
        return (RailSettings) getPlayerSettings().get(player.getUniqueId());
    }
}
