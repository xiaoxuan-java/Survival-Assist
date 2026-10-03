package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import com.example.veinminer.mixin.FishingHookAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;

/**
 * 自动钓鱼：第一次抛竿由玩家手动完成，之后检测到浮漂下沉（有鱼上钩）自动收竿，
 * 收竿后自动抛竿，进入全自动循环。
 *
 * Forge 映射：player.fishHook -> player.fishing；interactItem -> gameMode.useItem。
 * 浮漂下沉标志经 FishingHookAccessor 读取 FishingHook.biting。
 */
public class AutoFishFeature {

    // 抛竿后等待浮漂实体建立；收竿后等待鱼回来、浮漂实体被移除
    private static int recastCooldown = 0;
    // 是否已进入自动抛竿循环（第一次抛竿需玩家手动）
    private static boolean autoCastEnabled = false;

    public static void init() {
    }

    public static void onTick(Minecraft client) {
        if (!ModConfig.get().enableAutoFish) {
            return;
        }
        Player player = client.player;
        if (player == null || player.isSpectator() || client.gameMode == null) {
            return;
        }

        if (recastCooldown > 0) {
            recastCooldown--;
        }

        // 定位鱼竿所在手
        InteractionHand rodHand = null;
        if (player.getMainHandItem().is(Items.FISHING_ROD)) {
            rodHand = InteractionHand.MAIN_HAND;
        } else if (player.getOffhandItem().is(Items.FISHING_ROD)) {
            rodHand = InteractionHand.OFF_HAND;
        }
        if (rodHand == null) {
            recastCooldown = 0;
            autoCastEnabled = false;
            return;
        }
        // 正在使用物品（如拉弓）时不打扰
        if (player.isUsingItem()) {
            return;
        }

        FishingHook hook = player.fishing;
        if (hook == null) {
            // 尚未抛竿：第一次由玩家手动抛，只有激活自动循环后才自动抛
            if (autoCastEnabled && recastCooldown <= 0) {
                client.gameMode.useItem(player, rodHand);
                recastCooldown = 15;
            }
        } else {
            // 已有浮漂（已抛过竿）→ 激活自动抛竿循环
            autoCastEnabled = true;
            if (((FishingHookAccessor) hook).survivalassist$getBiting()) {
                // 浮漂下沉，有鱼上钩 → 收竿
                client.gameMode.useItem(player, rodHand);
                recastCooldown = 20;
            }
        }
    }
}
