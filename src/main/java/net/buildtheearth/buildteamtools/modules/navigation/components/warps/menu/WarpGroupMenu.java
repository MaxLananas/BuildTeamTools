package net.buildtheearth.buildteamtools.modules.navigation.components.warps.menu;

import com.alpsbte.alpslib.utils.ChatHelper;
import com.google.gson.Gson;
import net.buildtheearth.buildteamtools.BuildTeamTools;
import net.buildtheearth.buildteamtools.modules.navigation.NavigationModule;
import net.buildtheearth.buildteamtools.modules.navigation.components.warps.model.WarpGroup;
import net.buildtheearth.buildteamtools.modules.network.NetworkModule;
import net.buildtheearth.buildteamtools.modules.network.model.BuildTeam;
import net.buildtheearth.buildteamtools.modules.network.model.Permissions;
import net.buildtheearth.buildteamtools.utils.ListUtil;
import net.buildtheearth.buildteamtools.utils.MenuItems;
import net.buildtheearth.buildteamtools.utils.heads.HeadFactory;
import net.buildtheearth.buildteamtools.utils.heads.HeadTexture;
import net.buildtheearth.buildteamtools.utils.io.ConfigPaths;
import net.buildtheearth.buildteamtools.utils.io.ConfigUtil;
import net.buildtheearth.buildteamtools.utils.menus.AbstractMenu;
import net.buildtheearth.buildteamtools.utils.menus.AbstractPaginatedMenu;
import org.bukkit.entity.Player;
import org.ipvp.canvas.mask.BinaryMask;
import org.ipvp.canvas.mask.Mask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class WarpGroupMenu extends AbstractPaginatedMenu {

    public static final int BACK_ITEM_SLOT = 27;
    public static final int SWITCH_PAGE_ITEM_SLOT = 34;

    private final boolean hasBackItem;
    private final BuildTeam buildTeam;
    private AbstractMenu backMenue;
    private final boolean showPlusItem;

    /**
     * In this menu the player can select a warp group to view the warps in each warp group.
     *
     * @param menuPlayer  The player that is viewing the menu
     * @param buildTeam   The build team that the menu is for
     * @param hasBackItem Whether the menu has a back item
     */
    public WarpGroupMenu(Player menuPlayer, BuildTeam buildTeam, boolean hasBackItem, boolean autoLoad) {
        super(4, 3, "Warp Menu", menuPlayer, autoLoad);
        this.hasBackItem = hasBackItem;
        this.buildTeam = buildTeam;
        BuildTeam currentTeam = NetworkModule.getInstance().getBuildTeam();
        this.showPlusItem = getMenuPlayer().hasPermission(Permissions.WARP_GROUP_CREATE)
                && currentTeam != null && currentTeam.equals(buildTeam);
    }

    public WarpGroupMenu(Player menuPlayer, BuildTeam buildTeam, boolean hasBackItem, boolean autoLoad, AbstractMenu menu) {
        this(menuPlayer, buildTeam, hasBackItem, autoLoad);
        this.backMenue = menu;
    }

    @Override
    protected void setPreviewItems() {
        if (hasBackItem)
            setBackItem(BACK_ITEM_SLOT, backMenue);

        List<?> source = getSource();
        if (source.size() > getEffectiveMaxItems())
            setSwitchPageItems(SWITCH_PAGE_ITEM_SLOT);
        else
            for (int i = -1; i < 2; i++)
                getMenu().getSlot(SWITCH_PAGE_ITEM_SLOT + i).setItem(MenuItems.ITEM_BACKGROUND);

        super.setPreviewItems();
    }

    @Override
    protected void setMenuItemsAsync() {}

    @Override
    protected void setItemClickEventsAsync() {
        if (getSource().size() > getEffectiveMaxItems())
            setSwitchPageItemClickEvents(SWITCH_PAGE_ITEM_SLOT);
    }

    @Override
    protected void setPaginatedPreviewItems(@NotNull List<?> source) {
        List<WarpGroup> warpGroups = source.stream().map(l -> (WarpGroup) l).toList();

        getMenu().getSlot(getAlternativePlusSlot()).setItem(MenuItems.ITEM_BACKGROUND);
        recalculateAutoSlots(warpGroups);

        for (WarpGroup warpGroup : warpGroups) {
            int slot = getWarpGroupSlot(warpGroup);
            if (slot >= 0) getMenu().getSlot(slot).setItem(warpGroup.getMaterialItem());
        }

        if (showPlusItem) {
            getMenu().getSlot(getAlternativePlusSlot()).setItem(
                    HeadFactory.head(HeadTexture.GREEN_PLUS, "§a§lCreate a new Warp Group",
                            ListUtil.createList("§8Click to create a new warp group."))
            );
        }
    }

    @Override
    protected void setPaginatedMenuItemsAsync(List<?> source) {}

    @Override
    protected Mask getMask() {
        return BinaryMask.builder(getMenu())
                .item(MenuItems.ITEM_BACKGROUND)
                .pattern(BinaryMask.EMPTY_PATTERN)
                .pattern(BinaryMask.EMPTY_PATTERN)
                .pattern(BinaryMask.EMPTY_PATTERN)
                .pattern("111111110")
                .build();
    }

    @Override
    protected List<?> getSource() {
        List<WarpGroup> warpGroups;

        String mode = BuildTeamTools.getInstance().getConfig(ConfigUtil.NAVIGATION)
                .getString(ConfigPaths.Navigation.WARPS_GROUP_SORTING_MODE, "");

        if (mode.equalsIgnoreCase("name")) {
            warpGroups = buildTeam.getWarpGroups().stream()
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .toList();
        } else {
            warpGroups = new ArrayList<>(buildTeam.getWarpGroups());
        }

        return warpGroups;
    }

    @Override
    protected void setPaginatedItemClickEventsAsync(@NotNull List<?> source) {
        List<WarpGroup> warpGroups = source.stream().map(l -> (WarpGroup) l).toList();

        for (WarpGroup warpGroup : warpGroups)
            setClickEventForSlot(warpGroup);
    }

    protected void setClickEventForSlot(@NotNull WarpGroup warpGroup) {
        final int slot = getWarpGroupSlot(warpGroup);
        if (slot < 0) return;

        getMenu().getSlot(slot).setClickHandler((clickPlayer, clickInformation) -> {
            clickPlayer.closeInventory();

            if (clickInformation.getClickType().isRightClick() && clickPlayer.hasPermission(Permissions.WARP_GROUP_EDIT))
                new WarpGroupEditMenu(clickPlayer, warpGroup, true, true);
            else
                leftClickAction(clickPlayer, warpGroup);
        });

        getMenu().getSlot(getAlternativePlusSlot()).setClickHandler((clickPlayer, clickInformation) ->
                NavigationModule.getInstance().getWarpsComponent().createWarpGroup(clickPlayer));
    }

    protected void leftClickAction(Player clickPlayer, @NotNull WarpGroup warpGroup) {
        new WarpMenu(clickPlayer, warpGroup, true, true);
    }

    protected int getWarpGroupSlot(@NotNull WarpGroup g) {
        int s = g.getSlot();
        if (s >= 0 && s <= 26) return s;
        int a = g.getInternalSlot();
        return a >= 0 && a <= 26 ? a : -1;
    }

    /** Returns the slot used for the "create warp group" plus button. */
    private int getAlternativePlusSlot() {
        return 35;
    }

    /**
     * Returns the effective maximum number of warp groups that can fit on a single page,
     * excluding the plus button slot.
     */
    private int getEffectiveMaxItems() {
        return 27;
    }

    /**
     * Recalculates automatic slot assignments for the given warp groups.
     * <p>
     * Preserves valid explicit slots (0..26). For groups with an invalid slot,
     * assigns the first available slot in ascending order; if none remain, sets -1.
     * Any group with a valid explicit slot has its internal auto slot cleared (-1).
     *
     * @param warpGroups groups to update
     * @return The next free slot or -1 if it's outside the range
     */
    private static int recalculateAutoSlots(@NotNull List<WarpGroup> warpGroups) {
        final int MAX = 27;

        ArrayDeque<Integer> free = getFreeSlots(warpGroups, MAX);

        for (WarpGroup g : warpGroups) {
            int s = g.getSlot();
            if (s >= 0 && s < MAX) {
                g.setInternalSlot(-1);
            } else {
                Integer next = free.pollFirst();
                g.setInternalSlot(next != null ? next : -1);
            }
        }

        Integer next = free.pollFirst();

        if (BuildTeamTools.getInstance().isDebug() && BuildTeamTools.getInstance().getComponentLogger().isInfoEnabled()) {
            BuildTeamTools.getInstance().getComponentLogger().info("Free slot: {}.", free);
            BuildTeamTools.getInstance().getComponentLogger().info("Auto slots: {}.",
                    new Gson().toJson(warpGroups.stream().map(warpGroup -> new WarpGropSlotDebug(
                            warpGroup.getName(),
                            warpGroup.getSlot(),
                            warpGroup.getInternalSlot())).toList()));
        }

        return next != null ? next : -1;
    }

    private static @NotNull ArrayDeque<Integer> getFreeSlots(@NotNull List<WarpGroup> warpGroups, int max) {
        boolean[] taken = new boolean[max];
        for (WarpGroup g : warpGroups) {
            int s = g.getSlot();
            if (s >= 0 && s < max) taken[s] = true;
        }

        ArrayDeque<Integer> free = new ArrayDeque<>();
        for (int i = 0; i < max; i++) {
            if (!taken[i]) free.add(i);
        }

        return free;
    }

    private record WarpGropSlotDebug(String name, int slot, int internalSlot) {}
}
