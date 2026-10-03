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
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

public class VeinMinerFeature {

    // 当前用于渲染白色边框的方块列表（预览阶段 = 准星连通集合；挖掘阶段 = 剩余待挖目标）
    private static final List<BlockPos> outlineBlocks = new ArrayList<>();

    // 长按期间被监控的"种子方块"（即玩家准星指向、正要挖的那个方块）
    private static BlockPos monitoredPos = null;

    // 缓存的连通方块集合（预览白框内容，也是连锁目标）
    private static List<BlockPos> cachedChain = null;

    // ===== 挖掘调度器状态（仅远程服务器路径使用） =====
    private static final List<BlockPos> pendingQueue = new ArrayList<>();
    private static BlockPos activePos = null;
    private static int activePosStartTick = 0;
    private static final List<BlockPos> outlineTarget = new ArrayList<>();

    // ===== 连通计算缓存 =====
    private static BlockPos lastAimPos = null;

    // ===== 掉落物合并状态（仅远程服务器路径使用） =====
    private static BlockPos dropAnchor = null;
    private static final Set<ItemEntity> preExistingDrops = new HashSet<>();
    private static final List<BlockPos> minedBlocks = new ArrayList<>();

    public static void init() {
    }

    public static void onTick(Minecraft client) {
        ModConfig cfg = ModConfig.get();

        if (client.player == null || client.level == null) {
            resetAll();
            return;
        }

        boolean mining = !pendingQueue.isEmpty() || activePos != null;

        // 1) 持续推进连锁挖掘（远程服务器路径）
        if (mining) {
            processVeinMine(client);
        }

        if (!cfg.enableVeinMiner) {
            resetAll();
            return;
        }

        boolean pressed = SurvivalAssistMod.VEIN_MINE_KEY.isDown();

        // 2) 挖掘进行中：白框固定显示剩余待挖方块
        if (mining) {
            outlineBlocks.clear();
            outlineBlocks.addAll(outlineTarget);
            if (!pressed) {
                resetPreview();
            }
            return;
        }

        // 3) 非挖掘状态：正常预览逻辑
        outlineBlocks.clear();

        if (!pressed) {
            resetPreview();
            return;
        }

        Level world = client.level;

        // 检测：监控的种子方块是否已被挖掉（严格"挖完才触发"）
        if (monitoredPos != null && world.getBlockState(monitoredPos).isAir()) {
            List<BlockPos> chain = cachedChain != null ? cachedChain : new ArrayList<>();
            if (chain.isEmpty()) {
                chain = new ArrayList<>();
                chain.add(monitoredPos);
            }

            if (client.player != null) {
                client.player.swing(InteractionHand.MAIN_HAND);
            }

            // 单机（集成服务器）：直接调服务端破坏，一次性挖完 + 合并掉落
            if (client.getSingleplayerServer() != null) {
                triggerSingleplayerBreak(client, chain);
            } else {
                // 远程服务器：走客户端发包调度
                pendingQueue.clear();
                minedBlocks.clear();
                outlineTarget.clear();
                for (BlockPos p : chain) {
                    if (!p.equals(monitoredPos)) {
                        pendingQueue.add(p);
                    }
                }
                minedBlocks.addAll(chain);
                outlineTarget.addAll(chain);
                dropAnchor = monitoredPos.immutable();
                beginDropMerge(client);
            }

            monitoredPos = null;
            cachedChain = null;
            lastAimPos = null;
            return;
        }

        // 更新准星种子与白框（预览）
        HitResult hit = client.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            monitoredPos = null;
            cachedChain = null;
            lastAimPos = null;
            return;
        }

        BlockPos aimPos = blockHit.getBlockPos();
        BlockState aimState = world.getBlockState(aimPos);

        if (aimState.isAir() || !aimState.canHarvestBlock(world, aimPos, client.player)) {
            monitoredPos = null;
            cachedChain = null;
            lastAimPos = null;
            return;
        }

        if (!aimPos.equals(lastAimPos) || cachedChain == null) {
            cachedChain = computeConnectable(client, aimPos);
            lastAimPos = aimPos;
        }
        outlineBlocks.addAll(cachedChain);
        monitoredPos = aimPos;
    }

    private static void resetPreview() {
        monitoredPos = null;
        cachedChain = null;
        lastAimPos = null;
        outlineBlocks.clear();
    }

    private static void resetAll() {
        resetPreview();
        pendingQueue.clear();
        activePos = null;
        activePosStartTick = 0;
        outlineTarget.clear();
        dropAnchor = null;
        minedBlocks.clear();
        preExistingDrops.clear();
    }

    private static void triggerSingleplayerBreak(Minecraft client, List<BlockPos> chain) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        final ResourceKey<Level> dimKey = client.level.dimension();
        final UUID playerUuid = client.player.getUUID();
        final BlockPos anchor = monitoredPos != null
                ? monitoredPos.immutable()
                : (chain.isEmpty() ? null : chain.get(0).immutable());
        final List<BlockPos> blocks = new ArrayList<>(chain);

        server.execute(() -> {
            ServerLevel sw = server.getLevel(dimKey);
            ServerPlayer spe = server.getPlayerList().getPlayer(playerUuid);
            if (sw == null || spe == null) {
                return;
            }

            // 快照已存在的掉落物
            Set<ItemEntity> pre = new HashSet<>();
            for (BlockPos p : blocks) {
                AABB box = new AABB(p).inflate(1.5);
                for (ItemEntity ie : sw.getEntitiesOfClass(ItemEntity.class, box, e -> true)) {
                    pre.add(ie);
                }
            }

            // 一次性破坏所有连锁方块（destroyBlock 无距离限制，自带掉落+工具损耗）
            for (BlockPos p : blocks) {
                BlockState state = sw.getBlockState(p);
                if (state.isAir()) {
                    continue;
                }
                spe.gameMode.destroyBlock(p);
            }

            // 合并本次新掉落物到锚点
            if (anchor != null) {
                for (BlockPos p : blocks) {
                    AABB box = new AABB(p).inflate(1.5);
                    for (ItemEntity ie : sw.getEntitiesOfClass(ItemEntity.class, box, e -> true)) {
                        if (!pre.contains(ie)) {
                            ie.setPos(anchor.getX() + 0.5, anchor.getY() + 1.0, anchor.getZ() + 0.5);
                            ie.setDeltaMovement(0.0, 0.0, 0.0);
                            ie.setDefaultPickUpDelay();
                        }
                    }
                }
            }
        });
    }

    private static void processVeinMine(Minecraft client) {
        ClientPacketListener handler = client.getConnection();
        Level world = client.level;
        LocalPlayer player = client.player;
        if (handler == null || world == null || player == null) {
            return;
        }

        if (activePos != null) {
            if (world.getBlockState(activePos).isAir()) {
                outlineTarget.remove(activePos);
                activePos = null;
                activePosStartTick = 0;
            } else if (activePosStartTick > 0 && player.tickCount - activePosStartTick > 40) {
                handler.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, activePos, Direction.UP));
                outlineTarget.remove(activePos);
                activePos = null;
                activePosStartTick = 0;
            } else {
                handler.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, activePos, Direction.UP));
                return;
            }
        }

        if (activePos == null && !pendingQueue.isEmpty()) {
            BlockPos pos = pendingQueue.remove(0);
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                outlineTarget.remove(pos);
            } else {
                float delta = state.getDestroyProgress(player, world, pos);
                if (delta <= 0.0f) {
                    outlineTarget.remove(pos);
                } else {
                    handler.send(new ServerboundPlayerActionPacket(
                            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
                    if (delta >= 1.0f) {
                        handler.send(new ServerboundPlayerActionPacket(
                                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP));
                    }
                    activePos = pos;
                    activePosStartTick = player.tickCount;
                }
            }
        }

        if (activePos == null && pendingQueue.isEmpty() && dropAnchor != null) {
            finishDropMerge(client);
            dropAnchor = null;
            minedBlocks.clear();
            outlineTarget.clear();
        }
    }

    private static void beginDropMerge(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        final ResourceKey<Level> dimKey = client.level.dimension();
        final List<BlockPos> blocks = new ArrayList<>(minedBlocks);
        server.execute(() -> {
            ServerLevel sw = server.getLevel(dimKey);
            if (sw == null) {
                return;
            }
            preExistingDrops.clear();
            for (BlockPos p : blocks) {
                AABB box = new AABB(p).inflate(1.5);
                for (ItemEntity ie : sw.getEntitiesOfClass(ItemEntity.class, box, e -> true)) {
                    preExistingDrops.add(ie);
                }
            }
        });
    }

    private static void finishDropMerge(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        final ResourceKey<Level> dimKey = client.level.dimension();
        final List<BlockPos> blocks = new ArrayList<>(minedBlocks);
        final BlockPos anchor = dropAnchor;
        if (anchor == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel sw = server.getLevel(dimKey);
            if (sw == null) {
                return;
            }
            for (BlockPos p : blocks) {
                AABB box = new AABB(p).inflate(1.5);
                for (ItemEntity ie : sw.getEntitiesOfClass(ItemEntity.class, box, e -> true)) {
                    if (!preExistingDrops.contains(ie)) {
                        ie.setPos(anchor.getX() + 0.5, anchor.getY() + 1.0, anchor.getZ() + 0.5);
                        ie.setDeltaMovement(0.0, 0.0, 0.0);
                        ie.setDefaultPickUpDelay();
                    }
                }
            }
            preExistingDrops.clear();
        });
    }

    private static List<BlockPos> computeConnectable(Minecraft client, BlockPos start) {
        List<BlockPos> result = new ArrayList<>();
        ModConfig cfg = ModConfig.get();

        LocalPlayer player = client.player;
        Level world = client.level;
        if (player == null || world == null) {
            return result;
        }

        BlockState targetState = world.getBlockState(start);
        if (targetState.isAir() || !targetState.canHarvestBlock(world, start, player)) {
            return result;
        }

        int maxBlocks = Math.min(Math.max(cfg.veinMinerMaxBlocks, 1), 1024);

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty() && result.size() < maxBlocks) {
            BlockPos cur = queue.poll();
            result.add(cur);

            for (Direction dir : Direction.values()) {
                BlockPos next = cur.relative(dir);
                if (visited.contains(next)) {
                    continue;
                }
                visited.add(next);

                BlockState s = world.getBlockState(next);
                if (s.is(targetState.getBlock())) {
                    if (result.size() < maxBlocks) {
                        queue.add(next);
                    }
                }
            }
        }

        return result;
    }

    public static void renderOutline(RenderLevelStageEvent event) {
        ModConfig cfg = ModConfig.get();
        if (!cfg.enableVeinMiner) {
            return;
        }
        if (outlineBlocks.isEmpty()) {
            return;
        }

        Camera camera = event.getCamera();
        PoseStack matrices = event.getPoseStack();
        if (camera == null || matrices == null) {
            return;
        }

        Vec3 camPos = camera.getPosition();
        matrices.pushPose();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f positionMatrix = matrices.last().pose();

        RenderSystem.disableDepthTest();
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        Map<String, Integer> edgeCount = new HashMap<>();
        for (BlockPos pos : outlineBlocks) {
            for (int[][] e : boxEdges(pos)) {
                edgeCount.merge(edgeKey(e[0], e[1]), 1, Integer::sum);
            }
        }
        for (BlockPos pos : outlineBlocks) {
            for (int[][] e : boxEdges(pos)) {
                if (edgeCount.get(edgeKey(e[0], e[1])) == 1) {
                    buffer.vertex(positionMatrix, e[0][0], e[0][1], e[0][2]).color(1.0f, 1.0f, 1.0f, 1.0f).endVertex();
                    buffer.vertex(positionMatrix, e[1][0], e[1][1], e[1][2]).color(1.0f, 1.0f, 1.0f, 1.0f).endVertex();
                }
            }
        }

        tesselator.end();
        RenderSystem.enableDepthTest();
        matrices.popPose();
    }

    private static int[][][] boxEdges(BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int[] c000 = {x, y, z};
        int[] c100 = {x + 1, y, z};
        int[] c110 = {x + 1, y + 1, z};
        int[] c010 = {x, y + 1, z};
        int[] c001 = {x, y, z + 1};
        int[] c101 = {x + 1, y, z + 1};
        int[] c111 = {x + 1, y + 1, z + 1};
        int[] c011 = {x, y + 1, z + 1};
        return new int[][][]{
                {c000, c100}, {c100, c101}, {c101, c001}, {c001, c000},
                {c010, c110}, {c110, c111}, {c111, c011}, {c011, c010},
                {c000, c010}, {c100, c110}, {c101, c111}, {c001, c011}
        };
    }

    private static String edgeKey(int[] a, int[] b) {
        boolean aFirst = (a[0] != b[0]) ? a[0] < b[0]
                : (a[1] != b[1]) ? a[1] < b[1]
                : a[2] < b[2];
        int[] p = aFirst ? a : b;
        int[] q = aFirst ? b : a;
        return p[0] + "," + p[1] + "," + p[2] + "-" + q[0] + "," + q[1] + "," + q[2];
    }
}
