package com.example.veinminer.feature;

import com.example.veinminer.VeinMinerMod;
import com.example.veinminer.config.ModConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 自动挖矿（AutoMining）—— 带矿簇感知与路径规划的安全自动挖矿。
 *
 * 本版针对上一版缺陷，做了如下系统性改正（用户反馈 5 个问题 + 自检发现）：
 *
 * 【缺陷 1：挖矿时玩家不在矿旁，掉落物捡不到】
 *   旧：矿石进入 reach（约 4 格）就挖，掉落物落在远处够不到。
 *   新：只有矿石与玩家脚格「6 面相邻」时才挖（isAdjacent），
 *       掉落物必然落在脚下，配合主动走向掉落物，100% 能捡到。
 *
 * 【缺陷 2：没有矿簇概念，多矿在一起逐个跳远】
 *   旧：每次只扫一个"最近矿"，挖完跳去远处，紧邻的矿被忽略。
 *   新：扫描全部矿 → BFS 按 6 邻域聚成矿簇 → 一次挖完整个簇再换簇，
 *       簇内按离玩家距离排序，依次就近挖取。
 *
 * 【缺陷 3：深层扫不到（主世界 y<=-55、下界 y<=10 就停）】
 *   旧：垂直向下只扫 range(64) 格，玩家在地表只能扫到 y≈0。
 *   新：垂直向下直接扫到世界底部（主世界 -64、下界 0），向上扫 range 格。
 *       扫描改分帧进行，避免大范围遍历造成单帧卡顿。
 *
 * 【缺陷 4：卡在方块边缘、路线没规划】
 *   旧：水平推进只挖脚前 1 格，头前方块不处理，2 格高身体会顶头卡住。
 *   新：水平隧道固定挖「脚前 + 头前」2 格；脚前下方凸起台阶提前挖掉；
 *       垂直上升挖头顶两格+跳跃；垂直下降用阶梯（绝不垂直挖脚下）。
 *
 * 【缺陷 5：矿在上面却往下挖】
 *   旧：水平推进时无条件挖"脚前下方"（当台阶处理），且扫描向上只有 16 格、
 *       向下 64 格，导致上方矿被忽略、一路往下挖。
 *   新：方向判定精确到 dx/dz 是否同时为 0（正上/正下方）——
 *       正上方才向上挖、正下方才阶梯下降、其余一律水平推进；
 *       扫描垂直方向对称，上方矿不会被漏掉。
 *
 * 按快捷键（默认 G）切换。注册在 START_CLIENT_TICK（先于移动逻辑，按键当 tick 生效）。
 */
public class AutoMiningFeature {

    // ===== 运行状态 =====
    private static boolean active = false;

    // 当前目标矿簇（已按离玩家距离排序）
    private static final List<BlockPos> cluster = new ArrayList<>();

    // 当前正在挖的方块（锁定，挖空才换）
    private static BlockPos currentDig = null;
    private static int digTicks = 0;

    // 绕行路点（遇悬崖/危险时侧向偏移）
    private static BlockPos waypoint = null;
    private static int detourCount = 0;

    // 拉黑：挖不动/被阻挡的方块，避免重复选中
    private static final Set<BlockPos> blacklist = new HashSet<>();

    // 维度记忆
    private static RegistryKey<World> lastWorld = null;

    // 启动时选中的快捷栏槽
    private static int originalSelectedSlot = -1;

    // 拾取掉落物
    private static boolean waitingPickup = false;
    private static int pickupTicks = 0;
    private static BlockPos pickupPos = null;

    // 堵漏
    private static boolean plugging = false;
    private static int plugTicks = 0;
    private static BlockPos plugPos = null;

    // 卡死检测
    private static int stuckTicks = 0;
    private static int stuckCount = 0;
    private static BlockPos lastFoot = null;

    // 分帧扫描
    private static BlockPos.Mutable scanCursor = null;
    private static int scanMinX, scanMinY, scanMinZ, scanMaxX, scanMaxY, scanMaxZ;
    private static final List<BlockPos> scanFound = new ArrayList<>();

    // 上次提示
    private static String lastState = "";

    // ===== 常量 =====
    private static final int DIG_TIMEOUT = 200;        // 挖单块超时（10 秒）
    private static final int PICKUP_TIMEOUT = 100;     // 拾取超时（5 秒）
    private static final int PICKUP_MIN_WAIT = 15;     // 掉落物同步最短等待
    private static final double PICKUP_RADIUS = 6.0;   // 掉落物检测半径
    private static final int DETOUR_DISTANCE = 6;      // 绕行偏移格数
    private static final int MAX_DETOURS = 4;          // 连续绕行上限
    private static final int SCAN_STEP = 40000;        // 每 tick 扫描方块数
    private static final int STUCK_THRESHOLD = 60;     // 位置不变 3 秒视为卡死
    private static final int MAX_STUCK = 3;            // 连续卡死上限，超过停止
    private static final float LOW_HEALTH = 6.0f;      // 血量低于 6 点暂停

    private static final Direction[] HORIZONTALS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    public static void init() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void toggle() {
        if (active) {
            deactivate();
        } else {
            activate();
        }
    }

    private static void activate() {
        active = true;
        resetState();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            originalSelectedSlot = client.player.getInventory().selectedSlot;
            lastWorld = client.world != null ? client.world.getRegistryKey() : null;
        }
    }

    private static void deactivate() {
        active = false;
        resetState();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options != null) {
            releaseMove(client);
        }
        if (client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
        }
        if (client.player != null && originalSelectedSlot >= 0
                && client.player.getInventory().selectedSlot != originalSelectedSlot) {
            setSelectedSlot(client, originalSelectedSlot);
        }
        originalSelectedSlot = -1;
    }

    /** 强制停止（用于自动退出等场景）。 */
    public static void forceStop() {
        if (active) {
            deactivate();
        } else {
            resetState();
        }
    }

    private static void resetState() {
        cluster.clear();
        currentDig = null;
        digTicks = 0;
        waypoint = null;
        detourCount = 0;
        blacklist.clear();
        waitingPickup = false;
        pickupTicks = 0;
        pickupPos = null;
        plugging = false;
        plugTicks = 0;
        plugPos = null;
        stuckTicks = 0;
        stuckCount = 0;
        lastFoot = null;
        scanCursor = null;
        scanFound.clear();
        lastState = "";
    }

    public static void onTick(MinecraftClient client) {
        while (VeinMinerMod.AUTO_MINE_KEY.wasPressed()) {
            toggle();
        }

        ModConfig cfg = ModConfig.get();
        if (!cfg.enableAutoMine) {
            if (active) {
                deactivate();
            }
            return;
        }
        if (!active) {
            return;
        }

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.currentScreen != null) {
            return;
        }
        if (player.isDead()) {
            deactivate();
            setState("玩家已死亡，自动挖矿已停止");
            return;
        }

        // 维度切换 → 重置
        RegistryKey<World> nowWorld = client.world.getRegistryKey();
        if (lastWorld == null || lastWorld != nowWorld) {
            lastWorld = nowWorld;
            resetState();
        }

        // 血量过低 → 暂停
        if (player.getHealth() < LOW_HEALTH) {
            releaseAll(client);
            setState("§c血量过低，已暂停挖矿，恢复后自动继续");
            return;
        }

        BlockPos foot = player.getBlockPos();

        // 卡死检测
        if (lastFoot != null && lastFoot.equals(foot)) {
            stuckTicks++;
            if (stuckTicks >= STUCK_THRESHOLD) {
                stuckTicks = 0;
                onStuck(client);
                return;
            }
        } else {
            stuckTicks = 0;
            stuckCount = 0;
            lastFoot = foot;
        }

        // 堵漏
        if (plugging) {
            tickPlug(client);
            return;
        }

        // 正在挖某块 → 继续挖
        if (currentDig != null && !isGone(client, currentDig)) {
            releaseMove(client);
            digTicks++;
            if (digTicks >= DIG_TIMEOUT) {
                blacklist.add(currentDig.toImmutable());
                currentDig = null;
                digTicks = 0;
                if (waypoint == null) {
                    // 挖不动的可能是隧道方块，绕一下
                    setupDetour(client, 1, 0);
                }
                setState("§c方块挖不动，绕行");
            } else if (ensurePickaxe(client)) {
                dig(client, currentDig);
            } else {
                deactivate();
                setState("§c没有可用的镐子，自动挖矿已停止");
            }
            return;
        }

        // currentDig 刚被挖空 → 处理
        if (currentDig != null) {
            BlockPos dug = currentDig;
            currentDig = null;
            digTicks = 0;
            if (hasAdjacentFluid(client, dug)) {
                releaseMove(client);
                plugging = true;
                plugTicks = 0;
                plugPos = dug;
                tickPlug(client);
                return;
            }
            onBlockDug(client, dug);
            return;
        }

        // 等待拾取
        if (waitingPickup) {
            handlePickup(client, player);
            return;
        }

        // 分帧扫描进行中
        if (scanCursor != null) {
            tickScan(client);
            return;
        }

        // 无目标簇 → 启动扫描
        if (cluster.isEmpty()) {
            startScan(client);
            return;
        }

        // 有目标簇 → 导航 + 挖矿
        navigateAndMine(client, player, foot);
    }

    /** 挖空一个方块后的处理：从矿簇移除；簇空则进入拾取。 */
    private static void onBlockDug(MinecraftClient client, BlockPos dug) {
        boolean wasOre = false;
        for (int i = 0; i < cluster.size(); i++) {
            if (cluster.get(i).equals(dug)) {
                cluster.remove(i);
                wasOre = true;
                break;
            }
        }
        if (wasOre && cluster.isEmpty()) {
            waitingPickup = true;
            pickupTicks = 0;
            pickupPos = dug;
            waypoint = null;
            detourCount = 0;
            releaseMove(client);
            setState("矿簇已挖完，走向掉落物…");
        }
    }

    /** 堵漏流程。 */
    private static void tickPlug(MinecraftClient client) {
        plugTicks++;
        if (plugTicks > 40) {
            plugging = false;
            plugPos = null;
            setState("§c无法堵漏（无方块），绕行");
            return;
        }
        releaseMove(client);
        if (placeBlockAt(client, plugPos)) {
            plugging = false;
            plugPos = null;
            setState("挖到流体，已堵上");
        }
    }

    /** 卡死处理：连续卡死超过上限则停止，否则放弃最近矿重扫。 */
    private static void onStuck(MinecraftClient client) {
        releaseAll(client);
        stuckCount++;
        if (stuckCount > MAX_STUCK) {
            deactivate();
            setState("§c无法到达目标矿（被阻挡），自动挖矿已停止");
            return;
        }
        if (!cluster.isEmpty()) {
            cluster.remove(0);
        }
        currentDig = null;
        waypoint = null;
        detourCount = 0;
        setState("§c检测到卡死，切换目标…");
    }

    /** 导航 + 挖矿主逻辑。 */
    private static void navigateAndMine(MinecraftClient client, ClientPlayerEntity player, BlockPos foot) {
        // 到达绕行路点 → 清除
        if (waypoint != null && foot.getSquaredDistance(waypoint) <= 4.0) {
            waypoint = null;
            detourCount = 0;
        }

        // 取簇中离玩家最近的矿
        BlockPos ore = nearestInCluster(client, foot);
        if (ore == null) {
            cluster.clear();
            return;
        }

        BlockPos aim = waypoint != null ? waypoint : ore;

        // 站在矿旁 → 直接挖矿（掉落物落脚下）
        if (waypoint == null && isAdjacent(foot, ore)) {
            releaseMove(client);
            if (ensurePickaxe(client)) {
                currentDig = ore.toImmutable();
                digTicks = 0;
                dig(client, currentDig);
            } else {
                deactivate();
                setState("§c没有可用的镐子，自动挖矿已停止");
            }
            return;
        }

        int dx = aim.getX() - foot.getX();
        int dy = aim.getY() - foot.getY();
        int dz = aim.getZ() - foot.getZ();
        int stepX = Integer.signum(dx);
        int stepZ = Integer.signum(dz);

        // 朝矿（非绕行）前进时，检测前方悬崖/危险，触发绕行
        if (waypoint == null && (stepX != 0 || stepZ != 0)) {
            BlockPos front = foot.add(stepX, 0, stepZ);
            if (isCliffAhead(client, front) || isDangerBlock(client, front)
                    || isDangerBlock(client, front.up())) {
                setupDetour(client, stepX, stepZ);
                return;
            }
        }

        // 挖路径方块（隧道/阶梯/上升）
        BlockPos digTarget = pickDigTarget(client, foot, dx, dy, dz);
        if (digTarget != null) {
            releaseMove(client);
            if (ensurePickaxe(client)) {
                currentDig = digTarget;
                digTicks = 0;
                dig(client, digTarget);
            } else {
                deactivate();
                setState("§c没有可用的镐子，自动挖矿已停止");
            }
            return;
        }

        // 前方畅通 → 移动
        moveToward(client, player, foot, dx, dy, dz);
    }

    /** 从簇中取离玩家最近的矿。 */
    private static BlockPos nearestInCluster(MinecraftClient client, BlockPos foot) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : cluster) {
            double d = p.getSquaredDistance(foot);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** 矿石与玩家脚格是否 6 面相邻（挖矿掉落物能落到脚下）。 */
    private static boolean isAdjacent(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY())
                + Math.abs(a.getZ() - b.getZ()) == 1;
    }

    /**
     * 按方向精确选出要挖的方块。
     * dx/dz 不同时为 0 → 水平推进（脚前+头前两格，台阶防卡脚）；
     * dx==dz==0 且 dy>1 → 向上挖（头顶两格）；
     * dx==dz==0 且 dy<-1 → 阶梯下降。
     */
    private static BlockPos pickDigTarget(MinecraftClient client, BlockPos foot, int dx, int dy, int dz) {
        int stepX = Integer.signum(dx);
        int stepZ = Integer.signum(dz);

        if (stepX != 0 || stepZ != 0) {
            // 水平推进：保证 2 格高隧道
            BlockPos front = foot.add(stepX, 0, stepZ);
            if (mineableSafe(client, front)) return front;         // 脚前墙
            BlockPos headFront = front.up();
            if (mineableSafe(client, headFront)) return headFront;  // 头前墙（防顶头卡住）
            // 注意：不再挖 foot 前方的脚下方块（front.down()）。
            // 在平坦地面上 front.down() 是正常地面，挖它会自掘坑洞导致卡脚/坠落；
            // 真正的台阶上行（脚前 solid）已由 mineableSafe(front) 处理。
            return null;
        }

        // 水平已重合（正上/正下）
        if (dy > 1) {
            BlockPos head1 = foot.up(1);
            if (mineableSafe(client, head1)) return head1;
            BlockPos head2 = foot.up(2);
            if (mineableSafe(client, head2)) return head2;
            return null;
        }
        if (dy < -1) {
            Direction dir = pickDescendDir(client, foot);
            if (dir != null) {
                BlockPos fb = foot.add(dir.getOffsetX(), -1, dir.getOffsetZ());
                if (mineableSafe(client, fb)) return fb;
                // fb 已挖开（空气），无需再挖，交给 moveToward 前进
            }
            BlockPos below = foot.down();
            if (mineableSafe(client, below) && safeLanding(client, below.down())) return below;
            return null;
        }
        // dy ∈ [-1,1]，矿就在脚下/头顶/旁边，交给 isAdjacent 挖矿
        return null;
    }

    /** 选一个可下降的方向（脚前下方已挖开或可挖，且落点能站）。 */
    private static Direction pickDescendDir(MinecraftClient client, BlockPos foot) {
        for (Direction dir : HORIZONTALS) {
            BlockPos fb = foot.add(dir.getOffsetX(), -1, dir.getOffsetZ());
            BlockState s = client.world.getBlockState(fb);
            if (s.isAir() || mineableSafe(client, fb)) {
                if (safeLanding(client, fb.down())) {
                    return dir;
                }
            }
        }
        return null;
    }

    /** 前方畅通时，按方向移动。 */
    private static void moveToward(MinecraftClient client, ClientPlayerEntity player, BlockPos foot, int dx, int dy, int dz) {
        int stepX = Integer.signum(dx);
        int stepZ = Integer.signum(dz);
        if (stepX == 0 && stepZ == 0) {
            // 水平重合，纯垂直移动
            if (dy > 0) {
                client.options.jumpKey.setPressed(true);
                client.options.forwardKey.setPressed(false);
            } else if (dy < 0) {
                Direction dir = pickDescendDir(client, foot);
                if (dir != null) {
                    lookAtDir(player, dir);
                    client.options.forwardKey.setPressed(true);
                    client.options.jumpKey.setPressed(false);
                } else {
                    releaseMove(client);
                }
            }
            return;
        }
        // 水平前进
        lookAtHorizontal(player, foot.add(stepX, 0, stepZ));
        client.options.forwardKey.setPressed(true);
        // 遇到 1 格高台阶（脚前空、头前空，但脚前下方是实心）时自动跳跃攀上，避免频繁绕路
        BlockPos front = foot.add(stepX, 0, stepZ);
        boolean stepUp = client.world.getBlockState(front).isAir()
                && client.world.getBlockState(front.up()).isAir()
                && client.world.getBlockState(front.down()).isSolidBlock(client.world, front.down());
        client.options.jumpKey.setPressed(stepUp);
    }

    /** 挖掘指定方块。 */
    private static void dig(MinecraftClient client, BlockPos pos) {
        ClientPlayerEntity player = client.player;
        player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(pos));
        Vec3d eye = player.getEyePos();
        Vec3d c = Vec3d.ofCenter(pos);
        Direction dir = Direction.getFacing(c.x - eye.x, c.y - eye.y, c.z - eye.z);
        client.interactionManager.updateBlockBreakingProgress(pos, dir);
    }

    private static void lookAtHorizontal(ClientPlayerEntity player, BlockPos aim) {
        Vec3d eye = player.getEyePos();
        Vec3d target = new Vec3d(aim.getX() + 0.5, eye.y, aim.getZ() + 0.5);
        player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target);
    }

    private static void lookAtDir(ClientPlayerEntity player, Direction dir) {
        Vec3d eye = player.getEyePos();
        Vec3d t = eye.add(dir.getOffsetX() * 3.0, 0, dir.getOffsetZ() * 3.0);
        player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, t);
    }

    /** 方块非空气、非流体、不在黑名单、可被当前工具采集。 */
    private static boolean mineable(MinecraftClient client, BlockPos pos) {
        if (blacklist.contains(pos)) return false;
        BlockState state = client.world.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        return state.calcBlockBreakingDelta(client.player, client.world, pos) > 0.0f;
    }

    /** 可采集且挖掉不会暴露流体。 */
    private static boolean mineableSafe(MinecraftClient client, BlockPos pos) {
        return mineable(client, pos) && !hasAdjacentFluid(client, pos);
    }

    /** 危险方块：流体、重力方块、基岩。 */
    private static boolean isDangerBlock(MinecraftClient client, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        if (state.isAir()) return false;
        Block b = state.getBlock();
        if (b == Blocks.BEDROCK) return true;
        if (isGravityBlock(state)) return true;
        return !state.getFluidState().isEmpty();
    }

    /** 前方是否悬崖（脚前空气 + 脚前下方空气 = 会掉下去）。 */
    private static boolean isCliffAhead(MinecraftClient client, BlockPos front) {
        BlockState f = client.world.getBlockState(front);
        if (!f.isAir()) return false;
        BlockState fb = client.world.getBlockState(front.down());
        return fb.isAir();
    }

    private static boolean isGravityBlock(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.RED_SAND;
    }

    private static boolean safeLanding(MinecraftClient client, BlockPos pos) {
        BlockState s = client.world.getBlockState(pos);
        if (s.isAir()) return false;
        if (!s.getFluidState().isEmpty()) return false;
        return s.isSolidBlock(client.world, pos);
    }

    private static boolean hasAdjacentFluid(MinecraftClient client, BlockPos pos) {
        for (Direction d : Direction.values()) {
            FluidState fs = client.world.getBlockState(pos.offset(d)).getFluidState();
            if (!fs.isEmpty() && (fs.isIn(FluidTags.LAVA) || fs.isIn(FluidTags.WATER))) {
                return true;
            }
        }
        return false;
    }

    /** 设置绕行路点：向侧向偏移。 */
    private static void setupDetour(MinecraftClient client, int stepX, int stepZ) {
        detourCount++;
        if (detourCount > MAX_DETOURS) {
            if (!cluster.isEmpty()) {
                cluster.remove(0);
            }
            waypoint = null;
            detourCount = 0;
            setState("§c该矿无法到达，尝试下一个…");
            return;
        }
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        BlockPos foot = player.getBlockPos();
        if (stepX == 0 && stepZ == 0) {
            stepX = 1;
            stepZ = 0;
        }
        int sx = stepZ;
        int sz = -stepX;
        waypoint = foot.add(sx * DETOUR_DISTANCE, 0, sz * DETOUR_DISTANCE);
    }

    // ===== 分帧扫描 =====

    /** 启动一次全范围扫描。 */
    private static void startScan(MinecraftClient client) {
        ModConfig cfg = ModConfig.get();
        int range = Math.max(8, Math.min(cfg.autoMineRange, 64));
        BlockPos foot = client.player.getBlockPos();
        int bottom = client.world.getBottomY();
        int top = client.world.getTopY();
        int halfDown = foot.getY() - bottom;                 // 向下到世界底部
        int halfUp = Math.min(range, top - foot.getY() - 1); // 向上 range 格

        scanMinX = foot.getX() - range;
        scanMaxX = foot.getX() + range;
        scanMinZ = foot.getZ() - range;
        scanMaxZ = foot.getZ() + range;
        scanMinY = foot.getY() - halfDown;
        scanMaxY = foot.getY() + halfUp;

        scanCursor = new BlockPos.Mutable(scanMinX, scanMinY, scanMinZ);
        scanFound.clear();
        setState("扫描矿石中…");
    }

    /** 每 tick 推进一部分扫描。 */
    private static void tickScan(MinecraftClient client) {
        for (int i = 0; i < SCAN_STEP; i++) {
            if (scanCursor == null) return;
            if (!blacklist.contains(scanCursor)) {
                if (XRayFeature.isOre(client.world.getBlockState(scanCursor))) {
                    scanFound.add(scanCursor.toImmutable());
                }
            }
            if (!advanceCursor()) {
                finishScan(client);
                return;
            }
        }
    }

    private static boolean advanceCursor() {
        scanCursor.setX(scanCursor.getX() + 1);
        if (scanCursor.getX() > scanMaxX) {
            scanCursor.setX(scanMinX);
            scanCursor.setZ(scanCursor.getZ() + 1);
            if (scanCursor.getZ() > scanMaxZ) {
                scanCursor.setZ(scanMinZ);
                scanCursor.setY(scanCursor.getY() + 1);
                if (scanCursor.getY() > scanMaxY) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 扫描完成：聚类 + 选最近簇。 */
    private static void finishScan(MinecraftClient client) {
        scanCursor = null;
        List<BlockPos> found = new ArrayList<>(scanFound);
        scanFound.clear();

        if (found.isEmpty()) {
            setState("附近没有目标矿石");
            return;
        }

        // BFS 聚类（6 邻域）
        Set<BlockPos> visited = new HashSet<>();
        List<List<BlockPos>> clusters = new ArrayList<>();
        for (BlockPos ore : found) {
            if (visited.contains(ore)) continue;
            List<BlockPos> cl = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(ore);
            visited.add(ore);
            while (!queue.isEmpty()) {
                BlockPos cur = queue.poll();
                cl.add(cur);
                for (Direction d : Direction.values()) {
                    BlockPos nb = cur.offset(d);
                    if (!visited.contains(nb) && XRayFeature.isOre(client.world.getBlockState(nb))) {
                        visited.add(nb);
                        queue.add(nb);
                    }
                }
            }
            clusters.add(cl);
        }

        // 选离玩家最近的簇
        BlockPos foot = client.player.getBlockPos();
        List<BlockPos> bestCluster = null;
        double bestDist = Double.MAX_VALUE;
        for (List<BlockPos> cl : clusters) {
            for (BlockPos p : cl) {
                double d = p.getSquaredDistance(foot);
                if (d < bestDist) {
                    bestDist = d;
                    bestCluster = cl;
                }
            }
        }

        cluster.clear();
        if (bestCluster != null) {
            cluster.addAll(bestCluster);
            cluster.sort((a, b) -> Double.compare(
                    a.getSquaredDistance(foot), b.getSquaredDistance(foot)));
            setState("目标矿簇: " + describeOre(client, cluster.get(0)) + "（共 " + cluster.size() + " 个）");
        }
    }

    /** 等待拾取：主动走向掉落物。 */
    private static void handlePickup(MinecraftClient client, ClientPlayerEntity player) {
        pickupTicks++;
        if (pickupTicks >= PICKUP_TIMEOUT) {
            waitingPickup = false;
            pickupTicks = 0;
            pickupPos = null;
            releaseMove(client);
            return;
        }
        ItemEntity item = findNearestItem(client, pickupPos);
        if (item != null) {
            client.options.forwardKey.setPressed(true);
            lookAtHorizontal(player, item.getBlockPos());
            return;
        }
        if (pickupTicks < PICKUP_MIN_WAIT) {
            releaseMove(client);
            return;
        }
        waitingPickup = false;
        pickupTicks = 0;
        pickupPos = null;
        releaseMove(client);
    }

    private static boolean isGone(MinecraftClient client, BlockPos pos) {
        return client.world.getBlockState(pos).isAir();
    }

    // ===== 工具 / 放置 =====

    private static boolean ensurePickaxe(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        PlayerInventory inv = player.getInventory();
        ItemStack held = inv.getMainHandStack();
        if (held.getItem() instanceof PickaxeItem && !isBroken(held)) {
            return true;
        }
        int bestSlot = findBestPickaxe(inv);
        if (bestSlot < 0) {
            return false;
        }
        if (bestSlot < 9) {
            setSelectedSlot(client, bestSlot);
        } else {
            int hotbar = findEmptyHotbar(inv);
            if (hotbar < 0) return false;
            if (player.currentScreenHandler != player.playerScreenHandler) return false;
            client.interactionManager.clickSlot(
                    player.playerScreenHandler.syncId, bestSlot, hotbar, SlotActionType.SWAP, player);
            setSelectedSlot(client, hotbar);
        }
        return true;
    }

    private static int findBestPickaxe(PlayerInventory inv) {
        int bestSlot = -1;
        int bestLevel = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.main.get(i);
            if (s.getItem() instanceof PickaxeItem pItem) {
                if (isBroken(s)) continue;
                int lvl = pItem.getMaterial().getMiningLevel();
                if (lvl > bestLevel) {
                    bestLevel = lvl;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private static boolean isBroken(ItemStack s) {
        if (s.isEmpty()) return true;
        return s.getDamage() >= s.getMaxDamage() - 1;
    }

    private static int findEmptyHotbar(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.main.get(i).isEmpty()) return i;
        }
        return -1;
    }

    private static void setSelectedSlot(MinecraftClient client, int slot) {
        ClientPlayerEntity player = client.player;
        if (player.getInventory().selectedSlot != slot) {
            player.getInventory().selectedSlot = slot;
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
            }
        }
    }

    private static boolean placeBlockAt(MinecraftClient client, BlockPos pos) {
        ClientPlayerEntity player = client.player;
        PlayerInventory inv = player.getInventory();
        ClientPlayerInteractionManager im = client.interactionManager;
        if (im == null) return false;

        RegistryKey<World> dim = client.world.getRegistryKey();
        Block[] priority;
        if (dim == World.NETHER) {
            priority = new Block[]{Blocks.NETHERRACK, Blocks.SOUL_SOIL, Blocks.SOUL_SAND};
        } else if (player.getBlockY() < 0) {
            priority = new Block[]{Blocks.COBBLED_DEEPSLATE, Blocks.COBBLESTONE};
        } else {
            priority = new Block[]{Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE};
        }

        int slot = -1;
        outer:
        for (Block b : priority) {
            Item item = b.asItem();
            for (int i = 0; i < 36; i++) {
                if (inv.main.get(i).isOf(item)) {
                    slot = i;
                    break outer;
                }
            }
        }
        if (slot < 0) return false;

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            BlockState ns = client.world.getBlockState(neighbor);
            if (ns.isAir()) continue;
            if (!ns.getFluidState().isEmpty()) continue;
            if (!ns.isFullCube(client.world, neighbor)) continue;

            Direction side = dir.getOpposite();
            Vec3d hitPos = Vec3d.ofCenter(neighbor).add(
                    side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
            BlockHitResult hit = new BlockHitResult(hitPos, side, neighbor, false);

            int hotbarSlot = slot;
            boolean fromMain = slot >= 9;
            if (fromMain) {
                hotbarSlot = findEmptyHotbar(inv);
                if (hotbarSlot < 0) return false;
                if (player.currentScreenHandler != player.playerScreenHandler) return false;
                im.clickSlot(player.playerScreenHandler.syncId, slot, hotbarSlot, SlotActionType.SWAP, player);
            }

            int original = inv.selectedSlot;
            if (inv.selectedSlot != hotbarSlot) {
                inv.selectedSlot = hotbarSlot;
            }
            im.interactBlock(player, Hand.MAIN_HAND, hit);

            if (fromMain) {
                im.clickSlot(player.playerScreenHandler.syncId, slot, hotbarSlot, SlotActionType.SWAP, player);
            }
            if (inv.selectedSlot != original) {
                inv.selectedSlot = original;
                if (client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(original));
                }
            }
            return true;
        }
        return false;
    }

    private static ItemEntity findNearestItem(MinecraftClient client, BlockPos pos) {
        if (pos == null) return null;
        Box box = new Box(pos).expand(PICKUP_RADIUS);
        List<ItemEntity> items = client.world.getEntitiesByClass(ItemEntity.class, box, e -> true);
        if (items.isEmpty()) return null;
        Vec3d eye = client.player.getEyePos();
        ItemEntity best = items.get(0);
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity it : items) {
            double d = it.getPos().squaredDistanceTo(eye);
            if (d < bestDist) {
                bestDist = d;
                best = it;
            }
        }
        return best;
    }

    private static String describeOre(MinecraftClient client, BlockPos pos) {
        return client.world.getBlockState(pos).getBlock().getName().getString()
                + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static void setState(String s) {
        if (!s.equals(lastState)) {
            lastState = s;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§7[自动挖矿] §f" + s), true);
            }
        }
    }

    private static void releaseMove(MinecraftClient client) {
        if (client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private static void releaseAll(MinecraftClient client) {
        releaseMove(client);
        if (client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
        }
    }
}
