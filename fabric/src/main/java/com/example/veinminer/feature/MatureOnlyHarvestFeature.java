package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CocoaBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * 只允许采集成熟作物：未成熟的农作物（小麦/胡萝卜/马铃薯/甜菜根/下界疣）左键无法挖掘，
 * 只有成熟的才能挖。其它方块不受影响。
 *
 * 实现要点：通过 AttackBlockCallback 在客户端拦截"开始挖掘方块"的动作。
 * 若目标方块是未成熟作物，返回 FAIL 取消本次挖掘（不发送挖掘包）。
 * 注意：AttackBlockCallback 在持续挖掘期间不会反复触发（只在攻击起点触发一次），
 * 因此只需拦截攻击起点即可阻止未熟作物被挖。
 */
public class MatureOnlyHarvestFeature {

    public static void init() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!ModConfig.get().enableMatureOnlyHarvest) {
                return ActionResult.PASS;
            }
            if (isImmatureCrop(world, pos)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }

    private static boolean isImmatureCrop(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof CropBlock cropBlock) {
            return !cropBlock.isMature(state);
        }
        if (block instanceof NetherWartBlock) {
            return state.get(NetherWartBlock.AGE) < 3;
        }
        if (block instanceof CocoaBlock) {
            return state.get(CocoaBlock.AGE) < CocoaBlock.MAX_AGE;
        }
        return false;
    }
}
