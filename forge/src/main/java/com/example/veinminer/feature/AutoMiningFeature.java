package com.example.veinminer.feature;

import com.example.veinminer.SurvivalAssistMod;
import com.example.veinminer.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 自动挖矿（Forge 版）：带矿簇感知与路径规划的安全自动挖矿。
 * 与 Fabric 版逻辑一致，API 换成 Forge official 映射。
 */
public class AutoMiningFeature {

    private static boolean active = false;

    private static final List<BlockPos> cluster = new ArrayList<>();
    private static BlockPos currentDig = null;
    private static int digTicks = 0;

    private static BlockPos waypoint = null;
    private static int detourCount = 0;

    private static final Set<BlockPos> blacklist = new HashSet<>();

    private static ResourceKey<Level> lastWorld = null;
    private static int originalSelectedSlot = -1;

    private static boolean waitingPickup = false;
    private static int pickupTicks = 0;
    private static BlockPos pickupPos = null;

    private static boolean plugging = false;
    private static int plugTicks = 0;
    private static BlockPos plugPos = null;

    private static int stuckTicks = 0;
    private static int stuckCount = 0;
    private static BlockPos lastFoot = null;

    private static BlockPos.MutableBlockPos scanCursor = null;
    private static int scanMinX, scanMinY, scanMinZ, scanMaxX, scanMaxY, scanMaxZ;
    private static final List<BlockPos> scanFound = new ArrayList<>();

    private static String lastState = "";

    private static final int DIG_TIMEOUT = 200;
    private static final int PICKUP_TIMEOUT = 100;
    private static final int PICKUP_MIN_WAIT = 15;
    private static final double PICKUP_RADIUS = 6.0;
    private static final int DETOUR_DISTANCE = 6;
    private static final int MAX_DETOURS = 4;
    private static final int SCAN_STEP = 40000;
    private static final int STUCK_THRESHOLD = 60;
    private static final int MAX_STUCK = 3;
    private static final float LOW_HEALTH = 6.0f;

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
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            originalSelectedSlot = client.player.getInventory().selected;
            lastWorld = client.level != null ? client.level.dimension() : null;
        }
    }

    private static void deactivate() {
        active = false;
        resetState();
        Minecraft client = Minecraft.getInstance();
        if (client.options != null) {
            releaseMove(client);
        }
        if (client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
        if (client.player != null && originalSelectedSlot >= 0
                && client.player.getInventory().selected != originalSelectedSlot) {
            setSelectedSlot(client, originalSelectedSlot);
        }
        originalSelectedSlot = -1;
    }

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

    public static void onTick(Minecraft client) {
        while (SurvivalAssistMod.AUTO_MINE_KEY.consumeClick()) {
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

        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.screen != null) {
            return;
        }
        if (player.isDeadOrDying()) {
            deactivate();
            setState("玩家已死亡，自动挖矿已停止");
            return;
        }

        ResourceKey<Level> nowWorld = client.level.dimension();
        if (lastWorld == null || lastWorld != nowWorld) {
            lastWorld = nowWorld;
            resetState();
        }

        if (player.getHealth() < LOW_HEALTH) {
            releaseAll(client);
            setState("§c血量过低，已暂停挖矿，恢复后自动继续");
            return;
        }

        BlockPos foot = player.blockPosition();

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

        if (plugging) {
            tickPlug(client);
            return;
        }

        if (currentDig != null && !isGone(client, currentDig)) {
            releaseMove(client);
            digTicks++;
            if (digTicks >= DIG_TIMEOUT) {
                blacklist.add(currentDig.immutable());
                currentDig = null;
                digTicks = 0;
                if (waypoint == null) {
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

        if (waitingPickup) {
            handlePickup(client, player);
            return;
        }

        if (scanCursor != null) {
            tickScan(client);
            return;
        }

        if (cluster.isEmpty()) {
            startScan(client);
            return;
        }

        navigateAndMine(client, player, foot);
    }

    private static void onBlockDug(Minecraft client, BlockPos dug) {
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

    private static void tickPlug(Minecraft client) {
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

    private static void onStuck(Minecraft client) {
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

    private static void navigateAndMine(Minecraft client, LocalPlayer player, BlockPos foot) {
        if (waypoint != null && foot.distSqr(waypoint) <= 4.0) {
            waypoint = null;
            detourCount = 0;
        }

        BlockPos ore = nearestInCluster(client, foot);
        if (ore == null) {
            cluster.clear();
            return;
        }

        BlockPos aim = waypoint != null ? waypoint : ore;

        if (waypoint == null && isAdjacent(foot, ore)) {
            releaseMove(client);
            if (ensurePickaxe(client)) {
                currentDig = ore.immutable();
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

        if (waypoint == null && (stepX != 0 || stepZ != 0)) {
            BlockPos front = foot.offset(stepX, 0, stepZ);
            if (isCliffAhead(client, front) || isDangerBlock(client, front)
                    || isDangerBlock(client, front.above())) {
                setupDetour(client, stepX, stepZ);
                return;
            }
        }

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

        moveToward(client, player, foot, dx, dy, dz);
    }

    private static BlockPos nearestInCluster(Minecraft client, BlockPos foot) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : cluster) {
            double d = p.distSqr(foot);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    private static boolean isAdjacent(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY())
                + Math.abs(a.getZ() - b.getZ()) == 1;
    }

    private static BlockPos pickDigTarget(Minecraft client, BlockPos foot, int dx, int dy, int dz) {
        int stepX = Integer.signum(dx);
        int stepZ = Integer.signum(dz);

        if (stepX != 0 || stepZ != 0) {
            BlockPos front = foot.offset(stepX, 0, stepZ);
            if (mineableSafe(client, front)) return front;
            BlockPos headFront = front.above();
            if (mineableSafe(client, headFront)) return headFront;
            // 不再挖 foot 前方的脚下方块，避免平坦地面自掘坑洞导致卡脚/坠落
            return null;
        }

        if (dy > 1) {
            BlockPos head1 = foot.above(1);
            if (mineableSafe(client, head1)) return head1;
            BlockPos head2 = foot.above(2);
            if (mineableSafe(client, head2)) return head2;
            return null;
        }
        if (dy < -1) {
            Direction dir = pickDescendDir(client, foot);
            if (dir != null) {
                BlockPos fb = foot.offset(dir.getStepX(), -1, dir.getStepZ());
                if (mineableSafe(client, fb)) return fb;
            }
            BlockPos below = foot.below();
            if (mineableSafe(client, below) && safeLanding(client, below.below())) return below;
            return null;
        }
        return null;
    }

    private static Direction pickDescendDir(Minecraft client, BlockPos foot) {
        for (Direction dir : HORIZONTALS) {
            BlockPos fb = foot.offset(dir.getStepX(), -1, dir.getStepZ());
            BlockState s = client.level.getBlockState(fb);
            if (s.isAir() || mineableSafe(client, fb)) {
                if (safeLanding(client, fb.below())) {
                    return dir;
                }
            }
        }
        return null;
    }

    private static void moveToward(Minecraft client, LocalPlayer player, BlockPos foot, int dx, int dy, int dz) {
        int stepX = Integer.signum(dx);
        int stepZ = Integer.signum(dz);
        if (stepX == 0 && stepZ == 0) {
            if (dy > 0) {
                client.options.keyJump.setDown(true);
                client.options.keyUp.setDown(false);
            } else if (dy < 0) {
                Direction dir = pickDescendDir(client, foot);
                if (dir != null) {
                    lookAtDir(player, dir);
                    client.options.keyUp.setDown(true);
                    client.options.keyJump.setDown(false);
                } else {
                    releaseMove(client);
                }
            }
            return;
        }
        lookAtHorizontal(player, foot.offset(stepX, 0, stepZ));
        client.options.keyUp.setDown(true);
        // 遇到 1 格高台阶时自动跳跃攀上，避免频繁绕路
        BlockPos front = foot.offset(stepX, 0, stepZ);
        boolean stepUp = client.level.getBlockState(front).isAir()
                && client.level.getBlockState(front.above()).isAir()
                && client.level.getBlockState(front.below()).isCollisionShapeFullBlock(client.level, front.below());
        client.options.keyJump.setDown(stepUp);
    }

    private static void dig(Minecraft client, BlockPos pos) {
        LocalPlayer player = client.player;
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
        Vec3 eye = player.getEyePosition();
        Vec3 c = Vec3.atCenterOf(pos);
        Direction dir = Direction.getNearest(c.x - eye.x, c.y - eye.y, c.z - eye.z);
        client.gameMode.continueDestroyBlock(pos, dir);
    }

    private static void lookAtHorizontal(LocalPlayer player, BlockPos aim) {
        Vec3 eye = player.getEyePosition();
        Vec3 target = new Vec3(aim.getX() + 0.5, eye.y, aim.getZ() + 0.5);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
    }

    private static void lookAtDir(LocalPlayer player, Direction dir) {
        Vec3 eye = player.getEyePosition();
        Vec3 t = eye.add(dir.getStepX() * 3.0, 0, dir.getStepZ() * 3.0);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, t);
    }

    private static boolean mineable(Minecraft client, BlockPos pos) {
        if (blacklist.contains(pos)) return false;
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        return state.getDestroyProgress(client.player, client.level, pos) > 0.0f;
    }

    private static boolean mineableSafe(Minecraft client, BlockPos pos) {
        return mineable(client, pos) && !hasAdjacentFluid(client, pos);
    }

    private static boolean isDangerBlock(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir()) return false;
        Block b = state.getBlock();
        if (b == Blocks.BEDROCK) return true;
        if (isGravityBlock(state)) return true;
        return !state.getFluidState().isEmpty();
    }

    private static boolean isCliffAhead(Minecraft client, BlockPos front) {
        BlockState f = client.level.getBlockState(front);
        if (!f.isAir()) return false;
        BlockState fb = client.level.getBlockState(front.below());
        return fb.isAir();
    }

    private static boolean isGravityBlock(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.RED_SAND;
    }

    private static boolean safeLanding(Minecraft client, BlockPos pos) {
        BlockState s = client.level.getBlockState(pos);
        if (s.isAir()) return false;
        if (!s.getFluidState().isEmpty()) return false;
        return s.isCollisionShapeFullBlock(client.level, pos);
    }

    private static boolean hasAdjacentFluid(Minecraft client, BlockPos pos) {
        for (Direction d : Direction.values()) {
            net.minecraft.world.level.material.FluidState fs = client.level.getBlockState(pos.relative(d)).getFluidState();
            if (!fs.isEmpty() && (fs.is(FluidTags.LAVA) || fs.is(FluidTags.WATER))) {
                return true;
            }
        }
        return false;
    }

    private static void setupDetour(Minecraft client, int stepX, int stepZ) {
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
        LocalPlayer player = client.player;
        if (player == null) return;
        BlockPos foot = player.blockPosition();
        if (stepX == 0 && stepZ == 0) {
            stepX = 1;
            stepZ = 0;
        }
        int sx = stepZ;
        int sz = -stepX;
        waypoint = foot.offset(sx * DETOUR_DISTANCE, 0, sz * DETOUR_DISTANCE);
    }

    private static void startScan(Minecraft client) {
        ModConfig cfg = ModConfig.get();
        int range = Math.max(8, Math.min(cfg.autoMineRange, 64));
        BlockPos foot = client.player.blockPosition();
        int bottom = client.level.getMinBuildHeight();
        int top = client.level.getMaxBuildHeight();
        int halfDown = foot.getY() - bottom;
        int halfUp = Math.min(range, top - foot.getY() - 1);

        scanMinX = foot.getX() - range;
        scanMaxX = foot.getX() + range;
        scanMinZ = foot.getZ() - range;
        scanMaxZ = foot.getZ() + range;
        scanMinY = foot.getY() - halfDown;
        scanMaxY = foot.getY() + halfUp;

        scanCursor = new BlockPos.MutableBlockPos(scanMinX, scanMinY, scanMinZ);
        scanFound.clear();
        setState("扫描矿石中…");
    }

    private static void tickScan(Minecraft client) {
        for (int i = 0; i < SCAN_STEP; i++) {
            if (scanCursor == null) return;
            if (!blacklist.contains(scanCursor)) {
                if (XRayFeature.isOre(client.level.getBlockState(scanCursor))) {
                    scanFound.add(scanCursor.immutable());
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

    private static void finishScan(Minecraft client) {
        scanCursor = null;
        List<BlockPos> found = new ArrayList<>(scanFound);
        scanFound.clear();

        if (found.isEmpty()) {
            setState("附近没有目标矿石");
            return;
        }

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
                    BlockPos nb = cur.relative(d);
                    if (!visited.contains(nb) && XRayFeature.isOre(client.level.getBlockState(nb))) {
                        visited.add(nb);
                        queue.add(nb);
                    }
                }
            }
            clusters.add(cl);
        }

        BlockPos foot = client.player.blockPosition();
        List<BlockPos> bestCluster = null;
        double bestDist = Double.MAX_VALUE;
        for (List<BlockPos> cl : clusters) {
            for (BlockPos p : cl) {
                double d = p.distSqr(foot);
                if (d < bestDist) {
                    bestDist = d;
                    bestCluster = cl;
                }
            }
        }

        cluster.clear();
        if (bestCluster != null) {
            cluster.addAll(bestCluster);
            cluster.sort((a, b) -> Double.compare(a.distSqr(foot), b.distSqr(foot)));
            setState("目标矿簇: " + describeOre(client, cluster.get(0)) + "（共 " + cluster.size() + " 个）");
        }
    }

    private static void handlePickup(Minecraft client, LocalPlayer player) {
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
            client.options.keyUp.setDown(true);
            lookAtHorizontal(player, item.blockPosition());
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

    private static boolean isGone(Minecraft client, BlockPos pos) {
        return client.level.getBlockState(pos).isAir();
    }

    private static boolean ensurePickaxe(Minecraft client) {
        LocalPlayer player = client.player;
        Inventory inv = player.getInventory();
        ItemStack held = inv.getSelected();
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
            if (player.containerMenu != player.inventoryMenu) return false;
            client.gameMode.handleInventoryMouseClick(
                    player.inventoryMenu.containerId, bestSlot, hotbar, ClickType.SWAP, player);
            setSelectedSlot(client, hotbar);
        }
        return true;
    }

    private static int findBestPickaxe(Inventory inv) {
        int bestSlot = -1;
        int bestLevel = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.items.get(i);
            if (s.getItem() instanceof PickaxeItem pItem) {
                if (isBroken(s)) continue;
                int lvl = pItem.getTier().getLevel();
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
        return s.getDamageValue() >= s.getMaxDamage() - 1;
    }

    private static int findEmptyHotbar(Inventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.items.get(i).isEmpty()) return i;
        }
        return -1;
    }

    private static void setSelectedSlot(Minecraft client, int slot) {
        LocalPlayer player = client.player;
        if (player.getInventory().selected != slot) {
            player.getInventory().selected = slot;
            if (client.getConnection() != null) {
                client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            }
        }
    }

    private static boolean placeBlockAt(Minecraft client, BlockPos pos) {
        LocalPlayer player = client.player;
        Inventory inv = player.getInventory();
        MultiPlayerGameMode im = client.gameMode;
        if (im == null) return false;

        ResourceKey<Level> dim = client.level.dimension();
        Block[] priority;
        if (dim == Level.NETHER) {
            priority = new Block[]{Blocks.NETHERRACK, Blocks.SOUL_SOIL, Blocks.SOUL_SAND};
        } else if (player.blockPosition().getY() < 0) {
            priority = new Block[]{Blocks.COBBLED_DEEPSLATE, Blocks.COBBLESTONE};
        } else {
            priority = new Block[]{Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE};
        }

        int slot = -1;
        outer:
        for (Block b : priority) {
            Item item = b.asItem();
            for (int i = 0; i < 36; i++) {
                if (inv.items.get(i).is(item)) {
                    slot = i;
                    break outer;
                }
            }
        }
        if (slot < 0) return false;

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockState ns = client.level.getBlockState(neighbor);
            if (ns.isAir()) continue;
            if (!ns.getFluidState().isEmpty()) continue;
            if (!ns.isCollisionShapeFullBlock(client.level, neighbor)) continue;

            Direction side = dir.getOpposite();
            Vec3 hitPos = Vec3.atCenterOf(neighbor).add(
                    side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
            BlockHitResult hit = new BlockHitResult(hitPos, side, neighbor, false);

            int hotbarSlot = slot;
            boolean fromMain = slot >= 9;
            if (fromMain) {
                hotbarSlot = findEmptyHotbar(inv);
                if (hotbarSlot < 0) return false;
                if (player.containerMenu != player.inventoryMenu) return false;
                im.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, hotbarSlot, ClickType.SWAP, player);
            }

            int original = inv.selected;
            if (inv.selected != hotbarSlot) {
                inv.selected = hotbarSlot;
            }
            im.useItemOn(player, InteractionHand.MAIN_HAND, hit);

            if (fromMain) {
                im.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, hotbarSlot, ClickType.SWAP, player);
            }
            if (inv.selected != original) {
                inv.selected = original;
                if (client.getConnection() != null) {
                    client.getConnection().send(new ServerboundSetCarriedItemPacket(original));
                }
            }
            return true;
        }
        return false;
    }

    private static ItemEntity findNearestItem(Minecraft client, BlockPos pos) {
        if (pos == null) return null;
        AABB box = new AABB(pos).inflate(PICKUP_RADIUS);
        List<ItemEntity> items = client.level.getEntitiesOfClass(ItemEntity.class, box);
        if (items.isEmpty()) return null;
        Vec3 eye = client.player.getEyePosition();
        ItemEntity best = items.get(0);
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity it : items) {
            double d = it.position().distanceToSqr(eye);
            if (d < bestDist) {
                bestDist = d;
                best = it;
            }
        }
        return best;
    }

    private static String describeOre(Minecraft client, BlockPos pos) {
        return client.level.getBlockState(pos).getBlock().getName().getString()
                + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static void setState(String s) {
        if (!s.equals(lastState)) {
            lastState = s;
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("§7[自动挖矿] §f" + s), true);
            }
        }
    }

    private static void releaseMove(Minecraft client) {
        if (client.options == null) return;
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private static void releaseAll(Minecraft client) {
        releaseMove(client);
        if (client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
    }
}
