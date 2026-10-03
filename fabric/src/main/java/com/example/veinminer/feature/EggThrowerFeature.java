package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;

/**
 * 鸡蛋连点投掷：玩家右键使用鸡蛋后，自动连续投掷"当前手持"的鸡蛋，
 * 直到手里没有鸡蛋为止。只投手上的鸡蛋，不扫描背包、不切换槽位。
 *
 * 实现要点：
 * - 通过 UseItemCallback 检测玩家使用鸡蛋，进入"连点投掷"状态。
 * - 每若干 tick 自动投掷一个当前手持（主手或副手）的鸡蛋。
 * - 鸡蛋 use 在客户端会 decrement（原版行为），所以 interactItem 后客户端数量
 *   立即减少，手里那叠投完即自动停止，无需手动同步。
 */
public class EggThrowerFeature {

    private static boolean throwing = false;
    private static int cooldown = 0;
    private static int startSelectedSlot = -1;

    public static void init() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!ModConfig.get().enableEggThrower) {
                return TypedActionResult.pass(ItemStack.EMPTY);
            }
            if (player.getStackInHand(hand).isOf(Items.EGG)) {
                throwing = true;
                cooldown = 0;
                startSelectedSlot = player.getInventory().selectedSlot;
            }
            return TypedActionResult.pass(ItemStack.EMPTY);
        });
    }

    public static void onTick(MinecraftClient client) {
        if (!ModConfig.get().enableEggThrower || !throwing) {
            return;
        }
        if (client.player == null || client.interactionManager == null || client.getNetworkHandler() == null) {
            throwing = false;
            return;
        }
        if (client.player.isSpectator()) {
            throwing = false;
            return;
        }
        // 玩家切换了快捷栏槽位 → 立即停止连点投掷
        if (client.player.getInventory().selectedSlot != startSelectedSlot) {
            throwing = false;
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // 只投当前手持的鸡蛋，不扫描背包、不切换槽位
        ItemStack main = client.player.getMainHandStack();
        ItemStack off = client.player.getOffHandStack();

        if (main.isOf(Items.EGG)) {
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            cooldown = ModConfig.get().eggThrowInterval;
        } else if (off.isOf(Items.EGG)) {
            client.interactionManager.interactItem(client.player, Hand.OFF_HAND);
            cooldown = ModConfig.get().eggThrowInterval;
        } else {
            throwing = false; // 手里没有鸡蛋了
        }
    }
}
