package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 只允许采集成熟作物：未成熟的农作物（小麦/胡萝卜/马铃薯/甜菜根/下界疣/可可豆）左键无法挖掘，
 * 只有成熟的才能挖。其它方块不受影响。
 *
 * Forge 实现：由主类在 PlayerInteractEvent.LeftClickBlock 中调用 shouldCancel。
 */
public class MatureOnlyHarvestFeature {

    public static void init() {
    }

    /** 返回 true 表示应取消本次挖掘（目标为未成熟作物）。 */
    public static boolean shouldCancel(Level world, BlockPos pos) {
        if (!ModConfig.get().enableMatureOnlyHarvest) {
            return false;
        }
        return isImmatureCrop(world, pos);
    }

    private static boolean isImmatureCrop(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof CropBlock cropBlock) {
            return !cropBlock.isMaxAge(state);
        }
        if (block instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) < 3;
        }
        if (block instanceof CocoaBlock) {
            return state.getValue(CocoaBlock.AGE) < CocoaBlock.MAX_AGE;
        }
        return false;
    }
}
