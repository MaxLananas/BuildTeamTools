package net.buildtheearth.buildteamtools.modules.generator.components.rail;

import com.alpsbte.alpslib.utils.GeneratorUtils;
import net.buildtheearth.buildteamtools.modules.generator.GeneratorModule;
import net.buildtheearth.buildteamtools.modules.generator.model.GeneratorComponent;
import net.buildtheearth.buildteamtools.modules.generator.model.GeneratorType;
import org.bukkit.entity.Player;

public class Rail extends GeneratorComponent {

    public Rail() {
        super(GeneratorType.RAILWAY);
    }

    @Override
    public void analyzeCommand(Player player, String[] args) {
        if (!getPlayerSettings().containsKey(player.getUniqueId()))
            addPlayerSetting(player);

        if (args.length >= 2) {
            String subCommand = args[1].toLowerCase();

            switch (subCommand) {
                case "help", "info", "?" -> {
                    sendHelp(player);
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
        return !GeneratorUtils.checkForNoWorldEditSelection(player);
    }

    @Override
    public void generate(Player player) {
        if (!GeneratorModule.getInstance().getRail().checkForPlayer(player))
            return;

        new RailScripts(player, this);
    }
}
