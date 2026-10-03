package com.example.veinminer.feature;

import com.example.veinminer.VeinMinerMod;
import com.example.veinminer.config.ModConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
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
    // 待挖掘方块队列（触发连锁后填充）
    private static final List<BlockPos> pendingQueue = new ArrayList<>();
    // 当前正在挖掘的方块（已发送 START，等待其变为空气）
    private static BlockPos activePos = null;
    // 开始挖掘 activePos 时的客户端 tick 计数，用于防卡死超时
    private static int activePosStartTick = 0;
    // 连锁目标全集（触发后固定，挖掉一个移除一个），白框据此显示"剩余待挖方块"
    private static final List<BlockPos> outlineTarget = new ArrayList<>();

    // ===== 连通计算缓存 =====
    // 上次计算连通集合时的准星方块；准星未变则不重算，避免每 tick 跑 BFS
    private static BlockPos lastAimPos = null;

    // ===== 掉落物合并状态（仅远程服务器路径使用） =====
    private static BlockPos dropAnchor = null;
    private static final Set<ItemEntity> preExistingDrops = new HashSet<>();
    private static final List<BlockPos> minedBlocks = new ArrayList<>();

    public static void init() {
    }

    /**
     * 每客户端 tick 调用。
     *
     * 交互逻辑：
     * - 长按连锁键：实时显示同种连通方块的白色透视边框（预览）
     * - 长按期间玩家左键挖掘准星指向的那个方块
     * - 严格"挖完才触发"：只有当那个方块真正被挖掉后，才触发连锁
     * - 单机：触发后直接调服务端 tryBreakBlock 一次性挖完（绕过 6 方块距离限制）
     * - 服务器：触发后走客户端发包调度（受原版 6 方块距离限制）
     */
    public static void onTick(MinecraftClient client) {
        ModConfig cfg = ModConfig.get();

        if (client.player == null || client.world == null) {
            resetAll();
            return;
        }

        boolean mining = !pendingQueue.isEmpty() || activePos != null;

        // ===== 1) 持续推进连锁挖掘（远程服务器路径）=====
        if (mining) {
            processVeinMine(client);
        }

        if (!cfg.enableVeinMiner) {
            resetAll();
            return;
        }

        boolean pressed = VeinMinerMod.VEIN_MINE_KEY.isPressed();

        // ===== 2) 挖掘进行中：白框固定显示剩余待挖方块，与准星解耦 =====
        if (mining) {
            outlineBlocks.clear();
            outlineBlocks.addAll(outlineTarget);
            if (!pressed) {
                resetPreview();
            }
            return;
        }

        // ===== 3) 非挖掘状态：正常预览逻辑 =====
        outlineBlocks.clear();

        if (!pressed) {
            resetPreview();
            return;
        }

        World world = client.world;

        // 检测：监控的种子方块是否已被挖掉（严格"挖完才触发"）
        if (monitoredPos != null
                && world.getBlockState(monitoredPos).isAir()) {
            List<BlockPos> chain = cachedChain != null ? cachedChain : new ArrayList<>();
            if (chain.isEmpty()) {
                chain = new ArrayList<>();
                chain.add(monitoredPos);
            }

            if (client.player != null) {
                client.player.swingHand(Hand.MAIN_HAND);
            }

            // 单机（集成服务器）：直接调服务端破坏，一次性挖完 + 合并掉落
            if (client.getServer() != null) {
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
                dropAnchor = monitoredPos.toImmutable();
                beginDropMerge(client);
            }

            // 触发后清空预览（白框清空，单机方块已被破坏，服务器进入 mining 分支显示剩余目标）
            monitoredPos = null;
            cachedChain = null;
            lastAimPos = null;
            return;
        }

        // 更新准星种子与白框（预览）；准星未变则不重算连通集合
        HitResult hit = client.crosshairTarget;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            monitoredPos = null;
            cachedChain = null;
            lastAimPos = null;
            return;
        }

        BlockPos aimPos = blockHit.getBlockPos();
        BlockState aimState = world.getBlockState(aimPos);

        if (aimState.isAir() || !client.player.canHarvest(aimState)) {
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

    /** 清空预览状态（松键时调用），保留挖掘队列让连锁继续挖完。 */
    private static void resetPreview() {
        monitoredPos = null;
        cachedChain = null;
        lastAimPos = null;
        outlineBlocks.clear();
    }

    /** 清空所有状态（世界切换/功能关闭时调用）。 */
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

    /**
     * 单机（集成服务器）连锁：在服务端线程一次性破坏全部连锁方块，
     * 绕过 6 方块距离限制，并在破坏完成后把新掉落物合并到锚点位置。
     */
    private static void triggerSingleplayerBreak(MinecraftClient client, List<BlockPos> chain) {
        MinecraftServer server = client.getServer();
        if (server == null) {
            return;
        }
        final RegistryKey<World> dimKey = client.world.getRegistryKey();
        final UUID playerUuid = client.player.getUuid();
        final BlockPos anchor = monitoredPos != null
                ? monitoredPos.toImmutable()
                : (chain.isEmpty() ? null : chain.get(0).toImmutable());
        final List<BlockPos> blocks = new ArrayList<>(chain);

        server.execute(() -> {
            ServerWorld sw = server.getWorld(dimKey);
            ServerPlayerEntity spe = server.getPlayerManager().getPlayer(playerUuid);
            if (sw == null || spe == null) {
                return;
            }

            // 快照已存在的掉落物（避免误合并玩家原本就有的掉落）
            Set<ItemEntity> pre = new HashSet<>();
            for (BlockPos p : blocks) {
                Box box = new Box(p).expand(1.5);
                for (ItemEntity ie : sw.getEntitiesByClass(ItemEntity.class, box, e -> true)) {
                    pre.add(ie);
                }
            }

            // 一次性破坏所有连锁方块（tryBreakBlock 无距离限制，自带掉落+工具损耗）
            for (BlockPos p : blocks) {
                BlockState state = sw.getBlockState(p);
                if (state.isAir()) {
                    continue;
                }
                spe.interactionManager.tryBreakBlock(p);
            }

            // 合并本次新掉落物到锚点
            if (anchor != null) {
                for (BlockPos p : blocks) {
                    Box box = new Box(p).expand(1.5);
                    for (ItemEntity ie : sw.getEntitiesByClass(ItemEntity.class, box, e -> true)) {
                        if (!pre.contains(ie)) {
                            ie.setPosition(anchor.getX() + 0.5, anchor.getY() + 1.0, anchor.getZ() + 0.5);
                            ie.setVelocity(0.0, 0.0, 0.0);
                            ie.setToDefaultPickupDelay();
                        }
                    }
                }
            }
        });
    }

    /**
     * 挖掘调度器（仅远程服务器路径）：逐块挖掘，一次只针对一个方块发 START，
     * 随后每 tick 发 STOP 直到客户端观察到该方块变为空气。
     */
    private static void processVeinMine(MinecraftClient client) {
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        World world = client.world;
        PlayerEntity player = client.player;
        if (handler == null || world == null || player == null) {
            return;
        }

        // 有方块正在挖掘：发 STOP，直到观察到它变空气
        if (activePos != null) {
            if (world.getBlockState(activePos).isAir()) {
                outlineTarget.remove(activePos); // 已挖掉，从白框中移除
                activePos = null;
                activePosStartTick = 0;
            } else if (activePosStartTick > 0 && client.player.age - activePosStartTick > 40) {
                // 防卡死：等了 2 秒还没挖掉，放弃该方块继续挖下一个
                handler.sendPacket(new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, activePos, Direction.UP));
                outlineTarget.remove(activePos);
                activePos = null;
                activePosStartTick = 0;
            } else {
                handler.sendPacket(new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, activePos, Direction.UP));
                return;
            }
        }

        // 处理队列：逐块挖掘，一次只启动一个方块，等它真正变空气再挖下一个
        if (activePos == null && !pendingQueue.isEmpty()) {
            BlockPos pos = pendingQueue.remove(0);
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                outlineTarget.remove(pos);
            } else {
                float delta = state.calcBlockBreakingDelta(player, world, pos);
                if (delta <= 0.0f) {
                    outlineTarget.remove(pos);
                } else {
                    handler.sendPacket(new PlayerActionC2SPacket(
                            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
                    if (delta >= 1.0f) {
                        handler.sendPacket(new PlayerActionC2SPacket(
                                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP));
                    }
                    activePos = pos;
                    activePosStartTick = client.player.age;
                }
            }
        }

        // 连锁完成：队列清空且无正在挖掘的方块 → 合并掉落物、清空目标
        if (activePos == null && pendingQueue.isEmpty() && dropAnchor != null) {
            finishDropMerge(client);
            dropAnchor = null;
            minedBlocks.clear();
            outlineTarget.clear();
        }
    }

    /**
     * 连锁开始前，在服务端线程快照锚点附近已存在的掉落物。
     */
    private static void beginDropMerge(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        if (server == null) {
            return;
        }
        final RegistryKey<World> dimKey = client.world.getRegistryKey();
        final List<BlockPos> blocks = new ArrayList<>(minedBlocks);
        server.execute(() -> {
            ServerWorld sw = server.getWorld(dimKey);
            if (sw == null) {
                return;
            }
            preExistingDrops.clear();
            for (BlockPos p : blocks) {
                Box box = new Box(p).expand(1.5);
                for (ItemEntity ie : sw.getEntitiesByClass(ItemEntity.class, box, e -> true)) {
                    preExistingDrops.add(ie);
                }
            }
        });
    }

    /**
     * 连锁结束后，把本次新掉落的物品传送到锚点位置。
     */
    private static void finishDropMerge(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        if (server == null) {
            return;
        }
        final RegistryKey<World> dimKey = client.world.getRegistryKey();
        final List<BlockPos> blocks = new ArrayList<>(minedBlocks);
        final BlockPos anchor = dropAnchor;
        if (anchor == null) {
            return;
        }
        server.execute(() -> {
            ServerWorld sw = server.getWorld(dimKey);
            if (sw == null) {
                return;
            }
            for (BlockPos p : blocks) {
                Box box = new Box(p).expand(1.5);
                for (ItemEntity ie : sw.getEntitiesByClass(ItemEntity.class, box, e -> true)) {
                    if (!preExistingDrops.contains(ie)) {
                        ie.setPosition(anchor.getX() + 0.5, anchor.getY() + 1.0, anchor.getZ() + 0.5);
                        ie.setVelocity(0.0, 0.0, 0.0);
                        ie.setToDefaultPickupDelay();
                    }
                }
            }
            preExistingDrops.clear();
        });
    }

    /**
     * 从指定方块出发，计算与其同种、且可被当前工具采集的连通方块集合，上限由配置决定。
     */
    private static List<BlockPos> computeConnectable(MinecraftClient client, BlockPos start) {
        List<BlockPos> result = new ArrayList<>();
        ModConfig cfg = ModConfig.get();

        PlayerEntity player = client.player;
        World world = client.world;
        if (player == null || world == null) {
            return result;
        }

        BlockState targetState = world.getBlockState(start);
        if (targetState.isAir() || !player.canHarvest(targetState)) {
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
                BlockPos next = cur.offset(dir);
                if (visited.contains(next)) {
                    continue;
                }
                visited.add(next);

                BlockState s = world.getBlockState(next);
                if (s.isOf(targetState.getBlock())) {
                    if (result.size() < maxBlocks) {
                        queue.add(next);
                    }
                }
            }
        }

        return result;
    }

    /**
     * 渲染所有可连锁方块的透视白色边框（立即绘制，禁用深度测试实现透视）。
     * 只绘制整体外轮廓，相邻方块共享的内部棱不绘制。
     */
    public static void renderOutline(WorldRenderContext context) {
        ModConfig cfg = ModConfig.get();
        if (!cfg.enableVeinMiner) {
            return;
        }
        if (outlineBlocks.isEmpty()) {
            return;
        }

        Camera camera = context.camera();
        MatrixStack matrices = context.matrixStack();
        if (camera == null || matrices == null) {
            return;
        }

        Vec3d camPos = camera.getPos();
        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f positionMatrix = matrices.peek().getPositionMatrix();

        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        // 统计每条棱被多少个方块共享，只绘制只属于一个方块的棱（外轮廓）。
        Map<String, Integer> edgeCount = new HashMap<>();
        for (BlockPos pos : outlineBlocks) {
            for (int[][] e : boxEdges(pos)) {
                edgeCount.merge(edgeKey(e[0], e[1]), 1, Integer::sum);
            }
        }
        for (BlockPos pos : outlineBlocks) {
            for (int[][] e : boxEdges(pos)) {
                if (edgeCount.get(edgeKey(e[0], e[1])) == 1) {
                    buffer.vertex(positionMatrix, e[0][0], e[0][1], e[0][2]).color(1.0f, 1.0f, 1.0f, 1.0f).next();
                    buffer.vertex(positionMatrix, e[1][0], e[1][1], e[1][2]).color(1.0f, 1.0f, 1.0f, 1.0f).next();
                }
            }
        }

        tessellator.draw();
        RenderSystem.enableDepthTest();
        matrices.pop();
    }

    /**
     * 返回一个方块 12 条棱的两个端点坐标。
     */
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
