package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SoulSandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动补种：玩家挖掉成熟农作物后，自动用背包里对应的种子在原位置重新种上。
 * 支持标准作物（小麦/胡萝卜/马铃薯/甜菜根）、下界疣、可可豆。
 */
public class AutoReplantFeature {

    private static final int SCAN_RADIUS_XZ = 5;
    private static final int SCAN_DY_MIN = -2;
    private static final int SCAN_DY_MAX = 2;
    private static final int SCAN_INTERVAL_TICKS = 4;
    private static int scanCooldown = 0;

    private static final Map<BlockPos, CropResult> lastMature = new HashMap<>();
    private static final List<PendingReplant> pending = new ArrayList<>();

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

    public static void onTick(Minecraft client) {
        ModConfig cfg = ModConfig.get();
        if (client.player == null || client.level == null || client.gameMode == null) {
            lastMature.clear();
            pending.clear();
            return;
        }
        if (!cfg.enableAutoReplant || client.player.isSpectator()) {
            lastMature.clear();
            pending.clear();
            return;
        }

        Level world = client.level;

        // 1) 先推进待补种队列
        processPending(client, world);

        // 2) 节流
        if (scanCooldown > 0) {
            scanCooldown--;
            return;
        }
        scanCooldown = SCAN_INTERVAL_TICKS;

        // 3) 扫描当前帧成熟作物快照
        Map<BlockPos, CropResult> currentMature = new HashMap<>();
        BlockPos center = client.player.blockPosition();
        for (int dx = -SCAN_RADIUS_XZ; dx <= SCAN_RADIUS_XZ; dx++) {
            for (int dy = SCAN_DY_MIN; dy <= SCAN_DY_MAX; dy++) {
                for (int dz = -SCAN_RADIUS_XZ; dz <= SCAN_RADIUS_XZ; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
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

    private static void processPending(Minecraft client, Level world) {
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
                continue;
            }
            if (!isPlantable(world, p.pos, p.facing)) {
                continue;
            }
            replant(client, p.pos, p.seed, p.facing);
        }
    }

    private static CropResult getCrop(Level world, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock cropBlock) {
            if (cropBlock.isMaxAge(state)) {
                return new CropResult(cropBlock.getCloneItemStack(world, pos, state).getItem(), null);
            }
        } else if (block instanceof NetherWartBlock) {
            if (state.getValue(NetherWartBlock.AGE) >= 3) {
                return new CropResult(Items.NETHER_WART, null);
            }
        } else if (block instanceof CocoaBlock) {
            if (state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE) {
                return new CropResult(Items.COCOA_BEANS, state.getValue(HorizontalDirectionalBlock.FACING));
            }
        }
        return null;
    }

    private static boolean isPlantable(Level world, BlockPos pos, Direction facing) {
        if (facing == null) {
            BlockState below = world.getBlockState(pos.below());
            Block block = below.getBlock();
            return block instanceof FarmBlock || block instanceof SoulSandBlock;
        }
        return world.getBlockState(pos.relative(facing)).is(BlockTags.JUNGLE_LOGS);
    }

    private static void replant(Minecraft client, BlockPos cropPos, Item seed, Direction facing) {
        Inventory inv = client.player.getInventory();
        MultiPlayerGameMode im = client.gameMode;
        ClientPacketListener handler = client.getConnection();
        if (im == null || handler == null) {
            return;
        }

        // 1) 快捷栏（0-8）找种子
        int hotbarSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.items.get(i);
            if (!stack.isEmpty() && stack.is(seed)) {
                hotbarSlot = i;
                break;
            }
        }

        // 2) 快捷栏没有 → 主背包（9-35）找种子
        int fromSlot = -1;
        if (hotbarSlot < 0) {
            for (int i = 9; i < 36; i++) {
                ItemStack stack = inv.items.get(i);
                if (!stack.isEmpty() && stack.is(seed)) {
                    fromSlot = i;
                    break;
                }
            }
            if (fromSlot < 0) {
                return;
            }
            for (int i = 0; i < 9; i++) {
                if (inv.items.get(i).isEmpty()) {
                    hotbarSlot = i;
                    break;
                }
            }
            if (hotbarSlot < 0) {
                return;
            }
            // 打开了容器 GUI 时无法安全交换主背包，放弃
            if (client.player.containerMenu != client.player.inventoryMenu) {
                return;
            }
        }

        BlockHitResult hit;
        if (facing == null) {
            BlockPos groundPos = cropPos.below();
            hit = new BlockHitResult(
                    Vec3.atCenterOf(groundPos), Direction.UP, groundPos, false);
        } else {
            BlockPos logPos = cropPos.relative(facing);
            Direction side = facing.getOpposite();
            Vec3 hitPos = Vec3.atCenterOf(logPos).add(
                    side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
            hit = new BlockHitResult(hitPos, side, logPos, false);
        }

        int original = inv.selected;
        int containerId = client.player.inventoryMenu.containerId;

        // 主背包种子 → 快捷栏空槽（SWAP 点击，同步服务器）
        if (fromSlot >= 0) {
            im.handleInventoryMouseClick(containerId, fromSlot, hotbarSlot, ClickType.SWAP, client.player);
        }

        // 切到种子槽并右键种植
        if (inv.selected != hotbarSlot) {
            inv.selected = hotbarSlot;
        }
        im.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);

        // 种完后把剩余种子换回主背包原槽位
        if (fromSlot >= 0) {
            im.handleInventoryMouseClick(containerId, fromSlot, hotbarSlot, ClickType.SWAP, client.player);
        }

        // 恢复原选中槽位
        if (inv.selected != original) {
            inv.selected = original;
            handler.send(new ServerboundSetCarriedItemPacket(original));
        }
    }
}
