package com.example.veinminer.mixin;

import com.example.veinminer.feature.XRayFeature;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 矿透核心：拦截方块渲染。当矿透开启且方块不是目标矿石时，取消渲染，
 * 实现"非矿石方块不渲染（全透明）"，矿石照常渲染（再叠加 XRayFeature 的发光描边）。
 *
 * Forge official 映射：BlockRenderManager -> BlockRenderDispatcher，renderBlock -> renderBatched。
 */
@Mixin(BlockRenderDispatcher.class)
public abstract class BlockRenderDispatcherMixin {

    @Inject(method = "renderBatched", at = @At("HEAD"), cancellable = true)
    private void survivalassist$cancelNonOre(
            BlockState state, BlockPos pos, BlockAndTintGetter world,
            PoseStack poseStack, VertexConsumer vertexConsumer, boolean checkSides, RandomSource random,
            CallbackInfo ci) {
        if (XRayFeature.isActive() && !XRayFeature.isOre(state)) {
            ci.cancel();
        }
    }
}
