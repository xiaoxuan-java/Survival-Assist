package com.example.veinminer.mixin;

import com.example.veinminer.feature.XRayFeature;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 矿透核心：拦截方块渲染。当矿透开启且方块不是目标矿石时，取消渲染，
 * 实现"非矿石方块不渲染（全透明）"，矿石照常渲染（再叠加 XRayFeature 的发光描边）。
 */
@Mixin(BlockRenderManager.class)
public abstract class BlockRenderManagerMixin {

    @Inject(method = "renderBlock", at = @At("HEAD"), cancellable = true)
    private void survivalassist$cancelNonOre(
            BlockState state, BlockPos pos, BlockRenderView world,
            MatrixStack matrices, VertexConsumer vertexConsumer, boolean cull, Random random,
            CallbackInfo ci) {
        if (XRayFeature.isActive() && !XRayFeature.isOre(state)) {
            ci.cancel();
        }
    }
}
