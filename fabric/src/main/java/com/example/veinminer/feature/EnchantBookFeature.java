package com.example.veinminer.feature;

import com.example.veinminer.VeinMinerMod;
import com.example.veinminer.config.ModConfig;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.HopperScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自动刷附魔书：自动破坏/放置讲台，让图书管理员村民反复重置交易，
 * 直到刷出目标附魔书（及等级）为止。单机与服务器通用。
 *
 * 状态机：BREAK → BREAKING(仅服务器) → WAIT_LOSE → PLACE → WAIT_CLAIM
 *         → OPEN_TRADE(仅服务器)/CHECKING(仅单机) → 命中停止 / 未命中回到 BREAK
 *
 * 单机与服务器两处差异：
 * - 破坏讲台：单机走服务端 tryBreakBlock（瞬挖+掉落）；服务器走客户端
 *   attackBlock/updateBlockBreakingProgress 发包（需玩家手持斧头，空手太慢）。
 * - 读交易：单机在服务端线程读 getOffers()（不闪界面）；服务器必须右键村民
 *   打开交易界面读 MerchantScreenHandler.getRecipes()（会闪界面）。
 *
 * 硬性前提（原版机制，无法绕过）：
 * - 村民必须「从未交易过」：经验为 0 且等级 <= 1，否则破坏讲台不会令其失去职业；
 *   表现为破坏讲台后职业始终不变 NONE，本功能会超时停止。
 * - 村民与讲台需在 AI 认领范围内（约 48 方块）。
 * - 破坏讲台会掉落讲台，玩家需站在旁边自动拾取，放置时需要背包里有讲台。
 *
 * 交互（默认 V 键）：第一次对准村民按标记，第二次对准讲台按开始，刷取中按停止。
 */
public class EnchantBookFeature {

    private enum Phase {
        IDLE,
        BREAK,       // 发起破坏讲台
        BREAKING,    // 服务器路径：持续挖讲台直到变空气
        WAIT_LOSE,   // 等待村民失去职业（职业变 NONE）
        PLACE,       // 放置讲台
        WAIT_CLAIM,  // 等待村民重新认领为图书管理员
        OPEN_TRADE,  // 服务器路径：右键村民打开交易界面
        WAIT_SCREEN, // 服务器路径：等待交易界面就绪
        CHECKING,    // 单机路径：等待服务端读交易回传
        HOPPER_OPEN, // 服务器路径：打开漏斗界面取讲台
        HOPPER_TAKE, // 服务器路径：等待讲台转移到背包
        HOPPER_PLACE // 服务器路径：潜行放置讲台到漏斗上方
    }

    private static final int LOSE_TIMEOUT = 300;   // 等待失去职业超时（tick，约 15 秒）
    private static final int CLAIM_TIMEOUT = 300;  // 等待认领超时（tick）
    private static final int SCREEN_TIMEOUT = 40;  // 等待交易界面就绪超时（tick）
    private static final int PICKUP_TIMEOUT = 100; // 等待拾取掉落讲台超时（tick，约 5 秒）
    private static final double MAX_DISTANCE = 48.0;

    private static volatile boolean active = false;
    private static Phase phase = Phase.IDLE;
    private static UUID villagerUuid = null;
    private static int villagerEntityId = -1;
    private static BlockPos lecternPos = null;
    private static String targetEnchantId = "";
    private static int targetLevel = 0;
    private static int waitTicks = 0;
    private static int rounds = 0;
    private static boolean isSingleplayer = false;

    // 漏斗支持：讲台正下方放漏斗接住掉落讲台，放置时从漏斗取回
    private static BlockPos hopperPos = null;

    // 单机读交易回传（跨客户端/服务端线程）
    private static volatile boolean queryPending = false;
    private static volatile boolean replyReady = false;
    private static volatile boolean replyFound = false;

    // 单机从漏斗取讲台回传（跨客户端/服务端线程）
    private static volatile boolean placeInFlight = false;
    private static volatile boolean placeReplyReady = false;
    private static volatile boolean placeReplyOk = false;

    public static void init() {
    }

    public static void onTick(MinecraftClient client) {
        if (VeinMinerMod.ENCHANT_BOOK_KEY.wasPressed()) {
            handleKey(client);
            return;
        }

        if (!active) {
            return;
        }
        if (client.player == null || client.world == null) {
            stop(client, "世界已关闭");
            return;
        }
        if (!ModConfig.get().enableEnchantBook) {
            stop(client, "功能已关闭");
            return;
        }

        // 单机读交易回传
        if (isSingleplayer && replyReady) {
            boolean found = replyFound;
            replyReady = false;
            queryPending = false;
            onCheckResult(client, found);
        }
        // 单机从漏斗取讲台回传
        if (isSingleplayer && placeReplyReady) {
            boolean ok = placeReplyOk;
            placeReplyReady = false;
            placeInFlight = false;
            onPlaceResult(client, ok);
        }
        if (!active) {
            return;
        }

        advance(client);
    }

    private static void handleKey(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return;
        }
        if (active) {
            stop(client, "已停止刷取");
            return;
        }

        // 尚未标记村民
        if (villagerUuid == null) {
            Entity target = client.targetedEntity;
            if (target instanceof VillagerEntity) {
                villagerUuid = target.getUuid();
                villagerEntityId = target.getId();
                client.player.sendMessage(Text.literal("§a[附魔书刷取] 已标记村民，请对准讲台再按一次 " +
                        VeinMinerMod.ENCHANT_BOOK_KEY.getBoundKeyLocalizedText().getString()), false);
            } else {
                client.player.sendMessage(Text.literal("§e[附魔书刷取] 请先对准一个村民再按"), false);
            }
            return;
        }

        // 已标记村民：检测讲台
        HitResult hit = client.crosshairTarget;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            client.player.sendMessage(Text.literal("§e[附魔书刷取] 请对准讲台方块"), false);
            return;
        }
        BlockPos pos = bhr.getBlockPos();
        if (!client.world.getBlockState(pos).isOf(Blocks.LECTERN)) {
            client.player.sendMessage(Text.literal("§e[附魔书刷取] 请对准讲台方块"), false);
            return;
        }

        Entity e = client.world.getEntityById(villagerEntityId);
        if (e instanceof VillagerEntity v) {
            double d = v.squaredDistanceTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (d > MAX_DISTANCE * MAX_DISTANCE) {
                client.player.sendMessage(Text.literal("§e[附魔书刷取] 讲台离村民太远（超过 48 格），请靠近后再试"), false);
                return;
            }
        }

        lecternPos = pos.toImmutable();
        // 检测讲台正下方是否有漏斗（用于接住掉落讲台）
        hopperPos = null;
        BlockPos below = pos.down();
        if (client.world.getBlockState(below).isOf(Blocks.HOPPER)) {
            hopperPos = below.toImmutable();
        }
        String rawTarget = ModConfig.get().enchantBookTargetId;
        targetEnchantId = EnchantBookFeature.resolveEnchantId(rawTarget);
        if (targetEnchantId == null) {
            targetEnchantId = "minecraft:mending"; // 无法解析时兜底
        }
        targetLevel = ModConfig.get().enchantBookTargetLevel;
        isSingleplayer = client.getServer() != null;
        phase = Phase.BREAK;
        rounds = 0;
        waitTicks = 0;
        replyReady = false;
        queryPending = false;
        active = true;

        String levelText = targetLevel > 0 ? (" " + targetLevel + " 级") : "（任意等级）";
        client.player.sendMessage(Text.literal("§a[附魔书刷取] 开始刷取 " +
                displayName(targetEnchantId).getString() + levelText), false);
    }

    /** 统一客户端驱动状态机。 */
    private static void advance(MinecraftClient client) {
        ClientPlayerInteractionManager im = client.interactionManager;
        World world = client.world;
        if (im == null) {
            return;
        }

        switch (phase) {
            case BREAK: {
                if (isSingleplayer) {
                    // 服务端瞬挖（异步，不等待，直接进入等待失去职业）
                    issueServerBreak(client);
                    phase = Phase.WAIT_LOSE;
                    waitTicks = 0;
                } else {
                    if (world.getBlockState(lecternPos).isAir()) {
                        phase = Phase.WAIT_LOSE;
                        waitTicks = 0;
                    } else {
                        im.attackBlock(lecternPos, Direction.UP);
                        phase = Phase.BREAKING;
                    }
                }
                break;
            }
            case BREAKING: {
                if (world.getBlockState(lecternPos).isAir()) {
                    im.cancelBlockBreaking();
                    phase = Phase.WAIT_LOSE;
                    waitTicks = 0;
                } else {
                    im.updateBlockBreakingProgress(lecternPos, Direction.UP);
                }
                break;
            }
            case WAIT_LOSE: {
                VillagerEntity v = findVillagerClient(client);
                if (v == null) {
                    stop(client, "找不到已标记的村民，请重新标记");
                    return;
                }
                if (v.getVillagerData().getProfession() == VillagerProfession.NONE) {
                    phase = Phase.PLACE;
                    waitTicks = 0;
                } else {
                    waitTicks++;
                    if (waitTicks > LOSE_TIMEOUT) {
                        stop(client, "村民长时间未失去职业，可能已交易过（经验/等级非初始）");
                    }
                }
                break;
            }
            case PLACE: {
                if (isSingleplayer && hopperPos != null) {
                    // 单机漏斗路径：等掉落物被漏斗吸入后，从漏斗取讲台放回漏斗上方
                    if (placeInFlight) {
                        break; // 等服务端回传
                    }
                    waitTicks++;
                    if (waitTicks > PICKUP_TIMEOUT) {
                        stop(client, "漏斗里没有讲台（请确认漏斗在讲台正下方且能吸入掉落物）");
                        break;
                    }
                    if (waitTicks % 5 == 0) {
                        issueHopperPlace(client);
                    }
                } else if (!isSingleplayer && hopperPos != null) {
                    // 服务器漏斗路径：开漏斗界面 → shift 取讲台到背包 → 潜行放置
                    if (waitTicks == 0) {
                        im.interactBlock(client.player, Hand.MAIN_HAND,
                                new BlockHitResult(Vec3d.ofCenter(hopperPos), Direction.UP, hopperPos, false));
                        phase = Phase.HOPPER_OPEN;
                    }
                    waitTicks++;
                    if (waitTicks > PICKUP_TIMEOUT) {
                        stop(client, "漏斗里没有讲台（请确认漏斗在讲台正下方且能吸入掉落物）");
                    }
                } else {
                    if (placeLectern(client)) {
                        phase = Phase.WAIT_CLAIM;
                        waitTicks = 0;
                    } else {
                        // 破坏讲台后掉落物可能有拾取延迟，先等待捡起，超时才报错
                        waitTicks++;
                        if (waitTicks > PICKUP_TIMEOUT) {
                            stop(client, "背包里没有讲台（请靠近拾取破坏掉落的讲台）");
                        }
                    }
                }
                break;
            }
            case HOPPER_OPEN: {
                // 等待漏斗界面打开
                if (client.player.currentScreenHandler instanceof HopperScreenHandler hsh) {
                    int lecternSlot = -1;
                    for (int i = 0; i < 5; i++) {
                        if (hsh.getSlot(i).getStack().isOf(Items.LECTERN)) {
                            lecternSlot = i;
                            break;
                        }
                    }
                    if (lecternSlot < 0) {
                        // 漏斗里还没有讲台，等一会再看
                        client.player.closeHandledScreen();
                        phase = Phase.PLACE;
                        waitTicks = 0;
                    } else {
                        // shift 点击讲台，转移到背包
                        im.clickSlot(hsh.syncId, lecternSlot, 0, SlotActionType.QUICK_MOVE, client.player);
                        client.player.closeHandledScreen();
                        phase = Phase.HOPPER_TAKE;
                        waitTicks = 0;
                    }
                } else {
                    waitTicks++;
                    if (waitTicks > SCREEN_TIMEOUT) {
                        stop(client, "打开漏斗界面超时，请靠近漏斗后重试");
                    }
                }
                break;
            }
            case HOPPER_TAKE: {
                // 等待讲台转移到背包，然后潜行放置
                if (hasLecternInInventory(client)) {
                    // 潜行放置讲台到漏斗上方（潜行可绕过漏斗 GUI）
                    ClientCommandC2SPacket.Mode mode = ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY;
                    // 直接通过 sendPacket 潜行，避免改动玩家视觉状态
                    sendSneak(client, true);
                    placeLectern(client);
                    sendSneak(client, false);
                    phase = Phase.WAIT_CLAIM;
                    waitTicks = 0;
                } else {
                    waitTicks++;
                    if (waitTicks > PICKUP_TIMEOUT) {
                        stop(client, "讲台未转移到背包");
                    }
                }
                break;
            }
            case HOPPER_PLACE:
                // 预留
                break;
            case WAIT_CLAIM: {
                VillagerEntity v = findVillagerClient(client);
                if (v == null) {
                    stop(client, "找不到已标记的村民");
                    return;
                }
                if (v.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN) {
                    if (isSingleplayer) {
                        issueServerCheck(client);
                        phase = Phase.CHECKING;
                    } else {
                        im.interactEntity(client.player, v, Hand.MAIN_HAND);
                        phase = Phase.WAIT_SCREEN;
                        waitTicks = 0;
                    }
                } else {
                    waitTicks++;
                    if (waitTicks > CLAIM_TIMEOUT) {
                        stop(client, "村民长时间未认领讲台，请把村民放到讲台附近");
                    }
                }
                break;
            }
            case WAIT_SCREEN: {
                if (client.player.currentScreenHandler instanceof MerchantScreenHandler msh) {
                    if (checkOffers(msh.getRecipes())) {
                        playFoundSound(client);
                        stop(client, "已刷到目标附魔书「" + displayName(targetEnchantId).getString() +
                                "」！请直接交易锁定");
                    } else {
                        rounds++;
                        client.player.closeHandledScreen();
                        int maxRounds = ModConfig.get().enchantBookMaxRounds;
                        if (maxRounds > 0 && rounds >= maxRounds) {
                            stop(client, "已刷 " + rounds + " 轮仍未命中，已停止");
                        } else {
                            phase = Phase.BREAK;
                        }
                    }
                } else {
                    waitTicks++;
                    if (waitTicks > SCREEN_TIMEOUT) {
                        stop(client, "打开交易界面超时，请靠近村民后重试");
                    }
                }
                break;
            }
            case CHECKING:
                // 等待服务端读交易回传（onTick 中消费）
                break;
            default:
                break;
        }
    }

    private static void onCheckResult(MinecraftClient client, boolean found) {
        if (!active) {
            return;
        }
        if (found) {
            playFoundSound(client);
            stop(client, "已刷到目标附魔书「" + displayName(targetEnchantId).getString() +
                    "」！请右键村民交易锁定");
        } else {
            rounds++;
            int maxRounds = ModConfig.get().enchantBookMaxRounds;
            if (maxRounds > 0 && rounds >= maxRounds) {
                stop(client, "已刷 " + rounds + " 轮仍未命中，已停止");
            } else {
                phase = Phase.BREAK;
            }
        }
    }

    /** 单机：在服务端线程瞬挖讲台（异步，不等待）。 */
    private static void issueServerBreak(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        if (server == null) {
            return;
        }
        final RegistryKey<World> dk = client.world.getRegistryKey();
        final BlockPos p = lecternPos;
        final UUID pid = client.player.getUuid();
        server.execute(() -> {
            ServerWorld sw = server.getWorld(dk);
            if (sw == null) {
                return;
            }
            ServerPlayerEntity spe = server.getPlayerManager().getPlayer(pid);
            if (spe == null) {
                return;
            }
            if (sw.getBlockState(p).isOf(Blocks.LECTERN)) {
                spe.interactionManager.tryBreakBlock(p);
            }
        });
    }

    /** 单机：在服务端线程读村民交易并回传匹配结果。 */
    private static void issueServerCheck(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        if (server == null) {
            return;
        }
        final RegistryKey<World> dk = client.world.getRegistryKey();
        final UUID vid = villagerUuid;
        queryPending = true;
        server.execute(() -> {
            ServerWorld sw = server.getWorld(dk);
            boolean found = false;
            if (sw != null && vid != null) {
                Entity e = sw.getEntity(vid);
                if (e instanceof VillagerEntity v) {
                    found = checkOffers(v.getOffers());
                }
            }
            replyFound = found;
            queryPending = false;
            replyReady = true;
        });
    }

    /** 放置讲台：切槽到背包里的讲台，interactBlock 放置，再切回。 */
    private static boolean placeLectern(MinecraftClient client) {
        ClientPlayerInteractionManager im = client.interactionManager;
        if (im == null || client.player == null) {
            return false;
        }
        PlayerInventory inv = client.player.getInventory();
        int slot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.main.get(i);
            if (!stack.isEmpty() && stack.isOf(Items.LECTERN)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return false;
        }

        BlockPos groundPos = lecternPos.down();
        BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(groundPos), Direction.UP, groundPos, false);

        int original = inv.selectedSlot;
        inv.selectedSlot = slot;
        im.interactBlock(client.player, Hand.MAIN_HAND, hit);
        if (inv.selectedSlot != original) {
            inv.selectedSlot = original;
        }
        return true;
    }

    /** 检查玩家背包里是否有讲台。 */
    private static boolean hasLecternInInventory(MinecraftClient client) {
        if (client.player == null) {
            return false;
        }
        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.main.get(i);
            if (!stack.isEmpty() && stack.isOf(Items.LECTERN)) {
                return true;
            }
        }
        return false;
    }

    /** 发送潜行状态包（服务器路径下潜行放置可绕过漏斗 GUI）。 */
    private static void sendSneak(MinecraftClient client, boolean sneak) {
        if (client.player == null) {
            return;
        }
        ClientCommandC2SPacket.Mode mode = sneak
                ? ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY
                : ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY;
        client.player.networkHandler.sendPacket(new ClientCommandC2SPacket(client.player, mode));
    }

    /**
     * 单机：在服务端线程从漏斗取出讲台，放回讲台原位置（漏斗正上方）。
     * 前提：漏斗在 lecternPos.down()，掉落物已被漏斗吸入。
     */
    private static void issueHopperPlace(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        if (server == null || hopperPos == null) {
            return;
        }
        final RegistryKey<World> dk = client.world.getRegistryKey();
        final BlockPos hp = hopperPos;
        final BlockPos lp = lecternPos;
        placeInFlight = true;
        server.execute(() -> {
            ServerWorld sw = server.getWorld(dk);
            boolean ok = false;
            if (sw != null) {
                BlockEntity be = sw.getBlockEntity(hp);
                if (be instanceof HopperBlockEntity hopper) {
                    // 找到漏斗里的讲台
                    int slot = -1;
                    for (int i = 0; i < hopper.size(); i++) {
                        ItemStack s = hopper.getStack(i);
                        if (!s.isEmpty() && s.isOf(Items.LECTERN)) {
                            slot = i;
                            break;
                        }
                    }
                    if (slot >= 0 && sw.getBlockState(lp).isAir()) {
                        // 取 1 个讲台，放回讲台位置（保持原朝向）
                        hopper.removeStack(slot, 1);
                        sw.setBlockState(lp, Blocks.LECTERN.getDefaultState(), Block.NOTIFY_ALL);
                        ok = true;
                    }
                }
            }
            placeReplyOk = ok;
            placeInFlight = false;
            placeReplyReady = true;
        });
    }

    /** 单机漏斗放置结果回传处理。 */
    private static void onPlaceResult(MinecraftClient client, boolean ok) {
        if (!active) {
            return;
        }
        if (ok) {
            phase = Phase.WAIT_CLAIM;
            waitTicks = 0;
        }
        // ok=false 时不推进，advance 里会继续重试直到超时
    }

    /** 命中目标附魔书时播放提示音（客户端本地）。 */
    private static void playFoundSound(MinecraftClient client) {
        if (client.player != null) {
            client.player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }
    }

    private static boolean checkOffers(TradeOfferList offers) {
        for (TradeOffer offer : offers) {
            ItemStack sell = offer.getSellItem();
            if (sell.isOf(Items.ENCHANTED_BOOK)) {
                Map<Enchantment, Integer> enchs = EnchantmentHelper.get(sell);
                for (Map.Entry<Enchantment, Integer> e : enchs.entrySet()) {
                    String id = Registries.ENCHANTMENT.getId(e.getKey()).toString();
                    if (id.equals(targetEnchantId) && (targetLevel == 0 || e.getValue() == targetLevel)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static VillagerEntity findVillagerClient(MinecraftClient client) {
        if (villagerUuid == null || client.world == null) {
            return null;
        }
        Entity e = client.world.getEntityById(villagerEntityId);
        return e instanceof VillagerEntity v ? v : null;
    }

    /** 停止刷取，恢复讲台（若已被破坏），并提示。 */
    private static void stop(MinecraftClient client, String msg) {
        boolean wasActive = active;
        active = false;
        phase = Phase.IDLE;
        villagerUuid = null;
        villagerEntityId = -1;
        queryPending = false;
        replyReady = false;

        if (wasActive && lecternPos != null && client.world != null) {
            final BlockPos p = lecternPos;
            if (client.world.getBlockState(p).isAir()) {
                if (client.getServer() != null) {
                    final MinecraftServer server = client.getServer();
                    final RegistryKey<World> dk = client.world.getRegistryKey();
                    server.execute(() -> {
                        ServerWorld sw = server.getWorld(dk);
                        if (sw != null && sw.getBlockState(p).isAir()) {
                            sw.setBlockState(p, Blocks.LECTERN.getDefaultState(), Block.NOTIFY_ALL);
                        }
                    });
                } else {
                    placeLectern(client);
                }
            }
        }
        lecternPos = null;

        if (client.player != null && msg != null) {
            client.player.sendMessage(Text.literal("§c[附魔书刷取] " + msg), false);
        }
    }

    public static List<String> getAvailableEnchantIds() {
        List<String> ids = new ArrayList<>();
        for (Enchantment e : Registries.ENCHANTMENT) {
            if (e.isAvailableForEnchantedBookOffer()) {
                ids.add(Registries.ENCHANTMENT.getId(e).toString());
            }
        }
        ids.sort(String::compareTo);
        return ids;
    }

    public static Text displayName(String id) {
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) {
            return Text.literal(id);
        }
        Enchantment e = Registries.ENCHANTMENT.get(ident);
        return e == null ? Text.literal(id) : Text.translatable(e.getTranslationKey());
    }

    /**
     * 把下拉框保存的值解析为合法的附魔书 ID。
     * 输入可能是 ID（如 minecraft:mending）或中文显示名（如 耐久），统一返回 ID；
     * 无法解析时返回 null（调用方应保留原值）。
     */
    public static String resolveEnchantId(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        // 已是合法 ID 且存在
        Identifier ident = Identifier.tryParse(input);
        if (ident != null && Registries.ENCHANTMENT.get(ident) != null) {
            return input;
        }
        // 按翻译名（中文名）反查 ID
        for (Enchantment e : Registries.ENCHANTMENT) {
            if (Text.translatable(e.getTranslationKey()).getString().equals(input)) {
                return Registries.ENCHANTMENT.getId(e).toString();
            }
        }
        return null;
    }
}
