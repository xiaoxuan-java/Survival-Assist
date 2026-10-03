package com.example.veinminer.feature;

import com.example.veinminer.SurvivalAssistMod;
import com.example.veinminer.config.ModConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 矿透（X-Ray）：
 * - 非矿石方块直接不渲染（由 BlockRenderDispatcherMixin 拦截 renderBatched）
 * - 矿石发光描边 + 透视高亮（此处在世界渲染层画发光边框）
 * - 按快捷键（默认 X）切换开关
 *
 * 矿石种类与自动挖矿共享同一份勾选配置（autoMine* 字段）。
 */
public class XRayFeature {

    private static boolean active = false;

    // 缓存扫描到的矿石（每 N tick 重扫一次，避免每帧全量扫描）
    private static final List<BlockPos> oreBlocks = new ArrayList<>();
    private static int scanTimer = 0;

    public static void init() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void toggle() {
        active = !active;
        oreBlocks.clear();
        scanTimer = 0;
    }

    /** 强制关闭（用于自动退出等场景）。 */
    public static void disable() {
        active = false;
        oreBlocks.clear();
        scanTimer = 0;
    }

    public static void onTick(Minecraft client) {
        while (SurvivalAssistMod.XRAY_KEY.consumeClick()) {
            toggle();
        }
        if (!active) {
            return;
        }
        if (client.player == null || client.level == null) {
            return;
        }
        if (--scanTimer <= 0) {
            scanTimer = 10; // 每 10 tick（0.5 秒）重扫一次
            rescan(client);
        }
    }

    private static void rescan(Minecraft client) {
        oreBlocks.clear();
        ModConfig cfg = ModConfig.get();
        int range = Math.max(8, Math.min(cfg.xrayRange, 64));
        BlockPos center = client.player.blockPosition();
        int halfY = Math.min(range, 48);
        BlockPos from = center.offset(-range, -halfY, -range);
        BlockPos to = center.offset(range, halfY, range);
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            if (isOre(client.level.getBlockState(pos))) {
                oreBlocks.add(pos.immutable());
            }
        }
    }

    /** 判断方块是否为目标矿石（矿透与自动挖矿共享的勾选列表）。 */
    public static boolean isOre(BlockState state) {
        ModConfig cfg = ModConfig.get();
        Block b = state.getBlock();
        if (cfg.autoMineCoal && (b == Blocks.COAL_ORE || b == Blocks.DEEPSLATE_COAL_ORE)) return true;
        if (cfg.autoMineIron && (b == Blocks.IRON_ORE || b == Blocks.DEEPSLATE_IRON_ORE)) return true;
        if (cfg.autoMineCopper && (b == Blocks.COPPER_ORE || b == Blocks.DEEPSLATE_COPPER_ORE)) return true;
        if (cfg.autoMineGold && (b == Blocks.GOLD_ORE || b == Blocks.DEEPSLATE_GOLD_ORE)) return true;
        if (cfg.autoMineRedstone && (b == Blocks.REDSTONE_ORE || b == Blocks.DEEPSLATE_REDSTONE_ORE)) return true;
        if (cfg.autoMineLapis && (b == Blocks.LAPIS_ORE || b == Blocks.DEEPSLATE_LAPIS_ORE)) return true;
        if (cfg.autoMineDiamond && (b == Blocks.DIAMOND_ORE || b == Blocks.DEEPSLATE_DIAMOND_ORE)) return true;
        if (cfg.autoMineEmerald && (b == Blocks.EMERALD_ORE || b == Blocks.DEEPSLATE_EMERALD_ORE)) return true;
        if (cfg.autoMineNetherQuartz && b == Blocks.NETHER_QUARTZ_ORE) return true;
        if (cfg.autoMineNetherGold && b == Blocks.NETHER_GOLD_ORE) return true;
        if (cfg.autoMineAncientDebris && b == Blocks.ANCIENT_DEBRIS) return true;
        return isCustomOre(cfg, b);
    }

    /** 判断是否为用户自定义矿石（按方块注册 ID 匹配）。 */
    private static final java.util.Set<String> customOreCache = new java.util.HashSet<>();
    private static String lastCustomOreList = "\u0000";

    private static boolean isCustomOre(ModConfig cfg, Block b) {
        String list = cfg.customOreList == null ? "" : cfg.customOreList;
        if (!list.equals(lastCustomOreList)) {
            lastCustomOreList = list;
            customOreCache.clear();
            for (String part : list.split(",")) {
                String id = part.trim();
                if (!id.isEmpty()) customOreCache.add(id);
            }
        }
        ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(b);
        return rl != null && customOreCache.contains(rl.toString());
    }

    /** 返回矿石对应的发光颜色 (r,g,b)。 */
    private static float[] oreColor(Block b) {
        if (b == Blocks.COAL_ORE || b == Blocks.DEEPSLATE_COAL_ORE) return new float[]{0.15f, 0.15f, 0.15f};
        if (b == Blocks.IRON_ORE || b == Blocks.DEEPSLATE_IRON_ORE) return new float[]{0.85f, 0.75f, 0.6f};
        if (b == Blocks.COPPER_ORE || b == Blocks.DEEPSLATE_COPPER_ORE) return new float[]{1.0f, 0.55f, 0.25f};
        if (b == Blocks.GOLD_ORE || b == Blocks.DEEPSLATE_GOLD_ORE || b == Blocks.NETHER_GOLD_ORE) return new float[]{1.0f, 0.9f, 0.2f};
        if (b == Blocks.REDSTONE_ORE || b == Blocks.DEEPSLATE_REDSTONE_ORE) return new float[]{1.0f, 0.15f, 0.15f};
        if (b == Blocks.LAPIS_ORE || b == Blocks.DEEPSLATE_LAPIS_ORE) return new float[]{0.2f, 0.4f, 1.0f};
        if (b == Blocks.DIAMOND_ORE || b == Blocks.DEEPSLATE_DIAMOND_ORE) return new float[]{0.3f, 0.9f, 1.0f};
        if (b == Blocks.EMERALD_ORE || b == Blocks.DEEPSLATE_EMERALD_ORE) return new float[]{0.2f, 1.0f, 0.4f};
        if (b == Blocks.NETHER_QUARTZ_ORE) return new float[]{0.95f, 0.95f, 1.0f};
        if (b == Blocks.ANCIENT_DEBRIS) return new float[]{0.55f, 0.3f, 0.1f};
        if (isCustomOre(ModConfig.get(), b)) return new float[]{1.0f, 0.3f, 1.0f};
        return new float[]{1.0f, 1.0f, 1.0f};
    }

    /**
     * 渲染矿石的透视发光描边（禁用深度测试实现透视）。
     * 纯线条模式：只画亮色线框，不画面填充。
     */
    public static void renderOres(RenderLevelStageEvent event) {
        if (!active || oreBlocks.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }

        Camera camera = event.getCamera();
        PoseStack matrices = event.getPoseStack();
        if (camera == null || matrices == null) {
            return;
        }

        net.minecraft.world.phys.Vec3 camPos = camera.getPosition();
        matrices.pushPose();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f pm = matrices.last().pose();

        RenderSystem.disableDepthTest();
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();

        // 纯线条：亮色线框描边
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (BlockPos pos : oreBlocks) {
            Block b = client.level.getBlockState(pos).getBlock();
            float[] c = oreColor(b);
            drawEdges(buffer, pm, pos, c[0], c[1], c[2], 1.0f);
        }
        tesselator.end();

        RenderSystem.enableDepthTest();
        matrices.popPose();
    }

    /** 画一个方块 12 条棱。 */
    private static void drawEdges(BufferBuilder b, Matrix4f pm, BlockPos pos, float r, float g, float bl, float a) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int[][] v = {
                {x, y, z}, {x + 1, y, z}, {x, y + 1, z}, {x + 1, y + 1, z},
                {x, y, z + 1}, {x + 1, y, z + 1}, {x, y + 1, z + 1}, {x + 1, y + 1, z + 1}
        };
        int[][] edges = {
                {0, 1}, {1, 5}, {5, 4}, {4, 0},   // 下环
                {2, 3}, {3, 7}, {7, 6}, {6, 2},   // 上环
                {0, 2}, {1, 3}, {5, 7}, {4, 6}    // 竖棱
        };
        for (int[] e : edges) {
            int[] a0 = v[e[0]];
            int[] a1 = v[e[1]];
            b.vertex(pm, a0[0], a0[1], a0[2]).color(r, g, bl, a).endVertex();
            b.vertex(pm, a1[0], a1[1], a1[2]).color(r, g, bl, a).endVertex();
        }
    }
}
