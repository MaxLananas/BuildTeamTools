package net.buildtheearth.buildteamtools.modules.generator.components.rail;

import com.alpsbte.alpslib.utils.GeneratorUtils;
import net.buildtheearth.buildteamtools.modules.generator.GeneratorModule;
import net.buildtheearth.buildteamtools.modules.generator.model.GeneratorComponent;
import net.buildtheearth.buildteamtools.modules.generator.model.GeneratorType;
import org.bukkit.entity.Player;

public class Rail extends GeneratorComponent {

    public Rail() {
        super(GeneratorType.RAIL);
    }

    @Override
    public boolean checkForPlayer(Player player) {
        return !GeneratorUtils.checkForNoWorldEditSelection(player);
    }

    @Override
    public void generate(Player player) {
        if (!GeneratorModule.getInstance().getRail().checkForPlayer(player))
            return;

        new RailScripts(player, this);
    }
}
