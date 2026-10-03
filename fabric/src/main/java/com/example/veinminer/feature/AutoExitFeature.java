package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.text.Text;

/**
 * 自动退出（AutoExit）：
 * - 血量低于配置百分比（默认 30%）自动退出服务器
 * - 手中镐子耐久低于配置百分比（默认 30%）自动退出服务器
 * - 仅在连远程服务器时生效（client.getServer()==null 表示连的是远程服务器；
 *   单机集成服务器 getServer()!=null，退出就是关游戏，无意义，跳过）
 *
 * 触发退出后（doExit）：
 * 1. 关闭自动挖矿（forceStop）
 * 2. 关闭矿透（disable）
 * 3. 关闭本检测（enableAutoExit=false 并持久化）——避免重连后因血量/耐久仍 ≤30 被再次踢出、
 *    导致"一直进不去服务器"。
 * 用户重新进游戏后如需保护，可到菜单手动重新开启「自动退出保护」。
 */
public class AutoExitFeature {

    // 防重复触发：触发退出后置位，避免同一状态每 tick 重复 disconnect
    private static boolean triggered = false;

    // 世界切换/重连时重置
    private static boolean lastConnected = false;

    public static void init() {
    }

    public static void reset() {
        triggered = false;
        lastConnected = false;
    }

    public static void onTick(MinecraftClient client) {
        ModConfig cfg = ModConfig.get();
        if (!cfg.enableAutoExit) {
            return;
        }

        ClientPlayerEntity player = client.player;
        boolean connected = player != null && client.world != null && client.getNetworkHandler() != null;

        // 连接状态变化（刚进入世界/重连）→ 重置触发标志
        if (connected != lastConnected) {
            lastConnected = connected;
            if (connected) {
                triggered = false;
            }
        }

        if (!connected) {
            return;
        }

        // 仅在连远程服务器时生效（单机集成服务器跳过）
        if (client.getServer() != null) {
            return;
        }
        if (triggered) {
            return;
        }

        // 血量检查
        float health = player.getHealth();
        float maxHealth = player.getMaxHealth();
        float healthPercent = maxHealth > 0 ? (health / maxHealth) * 100.0f : 100.0f;

        if (healthPercent < cfg.exitHealthPercent) {
            doExit(client, "血量过低 (" + String.format("%.0f", healthPercent) + "%)，自动退出服务器");
            return;
        }

        // 镐子耐久检查（主手）
        ItemStack mainHand = player.getMainHandStack();
        if (mainHand.getItem() instanceof PickaxeItem && mainHand.isDamageable()) {
            int damage = mainHand.getDamage();
            int maxDamage = mainHand.getMaxDamage();
            if (maxDamage > 0) {
                float remainPercent = (1.0f - (float) damage / maxDamage) * 100.0f;
                if (remainPercent < cfg.exitPickaxePercent) {
                    doExit(client, "镐子耐久过低 (" + String.format("%.0f", remainPercent) + "%)，自动退出服务器");
                }
            }
        }
    }

    private static void doExit(MinecraftClient client, String reason) {
        triggered = true;

        // 1. 关闭自动挖矿（释放按键、取消挖掘）
        AutoMiningFeature.forceStop();
        // 2. 关闭矿透（恢复正常渲染）
        XRayFeature.disable();
        // 3. 关闭本检测并持久化，避免重连后再次被踢出
        ModConfig cfg = ModConfig.get();
        cfg.enableAutoExit = false;
        ModConfig.save();

        if (client.player != null) {
            client.player.sendMessage(Text.literal("§c[自动退出] §f" + reason
                    + "。已关闭自动挖矿/矿透/自动退出检测，重连后可手动重新开启。"), false);
        }
        client.disconnect();
    }
}
