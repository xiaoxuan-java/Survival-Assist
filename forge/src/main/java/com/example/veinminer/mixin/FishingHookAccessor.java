package com.example.veinminer.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 FishingHook 的私有字段 biting（是否有鱼上钩、浮漂是否下沉）。
 * Fabric 对应 FishingBobberEntity.caughtFish；Forge official 字段名为 biting。
 */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {
    @Accessor("biting")
    boolean survivalassist$getBiting();
}
