package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

public class HealthDisplayFeature {

    /** 满亮度 light 值（与文字渲染一致） */
    private static final int FULL_LIGHT = 0xF000F0;

    public static void init() {
    }

    /**
     * 在世界渲染阶段（AFTER_ENTITIES）绘制头顶血条。
     *
     * 血条使用与文字背景完全相同的渲染层 RenderType.textBackground()，
     * 与文字一起提交到同一个 bufferSource、统一 flush、同一深度行为。
     */
    public static void render(RenderLevelStageEvent event) {
        ModConfig cfg = ModConfig.get();
        if (!cfg.enableHealthDisplay) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            return;
        }
        // 按下 F1 隐藏 GUI 时，血条与文字一并隐藏
        if (client.options.hideGui) {
            return;
        }

        Camera camera = event.getCamera();
        PoseStack matrices = event.getPoseStack();
        if (camera == null || matrices == null) {
            return;
        }
        MultiBufferSource.BufferSource consumers = client.renderBuffers().bufferSource();

        float tickDelta = event.getPartialTick();
        Vec3 camPos = camera.getPosition();
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        Font font = client.font;

        // 血条图层：POSITION_COLOR 格式（位置+颜色），支持半透明，适合手绘四边形
        VertexConsumer bar = consumers.getBuffer(RenderType.debugQuads());

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (living == client.player) {
                continue;
            }
            if (!living.isAlive() || living.isRemoved()) {
                continue;
            }
            if (living.distanceToSqr(client.player) > cfg.healthDisplayRange * cfg.healthDisplayRange) {
                continue;
            }

            // 插值位置（移动流畅）
            Vec3 lerped = living.getPosition(tickDelta);
            Vec3 anchor = lerped.add(0.0, living.getBbHeight() + 0.35, 0.0);

            // 不穿墙：实心完整方块遮挡时不显示
            if (!isVisible(client, camPos, anchor)) {
                continue;
            }

            float ratio = Mth.clamp(living.getHealth() / living.getMaxHealth(), 0.0f, 1.0f);

            matrices.pushPose();
            matrices.translate(anchor.x - camPos.x, anchor.y - camPos.y, anchor.z - camPos.z);
            matrices.mulPose(dispatcher.cameraOrientation());
            matrices.scale(-0.022f, -0.022f, 0.022f);
            Matrix4f m = matrices.last().pose();

            // ===== 血条本体（TEXT_BACKGROUND 层，与文字同一管线）=====
            float halfW = cfg.healthBarWidth / 2.0f;
            float halfH = 3.6f;

            // 背景条
            quad(bar, m, -halfW, -halfH, halfW, halfH, 0.0f, 0.0f, 0.0f, 0.6f);
            // 前景条（绿->黄->红）
            float fillW = halfW * 2.0f * ratio;
            int color = ratio > 0.5f ? 0xFF00E000
                    : (ratio > 0.25f ? 0xFFFFCC00 : 0xFFFF2200);
            float r = ((color >> 16) & 0xFF) / 255.0f;
            float g = ((color >> 8) & 0xFF) / 255.0f;
            float b = (color & 0xFF) / 255.0f;
            quad(bar, m, -halfW, -halfH, -halfW + fillW, halfH, r, g, b, 1.0f);

            // ===== 文字（同一 consumers）=====
            // 名字：血条上方居中
            String name = living.getDisplayName().getString();
            float nameW = font.width(name);
            font.drawInBatch(name, -nameW / 2.0f, -halfH - font.lineHeight - 2.0f,
                    0xFFFFFFFF, false, m, consumers, Font.DisplayMode.NORMAL, 0x80000000, FULL_LIGHT);

            // 血量数字：血条中间居中，无背景
            if (cfg.healthShowNumbers) {
                String hp = Math.round(living.getHealth()) + "/" + Math.round(living.getMaxHealth());
                float hpW = font.width(hp);
                font.drawInBatch(hp, -hpW / 2.0f, -font.lineHeight / 2.0f,
                        0xFFFFFFFF, false, m, consumers,
                        Font.DisplayMode.NORMAL, 0x00000000, FULL_LIGHT);
            }

            matrices.popPose();
        }

        // 本阶段结束统一 flush（与 Fabric consumers 一致）
        consumers.endBatch();
    }

    /**
     * 可见性检测：从相机到目标逐步采样，仅当途中遇到"实心完整方块"时判定为遮挡。
     */
    private static boolean isVisible(Minecraft client, Vec3 from, Vec3 to) {
        Level world = client.level;
        if (world == null) {
            return false;
        }
        Vec3 delta = to.subtract(from);
        double dist = delta.length();
        if (dist < 0.05) {
            return true;
        }
        Vec3 dir = delta.normalize();
        double step = 0.5;
        for (double d = step; d < dist; d += step) {
            double px = from.x + dir.x * d;
            double py = from.y + dir.y * d;
            double pz = from.z + dir.z * d;
            BlockPos bp = BlockPos.containing(px, py, pz);
            if (world.getBlockState(bp).isSolidRender(world, bp)) {
                return false;
            }
        }
        return true;
    }

    /** 彩色矩形（DEBUG_QUADS / POSITION_COLOR 格式：位置 + 颜色，无需光照与纹理） */
    private static void quad(VertexConsumer consumer, Matrix4f matrix,
                             float x0, float y0, float x1, float y1,
                             float r, float g, float b, float a) {
        consumer.vertex(matrix, x0, y0, 0.0f).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, x0, y1, 0.0f).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, x1, y1, 0.0f).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, x1, y0, 0.0f).color(r, g, b, a).endVertex();
    }
}
