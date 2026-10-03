package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 鸡蛋连点投掷：玩家右键使用鸡蛋后，自动连续投掷"当前手持"的鸡蛋，
 * 直到手里没有鸡蛋为止。只投手上的鸡蛋，不扫描背包、不切换槽位。
 *
 * Forge 实现：由主类在 PlayerInteractEvent.RightClickItem 中调用 onItemUse 进入连点状态，
 * onTick 负责持续投掷。
 */
public class EggThrowerFeature {

    private static boolean throwing = false;
    private static int cooldown = 0;
    private static int startSelectedSlot = -1;

    public static void init() {
    }

    /** 玩家右键使用物品时调用：若为鸡蛋则进入连点状态。 */
    public static void onItemUse(Player player, InteractionHand hand) {
        if (!ModConfig.get().enableEggThrower) {
            return;
        }
        if (player.getItemInHand(hand).is(Items.EGG)) {
            throwing = true;
            cooldown = 0;
            startSelectedSlot = player.getInventory().selected;
        }
    }

    public static void onTick(Minecraft client) {
        if (!ModConfig.get().enableEggThrower || !throwing) {
            return;
        }
        if (client.player == null || client.gameMode == null || client.getConnection() == null) {
            throwing = false;
            return;
        }
        if (client.player.isSpectator()) {
            throwing = false;
            return;
        }
        // 玩家切换了快捷栏槽位 → 立即停止连点投掷
        if (client.player.getInventory().selected != startSelectedSlot) {
            throwing = false;
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // 只投当前手持的鸡蛋，不扫描背包、不切换槽位
        ItemStack main = client.player.getMainHandItem();
        ItemStack off = client.player.getOffhandItem();

        if (main.is(Items.EGG)) {
            client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
            cooldown = ModConfig.get().eggThrowInterval;
        } else if (off.is(Items.EGG)) {
            client.gameMode.useItem(client.player, InteractionHand.OFF_HAND);
            cooldown = ModConfig.get().eggThrowInterval;
        } else {
            throwing = false; // 手里没有鸡蛋了
        }
    }
}
