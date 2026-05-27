package net.buildtheearth.buildteamtools.modules.generator.listeners;

import com.alpsbte.alpslib.utils.ChatHelper;
import net.buildtheearth.buildteamtools.BuildTeamTools;
import net.buildtheearth.buildteamtools.modules.generator.GeneratorModule;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;

public class GeneratorListener implements Listener {

    public static final NamespacedKey INTERNAL_GENERATOR_COMMAND_KEY =
            new NamespacedKey(BuildTeamTools.getInstance(), "internal_generator_command");

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();

        if (!GeneratorModule.getInstance().isGenerating(p))
            return;

        if (!e.getMessage().startsWith("//"))
            return;

        if (p.getPersistentDataContainer().has(INTERNAL_GENERATOR_COMMAND_KEY, PersistentDataType.BYTE))
            return;

        e.setCancelled(true);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
        ChatHelper.sendErrorMessage(p, "You can't use WorldEdit commands while generating a structure.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();

        if (!GeneratorModule.getInstance().isGenerating(p))
            return;

        if (e.getItem() == null)
            return;

        if (e.getItem().getType() != Material.WOODEN_AXE)
            return;

        e.setCancelled(true);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
        ChatHelper.sendErrorMessage(p, "You can't use WorldEdit while generating a structure.");
    }
}
