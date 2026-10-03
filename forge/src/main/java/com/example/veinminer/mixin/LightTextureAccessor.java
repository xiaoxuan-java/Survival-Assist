package com.example.veinminer.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 LightTexture 的私有字段：lightPixels（光照贴图像素，NativeImage）与
 * lightTexture（DynamicTexture，用于重新 upload）。
 */
@Mixin(LightTexture.class)
public interface LightTextureAccessor {

    @Accessor("lightPixels")
    NativeImage survivalassist$getLightPixels();

    @Accessor("lightTexture")
    DynamicTexture survivalassist$getLightTexture();
}
