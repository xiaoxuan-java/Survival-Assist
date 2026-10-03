package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * 自动疾跑（ToggleSprint）：玩家按住前进键时自动进入疾跑状态，无需一直按疾跑键。
 *
 * 实现要点：每 tick 判断玩家是否"想要前进且具备疾跑条件"，满足则调用
 * {@code setSprinting(true)}。原版 LocalPlayer.tick() 会在玩家松开前进键、
 * 饥饿不足、使用物品或潜行时自动 {@code setSprinting(false)}，因此这里只需负责
 * "开启"一侧，停止逻辑由原版处理，互不冲突。
 */
public class ToggleSprintFeature {

    public static void init() {
    }

    public static void onTick(Minecraft client) {
        if (!ModConfig.get().enableAutoSprint) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || player.isSpectator()) {
            return;
        }

        boolean shouldSprint = client.options.keyUp.isDown()
                && player.getFoodData().getFoodLevel() > 6.0f
                && !player.isCrouching()
                && !player.isUsingItem()
                && !player.getAbilities().flying
                && (player.onGround() || player.isPassenger());

        if (shouldSprint) {
            player.setSprinting(true);
        }
    }
}
