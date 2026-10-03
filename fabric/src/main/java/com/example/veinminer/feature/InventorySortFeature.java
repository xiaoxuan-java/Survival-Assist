package com.example.veinminer.feature;

import com.example.veinminer.VeinMinerMod;
import com.example.veinminer.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 一键整理背包 / 容器：对当前打开的容器（或玩家主背包）一键整理。
 *
 * 覆盖范围：
 * - 未打开容器时：整理玩家主背包（27 格），不动快捷栏、副手、盔甲。
 * - 打开箱子/漏斗/潜影盒/末影箱等任意容器时：整理该容器的普通槽 + 玩家主背包。
 *
 * 全程通过 {@code clickSlot(PICKUP)} 模拟真实玩家点击，对单机与远程服务器都合法。
 * 整理分两阶段：
 *  1. 合并同类：把同类可堆叠物品合并到尽可能少的堆叠。
 *  2. 紧凑：把空槽沉到底部，物品向前聚拢。
 *
 * 关键点：为避免服务器回包延迟导致本地菜单状态滞后，先在本地对槽位做一次
 * 快照（copy），基于快照计算并维护一个镜像，每次点击后同步更新镜像，
 * 使连续发送的点击序列自洽，最终与服务器结果一致。
 */
public class InventorySortFeature {

    public static void init() {
    }

    public static void onTick(MinecraftClient client) {
        while (VeinMinerMod.SORT_INVENTORY_KEY.wasPressed()) {
            sort(client);
        }
    }

    public static void sort(MinecraftClient client) {
        if (!ModConfig.get().enableInventorySort) {
            return;
        }
        PlayerEntity player = client.player;
        ClientPlayerInteractionManager im = client.interactionManager;
        if (player == null || im == null || player.isSpectator()) {
            return;
        }
        ScreenHandler menu = player.currentScreenHandler;
        if (menu == null) {
            return;
        }
        // 玩家背包界面（无储存容器）不整理；只有打开储存容器时才整理
        if (menu instanceof PlayerScreenHandler) {
            return;
        }

        // 收集可整理槽（menu 内槽位序号）
        List<Integer> slots = collectSortableSlots(menu);
        if (slots.size() < 2) {
            return;
        }
        int syncId = menu.syncId;

        // 本地快照镜像
        ItemStack[] mirror = new ItemStack[menu.slots.size()];
        for (int i = 0; i < menu.slots.size(); i++) {
            ItemStack s = menu.slots.get(i).getStack();
            mirror[i] = s.isEmpty() ? ItemStack.EMPTY : s.copy();
        }

        // 阶段 1：合并同类
        mergeSame(menu, player, im, syncId, slots, mirror);
        // 阶段 2：紧凑（空槽沉底，保证前 N 个槽连续非空）
        compact(menu, player, im, syncId, slots, mirror);
        // 阶段 3：按物品注册 ID 排序（在连续非空区做选择排序）
        sortByIds(menu, player, im, syncId, slots, mirror);

        player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 1.0F);
    }

    /**
     * 收集可整理槽位：排除玩家快捷栏、盔甲、副手，以及结果槽/功能槽
     * （对普通物品 {@code canInsert} 返回 false 的槽，例如合成结果、熔炉输出、燃料槽）。
     */
    private static List<Integer> collectSortableSlots(ScreenHandler menu) {
        List<Integer> result = new ArrayList<>();
        ItemStack probe = new ItemStack(Items.STONE);
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            // 合成栏（工作台等）槽不参与整理
            if (slot.inventory instanceof CraftingInventory) {
                continue;
            }
            // 玩家库存槽：只保留主背包（容器内索引 9~35），排除快捷栏(0~8)、盔甲/副手(36+)
            if (slot.inventory instanceof PlayerInventory) {
                int idx = slot.getIndex();
                if (idx < 9 || idx >= 36) {
                    continue;
                }
            }
            // 结果槽/功能槽（不能放入普通物品）不参与整理
            if (!slot.canInsert(probe)) {
                continue;
            }
            result.add(i);
        }
        return result;
    }

    /** 合并同类，直到无法再合并。 */
    private static void mergeSame(ScreenHandler menu, PlayerEntity player, ClientPlayerInteractionManager im,
                                  int syncId, List<Integer> slots, ItemStack[] mirror) {
        boolean merged;
        do {
            merged = false;
            outer:
            for (int a : slots) {
                ItemStack sa = mirror[a];
                if (sa.isEmpty() || sa.getCount() >= sa.getMaxCount()) {
                    continue;
                }
                for (int b : slots) {
                    if (b <= a) {
                        continue;
                    }
                    ItemStack sb = mirror[b];
                    if (sb.isEmpty()) {
                        continue;
                    }
                    if (canMerge(sa, sb) && menu.slots.get(a).canInsert(sb)) {
                        // 拿起 b → 放到 a（自动合并，剩余留在光标）→ 剩余放回 b
                        click(im, syncId, b, player);
                        click(im, syncId, a, player);
                        click(im, syncId, b, player);
                        // 更新镜像
                        int space = sa.getMaxCount() - sa.getCount();
                        int moved = Math.min(space, sb.getCount());
                        sa.setCount(sa.getCount() + moved);
                        sb.setCount(sb.getCount() - moved);
                        merged = true;
                        continue outer;
                    }
                }
            }
        } while (merged);
    }

    /** 空槽沉底：从前往后找空槽，用其后能放入的非空槽填补。 */
    private static void compact(ScreenHandler menu, PlayerEntity player, ClientPlayerInteractionManager im,
                                int syncId, List<Integer> slots, ItemStack[] mirror) {
        for (int i = 0; i < slots.size(); i++) {
            int empty = slots.get(i);
            if (!mirror[empty].isEmpty()) {
                continue;
            }
            Slot slotEmpty = menu.slots.get(empty);
            for (int j = i + 1; j < slots.size(); j++) {
                int n = slots.get(j);
                ItemStack sn = mirror[n];
                if (sn.isEmpty()) {
                    continue;
                }
                if (!slotEmpty.canInsert(sn)) {
                    continue;
                }
                click(im, syncId, n, player);
                click(im, syncId, empty, player);
                mirror[empty] = sn;
                mirror[n] = ItemStack.EMPTY;
                break;
            }
        }
    }

    /**
     * 按物品注册 ID 排序：前提是前 count 个槽已连续非空（由 compact 保证）。
     * 对前 count 个位置做选择排序，把 ID 字典序最小的物品依次换到前面，
     * 使同类物品聚拢、按 ID 升序排列。
     */
    private static void sortByIds(ScreenHandler menu, PlayerEntity player, ClientPlayerInteractionManager im,
                                  int syncId, List<Integer> slots, ItemStack[] mirror) {
        int count = 0;
        for (int s : slots) {
            if (!mirror[s].isEmpty()) {
                count++;
            }
        }
        if (count < 2) {
            return;
        }

        for (int i = 0; i < count - 1; i++) {
            int posI = slots.get(i);
            int minPos = posI;
            String minId = itemId(mirror[posI]);
            for (int j = i + 1; j < count; j++) {
                int posJ = slots.get(j);
                String idJ = itemId(mirror[posJ]);
                if (idJ.compareTo(minId) < 0) {
                    minId = idJ;
                    minPos = posJ;
                }
            }
            if (minPos != posI) {
                // 交换 posI 与 minPos：拿起 minPos → 放到 posI（posI 原物品入光标）→ 放下到 minPos
                click(im, syncId, minPos, player);
                click(im, syncId, posI, player);
                click(im, syncId, minPos, player);
                ItemStack tmp = mirror[posI];
                mirror[posI] = mirror[minPos];
                mirror[minPos] = tmp;
            }
        }
    }

    private static String itemId(ItemStack stack) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id == null ? "" : id.toString();
    }

    private static boolean canMerge(ItemStack a, ItemStack b) {
        return a.getMaxCount() > 1
                && ItemStack.canCombine(a, b)
                && a.getCount() < a.getMaxCount();
    }

    /** 一次 PICKUP 点击（button=0）。 */
    private static void click(ClientPlayerInteractionManager im, int syncId, int slot, PlayerEntity player) {
        im.clickSlot(syncId, slot, 0, SlotActionType.PICKUP, player);
    }
}
