package com.example.veinminer.mixin;

import com.example.veinminer.feature.GammaFeature;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 突破原版 gamma 上限：在光照贴图纹理生成并 upload 之后，对每个像素做乘法提亮。
 *
 * 原版 LightmapTextureManager.update 会把亮度 clamp 到 [0,1]，gamma 也限制在 [0,1]。
 * 这里在纹理 upload 后读取图像，将 RGB 分量乘以额外亮度系数（&gt;1 时提亮，可达超亮），
 * 再重新 upload，实现客户端本地"超亮"效果，不影响服务端。
 */
@Mixin(LightmapTextureManager.class)
public abstract class LightmapTextureManagerMixin {

    @Shadow
    @Final
    private NativeImage image;

    @Shadow
    @Final
    private NativeImageBackedTexture texture;

    @Inject(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/texture/NativeImageBackedTexture;upload()V",
                    shift = At.Shift.AFTER
            )
    )
    private void survivalassist$applyExtraBrightness(float delta, CallbackInfo ci) {
        float brightness = GammaFeature.getExtraBrightness();
        if (Math.abs(brightness - 1.0f) < 0.001f) {
            return;
        }

        int width = this.image.getWidth();
        int height = this.image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = this.image.getColor(x, y);
                // 布局为 ABGR（低 8 位 R，往上依次 G、B、A）
                int a = (color >>> 24) & 0xFF;
                int r = (color) & 0xFF;
                int g = (color >>> 8) & 0xFF;
                int b = (color >>> 16) & 0xFF;

                r = Math.min(255, (int) (r * brightness));
                g = Math.min(255, (int) (g * brightness));
                b = Math.min(255, (int) (b * brightness));

                int newColor = (a << 24) | (b << 16) | (g << 8) | r;
                this.image.setColor(x, y, newColor);
            }
        }
        this.texture.upload();
    }
}
