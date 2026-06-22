package net.buildtheearth.buildteamtools.modules.navigation.components.warps.menu;

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

    // Bottom bar layout (row 4, slots 27-35):
    // 27 = back
    // 28 = bg
    // 29 = prev page arrow
    // 30 = bg
    // 31 = bg
    // 32 = bg
    // 33 = next page arrow
    // 34 = bg
    // 35 = create warp group (admin only)

    public static final int BACK_ITEM_SLOT = 27;
    public static final int PREV_PAGE_SLOT = 29;
    public static final int NEXT_PAGE_SLOT = 33;
    public static final int PLUS_SLOT = 35;

    // Threshold: content area is slots 0-26 = 27 slots.
    // We paginate when there are more warp groups than can fit in 27 slots.
    private static final int ITEMS_PER_PAGE = 27;

    private final boolean hasBackItem;
    private final BuildTeam buildTeam;
    private AbstractMenu backMenu;
    private final boolean showPlusItem;

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
        this.backMenu = menu;
    }

    @Override
    protected void setPreviewItems() {
        if (hasBackItem)
            setBackItem(BACK_ITEM_SLOT, backMenu);
        else
            getMenu().getSlot(BACK_ITEM_SLOT).setItem(MenuItems.ITEM_BACKGROUND);

        // Fill bottom bar background except special slots
        for (int i = 28; i <= 34; i++)
            getMenu().getSlot(i).setItem(MenuItems.ITEM_BACKGROUND);

        // Show pagination arrows only when needed
        if (isPaginated())
            setSwitchPageItems(PREV_PAGE_SLOT, NEXT_PAGE_SLOT);

        // Plus button slot — fill with background first, set actual item in setPaginatedPreviewItems
        getMenu().getSlot(PLUS_SLOT).setItem(MenuItems.ITEM_BACKGROUND);

        super.setPreviewItems();
    }

    @Override
    protected void setMenuItemsAsync() {}

    @Override
    protected void setItemClickEventsAsync() {
        if (isPaginated())
            setSwitchPageItemClickEvents(PREV_PAGE_SLOT, NEXT_PAGE_SLOT);
    }

    @Override
    protected void setPaginatedPreviewItems(@NotNull List<?> source) {
        List<WarpGroup> warpGroups = source.stream().map(l -> (WarpGroup) l).toList();

        // Clear content area
        for (int i = 0; i < ITEMS_PER_PAGE; i++)
            getMenu().getSlot(i).setItem(MenuItems.ITEM_BACKGROUND);

        // Clear plus slot
        getMenu().getSlot(PLUS_SLOT).setItem(MenuItems.ITEM_BACKGROUND);

        if (isPaginated()) {
            // Paginated mode: fill slots 0..N sequentially
            int slot = 0;
            for (WarpGroup warpGroup : warpGroups) {
                if (slot >= ITEMS_PER_PAGE) break;
                getMenu().getSlot(slot).setItem(warpGroup.getMaterialItem());
                slot++;
            }
        } else {
            // Non-paginated mode: use explicit/auto slots
            List<WarpGroup> all = getSource().stream().map(l -> (WarpGroup) l).toList();
            recalculateAutoSlots(all);
            for (WarpGroup warpGroup : warpGroups) {
                int slot = getWarpGroupSlot(warpGroup);
                if (slot >= 0 && slot < ITEMS_PER_PAGE)
                    getMenu().getSlot(slot).setItem(warpGroup.getMaterialItem());
            }
        }

        // Plus button: show on last page (or only page) if admin
        if (showPlusItem && !hasNextPage()) {
            getMenu().getSlot(PLUS_SLOT).setItem(
                    HeadFactory.head(HeadTexture.GREEN_PLUS, "§a§lCreate a new Warp Group",
                            ListUtil.createList("§8Click to create a new warp group."))
            );
        }
    }

    @Override
    protected void setPaginatedMenuItemsAsync(List<?> source) {}

    @Override
    protected void setPaginatedItemClickEventsAsync(@NotNull List<?> source) {
        List<WarpGroup> warpGroups = source.stream().map(l -> (WarpGroup) l).toList();

        // Clear any stale click handlers in content area
        for (int i = 0; i < ITEMS_PER_PAGE; i++)
            getMenu().getSlot(i).setClickHandler(null);

        // Clear plus slot click handler
        getMenu().getSlot(PLUS_SLOT).setClickHandler(null);

        if (isPaginated()) {
            int slot = 0;
            for (WarpGroup warpGroup : warpGroups) {
                if (slot >= ITEMS_PER_PAGE) break;
                setClickHandlerForSlot(slot, warpGroup);
                slot++;
            }
        } else {
            for (WarpGroup warpGroup : warpGroups) {
                int slot = getWarpGroupSlot(warpGroup);
                if (slot >= 0 && slot < ITEMS_PER_PAGE)
                    setClickHandlerForSlot(slot, warpGroup);
            }
        }

        // Plus button click handler: only when visible (last page, admin)
        if (showPlusItem && !hasNextPage()) {
            getMenu().getSlot(PLUS_SLOT).setClickHandler((clickPlayer, clickInformation) ->
                    NavigationModule.getInstance().getWarpsComponent().createWarpGroup(clickPlayer));
        }
    }

    private void setClickHandlerForSlot(int slot, @NotNull WarpGroup warpGroup) {
        getMenu().getSlot(slot).setClickHandler((clickPlayer, clickInformation) -> {
            clickPlayer.closeInventory();
            if (clickInformation.getClickType().isRightClick() && clickPlayer.hasPermission(Permissions.WARP_GROUP_EDIT))
                new WarpGroupEditMenu(clickPlayer, warpGroup, true, true);
            else
                leftClickAction(clickPlayer, warpGroup);
        });
    }

    protected void leftClickAction(Player clickPlayer, @NotNull WarpGroup warpGroup) {
        new WarpMenu(clickPlayer, warpGroup, true, true);
    }

    protected int getWarpGroupSlot(@NotNull WarpGroup g) {
        int s = g.getSlot();
        if (s >= 0 && s < ITEMS_PER_PAGE) return s;
        int a = g.getInternalSlot();
        return (a >= 0 && a < ITEMS_PER_PAGE) ? a : -1;
    }

    private boolean isPaginated() {
        return getSource().size() > ITEMS_PER_PAGE;
    }

    @Override
    protected Mask getMask() {
        return BinaryMask.builder(getMenu())
                .item(MenuItems.ITEM_BACKGROUND)
                .pattern(BinaryMask.EMPTY_PATTERN)
                .pattern(BinaryMask.EMPTY_PATTERN)
                .pattern(BinaryMask.EMPTY_PATTERN)
                .pattern("111111111")
                .build();
    }

    @Override
    protected List<?> getSource() {
        String mode = BuildTeamTools.getInstance().getConfig(ConfigUtil.NAVIGATION)
                .getString(ConfigPaths.Navigation.WARPS_GROUP_SORTING_MODE, "");

        List<WarpGroup> warpGroups;
        if (mode.equalsIgnoreCase("name")) {
            warpGroups = buildTeam.getWarpGroups().stream()
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .toList();
        } else {
            warpGroups = new ArrayList<>(buildTeam.getWarpGroups());
        }
        return warpGroups;
    }

    private static int recalculateAutoSlots(@NotNull List<WarpGroup> warpGroups) {
        final int MAX = ITEMS_PER_PAGE;

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

        if (BuildTeamTools.getInstance().isDebug()) {
            BuildTeamTools.getInstance().getComponentLogger().info("Auto slots: {}.",
                    new Gson().toJson(warpGroups.stream().map(wg ->
                            new WarpGroupSlotDebug(wg.getName(), wg.getSlot(), wg.getInternalSlot())).toList()));
        }

        Integer next = free.pollFirst();
        return next != null ? next : -1;
    }

    private static @NotNull ArrayDeque<Integer> getFreeSlots(@NotNull List<WarpGroup> warpGroups, int max) {
        boolean[] taken = new boolean[max];
        for (WarpGroup g : warpGroups) {
            int s = g.getSlot();
            if (s >= 0 && s < max) taken[s] = true;
        }
        ArrayDeque<Integer> free = new ArrayDeque<>();
        for (int i = 0; i < max; i++)
            if (!taken[i]) free.add(i);
        return free;
    }

    private record WarpGroupSlotDebug(String name, int slot, int internalSlot) {}
}
