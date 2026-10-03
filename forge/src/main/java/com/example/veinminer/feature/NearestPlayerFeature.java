package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;

public class NearestPlayerFeature {

    public static void init() {
    }

    public static void renderHud(GuiGraphics context, float tickDelta) {
        ModConfig cfg = ModConfig.get();
        if (!cfg.enableNearestPlayer) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            return;
        }

        Player nearest = null;
        double nearestSqDist = Double.MAX_VALUE;

        for (Player player : client.level.players()) {
            if (player == client.player) {
                continue;
            }
            if (!player.isAlive() || player.isRemoved()) {
                continue;
            }
            double d = player.distanceToSqr(client.player);
            if (d < nearestSqDist) {
                nearestSqDist = d;
                nearest = player;
            }
        }

        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();
        Font font = client.font;

        String text;
        int color;
        if (nearest == null) {
            text = "最近玩家: 无";
            color = 0xFFAAAAAA;
        } else {
            double dist = Math.sqrt(nearestSqDist);
            if (cfg.distanceShowName) {
                text = String.format("%s  %.1fm", nearest.getDisplayName().getString(), dist);
            } else {
                text = String.format("%.1fm", dist);
            }
            color = 0xFFFFFFFF;
        }

        int textWidth = font.width(text);
        // 显示在物品栏右侧（紧挨物品栏右边一点点），垂直居中对齐物品栏
        int hotbarWidth = 182;
        int hotbarLeft = (screenWidth - hotbarWidth) / 2;
        int hotbarRight = hotbarLeft + hotbarWidth;
        int x = hotbarRight + 8;
        int y = screenHeight - 22 + (22 - font.lineHeight) / 2;

        context.fill(x - 4, y - 2, x + textWidth + 4, y + font.lineHeight + 2, 0x80000000);
        context.drawString(font, text, x, y, color, true);
    }
}
