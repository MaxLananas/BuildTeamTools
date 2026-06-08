package net.buildtheearth.buildteamtools.modules.generator.components.rail;

import com.alpsbte.alpslib.utils.GeneratorUtils;
import com.sk89q.worldedit.regions.ConvexPolyhedralRegion;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import net.buildtheearth.buildteamtools.modules.generator.GeneratorModule;
import net.buildtheearth.buildteamtools.modules.generator.model.GeneratorComponent;
import net.buildtheearth.buildteamtools.modules.generator.model.GeneratorType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Rail extends GeneratorComponent {

    private final Set<UUID> preparingPlayers = ConcurrentHashMap.newKeySet();

    public Rail() {
        super(GeneratorType.RAIL);
    }

    @Override
    public boolean checkForPlayer(Player player) {
        if (GeneratorUtils.checkForNoWorldEditSelection(player))
            return false;

        Region region = GeneratorUtils.getWorldEditSelection(player);

        if (isSupportedRailSelection(region))
            return true;

        player.sendMessage(Component.text("Rail Generator supports cuboid, polygonal and convex WorldEdit selections.", NamedTextColor.RED));
        player.closeInventory();
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
        return false;
    }

    private boolean isSupportedRailSelection(Region region) {
        return region instanceof CuboidRegion
                || region instanceof Polygonal2DRegion
                || region instanceof ConvexPolyhedralRegion;
    }

    @Override
    public void generate(Player player) {
        if (GeneratorModule.getInstance().isGenerating(player) || !preparingPlayers.add(player.getUniqueId())) {
            sendAlreadyGeneratingMessage(player);
            return;
        }

        if (!GeneratorModule.getInstance().getRail().checkForPlayer(player)) {
            preparingPlayers.remove(player.getUniqueId());
            return;
        }

        new RailScripts(player, this, () -> preparingPlayers.remove(player.getUniqueId()));
    }

    private void sendAlreadyGeneratingMessage(Player player) {
        player.sendMessage(Component.text(
                "Rail Generator is already running. Please wait until the current generation is finished.",
                NamedTextColor.RED
        ));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
    }
}
