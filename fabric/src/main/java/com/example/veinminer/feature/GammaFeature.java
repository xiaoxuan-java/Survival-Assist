package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import net.minecraft.client.MinecraftClient;

/**
 * 伽马值（亮度）调节：突破原版 gamma 上限，实现客户端真正超亮。
 *
 * 原版 gamma 是 SimpleOption&lt;Double&gt;，范围 0.0~1.0（默认 0.5），超过 1.0 会被
 * 校验回退到默认值，且 LightmapTextureManager 最终会把亮度 clamp 到 [0,1]。
 *
 * 因此本功能分两段：
 *  - 0~100%：映射到原版 gamma（100% = 原版最亮，gamma=1.0），走原版管线。
 *  - 100% 以上：额外通过 Mixin 对光照贴图纹理做乘法提亮（超亮部分叠加在原版 gamma 之上）。
 *
 * 本类只负责把配置换算成两个值，真正的提亮由 {@code LightmapTextureManagerMixin} 读取。
 */
public class GammaFeature {

    /** 光照贴图额外提亮系数（1.0 = 不额外提亮），由 Mixin 每帧读取。 */
    private static float extraBrightness = 1.0f;

    public static void init() {
    }

    public static void onTick(MinecraftClient client) {
        ModConfig cfg = ModConfig.get();
        if (client == null || client.options == null) {
            return;
        }
        if (!cfg.enableGamma) {
            return;
        }

        int v = Math.max(0, cfg.gamma);

        // 0~100 映射原版 gamma
        float vanillaGamma = Math.min(100, v) / 100.0f;
        double current = client.options.getGamma().getValue();
        if (Math.abs(current - (double) vanillaGamma) > 0.0001) {
            client.options.getGamma().setValue((double) vanillaGamma);
        }

        // 超过 100 的部分 → 额外乘性提亮系数（100 = 1.0 倍，每 100 提高 1 倍）
        extraBrightness = 1.0f + Math.max(0, v - 100) / 100.0f;
    }

    /** 供 Mixin 读取：额外提亮系数。 */
    public static float getExtraBrightness() {
        ModConfig cfg = ModConfig.get();
        if (!cfg.enableGamma) {
            return 1.0f;
        }
        return extraBrightness;
    }
}
