package com.example.veinminer.mixin;

import net.minecraft.entity.projectile.FishingBobberEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 FishingBobberEntity 的私有字段 caughtFish（是否有鱼上钩、浮漂是否下沉）。
 */
@Mixin(FishingBobberEntity.class)
public interface FishingBobberEntityAccessor {
    @Accessor("caughtFish")
    boolean survivalassist$getCaughtFish();
}
