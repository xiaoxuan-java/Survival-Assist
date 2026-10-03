package com.example.veinminer.feature;

import com.example.veinminer.config.ModConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.GameRenderer;import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;

public class HealthDisplayFeature {

    /** 满亮度 light 值（与文字渲染一致） */
    private static final int FULL_LIGHT = 0xF000F0;

    public static void init() {
    }

    /**
     * 在世界渲染阶段绘制头顶血条。
     *
     * 血条使用独立的 Tessellator buffer 立即绘制：
     *   BufferBuilder buffer = Tessellator.getInstance().getBuffer();
     *   buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
     *   ... 写入顶点 ...
     *   tessellator.draw();
     *
     * 为什么不共用 context.consumers() 的共享 buffer：
     * 共享 BufferBuilder 可能已被同一渲染阶段的其它代码占用（currentElementId != 0），
     * 此时调用 next() 会在 BufferBuilder.next() 内部抛
     * "Not filled all elements of the vertex" 导致游戏崩溃。
     * 自己 begin() 的独立 buffer 初始状态干净，写入顺序完全可控。
     *
     * 顶点格式 POSITION_COLOR = 位置 + 颜色（两个元素），
     * 所以每个顶点只需 vertex(...).color(...).next()，不需要 light()/normal()。
     */
    public static void render(WorldRenderContext context) {
        ModConfig cfg = ModConfig.get();
        if (!cfg.enableHealthDisplay) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            return;
        }
        // 按下 F1 隐藏 GUI 时，血条与文字一并隐藏
        if (client.options.hudHidden) {
            return;
        }

        Camera camera = context.camera();
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (camera == null || matrices == null || consumers == null) {
            return;
        }

        float tickDelta = context.tickDelta();
        Vec3d camPos = camera.getPos();
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        TextRenderer textRenderer = client.textRenderer;

        // ===== 血条：独立 buffer 立即绘制 =====
        // 半透明混合 + 关闭深度写入（血条常驻在实体上方，不被自身深度遮挡）
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (living == client.player) {
                continue;
            }
            if (!living.isAlive() || living.isRemoved()) {
                continue;
            }
            if (living.squaredDistanceTo(client.player) > cfg.healthDisplayRange * cfg.healthDisplayRange) {
                continue;
            }

            // 插值位置（移动流畅）
            Vec3d lerped = living.getLerpedPos(tickDelta);
            Vec3d anchor = lerped.add(0.0, living.getHeight() + 0.35, 0.0);

            // 不穿墙：实心完整方块遮挡时不显示
            if (!isVisible(client, camPos, anchor)) {
                continue;
            }

            float ratio = MathHelper.clamp(living.getHealth() / living.getMaxHealth(), 0.0f, 1.0f);

            matrices.push();
            matrices.translate(anchor.x - camPos.x, anchor.y - camPos.y, anchor.z - camPos.z);
            matrices.multiply(dispatcher.getRotation());
            matrices.scale(-0.022f, -0.022f, 0.022f);
            Matrix4f m = matrices.peek().getPositionMatrix();

            // 每个实体一段 buffer：begin -> 写入 -> draw
            buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

            float halfW = cfg.healthBarWidth / 2.0f;
            float halfH = 3.6f;

            // 背景条（半透明黑）
            quad(buffer, m, -halfW, -halfH, halfW, halfH, 0.0f, 0.0f, 0.0f, 0.6f);
            // 前景条（血量比例，绿->黄->红）
            float fillW = halfW * 2.0f * ratio;
            int color = ratio > 0.5f ? 0xFF00E000
                    : (ratio > 0.25f ? 0xFFFFCC00 : 0xFFFF2200);
            float r = ((color >> 16) & 0xFF) / 255.0f;
            float g = ((color >> 8) & 0xFF) / 255.0f;
            float b = (color & 0xFF) / 255.0f;
            quad(buffer, m, -halfW, -halfH, -halfW + fillW, halfH, r, g, b, 1.0f);

            tessellator.draw();
            matrices.pop();
        }

        // 恢复渲染状态
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();

        // ===== 文字（走共享 consumers，需在血条之后绘制）=====
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (living == client.player) {
                continue;
            }
            if (!living.isAlive() || living.isRemoved()) {
                continue;
            }
            if (living.squaredDistanceTo(client.player) > cfg.healthDisplayRange * cfg.healthDisplayRange) {
                continue;
            }

            Vec3d lerped = living.getLerpedPos(tickDelta);
            Vec3d anchor = lerped.add(0.0, living.getHeight() + 0.35, 0.0);
            if (!isVisible(client, camPos, anchor)) {
                continue;
            }

            float halfH = 3.6f;
            matrices.push();
            matrices.translate(anchor.x - camPos.x, anchor.y - camPos.y, anchor.z - camPos.z);
            matrices.multiply(dispatcher.getRotation());
            matrices.scale(-0.022f, -0.022f, 0.022f);
            Matrix4f m = matrices.peek().getPositionMatrix();

            // 名字：血条上方居中
            String name = living.getDisplayName().getString();
            float nameW = textRenderer.getWidth(name);
            textRenderer.draw(name, -nameW / 2.0f, -halfH - textRenderer.fontHeight - 2.0f,
                    0xFFFFFFFF, false, m, consumers,
                    TextRenderer.TextLayerType.NORMAL, 0x80000000, FULL_LIGHT);

            // 血量数字：血条中间居中，无背景（血条充当数字的底）
            if (cfg.healthShowNumbers) {
                String hp = Math.round(living.getHealth()) + "/" + Math.round(living.getMaxHealth());
                float hpW = textRenderer.getWidth(hp);
                textRenderer.draw(hp, -hpW / 2.0f, -textRenderer.fontHeight / 2.0f,
                        0xFFFFFFFF, false, m, consumers,
                        TextRenderer.TextLayerType.NORMAL, 0x00000000, FULL_LIGHT);
            }

            matrices.pop();
        }
    }

    /**
     * 可见性检测：从相机到目标逐步采样，仅当途中遇到"实心完整方块"（isOpaqueFullCube）时判定为遮挡。
     */
    private static boolean isVisible(MinecraftClient client, Vec3d from, Vec3d to) {
        World world = client.world;
        if (world == null) {
            return false;
        }
        Vec3d delta = to.subtract(from);
        double dist = delta.length();
        if (dist < 0.05) {
            return true;
        }
        Vec3d dir = delta.normalize();
        double step = 0.5;
        for (double d = step; d < dist; d += step) {
            double px = from.x + dir.x * d;
            double py = from.y + dir.y * d;
            double pz = from.z + dir.z * d;
            BlockPos bp = BlockPos.ofFloored(px, py, pz);
            if (world.getBlockState(bp).isOpaqueFullCube(world, bp)) {
                return false;
            }
        }
        return true;
    }

    /** 彩色矩形（POSITION_COLOR 格式：位置 + 颜色，无需光照与纹理） */
    private static void quad(BufferBuilder buffer, Matrix4f matrix,
                             float x0, float y0, float x1, float y1,
                             float r, float g, float b, float a) {
        buffer.vertex(matrix, x0, y0, 0.0f).color(r, g, b, a).next();
        buffer.vertex(matrix, x0, y1, 0.0f).color(r, g, b, a).next();
        buffer.vertex(matrix, x1, y1, 0.0f).color(r, g, b, a).next();
        buffer.vertex(matrix, x1, y0, 0.0f).color(r, g, b, a).next();
    }
}
