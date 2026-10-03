package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CocoaBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.SoulSandBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动补种：玩家挖掉成熟农作物后，自动用背包里对应的种子在原位置重新种上。
 *
 * 支持作物类型：
 * - 标准作物（小麦/胡萝卜/马铃薯/甜菜根，CropBlock）：种子种在耕地上。
 * - 下界疣（NetherWartBlock）：种子种在灵魂沙上。
 * - 可可豆（CocoaBlock）：可可豆种在丛林原木侧面，需记录依附方向（FACING）才能补种。
 *
 * 实现要点：
 * - 每 tick 扫描玩家周围小范围，记录"成熟作物 -> 种子(+朝向)"快照；
 *   通过与上一帧快照对比，检测"成熟作物被挖空"的事件。
 * - 检测到后延迟若干 tick 再补种，给掉落的种子留出被拾取进背包的时间。
 * - 补种动作：临时把快捷栏切到种子所在槽位，调用原版 interactBlock 模拟右键，
 *   再切回原槽位。全程复用原版种植逻辑，不触碰玩家当前手持物品的长期状态。
 */
public class AutoReplantFeature {

    private static final int SCAN_RADIUS_XZ = 5;
    private static final int SCAN_DY_MIN = -2;
    private static final int SCAN_DY_MAX = 2;
    // 扫描节流：每 4 tick 扫描一次，降低每 tick 的方块查询与 HashMap 构建开销
    private static final int SCAN_INTERVAL_TICKS = 4;
    private static int scanCooldown = 0;

    // 上一帧检测到的成熟作物：位置 -> 作物信息（种子 + 朝向）
    private static final Map<BlockPos, CropResult> lastMature = new HashMap<>();

    // 待补种队列（延迟以等待掉落种子被拾取）
    private static final List<PendingReplant> pending = new ArrayList<>();

    /** 作物信息：种子 + 依附方向（仅可可豆需要，其余为 null）。 */
    private static class CropResult {
        final Item seed;
        final Direction facing;

        CropResult(Item seed, Direction facing) {
            this.seed = seed;
            this.facing = facing;
        }
    }

    private static class PendingReplant {
        final BlockPos pos;
        final Item seed;
        final Direction facing;
        int ticks;

        PendingReplant(BlockPos pos, Item seed, Direction facing, int ticks) {
            this.pos = pos;
            this.seed = seed;
            this.facing = facing;
            this.ticks = ticks;
        }
    }

    public static void init() {
    }

    public static void onTick(MinecraftClient client) {
        ModConfig cfg = ModConfig.get();
        if (client.player == null || client.world == null || client.interactionManager == null) {
            lastMature.clear();
            pending.clear();
            return;
        }
        if (!cfg.enableAutoReplant || client.player.isSpectator()) {
            lastMature.clear();
            pending.clear();
            return;
        }

        World world = client.world;

        // 1) 先推进待补种队列（延迟到期后真正补种）
        processPending(client, world);

        // 2) 节流：仅每 SCAN_INTERVAL_TICKS tick 扫描一次成熟作物快照
        if (scanCooldown > 0) {
            scanCooldown--;
            return;
        }
        scanCooldown = SCAN_INTERVAL_TICKS;

        // 3) 扫描当前帧成熟作物快照
        Map<BlockPos, CropResult> currentMature = new HashMap<>();
        BlockPos center = client.player.getBlockPos();
        for (int dx = -SCAN_RADIUS_XZ; dx <= SCAN_RADIUS_XZ; dx++) {
            for (int dy = SCAN_DY_MIN; dy <= SCAN_DY_MAX; dy++) {
                for (int dz = -SCAN_RADIUS_XZ; dz <= SCAN_RADIUS_XZ; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    CropResult cr = getCrop(world, pos, world.getBlockState(pos));
                    if (cr != null) {
                        currentMature.put(pos, cr);
                    }
                }
            }
        }

        // 4) 对比上一帧：成熟作物消失（被挖空）→ 加入待补种队列
        for (Map.Entry<BlockPos, CropResult> e : lastMature.entrySet()) {
            BlockPos pos = e.getKey();
            if (currentMature.containsKey(pos)) {
                continue;
            }
            CropResult cr = e.getValue();
            if (world.getBlockState(pos).isAir() && isPlantable(world, pos, cr.facing)) {
                pending.add(new PendingReplant(pos, cr.seed, cr.facing, cfg.autoReplantDelay));
            }
        }

        // 5) 更新快照
        lastMature.clear();
        lastMature.putAll(currentMature);
    }

    private static void processPending(MinecraftClient client, World world) {
        if (pending.isEmpty()) {
            return;
        }
        for (int i = pending.size() - 1; i >= 0; i--) {
            PendingReplant p = pending.get(i);
            p.ticks--;
            if (p.ticks > 0) {
                continue;
            }
            pending.remove(i);

            BlockState now = world.getBlockState(p.pos);
            if (!now.isAir()) {
                continue; // 位置已被占据，放弃
            }
            if (!isPlantable(world, p.pos, p.facing)) {
                continue; // 下方已不可种植，放弃
            }
            replant(client, p.pos, p.seed, p.facing);
        }
    }

    /** 返回方块若为成熟作物则其作物信息（种子 + 朝向），否则 null。 */
    private static CropResult getCrop(World world, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock cropBlock) {
            if (cropBlock.isMature(state)) {
                return new CropResult(cropBlock.getPickStack(world, pos, state).getItem(), null);
            }
        } else if (block instanceof NetherWartBlock) {
            if (state.get(NetherWartBlock.AGE) >= 3) {
                return new CropResult(Items.NETHER_WART, null);
            }
        } else if (block instanceof CocoaBlock) {
            if (state.get(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE) {
                return new CropResult(Items.COCOA_BEANS, state.get(HorizontalFacingBlock.FACING));
            }
        }
        return null;
    }

    /**
     * 判断作物所在位置是否仍可种植。
     * - 普通作物/下界疣（facing == null）：下方是耕地或灵魂沙。
     * - 可可豆（facing != null）：依附方向是丛林原木。
     */
    private static boolean isPlantable(World world, BlockPos pos, Direction facing) {
        if (facing == null) {
            BlockState below = world.getBlockState(pos.down());
            Block block = below.getBlock();
            return block instanceof FarmlandBlock || block instanceof SoulSandBlock;
        }
        return world.getBlockState(pos.offset(facing)).isIn(BlockTags.JUNGLE_LOGS);
    }

    /**
     * 临时切换到种子所在槽位，模拟右键种植，再切回原槽位。
     *
     * 查找顺序：先在快捷栏（0-8）找种子，找不到再到主背包（9-35）找，
     * 主背包找到后通过 SWAP 点击临时换到快捷栏空槽，种完再换回。
     */
    private static void replant(MinecraftClient client, BlockPos cropPos, Item seed, Direction facing) {
        PlayerInventory inv = client.player.getInventory();
        ClientPlayerInteractionManager im = client.interactionManager;
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (im == null || handler == null) {
            return;
        }

        // 1) 快捷栏（0-8）找种子
        int hotbarSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.main.get(i);
            if (!stack.isEmpty() && stack.isOf(seed)) {
                hotbarSlot = i;
                break;
            }
        }

        // 2) 快捷栏没有 → 主背包（9-35）找种子，准备临时交换到快捷栏空槽
        int fromSlot = -1;
        if (hotbarSlot < 0) {
            for (int i = 9; i < 36; i++) {
                ItemStack stack = inv.main.get(i);
                if (!stack.isEmpty() && stack.isOf(seed)) {
                    fromSlot = i;
                    break;
                }
            }
            if (fromSlot < 0) {
                return; // 整个背包都没有该种子
            }
            // 找一个空的快捷栏槽位用于临时放置种子（避免覆盖玩家物品）
            for (int i = 0; i < 9; i++) {
                if (inv.main.get(i).isEmpty()) {
                    hotbarSlot = i;
                    break;
                }
            }
            if (hotbarSlot < 0) {
                return; // 快捷栏已满，无法安全交换
            }
            // 打开了容器 GUI 时无法安全交换主背包，放弃
            if (client.player.currentScreenHandler != client.player.playerScreenHandler) {
                return;
            }
        }

        BlockHitResult hit;
        if (facing == null) {
            // 普通作物/下界疣：右键耕地/灵魂沙上表面
            BlockPos groundPos = cropPos.down();
            hit = new BlockHitResult(
                    Vec3d.ofCenter(groundPos), Direction.UP, groundPos, false);
        } else {
            // 可可豆：右键丛林原木朝向可可豆的那一面
            BlockPos logPos = cropPos.offset(facing);
            Direction side = facing.getOpposite();
            Vec3d hitPos = Vec3d.ofCenter(logPos).add(
                    side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
            hit = new BlockHitResult(hitPos, side, logPos, false);
        }

        int original = inv.selectedSlot;
        PlayerScreenHandler screenHandler = client.player.playerScreenHandler;

        // 主背包种子 → 快捷栏空槽（SWAP 点击，同步服务器）
        if (fromSlot >= 0) {
            im.clickSlot(screenHandler.syncId, fromSlot, hotbarSlot, SlotActionType.SWAP, client.player);
        }

        // 切到种子槽并右键种植（interactBlock 内部会先 syncSelectedSlot 发切槽包）
        if (inv.selectedSlot != hotbarSlot) {
            inv.selectedSlot = hotbarSlot;
        }
        im.interactBlock(client.player, Hand.MAIN_HAND, hit);

        // 种完后把剩余种子换回主背包原槽位
        if (fromSlot >= 0) {
            im.clickSlot(screenHandler.syncId, fromSlot, hotbarSlot, SlotActionType.SWAP, client.player);
        }

        // 恢复原选中槽位
        if (inv.selectedSlot != original) {
            inv.selectedSlot = original;
            handler.sendPacket(new UpdateSelectedSlotC2SPacket(original));
        }
    }
}
