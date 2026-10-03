package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import com.example.veinminer.mixin.FishingBobberEntityAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

/**
 * 自动钓鱼：第一次抛竿由玩家手动完成，之后检测到浮漂下沉（有鱼上钩）自动收竿，
 * 收竿后自动抛竿，进入全自动循环。
 *
 * 实现要点：
 * - 玩家主手或副手持有鱼竿才工作，否则待机。
 * - 抛竿与收竿都复用原版 interactItem（右键）：fishHook 为空时右键即抛竿，
 *   fishHook 非空时右键即收竿，逻辑由原版 FishingRodItem.use 决定。
 * - 通过 Mixin accessor 读取浮漂的 caughtFish 标志（服务端置位后同步到客户端，
 *   浮漂会下沉），一旦为 true 立即收竿。
 * - 第一次抛竿不自动：只有进入过"已抛竿"状态（fishHook != null）后，
 *   autoCastEnabled 才置位，之后收竿完成才会自动抛下一竿。
 * - 抛竿/收竿之间用冷却避免网络同步延迟导致的重复操作。
 */
public class AutoFishFeature {

    // 抛竿后等待浮漂实体建立；收竿后等待鱼回来、浮漂实体被移除
    private static int recastCooldown = 0;
    // 是否已进入自动抛竿循环（第一次抛竿需玩家手动）
    private static boolean autoCastEnabled = false;

    public static void init() {
    }

    public static void onTick(MinecraftClient client) {
        if (!ModConfig.get().enableAutoFish) {
            return;
        }
        PlayerEntity player = client.player;
        if (player == null || player.isSpectator() || client.interactionManager == null) {
            return;
        }

        if (recastCooldown > 0) {
            recastCooldown--;
        }

        // 定位鱼竿所在手
        Hand rodHand = null;
        if (player.getMainHandStack().isOf(Items.FISHING_ROD)) {
            rodHand = Hand.MAIN_HAND;
        } else if (player.getOffHandStack().isOf(Items.FISHING_ROD)) {
            rodHand = Hand.OFF_HAND;
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

        FishingBobberEntity hook = player.fishHook;
        if (hook == null) {
            // 尚未抛竿：第一次由玩家手动抛，只有激活自动循环后才自动抛
            if (autoCastEnabled && recastCooldown <= 0) {
                client.interactionManager.interactItem(player, rodHand);
                recastCooldown = 15;
            }
        } else {
            // 已有浮漂（已抛过竿）→ 激活自动抛竿循环
            autoCastEnabled = true;
            if (((FishingBobberEntityAccessor) hook).survivalassist$getCaughtFish()) {
                // 浮漂下沉，有鱼上钩 → 收竿
                client.interactionManager.interactItem(player, rodHand);
                recastCooldown = 20;
            }
        }
    }
}
